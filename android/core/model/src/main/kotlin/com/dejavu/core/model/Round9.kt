package com.dejavu.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** How an order reaches the customer. */
object DeliveryMethods {
    const val HOME = "HOME"
    /** Picked up from the office of a shipping company. */
    const val OFFICE = "OFFICE"
    /** Picked up from the store, free. */
    const val PICKUP = "PICKUP"
}

/** GET store/carriers: local shipping companies and their offices. */
@Serializable
data class StoreCarrier(
    val id: String,
    val name: String,
    val phones: List<String> = emptyList(),
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
    )
}

// --------------------------------------------------------------- profile ---

/** GET store/me/profile. [igStatus]: NONE, PENDING (send [igCode] by Instagram DM) or VERIFIED. */
@Serializable
data class MyProfile(
    val id: String,
    val name: String,
    val phone: String = "",
    val username: String? = null,
    val pendingUsername: String? = null,
    val gender: String? = null,
    val findableByPhone: Boolean = true,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val birthDate: String? = null,
    val igStatus: String = "NONE",
    val igCode: String? = null,
    val instagramVerified: Boolean = false,
    /** The store's Instagram account to send the code to. */
    val storeInstagram: String? = null,
    val loyaltyPoints: Int = 0,
    val counts: Counts = Counts(),
) {
    @Serializable
    data class Counts(val followers: Int = 0, val following: Int = 0, val posts: Int = 0)
}

/** PATCH store/me/profile: only the fields sent change; "" removes username / avatar. */
@Serializable
data class UpdateProfileRequest(
    val name: String? = null,
    val username: String? = null,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val birthDate: String? = null,
    val gender: String? = null,
    val findableByPhone: Boolean? = null,
)

@Serializable
data class UsernameCheck(val username: String = "", val available: Boolean = false, val reason: String? = null)

// --------------------------------------------------------------- loyalty ---

/** GET store/me/loyalty. */
@Serializable
data class MyLoyalty(
    val points: Int = 0,
    /** SAR the points are worth now. */
    val value: String = "0",
    val earning: Boolean = false,
    val rules: Rules = Rules(),
    val entries: List<Entry> = emptyList(),
) {
    @Serializable
    data class Rules(val enabled: Boolean = false, val pointsPerSar: Double = 1.0, val sarPer100Points: Double = 5.0, val minRedeemPoints: Int = 100)

    /** [kind]: EARN, REVERSE, REDEEM, ADJUST, AMBASSADOR. */
    @Serializable
    data class Entry(val id: String, val points: Int, val kind: String, val note: String? = null, val createdAt: String)
}

@Serializable
data class RedeemPointsRequest(val points: Int)

// ----------------------------------------------------------------- gifts ---

/** A present waiting for (or sent to) me. [status]: WAITING, PICKUP, DELIVERY, DONE. */
@Serializable
data class MyGift(
    val id: String,
    val number: String,
    val giverName: String,
    val occasion: String? = null,
    val message: String? = null,
    val source: String = "POS",
    val status: String,
    val items: List<Item> = emptyList(),
    val deliveryMethod: String? = null,
    val delivery: JsonObject? = null,
    val trackingNumber: String? = null,
    val createdAt: String,
) {
    @Serializable
    data class Item(val variantId: String, val quantity: Int = 1, val name: String = "", val size: String? = null, val color: String? = null, val imageUrl: String? = null)
}

@Serializable
data class GiftChoiceRequest(val deliveryMethod: String, val addressId: String? = null, val carrierId: String? = null, val carrierOfficeId: String? = null)

/** A collection among friends for one piece for someone. [status]: OPEN, FUNDED, CANCELLED. */
@Serializable
data class GroupGift(
    val id: String,
    val number: String,
    val title: String,
    val occasion: String? = null,
    val message: String? = null,
    val status: String,
    val target: String,
    val raised: String,
    val remaining: String,
    val endsAt: String,
    val organizer: PublicPerson? = null,
    val recipient: PublicPerson? = null,
    val product: Product? = null,
    val contributions: List<Contribution> = emptyList(),
    val isOrganizer: Boolean = false,
) {
    @Serializable
    data class Product(val name: String, val slug: String? = null, val size: String? = null, val color: String? = null, val imageUrl: String? = null)

    @Serializable
    data class Contribution(val by: PublicPerson? = null, val amount: String, val message: String? = null, val at: String)
}

/** A customer as other customers see them. */
@Serializable
data class PublicPerson(
    val id: String,
    val username: String? = null,
    val name: String = "",
    val avatarUrl: String? = null,
    val instagramVerified: Boolean = false,
)

@Serializable
data class StartGroupGiftRequest(val to: String, val variantId: String, val title: String? = null, val occasion: String? = null, val message: String? = null, val days: Int? = null)

@Serializable
data class ContributeRequest(val amount: String, val message: String? = null)

@Serializable
data class Contributed(val funded: Boolean = false, val groupGift: GroupGift)

// ------------------------------------------------------- wallet details ---

