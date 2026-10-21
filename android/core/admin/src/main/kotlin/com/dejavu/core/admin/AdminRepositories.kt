package com.dejavu.core.admin

import com.dejavu.core.model.DeviceRequest
import com.dejavu.core.model.DeviceToken
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.Page
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.RefreshRequest
import com.dejavu.core.network.ApiException
import com.dejavu.core.network.TokenStore
import com.dejavu.core.network.Tokens
import com.dejavu.core.network.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

/** Permission keys the app checks before showing a screen or an action (mirrors the backend seed). */
object Perm {
    const val DASHBOARD = "dashboard.view"
    const val ORDERS_READ = "orders.read"
    const val ORDERS_UPDATE = "orders.update_status"
    const val ORDERS_CANCEL = "orders.cancel"
    const val RETURNS = "returns.manage"
    const val PAYMENTS_RECORD = "payments.record"
    const val PAYMENTS_READ = "payments.read"
    const val PRODUCTS_READ = "products.read"
    const val PRODUCTS_WRITE = "products.write"
    const val STOCK_READ = "stock.read"
    const val STOCK_ADJUST = "stock.adjust"
    const val PURCHASES_READ = "purchases.read"
    const val PURCHASES_RECEIVE = "purchases.receive"
    const val SUPPLIER_PAY = "supplier_payments.record"
    const val EXPENSES_READ = "expenses.read"
    const val EXPENSES_WRITE = "expenses.write"
    const val EXPENSES_APPROVE = "expenses.approve"
    const val CUSTOMERS_READ = "customers.read"
    const val JOURNAL_READ = "journal.read"
    const val REPORTS_FINANCIAL = "reports.financial"
    const val REPORTS_SALES = "reports.sales"
}

// ----------------------------------------------------------------- session ---

sealed interface StaffSession {
    data object LoggedOut : StaffSession

    /** [profile] is null while it is still loading for a remembered session. */
    data class LoggedIn(val profile: StaffProfile?) : StaffSession {
        fun can(permission: String) = profile?.can(permission) == true
    }
}

class StaffSessionRepository(private val api: AdminApi, private val tokens: TokenStore) {
    private val _state = MutableStateFlow<StaffSession>(
        if (tokens.current() == null) StaffSession.LoggedOut else StaffSession.LoggedIn(null),
    )
    val state: StateFlow<StaffSession> = _state.asStateFlow()

    fun can(permission: String) = (state.value as? StaffSession.LoggedIn)?.can(permission) == true

    /** Sends new-order pushes for this staff member to this phone. */
    suspend fun registerDevice(token: String, appVersion: String?) = apiCall { api.registerDevice(DeviceRequest(token, appVersion)) }

    suspend fun unregisterDevice(token: String) = apiCall { api.unregisterDevice(DeviceToken(token)) }.map { }

    /** Checks that the configured server is reachable and its database is up. */
    suspend fun ping(): Result<Health> = apiCall { api.health() }

    suspend fun restore() {
        if (tokens.current() == null) return
        apiCall { api.me() }
            .onSuccess { _state.value = StaffSession.LoggedIn(it) }
            .onFailure { if ((it as ApiException).isUnauthorized) onSessionExpired() }
    }

    suspend fun login(email: String, password: String): Result<StaffProfile> =
        apiCall { api.login(StaffLoginRequest(email.trim().lowercase(), password)) }.map { auth ->
            tokens.save(Tokens(auth.accessToken, auth.refreshToken))
            _state.value = StaffSession.LoggedIn(auth.user)
            auth.user
        }

    suspend fun logout() {
        tokens.current()?.let { t -> apiCall { api.logout(RefreshRequest(t.refreshToken)) } }
        tokens.save(null)
        _state.value = StaffSession.LoggedOut
    }

    fun onSessionExpired() {
        tokens.save(null)
        _state.value = StaffSession.LoggedOut
    }
}

// --------------------------------------------------------------- business ---

class DashboardRepository(private val api: AdminApi) {
    suspend fun dashboard() = apiCall { api.dashboard() }
    suspend fun incomeStatement(from: String? = null, to: String? = null) = apiCall { api.incomeStatement(from, to) }
    suspend fun balanceSheet() = apiCall { api.balanceSheet() }
    suspend fun sales(groupBy: String, from: String? = null, to: String? = null) = apiCall { api.sales(groupBy, from, to) }
}

class AdminOrderRepository(private val api: AdminApi) {
    suspend fun list(status: OrderStatus? = null, query: String? = null, page: Int = 1): Result<Page<AdminOrderSummary>> =
        apiCall { api.orders(status?.name, query?.trim()?.ifBlank { null }, page) }

    suspend fun get(id: String) = apiCall { api.order(id) }

    suspend fun changeStatus(id: String, status: OrderStatus, note: String? = null, carrier: String? = null, tracking: String? = null) =
        apiCall { api.changeStatus(id, ChangeStatusRequest(status, note?.ifBlank { null }, carrier?.ifBlank { null }, tracking?.ifBlank { null })) }

    suspend fun recordPayment(id: String, method: PaymentMethod, amount: String, reference: String? = null, accountId: String? = null) =
        apiCall { api.recordPayment(id, PaymentRequest(method, amount, accountId, reference?.ifBlank { null })) }

