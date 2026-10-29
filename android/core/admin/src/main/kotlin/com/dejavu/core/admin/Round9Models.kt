@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dejavu.core.admin

import com.dejavu.core.network.apiCall
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ------------------------------------------------------------- carriers ---

/** A local shipping company (الجوال القدسي…) and its offices. */
@Serializable
data class Carrier(
    val id: String,
    val name: String,
    val phones: List<String> = emptyList(),
    val notes: String? = null,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    val offices: List<Office> = emptyList(),
) {
    @Serializable
    data class Office(
        val id: String,
        val name: String,
        val governorate: String,
        val city: String? = null,
        val address: String,
        val phones: List<String> = emptyList(),
        val isActive: Boolean = true,
    )
}

@Serializable
data class CarrierRequest(val name: String? = null, val phones: List<String>? = null, val notes: String? = null, val isActive: Boolean? = null)

@Serializable
data class CarrierOfficeRequest(val name: String, val governorate: String, val city: String? = null, val address: String, val phones: List<String> = emptyList(), val isActive: Boolean? = null)

@Serializable
data class CarrierRemoved(val deleted: Boolean = false, val hidden: Boolean = false)

// -------------------------------------------------------------- loyalty ---

/** [scope]: ALL, RETAIL, MERCHANTS or SELECTED (only customers switched on one by one). */
@Serializable
data class LoyaltySettings(
    @EncodeDefault val enabled: Boolean = false,
    @EncodeDefault val scope: String = "ALL",
    @EncodeDefault val pointsPerSar: Double = 1.0,
    @EncodeDefault val sarPer100Points: Double = 5.0,
    /** Purchases count from this day (ISO). */
    val startsAt: String? = null,
    @EncodeDefault val minRedeemPoints: Int = 100,
)

@Serializable
data class CustomerLoyalty(
    val points: Int = 0,
    /** SAR the points are worth now. */
    val value: String = "0",
    val earning: Boolean = false,
    /** true / false for this customer, null = follows the store setting. */
    val customerSetting: Boolean? = null,
    val entries: List<Entry> = emptyList(),
) {
    /** [kind]: EARN, REVERSE, REDEEM, ADJUST, AMBASSADOR. */
    @Serializable
    data class Entry(val id: String, val points: Int, val kind: String, val note: String? = null, val createdAt: String)
}

/** Left-out [enabled] follows the store setting. */
@Serializable
data class CustomerLoyaltyRequest(val enabled: Boolean? = null)

@Serializable
data class AdjustPointsRequest(val points: Int, val note: String? = null)

// ------------------------------------------------------------ statement ---

/** GET admin/customers/{id}/account/statement: one page per currency the customer dealt in. */
@Serializable
data class FullStatement(
    val customer: Who,
    val from: String? = null,
    val to: String? = null,
    val currencies: List<Section> = emptyList(),
) {
    @Serializable
    data class Who(val id: String, val code: String = "", val name: String, val phone: String = "", val username: String? = null)

    @Serializable
    data class Section(
        val currency: String,
        val opening: String = "0",
        /** Totals of the period: owed by the customer / paid or credited to them. */
        val owes: String = "0",
        val credit: String = "0",
        val closing: String = "0",
        val rows: List<Row> = emptyList(),
    )

    /** [balance]: + the customer owes, − the store holds credit for them. */
    @Serializable
    data class Row(
        val id: String,
        val date: String,
        val description: String,
        val note: String? = null,
        /** The invoice (or top-up / reservation / return) number. */
        val reference: String? = null,
        val orderId: String? = null,
        val pieces: Int? = null,
        val owes: String = "0",
        val credit: String = "0",
        val balance: String = "0",
    )
}

// ------------------------------------------------------ customer tools ---

@Serializable
data class BlockRequest(val reason: String? = null)

@Serializable
data class BlockPhoneRequest(val phone: String, val reason: String? = null)

@Serializable
data class BlockedPhone(val id: String, val phone: String, val reason: String? = null, val createdAt: String)

@Serializable
data class Blocked(val blocked: Boolean)

/** A customer's open cart (what they are thinking of buying). */
@Serializable
data class CustomerCart(
    val id: String,
    val updatedAt: String,
    val couponCode: String? = null,
    val customer: Person,
    val pieces: Int = 0,
    val items: List<Item> = emptyList(),
) {
    @Serializable
    data class Item(
        val variantId: String,
        val sku: String = "",
        val name: String,
        val size: String? = null,
        val color: String? = null,
        val quantity: Int,
        val price: String = "0",
        val imageUrl: String? = null,
        val addedAt: String,
    )
}

/** A customer as the staff tools list them. */
@Serializable
data class Person(
    val id: String,
    val code: String = "",
    val name: String,
    val phone: String = "",
    val username: String? = null,
    val avatarUrl: String? = null,
    val governorate: String? = null,
)

