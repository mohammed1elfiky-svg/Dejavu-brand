package com.dejavu.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dejavu.core.ui.theme.Button

/**
 * A soft light band sweeping across a placeholder while its content loads. Put it on any
 * box-shaped placeholder: `Box(Modifier.size(80.dp).shimmer())`.
 */
fun Modifier.shimmer(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val glow = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-x",
    )
    clip(shape).drawWithContent {
        val w = size.width.coerceAtLeast(1f)
        drawRect(base)
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, glow, Color.Transparent),
                start = Offset(w * x, 0f),
                end = Offset(w * (x + 0.6f), size.height),
            ),
        )
    }
}

/** A grey line of text. */
@Composable
fun ShimmerLine(width: androidx.compose.ui.unit.Dp? = null, height: androidx.compose.ui.unit.Dp = 12.dp, modifier: Modifier = Modifier) {
    Box((if (width != null) modifier.size(width, height) else modifier.fillMaxWidth().height(height)).shimmer(RoundedCornerShape(6.dp)))
}

/** A placeholder row shaped like a list item: picture, two lines of text and a trailing value. */
@Composable
fun ShimmerRow(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp).shimmer())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerLine(height = 14.dp)
            ShimmerLine(width = 120.dp, height = 10.dp)
        }
        ShimmerLine(width = 48.dp, height = 14.dp)
    }
}

/** The loading state of a list screen: [rows] placeholder rows. */
@Composable
fun ShimmerList(rows: Int = 8, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) { repeat(rows) { ShimmerRow() } }
}

/** A placeholder shaped like a product card, for grids. */
@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(180.dp).shimmer(RoundedCornerShape(20.dp)))
        ShimmerLine(height = 12.dp)
        ShimmerLine(width = 70.dp, height = 12.dp)
    }
}

/** A round avatar placeholder. */
@Composable
fun ShimmerAvatar(size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(Modifier.size(size).shimmer(CircleShape))
}

/** No connection: a calm message and a retry button, instead of an error dump. */
@Composable
fun OfflineState(onRetry: () -> Unit, modifier: Modifier = Modifier, message: String = "تحقق من اتصالك بالإنترنت ثم حاول مرة أخرى") {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📡", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("لا يوجد اتصال", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("إعادة المحاولة") }
    }
}

/** The failure text that means "no connection" (see ApiException.NETWORK). */
fun isOfflineMessage(message: String): Boolean = message.contains("تعذّر الاتصال") || message.contains("الإنترنت")
