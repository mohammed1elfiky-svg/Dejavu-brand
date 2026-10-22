@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.components

import androidx.compose.foundation.clickable
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

@Composable
fun ProductCard(product: StoreProduct, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            ProductImage(
                product.images.firstOrNull()?.url,
                Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                contentDescription = product.nameAr,
            )
            if (!product.inStock) {
                Tag("نفدت الكمية", Color.Black.copy(alpha = 0.7f), Modifier.align(Alignment.TopStart).padding(8.dp))
            } else {
                product.discountBadge()?.let { Tag(it, Gold, Modifier.align(Alignment.TopStart).padding(8.dp)) }
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(product.nameAr, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val cheapest = product.variants.minByOrNull { Money.parse(it.price) }
            if (product.merchantPrice) Text("سعر التاجر", style = MaterialTheme.typography.labelSmall, color = Gold)
            PriceText(cheapest?.price ?: product.basePrice, cheapest?.compareAtPrice ?: product.compareAtPrice)
        }
    }
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clickable(onClick = onClick)) {
        ProductImage(imageUrl, Modifier.size(64.dp).clip(CircleShape))
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
