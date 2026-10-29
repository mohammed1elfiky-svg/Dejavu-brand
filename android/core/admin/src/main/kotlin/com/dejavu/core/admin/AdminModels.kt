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
data class StaffLoginRequest(
    /** Mobile number (or username) the employee signs in with. */
    val phone: String,
    val password: String,
)

@Serializable
data class RoleRef(val id: String, val name: String)

@Serializable
data class StaffProfile(
    val id: String,
    val name: String,
    val email: String? = null,
    val phone: String? = null,
    val roles: List<RoleRef> = emptyList(),
    val permissions: List<String> = emptyList(),
    /** Currency the till opens with for this employee (null = SAR). */
    val saleCurrency: String? = null,
    /** The employee's own checkout layout. */
    val posSettings: PosSettings? = null,
) {
    fun can(permission: String) = permission in permissions
}

@Serializable
data class StaffAuthResponse(val accessToken: String, val refreshToken: String, val user: StaffProfile)

// ------------------------------------------------------------- dashboard ---

@Serializable
data class CashAccount(
    val accountId: String,
    val code: String,
    val nameAr: String,
    val nameEn: String,
    /** SAR book value. */
    val balance: String,
    /** Currency the box holds and how much of it (e.g. 100 USD). */
    val currency: Currency = Currency.SAR,
    val amount: String? = null,
    val icon: String? = null,
) {
    /** "100 $" for a dollar box, "380 ر.س" otherwise. */
    val shown: String get() = Money.plain(Money.parse(amount ?: balance), currency)
}

@Serializable
data class Dashboard(
    val today: Today,
    val ordersByStatus: Map<String, Int> = emptyMap(),
    val pendingReturns: Int = 0,
    val lowStockVariants: Int = 0,
    /** Exchange rates and whether each was updated in the last 24 hours. */
    val rates: List<RateStatus> = emptyList(),
    val cashAndBank: List<CashAccount> = emptyList(),
    val receivables: Receivables,
    val payables: Payables,
    val monthToDate: MonthToDate,
) {
    @Serializable data class Today(val orders: Int, val sales: String)
    @Serializable data class RateStatus(val currency: String, val nameAr: String? = null, val rate: String? = null, val effectiveAt: String? = null, val stale: Boolean = false)
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
    /** A merchant's order on credit waiting for the owner's approval. */
    val needsApproval: Boolean = false,
    val approvedAt: String? = null,
    /** HOME, OFFICE (a carrier office the customer picks it up from) or PICKUP (from the store). */
    val deliveryMethod: String = "HOME",
    /** The shipping company the customer chose. */
    val carrier: OrderCarrier? = null,
    val carrierOffice: Carrier.Office? = null,
) {
    val awaitingApproval: Boolean get() = needsApproval && approvedAt == null && status == OrderStatus.PENDING

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
    /** Pieces that can still be returned (sale looked up at the till). */
    val returnableQty: Int? = null,
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
    /** HOME, OFFICE or PICKUP. */
    val method: String? = null,
    /** OFFICE: the carrier and office the customer collects from. */
    val carrier: String? = null,
    val officeName: String? = null,
    val officePhones: List<String> = emptyList(),
    val phone: String,
    val governorate: String,
    val city: String,
    val street: String,
    val district: String? = null,
    val building: String? = null,
    val floor: String? = null,
    val apartment: String? = null,
    val landmark: String? = null,
    /** Where the shopper pinned the address, if they did. */
    val latitude: String? = null,
    val longitude: String? = null,
)

