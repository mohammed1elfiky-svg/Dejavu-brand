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

    /** [login]: the employee's mobile number (or username). */
    suspend fun login(login: String, password: String): Result<StaffProfile> =
        apiCall { api.login(StaffLoginRequest(login.trim().lowercase().replace(" ", ""), password)) }.map { auth ->
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

    /** Revenue of till and app sales, retail and merchants (dates yyyy-MM-dd; null = this month). */
    suspend fun revenue(from: String? = null, to: String? = null) = apiCall { api.revenue(from, to) }

    /** Each cashier's invoices, revenue, discounts, deleted invoices and shift over/short. */
    suspend fun staff(from: String? = null, to: String? = null) = apiCall { api.staffReport(from, to) }

    /** Best sellers with profit, and dead stock (no sale for [deadDays]). */
    suspend fun products(from: String? = null, to: String? = null, deadDays: Int? = null, limit: Int? = null) = apiCall { api.productsReport(from, to, deadDays, limit) }

    /** Pieces to order again so the shelf lasts [coverDays], from the last [days] of sales. */
    suspend fun reorder(days: Int? = null, coverDays: Int? = null) = apiCall { api.reorderReport(days, coverDays) }

    suspend fun dailySummary() = apiCall { api.dailySummary() }
    suspend fun dailySummarySettings() = apiCall { api.dailySummarySettings() }
    suspend fun saveDailySummarySettings(s: DailySummarySettings) = apiCall { api.saveDailySummarySettings(s) }
    suspend fun sendDailySummary() = apiCall { api.sendDailySummary() }
}

class AdminOrderRepository(private val api: AdminApi) {
    suspend fun list(status: OrderStatus? = null, query: String? = null, page: Int = 1): Result<Page<AdminOrderSummary>> =
        apiCall { api.orders(status?.name, query?.trim()?.ifBlank { null }, page) }

    suspend fun get(id: String) = apiCall { api.order(id) }

    /** Photo of the shipping receipt (سند الإرسال): the customer is notified and sees it on the order. */
    suspend fun sendShippingReceipt(id: String, bytes: ByteArray, mimeType: String = "image/jpeg") = apiCall {
        api.shippingReceipt(id, MultipartBody.Part.createFormData("file", "receipt.jpg", bytes.toRequestBody(mimeType.toMediaType())))
    }

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
    suspend fun wipeTestData(dryRun: Boolean, scope: WipeScope = WipeScope.ALL) = apiCall { api.wipeTestData(WipeRequest(WIPE_PHRASE, dryRun, scope)) }
    suspend fun product(id: String) = apiCall { api.product(id) }
    suspend fun updateProduct(id: String, request: UpdateProductRequest) = apiCall { api.updateProduct(id, request) }

    private var categoriesCache: List<AdminCategory>? = null

    /** Categories as a flat, indented list for pickers. */
    suspend fun categories(): Result<List<Pair<AdminCategory, Int>>> {
        categoriesCache?.let { return Result.success(it.flatMap { c -> c.flatten() }) }
        return apiCall { api.categories() }.onSuccess { categoriesCache = it }.map { it.flatMap { c -> c.flatten() } }
    }

    /** Renames a category or shows / hides it in the customer app. */
    suspend fun updateCategory(id: String, request: UpdateCategoryRequest) = apiCall { api.updateCategory(id, request) }.onSuccess { categoriesCache = null }

    suspend fun createCategory(nameAr: String, nameEn: String, parentId: String? = null) =
        apiCall { api.createCategory(CreateCategoryRequest(nameAr.trim(), nameEn.trim(), VariantPlanner.slugOf(nameEn), parentId)) }
            .onSuccess { categoriesCache = null }

    suspend fun createProduct(request: CreateProductRequest) = apiCall { api.createProduct(request) }

