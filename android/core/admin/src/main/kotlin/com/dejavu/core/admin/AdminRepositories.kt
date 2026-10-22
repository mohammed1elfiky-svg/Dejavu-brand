package com.dejavu.core.admin

import com.dejavu.core.model.Currency
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
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
    const val SETTINGS_MANAGE = "settings.manage"
    const val POS_SELL = "pos.sell"
    const val CASHBOXES_MANAGE = "cashboxes.manage"
    const val WALLET_MANAGE = "wallet.manage"
    const val PROMOTIONS_MANAGE = "promotions.manage"
    const val CUSTOMERS_WRITE = "customers.write"
    const val USERS_MANAGE = "users.manage"
    const val CATEGORIES_WRITE = "categories.write"
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

    private val _restoreError = MutableStateFlow<String?>(null)

    /** Why the saved session could not be loaded (server unreachable…); null while loading or fine. */
    val restoreError: StateFlow<String?> = _restoreError.asStateFlow()

    suspend fun restore() {
        if (tokens.current() == null) return
        _restoreError.value = null
        apiCall { api.me() }
            .onSuccess { _state.value = StaffSession.LoggedIn(it) }
            .onFailure {
                val e = it as ApiException
                if (e.isUnauthorized) onSessionExpired() else _restoreError.value = e.message
            }
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
        _restoreError.value = null
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

    /** [amount] is in [currency]: SAR, or the order's own currency at its locked rate. */
    suspend fun recordPayment(
        id: String,
        method: PaymentMethod,
        amount: String,
        reference: String? = null,
        accountId: String? = null,
        currency: Currency = Currency.SAR,
    ) = apiCall { api.recordPayment(id, PaymentRequest(method, amount, accountId, reference?.ifBlank { null }, currency = currency.takeIf { !it.isBase })) }

    suspend fun refund(
        id: String,
        method: PaymentMethod,
        amount: String,
        reason: String,
        accountId: String? = null,
        currency: Currency = Currency.SAR,
    ) = apiCall { api.refund(id, PaymentRequest(method, amount, accountId, reason = reason, currency = currency.takeIf { !it.isBase })) }
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

    suspend fun products(query: String? = null, page: Int = 1, categoryId: String? = null, pageSize: Int = 20, brandId: String? = null) =
        apiCall { api.products(query?.trim()?.ifBlank { null }, categoryId = categoryId, page = page, pageSize = pageSize, brandId = brandId) }

    suspend fun brands() = apiCall { api.brands() }
    suspend fun createBrand(name: String, logoUrl: String? = null) = apiCall { api.createBrand(BrandRequest(name = name.trim(), logoUrl = logoUrl)) }

    /** [logoUrl] "" removes the logo, null keeps it. */
    suspend fun updateBrand(id: String, name: String? = null, logoUrl: String? = null, isActive: Boolean? = null) =
        apiCall { api.updateBrand(id, BrandRequest(name = name?.trim(), logoUrl = logoUrl, isActive = isActive)) }

    suspend fun deleteBrand(id: String) = apiCall {
        val res = api.deleteBrand(id)
        if (!res.isSuccessful) throw HttpException(res)
    }

    /** Deletes all test data (admin only); [dryRun] only counts what would go. */
    suspend fun wipeTestData(dryRun: Boolean) = apiCall { api.wipeTestData(WipeRequest(WIPE_PHRASE, dryRun)) }
    suspend fun product(id: String) = apiCall { api.product(id) }
    suspend fun updateProduct(id: String, request: UpdateProductRequest) = apiCall { api.updateProduct(id, request) }

    private var categoriesCache: List<AdminCategory>? = null

    /** Categories as a flat, indented list for pickers. */
    suspend fun categories(): Result<List<Pair<AdminCategory, Int>>> {
        categoriesCache?.let { return Result.success(it.flatMap { c -> c.flatten() }) }
        return apiCall { api.categories() }.onSuccess { categoriesCache = it }.map { it.flatMap { c -> c.flatten() } }
    }

    suspend fun createCategory(nameAr: String, nameEn: String, parentId: String? = null) =
        apiCall { api.createCategory(CreateCategoryRequest(nameAr.trim(), nameEn.trim(), VariantPlanner.slugOf(nameEn), parentId)) }
            .onSuccess { categoriesCache = null }

    suspend fun createProduct(request: CreateProductRequest) = apiCall { api.createProduct(request) }

    /** Replaces the product's images with [images] (at most 10), in order. */
    suspend fun setImages(id: String, images: List<ImageRequest>) = apiCall { api.replaceImages(id, ImagesRequest(images)) }

    /** Uploads a product video and returns its public URL. */
    suspend fun uploadVideo(bytes: ByteArray, mimeType: String = "video/mp4") = apiCall {
        api.uploadVideo(MultipartBody.Part.createFormData("file", "video.mp4", bytes.toRequestBody(mimeType.toMediaType())))
    }

    /** Stock value at cost and at selling price, per category and overall. */
    suspend fun valuation() = apiCall { api.inventoryValuation() }

    /** Uploads a photo (JPEG bytes) and returns its public URL. */
    suspend fun uploadImage(bytes: ByteArray, mimeType: String = "image/jpeg") = apiCall {
        api.uploadImage(MultipartBody.Part.createFormData("file", "photo.jpg", bytes.toRequestBody(mimeType.toMediaType())))
    }

    suspend fun addVariant(productId: String, request: NewVariantRequest) = apiCall { api.addVariant(productId, request) }
    suspend fun updateVariant(variantId: String, request: UpdateVariantRequest) = apiCall { api.updateVariant(variantId, request) }
}