@Serializable
data class OrderCarrier(val id: String, val name: String, val phones: List<String> = emptyList())

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
    /** Photo of the shipping receipt sent to the customer. */
    val shippingReceiptFileId: String? = null,
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
    /** A merchant's order on credit waiting for the owner: approve, change quantities or reject. */
    val needsApproval: Boolean = false,
    val approvedAt: String? = null,
    /** HOME, OFFICE (a carrier office the customer picks it up from) or PICKUP (from the store). */
    val deliveryMethod: String = "HOME",
    /** The shipping company the customer chose. */
    val carrier: OrderCarrier? = null,
    val carrierOffice: Carrier.Office? = null,
) {
    val awaitingApproval: Boolean get() = needsApproval && approvedAt == null && status == OrderStatus.PENDING

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
data class OrderLineQuantity(val itemId: String, val quantity: Int)

@Serializable
data class EditOrderItemsRequest(val lines: List<OrderLineQuantity>)

@Serializable
data class OptionalReason(val reason: String? = null)

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

/** Where a product can be sold. */
@Serializable
enum class ProductVisibility(val label: String) {
    ALL("الكاشير وتطبيق العملاء"),
    POS_ONLY("الكاشير فقط"),
    MERCHANTS("الكاشير وتطبيق العملاء (التجار فقط)"),
    HIDDEN("مخفي تماماً"),
}

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
    val brand: BrandRef? = null,
    val videoUrl: String? = null,
    val instagramUrl: String? = null,
    val visibility: ProductVisibility = ProductVisibility.ALL,
    /** Cost per piece in SAR, shared by all variants. */
    val costPrice: String = "0",
    /** After a save with notifyCustomers: how many customers were notified (0 = not published, nothing sent). */
    val notifiedCustomers: Int? = null,
    /** Bag size in cm as shown to shoppers, e.g. "30 × 20 × 10". */
    val bagSize: String? = null,
    /** Heel height in cm, e.g. "7.5". */
    val heelHeightCm: String? = null,
)

@Serializable
data class UpdateProductRequest(
    val status: ProductStatus? = null,
    /** "" clears it. */
    val brandId: String? = null,
    /** Push a "new product" notification to customers (only sent when published). */
    val notifyCustomers: Boolean? = null,
    /** "" clears it. */
    val videoUrl: String? = null,
    /** "" clears it. */
    val instagramUrl: String? = null,
    val visibility: ProductVisibility? = null,
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
    /** "" clears it. */
    val bagSize: String? = null,
    /** "" clears it. */
    val heelHeightCm: String? = null,
)

/** Category tree node from GET admin/categories. */
@Serializable
data class AdminCategory(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val slug: String,
    val children: List<AdminCategory> = emptyList(),
    val parentId: String? = null,
    /** Shown in the customer app (hidden categories hide their products there too). */
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    val imageUrl: String? = null,
) {
    /** This category and all below it, depth-first, with their depth (for an indented picker). */
    fun flatten(depth: Int = 0): List<Pair<AdminCategory, Int>> = listOf(this to depth) + children.flatMap { it.flatten(depth + 1) }
}

/** A product's brand as shown on the product. */
@Serializable
data class BrandRef(val id: String, val name: String, val slug: String, val logoUrl: String? = null)

/** Brand from GET admin/brands. */
@Serializable
data class AdminBrand(
    val id: String,
    val name: String,
    val slug: String,
    val logoUrl: String? = null,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val productCount: Int = 0,
)

/** Create or edit a brand; on edit, null leaves a field as is and logoUrl "" removes the logo. */
@Serializable
data class BrandRequest(
    val name: String? = null,
    val logoUrl: String? = null,
    val sortOrder: Int? = null,
    val isActive: Boolean? = null,
)

/** Typed by the admin to confirm wiping the test data. */
const val WIPE_PHRASE = "حذف البيانات التجريبية"

/** ALL: everything incl. customers and staff; OPERATIONS: catalog and transactions only. */
@Serializable
enum class WipeScope { ALL, OPERATIONS }

@Serializable
data class WipeRequest(val confirm: String, val dryRun: Boolean = false, val scope: WipeScope = WipeScope.ALL)

@Serializable
data class WipeSummary(
    val dryRun: Boolean,
    val scope: WipeScope = WipeScope.ALL,
    val deleted: Map<String, Int> = emptyMap(),
    val keptUsers: List<String> = emptyList(),
)

@Serializable
data class CreateCategoryRequest(val nameAr: String, val nameEn: String, val slug: String, val parentId: String? = null)

