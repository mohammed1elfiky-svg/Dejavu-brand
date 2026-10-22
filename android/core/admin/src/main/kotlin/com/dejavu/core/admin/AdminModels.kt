package com.dejavu.core.admin

import com.dejavu.core.model.Currency
import com.dejavu.core.model.CurrencyRate
import com.dejavu.core.model.ItemCount
import com.dejavu.core.model.Money
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.PaymentStatus
import com.dejavu.core.model.ReturnStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire models for the staff API (/api/v1/admin). Money is a decimal string
 * exactly as the API sends it; use com.dejavu.core.model.Money to display it.
 */

// ------------------------------------------------------------------ auth ---

@Serializable
data class Health(val status: String)

@Serializable
data class StaffLoginRequest(val email: String, val password: String)

@Serializable
data class RoleRef(val id: String, val name: String)

@Serializable
data class StaffProfile(
    val id: String,
    val name: String,
    val email: String,
    val phone: String? = null,
    val roles: List<RoleRef> = emptyList(),
    val permissions: List<String> = emptyList(),
) {
    fun can(permission: String) = permission in permissions
}

@Serializable
data class StaffAuthResponse(val accessToken: String, val refreshToken: String, val user: StaffProfile)

// ------------------------------------------------------------- dashboard ---

@Serializable
data class CashAccount(val accountId: String, val code: String, val nameAr: String, val nameEn: String, val balance: String)

@Serializable
data class Dashboard(
    val today: Today,
    val ordersByStatus: Map<String, Int> = emptyMap(),
    val pendingReturns: Int = 0,
    val lowStockVariants: Int = 0,
    val cashAndBank: List<CashAccount> = emptyList(),
    val receivables: Receivables,
    val payables: Payables,
    val monthToDate: MonthToDate,
) {
    @Serializable data class Today(val orders: Int, val sales: String)
    @Serializable data class Receivables(val customers: String, val couriersCod: String, val pendingCodShipments: Int = 0)
    @Serializable data class Payables(val suppliers: String, val customerAdvances: String, val accruedExpenses: String, val vatDue: String)
    @Serializable data class MonthToDate(val netRevenue: String, val grossProfit: String, val operatingExpenses: String, val netIncome: String)
}

// ---------------------------------------------------------------- orders ---

@Serializable
data class PersonRef(val id: String? = null, val code: String? = null, val name: String, val phone: String? = null)

@Serializable
data class AdminOrderSummary(
    val id: String,
    val number: String,
    val status: OrderStatus,
    val paymentStatus: PaymentStatus,
    val paymentMethod: PaymentMethod,
    val grandTotal: String,
    val amountPaid: String,
    val placedAt: String,
    val customer: PersonRef,
    @SerialName("_count") val count: ItemCount? = null,
    val currency: Currency = Currency.SAR,
    val exchangeRate: String = "1",
) {
    /** "18,000 ر.ي" for a YER order, the SAR amount otherwise. */
    val totalShown: String
        get() = if (currency == Currency.SAR) Money.format(grandTotal) else Money.format(grandTotal, currency, exchangeRate)
}

@Serializable
data class AdminOrderItem(
    val id: String,
    val variantId: String,
    val productName: String,
    val sku: String,
    val size: String? = null,
    val color: String? = null,
    val quantity: Int,
    val unitPrice: String,
    val discount: String,
    val taxAmount: String,
    val lineTotal: String,
    val unitCost: String,
    val returnedQty: Int = 0,
)

@Serializable
data class AdminPayment(
    val id: String,
    val number: String,
    val method: PaymentMethod,
    val amount: String,
    val paidAt: String? = null,
    val gatewayRef: String? = null,
    /** PENDING / FAILED / CANCELLED are online card attempts that never reached the books. */
    val status: String = "SUCCEEDED",
    val gateway: String? = null,
    /** Currency the money was handed over in; [amount] is its SAR value. */
    val currency: Currency = Currency.SAR,
    val currencyAmount: String? = null,
    /** Change handed back at the till. */
    val isChange: Boolean = false,
) {
    /** "7,000 ر.ي (46.67 ر.س)" for YER, the SAR amount otherwise. */
    val shown: String
        get() = if (currency.isBase || currencyAmount == null) {
            Money.formatBase(amount)
        } else {
            "${Money.plain(Money.parse(currencyAmount), currency)} (${Money.formatBase(amount)})"
        }

    val isRefund: Boolean get() = amount.startsWith("-")
    val isSettled: Boolean get() = status == "SUCCEEDED"
}

