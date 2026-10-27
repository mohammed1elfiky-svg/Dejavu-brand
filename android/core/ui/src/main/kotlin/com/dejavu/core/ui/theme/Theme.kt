package com.dejavu.core.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry

/*
 * "Ultra-Modern Liquid Glass" design system shared by the customer and admin apps:
 * pearl backdrop, frosted white glass surfaces, liquid-gold accents, floating bars,
 * press micro-interactions and soft slide transitions.
 */

/**
 * Light or dark look of the whole app. Every [Liquid] color reads it, so changing it
 * repaints the screens (set by the app from the user's choice before [DejavuTheme]).
 */
object LiquidMode {
    var dark by mutableStateOf(false)
}

/** White & gold luxury palette (deep ink & gold in dark mode). */
object Liquid {
    val Pearl get() = if (LiquidMode.dark) Color(0xFF15130F) else Color(0xFFF8F9FA)
    val Mist get() = if (LiquidMode.dark) Color(0xFF1E1B16) else Color(0xFFF1F3F5)
    val Gold = Color(0xFFD4AF37)
    val GoldSoft = Color(0xFFC5A059)
    val GoldPale get() = if (LiquidMode.dark) Color(0xFF5C4A1E) else Color(0xFFF3E5AB)
    val GoldDeep get() = if (LiquidMode.dark) Color(0xFFE0BE5A) else Color(0xFFAA771C)
    val Ink get() = if (LiquidMode.dark) Color(0xFFF2EEE6) else Color(0xFF1A1A1A)
    val InkSoft get() = if (LiquidMode.dark) Color(0xFFADA79B) else Color(0xFF6C757D)
    val Success get() = if (LiquidMode.dark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
    val Danger get() = if (LiquidMode.dark) Color(0xFFEF5350) else Color(0xFFC62828)

    /** The glass tint: white in light mode, a warm charcoal in dark mode. */
    val Frost get() = if (LiquidMode.dark) Color(0xFF2B2720) else Color.White

    private val goldLight = Brush.linearGradient(listOf(Color(0xFFF3E5AB), Gold, Color(0xFFAA771C)))
    private val goldDark = Brush.linearGradient(listOf(Color(0xFFE6CC72), Gold, Color(0xFF8C6216)))
    private val goldLightH = Brush.horizontalGradient(listOf(Color(0xFFF3E5AB), Gold, Color(0xFFAA771C)))
    private val goldDarkH = Brush.horizontalGradient(listOf(Color(0xFFE6CC72), Gold, Color(0xFF8C6216)))

    /** Liquid gold for primary buttons, active items and highlight cards. */
    val GoldBrush get() = if (LiquidMode.dark) goldDark else goldLight
    val GoldBrushH get() = if (LiquidMode.dark) goldDarkH else goldLightH

    private val fillLight = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.18f)))
    private val fillDark = Brush.verticalGradient(listOf(Color(0xFF2B2720).copy(alpha = 0.92f), Color(0xFF221F19).copy(alpha = 0.85f)))
    private val edgeLight = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Gold.copy(alpha = 0.40f), Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.85f)))
    private val edgeDark = Brush.linearGradient(listOf(Gold.copy(alpha = 0.35f), Gold.copy(alpha = 0.12f), Color.White.copy(alpha = 0.06f)))

    /** Frosted glass fill and its glowing edge. */
    val GlassFill get() = if (LiquidMode.dark) fillDark else fillLight
    val GlassEdge get() = if (LiquidMode.dark) edgeDark else edgeLight

    val CardShape = RoundedCornerShape(24.dp)
    val ButtonShape = RoundedCornerShape(50)
    val FieldShape = RoundedCornerShape(16.dp)
}

// Names kept for screens written against the first palette.
val Aubergine get() = Liquid.Ink
val Gold = Liquid.Gold
val Cream get() = Liquid.Pearl
val Sand get() = Liquid.Mist
val Success get() = Liquid.Success
val Warning = Color(0xFFB26A00)

private fun liquidLight() = lightColorScheme(
    primary = Liquid.GoldDeep,
    onPrimary = Color.White,
    primaryContainer = Liquid.GoldPale,
    onPrimaryContainer = Liquid.Ink,
    secondary = Liquid.Gold,
    onSecondary = Color.White,
    secondaryContainer = Liquid.GoldPale.copy(alpha = 0.6f),
    onSecondaryContainer = Liquid.Ink,
    tertiary = Liquid.GoldSoft,
    // Transparent so the pearl backdrop and its glow show behind every screen.
    background = Color.Transparent,
    onBackground = Liquid.Ink,
    surface = Color.White.copy(alpha = 0.78f),
    onSurface = Liquid.Ink,
    surfaceVariant = Liquid.GoldPale.copy(alpha = 0.35f),
    onSurfaceVariant = Liquid.InkSoft,
    surfaceContainerLowest = Color.White.copy(alpha = 0.65f),
    surfaceContainerLow = Color.White.copy(alpha = 0.72f),
    surfaceContainer = Color.White.copy(alpha = 0.78f),
    // Dialogs, sheets and menus stay nearly opaque for legibility.
    surfaceContainerHigh = Color(0xFAFFFFFF),
    surfaceContainerHighest = Color(0xFFFDFCF8),
    outline = Liquid.Gold.copy(alpha = 0.35f),
    outlineVariant = Liquid.Gold.copy(alpha = 0.18f),
    error = Liquid.Danger,
)

