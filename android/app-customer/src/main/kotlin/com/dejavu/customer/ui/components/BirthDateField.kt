@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dejavu.core.model.Numerals
import com.dejavu.core.ui.theme.OutlinedButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A birth date picked from a calendar (no typing). [value] is yyyy-mm-dd or empty; [onChange]
 * gets the new one. Dates run from 1940 to 8 years ago.
 */
@Composable
fun BirthDateField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, error: String? = null, label: String = "تاريخ الميلاد") {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (value.isBlank()) label else "$label: ${Numerals.of(value.take(10))}")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (open) {
        val last = LocalDate.now().minusYears(8)
        val initial = runCatching { LocalDate.parse(value.take(10)) }.getOrNull() ?: LocalDate.of(2000, 1, 1)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            yearRange = 1940..last.year,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { ms -> onChange(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                        open = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) { Text("تأكيد") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("إلغاء") } },
        ) { DatePicker(state = state) }
    }
}