@Serializable
data class AdminShipment(
    val id: String,
    val carrier: String,
    val trackingNumber: String? = null,
    val status: String,
    val codAmount: String = "0",
    val shippedAt: String? = null,
    val deliveredAt: String? = null,
    val settlementId: String? = null,
)

@Serializable
data class NameRef(val id: String? = null, val name: String)

@Serializable
data class AdminStatusChange(val toStatus: OrderStatus, val note: String? = null, val createdAt: String, val changedBy: NameRef? = null)

@Serializable
data class AccountRef(val code: String, val nameAr: String, val nameEn: String? = null)

@Serializable
data class JournalLineView(val lineNo: Int, val debit: String, val credit: String, val account: AccountRef)

@Serializable
data class JournalEntryView(
    val id: String,
    val number: String,
    val entryDate: String,
    val description: String,
    val status: String,
    val lines: List<JournalLineView> = emptyList(),
)

@Serializable
data class ReturnRef(val id: String, val number: String, val status: ReturnStatus, val refundAmount: String)

@Serializable
data class Profit(val netRevenue: String, val costOfGoods: String, val grossProfit: String)

@Serializable
data class ShippingSnapshot(
    val recipient: String,
    val phone: String,
    val governorate: String,
    val city: String,
    val street: String,
    val district: String? = null,
    val building: String? = null,
    val floor: String? = null,
    val apartment: String? = null,
    val landmark: String? = null,
)

@Serializable
data class AdminOrderDetail(
    val id: String,
    val number: String,
    val status: OrderStatus,
    val paymentStatus: PaymentStatus,
    val paymentMethod: PaymentMethod,
    val shippingAddress: ShippingSnapshot,
    val subtotal: String,
    val discountTotal: String,
    val shippingFee: String,
    val taxTotal: String,
    val grandTotal: String,
    val amountPaid: String,
    val returnedTotal: String = "0",
    val balanceDue: String,
    val customerNote: String? = null,
    val placedAt: String,
    val cancelReason: String? = null,
    val customer: PersonRef,
    val items: List<AdminOrderItem>,
    val payments: List<AdminPayment> = emptyList(),
    val shipments: List<AdminShipment> = emptyList(),
    val statusHistory: List<AdminStatusChange> = emptyList(),
    val returns: List<ReturnRef> = emptyList(),
    val profit: Profit,
    val journalEntries: List<JournalEntryView> = emptyList(),
    val currency: Currency = Currency.SAR,
    /** Units of [currency] per 1 SAR, locked at checkout. */
    val exchangeRate: String = "1",
) {
    val isForeign: Boolean get() = currency != Currency.SAR

    /** Currencies money for this order can be entered in: its own first, then SAR. */
    val currencies: List<Currency> get() = listOfNotNull(currency.takeIf { isForeign }, Currency.SAR)

    /** A SAR amount of this order in its own currency, at its locked rate. */
    fun inOrderCurrency(amountSar: String?): String = Money.format(amountSar, currency, exchangeRate)

    /** What is still owed, in the order's currency (whole riyals for YER), as the API computes it. */
    val balanceDueInCurrency: String
        get() = Money.convert(Money.parse(balanceDue), currency, Money.parse(exchangeRate)).toPlainString()

    /** Next steps staff can take, in the order the backend allows them. */
    val nextStatuses: List<OrderStatus>
        get() = when (status) {
            OrderStatus.PENDING -> listOf(OrderStatus.CONFIRMED, OrderStatus.CANCELLED)
            OrderStatus.CONFIRMED -> listOf(OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.CANCELLED)
            OrderStatus.PROCESSING -> listOf(OrderStatus.SHIPPED, OrderStatus.CANCELLED)
            OrderStatus.SHIPPED -> listOf(OrderStatus.DELIVERED, OrderStatus.RETURNED)
            OrderStatus.DELIVERED -> listOf(OrderStatus.COMPLETED)
            else -> emptyList()
        }

    val canTakePayment: Boolean
        get() = status != OrderStatus.CANCELLED && status != OrderStatus.RETURNED && balanceDue.toBigDecimal().signum() > 0

    /** Prepaid money can be returned before shipping or after a cancelled / refused order. */
    val canRefund: Boolean
        get() = status in setOf(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PROCESSING, OrderStatus.CANCELLED, OrderStatus.RETURNED) &&
            amountPaid.toBigDecimal().signum() > 0
}

