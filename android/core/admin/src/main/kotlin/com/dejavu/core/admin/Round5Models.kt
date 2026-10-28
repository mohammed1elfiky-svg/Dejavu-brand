package com.dejavu.core.admin

import com.dejavu.core.model.Currency
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// --------------------------------------------------------- owner alerts ---

/** GET admin/owner-alerts */
@Serializable
data class OwnerAlert(val id: String, val kind: String, val title: String, val body: String, val data: JsonObject? = null, val createdAt: String)

/** GET/PUT admin/owner-alerts/settings. [shortageAbove] null = off. Send "" for [smsPhone] to clear it. */
@Serializable
data class OwnerAlertSettings(
    val voids: Boolean = true,
    val shortageAbove: Double? = 0.0,
    val priceChanges: Boolean = true,
    val smsPhone: String? = null,
)

// --------------------------------------------------------------- prices ---

/** POST admin/pricing/bulk. [mode]: PERCENT (+10 / -15), AMOUNT (SAR ±) or SET (new price). */
@Serializable
data class BulkPriceRequest(
    val productIds: List<String>? = null,
    val categoryIds: List<String>? = null,
    val brandIds: List<String>? = null,
    val mode: String,
    val value: String,
    val roundTo: Int? = null,
    val keepOldAsCompareAt: Boolean? = null,
    val includeVariants: Boolean? = null,
    val preview: Boolean = false,
)

@Serializable
data class BulkPriceResult(val preview: Boolean, val count: Int, val items: List<Item> = emptyList()) {
    @Serializable
    data class Item(val productId: String, val name: String, val skuNumber: Int? = null, val oldPrice: String, val newPrice: String, val variants: List<Variant> = emptyList())

    @Serializable
    data class Variant(
        val variantId: String,
        val sku: String,
        val barcode: String? = null,
        val size: String? = null,
        val color: String? = null,
        val oldPrice: String,
        val newPrice: String,
    )
}

/** GET admin/pricing/on-offer */
@Serializable
data class OfferProduct(
    val productId: String,
    val name: String,
    val slug: String,
    val status: String,
    val skuNumber: Int? = null,
    val imageUrl: String? = null,
    val category: Ref,
    val price: String,
    val was: String,
    val percentOff: String,
    val promotion: Promo? = null,
) {
    @Serializable
    data class Ref(val id: String, val nameAr: String)

    @Serializable
    data class Promo(val id: String, val name: String, val percent: String, val endsAt: String? = null)
}

// ------------------------------------------------------------- layaways ---

@Serializable
data class LayawayDeposit(val currency: Currency, val amount: String, val cashBoxId: String? = null)

@Serializable
data class CreateLayawayRequest(
    val customerId: String,
    val items: List<PosItem>,
    val deposit: LayawayDeposit? = null,
    val expiresAt: String? = null,
    val note: String? = null,
)

@Serializable
data class CompleteLayawayRequest(
    val payments: List<PosTender>? = null,
    val paymentMethod: String? = null,
    val changeCurrency: Currency? = null,
    val note: String? = null,
)

@Serializable
data class CancelRequest(val reason: String? = null)

@Serializable
data class Layaway(
    val id: String,
    val number: String,
    /** OPEN, COMPLETED or CANCELLED. */
    val status: String,
    val customer: Customer,
    /** SAR. */
    val total: String,
    val paid: Paid,
    /** SAR still to pay. */
    val remaining: String,
    /** What is left, in the deposit currency at today's rate. */
    val remainingInCurrency: String? = null,
    val expiresAt: String? = null,
    val expired: Boolean = false,
    val note: String? = null,
    val saleOrderId: String? = null,
    val createdAt: String,
    val closedAt: String? = null,
    /** When an employee put the pieces aside (null: still to do). */
    val heldAt: String? = null,
    val heldBy: String? = null,
    val payments: List<Payment> = emptyList(),
    val items: List<Item> = emptyList(),
) {
    @Serializable
    data class Customer(val id: String, val name: String, val phone: String, val code: String? = null)

    @Serializable
    data class Paid(val currency: Currency? = null, val amount: String = "0", val sar: String = "0")

    @Serializable
    data class Payment(val id: String, val currency: Currency, val amount: String, val createdAt: String)

    @Serializable
    data class Item(
        val variantId: String,
        val quantity: Int,
        val unitPrice: String,
        val sku: String = "",
        val size: String? = null,
        val color: String? = null,
        val productId: String? = null,
        val name: String = "",
        val imageUrl: String? = null,
    )
}

/** POST admin/layaways/{id}/complete */
@Serializable
data class LayawayCompleted(val layaway: Layaway, val sale: JsonElement)

// ----------------------------------------------------------- gift cards ---

