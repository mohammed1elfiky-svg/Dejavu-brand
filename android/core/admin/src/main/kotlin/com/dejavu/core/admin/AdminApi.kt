package com.dejavu.core.admin

import com.dejavu.core.model.Currency
import com.dejavu.core.model.DeviceRegistration
import com.dejavu.core.model.DeviceRequest
import com.dejavu.core.model.DeviceToken
import com.dejavu.core.model.Page
import com.dejavu.core.model.RefreshRequest
import com.dejavu.core.model.CurrencyRate
import com.dejavu.core.model.Currencies
import com.dejavu.core.model.StoreBankAccount
import com.dejavu.core.model.UploadedFile
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Staff endpoints used by the admin app. Paths are relative to `.../api/v1/`. */
interface AdminApi {
    @GET("health") suspend fun health(): Health

    // auth
    @POST("auth/staff/login") suspend fun login(@Body body: StaffLoginRequest): StaffAuthResponse
    @GET("auth/me") suspend fun me(): StaffProfile
    @POST("auth/logout") suspend fun logout(@Body body: RefreshRequest): Response<Unit>
    @POST("admin/devices") suspend fun registerDevice(@Body body: DeviceRequest): DeviceRegistration
    @HTTP(method = "DELETE", path = "admin/devices", hasBody = true) suspend fun unregisterDevice(@Body body: DeviceToken): Response<Unit>

    // dashboard & reports
    @GET("admin/reports/dashboard") suspend fun dashboard(): Dashboard
    @GET("admin/home/search") suspend fun homeSearch(@Query("q") q: String): HomeSearch
    @GET("admin/home/badges") suspend fun badges(): HomeBadges
    @GET("admin/home/daily-sales") suspend fun dailySales(@Query("days") days: Int = 7): DailySales
    @GET("admin/reports/income-statement") suspend fun incomeStatement(@Query("from") from: String?, @Query("to") to: String?): IncomeStatement
    @GET("admin/reports/balance-sheet") suspend fun balanceSheet(@Query("asOf") asOf: String? = null): BalanceSheet
    @GET("admin/reports/products") suspend fun productsReport(@Query("from") from: String?, @Query("to") to: String?, @Query("deadDays") deadDays: Int? = null, @Query("limit") limit: Int? = null): ProductsReport
    @GET("admin/reports/reorder") suspend fun reorderReport(@Query("days") days: Int? = null, @Query("coverDays") coverDays: Int? = null): ReorderReport
    @GET("admin/reports/daily-summary") suspend fun dailySummary(): DailySummaryText
    @GET("admin/reports/daily-summary/settings") suspend fun dailySummarySettings(): DailySummarySettings
    @PUT("admin/reports/daily-summary/settings") suspend fun saveDailySummarySettings(@Body body: DailySummarySettings): DailySummarySettings
    @POST("admin/reports/daily-summary/send") suspend fun sendDailySummary(): DailySummaryText
    @GET("admin/audit-logs") suspend fun auditLogs(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 30,
        @Query("entityType") entityType: String? = null,
        @Query("userId") userId: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): Page<AuditEntry>
    @GET("admin/audit-logs/types") suspend fun auditTypes(): List<AuditType>
    @GET("admin/debtors") suspend fun debtors(@Query("minDays") minDays: Int? = null): Debtors
    @POST("admin/debtors/reminders") suspend fun remindDebtors(@Body body: DebtRemindersRequest): DebtRemindersResult
    @retrofit2.http.Streaming @GET("admin/system/backup") suspend fun backup(): ResponseBody
    @Multipart @POST("admin/system/restore") suspend fun restore(@Part file: MultipartBody.Part, @Part("confirm") confirm: okhttp3.RequestBody): RestoreResult
    @GET("admin/reports/staff") suspend fun staffReport(@Query("from") from: String?, @Query("to") to: String?): StaffReport
    @GET("admin/reports/revenue") suspend fun revenue(@Query("from") from: String?, @Query("to") to: String?): RevenueReport
    @GET("admin/reports/sales") suspend fun sales(@Query("groupBy") groupBy: String, @Query("from") from: String?, @Query("to") to: String?): SalesReport