class PurchasingRepository(private val api: AdminApi) {
    suspend fun suppliers(query: String? = null) = apiCall { api.suppliers(query?.trim()?.ifBlank { null }) }
    suspend fun createSupplier(name: String, phone: String? = null) = apiCall { api.createSupplier(CreateSupplierRequest(name.trim(), phone?.trim()?.ifBlank { null })) }

    /** Buys, receives and (optionally) pays in one step — the till-style purchase screen. */
    suspend fun quickPurchase(request: QuickPurchaseRequest) = apiCall { api.quickPurchase(request) }
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

    suspend fun exchangeRates() = apiCall { api.exchangeRates() }

    /** New rate for orders placed from now on; existing orders keep theirs. */
    suspend fun setRate(currency: Currency, rate: String, note: String?) =
        apiCall { api.setRate(currency, SetRateRequest(rate.trim(), note?.trim()?.ifBlank { null })) }

    suspend fun customers(query: String? = null, page: Int = 1) = apiCall { api.customers(query?.trim()?.ifBlank { null }, page) }
    suspend fun customer(id: String) = apiCall { api.customer(id) }
    suspend fun customerAccount(id: String, page: Int = 1) = apiCall { api.customerAccount(id, page) }

    /** Merchant flag, merchant discount (%) and credit limit (SAR, null = none). */
    suspend fun updateCustomer(id: String, request: UpdateCustomerRequest) = apiCall { api.updateCustomer(id, request) }

    /** Money taken from a customer at the till: pays off their debt, any excess becomes wallet credit. */
    suspend fun customerReceipt(id: String, currency: Currency, amount: String, cashBoxId: String? = null, note: String? = null) =
        apiCall { api.customerReceipt(id, CustomerReceiptRequest(currency, amount.trim(), cashBoxId, note?.ifBlank { null })) }

    // ---- currencies

    /** Every currency (settings screen); registers their names and decimals for display. */
    suspend fun allCurrencies() = apiCall { api.allCurrencies() }.onSuccess { it.register() }

    /** Active currencies with a rate (till, payment dialogs). */
    suspend fun activeCurrencies() = apiCall { api.activeCurrencies() }.onSuccess { it.register() }

    suspend fun createCurrency(request: CurrencyRequest) = apiCall { api.createCurrency(request) }
    suspend fun updateCurrency(code: String, request: CurrencyRequest) = apiCall { api.updateCurrency(code, request) }
    suspend fun rateHistory(code: String) = apiCall { api.rateHistory(code) }
}

/** Employees: accounts, own permissions and the cash boxes they may use. */
class StaffRepository(private val api: AdminApi) {
    suspend fun list() = apiCall { api.staff() }
    suspend fun get(id: String) = apiCall { api.staffUser(id) }
    suspend fun create(request: CreateStaffRequest) = apiCall { api.createStaff(request) }
    suspend fun update(id: String, request: UpdateStaffRequest) = apiCall { api.updateStaff(id, request) }
    suspend fun setPermissions(id: String, keys: List<String>) = apiCall { api.setStaffPermissions(id, PermissionsRequest(keys)) }
    suspend fun setBoxes(id: String, boxIds: List<String>) = apiCall { api.setStaffBoxes(id, CashBoxIdsRequest(boxIds)) }
    suspend fun roles() = apiCall { api.roles() }
}

