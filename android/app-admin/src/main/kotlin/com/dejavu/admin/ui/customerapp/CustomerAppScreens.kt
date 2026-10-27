@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.customerapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.CustomerAppRepository
import com.dejavu.core.admin.CustomerUsage
import com.dejavu.core.admin.StoreAboutSettings
import com.dejavu.core.admin.StoreAppSettings
import com.dejavu.core.admin.durationLabel
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ------------------------------------------------------------ entry points ---

/** The store page ("عن المتجر"), sign-in before browsing, and the server addresses. */
@Composable
fun CustomerAppSettingsScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "customer-app-settings") { CustomerAppSettingsViewModel(it.customerApp) }
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.start() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("تطبيق العملاء", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { bundle ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoginCard(bundle.app.requireLogin, busy = SAVE_LOGIN in saving, onChange = vm::saveLogin)
                ServersCard()
                AboutCard(bundle.about, busy = SAVE_ABOUT in saving, onSave = vm::saveAbout)
            }
        }
    }
}

/** The shopping assistant: instructions, welcome, limits, and customers' conversations. */
@Composable
fun AssistantScreen(onBack: () -> Unit, openCustomer: (String) -> Unit) {
    AssistantContent(onBack, openCustomer)
}

/** Notifications / SMS to a group of customers, with a picture and a link; past campaigns. */
@Composable
fun CampaignsScreen(onBack: () -> Unit) {
    CampaignsContent(onBack)
}

/** "سلوك العملاء": time in the app, most viewed products, searches. */
@Composable
fun BehaviorScreen(onBack: () -> Unit, openCustomer: (String) -> Unit, openProduct: (String) -> Unit) {
    BehaviorContent(onBack, openCustomer, openProduct)
}