/** PATCH admin/categories/{id}: rename, show/hide in the customer app; null leaves a field as is. */
@Serializable
data class UpdateCategoryRequest(
    val nameAr: String? = null,
    val nameEn: String? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null,
    /** Picture / icon shown with the category in the customer app; "" removes it. */
    val imageUrl: String? = null,
)

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
    /** Defaults to the Arabic name on the server. */
    val nameEn: String? = null,
    /** Generated from the name and SKU number on the server when null. */
    val slug: String? = null,
    val basePrice: String,
    val variants: List<NewVariantRequest>,
    val costPrice: String? = null,
    val wholesalePrice: String? = null,
    val descriptionAr: String? = null,
    val compareAtPrice: String? = null,
    val status: ProductStatus? = null,
    val isFeatured: Boolean? = null,
    val images: List<ImageRequest>? = null,
    val brandId: String? = null,
    val notifyCustomers: Boolean? = null,
    val videoUrl: String? = null,
    val instagramUrl: String? = null,
    val visibility: ProductVisibility? = null,
    val bagSize: String? = null,
    val heelHeightCm: String? = null,
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
    /** Agent / supplier commission, percent of the goods value (import costs). */
    val commissionPercent: String = "0",
    val email: String? = null,
    val address: String? = null,
    val kind: SupplierKind = SupplierKind.SUPPLIER,
)

/** A goods supplier (dealt with in yuan) or a shipping company (freight and customs of imports, in dollars). */
@Serializable
enum class SupplierKind { SUPPLIER, SHIPPING_COMPANY }

/** GET admin/suppliers/{id}: the supplier with its latest orders. */
@Serializable
data class SupplierDetail(
    val id: String,
    val code: String,
    val name: String,
    val phone: String? = null,
    val contactName: String? = null,
    val isActive: Boolean = true,
    val balance: String = "0",
    val commissionPercent: String = "0",
    val kind: SupplierKind = SupplierKind.SUPPLIER,
    val purchaseOrders: List<SupplierOrder> = emptyList(),
) {
    @Serializable
    data class SupplierOrder(val id: String, val number: String, val status: PurchaseOrderStatus, val orderDate: String, val grandTotal: String, val amountPaid: String)
}

@Serializable
data class UpdateSupplierRequest(
    val name: String? = null,
    val phone: String? = null,
    val contactName: String? = null,
    val commissionPercent: String? = null,
    val isActive: Boolean? = null,
)

/** LOCAL: bought in SAR. IMPORT: bought abroad; landed cost set when the shipment arrives. */
@Serializable
enum class PurchaseKind { LOCAL, IMPORT }

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
    val kind: PurchaseKind = PurchaseKind.LOCAL,
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
    /** Import orders: supplier price per piece in the order currency. */
    val unitPriceForeign: String? = null,
    val cbm: String? = null,
    /** Import orders: final cost per piece (SAR) once received. */
    val landedUnitCost: String? = null,
    /** A new product created with this order (a draft until received). */
    val createdProduct: Boolean = false,
    /** Import orders: the factory / note shown on the supplier's statement. */
    val note: String? = null,
) {
    @Serializable data class Variant(val sku: String, val size: String? = null, val color: String? = null, val product: Name) {
        @Serializable
        data class Name(val nameAr: String, val id: String? = null, val skuNumber: Int? = null, val images: List<ImageUrl> = emptyList())
    }
    val remaining: Int get() = quantity - receivedQty
    val option: String get() = listOfNotNull(variant.size, variant.color).joinToString(" · ")
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
    val kind: PurchaseKind = PurchaseKind.LOCAL,
    val foreignCurrency: String? = null,
    val foreignPerUsd: String? = null,
    val sarPerUsd: String? = null,
    val commissionPercent: String = "0",
    val domesticShipping: String = "0",
    val totalCbm: String? = null,
    val cbmRateUsd: String? = null,
    val freightCost: String = "0",
    val receivedAt: String? = null,
    val expectedDate: String? = null,
    /** Photo of the supplier's invoice. */
    val invoiceImageUrl: String? = null,
    /** Import orders: the shipping company owed the freight and customs. */
    val shippingCompanyId: String? = null,
) {
    val canReceive: Boolean get() = status == PurchaseOrderStatus.ORDERED || status == PurchaseOrderStatus.PARTIALLY_RECEIVED
    val isImport: Boolean get() = kind == PurchaseKind.IMPORT
}