/** The till: products, scanning, sales and the cashier's shift. */
class PosRepository(private val api: AdminApi) {
    suspend fun products(query: String? = null, categoryId: String? = null) = apiCall { api.posProducts(query?.trim()?.ifBlank { null }, categoryId) }
    suspend fun lookup(code: String) = apiCall { api.posLookup(code.trim()) }

    /** Saves the grid order after a drag and drop (first id shows first). */
    suspend fun arrange(productIds: List<String>) = apiCall {
        val res = api.arrangeProducts(PosArrangeRequest(productIds))
        if (!res.isSuccessful) throw HttpException(res)
    }

    suspend fun sell(request: PosSaleRequest) = apiCall { api.sell(request) }
    suspend fun receipt(id: String) = apiCall { api.posReceipt(id) }

    /** A sale by its receipt number, with what can still be returned of each line. */
    suspend fun findSale(number: String) = apiCall { api.findSale(number.trim()) }

    /** Return (refund to the drawer or the account) or exchange for other pieces. */
    suspend fun returnOrExchange(request: PosReturnRequest) = apiCall { api.posReturn(request) }
    suspend fun shift() = apiCall { api.shift() }
    suspend fun quickCustomer(name: String, phone: String) = apiCall { api.quickCustomer(QuickCustomerRequest(name.trim(), phone.trim())) }
}

/** Drawers, safes and bank boxes; transfers and currency exchange. */
class TreasuryRepository(private val api: AdminApi) {
    suspend fun mine() = apiCall { api.myBoxes() }
    suspend fun all(kind: CashBoxKind? = null, currency: Currency? = null) = apiCall { api.allBoxes(kind?.name, currency?.code) }
    suspend fun safes() = apiCall { api.safes() }
    suspend fun create(name: String, kind: CashBoxKind, currency: Currency) = apiCall { api.createBox(CreateBoxRequest(name.trim(), kind, currency)) }
    suspend fun update(id: String, name: String? = null, isActive: Boolean? = null) = apiCall { api.updateBox(id, UpdateBoxRequest(name, isActive)) }
    suspend fun entries(id: String, page: Int = 1) = apiCall { api.boxEntries(id, page) }

    suspend fun transfer(fromBoxId: String, toBoxId: String, amount: String, toAmount: String? = null, note: String? = null) =
        apiCall { api.transfer(TransferRequest(fromBoxId, toBoxId, amount.trim(), toAmount?.trim()?.ifBlank { null }, note?.ifBlank { null })) }

    suspend fun transfers(exchangesOnly: Boolean = false, page: Int = 1) = apiCall { api.transfers(exchangesOnly.takeIf { it }, page) }
}

/** Wallet top-up requests and the store bank accounts shown to customers. */
class WalletAdminRepository(private val api: AdminApi) {
    suspend fun list(status: com.dejavu.core.model.TopUpStatus? = null, page: Int = 1) = apiCall { api.topUps(status?.name, page) }
    suspend fun get(id: String) = apiCall { api.topUp(id) }
    suspend fun approve(id: String, cashBoxId: String, amount: String? = null) = apiCall { api.approveTopUp(id, ApproveTopUpRequest(cashBoxId, amount?.trim()?.ifBlank { null })) }
    suspend fun reject(id: String, reason: String) = apiCall { api.rejectTopUp(id, ReasonBody(reason.trim())) }

    /** The deposit slip image bytes. */
    suspend fun receipt(fileId: String) = apiCall { api.file(fileId).use { it.bytes() } }

    suspend fun bankAccounts() = apiCall { api.bankAccounts() }
    suspend fun createBankAccount(request: BankAccountRequest) = apiCall { api.createBankAccount(request) }
    suspend fun updateBankAccount(id: String, request: BankAccountRequest) = apiCall { api.updateBankAccount(id, request) }
}

class PromotionRepository(private val api: AdminApi) {
    suspend fun list() = apiCall { api.promotions() }
    suspend fun create(request: PromotionRequest) = apiCall { api.createPromotion(request) }
    suspend fun update(id: String, request: PromotionRequest) = apiCall { api.updatePromotion(id, request) }
    suspend fun delete(id: String) = apiCall {
        val res = api.deletePromotion(id)
        if (!res.isSuccessful) throw HttpException(res)
    }
}
