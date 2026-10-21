package com.dejavu.core.data

import com.dejavu.core.model.AddressRequest
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.network.ApiClient
import com.dejavu.core.network.ApiException
import com.dejavu.core.network.InMemoryTokenStore
import kotlinx.coroutines.runBlocking
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

    @Before fun setUp() {
        assumeTrue("Set DEJAVU_API_URL to run backend contract tests", url.isNotBlank())
        val tokens = InMemoryTokenStore()
        lateinit var s: SessionRepository
        val (api, _) = ApiClient.create(baseUrl = { url }, tokens = tokens, onSessionExpired = { s.onSessionExpired() })
        s = SessionRepository(api, tokens)
        session = s
        catalog = CatalogRepository(api)
        cart = CartRepository(api)
        addresses = AddressRepository(api)
        orders = OrderRepository(api, cart)
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
}