// ------------------------------------------------------------- imports ---

@Serializable
data class ImportItemRequest(val variantId: String, val quantity: Int, val unitPriceForeign: String, val note: String? = null)

@Serializable
data class ImportNewVariant(val size: String? = null, val color: String? = null, val colorHex: String? = null, val quantity: Int)

/** A product that does not exist yet: created as a draft and sold only once received. */
@Serializable
data class ImportNewProduct(
    val categoryId: String,
    val nameAr: String,
    val basePrice: String,
    val unitPriceForeign: String,
    val variants: List<ImportNewVariant>,
    val imageUrl: String? = null,
    val brandId: String? = null,
    /** Factory name / note, shown on the supplier's statement. */
    val note: String? = null,
)

@Serializable
data class ImportOrderRequest(
    val supplierId: String,
    /** Optional: the yuan rate is set on the payment vouchers; null = the last voucher's rate. */
    val foreignPerUsd: String? = null,
    val foreignCurrency: String? = null,
    /** Null = the current USD rate from the currency settings. */
    val sarPerUsd: String? = null,
    /** Commission is billed in USD per period (supplier vouchers); normally left null (0). */
    val commissionPercent: String? = null,
    val domesticShipping: String? = null,
    val supplierInvoiceNo: String? = null,
    val note: String? = null,
    val items: List<ImportItemRequest> = emptyList(),
    val newProducts: List<ImportNewProduct> = emptyList(),
    /** Photo of the supplier's invoice ("admin/files/<id>"). */
    val invoiceImageUrl: String? = null,
    /** Shipping company owed the freight and customs (kind SHIPPING_COMPANY). */
    val shippingCompanyId: String? = null,
)

/** Counted pieces and the costs known on arrival (preview and "receive & confirm"). */
@Serializable
data class ImportReceiveRequest(
    val items: List<Line>,
    val cbmRateUsd: String,
    val totalCbm: String? = null,
    val foreignPerUsd: String? = null,
    val sarPerUsd: String? = null,
    val commissionPercent: String? = null,
    val domesticShipping: String? = null,
    val note: String? = null,
    /** Shipping company owed the freight and customs (in dollars); the order's one by default. */
    val shippingCompanyId: String? = null,
) {
    @Serializable data class Line(val itemId: String, val quantity: Int, val cbm: String? = null)
}

@Serializable
data class ImportCostLine(
    val itemId: String,
    val ordered: Int,
    val quantity: Int,
    val goods: String,
    val domestic: String,
    val commission: String,
    val freight: String,
    val total: String,
    val cbm: String,
    val unitCost: String,
)

@Serializable
data class ImportCostTotals(val goods: String, val domestic: String, val commission: String, val freight: String, val total: String, val cbm: String)

@Serializable
data class ImportCosts(
    val foreignPerUsd: String,
    val sarPerUsd: String,
    val commissionPercent: String,
    val lines: List<ImportCostLine>,
    val totals: ImportCostTotals,
)

/** Ordered from a supplier, not received yet. */
@Serializable
data class PendingItem(
    val id: String,
    val quantity: Int,
    val receivedQty: Int,
    val pendingQty: Int,
    val unitPriceForeign: String? = null,
    val unitCost: String = "0",
    val createdProduct: Boolean = false,
    val purchaseOrder: Order,
    val variant: VariantRef,
    val product: ProductRef,
) {
    @Serializable
    data class Order(val id: String, val number: String, val orderDate: String, val expectedDate: String? = null, val kind: PurchaseKind = PurchaseKind.LOCAL, val foreignCurrency: String? = null)

    @Serializable data class VariantRef(val id: String, val sku: String, val size: String? = null, val color: String? = null)

    @Serializable data class ProductRef(val id: String, val nameAr: String, val imageUrl: String? = null)

    val option: String get() = listOfNotNull(variant.size, variant.color).joinToString(" · ")
}

