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
    /** How the customer wants the code: SMS or WHATSAPP (they ask the store for it). */
    val otpChannel: String? = null,
    /** Optional: two English letters, then letters, digits, "." or "_". */
    val username: String? = null,
    /** The username is the customer's Instagram account: they get a code to send to the store. */
    val usernameIsInstagram: Boolean? = null,
    /** yyyy-mm-dd. */
    val birthDate: String? = null,
)

/** GET auth/governorates: Yemeni governorates and the rial each uses (Taiz is split in areas). */
@Serializable
data class Governorate(val name: String, val currency: String, val areas: List<Area> = emptyList()) {
    @Serializable data class Area(val name: String, val currency: String)
}

@Serializable
data class OtpRequired(val required: Boolean = false)

@Serializable
data class OtpRequest(val phone: String, val channel: String? = null)

/** Forgotten password: the texted code and the new password. */
@Serializable
data class PasswordResetRequest(val phone: String, val code: String, val newPassword: String)

/**
 * A code request: [channel] SMS = texted from the store phone (Yemeni numbers); WHATSAPP =
 * nothing was sent, ask the store on WhatsApp ([whatsapp] is its number).
 */
@Serializable
data class OtpSent(
    val sent: Boolean = true,
    val expiresInMinutes: Int = 10,
    val channel: String = "SMS",
    val whatsapp: String? = null,
) {
    val byWhatsapp: Boolean get() = channel == "WHATSAPP"
}

/** POST auth/customer/register: a session, or (when a code is needed) [pending] and how the code comes. */
@Serializable
data class SignUpResult(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: String? = null,
    val customer: Customer? = null,
    val pending: Boolean = false,
    val sent: Boolean = true,
    val channel: String = "SMS",
    val whatsapp: String? = null,
) {
    val byWhatsapp: Boolean get() = channel == "WHATSAPP"
}

/** POST auth/customer/verify: finishes a sign-up that waited for its code. */
@Serializable
data class VerifyRequest(val phone: String, val code: String)

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
    val username: String? = null,
    val avatarUrl: String? = null,
    /** NONE, PENDING (send [igCode] to the store's Instagram) or VERIFIED. */
    val igStatus: String = "NONE",
    val igCode: String? = null,
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
    /** Pieces on their way that can still be pre-ordered ("احجز من الشحنة القادمة"). */
    val incomingQty: Int = 0,
    /** Expected arrival of the shipment (ISO date), when known. */
    val expectedDate: String? = null,
)

/** A quantity offer on a product: "قطعة + قطعة مجاناً", "اشترِ 2 واحصل على قطعة مجاناً", a set. */
@Serializable
data class ProductDeal(
    val id: String,
    val name: String,
    /** BUY_X_GET_Y or BUNDLE. */
    val type: String,
    val label: String,
    val buyQty: Int = 1,
    val freeQty: Int = 1,
    val bundlePrice: String? = null,
    val productIds: List<String> = emptyList(),
)

/** An offer that applied in the cart. */
@Serializable
data class AppliedDeal(val dealId: String, val name: String, val times: Int = 1, val discount: String)

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
    /** Uploaded product video. */
    val videoUrl: String? = null,
    val instagramUrl: String? = null,
    val createdAt: String? = null,
    /** Added in the last two weeks ("وصل حديثاً"). */
    val isNew: Boolean = false,
    /** Pieces left in all sizes when only a few are (1-3), for "باقي قطعتان فقط". */
    val fewLeft: Int? = null,
    /** Quantity offers on this product. */
    val deals: List<ProductDeal> = emptyList(),
) {
    /** Photos to show for [color]: the ones tagged with it first, then the general ones. */
    fun imagesFor(color: String?): List<ProductImage> {
        if (color == null) return images
        val tagged = images.filter { it.color == color }
        return if (tagged.isEmpty()) images else tagged + images.filter { it.color == null }
    }
}

