package com.dejavu.admin.ui.system

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.ManagementRepository
import com.dejavu.core.admin.OwnerAlert
import com.dejavu.core.admin.OwnerAlertSettings
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The server merges the settings and drops nulls (explicitNulls = false), and it forbids negative
 * thresholds, so "no shortage alerts" is sent as this huge amount and shown as off.
 */
private const val SHORTAGE_OFF = 999_999_999.0

class OwnerAlertsViewModel(private val repo: ManagementRepository) : BaseViewModel() {
    private val _settings = MutableStateFlow<Load<OwnerAlertSettings>>(Load.Loading)
    val settings = _settings.asStateFlow()
    private val _alerts = MutableStateFlow<Load<List<OwnerAlert>>>(Load.Loading)
    val alerts = _alerts.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val refresher = Refresher()

    fun load() {
        if (_settings.value !is Load.Ready) {
            _settings.value = Load.Loading
            viewModelScope.launch { _settings.value = repo.ownerAlertSettings().toLoad() }
        }
        if (_alerts.value !is Load.Ready) _alerts.value = Load.Loading
        viewModelScope.launch { _alerts.value = repo.ownerAlerts().toLoad() }
    }

    fun refresh() = refresher.run(viewModelScope) {
        _alerts.value = repo.ownerAlerts().toLoad()
        repo.ownerAlertSettings().getOrNull()?.let { _settings.value = Load.Ready(it) }
    }

    fun save(s: OwnerAlertSettings) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.setOwnerAlertSettings(s).orSay()?.let {
                _settings.value = Load.Ready(it)
                say("تم حفظ إعدادات التنبيهات")
            }
            _busy.value = false
        }
    }
}

/** The owner's alerts (deleted invoices, drawer shortages, price changes) and their settings. */
@Composable
fun OwnerAlertsScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "owner-alerts") { OwnerAlertsViewModel(it.management) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("تنبيهات المالك", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        RefreshList(refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    when (val s = settings) {
                        is Load.Ready -> SettingsCard(s.value, busy, vm::save)
                        is Load.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                        Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                item { Text("آخر التنبيهات", style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep, modifier = Modifier.padding(top = 8.dp)) }
                when (val a = alerts) {
                    Load.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is Load.Failed -> item { Text(a.message, color = MaterialTheme.colorScheme.error) }
                    is Load.Ready -> {
                        if (a.value.isEmpty()) item { Text("لا توجد تنبيهات بعد", color = Lux.InkSoft) }
                        items(a.value, key = { it.id }) { AlertRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(initial: OwnerAlertSettings, busy: Boolean, onSave: (OwnerAlertSettings) -> Unit) {
    var voids by remember(initial) { mutableStateOf(initial.voids) }
    var prices by remember(initial) { mutableStateOf(initial.priceChanges) }
    val above = initial.shortageAbove
    val initialShortageOn = above != null && above < SHORTAGE_OFF
    var shortageOn by remember(initial) { mutableStateOf(initialShortageOn) }
    var shortage by remember(initial) {
        mutableStateOf(if (initialShortageOn) above?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty() else "0")
    }
    var phone by remember(initial) { mutableStateOf(initial.smsPhone.orEmpty()) }
    val shortageValue = shortage.toDoubleOrNull()?.takeIf { it >= 0 }
    val valid = !shortageOn || shortageValue != null

    GlassCard(Modifier.fillMaxWidth()) {
        Text("ما الذي يصلك؟", style = MaterialTheme.typography.titleSmall)
        ToggleRow("حذف فاتورة", "عند إلغاء أو حذف فاتورة بيع", voids) { voids = it }
        ToggleRow("تعديل الأسعار", "عند تغيير سعر منتج أو تعديل جماعي", prices) { prices = it }
        ToggleRow("عجز في الصندوق", "عند إغلاق وردية بعجز أكبر من المبلغ المحدد", shortageOn) { shortageOn = it }
        if (shortageOn) {
            OutlinedTextField(
                value = shortage,
                onValueChange = { v -> shortage = v.filter { it.isDigit() || it == '.' }.take(10) },
                label = { Text("نبّهني إذا تجاوز العجز (ر.س)") },
                supportingText = { Text("0 = أي عجز") },
                isError = shortageValue == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = phone,
            onValueChange = { v -> phone = v.filter { it.isDigit() || it == '+' }.take(20) },
            label = { Text("رقم جوال لرسائل SMS (اختياري)") },
            supportingText = { Text("اتركه فارغاً لإيقاف الرسائل النصية") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Button(
            onClick = {
                onSave(
                    OwnerAlertSettings(
                        voids = voids,
                        priceChanges = prices,
                        shortageAbove = if (shortageOn) shortageValue ?: 0.0 else SHORTAGE_OFF,
                        smsPhone = phone.trim(),
                    ),
                )
            },
            enabled = valid && !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text(if (busy) "جارٍ الحفظ…" else "حفظ الإعدادات") }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AlertRow(a: OwnerAlert) {
    val (icon, tint) = alertIcon(a.kind)
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.titleSmall)
                if (a.body.isNotBlank()) Text(a.body, style = MaterialTheme.typography.bodySmall)
                Text(formatDateTime(a.createdAt), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
        }
    }
}

@Composable
private fun alertIcon(kind: String): Pair<ImageVector, androidx.compose.ui.graphics.Color> = when (kind) {
    "VOID" -> Icons.Filled.Delete to MaterialTheme.colorScheme.error
    "SHORTAGE" -> Icons.Filled.Warning to Warning
    "PRICE" -> Icons.Filled.Edit to Lux.GoldDeep
    else -> Icons.Filled.Notifications to Lux.GoldDeep
}
