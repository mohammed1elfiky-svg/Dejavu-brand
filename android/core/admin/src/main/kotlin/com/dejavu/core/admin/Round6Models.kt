package com.dejavu.core.admin

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- deals ---

/** GET admin/deals: a quantity offer. [type]: BUY_X_GET_Y or BUNDLE. */
@Serializable
data class Deal(
    val id: String,
    val name: String,
    val type: String,
    val buyQty: Int = 1,
    val freeQty: Int = 1,
    /** Price of the whole set (BUNDLE). */
    val bundlePrice: String? = null,
    val startsAt: String,
    val endsAt: String? = null,
    val isActive: Boolean = true,
    val atPos: Boolean = true,
    val online: Boolean = true,
    /** "قطعة + قطعة مجاناً", "طقم بسعر 200"… */
    val label: String = "",
    /** Active and within its dates. */
    val running: Boolean = false,
    val products: List<Product> = emptyList(),
) {
    @Serializable
    data class Product(val id: String, val nameAr: String, val slug: String, val basePrice: String, val imageUrl: String? = null)
}

/** POST / PATCH admin/deals. Send endsAt = "" to clear the end date. */
@Serializable
data class DealRequest(
    val name: String,
    val type: String,
    val buyQty: Int? = null,
    val freeQty: Int? = null,
    val bundlePrice: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val isActive: Boolean? = null,
    val atPos: Boolean? = null,
    val online: Boolean? = null,
    val productIds: List<String>,
)

/** POST admin/pos/quote */
@Serializable
data class PosQuoteRequest(val items: List<PosItem>, val customerId: String? = null)

/** The offers a till basket gets (SAR). */
@Serializable
data class PosQuote(
    val subtotal: String,
    val dealDiscount: String,
    val total: String,
    val deals: List<Applied> = emptyList(),
    val lines: List<Line> = emptyList(),
) {
    @Serializable
    data class Applied(val dealId: String, val name: String, val times: Int = 1, val discount: String)

    @Serializable
    data class Line(val variantId: String, val unitPrice: String, val quantity: Int, val discount: String)
}

// ----------------------------------------------------------- pre-orders ---

@Serializable
data class AdminPreOrder(
    val id: String,
    val number: String,
    /** WAITING, NOTIFIED, CONVERTED, CANCELLED. */
    val status: String,
    val quantity: Int,
    val note: String? = null,
    val layawayId: String? = null,
    val createdAt: String,
    val notifiedAt: String? = null,
    val customer: Layaway.Customer,
    /** Null for a piece not in the catalogue yet ([itemName]); linked once it is added. */
    val variantId: String? = null,
    val sku: String? = null,
    val size: String? = null,
    val color: String? = null,
    val productId: String? = null,
    val name: String,
    val slug: String? = null,
    val imageUrl: String? = null,
    /** Pieces free on the shelf now. */
    val inStock: Int = 0,
    val expectedDate: String? = null,
    /** Taken by staff: put aside at once when it arrives (no confirmation asked). */
    val byStaff: Boolean = false,
    val itemName: String? = null,
    val itemSize: String? = null,
    val itemColor: String? = null,
    /** Agreed price per piece, SAR. */
    val unitPrice: String? = null,
    /** Arrived; the customer has not confirmed yet. */
    val awaitingConfirmation: Boolean = false,
    val confirmedAt: String? = null,
)

@Serializable
data class CreatePreOrderRequest(
    val customerId: String,
    val variantId: String? = null,
    val quantity: Int,
    val note: String? = null,
    val itemName: String? = null,
    val itemSize: String? = null,
    val itemColor: String? = null,
    val unitPrice: String? = null,
)

/** Changes to an open pre-order; "" clears a text field, null leaves it. */
@Serializable
data class UpdatePreOrderRequest(
    val variantId: String? = null,
    val quantity: Int? = null,
    val note: String? = null,
    val itemName: String? = null,
    val itemSize: String? = null,
    val itemColor: String? = null,
    val unitPrice: String? = null,
)

@Serializable
data class ArrivalsChecked(val handled: Int = 0)

/** GET admin/pre-orders/incoming: pieces on their way per size/colour. */
@Serializable
data class IncomingVariant(
    val variantId: String,
    val sku: String,
    val size: String? = null,
    val color: String? = null,
    val productId: String,
    val name: String,
    val imageUrl: String? = null,
    val incoming: Int,
    val claimed: Int,
    val open: Int,
    val expectedDate: String? = null,
)

@Serializable
data class PreOrderConverted(val preOrder: AdminPreOrder, val layaway: Layaway)

// ------------------------------------------------------------------- AI ---

@Serializable
data class ProductDraftRequest(val imageFileId: String)

/** POST admin/ai/product-draft: suggestions for a new product from its photo. */
@Serializable
data class ProductDraft(
    val nameAr: String = "",
    val descriptionAr: String = "",
    val categoryId: String? = null,
    val brandId: String? = null,
    val color: String? = null,
    val colorHex: String? = null,
    val kind: String? = null,
    val heelHeightCm: Double? = null,
    val tags: List<String> = emptyList(),
)

/** [channel]: PUSH or SMS. */
@Serializable
data class MarketingTextRequest(val idea: String, val channel: String)

@Serializable
data class MarketingTexts(val suggestions: List<Suggestion> = emptyList()) {
    @Serializable
    data class Suggestion(val title: String = "", val body: String)
}

/** GET admin/ai/weekly-summary */
@Serializable
data class WeeklySummary(val text: String, val generatedAt: String, val byAi: Boolean = false, val stats: Stats) {
    @Serializable
    data class Stats(
        val from: String,
        val to: String,
        val revenue: String,
        val previousRevenue: String,
        val changePercent: Double? = null,
        val orders: Int,
        val previousOrders: Int,
        val posOrders: Int = 0,
        val appOrders: Int = 0,
        val newCustomers: Int = 0,
        val returns: Int = 0,
        val topProducts: List<Top> = emptyList(),
        val runningOut: List<RunningOut> = emptyList(),
    )

    @Serializable
    data class Top(val name: String, val units: Int, val revenue: String, val available: Int = 0)

    @Serializable
    data class RunningOut(val name: String, val size: String? = null, val color: String? = null, val available: Int, val sold: Int)
}
