package com.dejavu.customer.ui.product

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.model.StoreVariant
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Soft spring used by every option circle: a lively bounce when picked or pressed. */
private val bounce = spring<Float>(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)

/**
 * Wraps [content] so that changing [key] (the chosen color) morphs it: the old picture
 * eases out while shrinking a little and the new one springs in, instead of a hard cut.
 */
@Composable
fun <T> Morph(key: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(spring(stiffness = Spring.StiffnessLow)) + scaleIn(initialScale = 0.9f, animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)))
                .togetherWith(fadeOut(tween(220)) + scaleOut(targetScale = 1.06f, animationSpec = tween(260)))
        },
        label = "morph",
    ) { content(it) }
}

/** Spring "pop" while pressed and a bigger one while selected. */
@Composable
private fun Modifier.springy(selected: Boolean, source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.88f else if (selected) 1.14f else 1f, bounce, label = "springy")
    return this.scale(s)
}

/** A color circle of the options sheet. */
@Composable
fun ColorCircle(hex: String?, name: String?, selected: Boolean, onClick: () -> Unit) {
    val fill = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.surfaceVariant
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .springy(selected, source)
            .clip(CircleShape)
            .background(fill)
            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Liquid.Gold else Liquid.InkSoft.copy(alpha = 0.35f)), CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = name, tint = if (fill.luminance() > 0.6f) Color.Black.copy(0.7f) else Color.White, modifier = Modifier.size(18.dp))
    }
}

private fun Color.luminance() = 0.299f * red + 0.587f * green + 0.114f * blue

/** A size circle of the options sheet; struck through when sold out. */
@Composable
fun SizeCircle(label: String, selected: Boolean, inStock: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(46.dp)
            .springy(selected, source)
            .clip(CircleShape)
            .background(if (selected) Liquid.GoldBrush else androidx.compose.ui.graphics.SolidColor(Liquid.Frost.copy(alpha = 0.55f)))
            .border(BorderStroke(1.dp, if (selected) Liquid.GoldDeep else Liquid.InkSoft.copy(alpha = 0.35f)), CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (inStock) null else TextDecoration.LineThrough,
            color = when {
                selected -> Color.White
                inStock -> Liquid.Ink
                else -> Liquid.InkSoft
            },
        )
    }
}

/**
 * The options (color, size, quantity) as a glass sheet floating above the buy bar. A tap on
 * its handle folds it to one line; the height change is a spring.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FloatingOptionsSheet(
    product: StoreProduct,
    selectedColor: String?,
    selectedSize: String?,
    variant: StoreVariant?,
    onColor: (String?) -> Unit,
    onSize: (String?) -> Unit,
    quantity: @Composable () -> Unit,
    availability: @Composable () -> Unit,
) {
    val colors = product.variants.map { it.color to it.colorHex }.distinctBy { it.first }
    val hasColors = colors.size > 1 || colors.firstOrNull()?.first != null
    val sizes = product.variants.filter { it.color == selectedColor }
    val hasSizes = sizes.any { it.size != null }
    if (!hasColors && !hasSizes && variant?.inStock != true) return

    var open by rememberSaveable { mutableStateOf(true) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .glass(RoundedCornerShape(24.dp), elevation = 18.dp)
            .animateContentSize(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (hasColors || hasSizes) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                listOfNotNull(selectedColor?.takeIf { hasColors }, selectedSize?.takeIf { hasSizes }?.let { "مقاس $it" }).joinToString("  •  ").ifBlank { "الخيارات" },
                style = MaterialTheme.typography.titleSmall,
                color = Liquid.GoldDeep,
            )
            Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, contentDescription = if (open) "طي" else "فتح", tint = Liquid.GoldDeep)
        }
        AnimatedVisibility(
            visible = open || !(hasColors || hasSizes),
            enter = fadeIn() + slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it / 3 },
            exit = fadeOut(tween(150)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (hasColors) {
                    Text("اللون", style = MaterialTheme.typography.labelLarge, color = Liquid.InkSoft)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        colors.forEach { (name, hex) -> ColorCircle(hex, name, selected = name == selectedColor) { onColor(name) } }
                    }
                }
                if (hasSizes) {
                    Text("المقاس", style = MaterialTheme.typography.labelLarge, color = Liquid.InkSoft)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sizes.forEach { v -> SizeCircle(v.size ?: "-", selected = v.size == selectedSize, inStock = v.inStock) { onSize(v.size) } }
                    }
                }
                availability()
                if (variant?.inStock == true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("الكمية", style = MaterialTheme.typography.labelLarge, color = Liquid.InkSoft)
                        quantity()
                    }
                }
            }
        }
    }
}

/** The floating glass cart button; it bumps when something lands in it. */
@Composable
fun FloatingCartButton(count: Int, bump: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(bump) {
        if (bump > 0) {
            scale.animateTo(1.28f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
            scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        }
    }
    Box(
        modifier
            .scale(scale.value)
            .size(52.dp)
            .glass(CircleShape, elevation = 14.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BadgedBox(badge = { if (count > 0) Badge { Text(if (count > 99) "99+" else "$count") } }) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = "السلة", tint = Liquid.GoldDeep)
        }
    }
}

/**
 * The picture shrinking toward the cart button: starts at [from] (the product picture) and
 * lands on [to] (the cart button), both in root coordinates; [origin] is this layer's own
 * position in the root. Calls [onLanded] at the end.
 */
@Composable
fun FlyToCart(
    flight: Int,
    imageUrl: String?,
    from: Rect?,
    to: Rect?,
    origin: Offset,
    onLanded: () -> Unit,
) {
    if (flight == 0 || from == null || to == null) return
    val progress = remember(flight) { Animatable(0f) }
    var done by remember(flight) { mutableStateOf(false) }
    LaunchedEffect(flight) {
        progress.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
        done = true
        onLanded()
    }
    if (done) return
    val t = progress.value
    val density = LocalDensity.current
    val size = 120.dp
    val sizePx = with(density) { size.toPx() }
    // Start centered on the product picture, end centered on the cart, along a slight arc.
    val start = from.center
    val end = to.center
    val x = start.x + (end.x - start.x) * t
    val arc = -160f * 4f * t * (1f - t)
    val y = start.y + (end.y - start.y) * t + arc
    val s = 1f - 0.88f * t
    Box(
        Modifier
            .offset { IntOffset((x - origin.x - sizePx / 2).roundToInt(), (y - origin.y - sizePx / 2).roundToInt()) }
            .size(size)
            .graphicsLayer {
                scaleX = s
                scaleY = s
                alpha = 1f - 0.35f * t
            }
            .glass(RoundedCornerShape(24.dp), elevation = 16.dp),
    ) {
        ProductImage(imageUrl, Modifier.size(size), contentDescription = null)
    }
}

/** The confirmation card sliding up from the bottom after adding to the cart. */
@Composable
fun AddedCard(visible: Boolean, productName: String, onViewCart: () -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .glass(RoundedCornerShape(24.dp), elevation = 20.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Liquid.Success), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text("تمت الإضافة إلى السلة", style = MaterialTheme.typography.titleSmall)
                    Text(productName, style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onViewCart, modifier = Modifier.weight(1f)) { Text("عرض السلة") }
                TextButton(onClick = onContinue, modifier = Modifier.weight(1f)) { Text("متابعة التسوق", color = Liquid.GoldDeep) }
            }
        }
    }
}
