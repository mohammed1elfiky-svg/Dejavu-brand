package com.dejavu.core.admin

import com.dejavu.core.model.BankRef
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.Page
import com.dejavu.core.model.PromotionTag
import com.dejavu.core.model.TopUpStatus
import kotlinx.serialization.Serializable

/*
 * Wire models for the till (POS), cash boxes, customer accounts, wallet top-ups,
 * promotions and currency settings. Amounts are decimal strings; SAR unless a
 * field says it is in a currency.
 */

// ------------------------------------------------------------------- POS ---

@Serializable
data class PosVariant(
    val id: String,
    val sku: String,
    val barcode: String? = null,
    val size: String? = null,
    val color: String? = null,
    val colorHex: String? = null,
    /** Selling price now (after a running promotion), SAR. */
    val price: String,
    val regularPrice: String = price,
    val available: Int = 0,
    val imageUrl: String? = null,
) {
    val title: String get() = listOfNotNull(color, size).joinToString(" · ").ifBlank { sku }
}

@Serializable
data class PosProduct(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val skuNumber: Int? = null,
    val basePrice: String,
    val price: String = basePrice,
    /** Price for merchant customers (SAR); null = they pay [price]. */
    val wholesalePrice: String? = null,
    val posSortOrder: Int = 0,
    val category: CategoryName? = null,
    val imageUrl: String? = null,
    val promotion: PromotionTag? = null,
    val variants: List<PosVariant> = emptyList(),
) {
    val available: Int get() = variants.sumOf { it.available.coerceAtLeast(0) }
}

@Serializable
data class PosLookup(val product: PosProduct, val variantId: String? = null)

@Serializable
data class PosArrangeRequest(val productIds: List<String>)

@Serializable
data class PosItem(val variantId: String, val quantity: Int)

@Serializable
data class PosTender(
    val currency: Currency,
    val amount: String,
    /** CASH (drawer) or BANK_TRANSFER (bank box); the sale's [PosSaleRequest.paymentMethod] by default. */
    val method: String? = null,
    /** Bank box a transfer went to; the first bank box of the currency by default. */
    val bankBoxId: String? = null,
)

@Serializable
enum class SaleType { CASH, CREDIT }

@Serializable
enum class DiscountType { PERCENT, AMOUNT }

@Serializable
data class PosSaleRequest(
    val items: List<PosItem>,
    val saleType: SaleType,
    /** Invoice currency (its current rate is locked in). */
    val currency: Currency? = null,
    val customerId: String? = null,
    val discountType: DiscountType? = null,
    /** Percent, or an amount in the invoice currency. */
    val discountValue: String? = null,
    val payments: List<PosTender> = emptyList(),
    val changeCurrency: Currency? = null,
    val note: String? = null,
    /** CASH, or BANK_TRANSFER for a transfer / deposit slip. */
    val paymentMethod: String? = null,
    /** Transfer or deposit slip number. */
    val reference: String? = null,
    /** Pay this much (invoice currency) from the customer's own credit first. */
    val fromAccount: String? = null,
)

@Serializable
data class PosInCurrency(
    val currency: Currency,
    val exchangeRate: String,
    val subtotal: String,
    val discountTotal: String,
    val taxTotal: String,
    val grandTotal: String,
)

@Serializable
data class PosReceipt(
    val id: String,
    val number: String,
    val placedAt: String,
    val subtotal: String,
    val discountTotal: String,
    val taxTotal: String,
    val grandTotal: String,
    val currency: Currency = Currency.SAR,
    val exchangeRate: String = "1",
    val items: List<AdminOrderItem> = emptyList(),
    val payments: List<AdminPayment> = emptyList(),
    val customer: PersonRef? = null,
    val cashier: NameRef? = null,
    val inCurrency: PosInCurrency,
    /** Put on the customer's account (credit sale), SAR. */
    val onAccount: String = "0",
) {
    fun inInvoiceCurrency(amountSar: String?): String = Money.format(amountSar, currency, exchangeRate)
}

@Serializable
data class ShiftSale(
    val id: String,
    val number: String,
    val grandTotal: String,
    val grandTotalInCurrency: String,
    val currency: Currency,
    val placedAt: String,
    val customer: NameRef? = null,
)

@Serializable
data class PosShift(val count: Int, val total: String, val sales: List<ShiftSale> = emptyList(), val boxes: List<CashBox> = emptyList())

@Serializable
data class QuickCustomerRequest(val name: String, val phone: String)