@Serializable
data class PendingItems(val items: List<PendingItem> = emptyList(), val totalPieces: Int = 0)

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
    /** ACTIVE, PENDING (signed up, never entered the code) or BLOCKED. */
    val status: String = "ACTIVE",
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
    val status: String = "ACTIVE",
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

/** PUT admin/purchase-orders/{id}/import: lines (existing new-item lines are sent again in items), costs and notes. */
@Serializable
data class UpdateImportOrderRequest(
    val items: List<ImportItemRequest>,
    val newProducts: List<ImportNewProduct> = emptyList(),
    val domesticShipping: String? = null,
    val supplierInvoiceNo: String? = null,
    val note: String? = null,
    /** "" removes the invoice photo. */
    val invoiceImageUrl: String? = null,
    val shippingCompanyId: String? = null,
)

// ------------------------------------------------- supplier own account ---

/** GET admin/suppliers/{id}/account: what is matched with the supplier, per currency (+ = we owe). */
@Serializable
data class SupplierAccount(
    val balances: List<Balance> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val vouchers: List<SupplierVoucher> = emptyList(),
    /** Yuan per dollar of the latest payment voucher. */
    val lastRate: String? = null,
) {
    @Serializable data class Balance(val currency: String, val balance: String)

    @Serializable
    data class Entry(
        val id: String,
        val currency: String,
        /** + adds to what we owe (orders, commission), − takes off (payments, shortages). */
        val amount: String,
        /** PURCHASE_ORDER, SHORTAGE or VOUCHER. */
        val sourceType: String,
        val sourceId: String? = null,
        val note: String? = null,
        val createdAt: String,
    )
}

@Serializable
enum class SupplierVoucherKind { PAYMENT, COMMISSION }

@Serializable
data class SupplierVoucher(
    val id: String,
    val number: String,
    val supplierId: String,
    val kind: SupplierVoucherKind,
    val usdAmount: String,
    val foreignCurrency: String? = null,
    val foreignPerUsd: String? = null,
    val foreignAmount: String? = null,
    val cashBoxId: String? = null,
    val periodFrom: String? = null,
    val periodTo: String? = null,
    val note: String? = null,
    val cancelledAt: String? = null,
    val createdAt: String,
) {
    val isCancelled: Boolean get() = cancelledAt != null
}

/** Payment voucher: dollars from a USD box at the day's yuan rate. */
@Serializable
data class SupplierPaymentVoucherRequest(
    val supplierId: String,
    val usdAmount: String,
    val cashBoxId: String,
    /** Required when applyTo = GOODS. */
    val foreignPerUsd: String? = null,
    val foreignCurrency: String? = null,
    /** GOODS (takes yuan off) or COMMISSION (pays the dollar commission). */
    val applyTo: String = "GOODS",
    val note: String? = null,
)

/** The agent's commission in USD for a period (yyyy-MM-dd). */
@Serializable
data class SupplierCommissionRequest(
    val supplierId: String,
    val usdAmount: String,
    val periodFrom: String,
    val periodTo: String,
    val note: String? = null,
)

// ------------------------------------------------------------------- SMS ---

/** A text the gateway phone sends now. */
@Serializable
data class SmsToSend(val id: String, val phone: String, val body: String, val purpose: String)

@Serializable
data class SmsResultRequest(val ok: Boolean, val error: String? = null)

@Serializable
data class OutgoingSms(
    val id: String,
    val phone: String,
    val body: String,
    val purpose: String,
    /** PENDING, SENDING, SENT or FAILED. */
    val status: String,
    val error: String? = null,
    val createdAt: String,
    val sentAt: String? = null,
)

@Serializable
data class SmsSettings(
    val requirePhoneOtp: Boolean = false,
    val gatewaySeenAt: String? = null,
    val gatewayOnline: Boolean = false,
    val pending: Int = 0,
)