@Serializable
data class ChangeStatusRequest(val status: OrderStatus, val note: String? = null, val carrier: String? = null, val trackingNumber: String? = null)

@Serializable
data class PaymentRequest(
    val method: PaymentMethod,
    /** In [currency] (SAR when null). */
    val amount: String,
    val accountId: String? = null,
    val reference: String? = null,
    val reason: String? = null,
    val currency: Currency? = null,
)

// --------------------------------------------------------------- returns ---

@Serializable
data class AdminReturnSummary(
    val id: String,
    val number: String,
    val status: ReturnStatus,
    val reason: String,
    val refundAmount: String,
    val requestedAt: String,
    val order: OrderNumber,
    val customer: PersonRef,
) {
    @Serializable data class OrderNumber(val number: String)
}

@Serializable
data class ReturnedOrderItem(val id: String, val productName: String, val sku: String, val size: String? = null, val color: String? = null, val quantity: Int)

@Serializable
data class AdminReturnLine(val id: String, val quantity: Int, val refundAmount: String, val restock: Boolean = true, val orderItem: ReturnedOrderItem)

@Serializable
data class AdminReturnDetail(
    val id: String,
    val number: String,
    val status: ReturnStatus,
    val reason: String,
    val rejectReason: String? = null,
    val refundAmount: String,
    val refundDue: String = "0",
    val requestedAt: String,
    val order: OrderRef,
    val customer: PersonRef,
    val items: List<AdminReturnLine>,
    val journalEntries: List<JournalEntryView> = emptyList(),
) {
    @Serializable data class OrderRef(val id: String, val number: String, val paymentMethod: PaymentMethod)
}

@Serializable
data class ReasonBody(val reason: String)

@Serializable
data class ReceiveReturnRequest(val items: List<Restock>) {
    @Serializable data class Restock(val itemId: String, val restock: Boolean)
}

@Serializable
data class RefundReturnRequest(val method: PaymentMethod, val accountId: String? = null, val reference: String? = null)

// -------------------------------------------------------------- products ---

@Serializable
data class StockLevelRef(val warehouseId: String, val onHand: Int, val reserved: Int)

@Serializable
data class AdminVariant(
    val id: String,
    val sku: String,
    val variantNo: Int? = null,
    /** Manufacturer's barcode; the printed label always encodes [sku]. */
    val barcode: String? = null,
    val size: String? = null,
    val color: String? = null,
    val colorHex: String? = null,
    val price: String? = null,
    val avgCost: String,
    val lowStockThreshold: Int = 5,
    val isActive: Boolean = true,
    val stockLevels: List<StockLevelRef> = emptyList(),
) {
    /** "M · أسود", or the SKU for a product without options. */
    val title: String get() = listOfNotNull(size, color).joinToString(" · ").ifBlank { sku }

    val onHand: Int get() = stockLevels.sumOf { it.onHand }
    val available: Int get() = stockLevels.sumOf { it.onHand - it.reserved }
}

@Serializable
enum class ProductStatus { DRAFT, ACTIVE, ARCHIVED }

@Serializable
data class CategoryName(val id: String, val nameAr: String)

@Serializable
data class ImageUrl(val url: String, val color: String? = null)

@Serializable
data class AdminProduct(
    val id: String,
    val slug: String,
    val nameAr: String,
    val nameEn: String,
    val descriptionAr: String? = null,
    val descriptionEn: String? = null,
    val status: ProductStatus,
    val basePrice: String,
    val compareAtPrice: String? = null,
    /** Price for merchant customers, SAR. */
    val wholesalePrice: String? = null,
    val isFeatured: Boolean = false,
    val category: CategoryName,
    val images: List<ImageUrl> = emptyList(),
    val variants: List<AdminVariant> = emptyList(),
    /** Numeric product SKU (100…); variants are "<skuNumber>-<n>". */
    val skuNumber: Int? = null,
    /** Cost per piece in SAR, shared by all variants. */
    val costPrice: String = "0",
)