@Serializable
data class InstagramCodeRequest(val code: String)

@Serializable
data class InstagramResult(val verified: Boolean)

// ---------------------------------------------------------------- gifts ---

@Serializable
data class PosGiftRequest(val saleOrderId: String, val to: String, val giverName: String, val occasion: String? = null, val message: String? = null)

/** [status]: WAITING (receiver not chosen yet), PICKUP, DELIVERY, DONE, CANCELLED. [source]: POS or GROUP. */
@Serializable
data class AdminGift(
    val id: String,
    val number: String,
    val giverName: String,
    val occasion: String? = null,
    val message: String? = null,
    val source: String = "POS",
    val status: String,
    val items: List<GiftItem> = emptyList(),
    val saleOrderId: String? = null,
    val deliveryMethod: String? = null,
    /** method, recipient, phone, governorate, city, street, carrier, officeName… */
    val delivery: JsonObject? = null,
    val trackingNumber: String? = null,
    val chosenAt: String? = null,
    val doneAt: String? = null,
    val createdAt: String,
    val recipient: Person? = null,
)

@Serializable
data class GiftItem(val variantId: String, val quantity: Int = 1, val name: String = "", val size: String? = null, val color: String? = null, val imageUrl: String? = null)

@Serializable
data class GiftDoneRequest(val trackingNumber: String? = null)

// ---------------------------------------------------------------- today ---

/** GET admin/today: everything waiting on the staff, by what this user may handle. */
@Serializable
data class TodayTasks(
    val counts: Map<String, Int> = emptyMap(),
    val total: Int = 0,
    val topUps: List<TopUpTask> = emptyList(),
    val approvals: List<OrderTask> = emptyList(),
    val toShip: List<OrderTask> = emptyList(),
    val layaways: List<NumberedTask> = emptyList(),
    val gifts: List<GiftTask> = emptyList(),
    val preOrders: List<NumberedTask> = emptyList(),
    val returns: List<NumberedTask> = emptyList(),
    val instagram: List<Person> = emptyList(),
    val lowStock: List<LowStockTask> = emptyList(),
) {
    @Serializable
    data class TopUpTask(val id: String, val number: String, val amount: String, val currency: String, val status: String, val autoApproved: Boolean = false, val createdAt: String, val customer: Person)

    @Serializable
    data class OrderTask(
        val id: String,
        val number: String,
        val status: String? = null,
        val deliveryMethod: String? = null,
        val grandTotal: String = "0",
        val currency: String = "SAR",
        val placedAt: String,
        val customer: Person,
        val carrier: Named? = null,
    )

    @Serializable
    data class NumberedTask(
        val id: String,
        val number: String,
        val status: String? = null,
        val quantity: Int? = null,
        val itemName: String? = null,
        val createdAt: String? = null,
        val requestedAt: String? = null,
        val notifiedAt: String? = null,
        val customer: Person,
    )

    @Serializable
    data class GiftTask(val id: String, val number: String, val status: String, val giverName: String, val chosenAt: String? = null, val recipient: Person)

    @Serializable
    data class LowStockTask(val variantId: String, val sku: String, val name: String, val available: Int, val threshold: Int)

    @Serializable
    data class Named(val name: String)
}

// ------------------------------------------------------------- dormant ---

@Serializable
data class DormantCustomer(
    val id: String,
    val code: String = "",
    val name: String,
    val phone: String = "",
    val username: String? = null,
    val governorate: String? = null,
    val lastOrderAt: String,
    val daysAway: Int,
    val orders: Int,
    /** A personal coupon they have not used yet. */
    val openCoupon: String? = null,
)

/** [type]: PERCENTAGE, FIXED_AMOUNT or FREE_SHIPPING. Empty [customerIds] = every dormant customer of [days]. */
@Serializable
data class DormantCouponRequest(
    val customerIds: List<String> = emptyList(),
    val days: Int? = null,
    val type: String,
    val value: String,
    val minOrderAmount: String? = null,
    val validDays: Int? = null,
    val message: String? = null,
)

@Serializable
data class DormantCouponsSent(val sent: Int, val coupons: List<Sent> = emptyList()) {
    @Serializable
    data class Sent(val customerId: String, val code: String)
}

// --------------------------------------------------------------- social ---

@Serializable
data class AdminPost(
    val id: String,
    val kind: String = "LOOK",
    val caption: String? = null,
    val images: List<String> = emptyList(),
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val createdAt: String,
    val isHidden: Boolean = false,
    val author: Author,
    val tags: List<Tag> = emptyList(),
) {
    @Serializable
    data class Author(val id: String, val username: String? = null, val name: String = "", val avatarUrl: String? = null, val instagramVerified: Boolean = false, val stylist: Boolean = false)

    @Serializable
    data class Tag(val productId: String, val name: String = "", val price: String = "0", val imageUrl: String? = null)
}