    // orders
    @GET("admin/orders") suspend fun orders(
        @Query("status") status: String? = null,
        @Query("q") query: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
    ): Page<AdminOrderSummary>
    @GET("admin/orders/{id}") suspend fun order(@Path("id") id: String): AdminOrderDetail
    @Multipart @POST("admin/orders/{id}/shipping-receipt") suspend fun shippingReceipt(@Path("id") id: String, @Part file: MultipartBody.Part): AdminOrderDetail
    @POST("admin/orders/{id}/status") suspend fun changeStatus(@Path("id") id: String, @Body body: ChangeStatusRequest): AdminOrderDetail
    @POST("admin/orders/{id}/payments") suspend fun recordPayment(@Path("id") id: String, @Body body: PaymentRequest): AdminOrderDetail
    @POST("admin/orders/{id}/refunds") suspend fun refund(@Path("id") id: String, @Body body: PaymentRequest): AdminOrderDetail

    // returns
    @GET("admin/returns") suspend fun returns(@Query("status") status: String? = null, @Query("page") page: Int = 1): Page<AdminReturnSummary>
    @GET("admin/returns/{id}") suspend fun salesReturn(@Path("id") id: String): AdminReturnDetail
    @POST("admin/returns/{id}/approve") suspend fun approveReturn(@Path("id") id: String): AdminReturnDetail
    @POST("admin/returns/{id}/reject") suspend fun rejectReturn(@Path("id") id: String, @Body body: ReasonBody): AdminReturnDetail
    @POST("admin/returns/{id}/receive") suspend fun receiveReturn(@Path("id") id: String, @Body body: ReceiveReturnRequest): AdminReturnDetail
    @POST("admin/returns/{id}/refund") suspend fun refundReturn(@Path("id") id: String, @Body body: RefundReturnRequest): AdminReturnDetail

    // products
    @GET("admin/products") suspend fun products(
        @Query("q") query: String? = null,
        @Query("status") status: String? = null,
        @Query("categoryId") categoryId: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("brandId") brandId: String? = null,
    ): Page<AdminProduct>
    @GET("admin/products/{id}") suspend fun product(@Path("id") id: String): AdminProduct
    @PATCH("admin/products/{id}") suspend fun updateProduct(@Path("id") id: String, @Body body: UpdateProductRequest): AdminProduct
    @POST("admin/products") suspend fun createProduct(@Body body: CreateProductRequest): AdminProduct
    @PUT("admin/products/{id}/images") suspend fun replaceImages(@Path("id") id: String, @Body body: ImagesRequest): AdminProduct
    @POST("admin/products/{id}/variants") suspend fun addVariant(@Path("id") id: String, @Body body: NewVariantRequest): AdminVariant
    @PATCH("admin/variants/{id}") suspend fun updateVariant(@Path("id") id: String, @Body body: UpdateVariantRequest): AdminVariant
    @GET("admin/categories") suspend fun categories(): List<AdminCategory>
    @POST("admin/categories") suspend fun createCategory(@Body body: CreateCategoryRequest): AdminCategory
    @PATCH("admin/categories/{id}") suspend fun updateCategory(@Path("id") id: String, @Body body: UpdateCategoryRequest): AdminCategory
    @GET("admin/coupons") suspend fun coupons(): List<AdminCoupon>
    @POST("admin/coupons") suspend fun createCoupon(@Body body: CouponRequest): AdminCoupon
    @PATCH("admin/coupons/{id}") suspend fun updateCoupon(@Path("id") id: String, @Body body: CouponRequest): AdminCoupon
    @GET("admin/brands") suspend fun brands(): List<AdminBrand>
    @POST("admin/brands") suspend fun createBrand(@Body body: BrandRequest): AdminBrand
    @PATCH("admin/brands/{id}") suspend fun updateBrand(@Path("id") id: String, @Body body: BrandRequest): AdminBrand
    @DELETE("admin/brands/{id}") suspend fun deleteBrand(@Path("id") id: String): Response<Unit>
    @POST("admin/system/wipe-test-data") suspend fun wipeTestData(@Body body: WipeRequest): WipeSummary