@Serializable
data class SmsSettingsRequest(val requirePhoneOtp: Boolean? = null)

// ------------------------------------------------------------- coupons ---

/** PERCENTAGE, FIXED_AMOUNT (SAR) or FREE_SHIPPING. */
@Serializable
enum class CouponType { PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING }

@Serializable
data class AdminCoupon(
    val id: String,
    val code: String,
    val description: String? = null,
    val type: CouponType,
    val value: String,
    val minOrderAmount: String? = null,
    val maxDiscount: String? = null,
    val usageLimit: Int? = null,
    val perCustomerLimit: Int? = null,
    val usedCount: Int = 0,
    val startsAt: String,
    val endsAt: String? = null,
    val isActive: Boolean = true,
)

/** Create (all required fields) or edit (null leaves a field as is). Dates are ISO-8601. */
@Serializable
data class CouponRequest(
    val code: String? = null,
    val description: String? = null,
    val type: CouponType? = null,
    val value: String? = null,
    val minOrderAmount: String? = null,
    val maxDiscount: String? = null,
    val usageLimit: Int? = null,
    val perCustomerLimit: Int? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val isActive: Boolean? = null,
)

// -------------------------------------------------------- revenue report ---

/** GET admin/reports/revenue: till vs app, retail vs merchants (SAR, net of tax and returns). */
@Serializable
data class RevenueReport(
    val from: String,
    val to: String,
    val pos: Channel,
    val app: Channel,
    val merchants: Cell,
    val retail: Cell,
    val total: Cell,
) {
    @Serializable data class Channel(val retail: Cell, val merchants: Cell, val total: Cell)

    @Serializable
    data class Cell(val orders: Int = 0, val units: Int = 0, val revenue: String = "0", val cost: String = "0", val grossProfit: String = "0")
}

/** POST admin/suppliers/{id}/opening-balance. */
@Serializable
data class SupplierOpeningBalanceRequest(
    val currency: String,
    val amount: String,
    /** WE_OWE (we owe the supplier) or SUPPLIER_OWES (we paid ahead). */
    val direction: String,
    /** SAR value if set by hand; otherwise worked out from the rates. */
    val sarAmount: String? = null,
    /** CNY: yuan per dollar; null = the last payment voucher's rate. */
    val foreignPerUsd: String? = null,
    val note: String? = null,
)

// ----------------------------------------------------------------- audit ---

@Serializable
data class AuditEntry(
    val id: String,
    val action: String,
    val entityType: String,
    val entityId: String? = null,
    val before: kotlinx.serialization.json.JsonElement? = null,
    val after: kotlinx.serialization.json.JsonElement? = null,
    val createdAt: String,
    val user: NameRef? = null,
)

@Serializable
data class AuditType(val entityType: String, val count: Int = 0)

// --------------------------------------------------------------- debtors ---

@Serializable
data class Debtors(val items: List<Debtor> = emptyList(), val totalOwed: String = "0")

@Serializable
data class Debtor(
    val id: String,
    val code: String,
    val name: String,
    val phone: String? = null,
    val isMerchant: Boolean = false,
    val balances: List<Balance> = emptyList(),
    val owedSar: String = "0",
    val lastPaymentAt: String? = null,
    val lastPurchaseAt: String? = null,
    val daysSincePayment: Int? = null,
) {
    @Serializable data class Balance(val currency: String, val balance: String)
}

@Serializable
data class DebtRemindersRequest(val customerIds: List<String>)

@Serializable
data class DebtRemindersResult(val queued: Int = 0, val skipped: List<Skipped> = emptyList()) {
    @Serializable data class Skipped(val customerId: String, val reason: String)
}

// ---------------------------------------------------------- daily summary ---

@Serializable
data class DailySummarySettings(val enabled: Boolean = false, val phone: String? = null, val hour: Int = 21)

@Serializable
data class DailySummaryText(val text: String)

// --------------------------------------------------------- product reports ---