@Serializable
data class ShippingZone(
    val governorate: String,
    val fee: String = "0",
    val estimatedDays: Int = 3,
    val isActive: Boolean = true,
    /** Cash on delivery is offered for addresses here. */
    val codEnabled: Boolean = true,
)

// --------------------------------------------------------------- reviews ---

/** A customer's review: stars, comment and photos. */
@Serializable
data class ProductReview(
    val id: String,
    val rating: Int,
    val comment: String? = null,
    val images: List<String> = emptyList(),
    val createdAt: String,
    /** First name of the customer. */
    val customerName: String? = null,
    val isApproved: Boolean = true,
)

/** GET store/products/{slug}/reviews (approved only). */
@Serializable
data class ProductReviews(val average: String = "0", val count: Int = 0, val items: List<ProductReview> = emptyList(), val total: Int = 0, val page: Int = 1, val pageSize: Int = 20) {
    val hasMore: Boolean get() = page * pageSize < total
}

/** The customer's own review (waiting for approval or shown) and whether they may write one. */
@Serializable
data class MyReview(val review: ProductReview? = null, val canReview: Boolean = false)

@Serializable
data class WriteReviewRequest(val rating: Int, val comment: String? = null, val images: List<String> = emptyList())

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
    /** The spot on the map, when the shopper set it (sent as text by the server). */
    val latitude: String? = null,
    val longitude: String? = null,
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
    val latitude: Double? = null,
    val longitude: Double? = null,
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
    /** Quantity offers that applied (their discount is in totals.discountTotal). */
    val deals: List<AppliedDeal> = emptyList(),
    /** Whether any coupon can be used now: the code field shows only then. */
    val couponsAvailable: Boolean = true,
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
    /** Needed for HOME delivery only. */
    val addressId: String?,
    val paymentMethod: PaymentMethod,
    val customerNote: String? = null,
    /** Invoice currency; the current rate is locked into the order. */
    val currency: Currency? = null,
    /** [DeliveryMethods]: HOME (default), OFFICE or PICKUP. */
    val deliveryMethod: String? = null,
    val carrierId: String? = null,
    val carrierOfficeId: String? = null,
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
    /** The carrier's receipt photo and photos of the packed order, taken when it was shipped. */
    val receiptImageUrl: String? = null,
    val photoUrls: List<String> = emptyList(),
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
    /** A merchant's order on credit waiting for the store's approval. */
    val needsApproval: Boolean = false,
    val approvedAt: String? = null,
    /** [DeliveryMethods]: HOME, OFFICE or PICKUP. */
    val deliveryMethod: String = DeliveryMethods.HOME,
    val carrier: StoreCarrier? = null,
    val carrierOffice: StoreCarrier.Office? = null,
) {
    /** A SAR amount of this order in its own currency, at the rate locked at checkout. */
    fun format(amountSar: String?): String = Money.format(amountSar, currency, exchangeRate)

    val awaitingApproval: Boolean get() = needsApproval && approvedAt == null && status == OrderStatus.PENDING

    val canCancel: Boolean get() = status == OrderStatus.PENDING || status == OrderStatus.CONFIRMED

    /** Shipped and not yet confirmed by the customer: shows "استلمت الطلب". */
    val canMarkReceived: Boolean get() = status == OrderStatus.SHIPPED

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

/** POST store/me/wallet/gift: balance given to another customer by their mobile number. */
@Serializable
data class GiftRequest(val phone: String, val currency: String, val amount: String, val password: String, val note: String? = null)

/** The receiver's name as the sender may see it ("منى س."). */
@Serializable
data class GiftRecipient(val name: String)

@Serializable
data class GiftSent(val sent: Boolean = true, val to: String = "", val amount: String = "")

/** POST auth/change-password */
@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

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
    /** Credited at once (staff check the bank afterwards). */
    val autoApproved: Boolean = false,
)

/** What could be read off a slip photo before sending it. */
@Serializable
data class SlipScan(
    val reference: String? = null,
    val amount: String? = null,
    val currency: String? = null,
    val bankName: String? = null,
    /** Set when this transfer (or photo) was already used. */
    val duplicate: String? = null,
)

