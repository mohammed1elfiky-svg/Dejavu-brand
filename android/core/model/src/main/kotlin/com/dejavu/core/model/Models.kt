package com.dejavu.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire models for the Dejavu customer API (/api/v1/store and /auth).
 * Money is a decimal string exactly as the API sends it ("1140", "99.5");
 * use [Money] to do arithmetic or display it. Timestamps are ISO-8601 strings.
 */

// ------------------------------------------------------------------ auth ---

@Serializable
data class RegisterRequest(
    val name: String,
    val phone: String,
    val password: String,
    val email: String? = null,
    /** One of [Governorate.name]. */
    val governorate: String? = null,
    /** Taiz only: الحوبان or المدينة. */
    val area: String? = null,
    /** Code texted to the phone, when sign-up asks for one. */
    val otp: String? = null,
)

/** GET auth/governorates: Yemeni governorates and the rial each uses (Taiz is split in areas). */
@Serializable
data class Governorate(val name: String, val currency: String, val areas: List<Area> = emptyList()) {
    @Serializable data class Area(val name: String, val currency: String)
}

@Serializable
data class OtpRequired(val required: Boolean = false)

@Serializable
data class OtpRequest(val phone: String)

/** Forgotten password: the texted code and the new password. */
@Serializable
data class PasswordResetRequest(val phone: String, val code: String, val newPassword: String)

@Serializable
data class OtpSent(val sent: Boolean = true, val expiresInMinutes: Int = 10)

@Serializable
data class LoginRequest(val phone: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: String? = null,
    val customer: Customer,
)

@Serializable
data class Customer(
    val id: String,
    val code: String,
    val name: String,
    val phone: String,
    val email: String? = null,
    val loyaltyPoints: Int = 0,
    val phoneVerified: Boolean = false,
    /** Merchant (wholesale) customer: wholesale prices and may order on credit. */
    val isMerchant: Boolean = false,
    val creditLimit: String? = null,
    val governorate: String? = null,
    val area: String? = null,
    /** The rial of the customer's region (YER old notes / YEQ new notes); null = unknown. */
    val preferredCurrency: String? = null,
)

// --------------------------------------------------------------- catalog ---

@Serializable
data class Page<T>(val items: List<T>, val total: Int, val page: Int, val pageSize: Int) {
    val hasMore: Boolean get() = page * pageSize < total
}

@Serializable
data class Category(
    val id: String,
    val parentId: String? = null,
    val slug: String,
    val nameAr: String,
    val nameEn: String,
    val imageUrl: String? = null,
    val children: List<Category> = emptyList(),
)

@Serializable
data class CategoryRef(val id: String, val slug: String, val nameAr: String, val nameEn: String)

/** A brand shoppers can browse by (GET store/brands, and on each product). */
@Serializable
data class Brand(val id: String, val name: String, val slug: String, val logoUrl: String? = null)

@Serializable
data class ProductImage(val url: String, val altText: String? = null, val variantId: String? = null, val color: String? = null)

@Serializable
data class StoreVariant(
    val id: String,
    val sku: String,
    val size: String? = null,
    val color: String? = null,
    val colorHex: String? = null,
    val price: String,
    /** Price before a running promotion. */
    val compareAtPrice: String? = null,
    val availableQty: Int,
    val inStock: Boolean,
)

@Serializable
data class PromotionTag(val name: String, val percent: String, val endsAt: String? = null)

@Serializable
data class StoreProduct(
    val id: String,
    val slug: String,
    val nameAr: String,
    val nameEn: String,
    val descriptionAr: String? = null,
    val descriptionEn: String? = null,
    val basePrice: String,
    val compareAtPrice: String? = null,
    val isFeatured: Boolean = false,
    val ratingAvg: String = "0",
    val ratingCount: Int = 0,
    val category: CategoryRef,
    val brand: Brand? = null,
    val images: List<ProductImage> = emptyList(),
    val variants: List<StoreVariant> = emptyList(),
    val inStock: Boolean,
    val promotion: PromotionTag? = null,
    /** Prices shown are the merchant's wholesale prices. */
    val merchantPrice: Boolean = false,
    /** Bag size in cm, e.g. "30 × 20 × 10". */
    val bagSize: String? = null,
    /** Heel height in cm, e.g. "7.5". */
    val heelHeightCm: String? = null,
) {
    /** Photos to show for [color]: the ones tagged with it first, then the general ones. */
    fun imagesFor(color: String?): List<ProductImage> {
        if (color == null) return images
        val tagged = images.filter { it.color == color }
        return if (tagged.isEmpty()) images else tagged + images.filter { it.color == null }
    }
}