    // stock counts (جرد): count the shelf by scanning, then post the differences
    suspend fun stockCounts(page: Int = 1) = apiCall { api.stockCounts(page) }
    suspend fun stockCount(id: String) = apiCall { api.stockCount(id) }
    suspend fun createStockCount(request: CreateStockCountRequest) = apiCall { api.createStockCount(request) }
    suspend fun postStockCount(id: String) = apiCall { api.postStockCount(id) }
    suspend fun deleteStockCount(id: String) = apiCall {
        val res = api.deleteStockCount(id)
        if (!res.isSuccessful) throw HttpException(res)
    }

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
    suspend fun suppliers(query: String? = null, kind: SupplierKind? = null) = apiCall { api.suppliers(query?.trim()?.ifBlank { null }, kind = kind?.name) }
    suspend fun createSupplier(name: String, phone: String? = null, commissionPercent: String? = null, kind: SupplierKind = SupplierKind.SUPPLIER) =
        apiCall { api.createSupplier(CreateSupplierRequest(name.trim(), phone?.trim()?.ifBlank { null }, commissionPercent?.trim()?.ifBlank { null }, kind)) }

    /** Photo of a supplier's invoice; send the returned url as invoiceImageUrl. Read it back with [file]. */
    suspend fun uploadInvoiceImage(bytes: ByteArray, mimeType: String = "image/jpeg") = apiCall {
        api.uploadPrivate(MultipartBody.Part.createFormData("file", "invoice.jpg", bytes.toRequestBody(mimeType.toMediaType())))
    }

    /** A private file (invoice photo) by its id, read with the staff token. */
    suspend fun file(id: String) = apiCall { api.file(id).bytes() }
    suspend fun supplier(id: String) = apiCall { api.supplier(id) }
    suspend fun updateSupplier(id: String, request: UpdateSupplierRequest) = apiCall { api.updateSupplier(id, request) }

    /** Pieces ordered from the supplier and not received yet. */
    suspend fun pendingItems(supplierId: String) = apiCall { api.pendingItems(supplierId) }

    /** Import order priced in the supplier's currency (new items become drafts held for it). */
    suspend fun createImport(request: ImportOrderRequest) = apiCall { api.createImport(request) }

    /** Landed cost per piece for the counted quantities and CBM, without saving. */
    suspend fun importCosts(id: String, request: ImportReceiveRequest) = apiCall { api.importCosts(id, request) }

    /** "Receive and confirm matching": stock in at the landed cost. */
    suspend fun importReceive(id: String, request: ImportReceiveRequest) = apiCall { api.importReceive(id, request) }

    /** Changes an import order that hasn't arrived (lines, notes, inland shipping). */
    suspend fun updateImport(id: String, request: UpdateImportOrderRequest) = apiCall { api.updateImport(id, request) }

    /** Deletes an order nothing was received for: it leaves the supplier account; new items made for it are archived. */
    suspend fun cancelOrder(id: String) = apiCall { api.cancelPurchaseOrder(id) }

    /** A supplier balance from before the system. */
    suspend fun supplierOpeningBalance(supplierId: String, request: SupplierOpeningBalanceRequest) = apiCall { api.supplierOpeningBalance(supplierId, request) }

    /** The supplier's own account in yuan / dollars, with vouchers. */
    suspend fun supplierAccount(supplierId: String) = apiCall { api.supplierAccount(supplierId) }

    /** Payment voucher (new, or a correction of [editId]). */
    suspend fun paymentVoucher(request: SupplierPaymentVoucherRequest, editId: String? = null) =
        apiCall { if (editId == null) api.paymentVoucher(request) else api.editPaymentVoucher(editId, request) }

    /** Commission in USD for a period (new, or a correction of [editId]). */
    suspend fun commissionVoucher(request: SupplierCommissionRequest, editId: String? = null) =
        apiCall { if (editId == null) api.commissionVoucher(request) else api.editCommissionVoucher(editId, request) }

    suspend fun cancelVoucher(id: String) = apiCall { api.cancelVoucher(id) }

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
    suspend fun customerReceipt(id: String, currency: Currency, amount: String, cashBoxId: String? = null, note: String? = null, sendSms: Boolean = false) =
        apiCall { api.customerReceipt(id, CustomerReceiptRequest(currency, amount.trim(), cashBoxId, note?.ifBlank { null }, sendSms.takeIf { it })) }