@Serializable
data class UpdateProductRequest(
    val status: ProductStatus? = null,
    val basePrice: String? = null,
    val wholesalePrice: String? = null,
    val costPrice: String? = null,
    val compareAtPrice: String? = null,
    val isFeatured: Boolean? = null,
    val categoryId: String? = null,
    val nameAr: String? = null,
    val nameEn: String? = null,
    val slug: String? = null,
    val descriptionAr: String? = null,
    val descriptionEn: String? = null,
)

/** Category tree node from GET admin/categories. */
@Serializable
data class AdminCategory(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val slug: String,
    val children: List<AdminCategory> = emptyList(),
) {
    /** This category and all below it, depth-first, with their depth (for an indented picker). */
    fun flatten(depth: Int = 0): List<Pair<AdminCategory, Int>> = listOf(this to depth) + children.flatMap { it.flatten(depth + 1) }
}

@Serializable
data class CreateCategoryRequest(val nameAr: String, val nameEn: String, val slug: String, val parentId: String? = null)

@Serializable
data class NewVariantRequest(
    /** Leave null to number it automatically (<product SKU>-<n>). */
    val sku: String? = null,
    val size: String? = null,
    val color: String? = null,
    val colorHex: String? = null,
    /** Overrides the product price for this variant. */
    val price: String? = null,
    val lowStockThreshold: Int? = null,
    /** Pieces on hand now, booked as opening stock with [unitCost] (SAR). */
    val initialQuantity: Int? = null,
    val unitCost: String? = null,
    /** Manufacturer's barcode. */
    val barcode: String? = null,
)

@Serializable
data class ImageRequest(val url: String, val color: String? = null)

@Serializable
data class CreateProductRequest(
    val categoryId: String,
    val nameAr: String,
    val nameEn: String,
    val slug: String,
    val basePrice: String,
    val variants: List<NewVariantRequest>,
    val costPrice: String? = null,
    val wholesalePrice: String? = null,
    val descriptionAr: String? = null,
    val compareAtPrice: String? = null,
    val status: ProductStatus? = null,
    val isFeatured: Boolean? = null,
    val images: List<ImageRequest>? = null,
)

@Serializable
data class UpdateVariantRequest(
    val sku: String? = null,
    val size: String? = null,
    val color: String? = null,
    val colorHex: String? = null,
    val price: String? = null,
    val lowStockThreshold: Int? = null,
    val isActive: Boolean? = null,
    /** Manufacturer's barcode; "" clears it. */
    val barcode: String? = null,
)

@Serializable
data class ImagesRequest(val images: List<ImageRequest>)

// ------------------------------------------------------------- inventory ---

@Serializable
data class Warehouse(val id: String, val code: String, val name: String, val isDefault: Boolean = false, val isActive: Boolean = true)

@Serializable
data class VariantRef(
    val id: String,
    val sku: String,
    val size: String? = null,
    val color: String? = null,
    val avgCost: String,
    val lowStockThreshold: Int = 5,
    val product: ProductName,
) {
    @Serializable data class ProductName(val id: String, val nameAr: String, val nameEn: String)
}

@Serializable
data class StockLevel(
    val variantId: String,
    val warehouseId: String,
    val onHand: Int,
    val reserved: Int,
    val available: Int,
    val stockValue: String,
    val variant: VariantRef,
    val warehouse: WarehouseRef,
) {
    @Serializable data class WarehouseRef(val id: String, val code: String, val name: String)
}

@Serializable
data class LowStockRow(
    @SerialName("variant_id") val variantId: String,
    val sku: String,
    @SerialName("name_ar") val nameAr: String,
    @SerialName("on_hand") val onHand: Int,
    val reserved: Int,
    val available: Int,
    val threshold: Int,
)

@Serializable
enum class AdjustmentReason { OPENING, COUNT_SURPLUS, OTHER_IN, COUNT_SHORTAGE, DAMAGE, OTHER_OUT }