private fun liquidDark() = darkColorScheme(
    primary = Liquid.GoldDeep,
    onPrimary = Color(0xFF1A1407),
    primaryContainer = Liquid.GoldPale,
    onPrimaryContainer = Liquid.Ink,
    secondary = Liquid.Gold,
    onSecondary = Color(0xFF1A1407),
    secondaryContainer = Liquid.GoldPale.copy(alpha = 0.6f),
    onSecondaryContainer = Liquid.Ink,
    tertiary = Liquid.GoldSoft,
    background = Color.Transparent,
    onBackground = Liquid.Ink,
    surface = Liquid.Frost.copy(alpha = 0.85f),
    onSurface = Liquid.Ink,
    surfaceVariant = Liquid.GoldPale.copy(alpha = 0.35f),
    onSurfaceVariant = Liquid.InkSoft,
    surfaceContainerLowest = Liquid.Frost.copy(alpha = 0.7f),
    surfaceContainerLow = Liquid.Frost.copy(alpha = 0.78f),
    surfaceContainer = Liquid.Frost.copy(alpha = 0.85f),
    surfaceContainerHigh = Color(0xFA2B2720),
    surfaceContainerHighest = Color(0xFF332E26),
    outline = Liquid.Gold.copy(alpha = 0.4f),
    outlineVariant = Liquid.Gold.copy(alpha = 0.2f),
    error = Liquid.Danger,
)

private fun liquidType() = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, color = Liquid.Ink),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Bold),
    )
}

val PriceStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)

/**
 * The app theme: liquid-glass colors (light or dark, see [LiquidMode]), rounded shapes and the
 * glowing pearl backdrop. [fontScale] enlarges or shrinks all text on top of the system setting.
 */
@Composable
fun DejavuTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = LiquidMode.dark
    val density = LocalDensity.current
    MaterialTheme(
        colorScheme = remember(dark) { if (dark) liquidDark() else liquidLight() },
        typography = remember(dark) { liquidType() },
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
            LiquidBackdrop { content() }
        }
    }
}

/** Pearl-to-mist gradient with slow liquid-gold glows: the depth behind the glass. */
@Composable
fun LiquidBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Liquid.Pearl, Liquid.Mist, Liquid.Pearl)))) {
        Canvas(Modifier.fillMaxSize()) {
            fun glow(center: Offset, radius: Float, color: Color) =
                drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius), radius, center)
            glow(Offset(size.width * 0.92f, size.height * 0.06f), size.minDimension * 0.8f, Liquid.GoldPale.copy(alpha = if (LiquidMode.dark) 0.35f else 0.55f))
            glow(Offset(size.width * 0.04f, size.height * 0.5f), size.minDimension * 0.7f, Liquid.Gold.copy(alpha = 0.12f))
            glow(Offset(size.width * 0.75f, size.height * 1.0f), size.minDimension * 0.75f, Liquid.GoldPale.copy(alpha = 0.4f))
        }
        content()
    }
}

// ---------------------------------------------------------------- glass ---

/**
 * Frosted glass: translucent white fill, glowing hairline edge and a soft floating shadow.
 * With a [backdrop] the content behind is blurred first (floating elements over content);
 * where blur is not available the glass gets a denser fill so it stays readable.
 */
fun Modifier.glass(shape: Shape = Liquid.CardShape, elevation: Dp = 12.dp, backdrop: Backdrop? = null): Modifier =
    this
        .shadow(elevation, shape, ambientColor = Color(0xFFD4AF37).copy(alpha = 0.16f), spotColor = Color(0xFFD4AF37).copy(alpha = 0.30f))
        .clip(shape)
        .then(if (backdrop != null) Modifier.backdropBlur(backdrop, shape) else Modifier)
        .then(if (backdrop != null && !backdrop.supported) Modifier.background(Liquid.Frost.copy(alpha = 0.88f)) else Modifier)
        .background(Liquid.GlassFill)
        .border(BorderStroke(1.dp, Liquid.GlassEdge), shape)