@Serializable
data class IssueGiftCardRequest(
    val currency: Currency,
    val amount: String,
    val cashBoxId: String? = null,
    val buyerName: String? = null,
    val note: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class RedeemGiftCardRequest(val code: String, val customerId: String? = null)

@Serializable
data class GiftCard(
    val id: String,
    val code: String,
    val currency: Currency,
    val amount: String,
    /** ACTIVE, REDEEMED or CANCELLED. */
    val status: String,
    val buyerName: String? = null,
    val note: String? = null,
    val expiresAt: String? = null,
    val createdAt: String,
    val redeemedAt: String? = null,
    val redeemedBy: Ref? = null,
    val usable: Boolean = false,
) {
    @Serializable
    data class Ref(val id: String, val name: String)
}

/** GET admin/gift-cards */
@Serializable
data class GiftCardPage(
    val items: List<GiftCard>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    /** Value of unused cards, per currency. */
    val outstanding: List<Outstanding> = emptyList(),
) {
    @Serializable
    data class Outstanding(val currency: Currency, val amount: String? = null, val cards: Int)
}

/** GET admin/gift-cards/check/{code} */
@Serializable
data class GiftCardCheck(val code: String, val currency: Currency, val amount: String, val status: String, val expiresAt: String? = null, val usable: Boolean, val reason: String? = null)

// -------------------------------------------------------------- damages ---

@Serializable
data class DamageItem(val variantId: String, val quantity: Int)

@Serializable
data class StockDamageRequest(val warehouseId: String? = null, val items: List<DamageItem>, val note: String? = null, val photoUrls: List<String> = emptyList())

@Serializable
data class StockDamage(
    val id: String,
    val number: String,
    val items: List<Item> = emptyList(),
    val totalValue: String,
    val note: String? = null,
    val photoUrls: List<String> = emptyList(),
    val createdBy: String? = null,
    val createdAt: String,
) {
    @Serializable
    data class Item(val variantId: String, val quantity: Int, val sku: String = "", val name: String = "", val size: String? = null, val color: String? = null)
}

@Serializable
data class StockDamagePage(val items: List<StockDamage>, val total: Int, val page: Int, val pageSize: Int, val totalValue: String = "0")

// ------------------------------------------------------------ campaigns ---

/** Which customers get a campaign; every filter that is set must match. */
@Serializable
data class Segment(
    val merchantsOnly: Boolean? = null,
    val governorates: List<String>? = null,
    val boughtCategoryIds: List<String>? = null,
    val boughtWithinDays: Int? = null,
    val notBoughtForDays: Int? = null,
    val minSpent: Double? = null,
    val joinedWithinDays: Int? = null,
    val wishlistProductIds: List<String>? = null,
    val hasCart: Boolean? = null,
    val customerIds: List<String>? = null,
)

/** Where tapping the notification leads: none, product (slug), category (slug) or url. */
@Serializable
data class CampaignTarget(val type: String = "none", val value: String? = null)

@Serializable
data class CampaignPreviewRequest(val segment: Segment)

@Serializable
data class CampaignPreview(val count: Int, val withApp: Int = 0, val sample: List<Person> = emptyList(), val smsMax: Int = 500) {
    @Serializable
    data class Person(val id: String, val name: String, val phone: String)
}

/** POST admin/campaigns. [channels]: "PUSH" and/or "SMS". */
@Serializable
data class CampaignRequest(
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val target: CampaignTarget = CampaignTarget(),
    val segment: Segment,
    val channels: List<String>,
)

@Serializable
data class Campaign(
    val id: String,
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val target: CampaignTarget = CampaignTarget(),
    val channels: List<String> = emptyList(),
    val recipients: Int,
    val smsCount: Int = 0,
    val createdAt: String,
)

// ---------------------------------------------------------------- excel ---

/** POST admin/import/products */
@Serializable
data class ProductImportResult(
    val rows: Int,
    val newProducts: Int = 0,
    val existingProducts: Int = 0,
    val errors: List<RowError> = emptyList(),
    val saved: Boolean = false,
    val created: Int = 0,
    val variantsAdded: Int = 0,
) {
    @Serializable
    data class RowError(val row: Int, val message: String)
}

// --------------------------------------------------------- customer app ---

/** GET/PUT admin/store-app */
@Serializable
data class StoreAppSettings(val requireLogin: Boolean = false, val ads: Ads = Ads()) {
    @Serializable
    data class Ads(
        val enabled: Boolean = false,
        val bannerUnitId: String = "",
        val interstitialUnitId: String = "",
        val homeBanner: Boolean = true,
        val productBanner: Boolean = true,
        val interstitialEvery: Int = 0,
    )
}

/** GET/PUT admin/store-about */
@Serializable
data class StoreAboutSettings(
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

/** GET/PUT admin/chat/settings */
@Serializable
data class ChatSettings(
    val enabled: Boolean = false,
    val instructions: String = "",
    val welcome: String = "",
    val whatsapp: String = "",
    val dailyLimit: Int = 30,
    val imageLimit: Int = 10,
    val model: String = "gemini-flash-latest",
    /** Whether GEMINI_API_KEY is set on the server (read only). */
    val configured: Boolean = false,
)

/** PUT body for the assistant settings (without the read-only flag). */
@Serializable
data class ChatSettingsRequest(
    val enabled: Boolean? = null,
    val instructions: String? = null,
    val welcome: String? = null,
    val whatsapp: String? = null,
    val dailyLimit: Int? = null,
    val imageLimit: Int? = null,
    val model: String? = null,
)

@Serializable
data class ChatConversation(
    val customer: Person,
    val messages: Int,
    val images: Int = 0,
    val handovers: Int = 0,
    /** Answers the customer reported as wrong or offensive. */
    val reports: Int = 0,
    val lastAt: String,
    val lastText: String = "",
) {
    @Serializable
    data class Person(val id: String, val name: String, val phone: String)
}

@Serializable
data class ChatLine(
    val id: String,
    /** USER or BOT. */
    val role: String,
    val text: String,
    val imageFileId: String? = null,
    val productIds: List<String> = emptyList(),
    val handover: Boolean = false,
    /** When the customer reported this answer (null = not reported). */
    val reportedAt: String? = null,
    val reportReason: String? = null,
    val createdAt: String,
)

// ------------------------------------------------------------ behaviour ---

/** GET admin/reports/behavior */
@Serializable
data class BehaviorReport(
    val from: String,
    val to: String,
    val totals: Totals,
    val perDay: List<Day> = emptyList(),
    val hours: List<Hour> = emptyList(),
    val topCustomers: List<CustomerTime> = emptyList(),
    val topViewed: List<Viewed> = emptyList(),
    val viewedNotBought: List<Viewed> = emptyList(),
    val viewedOutOfStock: List<Viewed> = emptyList(),
    val topSearches: List<Search> = emptyList(),
    val noResultSearches: List<Search> = emptyList(),
) {
    @Serializable
    data class Totals(val seconds: Long, val sessions: Int, val devices: Int, val customers: Int, val avgSessionSeconds: Int)

    @Serializable
    data class Day(val date: String, val seconds: Long, val users: Int, val sessions: Int)

    @Serializable
    data class Hour(val hour: Int, val sessions: Int)

    @Serializable
    data class CustomerTime(val customer: ChatConversation.Person, val seconds: Long, val sessions: Int, val lastSeen: String? = null)

    @Serializable
    data class Viewed(
        val productId: String,
        val name: String,
        val slug: String,
        val imageUrl: String? = null,
        val views: Int,
        val viewers: Int,
        val avgSeconds: Int,
        val sold: Int,
        /** Buyers per 100 viewers. */
        val conversion: Double,
        val available: Int,
    )

    @Serializable
    data class Search(val query: String, val count: Int, val results: Int = 0)
}

/** GET admin/customers/{id}/usage */
@Serializable
data class CustomerUsage(
    val seconds: Long,
    val sessions: Int,
    val last30Days: Period,
    val lastSeen: String? = null,
    val mostViewed: List<Viewed> = emptyList(),
) {
    @Serializable
    data class Period(val seconds: Long, val sessions: Int)

    @Serializable
    data class Viewed(val productId: String, val name: String, val views: Int, val seconds: Long)
}

/** "1 س 20 د" / "45 د" / "30 ث" for a number of seconds. */
fun durationLabel(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} س ${(seconds % 3600) / 60} د"
    seconds >= 60 -> "${seconds / 60} د"
    else -> "$seconds ث"
}

/** POST admin/chat/test: whether the Gemini key and model work, and why not. */
@Serializable
data class ChatTestResult(
    val ok: Boolean,
    val model: String = "",
    val answer: String? = null,
    val error: String? = null,
    val status: Int? = null,
    val available: List<String> = emptyList(),
)

// ------------------------------------------------------------ app servers ---

/** GET admin/app-servers: the customer app's server addresses and their copy on GitHub. */
@Serializable
data class AppServers(
    val servers: List<String> = emptyList(),
    val updatedAt: String? = null,
    val directory: Directory = Directory(),
) {
    @Serializable
    data class Directory(
        /** The server has a GitHub token to keep the copy up to date. */
        val configured: Boolean = false,
        val url: String = "",
        val syncedAt: String? = null,
        val error: String? = null,
    )
}

@Serializable
data class AppServersRequest(val servers: List<String>)
