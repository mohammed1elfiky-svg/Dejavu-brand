package com.dejavu.admin.ui.more

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.ui.ThemeMode
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Liquid
import java.time.Instant
import kotlin.math.abs

private val textSizes = listOf(0.9f to "صغير", 1f to "عادي", 1.15f to "كبير", 1.3f to "كبير جداً")

/** Theme (light / dark / system), text size and Arabic or Latin digits. */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val prefs = adminContainer().uiPrefs
    val context = LocalContext.current
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val fontScale by prefs.fontScale.collectAsStateWithLifecycle()
    val arabicDigits by prefs.arabicDigits.collectAsStateWithLifecycle()
    // Formatted again whenever the digits choice changes.
    val sampleAmount = remember(arabicDigits) { Money.format("1250.5") }
    val sampleDate = remember(arabicDigits) { formatDate(Instant.now().toString()) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("المظهر", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("الوضع", "الوضع الداكن أريح للعين في الإضاءة الخافتة.") {
                val modes = ThemeMode.entries
                ChoiceRow(
                    options = modes.map { it.label },
                    selected = modes.indexOf(theme),
                    onSelect = { prefs.setTheme(modes[it]) },
                )
            }

            Section("حجم النص", "يُضاف إلى حجم الخط المختار في إعدادات الجهاز.") {
                ChoiceRow(
                    options = textSizes.map { it.second },
                    selected = textSizes.indexOfFirst { abs(it.first - fontScale) < 0.01f },
                    onSelect = { prefs.setFontScale(textSizes[it].first) },
                    sample = { i -> Text("أ", fontSize = (18 * textSizes[i].first).sp, fontWeight = FontWeight.Bold) },
                )
                PreviewBox {
                    Text("هكذا تظهر النصوص في التطبيق.", style = MaterialTheme.typography.bodyLarge, color = Lux.Ink)
                    Text("وهذا نص ثانوي أصغر للتفاصيل.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
            }

            Section("الأرقام", "شكل الأرقام في المبالغ والتواريخ والكميات.") {
                ChoiceRow(
                    options = listOf("123", "١٢٣"),
                    selected = if (arabicDigits) 1 else 0,
                    onSelect = { i ->
                        val on = i == 1
                        if (on != arabicDigits) {
                            prefs.setArabicDigits(on)
                            // Every screen formats its numbers once; rebuild them with the new digits.
                            context.findActivity()?.recreate()
                        }
                    },
                )
                PreviewBox {
                    Text("مبلغ: $sampleAmount", style = MaterialTheme.typography.bodyLarge, color = Lux.Ink)
                    Text("تاريخ: $sampleDate", style = MaterialTheme.typography.bodyMedium, color = Lux.InkSoft)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun PreviewBox(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Liquid.Frost.copy(alpha = 0.6f))
            .border(1.dp, Liquid.Gold.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("معاينة", style = MaterialTheme.typography.labelSmall, color = Lux.GoldDeep)
        content()
    }
}

/** Segmented choice: one pill per option, the chosen one in gold. */
@Composable
private fun ChoiceRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    sample: (@Composable (Int) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Liquid.Frost.copy(alpha = 0.5f))
            .border(1.dp, Liquid.Gold.copy(alpha = 0.3f), shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (on) Modifier.background(Lux.GoldBrush) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val color = if (on) Color.White else Lux.Ink
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (sample != null) {
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides color) { sample(i) }
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
