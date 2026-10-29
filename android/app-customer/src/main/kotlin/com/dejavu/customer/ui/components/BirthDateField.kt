package com.dejavu.customer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dejavu.core.model.Numerals
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import java.time.LocalDate
import java.time.YearMonth

private val MONTHS = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")

/**
 * A birth date chosen in three steps from lists: the year, then the month, then the day.
 * [value] is yyyy-mm-dd or empty; [onChange] gets the chosen date. Years run from 1940 to 8 years ago.
 */
@Composable
fun BirthDateField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, error: String? = null, label: String = "تاريخ الميلاد") {
    var open by remember { mutableStateOf(false) }
    val shown = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (shown == null) label else "$label: ${Numerals.of("${shown.dayOfMonth}")} ${MONTHS[shown.monthValue - 1]} ${Numerals.of("${shown.year}")}")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (open) BirthDateDialog(initial = shown, onDismiss = { open = false }, onPicked = { onChange(it.toString()); open = false })
}

@Composable
private fun BirthDateDialog(initial: LocalDate?, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val newest = LocalDate.now().year - 8
    val years = remember { (newest downTo 1940).toList() }
    var year by remember { mutableStateOf(initial?.year) }
    var month by remember { mutableStateOf(initial?.monthValue) }
    val step = when {
        year == null -> 1
        month == null -> 2
        else -> 3
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (step) {
                    1 -> "اختر سنة الميلاد"
                    2 -> "${Numerals.of("$year")} · اختر الشهر"
                    else -> "${MONTHS[month!! - 1]} ${Numerals.of("$year")} · اختر اليوم"
                },
            )
        },
        text = {
            when (step) {
                1 -> {
                    // Starts near the 2000s, where most customers are.
                    val state = rememberLazyListState(initialFirstVisibleItemIndex = (newest - 2000).coerceAtLeast(0))
                    LazyColumn(Modifier.heightIn(max = 360.dp), state = state) {
                        items(years) { y ->
                            Text(Numerals.of("$y"), Modifier.fillMaxWidth().clickable { year = y }.padding(vertical = 12.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()
                        }
                    }
                }
                2 -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.heightIn(max = 360.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MONTHS.indices.toList()) { i ->
                        Text(
                            MONTHS[i],
                            Modifier.fillMaxWidth().clickable { month = i + 1 }.padding(vertical = 14.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Liquid.GoldDeep,
                        )
                    }
                }
                else -> {
                    val days = YearMonth.of(year!!, month!!).lengthOfMonth()
                    LazyVerticalGrid(GridCells.Fixed(7), Modifier.heightIn(max = 360.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items((1..days).toList()) { d ->
                            Text(
                                Numerals.of("$d"),
                                Modifier.fillMaxWidth().clickable { onPicked(LocalDate.of(year!!, month!!, d)) }.padding(vertical = 10.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step > 1) TextButton(onClick = { if (step == 3) month = null else year = null }) { Text("رجوع") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