@Serializable
data class HiddenRequest(val hidden: Boolean)

/** Reward to a customer whose post sold something: [percent] of the sale, as POINTS or WALLET credit. */
@Serializable
data class AmbassadorSettings(@EncodeDefault val enabled: Boolean = true, @EncodeDefault val percent: Double = 3.0, @EncodeDefault val kind: String = "POINTS")

// ------------------------------------------------------------------- AI ---

@Serializable
data class ProductPrompt(val prompt: String, val isDefault: Boolean = true, val defaultPrompt: String = "")

@Serializable
data class ProductPromptRequest(val prompt: String)

// ---------------------------------------------------------- repository ---

/** Round 9 staff tools: carriers, loyalty, statements, customer tools, gifts, today, dormant customers, social. */
class Round9Repository(private val api: AdminApi) {
    suspend fun carriers() = apiCall { api.carriers() }
    suspend fun createCarrier(name: String, phones: List<String>) = apiCall { api.createCarrier(CarrierRequest(name = name.trim(), phones = phones)) }
    suspend fun updateCarrier(id: String, request: CarrierRequest) = apiCall { api.updateCarrier(id, request) }
    suspend fun removeCarrier(id: String) = apiCall { api.removeCarrier(id) }
    suspend fun addOffice(carrierId: String, request: CarrierOfficeRequest) = apiCall { api.addOffice(carrierId, request) }
    suspend fun updateOffice(id: String, request: CarrierOfficeRequest) = apiCall { api.updateOffice(id, request) }
    suspend fun removeOffice(id: String) = apiCall { api.removeOffice(id) }

    suspend fun loyaltySettings() = apiCall { api.loyaltySettings() }
    suspend fun setLoyaltySettings(s: LoyaltySettings) = apiCall { api.setLoyaltySettings(s) }
    suspend fun customerLoyalty(id: String) = apiCall { api.customerLoyalty(id) }
    suspend fun setCustomerLoyalty(id: String, enabled: Boolean?) = apiCall { api.setCustomerLoyalty(id, CustomerLoyaltyRequest(enabled)) }
    suspend fun adjustPoints(id: String, points: Int, note: String?) = apiCall { api.adjustPoints(id, AdjustPointsRequest(points, note?.takeIf { it.isNotBlank() })) }

    suspend fun statement(customerId: String, from: String? = null, to: String? = null) = apiCall { api.fullStatement(customerId, from, to) }

    suspend fun block(customerId: String, reason: String?) = apiCall { api.blockCustomer(customerId, BlockRequest(reason?.takeIf { it.isNotBlank() })) }
    suspend fun unblock(customerId: String) = apiCall { api.unblockCustomer(customerId) }
    suspend fun blockedPhones() = apiCall { api.blockedPhones() }
    suspend fun blockPhone(phone: String, reason: String?) = apiCall { api.blockPhone(BlockPhoneRequest(phone.trim(), reason?.takeIf { it.isNotBlank() })) }
    suspend fun unblockPhone(id: String) = apiCall { api.unblockPhone(id) }
    suspend fun carts() = apiCall { api.customerCarts() }
    suspend fun instagramPending() = apiCall { api.instagramPending() }
    suspend fun verifyInstagram(customerId: String, code: String) = apiCall { api.verifyInstagram(customerId, InstagramCodeRequest(code.trim())) }
    suspend fun rejectInstagram(customerId: String) = apiCall { api.rejectInstagram(customerId) }

    suspend fun markGift(request: PosGiftRequest) = apiCall { api.markGift(request) }
    suspend fun gifts(status: String? = null) = apiCall { api.gifts(status) }
    suspend fun giftDone(id: String, tracking: String?) = apiCall { api.giftDone(id, GiftDoneRequest(tracking?.takeIf { it.isNotBlank() })) }

    suspend fun today() = apiCall { api.today() }

    suspend fun dormant(days: Int) = apiCall { api.dormantCustomers(days) }
    suspend fun dormantCoupon(request: DormantCouponRequest) = apiCall { api.dormantCoupon(request) }

    suspend fun posts(page: Int = 1) = apiCall { api.adminPosts(page) }
    suspend fun hidePost(id: String, hidden: Boolean) = apiCall { api.hidePost(id, HiddenRequest(hidden)) }
    suspend fun ambassadorSettings() = apiCall { api.ambassadorSettings() }
    suspend fun setAmbassadorSettings(s: AmbassadorSettings) = apiCall { api.setAmbassadorSettings(s) }

    suspend fun productPrompt() = apiCall { api.productPrompt() }
    suspend fun setProductPrompt(prompt: String) = apiCall { api.setProductPrompt(ProductPromptRequest(prompt)) }
}
