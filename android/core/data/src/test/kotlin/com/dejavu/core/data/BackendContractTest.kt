package com.dejavu.core.data

import com.dejavu.core.model.AddressRequest
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.PaymentStatus
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.network.ApiClient
import com.dejavu.core.network.ApiException
import com.dejavu.core.network.DejavuApi
import com.dejavu.core.network.InMemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the whole customer journey with the real client against a running
 * backend loaded with demo data, so model/API drift fails fast.
 *
 *   DEJAVU_API_URL=http://localhost:3000/api/v1/ ./gradlew -PjvmOnly=true :core:data:test
 */
class BackendContractTest {
    private val url = System.getenv("DEJAVU_API_URL").orEmpty()
    private lateinit var session: SessionRepository
    private lateinit var catalog: CatalogRepository
    private lateinit var cart: CartRepository
    private lateinit var addresses: AddressRepository
    private lateinit var orders: OrderRepository
    private lateinit var notifications: NotificationRepository
    private lateinit var http: OkHttpClient
    private lateinit var api: DejavuApi

    @Before fun setUp() {
        assumeTrue("Set DEJAVU_API_URL to run backend contract tests", url.isNotBlank())
        val tokens = InMemoryTokenStore()
        lateinit var s: SessionRepository
        val (dejavuApi, client) = ApiClient.create(baseUrl = { url }, tokens = tokens, onSessionExpired = { s.onSessionExpired() })
        api = dejavuApi
        http = client
        s = SessionRepository(api, tokens)
        session = s
        catalog = CatalogRepository(api)
        cart = CartRepository(api)
        addresses = AddressRepository(api)
        orders = OrderRepository(api, cart)
        notifications = NotificationRepository(api)
    }

    /** Signs up a fresh customer with a deliverable address and one piece in the cart. */
    private suspend fun shopperWithCart(): String {
        val product = catalog.product(catalog.products(featured = true).getOrThrow().items.first { it.inStock }.slug).getOrThrow()
        val zone = catalog.shippingZones().getOrThrow().first()
        val phone = "015" + ((System.nanoTime() / 1000) % 100_000_000).toString().padStart(8, '0')
        session.register("عميل البطاقة", phone, "secret123", null).getOrThrow()
        val address = addresses.create(
            AddressRequest(recipient = "عميل البطاقة", phone = phone, governorate = zone.governorate, city = "المدينة", street = "شارع 2"),
        ).getOrThrow()
        cart.add(product.variants.first { it.availableQty >= 1 }.id, 1).getOrThrow()
        return address.id
    }

    @Test
    fun `customer journey from sign-up to cancelled order`() = runBlocking {
        // Catalog is public
        val categories = catalog.categories().getOrThrow()
        assertTrue(categories.isNotEmpty())
        val featured = catalog.products(featured = true).getOrThrow()
        assertTrue(featured.items.isNotEmpty())
        val product = catalog.product(featured.items.first { it.inStock }.slug).getOrThrow()
        val variant = product.variants.first { it.availableQty >= 1 }
        val zones = catalog.shippingZones().getOrThrow()

        // Sign up with a fresh number
        val phone = "015" + (System.currentTimeMillis() % 100_000_000).toString().padStart(8, '0')
        val bad = session.register("ع", phone, "short", null).exceptionOrNull() as ApiException
        assertEquals(400, bad.status)
        val me = session.register("عميل اختبار", phone, "secret123", null).getOrThrow()
        assertEquals(phone, me.phone)
        assertTrue(session.isLoggedIn)

        // Address in a governorate we deliver to
        val address = addresses.create(
            AddressRequest(recipient = "عميل اختبار", phone = phone, governorate = zones.first().governorate, city = "المدينة", street = "شارع الاختبار 1"),
        ).getOrThrow()
        assertTrue(address.isDefault)

        // Cart with coupon and shipping
        cart.add(variant.id, 1).getOrThrow()
        cart.applyCoupon("WELCOME10").getOrThrow()
        val quote = cart.refresh(address.id).getOrThrow()
        assertTrue(quote.canCheckout)
        assertEquals("WELCOME10", quote.couponCode)
        assertEquals(zones.first().fee.toBigDecimal(), quote.totals.shippingFee.toBigDecimal())
        assertEquals(1, cart.cart.value!!.itemCount)

        // Checkout, track, cancel
        val order = orders.checkout(address.id, PaymentMethod.CASH_ON_DELIVERY, "اتصل قبل الوصول").getOrThrow()
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(quote.totals.grandTotal.toBigDecimal(), order.grandTotal.toBigDecimal())
        assertEquals(0, cart.cart.value!!.itemCount)
        assertTrue(orders.orders().getOrThrow().items.any { it.number == order.number })

        val cancelled = orders.cancel(order.number, "طلبت بالخطأ").getOrThrow()
        assertEquals(OrderStatus.CANCELLED, cancelled.status)
        assertEquals(listOf(OrderStatus.PENDING, OrderStatus.CANCELLED), cancelled.statusHistory.map { it.toStatus })
        assertTrue(orders.returns().getOrThrow().isEmpty())

        session.logout()
        assertTrue(!session.isLoggedIn)
        val afterLogout = orders.orders().exceptionOrNull() as ApiException
        assertEquals(401, afterLogout.status)
    }