@Serializable
data class ProductsReport(
    val from: String,
    val to: String,
    val deadDays: Int = 60,
    val topSellers: List<TopSeller> = emptyList(),
    val deadStock: List<DeadItem> = emptyList(),
    /** Products that made the most profit. */
    val topProfit: List<TopSeller> = emptyList(),
    /** Categories by pieces sold, and by profit. */
    val topCategories: List<CategorySales> = emptyList(),
    val profitableCategories: List<CategorySales> = emptyList(),
) {
    @Serializable
    data class CategorySales(
        val categoryId: String,
        val nameAr: String,
        val units: Int,
        val products: Int = 0,
        val revenue: String,
        val cost: String,
        val grossProfit: String,
        val margin: String,
    )

    @Serializable
    data class TopSeller(
        val productId: String,
        val nameAr: String,
        val skuNumber: Int? = null,
        val imageUrl: String? = null,
        val units: Int,
        val revenue: String,
        val cost: String,
        val grossProfit: String,
        val margin: String,
        val onHand: Int = 0,
    )

    @Serializable
    data class DeadItem(
        val productId: String,
        val nameAr: String,
        val skuNumber: Int? = null,
        val imageUrl: String? = null,
        val onHand: Int,
        val stockValue: String,
        val lastSoldAt: String? = null,
    )
}

@Serializable
data class ReorderReport(val days: Int, val coverDays: Int, val items: List<Item> = emptyList()) {
    @Serializable
    data class Item(
        val variantId: String,
        val productId: String,
        val nameAr: String,
        val sku: String,
        val size: String? = null,
        val color: String? = null,
        val imageUrl: String? = null,
        val sold: Int,
        val perDay: Double,
        val onHand: Int,
        val incoming: Int,
        val daysLeft: Int? = null,
        val suggested: Int,
        val lastSupplier: String? = null,
    )
}

@Serializable
data class RestoreResult(val restored: Boolean = false, val backupCreatedAt: String? = null)

// ----------------------------------------------------------- stock counts ---

@Serializable
data class StockCountSummary(
    val id: String,
    val number: String,
    val isPosted: Boolean = false,
    val note: String? = null,
    val countedAt: String,
    val postedAt: String? = null,
    val warehouse: Wh? = null,
    val countedBy: NameRef? = null,
    @SerialName("_count") val counts: Counts? = null,
) {
    @Serializable data class Wh(val id: String? = null, val code: String? = null, val name: String)
    @Serializable data class Counts(val items: Int = 0)
}

@Serializable
data class StockCountDetail(
    val id: String,
    val number: String,
    val isPosted: Boolean = false,
    val note: String? = null,
    val countedAt: String,
    val postedAt: String? = null,
    val warehouse: StockCountSummary.Wh? = null,
    val countedBy: NameRef? = null,
    val items: List<Item> = emptyList(),
) {
    @Serializable
    data class Item(
        val id: String,
        val variantId: String,
        val systemQty: Int,
        val countedQty: Int,
        /** counted − on the books (+ surplus, − shortage). */
        val difference: Int = 0,
        /** SAR value of the difference at average cost. */
        val differenceValue: String = "0",
        val variant: V,
    ) {
        @Serializable data class V(val id: String, val sku: String, val size: String? = null, val color: String? = null, val product: P)
        @Serializable data class P(val id: String, val nameAr: String)
    }
}

/** A count of the pieces on the shelf; posting it books the differences. */
@Serializable
data class CreateStockCountRequest(val warehouseId: String, val items: List<Line>, val note: String? = null) {
    @Serializable data class Line(val variantId: String, val countedQty: Int)
}

// ----------------------------------------------------------- staff report ---

/** GET admin/reports/staff: each cashier's period at the till. */
@Serializable
data class StaffReport(val from: String, val to: String, val staff: List<Row> = emptyList()) {
    @Serializable
    data class Row(
        val user: NameRef,
        val invoices: Int = 0,
        val voided: Int = 0,
        val revenue: String = "0",
        val grossProfit: String = "0",
        val averageInvoice: String = "0",
        val discounts: String = "0",
        /** + over, − short, per currency. */
        val shiftDifferences: List<Diff> = emptyList(),
        val closedShifts: Int = 0,
    )

