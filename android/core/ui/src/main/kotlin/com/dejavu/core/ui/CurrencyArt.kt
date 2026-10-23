package com.dejavu.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * The picture shown next to a currency: the photo uploaded for it, or a drawn note / coin —
 * a 500 riyal note (SAR), the new 100 rial note (YEQ), the old 100 rial coin (YER) and a
 * 100 dollar note (USD). Other currencies get a plain note with their code.
 *
 * Notes are drawn 2:1; [height] sets the size (a coin is a circle of that height).
 */
@Composable
fun CurrencyArt(code: String, imageUrl: String? = null, height: Dp = 22.dp, modifier: Modifier = Modifier) {
    val art = artOf(code)
    val width = if (art.coin) height else height * 2
    if (!imageUrl.isNullOrBlank()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = code,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(width, height).clip(if (art.coin) RoundedCornerShape(50) else RoundedCornerShape(height * 0.15f)),
        )
        return
    }
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { if (art.coin) drawCoin(art) else drawNote(art) }
        Text(
            art.label,
            color = art.ink,
            fontWeight = FontWeight.Bold,
            fontSize = (height.value * if (art.coin) 0.36f else 0.46f).sp,
            maxLines = 1,
        )
    }
}

private data class Art(val label: String, val light: Color, val dark: Color, val ink: Color, val coin: Boolean = false)

private fun artOf(code: String): Art = when (code.uppercase()) {
    // 500 riyal: green-teal note.
    "SAR" -> Art("500", Color(0xFF7CC4A8), Color(0xFF2E7D66), Color(0xFF0E3B30))
    // New (qu'aiti) 100 rial note: violet-red.
    "YEQ" -> Art("100", Color(0xFFE3A1B8), Color(0xFF9C3D63), Color(0xFF4A0F27))
    // Old 100 rial coin: silver.
    "YER" -> Art("100", Color(0xFFF1F1F1), Color(0xFF9E9E9E), Color(0xFF3A3A3A), coin = true)
    // 100 dollar note: grey-green with a blue strip.
    "USD" -> Art("100", Color(0xFFD7E3CF), Color(0xFF7D9472), Color(0xFF223318))
    else -> Art(code.take(3).uppercase(), Color(0xFFE8DCC0), Color(0xFFB8A06A), Color(0xFF3E3215))
}

private fun DrawScope.drawNote(a: Art) {
    val r = CornerRadius(size.height * 0.15f)
    drawRoundRect(Brush.linearGradient(listOf(a.light, a.dark), Offset.Zero, Offset(size.width, size.height)), cornerRadius = r)
    // Inner frame and the round seal of a banknote.
    val inset = size.height * 0.12f
    drawRoundRect(
        a.ink.copy(alpha = 0.35f),
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        cornerRadius = CornerRadius(size.height * 0.08f),
        style = Stroke(width = size.height * 0.05f),
    )
    drawCircle(a.ink.copy(alpha = 0.18f), radius = size.height * 0.28f, center = Offset(size.width * 0.2f, size.height / 2))
    drawCircle(a.ink.copy(alpha = 0.18f), radius = size.height * 0.28f, center = Offset(size.width * 0.8f, size.height / 2))
    if (a.label == "100" && a.light == Color(0xFFD7E3CF)) {
        // The blue security ribbon of the 100 dollar note.
        drawRect(Color(0xFF4F7BD9).copy(alpha = 0.7f), topLeft = Offset(size.width * 0.62f, 0f), size = Size(size.width * 0.05f, size.height))
    }
}

private fun DrawScope.drawCoin(a: Art) {
    val c = Offset(size.width / 2, size.height / 2)
    val radius = size.minDimension / 2
    drawCircle(Brush.radialGradient(listOf(a.light, a.dark), center = Offset(c.x - radius * 0.3f, c.y - radius * 0.3f), radius = radius * 1.6f), radius = radius, center = c)
    drawCircle(a.ink.copy(alpha = 0.35f), radius = radius * 0.82f, center = c, style = Stroke(width = radius * 0.08f))
}