/** Press micro-interaction: the element sinks slightly while pressed, then springs back. */
fun Modifier.liquidClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}

/** A floating glass card; clickable (with the press effect) when [onClick] is given. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = Liquid.CardShape,
    elevation: Dp = 12.dp,
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .then(if (onClick != null) Modifier.liquidClickable(onClick = onClick) else Modifier)
            .glass(shape, elevation)
            .padding(contentPadding),
        content = content,
    )
}

/** Primary action: liquid-gold gradient with a warm glow and bold white text. */
@Composable
fun LiquidGoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 52.dp,
) {
    Box(
        modifier
            .heightIn(min = height)
            .liquidClickable(enabled = enabled, onClick = onClick)
            .shadow(if (enabled) 14.dp else 0.dp, Liquid.ButtonShape, ambientColor = Liquid.Gold, spotColor = Liquid.GoldDeep)
            .clip(Liquid.ButtonShape)
            .background(if (enabled) Liquid.GoldBrushH else Brush.horizontalGradient(listOf(Liquid.Mist, Liquid.Mist.copy(alpha = 0.8f))))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), Liquid.ButtonShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (enabled) Color.White else Liquid.InkSoft, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = if (enabled) Color.White else Liquid.InkSoft,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Secondary action: clear glass with a thin gold edge. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .liquidClickable(enabled = enabled, onClick = onClick)
            .clip(Liquid.ButtonShape)
            .background(Liquid.Frost.copy(alpha = 0.55f))
            .border(BorderStroke(1.dp, Liquid.Gold.copy(alpha = if (enabled) 0.6f else 0.2f)), Liquid.ButtonShape)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Liquid.GoldDeep, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = if (enabled) Liquid.GoldDeep else Liquid.InkSoft, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Scale-down while pressed, driven by an existing interaction source. */
@Composable
private fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Translucent gold used by glass buttons. */
private val glassGold get() = Brush.verticalGradient(listOf(Liquid.Gold.copy(alpha = 0.80f), Liquid.GoldSoft.copy(alpha = 0.90f), Liquid.GoldDeep.copy(alpha = 0.92f)))

/** The bright, glowing rim around a glass button. */
private val glassRim get() = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.25f), Liquid.GoldPale.copy(alpha = 0.9f), Color.White.copy(alpha = 0.7f)))

/** A round frosted-glass button for icons (back, favorite, notifications, share). */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .padding(horizontal = 3.dp)
            .size(42.dp)
            .pressScale(source)
            .glass(androidx.compose.foundation.shape.CircleShape, elevation = 8.dp)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Liquid.GoldDeep) { content() }
    }
}

/**
 * Drop-in for Material's Button: liquid-gold gradient with a glow and the press effect.
 * Passing [colors] (e.g. a red "delete") keeps Material's flat look in those colors.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Liquid.ButtonShape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = if (colors != null) {
        modifier.pressScale(source)
    } else {
        // Tinted glass: translucent gold, a bright highlight across the top and a glowing rim.
        modifier
            .pressScale(source)
            .shadow(if (enabled) 14.dp else 0.dp, shape, ambientColor = Liquid.Gold.copy(alpha = 0.45f), spotColor = Liquid.GoldDeep.copy(alpha = 0.40f))
            .clip(shape)
            .background(if (enabled) glassGold else Brush.horizontalGradient(listOf(Liquid.Mist, Liquid.Mist.copy(alpha = 0.8f))))
            .drawBehind {
                if (enabled) {
                    val h = size.height * 0.55f
                    drawRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f), Color.Transparent), endY = h),
                        size = androidx.compose.ui.geometry.Size(size.width, h),
                    )
                }
            }
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = look,
        enabled = enabled,
        shape = shape,
        colors = colors ?: ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Liquid.InkSoft,
        ),
        elevation = elevation ?: if (colors == null) ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp) else ButtonDefaults.buttonElevation(),
        border = border ?: if (colors == null) BorderStroke(1.5.dp, glassRim) else null,
        contentPadding = contentPadding,
        interactionSource = source,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                shadow = if (colors == null) androidx.compose.ui.graphics.Shadow(Color(0x66000000), Offset(0f, 2f), 4f) else null,
            ),
        ) { content() }
    }
}

/** Drop-in for Material's OutlinedButton: clear glass with a gold edge and the press effect. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Liquid.ButtonShape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .pressScale(source)
            .then(if (colors == null && enabled) Modifier.shadow(8.dp, shape, ambientColor = Liquid.Gold.copy(alpha = 0.25f), spotColor = Liquid.GoldDeep.copy(alpha = 0.2f)) else Modifier),
        enabled = enabled,
        shape = shape,
        colors = colors ?: ButtonDefaults.outlinedButtonColors(containerColor = Liquid.Frost.copy(alpha = 0.6f), contentColor = Liquid.GoldDeep),
        elevation = elevation,
        border = border ?: if (enabled) BorderStroke(1.5.dp, glassRim) else BorderStroke(1.dp, Liquid.Gold.copy(alpha = 0.2f)),
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

/** Glass input colors: concave translucent fill, gold glow on focus. */
@Composable
fun glassFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Liquid.Frost.copy(alpha = 0.62f),
    unfocusedContainerColor = Liquid.Frost.copy(alpha = 0.50f),
    disabledContainerColor = Liquid.Frost.copy(alpha = 0.35f),
    errorContainerColor = Liquid.Frost.copy(alpha = 0.55f),
    focusedBorderColor = Liquid.Gold,
    unfocusedBorderColor = Liquid.Gold.copy(alpha = 0.28f),
    focusedLabelColor = Liquid.GoldDeep,
    cursorColor = Liquid.GoldDeep,
    focusedLeadingIconColor = Liquid.GoldDeep,
    focusedTrailingIconColor = Liquid.GoldDeep,
)