@Serializable
data class QuickCustomer(val id: String, val code: String, val name: String, val phone: String)

// ------------------------------------------------------------- cash boxes ---

@Serializable
enum class CashBoxKind { CASHIER, VAULT, BANK }

@Serializable
data class BoxAccount(val id: String, val code: String)

@Serializable
data class CashBox(
    val id: String,
    val name: String,
    val kind: CashBoxKind,
    val currency: Currency,
    val isActive: Boolean = true,
    val user: NameRef? = null,
    val account: BoxAccount? = null,
    /** Money in the box, in its own currency. */
    val balance: String = "0",
    /** What that money is carried at in the books (SAR). */
    val bookValue: String = "0",
) {
    val shownBalance: String get() = Money.plain(Money.parse(balance), currency)
}

@Serializable
data class CashBoxEntry(
    val id: String,
    val amount: String,
    val baseAmount: String,
    val sourceType: String,
    val note: String? = null,
    val createdAt: String,
)

@Serializable
data class CreateBoxRequest(val name: String, val kind: CashBoxKind, val currency: Currency)

@Serializable
data class UpdateBoxRequest(val name: String? = null, val isActive: Boolean? = null)

@Serializable
data class TransferRequest(
    val fromBoxId: String,
    val toBoxId: String,
    /** In the source box's currency. */
    val amount: String,
    /** In the target box's currency; required when the currencies differ (an exchange). */
    val toAmount: String? = null,
    val note: String? = null,
)

@Serializable
data class BoxRef(val id: String, val name: String, val currency: Currency)

@Serializable
data class CashTransfer(
    val id: String,
    val number: String,
    val fromAmount: String,
    val toAmount: String,
    val baseAmount: String,
    /** Exchange gain (+) or loss (-), SAR. */
    val fxDifference: String = "0",
    val note: String? = null,
    val createdAt: String,
    val fromBox: BoxRef,
    val toBox: BoxRef,
) {
    val isExchange: Boolean get() = fromBox.currency != toBox.currency
}

// ------------------------------------------------------- customer accounts ---

@Serializable
data class AccountBalance(
    val currency: Currency,
    /** + the customer owes the store, - the store holds their money. */
    val balance: String,
    val available: String = "0",
    val bookValue: String = "0",
)

@Serializable
data class AccountEntry(
    val id: String,
    val currency: Currency,
    val amount: String,
    val sourceType: String,
    val note: String? = null,
    val createdAt: String,
)

@Serializable
data class CustomerAccount(val balances: List<AccountBalance> = emptyList(), val entries: Page<AccountEntry>)

@Serializable
data class AccountBalances(val balances: List<AccountBalance> = emptyList())

@Serializable
data class CustomerReceiptRequest(val currency: Currency, val amount: String, val cashBoxId: String? = null, val note: String? = null)

// ---------------------------------------------------------- wallet top-ups ---

@Serializable
data class AdminTopUp(
    val id: String,
    val number: String,
    val currency: Currency,
    val amount: String,
    val reference: String? = null,
    val note: String? = null,
    val status: TopUpStatus,
    val receiptFileId: String,
    val rejectReason: String? = null,
    val createdAt: String,
    val reviewedAt: String? = null,
    val bankAccount: BankRef? = null,
    val customer: PersonRef? = null,
    val cashBox: BoxRef? = null,
    val reviewedBy: NameRef? = null,
    val balances: List<AccountBalance> = emptyList(),
) {
    val shownAmount: String get() = Money.plain(Money.parse(amount), currency)
}

@Serializable
data class ApproveTopUpRequest(val cashBoxId: String, val amount: String? = null)

@Serializable
data class BankAccountRequest(
    val bankName: String,
    val accountName: String,
    val accountNumber: String,
    val currency: Currency,
    val iban: String? = null,
    val instructions: String? = null,
    val isActive: Boolean? = null,
)

// -------------------------------------------------------------- promotions ---

@Serializable
data class PromotionProduct(val id: String, val nameAr: String, val nameEn: String, val basePrice: String, val skuNumber: Int? = null, val images: List<ImageUrl> = emptyList())

@Serializable
data class Promotion(
    val id: String,
    val name: String,
    val percent: String,
    val startsAt: String,
    val endsAt: String? = null,
    val isActive: Boolean = true,
    val running: Boolean = false,
    val products: List<PromotionProduct> = emptyList(),
)

