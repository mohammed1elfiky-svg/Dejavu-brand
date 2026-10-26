@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.components

import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.glass
import com.dejavu.core.ui.theme.liquidClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import coil3.compose.AsyncImage
import com.dejavu.core.model.Brand
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dejavu.core.model.Money
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.ui.PriceText
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.Tag
import com.dejavu.core.ui.theme.Gold
import java.math.RoundingMode
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Numerals
import com.dejavu.customer.ui.appContainer
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProductCard(product: StoreProduct, onClick: () -> Unit, modifier: Modifier = Modifier, showWishlist: Boolean = true) {
    GlassCard(modifier, elevation = 10.dp, contentPadding = 0.dp, onClick = onClick) {
        Box {
            ProductImage(
                product.images.firstOrNull()?.url,
                Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                contentDescription = product.nameAr,
            )
            Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!product.inStock) {
                    Tag("نفدت الكمية", Color.Black.copy(alpha = 0.7f))
                } else {
                    product.discountBadge()?.let { Tag(it, Gold) }
                    if (product.isNew) Tag("جديد", Liquid.Success)
                    fewLeftText(product.fewLeft)?.let { Tag(it, Liquid.Danger) }
                }
            }
            if (showWishlist) {
                val toggle = rememberWishlistToggle()
                WishlistHeart(product, toggle, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(product.nameAr, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val cheapest = product.variants.minByOrNull { Money.parse(it.price) }
            if (product.merchantPrice) Text("سعر التاجر", style = MaterialTheme.typography.labelSmall, color = Liquid.GoldDeep)
            PriceText(cheapest?.price ?: product.basePrice, cheapest?.compareAtPrice ?: product.compareAtPrice)
            if (product.inStock) {
                PromoCountdown(product.promotion?.endsAt, style = MaterialTheme.typography.labelSmall, short = true)
            }
        }
    }
}

/** A titled horizontal row of product cards; nothing when [products] is empty. */
@Composable
fun ProductStrip(title: String, products: List<StoreProduct>, openProduct: (String) -> Unit, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    if (products.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        SectionTitle(title, action, onAction)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(products, key = { it.id }) { p ->
                ProductCard(p, onClick = { openProduct(p.slug) }, modifier = Modifier.width(160.dp))
            }
        }
    }
}

// ----------------------------------------------------------------- wish list ---

/**
 * Adds or removes a product from the wish list. Signed out, it shows a toast and calls
 * [onLoginRequired].
 */
@Composable
fun rememberWishlistToggle(onLoginRequired: () -> Unit = {}): (StoreProduct) -> Unit {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val login by rememberUpdatedState(onLoginRequired)
    val busy = remember { mutableSetOf<String>() }
    return remember(container, context, scope) {
        { product: StoreProduct ->
            if (!container.session.isLoggedIn) {
                Toast.makeText(context, "سجّل الدخول لإضافة المفضلة", Toast.LENGTH_SHORT).show()
                login()
            } else if (busy.add(product.id)) {
                scope.launch {
                    container.extras.toggleWishlist(product.id).onFailure {
                        Toast.makeText(context, it.userMessage(), Toast.LENGTH_SHORT).show()
                    }
                    busy.remove(product.id)
                }
            }
        }
    }
}

