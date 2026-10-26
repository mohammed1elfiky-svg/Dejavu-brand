package com.dejavu.customer.ui.product

import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.dejavu.core.model.Numerals
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.network.normalizeBaseUrl
import com.dejavu.core.ui.ProductImage

/** One page of the product gallery: a photo or the product video. */
sealed interface MediaItem {
    data class Image(val url: String?) : MediaItem
    data class Video(val url: String, val poster: String?) : MediaItem
}

/**
 * [url] as an absolute address: server-relative paths ("/uploads/x.mp4", "api/v1/files/x")
 * are put under the server the app talks to ([baseUrl], as kept in the settings).
 */
fun absoluteMediaUrl(url: String, baseUrl: String): String {
    val u = url.trim()
    if (u.startsWith("http://") || u.startsWith("https://")) return u
    val origin = normalizeBaseUrl(baseUrl).substringBefore("/api/v1").trimEnd('/')
    return "$origin/${u.trimStart('/')}"
}

/** The photos of [color], then the product video when there is one. Never empty. */
fun productMedia(p: StoreProduct, color: String?, baseUrl: String): List<MediaItem> {
    val images: List<MediaItem> = p.imagesFor(color).map { MediaItem.Image(it.url) }
    val video = p.videoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
        MediaItem.Video(absoluteMediaUrl(it, baseUrl), p.imagesFor(color).firstOrNull()?.url)
    }
    val all = images + listOfNotNull(video)
    return all.ifEmpty { listOf(MediaItem.Image(null)) }
}

/** The video page in the small gallery: its poster with a play button. */
@Composable
fun VideoPoster(poster: String?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        ProductImage(poster, Modifier.fillMaxSize())
        Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "تشغيل الفيديو", tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

/** Full-screen gallery: swipe between photos (pinch or double-tap to zoom) and the video. */
@Composable
fun MediaViewer(items: List<MediaItem>, startIndex: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var zoomed by remember { mutableStateOf(false) }
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { page ->
                val active = pager.currentPage == page
                when (val item = items[page]) {
                    is MediaItem.Image -> ZoomableImage(item.url, active = active, onZoomChange = { if (active) zoomed = it })
                    is MediaItem.Video ->
                        if (active) VideoPlayer(item.url) else VideoPoster(item.poster, Modifier.fillMaxSize())
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            ) { Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = Color.White) }
            if (items.size > 1) {
                Text(
                    Numerals.of("${pager.currentPage + 1} / ${items.size}"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(url: String?, active: Boolean, onZoomChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val zoomChanged by rememberUpdatedState(onZoomChange)

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    // Leaving the page resets the zoom.
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale > 1.01f) { zoomChanged(scale > 1.01f) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        val target = 2.5f
                        val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                        offset = clamp((center - tap) * (target - 1f), target)
                        scale = target
                    }
                })
            }
            .pointerInput(Unit) {
                // Pinch to zoom; one-finger drags pan only while zoomed (else the pager swipes).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1.01f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (newScale > 1.01f) clamp(offset + event.calculatePan(), newScale) else Offset.Zero
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}

/** Plays [url] with the system player controls (tap the video to show them). */
@Composable
private fun VideoPlayer(url: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, _, _ ->
                        Toast.makeText(ctx, "تعذّر تشغيل الفيديو", Toast.LENGTH_SHORT).show()
                        true
                    }
                    setVideoURI(Uri.parse(url))
                }
            },
            onRelease = { it.stopPlayback() },
        )
    }
}