/** GET store/me/wallet/entries/{id}. [kind]: ORDER, TOP_UP, RETURN, LAYAWAY or OTHER. [amount]: + money in, − out. */
@Serializable
data class WalletEntryDetail(
    val id: String,
    val kind: String,
    val sourceType: String = "",
    val title: String = "",
    val currency: String = "SAR",
    val amount: String = "0",
    val createdAt: String,
    val note: String? = null,
    val order: Order? = null,
    val paymentNumber: String? = null,
    val paymentMethod: String? = null,
    val topUp: TopUpInfo? = null,
    @kotlinx.serialization.SerialName("return") val returnInfo: ReturnInfo? = null,
    val layaway: LayawayInfo? = null,
) {
    @Serializable
    data class Line(val productName: String = "", val size: String? = null, val color: String? = null, val quantity: Int = 1, val unitPrice: String? = null, val lineTotal: String = "0")

    @Serializable
    data class Order(
        val id: String,
        val number: String,
        val status: String = "",
        val paymentMethod: String = "",
        val subtotal: String = "0",
        val discountTotal: String = "0",
        val shippingFee: String = "0",
        val grandTotal: String = "0",
        val currency: String = "SAR",
        val placedAt: String? = null,
        val items: List<Line> = emptyList(),
    )

    @Serializable
    data class TopUpInfo(val number: String, val status: String = "", val amount: String = "0", val currency: String = "SAR", val reference: String? = null, val bankName: String? = null, val createdAt: String? = null)

    @Serializable
    data class ReturnInfo(val number: String, val status: String = "", val refundAmount: String = "0", val orderNumber: String = "", val items: List<Line> = emptyList())

    @Serializable
    data class LayawayInfo(val number: String, val status: String = "", val total: String = "0")
}

// ---------------------------------------------------------------- social ---

/** A customer on the social side. [stylist]: the "مُنسِّق متميز" badge. */
@Serializable
data class Poster(
    val id: String,
    val username: String? = null,
    val name: String = "",
    val avatarUrl: String? = null,
    val bio: String? = null,
    val instagramVerified: Boolean = false,
    val stylist: Boolean = false,
    /** Birthday list only: I follow them. */
    val isFollowing: Boolean = false,
)

/** A product pinned on a photo: [x] / [y] are 0..1 from the left / top of image [imageIndex]. */
@Serializable
data class PostTag(
    val id: String = "",
    val productId: String,
    val variantId: String? = null,
    /** The size to gift/add: the tagged one, or the first in stock. */
    val giftVariantId: String? = null,
    val imageIndex: Int = 0,
    val x: Double? = null,
    val y: Double? = null,
    val name: String = "",
    val slug: String = "",
    val price: String = "0",
    val imageUrl: String? = null,
    val available: Boolean = true,
)

/** [kind]: LOOK (a styled outfit) or WISHLIST ("my birthday wishes"). */
@Serializable
data class Post(
    val id: String,
    val kind: String = "LOOK",
    val caption: String? = null,
    val images: List<String> = emptyList(),
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val createdAt: String,
    val liked: Boolean = false,
    val author: Poster,
    val mentions: List<String> = emptyList(),
    val tags: List<PostTag> = emptyList(),
)

@Serializable
data class PostPage(val items: List<Post> = emptyList(), val page: Int = 1, val pageSize: Int = 20, val hasMore: Boolean = false)

@Serializable
data class PostCounts(val likeCount: Int = 0, val commentCount: Int = 0, val liked: Boolean = false)

@Serializable
data class Comment(val id: String, val body: String, val createdAt: String, val author: Poster? = null)

@Serializable
data class CommentRequest(val body: String)

@Serializable
data class NewPostTag(val productId: String, val variantId: String? = null, val imageIndex: Int = 0, val x: Double? = null, val y: Double? = null)

@Serializable
data class NewPostRequest(val kind: String = "LOOK", val caption: String? = null, val images: List<String>, val tags: List<NewPostTag> = emptyList())

@Serializable
data class SocialProfile(
    val id: String,
    val username: String? = null,
    val name: String = "",
    val avatarUrl: String? = null,
    val bio: String? = null,
    val instagramVerified: Boolean = false,
    val stylist: Boolean = false,
    /** MM-DD, when the customer set one. */
    val birthday: String? = null,
    val counts: MyProfile.Counts = MyProfile.Counts(),
    val isFollowing: Boolean = false,
    val isMe: Boolean = false,
)

@Serializable
data class WishlistOf(val owner: Poster, val items: List<Item> = emptyList()) {
    @Serializable
    data class Item(
        val productId: String,
        val name: String = "",
        val slug: String = "",
        val price: String = "0",
        val imageUrl: String? = null,
        val giftVariantId: String? = null,
        /** A friend is already collecting for it ("قيد الإهداء"). */
        val pendingGift: Boolean = false,
    )
}

@Serializable
data class ShopResult(val added: Int = 0, val skipped: List<String> = emptyList())

/** Ambassador rewards from my posts: [paid] and [pending] in SAR. */
@Serializable
data class MyRewards(val paid: String = "0", val pending: String = "0")

/** The username a customer uses is their Instagram handle, so it follows Instagram's rule. */
object UsernameRule {
    private val regex = Regex("^(?!.*\\.\\.)(?!.*\\.$)[A-Za-z0-9._]{1,30}$")

    fun clean(raw: String): String = raw.trim().removePrefix("@")
    fun valid(raw: String): Boolean = regex.matches(clean(raw))
    const val HINT = "حروف إنجليزية وأرقام ونقطة أو _ (حتى 30)، بلا نقطتين متتاليتين ولا نقطة في آخره"
}

/** GET store/me/find: a friend found by username or full mobile number. [username] is null without a checked one. */
@Serializable
data class FoundPerson(
    val id: String,
    val username: String? = null,
    val name: String = "",
    val avatarUrl: String? = null,
    val instagramVerified: Boolean = false,
    val viaPhone: Boolean = false,
)
