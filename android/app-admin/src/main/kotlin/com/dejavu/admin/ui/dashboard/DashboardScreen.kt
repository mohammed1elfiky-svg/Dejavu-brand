@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.dashboard

import com.dejavu.core.ui.theme.GlassTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.model.Currency
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.OutlinedTextField
import java.math.BigDecimal
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.core.admin.Dashboard
import com.dejavu.core.admin.DashboardRepository
import com.dejavu.core.model.Money
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.label
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(private val repo: DashboardRepository, private val finance: FinanceRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<Dashboard>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _pictures = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Uploaded picture of each currency (code → URL). */
    val pictures = _pictures.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.dashboard().toLoad() }
        viewModelScope.launch {
            finance.activeCurrencies().onSuccess { cs ->
                _pictures.value = cs.currencies.mapNotNull { c -> c.imageUrl?.takeIf { it.isNotBlank() }?.let { c.code.code to it } }.toMap()
            }
        }
    }

    /** Same as the currencies screen: the new rate applies to operations from now on. */
    fun setRate(code: String, rate: String, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            finance.setRate(Currency.of(code), rate, null).orSay()?.let {
                say("تم تحديث سعر الصرف. العمليات الجديدة ستُحسب به")
                done()
                repo.dashboard().getOrNull()?.let { d -> _state.value = Load.Ready(d) }
            }
            _busy.value = false
        }
    }
}

/** A rate: units per 1 SAR, more than 0, up to 6 decimals. */
private fun dashRateOf(v: String): BigDecimal? = v.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 6 }

private fun dashPlainRate(rate: String?): String = rate?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: "—"