@Serializable
data class ShippingZone(val governorate: String, val fee: String, val estimatedDays: Int, val isActive: Boolean = true)

// ------------------------------------------------------------- addresses ---

@Serializable
data class Address(
    val id: String,
    val label: String? = null,
    val recipient: String,
    val phone: String,
    val governorate: String,
    val city: String,
    val district: String? = null,
    val street: String,
    val building: String? = null,
    val floor: String? = null,
    val apartment: String? = null,
    val landmark: String? = null,
    val isDefault: Boolean = false,
) {
    /** One-line address for lists: "شارع عباس العقاد 15، مدينة نصر، القاهرة" */
    val summary: String
        get() = listOfNotNull(
            street,
            building?.let { "مبنى $it" },
            district,
            city,
            governorate,
        ).joinToString("، ")
}

@Serializable
data class AddressRequest(
    val recipient: String,
    val phone: String,
    val governorate: String,
    val city: String,
    val street: String,
    val label: String? = null,
    val district: String? = null,
    val building: String? = null,
    val floor: String? = null,
    val apartment: String? = null,
    val landmark: String? = null,
    val isDefault: Boolean? = null,
)

// ------------------------------------------------------------------ cart ---

@Serializable
data class AddCartItemRequest(val variantId: String, val quantity: Int)

@Serializable
data class QuantityRequest(val quantity: Int)

@Serializable
data class CouponRequest(val code: String)

@Serializable
data class CartItem(
    val variantId: String,
    val sku: String,
    val productName: String,
    val nameAr: String,
    val nameEn: String,
    val slug: String,
    val size: String? = null,
    val color: String? = null,
    val imageUrl: String? = null,
    val quantity: Int,
    val availableQty: Int,
    val problem: String? = null,
    val unitPrice: String,
    val discount: String,
    val lineTotal: String,
)

@Serializable
data class ShippingQuote(val governorate: String, val fee: String, val estimatedDays: Int)

@Serializable
data class Totals(
    val subtotal: String,
    val discountTotal: String,
    val shippingFee: String,
    val taxTotal: String,
    val grandTotal: String,
)

@Serializable
data class Cart(
    val items: List<CartItem>,
    val couponCode: String? = null,
    val couponProblem: String? = null,
    val shipping: ShippingQuote? = null,
    val shippingProblem: String? = null,
    val totals: Totals,
    val canCheckout: Boolean,
    val problems: List<String> = emptyList(),
) {
    val itemCount: Int get() = items.sumOf { it.quantity }
}

// ---------------------------------------------------------------- orders ---

@Serializable
enum class OrderStatus { PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, COMPLETED, CANCELLED, RETURNED }

@Serializable
enum class PaymentStatus { UNPAID, PARTIALLY_PAID, PAID, PARTIALLY_REFUNDED, REFUNDED }

@Serializable
enum class PaymentMethod { CASH_ON_DELIVERY, CARD, WALLET, BANK_TRANSFER, INSTAPAY, CASH, CUSTOMER_ACCOUNT }

@Serializable
data class CheckoutRequest(
    val addressId: String,
    val paymentMethod: PaymentMethod,
    val customerNote: String? = null,
    /** Invoice currency; the current rate is locked into the order. */
    val currency: Currency? = null,
)

@Serializable
data class ReasonRequest(val reason: String)

@Serializable
data class ItemCount(val items: Int)

@Serializable
data class OrderSummary(
    val id: String,
    val number: String,
    val status: OrderStatus,
    val paymentStatus: PaymentStatus,
    val grandTotal: String,
    val placedAt: String,
    @SerialName("_count") val count: ItemCount? = null,
    val currency: Currency = Currency.SAR,
    val exchangeRate: String = "1",
) {
    /** A SAR amount of this order in its own currency, at its locked rate. */
    fun format(amountSar: String?): String = Money.format(amountSar, currency, exchangeRate)
}