    /** Exchanges [amount] of [from] in the customer's account for [toAmount] of [to]. */
    suspend fun customerExchange(id: String, from: Currency, amount: String, to: Currency, toAmount: String, note: String? = null, sendSms: Boolean = false) =
        apiCall { api.customerExchange(id, CustomerExchangeRequest(from, amount.trim(), to, toAmount.trim(), note?.trim()?.ifBlank { null }, sendSms.takeIf { it })) }

    /** Balance from before the system: [owes] = the customer owes the store, else the store holds their money. */
    suspend fun openingBalance(id: String, currency: Currency, amount: String, owes: Boolean, note: String? = null) =
        apiCall { api.openingBalance(id, OpeningBalanceRequest(currency, amount.trim(), if (owes) "OWES" else "CREDIT", note?.trim()?.ifBlank { null })) }

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

    /** Return or exchange without the invoice: returned pieces picked by hand. */
    suspend fun returnWithoutInvoice(request: FreeReturnRequest) = apiCall { api.posFreeReturn(request) }

    /** New app orders and top-up requests since [since] (ISO time); polled for alerts. */
    suspend fun alerts(since: String? = null) = apiCall { api.alerts(since) }
    suspend fun shift() = apiCall { api.shift() }

    /** Till sales of a day (yyyy-MM-dd, null = today): your own, or everyone's with [all] (managers). */
    suspend fun sales(date: String? = null, all: Boolean = false) = apiCall { api.posSales(date, all.takeIf { it }) }

    /** Deletes a sale: money, stock and accounts are put back. */
    suspend fun voidSale(id: String, reason: String? = null) = apiCall { api.voidSale(id, VoidSaleRequest(reason?.trim()?.ifBlank { null })) }

    /** Corrects a sale: it is deleted and [request] rung up in its place; returns the new receipt. */
    suspend fun editSale(id: String, request: PosSaleRequest) = apiCall { api.editSale(id, request) }

    /** Photo of a deposit / transfer slip; send its id as PosSaleRequest.slipFileId. */
    suspend fun uploadSlip(bytes: ByteArray, mimeType: String = "image/jpeg") = apiCall {
        api.uploadPrivate(MultipartBody.Part.createFormData("file", "slip.jpg", bytes.toRequestBody(mimeType.toMediaType())))
    }

    // shifts
    suspend fun currentShift() = apiCall { api.currentShift() }.map { it.shift }
    suspend fun openShift(request: OpenShiftRequest) = apiCall { api.openShift(request) }
    suspend fun closeShift(request: CloseShiftRequest, shiftId: String? = null) =
        apiCall { if (shiftId == null) api.closeShift(request) else api.closeShiftOf(shiftId, request) }
    suspend fun shifts(page: Int = 1, userId: String? = null) = apiCall { api.shifts(page, userId) }

    /** The signed-in employee's own checkout layout and sale currency. */
    suspend fun settings() = apiCall { api.posSettings() }
    suspend fun saveSettings(settings: PosSettings) = apiCall { api.savePosSettings(settings) }

    suspend fun quickCustomer(name: String, phone: String) = apiCall { api.quickCustomer(QuickCustomerRequest(name.trim(), phone.trim())) }
}

/** Drawers, safes and bank boxes; transfers and currency exchange. */
class TreasuryRepository(private val api: AdminApi) {
    suspend fun mine() = apiCall { api.myBoxes() }
    suspend fun all(kind: CashBoxKind? = null, currency: Currency? = null) = apiCall { api.allBoxes(kind?.name, currency?.code) }
    suspend fun safes() = apiCall { api.safes() }
    suspend fun create(name: String, kind: CashBoxKind, currency: Currency, icon: String? = null, imageUrl: String? = null) =
        apiCall { api.createBox(CreateBoxRequest(name.trim(), kind, currency, icon?.ifBlank { null }, imageUrl?.ifBlank { null })) }

