package com.dejavu.customer.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.StoreExtrasRepository
import com.dejavu.core.model.StoreAbout
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.theme.GlassButton
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.LiquidGoldButton
import com.dejavu.core.ui.toLoad
import com.dejavu.customer.ui.appViewModel
import com.dejavu.customer.ui.components.GoldIconCircle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class AboutViewModel(private val extras: StoreExtrasRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<StoreAbout>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = extras.about().toLoad() }
    }
}

/** "عن المتجر": address and map, hours, return policy, phones and social links. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val vm = appViewModel { AboutViewModel(it.extras) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { if (vm.state.value !is Load.Ready) vm.load() }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("عن المتجر", onBack) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { about ->
            AboutContent(about, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutContent(about: StoreAbout, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth(), elevation = 14.dp, contentPadding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoldIconCircle(Icons.Filled.Info, size = 52.dp)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(about.name.ifBlank { "Dejavu" }, style = MaterialTheme.typography.headlineSmall, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold)
            }
            if (about.about.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(about.about.trim(), style = MaterialTheme.typography.bodyMedium, color = Liquid.Ink)
            }
        }

        val hasMap = about.mapUrl.isNotBlank() || (about.latitude != null && about.longitude != null)
        if (about.address.isNotBlank() || hasMap) {
            Section(Icons.Filled.LocationOn, "العنوان") {
                if (about.address.isNotBlank()) Text(about.address.trim(), style = MaterialTheme.typography.bodyMedium)
                if (hasMap) {
                    Spacer(Modifier.height(10.dp))
                    GlassButton("فتح في الخرائط", onClick = { openMap(context, about) }, icon = Icons.Filled.LocationOn, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (about.hours.isNotBlank()) {
            Section(Icons.Filled.DateRange, "مواعيد العمل") {
                Text(about.hours.trim(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (about.returnPolicy.isNotBlank()) {
            Section(Icons.Filled.Refresh, "سياسة الاستبدال والاسترجاع") {
                Text(about.returnPolicy.trim(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        val phones = about.phones.map { it.trim() }.filter { it.isNotEmpty() }
        if (phones.isNotEmpty() || about.whatsapp.isNotBlank()) {
            Section(Icons.Filled.Phone, "تواصل معنا") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    phones.forEach { phone ->
                        GlassButton(phone, onClick = { dial(context, phone) }, icon = Icons.Filled.Phone, modifier = Modifier.fillMaxWidth())
                    }
                    if (about.whatsapp.isNotBlank()) {
                        LiquidGoldButton("واتساب", onClick = { openWhatsApp(context, about.whatsapp) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        val socials = listOf(
            "إنستغرام" to socialUrl(about.instagram, "https://instagram.com/"),
            "فيسبوك" to socialUrl(about.facebook, "https://facebook.com/"),
            "تيك توك" to socialUrl(about.tiktok, "https://www.tiktok.com/@"),
        ).filter { it.second != null }
        if (socials.isNotEmpty()) {
            Section(Icons.Filled.Share, "تابعنا") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    socials.forEach { (label, url) ->
                        GlassButton(label, onClick = { openLink(context, url!!) })
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GoldIconCircle(icon, size = 34.dp)
            Spacer(Modifier.padding(horizontal = 5.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** A full link as-is; a bare handle ("@dejavu" or "dejavu") on the network's site. */
private fun socialUrl(value: String, base: String): String? {
    val v = value.trim()
    if (v.isEmpty()) return null
    if (v.startsWith("http://") || v.startsWith("https://")) return v
    if (v.contains('.') && v.contains('/')) return "https://$v"
    return base + v.removePrefix("@")
}

private fun openMap(context: Context, about: StoreAbout) {
    val lat = about.latitude
    val lng = about.longitude
    val uri = when {
        about.mapUrl.isNotBlank() -> Uri.parse(about.mapUrl.trim())
        lat != null && lng != null -> {
            val point = String.format(Locale.US, "%f,%f", lat, lng)
            Uri.parse("geo:$point?q=$point(${Uri.encode(about.name)})")
        }
        else -> return
    }
    start(context, Intent(Intent.ACTION_VIEW, uri))
}

private fun dial(context: Context, phone: String) {
    start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })))
}

private fun openWhatsApp(context: Context, number: String) {
    val digits = number.filter { it.isDigit() }
    if (digits.isEmpty()) return
    openLink(context, "https://wa.me/$digits")
}

private fun openLink(context: Context, url: String) = start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

private fun start(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No app on the phone handles it (e.g. no maps app): nothing to open.
    }
}