/** Time this customer spent in the app (for the customer page). */
@Composable
fun CustomerUsageCard(customerId: String) {
    val vm = adminViewModel(key = "usage:$customerId") { CustomerUsageViewModel(it.customerApp, customerId) }
    val loaded by vm.usage.collectAsStateWithLifecycle()
    val u = loaded ?: return
    if (u.sessions == 0) return
    GlassCard(Modifier.fillMaxWidth()) {
        Text("الاستخدام في تطبيق العملاء", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        SummaryRow("إجمالي الوقت", durationLabel(u.seconds))
        SummaryRow("عدد مرات الدخول", "${u.sessions}")
        SummaryRow("آخر 30 يوماً", "${durationLabel(u.last30Days.seconds)} · ${u.last30Days.sessions} مرة")
        if (!u.lastSeen.isNullOrBlank()) SummaryRow("آخر ظهور", formatDateTime(u.lastSeen))
        if (u.mostViewed.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Lux.Hairline)
            Text("أكثر ما شاهده", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
            Text(
                u.mostViewed.take(5).joinToString("، ") { "${it.name} (${it.views})" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// --------------------------------------------------------------- settings ---

private const val SAVE_LOGIN = "login"
private const val SAVE_ABOUT = "about"

data class CustomerAppBundle(val app: StoreAppSettings, val about: StoreAboutSettings)

class CustomerAppSettingsViewModel(private val repo: CustomerAppRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<CustomerAppBundle>>(Load.Loading)
    val state = _state.asStateFlow()

    /** Which cards are being saved. */
    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    val saving = _saving.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch {
            val app = repo.appSettings()
            val about = repo.about()
            _state.value = app.mapCatching { a -> CustomerAppBundle(a, about.getOrThrow()) }.toLoad()
        }
    }

    fun saveLogin(on: Boolean) {
        val current = (_state.value as? Load.Ready)?.value ?: return
        saveCard(SAVE_LOGIN) {
            repo.setAppSettings(current.app.copy(requireLogin = on)).orSay()?.let { saved ->
                update { it.copy(app = saved) }
                say(if (saved.requireLogin) "يجب على العملاء تسجيل الدخول قبل التصفح" else "يمكن للعملاء التصفح بدون تسجيل الدخول")
            }
        }
    }

    fun saveAbout(about: StoreAboutSettings) {
        saveCard(SAVE_ABOUT) {
            repo.setAbout(about).orSay()?.let { saved ->
                update { it.copy(about = saved) }
                say("تم حفظ صفحة عن المتجر")
            }
        }
    }

    private fun saveCard(card: String, block: suspend () -> Unit) {
        if (card in _saving.value) return
        _saving.value = _saving.value + card
        viewModelScope.launch {
            try {
                block()
            } finally {
                _saving.value = _saving.value - card
            }
        }
    }

    private fun update(change: (CustomerAppBundle) -> CustomerAppBundle) {
        val s = (_state.value as? Load.Ready)?.value ?: return
        _state.value = Load.Ready(change(s))
    }
}

@Composable
private fun LoginCard(requireLogin: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("تسجيل الدخول قبل التصفح", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            title = "إلزام العملاء بتسجيل الدخول",
            note = "عند التفعيل لا يرى العميل المنتجات حتى يسجّل الدخول أو ينشئ حساباً.",
            checked = requireLogin,
            enabled = !busy,
            onChange = onChange,
        )
    }
}

@Composable
private fun AboutCard(saved: StoreAboutSettings, busy: Boolean, onSave: (StoreAboutSettings) -> Unit) {
    var name by remember(saved) { mutableStateOf(saved.name) }
    var about by remember(saved) { mutableStateOf(saved.about) }
    var address by remember(saved) { mutableStateOf(saved.address) }
    var mapUrl by remember(saved) { mutableStateOf(saved.mapUrl) }
    var hours by remember(saved) { mutableStateOf(saved.hours) }
    var returnPolicy by remember(saved) { mutableStateOf(saved.returnPolicy) }
    var phones by remember(saved) { mutableStateOf(saved.phones.joinToString("\n")) }
    var whatsapp by remember(saved) { mutableStateOf(saved.whatsapp) }
    var instagram by remember(saved) { mutableStateOf(saved.instagram) }
    var facebook by remember(saved) { mutableStateOf(saved.facebook) }
    var tiktok by remember(saved) { mutableStateOf(saved.tiktok) }

    GlassCard(Modifier.fillMaxWidth()) {
        Text("عن المتجر", style = MaterialTheme.typography.titleMedium)
        Text(
            "تظهر هذه المعلومات للعملاء في صفحة \"عن المتجر\" داخل التطبيق.",
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        FormField(name, { name = it.take(100) }, "اسم المتجر", enabled = !busy)
        FormField(about, { about = it.take(4000) }, "نبذة عن المتجر", singleLine = false, minLines = 4, enabled = !busy)
        FormField(address, { address = it.take(500) }, "العنوان", singleLine = false, minLines = 2, enabled = !busy)
        FormField(mapUrl, { mapUrl = it.trim().take(500) }, "رابط الموقع على الخريطة", hint = "https://maps.app.goo.gl/…", enabled = !busy)
        FormField(hours, { hours = it.take(1000) }, "أوقات العمل", singleLine = false, minLines = 2, enabled = !busy)
        FormField(returnPolicy, { returnPolicy = it.take(4000) }, "سياسة الاسترجاع والاستبدال", singleLine = false, minLines = 3, enabled = !busy)
        FormField(phones, { phones = it.take(200) }, "أرقام الهاتف", singleLine = false, minLines = 2, phone = true, note = "رقم في كل سطر، حتى 5 أرقام", enabled = !busy)
        FormField(whatsapp, { whatsapp = it.trim().take(30) }, "رقم واتساب", phone = true, enabled = !busy)
        FormField(instagram, { instagram = it.trim().take(200) }, "إنستغرام", hint = "رابط الحساب أو اسم المستخدم", enabled = !busy)
        FormField(facebook, { facebook = it.trim().take(200) }, "فيسبوك", hint = "رابط الصفحة", enabled = !busy)
        FormField(tiktok, { tiktok = it.trim().take(200) }, "تيك توك", hint = "رابط الحساب أو اسم المستخدم", enabled = !busy)
        SaveButton(busy = busy, enabled = true) {
            onSave(
                saved.copy(
                    name = name.trim().ifBlank { saved.name },
                    about = about.trim(),
                    address = address.trim(),
                    mapUrl = mapUrl.trim(),
                    hours = hours.trim(),
                    returnPolicy = returnPolicy.trim(),
                    phones = phones.split('\n', ',', '،').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5),
                    whatsapp = whatsapp.trim(),
                    instagram = instagram.trim(),
                    facebook = facebook.trim(),
                    tiktok = tiktok.trim(),
                ),
            )
        }
    }
}

// ------------------------------------------------------------ usage card ---

class CustomerUsageViewModel(private val repo: CustomerAppRepository, private val customerId: String) : BaseViewModel() {
    private val _usage = MutableStateFlow<CustomerUsage?>(null)

    /** Null while loading or when it could not be loaded. */
    val usage = _usage.asStateFlow()

    init {
        viewModelScope.launch { _usage.value = repo.customerUsage(customerId).getOrNull() }
    }
}

// --------------------------------------------------------------- helpers ---

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, note: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun FormField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    number: Boolean = false,
    phone: Boolean = false,
    hint: String? = null,
    note: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    val hintText = hint ?: ""
    val noteText = note ?: ""
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        enabled = enabled,
        label = { Text(label) },
        placeholder = if (hint != null) ({ Text(hintText) }) else null,
        supportingText = if (note != null) ({ Text(noteText) }) else null,
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                phone -> KeyboardType.Phone
                else -> KeyboardType.Text
            },
        ),
    )
}

@Composable
internal fun SaveButton(busy: Boolean, enabled: Boolean, text: String = "حفظ", onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(text)
        }
    }
}
