@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.dashboard

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
import androidx.compose.material3.Card
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

class DashboardViewModel(private val repo: DashboardRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<Dashboard>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.dashboard().toLoad() }
    }
}

@Composable
fun DashboardScreen(openOrders: () -> Unit, openReturns: () -> Unit, openStock: () -> Unit, openSettlements: () -> Unit) {
    val vm = adminViewModel { DashboardViewModel(it.dashboard) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("لوحة التحكم") },
                actions = { IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") } },
            )
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { d ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                item {
                    InfoCard {
                        Text("النقدية والبنوك", style = MaterialTheme.typography.titleSmall)
                        d.cashAndBank.forEach { SummaryRow(it.nameAr, Money.format(it.balance)) }
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