@Serializable
data class PromotionRequest(
    val name: String,
    val percent: String,
    val productIds: List<String>,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val isActive: Boolean? = null,
)

// -------------------------------------------------------------- currencies ---

@Serializable
data class CurrencyRequest(
    val code: String? = null,
    val nameAr: String? = null,
    val nameEn: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
    val isActive: Boolean? = null,
    val showInCustomerApp: Boolean? = null,
    val sortOrder: Int? = null,
    /** Initial rate (create only): units per 1 SAR. */
    val rate: String? = null,
)

// --------------------------------------------------------------- merchants ---

@Serializable
data class UpdateCustomerRequest(
    val isMerchant: Boolean? = null,
    val merchantDiscount: String? = null,
    val creditLimit: String? = null,
    val notes: String? = null,
)

// ------------------------------------------------------------------ staff ---

@Serializable
data class StaffRole(val id: String, val name: String, val description: String? = null, val isSystem: Boolean = false, val permissions: List<String> = emptyList())

@Serializable
data class PermissionDef(val id: String, val key: String, val module: String, val description: String? = null)

@Serializable
data class StaffUser(
    val id: String,
    val name: String,
    val email: String,
    val username: String? = null,
    val phone: String? = null,
    val status: String = "ACTIVE",
    val lastLoginAt: String? = null,
    val createdAt: String? = null,
    val roles: List<RoleRef> = emptyList(),
    /** Granted to this employee directly. */
    val customPermissions: List<String> = emptyList(),
    /** Everything they can do (roles + own). */
    val permissions: List<String> = emptyList(),
    /** Boxes they may use (empty = only their own drawers). */
    val cashBoxIds: List<String> = emptyList(),
) {
    val isActive: Boolean get() = status == "ACTIVE"
}

@Serializable
data class CreateStaffRequest(
    val name: String,
    val email: String,
    val password: String,
    val roleIds: List<String> = emptyList(),
    val username: String? = null,
    val phone: String? = null,
    val permissions: List<String>? = null,
    val cashBoxIds: List<String>? = null,
)

@Serializable
data class UpdateStaffRequest(
    val name: String? = null,
    /** "" clears it. */
    val username: String? = null,
    val phone: String? = null,
    val status: String? = null,
    val password: String? = null,
    val roleIds: List<String>? = null,
)

@Serializable
data class PermissionsRequest(val permissions: List<String>)

@Serializable
data class CashBoxIdsRequest(val cashBoxIds: List<String>)

/** Arabic names of the permission keys, for the staff screen (unknown keys show as-is). */
object PermissionLabels {
    val groups: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "الكاشير والصناديق" to listOf(
            "pos.sell" to "البيع من الكاشير",
            "cashboxes.manage" to "إدارة كل الصناديق والسحب منها",
            "wallet.manage" to "مراجعة طلبات شحن الرصيد",
        ),
        "الطلبات والعملاء" to listOf(
            "orders.read" to "عرض الطلبات",
            "orders.update_status" to "تغيير حالة الطلبات",
            "orders.cancel" to "إلغاء الطلبات",
            "returns.manage" to "المرتجعات",
            "customers.read" to "عرض العملاء",
            "customers.write" to "تعديل العملاء (التجار)",
            "coupons.manage" to "الكوبونات",
            "promotions.manage" to "العروض والخصومات",
        ),
        "المنتجات والمخزون" to listOf(
            "products.read" to "عرض المنتجات",
            "products.write" to "إضافة وتعديل المنتجات",
            "categories.write" to "التصنيفات",
            "stock.read" to "عرض المخزون",
            "stock.adjust" to "تعديل المخزون (تسويات)",
            "stock.transfer" to "تحويل المخزون",
            "stock.count" to "الجرد",
        ),
        "المشتريات والمصروفات" to listOf(
            "suppliers.manage" to "الموردون",
            "purchases.read" to "عرض المشتريات",
            "purchases.write" to "إنشاء أوامر الشراء",
            "purchases.receive" to "استلام المشتريات",
            "payments.read" to "عرض المدفوعات",
            "payments.record" to "تسجيل المدفوعات",
            "supplier_payments.record" to "الدفع للموردين",
            "expenses.read" to "عرض المصروفات",
            "expenses.write" to "تسجيل المصروفات",
            "expenses.approve" to "اعتماد المصروفات",
        ),
        "الحسابات والتقارير" to listOf(
            "dashboard.view" to "لوحة المعلومات",
            "reports.sales" to "تقارير المبيعات",
            "reports.inventory" to "تقارير المخزون",
            "reports.financial" to "التقارير المالية",
            "accounts.manage" to "دليل الحسابات",
            "journal.read" to "عرض القيود",
            "journal.write" to "إنشاء القيود",
            "journal.post" to "ترحيل القيود",
            "journal.reverse" to "عكس القيود",
            "periods.close" to "إقفال الفترات",
        ),
        "النظام" to listOf(
            "users.manage" to "إدارة الموظفين",
            "roles.manage" to "إدارة الأدوار",
            "settings.manage" to "الإعدادات والعملات وأسعار الصرف",
            "audit.read" to "سجل العمليات",
            "notifications.send" to "إرسال الإشعارات",
        ),
    )

    /** Ready-made sets for a new employee. */
    val presets: List<Pair<String, List<String>>> = listOf(
        "كاشير فقط" to listOf("pos.sell", "products.read", "stock.read", "customers.read", "customers.write"),
        "أمين مخزن" to listOf("products.read", "products.write", "stock.read", "stock.adjust", "stock.transfer", "stock.count", "purchases.read", "purchases.receive"),
        "مبيعات وطلبات" to listOf("dashboard.view", "orders.read", "orders.update_status", "returns.manage", "customers.read", "customers.write", "pos.sell", "products.read", "stock.read"),
        "محاسب وتقارير" to listOf("dashboard.view", "reports.sales", "reports.financial", "reports.inventory", "journal.read", "payments.read", "expenses.read", "expenses.approve", "cashboxes.manage"),
    )

    fun label(key: String): String = groups.flatMap { it.second }.firstOrNull { it.first == key }?.second ?: key
}

