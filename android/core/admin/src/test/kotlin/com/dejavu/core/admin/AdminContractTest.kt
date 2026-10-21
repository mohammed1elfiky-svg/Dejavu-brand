package com.dejavu.core.admin

import com.dejavu.core.data.AddressRepository
import com.dejavu.core.data.CartRepository
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.OrderRepository
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.model.AddressRequest
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.PaymentStatus
import com.dejavu.core.model.ReturnStatus
import com.dejavu.core.network.ApiClient
import com.dejavu.core.model.Currency
import com.dejavu.core.network.ApiException
import com.dejavu.core.network.InMemoryTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Staff journey against a running backend with demo data:
 *   DEJAVU_API_URL=http://localhost:3000/api/v1/ ./gradlew -PjvmOnly=true :core:admin:test
 */
class AdminContractTest {
    private val url = System.getenv("DEJAVU_API_URL").orEmpty()

    private fun staff(): Pair<StaffSessionRepository, AdminApi> {
        val tokens = InMemoryTokenStore()
        lateinit var session: StaffSessionRepository
        val (retrofit, _) = ApiClient.retrofit({ url }, tokens, { session.onSessionExpired() })
        val api = retrofit.create(AdminApi::class.java)
        session = StaffSessionRepository(api, tokens)
        return session to api
    }