@Composable
fun DashboardScreen(
    session: StaffSession.LoggedIn,
    navigate: (String) -> Unit,
    switchTab: (String) -> Unit,
    openOrders: () -> Unit,
    openReturns: () -> Unit,
    openStock: () -> Unit,
    openSettlements: () -> Unit,
) {
    val vm = adminViewModel { DashboardViewModel(it.dashboard, it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val pictures by vm.pictures.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val canEditRates = adminContainer().session.can(Perm.SETTINGS_MANAGE)
    val snackbar = remember { SnackbarHostState() }
    var editingRate by remember { mutableStateOf<Dashboard.RateStatus?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GlassTopBar(
                title = { Text("لوحة التحكم") },
                actions = { IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { d ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (d.rates.any { it.stale }) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "سعر الصرف لم يُحدَّث اليوم",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Kpi("مبيعات اليوم", Money.format(d.today.sales), "${d.today.orders} طلب", Modifier.weight(1f), onClick = openOrders)
                        Kpi(
                            "صافي ربح الشهر",
                            Money.format(d.monthToDate.netIncome),
                            "مجمل الربح ${Money.format(d.monthToDate.grossProfit)}",
                            Modifier.weight(1f),
                            valueColor = if (d.monthToDate.netIncome.startsWith("-")) MaterialTheme.colorScheme.error else Success,
                        )
                    }
                }
                item { Alerts(d, openOrders, openReturns, openStock, openSettlements) }
                if (d.rates.isNotEmpty()) {
                    item { RatesCard(d.rates, pictures, canEdit = canEditRates && !busy) { editingRate = it } }
                }
                item {
                    InfoCard {
                        Text("النقدية والبنوك", style = MaterialTheme.typography.titleSmall)
                        // Each box in its own currency (100 $), with the riyal value for foreign ones.
                        d.cashAndBank.forEach {
                            val label = listOfNotNull(it.icon, it.nameAr).joinToString(" ")
                            SummaryRow(label, if (it.currency.isBase) it.shown else "${it.shown}  (≈ ${Money.format(it.balance)})")
                        }
                    }
                }
                item {
                    InfoCard {
                        Text("المستحقات والالتزامات", style = MaterialTheme.typography.titleSmall)
                        SummaryRow("على العملاء", Money.format(d.receivables.customers))
                        SummaryRow("لدى شركات الشحن (تحصيل)", Money.format(d.receivables.couriersCod))
                        SummaryRow("للموردين", Money.format(d.payables.suppliers))
                        SummaryRow("مصروفات مستحقة", Money.format(d.payables.accruedExpenses))
                        SummaryRow("دفعات مقدمة من العملاء", Money.format(d.payables.customerAdvances))
                    }
                }
                item {
                    InfoCard {
                        Text("هذا الشهر", style = MaterialTheme.typography.titleSmall)
                        SummaryRow("صافي الإيرادات", Money.format(d.monthToDate.netRevenue))
                        SummaryRow("مجمل الربح", Money.format(d.monthToDate.grossProfit))
                        SummaryRow("المصروفات التشغيلية", Money.format(d.monthToDate.operatingExpenses))
                        SummaryRow("صافي الربح", Money.format(d.monthToDate.netIncome), strong = true)
                    }
                }
                item {
                    InfoCard {
                        Text("الطلبات حسب الحالة", style = MaterialTheme.typography.titleSmall)
                        OrderStatus.entries.forEach { s -> d.ordersByStatus[s.name]?.let { SummaryRow(s.label, "$it") } }
                    }
                }
            }
        }
    }

    editingRate?.let { r ->
        QuickRateDialog(r, busy, onDismiss = { editingRate = null }) { rate -> vm.setRate(r.currency, rate) { editingRate = null } }
    }
}

/** Each exchange rate (units per 1 SAR), when it was set, and a red tag when not updated in 24 hours. */
@Composable
private fun RatesCard(rates: List<Dashboard.RateStatus>, pictures: Map<String, String>, canEdit: Boolean, onEdit: (Dashboard.RateStatus) -> Unit) {
    InfoCard {
        Text("أسعار الصرف", style = MaterialTheme.typography.titleSmall)
        rates.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = Lux.Hairline)
            Row(
                Modifier.fillMaxWidth().clickable(enabled = canEdit) { onEdit(r) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CurrencyArt(r.currency, pictures[r.currency], height = 26.dp)
                Column(Modifier.weight(1f)) {
                    Text(r.nameAr ?: Currency.of(r.currency).nameAr, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        r.effectiveAt?.let { "آخر تحديث ${formatDateTime(it)}" } ?: "لم يُحدَّد بعد",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                    )
                }
                if (r.stale) StatusChip("قديم", MaterialTheme.colorScheme.error)
                Text("1 ر.س = ${dashPlainRate(r.rate)}", style = MaterialTheme.typography.titleSmall)
            }
        }
        if (canEdit) Text("اضغط على العملة لتعديل سعرها", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
    }
}

@Composable
private fun QuickRateDialog(r: Dashboard.RateStatus, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var rate by rememberSaveable { mutableStateOf(r.rate?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    val value = dashRateOf(rate)
    val currency = Currency.of(r.currency)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("سعر ${r.nameAr ?: currency.nameAr}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CurrencyArt(r.currency, height = 30.dp)
                    Text("الحالي: 1 ر.س = ${dashPlainRate(r.rate)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it.filter { ch -> ch.isDigit() || ch == '.' }.take(20) },
                    label = { Text("كم ${currency.symbol} يساوي 1 ر.س؟") },
                    isError = rate.isNotBlank() && value == null,
                    supportingText = { Text("حتى 6 خانات عشرية") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("يُطبَّق على الطلبات والفواتير الجديدة فقط؛ السابقة تبقى بسعرها.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
        },
        confirmButton = { TextButton(onClick = { value?.let { onSave(it.toPlainString()) } }, enabled = value != null && !busy) { Text("حفظ السعر") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun Alerts(d: Dashboard, openOrders: () -> Unit, openReturns: () -> Unit, openStock: () -> Unit, openSettlements: () -> Unit) {
    val pending = d.ordersByStatus[OrderStatus.PENDING.name] ?: 0
    val items = buildList {
        if (pending > 0) add(Triple("$pending طلب بانتظار التأكيد", Warning, openOrders))
        if (d.pendingReturns > 0) add(Triple("${d.pendingReturns} مرتجع يحتاج إجراء", Warning, openReturns))
        if (d.lowStockVariants > 0) add(Triple("${d.lowStockVariants} صنف وصل لحد إعادة الطلب", MaterialTheme.colorScheme.error, openStock))
        if (d.receivables.pendingCodShipments > 0) {
            add(Triple("${d.receivables.pendingCodShipments} شحنة تحصيل لم تُورد بعد", Warning, openSettlements))
        }
    }
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (text, color, action) ->
            Card(onClick = action, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)), modifier = Modifier.fillMaxWidth()) {
                Text(text, color = color, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

@Composable
private fun Kpi(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier,
    valueColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier, colors = colors) { content() } else Card(modifier = modifier, colors = colors) { content() }
}
