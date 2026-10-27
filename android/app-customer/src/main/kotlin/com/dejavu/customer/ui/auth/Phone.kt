package com.dejavu.customer.ui.auth

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField

/** Countries a shopper can sign up from: Yemen first (the default), then Saudi Arabia. */
enum class Country(val dial: String, val flag: String, val nameAr: String, val example: String, private val local: Regex) {
    YE("+967", "🇾🇪", "اليمن", "771234567", Regex("^7[01378]\\d{7}$")),
    SA("+966", "🇸🇦", "السعودية", "512345678", Regex("^5\\d{8}$")),
    EG("+20", "🇪🇬", "مصر", "1012345678", Regex("^1[0125]\\d{8}$")),
    ;

    /** The digits typed, without a leading 0 (0512… and 512… are the same Saudi number). */
    fun national(typed: String) = typed.filter { it.isDigit() }.trimStart('0')

    fun isValid(typed: String) = local.matches(national(typed))

    /** The number as the server takes it, e.g. +967771234567. */
    fun full(typed: String) = dial + national(typed)

    companion object {
        /** Splits a saved number ("+966512…", "0512…", "771…") into its country and the rest. */
        fun split(saved: String): Pair<Country, String> {
            val p = saved.filter { it.isDigit() || it == '+' }
            entries.firstOrNull { p.startsWith(it.dial) }?.let { return it to p.removePrefix(it.dial) }
            entries.firstOrNull { p.startsWith(it.dial.removePrefix("+")) && p.length > 10 }?.let { return it to p.removePrefix(it.dial.removePrefix("+")) }
            return when {
                p.startsWith("05") || (p.startsWith("5") && p.length == 9) -> SA to p.trimStart('0')
                p.startsWith("01") && p.length == 11 -> EG to p.trimStart('0')
                else -> YE to p
            }
        }
    }
}

/** A wa.me link to [storeNumber] with [text] ready to send. */
fun whatsappLink(storeNumber: String, text: String): String {
    val digits = storeNumber.filter { it.isDigit() }
    val intl = when {
        digits.length == 9 && digits.startsWith("7") -> "967$digits"
        digits.length == 10 && digits.startsWith("05") -> "966${digits.drop(1)}"
        digits.length == 11 && digits.startsWith("01") -> "20${digits.drop(1)}"
        digits.startsWith("00") -> digits.drop(2)
        else -> digits
    }
    return "https://wa.me/$intl?text=${Uri.encode(text)}"
}

/** Mobile number with the country picker (flag and dialing code) beside it. */
@Composable
fun PhoneField(country: Country, number: String, error: String?, onCountry: (Country) -> Unit, onNumber: (String) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    // Numbers read left to right even in the Arabic layout.
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Box {
                OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.padding(top = 8.dp)) {
                    Text("${country.flag} ${country.dial}")
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "اختر الدولة")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    Country.entries.forEach { c ->
                        DropdownMenuItem(
                            text = { Text("${c.flag}  ${c.nameAr}  ${c.dial}") },
                            onClick = {
                                open = false
                                onCountry(c)
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = number,
                onValueChange = { v -> onNumber(v.filter { it.isDigit() }.take(12)) },
                label = { Text("رقم الموبايل") },
                placeholder = { Text(country.example) },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
