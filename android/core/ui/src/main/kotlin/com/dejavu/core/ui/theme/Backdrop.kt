package com.dejavu.core.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * What floating glass blurs: a picture of the content behind it. The content marks itself
 * with [backdropSource]; a floating element asks for the blur with [backdropBlur] and must
 * sit beside that content, not inside it. Blur needs Android 12 (RenderEffect); on older
 * phones [supported] is false and the glass just stays more opaque.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Where the source sits in the window, so a floating element knows which part to show. */
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Marks the content that floating glass blurs. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier =
    this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            if (backdrop.supported) {
                backdrop.layer.record { this@drawWithContent.drawContent() }
                drawLayer(backdrop.layer)
            } else {
                drawContent()
            }
        }

/**
 * Frosted glass: the part of [backdrop] behind this element, blurred by [radius], clipped to
 * [shape] and drawn under the element's own (translucent) glass fill.
 */
fun Modifier.backdropBlur(backdrop: Backdrop, shape: Shape, radius: Dp = 22.dp): Modifier = composed {
    if (!backdrop.supported) return@composed this
    val blurred = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawWithContent {
            val r = radius.toPx()
            blurred.renderEffect = BlurEffect(r, r, TileMode.Clamp)
            blurred.record(density = this, layoutDirection = layoutDirection, size = IntSize(size.width.toInt(), size.height.toInt())) {
                // The blur samples a margin outside the element, so edges do not fade out.
                translate(-(position.x - backdrop.origin.x), -(position.y - backdrop.origin.y)) {
                    drawLayer(backdrop.layer)
                }
            }
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply {
                when (outline) {
                    is Outline.Rectangle -> addRect(outline.rect)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Generic -> addPath(outline.path)
                }
            }
            clipPath(path) { drawLayer(blurred) }
            drawContent()
        }
}