@Serializable
data class OrderItem(
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
    val returnedQty: Int = 0,
) {
    val returnableQty: Int get() = quantity - returnedQty
}

@Serializable
data class StatusChange(val toStatus: OrderStatus, val createdAt: String)

@Serializable
data class Shipment(
    val carrier: String,
    val trackingNumber: String? = null,
    val status: String,
    val shippedAt: String? = null,
    val deliveredAt: String? = null,
)

@Serializable
data class AddressSnapshot(
    val recipient: String,
    val phone: String,
    val governorate: String,
    val city: String,
    val street: String,
    val district: String? = null,
    val building: String? = null,
)

@Serializable
data class CouponRef(val code: String)

@Serializable
data class OrderDetail(
    val id: String,
    val number: String,
    val status: OrderStatus,
    val paymentStatus: PaymentStatus,
    val paymentMethod: PaymentMethod,
    val shippingAddress: AddressSnapshot,
    val subtotal: String,
    val discountTotal: String,
    val shippingFee: String,
    val taxTotal: String,
    val grandTotal: String,
    val amountPaid: String,
    val customerNote: String? = null,
    /** Photo of the shipping receipt (سند الإرسال), read with the customer token: store/me/files/{id}. */
    val shippingReceiptFileId: String? = null,
    val placedAt: String,
    val deliveredAt: String? = null,
    val cancelledAt: String? = null,
    val cancelReason: String? = null,
    val coupon: CouponRef? = null,
    val items: List<OrderItem>,
    val statusHistory: List<StatusChange> = emptyList(),
    val shipments: List<Shipment> = emptyList(),
    val currency: Currency = Currency.SAR,
    val exchangeRate: String = "1",
) {
    /** A SAR amount of this order in its own currency, at the rate locked at checkout. */
    fun format(amountSar: String?): String = Money.format(amountSar, currency, exchangeRate)

    val canCancel: Boolean get() = status == OrderStatus.PENDING || status == OrderStatus.CONFIRMED

    /** Card orders can be paid (or the payment retried) online until fully paid. */
    val canPayByCard: Boolean
        get() = paymentMethod == PaymentMethod.CARD &&
            (paymentStatus == PaymentStatus.UNPAID || paymentStatus == PaymentStatus.PARTIALLY_PAID) &&
            status != OrderStatus.CANCELLED && status != OrderStatus.RETURNED
    val canReturn: Boolean
        get() = (status == OrderStatus.DELIVERED || status == OrderStatus.COMPLETED) && items.any { it.returnableQty > 0 }
}

// --------------------------------------------------------------- returns ---

@Serializable
enum class ReturnStatus { REQUESTED, APPROVED, REJECTED, RECEIVED, REFUNDED }

@Serializable
data class ReturnItemRequest(val orderItemId: String, val quantity: Int)

@Serializable
data class CreateReturnRequest(val reason: String, val items: List<ReturnItemRequest>)

@Serializable
data class ReturnedItemRef(val productName: String, val sku: String, val size: String? = null, val color: String? = null)

@Serializable
data class ReturnLine(val quantity: Int, val refundAmount: String, val orderItem: ReturnedItemRef)

@Serializable
data class OrderNumberRef(val number: String, val currency: Currency = Currency.SAR, val exchangeRate: String = "1")

@Serializable
data class SalesReturn(
    val id: String,
    val number: String,
    val status: ReturnStatus,
    val reason: String,
    val rejectReason: String? = null,
    val refundAmount: String,
    val requestedAt: String,
    val resolvedAt: String? = null,
    val order: OrderNumberRef,
    val items: List<ReturnLine>,
)

// --------------------------------------------------------- card payments ---

/** A hosted card-payment page opened for an order. */
@Serializable
data class CardCheckout(val paymentId: String, val orderNumber: String, val amount: String, val checkoutUrl: String)

// --------------------------------------------------------- notifications ---

