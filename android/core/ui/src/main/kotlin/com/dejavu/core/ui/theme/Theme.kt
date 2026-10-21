package com.dejavu.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Aubergine = Color(0xFF2B1B2F)
val Gold = Color(0xFFB08D57)
val Cream = Color(0xFFFBF8F4)
val Sand = Color(0xFFF1ECE6)
val Success = Color(0xFF2E7D32)
val Warning = Color(0xFFB26A00)

private val Light = lightColorScheme(
    primary = Aubergine,
    onPrimary = Color.White,
    secondary = Gold,
    onSecondary = Color.White,
    tertiary = Gold,
    background = Cream,
    onBackground = Color(0xFF1D1A1F),
    surface = Color.White,
    onSurface = Color(0xFF1D1A1F),
    surfaceVariant = Sand,
    onSurfaceVariant = Color(0xFF5E5660),
    outline = Color(0xFFD6CEC7),
)

private val Dark = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF1D1A1F),
    secondary = Gold,
    background = Color(0xFF141116),
    surface = Color(0xFF1D1A1F),
    surfaceVariant = Color(0xFF2A2530),
    onSurfaceVariant = Color(0xFFCBC2CC),
)

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val PriceStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)

@Composable
fun DejavuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}