    @Test
    fun `staff run an order from confirmation to courier settlement, plus back-office tasks`() = runBlocking {
        assumeTrue("Set DEJAVU_API_URL to run contract tests", url.isNotBlank())

        // ---- a customer places a cash-on-delivery order
        val customerTokens = InMemoryTokenStore()
        lateinit var customerSession: SessionRepository
        val (customerApi, _) = ApiClient.create({ url }, customerTokens, { customerSession.onSessionExpired() })
        customerSession = SessionRepository(customerApi, customerTokens)
        val catalog = CatalogRepository(customerApi)
        val cart = CartRepository(customerApi)
        val customerOrders = OrderRepository(customerApi, cart)
        val phone = "012" + (System.currentTimeMillis() % 100_000_000).toString().padStart(8, '0')
        customerSession.register("عميل لوحة الإدارة", phone, "secret123", null).getOrThrow()
        val zone = catalog.shippingZones().getOrThrow().first()
        val address = AddressRepository(customerApi).create(
            AddressRequest(recipient = "عميل", phone = phone, governorate = zone.governorate, city = "المدينة", street = "شارع 1"),
        ).getOrThrow()
        val variant = catalog.products(pageSize = 50).getOrThrow().items
            .flatMap { p -> p.variants.map { it } }.first { it.availableQty >= 3 }
        cart.add(variant.id, 2).getOrThrow()
        val placed = customerOrders.checkout(address.id, PaymentMethod.CASH_ON_DELIVERY, null).getOrThrow()

        // ---- staff sign in
        val (session, api) = staff()
        assertEquals("ok", session.ping().getOrThrow().status)
        val profile = session.login("ADMIN@dejavu.local ", "ChangeMe!2026").getOrThrow()
        assertTrue(profile.can(Perm.ORDERS_UPDATE))
        val dashboard = DashboardRepository(api).dashboard().getOrThrow()
        assertTrue(dashboard.today.orders >= 1)

        // ---- order lifecycle
        val orders = AdminOrderRepository(api)
        val pending = orders.list(OrderStatus.PENDING, placed.number).getOrThrow()
        val orderId = pending.items.single().id
        var order = orders.get(orderId).getOrThrow()
        assertEquals(listOf(OrderStatus.CONFIRMED, OrderStatus.CANCELLED), order.nextStatuses)
        orders.changeStatus(orderId, OrderStatus.CONFIRMED).getOrThrow()
        val carrier = "Contract-${System.currentTimeMillis() % 100000}"
        order = orders.changeStatus(orderId, OrderStatus.SHIPPED, carrier = carrier, tracking = "TRK-1").getOrThrow()
        assertTrue(order.journalEntries.isNotEmpty())
        assertEquals(order.grandTotal, order.shipments.single().codAmount)
        order = orders.changeStatus(orderId, OrderStatus.DELIVERED).getOrThrow()
        assertEquals(PaymentStatus.PAID, order.paymentStatus)
        assertEquals(0, order.balanceDue.toBigDecimal().signum())

        // ---- the customer returns one piece; staff approve, receive and refund
        customerOrders.requestReturn(placed.number, "المقاس غير مناسب", mapOf(order.items.single().id to 1)).getOrThrow()
        val returns = AdminReturnRepository(api)
        val requested = returns.list(ReturnStatus.REQUESTED.name).getOrThrow().items.first { it.order.number == placed.number }
        returns.approve(requested.id).getOrThrow()
        val received = returns.receive(requested.id, emptyMap()).getOrThrow()
        assertEquals(ReturnStatus.RECEIVED, received.status)
        assertEquals(received.refundAmount, received.refundDue)
        assertEquals(ReturnStatus.REFUNDED, returns.refund(requested.id, PaymentMethod.CASH).getOrThrow().status)

        // ---- the courier pays over the cash it collected
        val finance = FinanceRepository(api)
        val bank = finance.cashAccounts().first { it.code == "1102" }
        val cod = finance.pendingCod(carrier).getOrThrow()
        assertEquals(1, cod.shipments.size)
        val settlement = finance.settle(carrier, cod.shipments.map { it.id }, bank.id, "10", "PAYOUT-1").getOrThrow()
        assertEquals(order.grandTotal.toBigDecimal().subtract("10".toBigDecimal()), settlement.netAmount.toBigDecimal())

        // ---- inventory
        val inventory = InventoryRepository(api)
        val warehouse = inventory.warehouses().getOrThrow().first { it.isDefault }
        val level = inventory.levels(variant.sku).getOrThrow().items.first { it.variantId == variant.id }
        val adjusted = inventory.adjust(warehouse.id, AdjustmentReason.OTHER_IN, variant.id, 1, "contract test").getOrThrow()
        assertTrue(adjusted.number.startsWith("ADJ-"))
        assertEquals(level.onHand + 1, inventory.levels(variant.sku).getOrThrow().items.first { it.variantId == variant.id }.onHand)
        inventory.lowStock().getOrThrow()
        val product = inventory.products().getOrThrow().items.first()
        val toggled = inventory.updateProduct(product.id, UpdateProductRequest(isFeatured = !product.isFeatured)).getOrThrow()
        assertEquals(!product.isFeatured, toggled.isFeatured)
        inventory.updateProduct(product.id, UpdateProductRequest(isFeatured = product.isFeatured)).getOrThrow()

        // ---- expenses
        val expenses = ExpenseRepository(api)
        val category = expenses.categories().getOrThrow().first()
        val draft = expenses.create(ExpenseRequest(category.id, "125.50", LocalDate.now().toString(), "اختبار مصروف", payee = "مورد")).getOrThrow()
        val paid = expenses.approve(draft.id, finance.cashAccounts().first { it.code == "1101" }.id).getOrThrow()
        assertEquals(ExpenseStatus.PAID, paid.status)
        assertTrue(paid.journalEntries.isNotEmpty())
        assertTrue(expenses.list().getOrThrow().items.any { it.id == draft.id })

        // ---- purchasing (demo data has a partly received order)
        val purchasing = PurchasingRepository(api)
        val supplier = purchasing.suppliers().getOrThrow().items.first()
        val open = purchasing.orders(PurchaseOrderStatus.PARTIALLY_RECEIVED).getOrThrow().items.firstOrNull()
        if (open != null) {
            val po = purchasing.order(open.id).getOrThrow()
            val line = po.items.first { it.remaining > 0 }
            val after = purchasing.receive(po.id, mapOf(line.id to 1)).getOrThrow()
            assertEquals(line.receivedQty + 1, after.items.first { it.id == line.id }.receivedQty)
            purchasing.pay(after.supplier.id!!, PaymentMethod.BANK_TRANSFER, "1", po.id, "contract").getOrThrow()
        }
        assertNotNull(supplier.balance)

        // ---- reports & customers
        val reports = DashboardRepository(api)
        reports.incomeStatement().getOrThrow()
        assertTrue(reports.balanceSheet().getOrThrow().balanced)
        assertTrue(reports.sales("product").getOrThrow().groups.isNotEmpty())
        val customer = finance.customers(phone).getOrThrow().items.single()
        assertEquals(1, finance.customer(customer.id).getOrThrow().lifetime.orders)

        session.logout()
        assertTrue(session.state.value is StaffSession.LoggedOut)
    }