/**
 * Drop-in for Material's OutlinedTextField (same parameters, same order) with the glass
 * look. Screens import this one instead of androidx.compose.material3.OutlinedTextField.
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = androidx.compose.material3.LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = Liquid.FieldShape,
    colors: TextFieldColors = glassFieldColors(),
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
    )
}

// ----------------------------------------------------------------- bars ---

/** A top bar floating as a rounded glass pill below the status bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), elevation = 10.dp)) {
            TopAppBar(
                title = title,
                navigationIcon = navigationIcon,
                actions = actions,
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = Liquid.Ink,
                    navigationIconContentColor = Liquid.GoldDeep,
                    actionIconContentColor = Liquid.GoldDeep,
                ),
            )
        }
    }
}

/** A bottom navigation bar floating as a glass pill above the system bar. */
@Composable
fun FloatingGlassBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(66.dp).glass(RoundedCornerShape(33.dp), elevation = 18.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            content = content,
        )
    }
}

/** One item of [FloatingGlassBar]: the active one sits on a liquid-gold pill. */
@Composable
fun RowScope.GlassNavItem(selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String, badge: Int = 0) {
    Box(Modifier.weight(1f).fillMaxSize().liquidClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .then(
                    if (selected) {
                        Modifier
                            .glass(RoundedCornerShape(22.dp), elevation = 8.dp)
                            .background(Liquid.Gold.copy(alpha = 0.16f))
                    } else {
                        Modifier.clip(RoundedCornerShape(22.dp))
                    },
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val pop by animateFloatAsState(if (selected) 1.16f else 1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow), label = "navPop")
            Box {
                Icon(icon, contentDescription = label, tint = if (selected) Liquid.GoldDeep else Liquid.InkSoft, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = pop; scaleY = pop })
                if (badge > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(16.dp).clip(RoundedCornerShape(8.dp)).background(Liquid.Danger),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                color = if (selected) Liquid.GoldDeep else Liquid.InkSoft,
                maxLines = 1,
            )
        }
    }
}

/** Gold badge for prices and highlights. */
@Composable
fun GoldBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(Liquid.GoldBrushH).padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1) }
}

// ---------------------------------------------------------- transitions ---

/**
 * Screen changes like iOS: the new screen slides in over the old one, which drifts away a
 * third of the way and dims; going back reverses it. Switching between the main tabs only
 * cross-fades. Everything eases out with the same soft curve.
 */
class IosTransitions(private val tabs: Set<String> = emptySet()) {
    private val ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() =
        initialState.destination.route in tabs && targetState.destination.route in tabs

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (betweenTabs()) fadeIn(tween(220)) else slideInHorizontally(tween(MS, easing = ease)) { -it } + fadeIn(tween(MS / 2))
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (betweenTabs()) fadeOut(tween(160)) else slideOutHorizontally(tween(MS, easing = ease)) { it / 3 } + fadeOut(tween(MS, easing = ease), targetAlpha = 0.65f)
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (betweenTabs()) fadeIn(tween(220)) else slideInHorizontally(tween(MS, easing = ease)) { it / 3 } + fadeIn(tween(MS, easing = ease), initialAlpha = 0.65f)
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (betweenTabs()) fadeOut(tween(160)) else slideOutHorizontally(tween(MS, easing = ease)) { -it } + fadeOut(tween(MS / 2, delayMillis = MS / 2))
    }

    private companion object {
        const val MS = 420
    }
}

/** The transitions for apps without a tab bar. */
object LiquidTransitions {
    private val ios = IosTransitions()
    val enter get() = ios.enter
    val exit get() = ios.exit
    val popEnter get() = ios.popEnter
    val popExit get() = ios.popExit
}
