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
data class RegisterRequest(val name: String, val phone: String, val password: String, val email: String? = null)

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

@Serializable
data class ProductImage(val url: String, val altText: String? = null, val variantId: String? = null)

@Serializable
data class StoreVariant(
    val id: String,
    val sku: String,
    val size: String? = null,
    val color: String? = null,
    val colorHex: String? = null,
    val price: String,
    val availableQty: Int,
    val inStock: Boolean,
)

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
    val images: List<ProductImage> = emptyList(),
    val variants: List<StoreVariant> = emptyList(),
    val inStock: Boolean,
)

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
enum class PaymentMethod { CASH_ON_DELIVERY, CARD, WALLET, BANK_TRANSFER, INSTAPAY, CASH }

@Serializable
data class CheckoutRequest(val addressId: String, val paymentMethod: PaymentMethod, val customerNote: String? = null)

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
)

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
    val placedAt: String,
    val deliveredAt: String? = null,
    val cancelledAt: String? = null,
    val cancelReason: String? = null,
    val coupon: CouponRef? = null,
    val items: List<OrderItem>,
    val statusHistory: List<StatusChange> = emptyList(),
    val shipments: List<Shipment> = emptyList(),
) {
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
data class OrderNumberRef(val number: String)

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