    // inventory
    @GET("admin/warehouses") suspend fun warehouses(): List<Warehouse>
    @GET("admin/inventory/levels") suspend fun stockLevels(
        @Query("q") query: String? = null,
        @Query("warehouseId") warehouseId: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 30,
    ): Page<StockLevel>
    @GET("admin/inventory/low-stock") suspend fun lowStock(): List<LowStockRow>
    @GET("admin/stock-counts") suspend fun stockCounts(@Query("page") page: Int = 1): Page<StockCountSummary>
    @GET("admin/stock-counts/{id}") suspend fun stockCount(@Path("id") id: String): StockCountDetail
    @POST("admin/stock-counts") suspend fun createStockCount(@Body body: CreateStockCountRequest): StockCountDetail
    @POST("admin/stock-counts/{id}/post") suspend fun postStockCount(@Path("id") id: String): StockCountDetail
    @DELETE("admin/stock-counts/{id}") suspend fun deleteStockCount(@Path("id") id: String): Response<Unit>
    @POST("admin/inventory/adjustments") suspend fun adjust(@Body body: AdjustmentRequest): AdjustmentResult

    // purchasing
    @GET("admin/suppliers") suspend fun suppliers(@Query("q") query: String? = null, @Query("pageSize") pageSize: Int = 50, @Query("kind") kind: String? = null): Page<Supplier>
    @POST("admin/suppliers") suspend fun createSupplier(@Body body: CreateSupplierRequest): Supplier
    @GET("admin/suppliers/{id}") suspend fun supplier(@Path("id") id: String): SupplierDetail
    @PATCH("admin/suppliers/{id}") suspend fun updateSupplier(@Path("id") id: String, @Body body: UpdateSupplierRequest): Supplier
    @GET("admin/suppliers/{id}/pending-items") suspend fun pendingItems(@Path("id") id: String): PendingItems
    @POST("admin/purchase-orders/import") suspend fun createImport(@Body body: ImportOrderRequest): PurchaseOrderDetail
    @POST("admin/purchase-orders/{id}/import-costs") suspend fun importCosts(@Path("id") id: String, @Body body: ImportReceiveRequest): ImportCosts
    @POST("admin/purchase-orders/{id}/import-receive") suspend fun importReceive(@Path("id") id: String, @Body body: ImportReceiveRequest): PurchaseOrderDetail
    @PUT("admin/purchase-orders/{id}/import") suspend fun updateImport(@Path("id") id: String, @Body body: UpdateImportOrderRequest): PurchaseOrderDetail
    @POST("admin/purchase-orders/{id}/cancel") suspend fun cancelPurchaseOrder(@Path("id") id: String): PurchaseOrderDetail
    @POST("admin/suppliers/{id}/opening-balance") suspend fun supplierOpeningBalance(@Path("id") id: String, @Body body: SupplierOpeningBalanceRequest): SupplierAccount
    @GET("admin/suppliers/{id}/account") suspend fun supplierAccount(@Path("id") id: String): SupplierAccount
    @POST("admin/supplier-vouchers/payment") suspend fun paymentVoucher(@Body body: SupplierPaymentVoucherRequest): SupplierVoucher
    @POST("admin/supplier-vouchers/commission") suspend fun commissionVoucher(@Body body: SupplierCommissionRequest): SupplierVoucher
    @PUT("admin/supplier-vouchers/{id}/payment") suspend fun editPaymentVoucher(@Path("id") id: String, @Body body: SupplierPaymentVoucherRequest): SupplierVoucher
    @PUT("admin/supplier-vouchers/{id}/commission") suspend fun editCommissionVoucher(@Path("id") id: String, @Body body: SupplierCommissionRequest): SupplierVoucher
    @POST("admin/supplier-vouchers/{id}/cancel") suspend fun cancelVoucher(@Path("id") id: String): SupplierVoucher
    @POST("admin/purchase-orders/quick") suspend fun quickPurchase(@Body body: QuickPurchaseRequest): PurchaseOrderDetail
    @GET("admin/reports/inventory-valuation") suspend fun inventoryValuation(): InventoryValuation
    @Multipart @POST("admin/files/video") suspend fun uploadVideo(@Part file: MultipartBody.Part): UploadedFile
    @GET("admin/purchase-orders") suspend fun purchaseOrders(@Query("status") status: String? = null, @Query("page") page: Int = 1): Page<PurchaseOrderSummary>
    @GET("admin/purchase-orders/{id}") suspend fun purchaseOrder(@Path("id") id: String): PurchaseOrderDetail
    @POST("admin/purchase-orders/{id}/receive") suspend fun receive(@Path("id") id: String, @Body body: ReceiveRequest): PurchaseOrderDetail
    @POST("admin/supplier-payments") suspend fun paySupplier(@Body body: SupplierPaymentRequest): Response<Unit>