@Serializable
data class AdjustmentRequest(val warehouseId: String, val reason: AdjustmentReason, val items: List<Item>, val note: String? = null) {
    @Serializable data class Item(val variantId: String, val quantity: Int, val unitCost: String? = null)
}

@Serializable
data class AdjustmentResult(val number: String, val totalValue: String, val journalEntryId: String? = null)

// ------------------------------------------------------------ purchasing ---

@Serializable
data class Supplier(
    val id: String,
    val code: String,
    val name: String,
    val phone: String? = null,
    val contactName: String? = null,
    val isActive: Boolean = true,
    val balance: String = "0",
)

@Serializable
enum class PurchaseOrderStatus { DRAFT, ORDERED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED }

@Serializable
data class PurchaseOrderSummary(
    val id: String,
    val number: String,
    val status: PurchaseOrderStatus,
    val orderDate: String,
    val grandTotal: String,
    val amountPaid: String,
    val supplier: NameRef,
)

@Serializable
data class PurchaseItem(
    val id: String,
    val variantId: String,
    val quantity: Int,
    val receivedQty: Int,
    val unitCost: String,
    val taxRate: String,
    val lineTotal: String,
    val variant: Variant,
) {
    @Serializable data class Variant(val sku: String, val size: String? = null, val color: String? = null, val product: Name) {
        @Serializable data class Name(val nameAr: String)
    }
    val remaining: Int get() = quantity - receivedQty
}

@Serializable
data class SupplierPaymentView(val id: String, val number: String, val amount: String, val method: PaymentMethod, val paidAt: String)

@Serializable
data class PurchaseOrderDetail(
    val id: String,
    val number: String,
    val status: PurchaseOrderStatus,
    val supplierInvoiceNo: String? = null,
    val orderDate: String,
    val subtotal: String,
    val taxTotal: String,
    val shippingCost: String,
    val grandTotal: String,
    val amountPaid: String,
    val balanceDue: String,
    val note: String? = null,
    val supplier: PersonRef,
    val items: List<PurchaseItem>,
    val payments: List<SupplierPaymentView> = emptyList(),
    val journalEntries: List<JournalEntryView> = emptyList(),
) {
    val canReceive: Boolean get() = status == PurchaseOrderStatus.ORDERED || status == PurchaseOrderStatus.PARTIALLY_RECEIVED
}

@Serializable
data class ReceiveRequest(val items: List<Line>, val note: String? = null) {
    @Serializable data class Line(val itemId: String, val quantity: Int)
}

@Serializable
data class SupplierPaymentRequest(
    val supplierId: String,
    val method: PaymentMethod,
    val amount: String,
    val purchaseOrderId: String? = null,
    val accountId: String? = null,
    val reference: String? = null,
)

// -------------------------------------------------------------- expenses ---

@Serializable
enum class ExpenseStatus { DRAFT, APPROVED, PAID, CANCELLED }

@Serializable
data class ExpenseCategory(val id: String, val name: String, val isActive: Boolean = true)

@Serializable
data class ExpenseSummary(
    val id: String,
    val number: String,
    val status: ExpenseStatus,
    val amount: String,
    val taxAmount: String,
    val expenseDate: String,
    val description: String,
    val payee: String? = null,
    val category: Name,
) {
    @Serializable data class Name(val name: String)
}

@Serializable
data class ExpensePage(val items: List<ExpenseSummary>, val total: Int, val page: Int, val pageSize: Int, val totals: Totals) {
    @Serializable data class Totals(val amount: String, val taxAmount: String)
}

@Serializable
data class ExpenseDetail(
    val id: String,
    val number: String,
    val status: ExpenseStatus,
    val amount: String,
    val taxAmount: String,
    val total: String,
    val expenseDate: String,
    val description: String,
    val payee: String? = null,
    val category: ExpenseCategory,
    val createdBy: NameRef? = null,
    val approvedBy: NameRef? = null,
    val journalEntries: List<JournalEntryView> = emptyList(),
)

@Serializable
data class ExpenseRequest(
    val categoryId: String,
    val amount: String,
    val expenseDate: String,
    val description: String,
    val taxAmount: String? = null,
    val payee: String? = null,
)

@Serializable
data class PaidFrom(val paidFromAccountId: String? = null)