    /** [icon] / [imageUrl]: null keeps them, "" removes them. */
    suspend fun update(id: String, name: String? = null, isActive: Boolean? = null, icon: String? = null, imageUrl: String? = null) =
        apiCall { api.updateBox(id, UpdateBoxRequest(name, isActive, icon, imageUrl)) }
    suspend fun entries(id: String, page: Int = 1) = apiCall { api.boxEntries(id, page) }

    suspend fun transfer(fromBoxId: String, toBoxId: String, amount: String, toAmount: String? = null, note: String? = null) =
        apiCall { api.transfer(TransferRequest(fromBoxId, toBoxId, amount.trim(), toAmount?.trim()?.ifBlank { null }, note?.ifBlank { null })) }

    suspend fun transfers(exchangesOnly: Boolean = false, page: Int = 1) = apiCall { api.transfers(exchangesOnly.takeIf { it }, page) }

    /** Cancels a transfer or exchange (reversed in the books). */
    suspend fun cancelTransfer(id: String, reason: String? = null) = apiCall { api.cancelTransfer(id, CancelTransferRequest(reason?.trim()?.ifBlank { null })) }

    /** Corrects a transfer or exchange: the old one is cancelled and this one recorded. */
    suspend fun editTransfer(id: String, fromBoxId: String, toBoxId: String, amount: String, toAmount: String? = null, note: String? = null) =
        apiCall { api.editTransfer(id, TransferRequest(fromBoxId, toBoxId, amount.trim(), toAmount?.trim()?.ifBlank { null }, note?.ifBlank { null })) }
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

/** Texts sent from this phone's SIM (the store phone is the SMS gateway). */
class SmsRepository(private val api: AdminApi) {
    suspend fun claim(limit: Int = 10) = apiCall { api.claimSms(limit) }
    suspend fun report(id: String, ok: Boolean, error: String? = null) = apiCall {
        val res = api.smsResult(id, SmsResultRequest(ok, error?.take(300)))
        if (!res.isSuccessful) throw HttpException(res)
    }
    suspend fun recent(status: String? = null) = apiCall { api.smsList(status) }
    suspend fun settings() = apiCall { api.smsSettings() }
    suspend fun setRequireOtp(on: Boolean) = apiCall { api.updateSmsSettings(SmsSettingsRequest(requirePhoneOtp = on)) }
}

/** Discount codes for the app and the till. */
class CouponRepository(private val api: AdminApi) {
    suspend fun list() = apiCall { api.coupons() }
    suspend fun create(request: CouponRequest) = apiCall { api.createCoupon(request) }
    suspend fun update(id: String, request: CouponRequest) = apiCall { api.updateCoupon(id, request) }
}

/** Who changed what (audit log), customers who owe, and whole-database backups. */
class SystemRepository(private val api: AdminApi) {
    suspend fun audit(page: Int = 1, entityType: String? = null, userId: String? = null, from: String? = null, to: String? = null) =
        apiCall { api.auditLogs(page, 30, entityType, userId, from, to) }
    suspend fun auditTypes() = apiCall { api.auditTypes() }

    suspend fun debtors(minDays: Int? = null) = apiCall { api.debtors(minDays) }
    suspend fun remind(customerIds: List<String>) = apiCall { api.remindDebtors(DebtRemindersRequest(customerIds)) }

    /** The whole database as a gzipped JSON file; the caller copies the stream where it wants it. */
    suspend fun backup() = apiCall { api.backup() }

    /** Replaces all data with [bytes] (a backup file). [confirm] must be the restore phrase. */
    suspend fun restore(bytes: ByteArray, confirm: String) = apiCall {
        api.restore(
            MultipartBody.Part.createFormData("file", "backup.json.gz", bytes.toRequestBody("application/gzip".toMediaType())),
            confirm.toRequestBody("text/plain".toMediaType()),
        )
    }

    companion object {
        /** Typed to confirm a restore (as the server expects). */
        const val RESTORE_PHRASE = "استعادة النسخة الاحتياطية"
    }
}