    // expenses
    @GET("admin/expense-categories") suspend fun expenseCategories(): List<ExpenseCategory>
    @GET("admin/expenses") suspend fun expenses(@Query("status") status: String? = null, @Query("page") page: Int = 1): ExpensePage
    @GET("admin/expenses/{id}") suspend fun expense(@Path("id") id: String): ExpenseDetail
    @POST("admin/expenses") suspend fun createExpense(@Body body: ExpenseRequest): ExpenseDetail
    @POST("admin/expenses/{id}/approve") suspend fun approveExpense(@Path("id") id: String, @Body body: PaidFrom): ExpenseDetail
    @POST("admin/expenses/{id}/pay") suspend fun payExpense(@Path("id") id: String, @Body body: PaidFrom): ExpenseDetail
    @POST("admin/expenses/{id}/cancel") suspend fun cancelExpense(@Path("id") id: String, @Body body: ReasonBody): ExpenseDetail

    @GET("admin/accounts") suspend fun cashAccounts(@Query("cashOrBank") cashOrBank: Boolean = true): List<LedgerAccount>

    // courier settlements
    @GET("admin/courier-settlements/pending") suspend fun pendingCod(@Query("carrier") carrier: String? = null): PendingCod
    @POST("admin/courier-settlements") suspend fun settle(@Body body: SettlementRequest): Settlement

    // currencies
    @GET("admin/exchange-rates") suspend fun exchangeRates(): ExchangeRates
    @PUT("admin/exchange-rates/{currency}") suspend fun setRate(@Path("currency") currency: Currency, @Body body: SetRateRequest): ExchangeRates

    @GET("admin/currencies") suspend fun allCurrencies(): Currencies
    @GET("admin/currencies/active") suspend fun activeCurrencies(): Currencies
    @POST("admin/currencies") suspend fun createCurrency(@Body body: CurrencyRequest): CurrencyRate
    @PATCH("admin/currencies/{code}") suspend fun updateCurrency(@Path("code") code: String, @Body body: CurrencyRequest): CurrencyRate
    @GET("admin/currencies/{code}/rates") suspend fun rateHistory(@Path("code") code: String): List<ExchangeRates.Entry>

    // customers
    @GET("admin/customers") suspend fun customers(@Query("q") query: String? = null, @Query("page") page: Int = 1): Page<AdminCustomerSummary>
    @GET("admin/customers/{id}") suspend fun customer(@Path("id") id: String): AdminCustomerDetail
    @GET("admin/customers/{id}/account") suspend fun customerAccount(@Path("id") id: String, @Query("page") page: Int = 1): CustomerAccount
    @POST("admin/customers/{id}/account/receipts") suspend fun customerReceipt(@Path("id") id: String, @Body body: CustomerReceiptRequest): AccountBalances
    @POST("admin/customers/{id}/account/exchange") suspend fun customerExchange(@Path("id") id: String, @Body body: CustomerExchangeRequest): CustomerExchangeResult
    @POST("admin/customers/{id}/account/opening-balance") suspend fun openingBalance(@Path("id") id: String, @Body body: OpeningBalanceRequest): AccountBalances

    @PATCH("admin/customers/{id}") suspend fun updateCustomer(@Path("id") id: String, @Body body: UpdateCustomerRequest): AdminCustomerDetail

