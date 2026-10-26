@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dejavu.core.model.Money
import com.dejavu.core.model.Numerals
import com.dejavu.core.model.PreOrder
import com.dejavu.core.model.ProductDeal
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.model.StoreVariant
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.QuantityStepper
import com.dejavu.core.ui.Tag
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.customer.ui.components.dealLabel
import java.math.BigDecimal

/** Lowest price of [p] (its cheapest variant), as shown on product cards. */
internal fun StoreProduct.cheapestPrice(): String = variants.minByOrNull { Money.parse(it.price) }?.price ?: basePrice

/** "21 أكتوبر 2026" from a full ISO time or a plain "2026-10-21" date. */
internal fun formatExpected(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return formatDate(iso).ifBlank { formatDate(iso.take(10) + "T12:00:00Z") }
}

/** A variant that is sold out now but can be reserved from the shipment on its way. */
internal fun StoreVariant.preOrderable(): Boolean = (!inStock || availableQty <= 0) && incomingQty > 0

/**
 * The quantity offers on this product. [known] are products already loaded on the page
 * (loaded by id, plus related ones) used to show the other products of the offer.
 * [onAddSet] gets the variant ids of one piece of each set product, offered only when every
 * product of the set is known and has a single in-stock option (otherwise the shopper opens
 * each product and picks the size).
 */
@Composable
internal fun ProductDeals(
    product: StoreProduct,
    selected: StoreVariant?,
    known: List<StoreProduct>,
    openProduct: (String) -> Unit,
    onAddSet: (List<String>) -> Unit,
    adding: Boolean,
) {
    product.deals.forEach { deal -> DealCard(deal, product, selected, known, openProduct, onAddSet, adding) }
}

@Composable
private fun DealCard(
    deal: ProductDeal,
    product: StoreProduct,
    selected: StoreVariant?,
    known: List<StoreProduct>,
    openProduct: (String) -> Unit,
    onAddSet: (List<String>) -> Unit,
    adding: Boolean,
) {
    val otherIds = deal.productIds.filter { it != product.id }
    val others = otherIds.mapNotNull { id -> known.firstOrNull { it.id == id } }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎁", style = MaterialTheme.typography.titleMedium)
                Tag(dealLabel(deal), Liquid.Success)
            }
            if (deal.type == "BUNDLE") {
                val allKnown = others.size == otherIds.size
                val price = deal.bundlePrice?.let { Money.format(it) } ?: ""
                val text = if (allKnown && others.isNotEmpty()) {
                    val names = (listOf(product) + others).joinToString(" + ") { it.nameAr }
                    val sum = (listOf(product) + others).fold(BigDecimal.ZERO) { acc, p -> acc + Money.parse(p.cheapestPrice()) }
                    "طقم: $names بسعر $price بدل ${Money.format(sum)}"
                } else {
                    Numerals.of("طقم من ${deal.productIds.size} قطع بسعر $price — أضيفي قطعة من كل منتج في الطقم للسلة")
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Liquid.Ink)
                OfferProducts(others, openProduct)
                // One tap only when nothing needs choosing: every set product known with a single option.
                val singles = others.map { p -> p.variants.singleOrNull()?.takeIf { it.inStock && it.availableQty > 0 } }
                if (allKnown && others.isNotEmpty() && selected != null && selected.inStock && singles.all { it != null }) {
                    Button(onClick = { onAddSet(listOf(selected.id) + singles.mapNotNull { it?.id }) }, enabled = !adding, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("أضيفي الطقم للسلة")
                    }
                } else if (others.isNotEmpty()) {
                    Text("اختاري المقاس من كل منتج في الطقم وأضيفيه للسلة", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
                }
            } else {
                val free = if (deal.freeQty == 1) "قطعة" else "${deal.freeQty} قطع"
                val more = if (otherIds.isNotEmpty()) "، ويشمل العرض منتجات أخرى" else ""
                Text(
                    Numerals.of("اشترِ ${deal.buyQty} واحصلي على $free مجاناً — القطعة الأرخص مجاناً$more"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Liquid.Ink,
                )
                OfferProducts(others, openProduct)
            }
        }
    }
}

/** A horizontal row of small tappable product tiles. */
@Composable
private fun OfferProducts(products: List<StoreProduct>, openProduct: (String) -> Unit) {
    if (products.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        products.forEach { p ->
            GlassCard(Modifier.width(110.dp), elevation = 6.dp, contentPadding = 0.dp, onClick = { openProduct(p.slug) }) {
                ProductImage(p.images.firstOrNull()?.url, Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)), contentDescription = p.nameAr)
                Column(Modifier.padding(8.dp)) {
                    Text(p.nameAr, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Money.format(p.cheapestPrice()), style = MaterialTheme.typography.labelMedium, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** "قادم في الشحنة القادمة" and the expected arrival, for a sold-out size with pieces on the way. */
@Composable
internal fun IncomingInfo(variant: StoreVariant) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.DateRange, contentDescription = null, tint = Liquid.GoldDeep, modifier = Modifier.size(18.dp))
            Text("قادم في الشحنة القادمة", style = MaterialTheme.typography.labelLarge, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold)
        }
        val date = formatExpected(variant.expectedDate)
        if (date.isNotEmpty()) Text("الوصول المتوقع: $date", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
        Text("احجزي قطعتك الآن ونعلمك فور وصولها", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
    }
}

/** Picks how many pieces to reserve from the incoming shipment. */
@Composable
internal fun PreOrderDialog(variant: StoreVariant, busy: Boolean, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var qty by remember(variant.id) { mutableIntStateOf(1) }
    val max = minOf(variant.incomingQty, 20).coerceAtLeast(1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("احجزي من الشحنة القادمة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(listOfNotNull(variant.color, variant.size?.let { "مقاس $it" }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                val date = formatExpected(variant.expectedDate)
                if (date.isNotEmpty()) Text("الوصول المتوقع: $date", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("الكمية", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                    QuantityStepper(qty, { qty = it.coerceIn(1, max) }, max = max, enabled = !busy)
                }
                Text("لا تدفعين شيئاً الآن، سنرسل لك إشعاراً عند وصولها.", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(qty) }, enabled = !busy) { Text("تأكيد الحجز") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("تراجع") } },
    )
}

/** Shown after a successful pre-order. */
@Composable
internal fun PreOrderDoneDialog(preOrder: PreOrder, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تم حجز قطعتك") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Numerals.of("رقم الحجز ${preOrder.number} — ${preOrder.name} × ${preOrder.quantity}"), style = MaterialTheme.typography.bodyMedium)
                Text("سنرسل لك إشعاراً عند وصولها، ثم تطلبينها من التطبيق قبل غيرك.", style = MaterialTheme.typography.bodyMedium)
                val date = formatExpected(preOrder.expectedDate)
                if (date.isNotEmpty()) Text("الوصول المتوقع: $date", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
                Spacer(Modifier.height(2.dp))
                Text("تجدين حجوزاتك في حسابي ← حجوزاتي المسبقة", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("حسناً") } },
    )
}