    @Serializable data class Diff(val currency: String, val difference: String)
}

// ------------------------------------------------------- customer app setup ---

@Serializable
data class BankAccountDeleted(val deleted: Boolean = false, val hidden: Boolean = false)

@Serializable
data class StorePaymentMethodsRequest(val methods: List<com.dejavu.core.model.PaymentMethod>)

/** A customer's review waiting for approval or shown in the store. */
@Serializable
data class AdminReview(
    val id: String,
    val rating: Int,
    val comment: String? = null,
    val images: List<String> = emptyList(),
    val isApproved: Boolean = false,
    val createdAt: String,
    val customer: PersonRef? = null,
    val product: Product? = null,
) {
    @Serializable data class Product(val id: String, val nameAr: String, val slug: String, val images: List<ImageUrl> = emptyList())
}

@Serializable
data class ApproveReviewRequest(val approved: Boolean)

// ------------------------------------------------------------------ home ---

/** GET admin/home/search: everything matching one search box (only what the user may see). */
@Serializable
data class HomeSearch(
    val products: List<P> = emptyList(),
    val customers: List<C> = emptyList(),
    val orders: List<O> = emptyList(),
    val suppliers: List<S> = emptyList(),
    val purchaseOrders: List<PO> = emptyList(),
) {
    val isEmpty: Boolean get() = products.isEmpty() && customers.isEmpty() && orders.isEmpty() && suppliers.isEmpty() && purchaseOrders.isEmpty()

    @Serializable data class P(val id: String, val nameAr: String, val skuNumber: Int? = null, val status: String? = null, val basePrice: String = "0", val imageUrl: String? = null)
    @Serializable data class C(val id: String, val name: String, val phone: String? = null, val code: String? = null, val isMerchant: Boolean = false)
    @Serializable
    data class O(
        val id: String,
        val number: String,
        val status: String,
        /** POS (till) or ONLINE (customer app). */
        val channel: String,
        val grandTotal: String,
        val grandTotalInCurrency: String = grandTotal,
        val currency: String = "SAR",
        val placedAt: String,
        val customer: NameRef? = null,
    )
    @Serializable data class S(val id: String, val name: String, val code: String? = null, val kind: SupplierKind = SupplierKind.SUPPLIER)
    @Serializable data class PO(val id: String, val number: String, val status: String, val kind: String = "LOCAL", val supplier: NameRef? = null)
}

/** Counters for badges on the home screen and menu. */
@Serializable
data class HomeBadges(
    val newOrders: Int = 0,
    val topUps: Int = 0,
    val reviews: Int = 0,
    val openShifts: Int = 0,
    val smsWaiting: Int = 0,
    val returns: Int = 0,
    val lowStock: Int = 0,
)

/** GET admin/home/daily-sales: revenue per day (SAR, net of tax) and the total of the period before. */
@Serializable
data class DailySales(val days: List<Day> = emptyList(), val total: String = "0", val previousTotal: String = "0") {
    @Serializable data class Day(val date: String, val revenue: String, val orders: Int = 0)
}

// ------------------------------------------------------------ customer access ---

/** POST admin/customers/{id}/password: the new random password (shown once). */
@Serializable
data class CustomerPassword(val password: String, val smsQueued: Boolean = false, val phone: String = "", val message: String = "")

/** POST admin/customers/{id}/code: a code to hand over yourself (e.g. on WhatsApp). */
@Serializable
data class CustomerCode(val code: String, val phone: String = "", val expiresInMinutes: Int = 10, val message: String = "")

@Serializable
data class SendPasswordRequest(val sendSms: Boolean = true)

@Serializable
data class IssueCodeRequest(val purpose: String? = null)

/** GET admin/store-order: products placed by hand, in their order. */
@Serializable
data class StoreOrderItem(val id: String, val nameAr: String, val slug: String = "", val status: String = "ACTIVE", val imageUrl: String? = null)

@Serializable
data class StoreOrderRequest(val productIds: List<String>)
