package com.dejavu.customer.ui.auth

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

/**
 * A country a shopper can sign up from: its ISO code, dialling code, Arabic name and a sample
 * number. Yemen first (the default), then Saudi Arabia and Egypt, then every other country.
 */
data class Country(val iso: String, val dial: String, val nameAr: String, val example: String, private val local: Regex? = null) {
    /** The flag emoji made from the ISO code. */
    val flag: String get() = iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    /** The digits typed, without a leading 0 (0512… and 512… are the same Saudi number). */
    fun national(typed: String) = typed.filter { it.isDigit() }.trimStart('0')

    /** Yemen, Saudi Arabia and Egypt are checked exactly; other countries by length. */
    fun isValid(typed: String) = local?.matches(national(typed)) ?: (national(typed).length in 6..12)

    /** The number as the server takes it, e.g. +967771234567. */
    fun full(typed: String) = dial + national(typed)

    companion object {
        val YE = Country("YE", "+967", "اليمن", "771234567", Regex("^7[01378]\\d{7}$"))
        val SA = Country("SA", "+966", "السعودية", "512345678", Regex("^5\\d{8}$"))
        val EG = Country("EG", "+20", "مصر", "1012345678", Regex("^1[0125]\\d{8}$"))

        val entries: List<Country> = listOf(
            YE,
            SA,
            EG,
        Country("AE", "+971", "الإمارات", "501234567"),
        Country("KW", "+965", "الكويت", "50012345"),
        Country("QA", "+974", "قطر", "33123456"),
        Country("BH", "+973", "البحرين", "36001234"),
        Country("OM", "+968", "عُمان", "92123456"),
        Country("JO", "+962", "الأردن", "791234567"),
        Country("IQ", "+964", "العراق", "7912345678"),
        Country("SY", "+963", "سوريا", "944567890"),
        Country("LB", "+961", "لبنان", "71123456"),
        Country("PS", "+970", "فلسطين", "599123456"),
        Country("SD", "+249", "السودان", "911231234"),
        Country("SS", "+211", "جنوب السودان", "977123456"),
        Country("LY", "+218", "ليبيا", "912345678"),
        Country("TN", "+216", "تونس", "20123456"),
        Country("DZ", "+213", "الجزائر", "551234567"),
        Country("MA", "+212", "المغرب", "650123456"),
        Country("MR", "+222", "موريتانيا", "22123456"),
        Country("SO", "+252", "الصومال", "712345678"),
        Country("DJ", "+253", "جيبوتي", "77831001"),
        Country("KM", "+269", "جزر القمر", "3212345"),
        Country("ER", "+291", "إريتريا", "7123456"),
        Country("ET", "+251", "إثيوبيا", "911234567"),
        Country("KE", "+254", "كينيا", "712123456"),
        Country("TZ", "+255", "تنزانيا", "621234567"),
        Country("UG", "+256", "أوغندا", "712345678"),
        Country("NG", "+234", "نيجيريا", "8021234567"),
        Country("GH", "+233", "غانا", "231234567"),
        Country("SN", "+221", "السنغال", "701234567"),
        Country("ZA", "+27", "جنوب أفريقيا", "711234567"),
        Country("TR", "+90", "تركيا", "5012345678"),
        Country("IR", "+98", "إيران", "9123456789"),
        Country("AF", "+93", "أفغانستان", "701234567"),
        Country("PK", "+92", "باكستان", "3012345678"),
        Country("IN", "+91", "الهند", "8123456789"),
        Country("BD", "+880", "بنغلاديش", "1812345678"),
        Country("LK", "+94", "سريلانكا", "712345678"),
        Country("NP", "+977", "نيبال", "9841234567"),
        Country("MV", "+960", "المالديف", "7712345"),
        Country("MY", "+60", "ماليزيا", "123456789"),
        Country("ID", "+62", "إندونيسيا", "812345678"),
        Country("PH", "+63", "الفلبين", "9051234567"),
        Country("TH", "+66", "تايلاند", "812345678"),
        Country("VN", "+84", "فيتنام", "912345678"),
        Country("SG", "+65", "سنغافورة", "81234567"),
        Country("CN", "+86", "الصين", "13123456789"),
        Country("HK", "+852", "هونغ كونغ", "51234567"),
        Country("TW", "+886", "تايوان", "912345678"),
        Country("JP", "+81", "اليابان", "9012345678"),
        Country("KR", "+82", "كوريا الجنوبية", "1020000000"),
        Country("KZ", "+7", "كازاخستان", "7710009998"),
        Country("UZ", "+998", "أوزبكستان", "912345678"),
        Country("AZ", "+994", "أذربيجان", "401234567"),
        Country("GE", "+995", "جورجيا", "555123456"),
        Country("AM", "+374", "أرمينيا", "77123456"),
        Country("RU", "+7", "روسيا", "9123456789"),
        Country("UA", "+380", "أوكرانيا", "501234567"),
        Country("GB", "+44", "بريطانيا", "7400123456"),
        Country("IE", "+353", "أيرلندا", "850123456"),
        Country("FR", "+33", "فرنسا", "612345678"),
        Country("DE", "+49", "ألمانيا", "15123456789"),
        Country("NL", "+31", "هولندا", "612345678"),
        Country("BE", "+32", "بلجيكا", "470123456"),
        Country("LU", "+352", "لوكسمبورغ", "628123456"),
        Country("CH", "+41", "سويسرا", "781234567"),
        Country("AT", "+43", "النمسا", "664123456"),
        Country("IT", "+39", "إيطاليا", "3123456789"),
        Country("ES", "+34", "إسبانيا", "612345678"),
        Country("PT", "+351", "البرتغال", "912345678"),
        Country("GR", "+30", "اليونان", "6912345678"),
        Country("CY", "+357", "قبرص", "96123456"),
        Country("MT", "+356", "مالطا", "96961234"),
        Country("SE", "+46", "السويد", "701234567"),
        Country("NO", "+47", "النرويج", "40612345"),
        Country("DK", "+45", "الدنمارك", "32123456"),
        Country("FI", "+358", "فنلندا", "412345678"),
        Country("IS", "+354", "آيسلندا", "6111234"),
        Country("PL", "+48", "بولندا", "512345678"),
        Country("CZ", "+420", "التشيك", "601123456"),
        Country("SK", "+421", "سلوفاكيا", "912123456"),
        Country("HU", "+36", "المجر", "201234567"),
        Country("RO", "+40", "رومانيا", "712034567"),
        Country("BG", "+359", "بلغاريا", "48123456"),
        Country("RS", "+381", "صربيا", "601234567"),
        Country("HR", "+385", "كرواتيا", "921234567"),
        Country("BA", "+387", "البوسنة والهرسك", "61123456"),
        Country("AL", "+355", "ألبانيا", "672123456"),
        Country("XK", "+383", "كوسوفو", "43201234"),
        Country("MK", "+389", "مقدونيا الشمالية", "72345678"),
        Country("SI", "+386", "سلوفينيا", "31234567"),
        Country("US", "+1", "أمريكا", "2015550123"),
        Country("CA", "+1", "كندا", "5062345678"),
        Country("MX", "+52", "المكسيك", "2221234567"),
        Country("BR", "+55", "البرازيل", "11961234567"),
        Country("AR", "+54", "الأرجنتين", "91123456789"),
        Country("CL", "+56", "تشيلي", "221234567"),
        Country("CO", "+57", "كولومبيا", "3211234567"),
        Country("PE", "+51", "بيرو", "912345678"),
        Country("VE", "+58", "فنزويلا", "4121234567"),
        Country("AU", "+61", "أستراليا", "412345678"),
        Country("NZ", "+64", "نيوزيلندا", "211234567"),
        )

        /** Splits a saved number ("+966512…", "0512…", "771…") into its country and the rest. */
        fun split(saved: String): Pair<Country, String> {
            val p = saved.filter { it.isDigit() || it == '+' }.let { if (it.startsWith("00")) "+" + it.drop(2) else it }
            if (p.startsWith("+")) {
                // Longest dialling code first (+971 before +97…, +1 last).
                entries.sortedByDescending { it.dial.length }.firstOrNull { p.startsWith(it.dial) }?.let { return it to p.removePrefix(it.dial) }
            }
            listOf(YE, SA, EG).firstOrNull { p.startsWith(it.dial.removePrefix("+")) && p.length > 10 }?.let { return it to p.removePrefix(it.dial.removePrefix("+")) }
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
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Box {
                OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.padding(top = 8.dp)) {
                    Text("${country.flag} ${country.dial}")
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "اختر الدولة")
                }
                if (open) {
                    CountryPicker(onPick = { c ->
                        open = false
                        onCountry(c)
                    }, onDismiss = { open = false })
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

/** Every country, searchable by Arabic name or dialling code. */
@Composable
private fun CountryPicker(onPick: (Country) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query) {
        val q = query.trim().removePrefix("+")
        if (q.isEmpty()) Country.entries else Country.entries.filter { it.nameAr.contains(q) || it.dial.removePrefix("+").startsWith(q) || it.iso.equals(q, ignoreCase = true) }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختر الدولة") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(30) },
                    placeholder = { Text("ابحث باسم الدولة أو المفتاح") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 380.dp)) {
                    items(shown.size) { i ->
                        val c = shown[i]
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            Text(
                                "${c.flag}  ${c.nameAr}  ${c.dial}",
                                modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}