// --------------------------------------------------- returns & exchanges ---

@Serializable
data class PosReturnItem(val orderItemId: String, val quantity: Int, val restock: Boolean = true)

@Serializable
data class PosReturnRequest(
    val orderId: String,
    val items: List<PosReturnItem>,
    val reason: String? = null,
    /** CASH (from the drawer) or ACCOUNT (kept as the customer's credit). */
    val refundTo: String = "CASH",
    /** SAR or the sale's currency. */
    val refundCurrency: Currency? = null,
    /** Exchange: the pieces taken instead. */
    val newItems: List<PosItem>? = null,
    /** Exchange: what the customer pays on top. */
    val payments: List<PosTender>? = null,
    val paymentMethod: String? = null,
    val reference: String? = null,
    val changeCurrency: Currency? = null,
)

@Serializable
data class RefundedAmount(val sar: String = "0", val currencyAmount: String = "0")

@Serializable
data class PosReturnResult(
    @kotlinx.serialization.SerialName("return") val salesReturn: AdminReturnDetail? = null,
    val sale: PosReceipt? = null,
    /** Returned value paid back or credited. */
    val refunded: RefundedAmount = RefundedAmount(),
    /** Exchange: returned value not used by the new pieces (sale currency). */
    val leftover: String = "0",
)

// ------------------------------------------------------ inventory value ---

@Serializable
data class CategoryValue(
    val categoryId: String,
    val nameAr: String,
    val products: Int,
    val pieces: Int,
    /** At purchase cost, SAR. */
    val costValue: String,
    /** At selling price, SAR. */
    val saleValue: String,
    val expectedProfit: String,
)

@Serializable
data class ValuationTotals(val products: Int, val pieces: Int, val costValue: String, val saleValue: String, val expectedProfit: String)

@Serializable
data class InventoryValuation(val categories: List<CategoryValue> = emptyList(), val totals: ValuationTotals)

// ------------------------------------------------------------- purchases ---

@Serializable
data class QuickPurchaseItem(val variantId: String, val quantity: Int, val unitCost: String)

@Serializable
data class QuickPurchaseRequest(
    val supplierId: String,
    val items: List<QuickPurchaseItem>,
    val supplierInvoiceNo: String? = null,
    val shippingCost: String? = null,
    val note: String? = null,
    /** Receive all pieces into stock now (default true on the server). */
    val receiveNow: Boolean? = null,
    /** CASH or BANK_TRANSFER to pay now; null = on account. */
    val payMethod: String? = null,
    /** SAR paid now; the whole invoice when null. */
    val payAmount: String? = null,
    val payAccountId: String? = null,
)

@Serializable
data class CreateSupplierRequest(val name: String, val phone: String? = null)