    // staff
    @GET("admin/users") suspend fun staff(@Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 100): Page<StaffUser>
    @GET("admin/users/{id}") suspend fun staffUser(@Path("id") id: String): StaffUser
    @POST("admin/users") suspend fun createStaff(@Body body: CreateStaffRequest): StaffUser
    @PATCH("admin/users/{id}") suspend fun updateStaff(@Path("id") id: String, @Body body: UpdateStaffRequest): StaffUser
    @PUT("admin/users/{id}/permissions") suspend fun setStaffPermissions(@Path("id") id: String, @Body body: PermissionsRequest): StaffUser
    @PUT("admin/users/{id}/cash-boxes") suspend fun setStaffBoxes(@Path("id") id: String, @Body body: CashBoxIdsRequest): StaffUser
    @GET("admin/roles") suspend fun roles(): List<StaffRole>

    // POS
    @GET("admin/pos/products") suspend fun posProducts(@Query("q") query: String? = null, @Query("categoryId") categoryId: String? = null): List<PosProduct>
    @GET("admin/pos/lookup") suspend fun posLookup(@Query("code") code: String): PosLookup
    @PUT("admin/pos/product-order") suspend fun arrangeProducts(@Body body: PosArrangeRequest): Response<Unit>
    @POST("admin/pos/sales") suspend fun sell(@Body body: PosSaleRequest): PosReceipt
    @GET("admin/pos/sales/{id}") suspend fun posReceipt(@Path("id") id: String): PosReceipt
    @GET("admin/pos/sales/by-number/{number}") suspend fun findSale(@Path("number") number: String): PosReceipt
    @POST("admin/pos/returns") suspend fun posReturn(@Body body: PosReturnRequest): PosReturnResult
    @GET("admin/pos/sales") suspend fun posSales(@Query("date") date: String? = null, @Query("all") all: Boolean? = null): List<PosSaleSummary>
    @POST("admin/pos/sales/{id}/void") suspend fun voidSale(@Path("id") id: String, @Body body: VoidSaleRequest): PosReceipt
    @PUT("admin/pos/sales/{id}") suspend fun editSale(@Path("id") id: String, @Body body: PosSaleRequest): PosReceipt
    @GET("admin/pos/shifts/current") suspend fun currentShift(): CurrentShift
    @POST("admin/pos/shifts/open") suspend fun openShift(@Body body: OpenShiftRequest): CashierShift
    @POST("admin/pos/shifts/close") suspend fun closeShift(@Body body: CloseShiftRequest): CashierShift
    @POST("admin/pos/shifts/{id}/close") suspend fun closeShiftOf(@Path("id") id: String, @Body body: CloseShiftRequest): CashierShift
    @GET("admin/pos/shifts") suspend fun shifts(@Query("page") page: Int = 1, @Query("userId") userId: String? = null): Page<CashierShift>
    @GET("admin/pos/settings") suspend fun posSettings(): PosSettings
    @PUT("admin/pos/settings") suspend fun savePosSettings(@Body body: PosSettings): PosSettings
    @POST("admin/pos/returns/without-invoice") suspend fun posFreeReturn(@Body body: FreeReturnRequest): FreeReturnResult
    @GET("admin/alerts") suspend fun alerts(@Query("since") since: String? = null): StaffAlerts
    @GET("admin/pos/shift") suspend fun shift(): PosShift
    @POST("admin/pos/customers") suspend fun quickCustomer(@Body body: QuickCustomerRequest): QuickCustomer

    // cash boxes
    @GET("admin/cash-boxes/mine") suspend fun myBoxes(): List<CashBox>
    @GET("admin/cash-boxes") suspend fun allBoxes(@Query("kind") kind: String? = null, @Query("currency") currency: String? = null): List<CashBox>
    @GET("admin/cash-boxes/safes") suspend fun safes(): List<CashBox>
    @POST("admin/cash-boxes") suspend fun createBox(@Body body: CreateBoxRequest): CashBox
    @PATCH("admin/cash-boxes/{id}") suspend fun updateBox(@Path("id") id: String, @Body body: UpdateBoxRequest): CashBox
    @GET("admin/cash-boxes/{id}/entries") suspend fun boxEntries(@Path("id") id: String, @Query("page") page: Int = 1): Page<CashBoxEntry>
    @POST("admin/cash-transfers") suspend fun transfer(@Body body: TransferRequest): CashTransfer
    @POST("admin/cash-transfers/{id}/cancel") suspend fun cancelTransfer(@Path("id") id: String, @Body body: CancelTransferRequest): CashTransfer
    @PUT("admin/cash-transfers/{id}") suspend fun editTransfer(@Path("id") id: String, @Body body: TransferRequest): CashTransfer
    @GET("admin/cash-transfers") suspend fun transfers(@Query("exchangesOnly") exchangesOnly: Boolean? = null, @Query("page") page: Int = 1): Page<CashTransfer>