@Serializable
data class ScanSlipRequest(val receiptFileId: String)

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


// ------------------------------------------------------------ round 5 ---

/** GET store/config: what the app needs at start. */
@Serializable
data class StoreConfig(
    val requireLogin: Boolean = false,
    val chat: ChatConfig = ChatConfig(),
)

@Serializable
data class ChatConfig(val enabled: Boolean = false, val welcome: String = "")

/** GET store/about: the "عن المتجر" page. */
@Serializable
data class StoreAbout(
    val name: String = "Dejavu",
    val about: String = "",
    val address: String = "",
    val mapUrl: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val hours: String = "",
    val returnPolicy: String = "",
    val phones: List<String> = emptyList(),
    val whatsapp: String = "",
    val instagram: String = "",
    val facebook: String = "",
    val tiktok: String = "",
)

/** GET store/products/{slug}/related */
@Serializable
data class RelatedProducts(val similar: List<StoreProduct> = emptyList(), val alsoBought: List<StoreProduct> = emptyList())

@Serializable
enum class ChatRole { USER, BOT }

@Serializable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val imageFileId: String? = null,
    val handover: Boolean = false,
    val createdAt: String,
    val products: List<StoreProduct> = emptyList(),
    /** On an answer: the staff WhatsApp number when the assistant handed over. */
    val whatsapp: String? = null,
    /** The customer already reported this answer. */
    val reported: Boolean = false,
)

/** POST store/chat/{id}/report */
@Serializable
data class ChatReportRequest(val reason: String? = null)

@Serializable
data class ChatReportResult(val reported: Boolean = true)

/** GET store/servers, and the same list in the GitHub "directory" file. */
@Serializable
data class ServerList(val servers: List<String> = emptyList(), val updatedAt: String? = null)

/** POST store/me/delete-account: the password, asked again. */
@Serializable
data class DeleteAccountRequest(val password: String)

/** GET store/chat */
@Serializable
data class ChatHistory(
    val enabled: Boolean = false,
    val welcome: String = "",
    val whatsapp: String = "",
    val messages: List<ChatMessage> = emptyList(),
)

@Serializable
data class ChatSendRequest(val text: String? = null, val imageFileId: String? = null)

/** POST store/analytics: time in the app and product views, in batches. */
@Serializable
data class AnalyticsBatch(
    val deviceId: String,
    val sessions: List<AnalyticsSession> = emptyList(),
    val views: List<AnalyticsView> = emptyList(),
)

@Serializable
data class AnalyticsSession(val id: String, val startedAt: String, val seconds: Int)

@Serializable
data class AnalyticsView(val productId: String, val seconds: Int, val at: String)

@Serializable
data class GiftCardRedeemRequest(val code: String)

@Serializable
data class GiftCardRedeemed(val code: String, val currency: String, val amount: String)


/** GET store/pre-orders: pieces claimed from a shipment on its way. */
@Serializable
data class PreOrder(
    val id: String,
    val number: String,
    /** WAITING, NOTIFIED (arrived), CONVERTED (put aside / sold) or CANCELLED. */
    val status: String,
    val quantity: Int,
    val note: String? = null,
    val createdAt: String,
    val notifiedAt: String? = null,
    val variantId: String? = null,
    val sku: String? = null,
    val size: String? = null,
    val color: String? = null,
    val productId: String? = null,
    val name: String,
    val slug: String? = null,
    val imageUrl: String? = null,
    val inStock: Int = 0,
    val expectedDate: String? = null,
    /** Arrived: the customer confirms to have it put aside and prepared. */
    val awaitingConfirmation: Boolean = false,
    val layawayId: String? = null,
)

@Serializable
data class PreOrderConfirmed(val preOrder: PreOrder)

@Serializable
data class PreOrderRequest(val variantId: String, val quantity: Int, val note: String? = null)
