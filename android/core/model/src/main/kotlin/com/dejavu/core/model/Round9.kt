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
    val usernameIsInstagram: Boolean? = null,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val birthDate: String? = null,
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