/** A heart on a small glass circle: filled when [product] is in the wish list. */
@Composable
fun WishlistHeart(product: StoreProduct, onToggle: (StoreProduct) -> Unit, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val ids by appContainer().extras.wishlistIds.collectAsStateWithLifecycle()
    val saved = product.id in ids
    Box(
        modifier.size(size).clip(CircleShape).background(Liquid.Frost.copy(alpha = 0.85f)).clickable { onToggle(product) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (saved) "إزالة من المفضلة" else "أضف إلى المفضلة",
            tint = if (saved) Liquid.Danger else Liquid.GoldDeep,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

// ------------------------------------------------------------- timed offers ---

/** "باقي قطعة واحدة" / "باقي قطعتان فقط" / "باقي 3 قطع" for 1-3 pieces left, else null. */
fun fewLeftText(left: Int?): String? = when (left) {
    1 -> "باقي قطعة واحدة"
    2 -> "باقي قطعتان فقط"
    3 -> Numerals.of("باقي 3 قطع")
    else -> null
}

/** "2ي 05:12:09" (days, then hours:minutes:seconds). */
fun countdownText(millisLeft: Long): String {
    val total = (millisLeft / 1000).coerceAtLeast(0)
    val days = total / 86_400
    val clock = String.format(Locale.US, "%02d:%02d:%02d", (total % 86_400) / 3600, (total % 3600) / 60, total % 60)
    return Numerals.of(if (days > 0) "${days}ي $clock" else clock)
}

/** A live "ينتهي العرض خلال 2ي 05:12:09" while the offer ending at [endsAt] (ISO time) runs. */
@Composable
fun PromoCountdown(
    endsAt: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    short: Boolean = false,
) {
    val end = remember(endsAt) { endsAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } } ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) {
        while (now < end) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val left = end - now
    if (left <= 0) return
    Text(
        (if (short) "ينتهي خلال " else "ينتهي العرض خلال ") + countdownText(left),
        style = style,
        color = Liquid.Danger,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Percent off while a promotion runs ("20.00" → 20), else the saving versus the strike-through price. */
fun StoreProduct.discountPercent(): Int? {
    promotion?.percent?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { return it.setScale(0, RoundingMode.HALF_UP).toInt() }
    val cheapest = variants.minByOrNull { Money.parse(it.price) }
    return Money.discountPercent(cheapest?.price ?: basePrice, cheapest?.compareAtPrice ?: compareAtPrice)
}

/** "خصم 20%" on promoted or reduced products, null otherwise. */
fun StoreProduct.discountBadge(): String? {
    val percent = discountPercent()
    return when {
        percent != null -> "خصم $percent%"
        compareAtPrice != null -> "خصم"
        else -> null
    }
}

@Composable
fun CategoryAvatar(name: String, imageUrl: String?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).liquidClickable(onClick = onClick)) {
        Box(Modifier.padding(top = 4.dp).size(64.dp).glass(CircleShape, elevation = 6.dp).padding(3.dp)) {
            ProductImage(imageUrl, Modifier.fillMaxSize().clip(CircleShape))
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Brand logo in a round glass tile (its initial in gold when there is no logo). */
@Composable
fun BrandLogo(brand: Brand, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).glass(CircleShape, elevation = if (size > 32.dp) 6.dp else 0.dp), contentAlignment = Alignment.Center) {
        if (brand.logoUrl != null) {
            AsyncImage(
                model = brand.logoUrl,
                contentDescription = brand.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size / 7),
            )
        } else {
            Text(
                brand.name.trim().take(1).uppercase(),
                color = Liquid.GoldDeep,
                style = if (size > 32.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A brand tile for the home screen's "الماركات" row. */
@Composable
fun BrandAvatar(brand: Brand, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp).liquidClickable(onClick = onClick)) {
        BrandLogo(brand, 64.dp, Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(6.dp))
        Text(brand.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "الماركة: <name>" pill on the product page; opens the brand's products. */
@Composable
fun BrandChip(brand: Brand, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.liquidClickable(onClick = onClick).glass(shape, elevation = 4.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (brand.logoUrl != null) BrandLogo(brand, 24.dp)
        Text("الماركة: ${brand.name}", style = MaterialTheme.typography.labelLarge, color = Liquid.GoldDeep, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------ liquid glass helpers ---

/** Gold section title (the admin app's look), with an optional gold action link. */
@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold)
        if (action != null) {
            Text(
                action,
                color = Liquid.GoldDeep,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(50)).liquidClickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** An icon on a liquid-gold circle, as used by the admin app's menu tiles. */
@Composable
fun GoldIconCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 42.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(Liquid.GoldBrush),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** A floating glass row: leading content, title/subtitle and optional trailing content. */
@Composable
fun GlassRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    GlassCard(modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Liquid.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
            }
            trailing?.invoke()
        }
    }
}

/** Gold selected colors for filter chips. */
@Composable
fun goldChipColors() = androidx.compose.material3.FilterChipDefaults.filterChipColors(
    selectedContainerColor = Liquid.GoldPale,
    selectedLabelColor = Liquid.Ink,
    selectedLeadingIconColor = Liquid.GoldDeep,
    containerColor = Color.White.copy(alpha = 0.55f),
)

/** A bottom action panel floating as glass above the navigation bar. */
@Composable
fun GlassBottomPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    GlassCard(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        elevation = 16.dp,
        contentPadding = 16.dp,
        content = content,
    )
}