@Serializable
data class AppNotification(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val data: Map<String, String> = emptyMap(),
    val readAt: String? = null,
    val sentAt: String,
) {
    val isRead: Boolean get() = readAt != null

    /** Order the notification is about, if any. */
    val orderNumber: String? get() = data["order"]
}

@Serializable
data class NotificationPage(
    val items: List<AppNotification>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val unread: Int,
) {
    val hasMore: Boolean get() = page * pageSize < total
}

@Serializable
data class UnreadCount(val unread: Int)

@Serializable
data class DeviceRequest(val token: String, val appVersion: String? = null)

@Serializable
data class DeviceToken(val token: String)

@Serializable
data class DeviceRegistration(val registered: Boolean, val pushEnabled: Boolean)

// ------------------------------------------------------------ currencies ---

@Serializable
data class CurrencyRate(
    val code: Currency,
    /** Units per 1 SAR; null while no rate is set. */
    val rate: String? = null,
    val updatedAt: String? = null,
    val nameAr: String? = null,
    val nameEn: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
    val isBase: Boolean = false,
    val isActive: Boolean = true,
    val showInCustomerApp: Boolean = true,
    val sortOrder: Int = 0,
    /** Picture of a note or coin uploaded for the currency; null = the app's built-in picture. */
    val imageUrl: String? = null,
)

@Serializable
data class Currencies(val base: Currency, val currencies: List<CurrencyRate>) {
    fun rate(code: Currency): CurrencyRate? = currencies.firstOrNull { it.code == code }

    /** Teaches [Currency] how the server names and rounds each currency; returns them in order. */
    fun register(): List<Currency> = currencies.map { c ->
        if (c.symbol != null && c.nameAr != null && c.decimals != null) Currency.register(c.code.code, c.symbol, c.nameAr, c.decimals) else c.code
    }
}

// ---------------------------------------------------------------- wallet ---

@Serializable
data class WalletBalance(val currency: Currency, val balance: String, val owed: String = "0")

@Serializable
data class WalletEntry(val id: String, val currency: Currency, val amount: String, val note: String, val createdAt: String)

@Serializable
data class MerchantTerms(val discount: String = "0", val creditLimit: String? = null)

@Serializable
data class Wallet(
    val balances: List<WalletBalance> = emptyList(),
    val entries: List<WalletEntry> = emptyList(),
    /** Set for merchants: they may pay "on account" (credit) up to [MerchantTerms.creditLimit]. */
    val merchant: MerchantTerms? = null,
) {
    fun available(currency: Currency): String = balances.firstOrNull { it.currency == currency }?.balance ?: "0"
}

@Serializable
data class StoreBankAccount(
    val id: String,
    val bankName: String,
    val accountName: String,
    val accountNumber: String,
    val iban: String? = null,
    val currency: Currency,
    val instructions: String? = null,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    /** Every currency the account takes (the main one first); empty on old servers = only [currency]. */
    val currencies: List<String> = emptyList(),
    /** Picture / logo of the account. */
    val imageUrl: String? = null,
) {
    val accepted: List<String> get() = currencies.ifEmpty { listOf(currency.code) }
}

/** A size/colour the customer asked to be told about when it is back in stock. */
@Serializable
data class StockAlert(val variantId: String, val createdAt: String? = null, val size: String? = null, val color: String? = null, val productName: String? = null, val productSlug: String? = null)

@Serializable
enum class TopUpStatus { PENDING, APPROVED, REJECTED }

@Serializable
data class BankRef(val id: String, val bankName: String, val accountNumber: String, val currency: Currency)

@Serializable
data class TopUp(
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
)

@Serializable
data class TopUpRequest(
    val currency: Currency,
    val amount: String,
    val receiptFileId: String,
    val bankAccountId: String? = null,
    val reference: String? = null,
    val note: String? = null,
)

/** Changes to a request still under review; null leaves a field as it is. */
@Serializable
data class UpdateTopUpRequest(
    val currency: Currency? = null,
    val amount: String? = null,
    val receiptFileId: String? = null,
    val bankAccountId: String? = null,
    val reference: String? = null,
    val note: String? = null,
)

@Serializable
data class UploadedFile(val id: String, val url: String, val mimeType: String, val size: Int)
