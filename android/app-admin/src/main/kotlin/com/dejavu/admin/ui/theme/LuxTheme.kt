package com.dejavu.admin.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dejavu.core.ui.theme.DejavuTheme
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.LiquidBackdrop
import com.dejavu.core.ui.theme.GlassCard as LiquidGlassCard

/** The admin app's names for the shared liquid-glass palette. */
object Lux {
    val Gold = Liquid.Gold
    val GoldDeep get() = Liquid.GoldDeep
    val GoldLight get() = Liquid.GoldPale
    val Champagne get() = if (com.dejavu.core.ui.theme.LiquidMode.dark) Color(0xFF2E2819) else Color(0xFFFBF6E4)
    val Pearl get() = Liquid.Pearl
    val Ink get() = Liquid.Ink
    val InkSoft get() = Liquid.InkSoft
    val Hairline = Liquid.Gold.copy(alpha = 0.2f)

    val GoldBrush get() = Liquid.GoldBrush
    val GlassBrush get() = Liquid.GlassFill
    val GlassEdge get() = Liquid.GlassEdge
}

/** Same design system as the customer app. */
@Composable
fun LuxTheme(fontScale: Float = 1f, content: @Composable () -> Unit) = DejavuTheme(fontScale, content)

@Composable
fun LuxBackdrop(content: @Composable BoxScope.() -> Unit) = LiquidBackdrop(content)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = Liquid.CardShape,
    elevation: Dp = 12.dp,
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = LiquidGlassCard(modifier, shape, elevation, contentPadding, onClick, content)

/** A liquid-gold pill for totals and key figures. */
@Composable
fun GoldPill(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Lux.GoldBrush)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        content = content,
    )
}

@Composable
fun luxShapes() = MaterialTheme.shapes
