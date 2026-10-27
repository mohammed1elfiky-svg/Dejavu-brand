package com.dejavu.core.ui.theme

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * A screen of the app, iOS style: solid (its own pearl backdrop, so the screen under it never
 * shows through while sliding), a soft shadow along the edge while it slides over another
 * screen, and dimmed while it is pushed aside by the screen coming on top.
 */
@Composable
fun IosPage(content: @Composable () -> Unit) {
    // How far this page sits from its resting place, as a fraction of its width.
    var shift by remember { mutableFloatStateOf(0f) }
    LiquidBackdrop {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { c ->
                    val w = c.size.width
                    if (w > 0) shift = c.positionInRoot().x / w
                },
        ) { content() }
    }
    // Drawn on top of the page: the dimming and the edge shadow.
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // Pushed aside to the right (under the new screen): dim, like iOS.
                if (shift > 0.001f) drawRect(Color.Black.copy(alpha = 0.16f * (shift / 0.34f).coerceIn(0f, 1f)))
                // Sliding over another screen from the left: shadow just past the right edge.
                if (shift < -0.001f) {
                    val w = 18.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.14f), Color.Transparent), startX = size.width, endX = size.width + w),
                        topLeft = Offset(size.width, 0f),
                        size = Size(w, size.height),
                    )
                }
            },
    )
}

/** Like [composable], but the screen is an [IosPage]. */
fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments, deepLinks) { entry -> IosPage { content(entry) } }
}