    @Test
    fun `a storekeeper sees stock but not orders`() = runBlocking {
        assumeTrue("Set DEJAVU_API_URL to run contract tests", url.isNotBlank())
        val (session, api) = staff()
        val profile = session.login("store@dejavu.local", "Demo!2026").getOrThrow()
        assertTrue(profile.can(Perm.STOCK_READ))
        assertTrue(!profile.can(Perm.ORDERS_READ))
        InventoryRepository(api).levels().getOrThrow()
        val denied = AdminOrderRepository(api).list().exceptionOrNull() as ApiException
        assertEquals(403, denied.status)

        val wrong = staff().first.login("store@dejavu.local", "wrong-password").exceptionOrNull() as ApiException
        assertEquals(401, wrong.status)
    }

    @Test
    fun `admin creates a product with sizes, colors and stock, then edits it`() = runBlocking {
        assumeTrue("Set DEJAVU_API_URL to run contract tests", url.isNotBlank())
        val (session, api) = staff()
        session.login("admin@dejavu.local", "ChangeMe!2026").getOrThrow()
        val repo = InventoryRepository(api)
        val run = System.currentTimeMillis().toString(36)

        val category = repo.createCategory("قسم $run", "Section $run").getOrThrow()
        assertTrue(repo.categories().getOrThrow().any { it.first.id == category.id })

        val slug = VariantPlanner.slugOf("Contract Shirt $run")
        val planned = VariantPlanner.plan(slug, listOf("M", "L"), listOf("أسود"))
        val created = repo.createProduct(
            CreateProductRequest(
                categoryId = category.id,
                nameAr = "قميص $run",
                nameEn = "Contract Shirt $run",
                slug = slug,
                basePrice = "99.5",
                status = ProductStatus.ACTIVE,
                variants = planned.mapIndexed { i, p ->
                    NewVariantRequest(p.sku, p.size, p.color, p.colorHex, initialQuantity = if (i == 0) 4 else null, unitCost = if (i == 0) "30" else null)
                },
            ),
        ).getOrThrow()
        assertEquals(planned.map { it.sku }.toSet(), created.variants.map { it.sku }.toSet())
        assertEquals(4, created.variants.first { it.size == "M" }.onHand)
        assertEquals("#000000", created.variants.first().colorHex)

        val red = repo.addVariant(created.id, NewVariantRequest("${VariantPlanner.skuBase(slug)}-M-RED", "M", "أحمر", initialQuantity = 2, unitCost = "31")).getOrThrow()
        assertEquals("M · أحمر", red.title)
        repo.updateVariant(red.id, UpdateVariantRequest(price = "105", isActive = false)).getOrThrow()

        val edited = repo.updateProduct(created.id, UpdateProductRequest(nameAr = "قميص معدّل $run", descriptionAr = "وصف", compareAtPrice = "120")).getOrThrow()
        assertEquals("قميص معدّل $run", edited.nameAr)
        assertEquals("وصف", edited.descriptionAr)
        val withImages = repo.setImages(created.id, listOf("https://example.com/a.jpg")).getOrThrow()
        assertEquals(listOf("https://example.com/a.jpg"), withImages.images.map { it.url })

        val reloaded = repo.product(created.id).getOrThrow()
        val redNow = reloaded.variants.first { it.id == red.id }
        assertEquals(2, redNow.onHand)
        assertTrue(!redNow.isActive)
    }

    @Test
    fun `admin reads and sets the YER rate`() = runBlocking {
        assumeTrue("Set DEJAVU_API_URL to run contract tests", url.isNotBlank())
        val (session, api) = staff()
        session.login("admin@dejavu.local", "ChangeMe!2026").getOrThrow()
        val finance = FinanceRepository(api)
        val before = finance.exchangeRates().getOrThrow()
        assertEquals(Currency.SAR, before.base)
        val old = before.rate(Currency.YER)!!.rate
        val updated = finance.setRate(Currency.YER, "151.25", "contract test").getOrThrow()
        assertEquals("151.25", updated.rate(Currency.YER)!!.rate)
        assertEquals("contract test", updated.history.first().note)
        assertEquals("System Admin", updated.history.first().createdBy?.name)
        finance.setRate(Currency.YER, old, "restore").getOrThrow()

        // A storekeeper may not change it.
        val (store, storeApi) = staff()
        store.login("store@dejavu.local", "Demo!2026").getOrThrow()
        val denied = FinanceRepository(storeApi).setRate(Currency.YER, "1", null).exceptionOrNull() as ApiException
        assertEquals(403, denied.status)
    }
}