    // wallet top-ups & bank accounts
    @GET("admin/wallet/top-ups") suspend fun topUps(@Query("status") status: String? = null, @Query("page") page: Int = 1): Page<AdminTopUp>
    @GET("admin/wallet/top-ups/{id}") suspend fun topUp(@Path("id") id: String): AdminTopUp
    @POST("admin/wallet/top-ups/{id}/approve") suspend fun approveTopUp(@Path("id") id: String, @Body body: ApproveTopUpRequest): AdminTopUp
    @POST("admin/wallet/top-ups/{id}/reject") suspend fun rejectTopUp(@Path("id") id: String, @Body body: ReasonBody): AdminTopUp
    @GET("admin/bank-accounts") suspend fun bankAccounts(): List<StoreBankAccount>
    @POST("admin/bank-accounts") suspend fun createBankAccount(@Body body: BankAccountRequest): StoreBankAccount
    @PATCH("admin/bank-accounts/{id}") suspend fun updateBankAccount(@Path("id") id: String, @Body body: BankAccountRequest): StoreBankAccount
    @DELETE("admin/bank-accounts/{id}") suspend fun deleteBankAccount(@Path("id") id: String): BankAccountDeleted

    // what the customer app offers
    @GET("admin/store-payment-methods") suspend fun storePaymentMethods(): List<com.dejavu.core.model.PaymentMethod>
    @PUT("admin/store-payment-methods") suspend fun setStorePaymentMethods(@Body body: StorePaymentMethodsRequest): List<com.dejavu.core.model.PaymentMethod>
    @GET("admin/shipping-zones") suspend fun shippingZones(): List<com.dejavu.core.model.ShippingZone>
    @PUT("admin/shipping-zones") suspend fun saveShippingZone(@Body body: com.dejavu.core.model.ShippingZone): com.dejavu.core.model.ShippingZone

    // customer reviews
    @GET("admin/reviews") suspend fun reviews(@Query("status") status: String? = null, @Query("page") page: Int = 1): Page<AdminReview>
    @POST("admin/reviews/{id}/approve") suspend fun approveReview(@Path("id") id: String, @Body body: ApproveReviewRequest): AdminReview
    @DELETE("admin/reviews/{id}") suspend fun deleteReview(@Path("id") id: String): Response<Unit>

    // promotions
    @GET("admin/promotions") suspend fun promotions(): List<Promotion>
    @POST("admin/promotions") suspend fun createPromotion(@Body body: PromotionRequest): Promotion
    @PATCH("admin/promotions/{id}") suspend fun updatePromotion(@Path("id") id: String, @Body body: PromotionRequest): Promotion
    @DELETE("admin/promotions/{id}") suspend fun deletePromotion(@Path("id") id: String): Response<Unit>

    // SMS sent from the store phone (this app is the gateway)
    @POST("admin/sms/claim") suspend fun claimSms(@Query("limit") limit: Int = 10): List<SmsToSend>
    @POST("admin/sms/{id}/result") suspend fun smsResult(@Path("id") id: String, @Body body: SmsResultRequest): Response<Unit>
    @GET("admin/sms") suspend fun smsList(@Query("status") status: String? = null): List<OutgoingSms>
    @GET("admin/sms/settings") suspend fun smsSettings(): SmsSettings
    @PUT("admin/sms/settings") suspend fun updateSmsSettings(@Body body: SmsSettingsRequest): SmsSettings

    // files
    @Multipart @POST("admin/files") suspend fun uploadImage(@Part file: MultipartBody.Part): UploadedFile
    /** Private staff file (deposit slip photo at the till). */
    @Multipart @POST("admin/files/private") suspend fun uploadPrivate(@Part file: MultipartBody.Part): UploadedFile
    /** A private file (deposit slip), read with the staff token. */
    @GET("admin/files/{id}") suspend fun file(@Path("id") id: String): ResponseBody
}
