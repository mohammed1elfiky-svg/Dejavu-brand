package com.dejavu.admin.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** White & gold palette of the admin app. */
object Lux {
    val Gold = Color(0xFFC9A44C)
    val GoldDeep = Color(0xFF9A7426)
    val GoldLight = Color(0xFFF4E6BF)
    val Champagne = Color(0xFFFBF3DF)
    val Pearl = Color(0xFFFFFDF8)
    val Ink = Color(0xFF2B2416)
    val InkSoft = Color(0xFF6E6250)
    val Hairline = Color(0x33C9A44C)

    /** Soft gold sheen for primary buttons and highlights. */
    val GoldBrush = Brush.linearGradient(listOf(Color(0xFFE2C275), Gold, GoldDeep))

    /** Frosted-glass fill: mostly white, letting the background glow through. */
    val GlassBrush = Brush.verticalGradient(listOf(Color(0xE6FFFFFF), Color(0xB3FFFFFF)))
    val GlassEdge = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0x66E2C275), Color(0xCCFFFFFF)))
}

private val LuxColors = lightColorScheme(
    primary = Lux.GoldDeep,
    onPrimary = Color.White,
    primaryContainer = Lux.GoldLight,
    onPrimaryContainer = Lux.Ink,
    secondary = Lux.Gold,
    onSecondary = Color.White,
    secondaryContainer = Lux.Champagne,
    onSecondaryContainer = Lux.Ink,
    tertiary = Lux.GoldDeep,
    // Transparent so the glowing backdrop shows behind every screen.
    background = Color.Transparent,
    onBackground = Lux.Ink,
    surface = Color(0xD9FFFFFF),
    onSurface = Lux.Ink,
    surfaceVariant = Color(0x99F4E6BF),
    onSurfaceVariant = Lux.InkSoft,
    surfaceContainerLowest = Color(0xCCFFFFFF),
    surfaceContainerLow = Color(0xD9FFFFFF),
    surfaceContainer = Color(0xE0FFFFFF),
    // Dialogs and menus stay nearly opaque for legibility.
    surfaceContainerHigh = Color(0xFAFFFDF8),
    surfaceContainerHighest = Color(0xFFFFFBF0),
    outline = Color(0xFFE0CFA3),
    outlineVariant = Lux.Hairline,
)

private val LuxType = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun LuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LuxColors,
        typography = LuxType,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
    ) { LuxBackdrop { content() } }
}

/** Pearl-white backdrop with slow gold glows: the "liquid" behind the glass cards. */
@Composable
fun LuxBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Lux.Pearl, Color(0xFFFFF8EA), Lux.Pearl)))) {
        Canvas(Modifier.fillMaxSize()) {
            fun glow(center: Offset, radius: Float, color: Color) =
                drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius), radius, center)
            glow(Offset(size.width * 0.9f, size.height * 0.08f), size.minDimension * 0.75f, Color(0x40E2C275))
            glow(Offset(size.width * 0.05f, size.height * 0.55f), size.minDimension * 0.65f, Color(0x2EC9A44C))
            glow(Offset(size.width * 0.7f, size.height * 0.98f), size.minDimension * 0.7f, Color(0x33F4E6BF))
        }
        content()
    }
}

/** Frosted-glass card: translucent white, bright hairline edge, soft gold-tinted shadow. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    elevation: Dp = 10.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .shadow(elevation, shape, ambientColor = Lux.Gold.copy(alpha = 0.25f), spotColor = Lux.GoldDeep.copy(alpha = 0.22f))
            .clip(shape)
            .background(Lux.GlassBrush)
            .border(BorderStroke(1.dp, Lux.GlassEdge), shape)
            .padding(contentPadding),
        content = content,
    )
}

/** A gold pill for totals and key figures. */
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