/** Cash box / bank / wallet account, for "paid from" pickers. */
@Serializable
data class LedgerAccount(val id: String, val code: String, val nameAr: String, val nameEn: String, val isCashOrBank: Boolean = false)

// ----------------------------------------------------- courier settlement ---

@Serializable
data class PendingCod(val carriers: List<Carrier>, val shipments: List<Shipment>) {
    @Serializable data class Carrier(val carrier: String, val shipments: Int, val collected: String)
    @Serializable data class Shipment(
        val id: String,
        val carrier: String,
        val codAmount: String,
        val deliveredAt: String? = null,
        val trackingNumber: String? = null,
        val order: Order,
    ) {
        @Serializable data class Order(val number: String, val customer: Name) {
            @Serializable data class Name(val name: String)
        }
    }
}

@Serializable
data class SettlementRequest(
    val carrier: String,
    val shipmentIds: List<String>,
    val accountId: String,
    val feesAmount: String? = null,
    val reference: String? = null,
)

@Serializable
data class Settlement(val id: String, val number: String, val carrier: String, val collectedAmount: String, val feesAmount: String, val netAmount: String)

// --------------------------------------------------------------- reports ---

@Serializable
data class ReportLine(val code: String, val nameAr: String, val amount: String)

@Serializable
data class ReportSection(val lines: List<ReportLine>, val total: String)

@Serializable
data class IncomeStatement(
    val revenue: ReportSection,
    val costOfSales: ReportSection,
    val grossProfit: String,
    val grossMarginPct: String? = null,
    val operatingExpenses: ReportSection,
    val netIncome: String,
    val netMarginPct: String? = null,
)

@Serializable
data class BalanceSheet(
    val assets: ReportSection,
    val liabilities: ReportSection,
    val equity: Equity,
    val totalLiabilitiesAndEquity: String,
    val balanced: Boolean,
) {
    @Serializable data class Equity(val lines: List<ReportLine>, val currentEarnings: String, val total: String)
}

@Serializable
data class SalesReport(val groups: List<Group>, val totals: Totals) {
    @Serializable data class Group(
        val key: String,
        val label: String,
        val orders: Int,
        val units: Int,
        val returnedUnits: Int = 0,
        val netSales: String,
        val costOfGoods: String,
        val grossProfit: String,
    )
    @Serializable data class Totals(val units: Int, val netSales: String, val costOfGoods: String, val grossProfit: String, val discounts: String)
}

// ------------------------------------------------------------- customers ---

@Serializable
data class AdminCustomerSummary(
    val id: String,
    val code: String,
    val name: String,
    val phone: String,
    val email: String? = null,
    val createdAt: String,
    @SerialName("_count") val count: OrdersCount? = null,
    /** Merchant (wholesale) customer. */
    val isMerchant: Boolean = false,
    /** Extra percent off wholesale prices. */
    val merchantDiscount: String = "0",
    /** Most the merchant may owe, SAR (null = no limit). */
    val creditLimit: String? = null,
) {
    @Serializable data class OrdersCount(val orders: Int)
}

@Serializable
data class AdminCustomerDetail(
    val id: String,
    val code: String,
    val name: String,
    val phone: String,
    val email: String? = null,
    val ledgerBalance: String,
    val lifetime: Lifetime,
    val orders: List<Order> = emptyList(),
    val isMerchant: Boolean = false,
    val merchantDiscount: String = "0",
    val creditLimit: String? = null,
    val notes: String? = null,
) {
    @Serializable data class Lifetime(val orders: Int, val spent: String)
    @Serializable data class Order(val id: String, val number: String, val status: OrderStatus, val grandTotal: String, val placedAt: String)
}

// ------------------------------------------------------------ currencies ---

@Serializable
data class ExchangeRates(val base: Currency, val currencies: List<CurrencyRate>, val history: List<Entry> = emptyList()) {
    @Serializable data class Entry(
        val id: String,
        val rate: String,
        val note: String? = null,
        val effectiveAt: String,
        val createdBy: PersonName? = null,
        val currency: Currency? = null,
    )
    @Serializable data class PersonName(val id: String, val name: String)

    fun rate(code: Currency): CurrencyRate? = currencies.firstOrNull { it.code == code }
}

@Serializable
data class SetRateRequest(val rate: String, val note: String? = null)