    @Test
    fun `demo customer sees orders with full details`() = runBlocking {
        session.login("0100 000 0001", "Demo!2026").getOrThrow()
        val list = orders.orders().getOrThrow()
        assertTrue(list.items.isNotEmpty())
        for (summary in list.items) {
            val detail = orders.order(summary.number).getOrThrow()
            assertEquals(summary.number, detail.number)
            assertTrue(detail.items.isNotEmpty())
        }
        val delivered = list.items.firstOrNull { it.status == OrderStatus.DELIVERED }
        if (delivered != null) assertTrue(orders.order(delivered.number).getOrThrow().statusHistory.size >= 3)
        orders.returns().getOrThrow() // decodes the demo returns
        Unit
    }

    @Test
    fun `card order paid on the test gateway, with notifications`() = runBlocking {
        assertTrue(PaymentMethod.CARD in orders.paymentMethods())
        val addressId = shopperWithCart()
        val order = orders.checkout(addressId, PaymentMethod.CARD, null).getOrThrow()
        assertTrue(order.canPayByCard)

        val checkout = orders.payByCard(order.number).getOrThrow()
        assertEquals(order.number, checkout.orderNumber)
        assertEquals(order.grandTotal.toBigDecimal(), checkout.amount.toBigDecimal())

        // What the customer does in the browser: press "pay" on the test card page.
        val page = checkout.checkoutUrl.toHttpUrl()
        val form = FormBody.Builder().add("sig", page.queryParameter("sig")!!).add("outcome", "success").build()
        http.newCall(Request.Builder().url(page.newBuilder().query(null).build()).post(form).build()).execute().use {
            assertEquals(200, it.code)
        }

        val paid = orders.order(order.number).getOrThrow()
        assertEquals(PaymentStatus.PAID, paid.paymentStatus)
        assertEquals(OrderStatus.CONFIRMED, paid.status)
        assertTrue(!paid.canPayByCard)

        val inbox = notifications.page().getOrThrow()
        assertTrue(inbox.items.any { it.orderNumber == order.number && it.title.contains("الدفع") })
        assertEquals(inbox.unread, notifications.unread.value)
        assertTrue(inbox.unread >= 2)
        notifications.markRead(inbox.items.first().id).getOrThrow()
        assertEquals(inbox.unread - 1, notifications.unread.value)
        notifications.markAllRead().getOrThrow()
        assertEquals(0, notifications.unread.value)

        val token = "contract-test-token-${System.nanoTime()}-abcdefghij"
        notifications.registerDevice(token, "test").getOrThrow()
        notifications.unregisterDevice(token).getOrThrow()
        session.logout()
    }

    @Test
    fun `shops in YER at the published rate, and the order keeps that rate`() = runBlocking {
        var saved = Currency.SAR
        val currencies = CurrencyRepository(api, { saved }, { saved = it })
        val rates = currencies.refresh().getOrThrow()
        assertEquals(Currency.SAR, rates.base)
        val yer = rates.rate(Currency.YER)!!.rate
        currencies.select(Currency.YER)
        assertEquals(Currency.YER, saved)
        assertEquals(Currency.YER, Money.display.currency)
        try {
            val addressId = shopperWithCart()
            val quote = cart.refresh(addressId).getOrThrow()
            val order = orders.checkout(addressId, PaymentMethod.CASH_ON_DELIVERY, null, currencies.checkoutCurrency()).getOrThrow()
            assertEquals(Currency.YER, order.currency)
            assertEquals(yer.toBigDecimal(), order.exchangeRate.toBigDecimal())
            // What the cart showed is what the invoice says.
            assertEquals(Money.format(quote.totals.grandTotal), order.format(order.grandTotal))
            val listed = orders.orders().getOrThrow().items.first { it.number == order.number }
            assertEquals(order.format(order.grandTotal), listed.format(listed.grandTotal))
            orders.cancel(order.number, "تجربة العملة").getOrThrow()
        } finally {
            currencies.select(Currency.SAR)
            session.logout()
        }
        Unit
    }
}