    suspend fun refund(id: String, method: PaymentMethod, amount: String, reason: String, accountId: String? = null) =
        apiCall { api.refund(id, PaymentRequest(method, amount, accountId, reason = reason)) }
}

class AdminReturnRepository(private val api: AdminApi) {
    suspend fun list(status: String? = null, page: Int = 1) = apiCall { api.returns(status, page) }
    suspend fun get(id: String) = apiCall { api.salesReturn(id) }
    suspend fun approve(id: String) = apiCall { api.approveReturn(id) }
    suspend fun reject(id: String, reason: String) = apiCall { api.rejectReturn(id, ReasonBody(reason)) }

    /** [restock]: return-line id → whether the piece can be sold again. */
    suspend fun receive(id: String, restock: Map<String, Boolean>) =
        apiCall { api.receiveReturn(id, ReceiveReturnRequest(restock.map { (itemId, r) -> ReceiveReturnRequest.Restock(itemId, r) })) }

    suspend fun refund(id: String, method: PaymentMethod) = apiCall { api.refundReturn(id, RefundReturnRequest(method)) }
}

class InventoryRepository(private val api: AdminApi) {
    private var warehousesCache: List<Warehouse>? = null

    suspend fun warehouses(): Result<List<Warehouse>> {
        warehousesCache?.let { return Result.success(it) }
        return apiCall { api.warehouses() }.onSuccess { warehousesCache = it }
    }

    suspend fun levels(query: String? = null, warehouseId: String? = null, page: Int = 1) =
        apiCall { api.stockLevels(query?.trim()?.ifBlank { null }, warehouseId, page) }

    suspend fun lowStock() = apiCall { api.lowStock() }

    suspend fun adjust(warehouseId: String, reason: AdjustmentReason, variantId: String, quantity: Int, note: String?) =
        apiCall { api.adjust(AdjustmentRequest(warehouseId, reason, listOf(AdjustmentRequest.Item(variantId, quantity)), note?.ifBlank { null })) }

    suspend fun products(query: String? = null, page: Int = 1) = apiCall { api.products(query?.trim()?.ifBlank { null }, page = page) }
    suspend fun product(id: String) = apiCall { api.product(id) }
    suspend fun updateProduct(id: String, request: UpdateProductRequest) = apiCall { api.updateProduct(id, request) }
}

class PurchasingRepository(private val api: AdminApi) {
    suspend fun suppliers(query: String? = null) = apiCall { api.suppliers(query?.trim()?.ifBlank { null }) }
    suspend fun orders(status: PurchaseOrderStatus? = null, page: Int = 1) = apiCall { api.purchaseOrders(status?.name, page) }
    suspend fun order(id: String) = apiCall { api.purchaseOrder(id) }

    /** [quantities]: purchase-order item id → pieces received now. */
    suspend fun receive(id: String, quantities: Map<String, Int>, note: String? = null) = apiCall {
        api.receive(id, ReceiveRequest(quantities.filterValues { it > 0 }.map { (itemId, q) -> ReceiveRequest.Line(itemId, q) }, note?.ifBlank { null }))
    }

    suspend fun pay(supplierId: String, method: PaymentMethod, amount: String, purchaseOrderId: String? = null, reference: String? = null) = apiCall {
        val res = api.paySupplier(SupplierPaymentRequest(supplierId, method, amount, purchaseOrderId, reference = reference?.ifBlank { null }))
        if (!res.isSuccessful) throw HttpException(res)
    }
}

class ExpenseRepository(private val api: AdminApi) {
    suspend fun categories() = apiCall { api.expenseCategories() }
    suspend fun list(status: ExpenseStatus? = null, page: Int = 1) = apiCall { api.expenses(status?.name, page) }
    suspend fun get(id: String) = apiCall { api.expense(id) }
    suspend fun create(request: ExpenseRequest) = apiCall { api.createExpense(request) }
    suspend fun approve(id: String, paidFromAccountId: String?) = apiCall { api.approveExpense(id, PaidFrom(paidFromAccountId)) }
    suspend fun pay(id: String, paidFromAccountId: String) = apiCall { api.payExpense(id, PaidFrom(paidFromAccountId)) }
    suspend fun cancel(id: String, reason: String) = apiCall { api.cancelExpense(id, ReasonBody(reason)) }
}

class FinanceRepository(private val api: AdminApi) {
    private var cashCache: List<LedgerAccount>? = null

    /** Cash boxes, banks and wallets. Empty if the user may not read the chart of accounts. */
    suspend fun cashAccounts(): List<LedgerAccount> {
        cashCache?.let { return it }
        return apiCall { api.cashAccounts() }.getOrNull()?.also { cashCache = it }.orEmpty()
    }

    suspend fun pendingCod(carrier: String? = null) = apiCall { api.pendingCod(carrier) }

    suspend fun settle(carrier: String, shipmentIds: List<String>, accountId: String, fees: String?, reference: String?) =
        apiCall { api.settle(SettlementRequest(carrier, shipmentIds, accountId, fees?.ifBlank { null }, reference?.ifBlank { null })) }

    suspend fun customers(query: String? = null, page: Int = 1) = apiCall { api.customers(query?.trim()?.ifBlank { null }, page) }
    suspend fun customer(id: String) = apiCall { api.customer(id) }
}
