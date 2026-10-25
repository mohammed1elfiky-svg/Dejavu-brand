@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.AdminContainer
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.R
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.search.recentRoute
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.DailySales
import com.dejavu.core.admin.Dashboard
import com.dejavu.core.admin.HomeBadges
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.label
import com.dejavu.core.ui.theme.GlassTopBar
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import java.math.BigDecimal
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(private val container: AdminContainer) : BaseViewModel() {
    private val repo = container.dashboard
    private val finance = container.finance
    private val _state = MutableStateFlow<Load<Dashboard>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _pictures = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Uploaded picture of each currency (code → URL). */
    val pictures = _pictures.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** Revenue per day of the last 7 days for the chart. */
    private val _sales = MutableStateFlow<Load<DailySales>>(Load.Loading)
    val sales = _sales.asStateFlow()

    /** Pull-to-refresh in progress. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    /** [withDashboard]: the user may see the figures (dashboard.view); badges load either way. */
    fun load(withDashboard: Boolean) {
        viewModelScope.launch { fetch(withDashboard) }
    }

    fun refresh(withDashboard: Boolean) {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            fetch(withDashboard)
            _refreshing.value = false
        }
    }

    fun reloadSales() {
        _sales.value = Load.Loading
        viewModelScope.launch { _sales.value = repo.dailySales(7).toLoad() }
    }

    private suspend fun fetch(withDashboard: Boolean) {
        coroutineScope<Unit> {
            launch { container.refreshBadges() }
            if (withDashboard) {
                if (_state.value !is Load.Ready) _state.value = Load.Loading
                launch {
                    val r = repo.dashboard().toLoad()
                    // A failed refresh keeps the figures already shown.
                    if (r is Load.Failed && _state.value is Load.Ready) {
                        say(r.message)
                    } else {
                        _state.value = r
                    }
                }
                launch {
                    val r = repo.dailySales(7).toLoad()
                    if (!(r is Load.Failed && _sales.value is Load.Ready)) {
                        _sales.value = r
                    }
                }
                launch {
                    finance.activeCurrencies().onSuccess { cs ->
                        _pictures.value = cs.currencies.mapNotNull { c -> c.imageUrl?.takeIf { it.isNotBlank() }?.let { c.code.code to it } }.toMap()
                    }
                }
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

private fun hasAlerts(d: Dashboard): Boolean =
    d.rates.any { it.stale } || (d.ordersByStatus[OrderStatus.PENDING.name] ?: 0) > 0 || d.pendingReturns > 0 ||
        d.lowStockVariants > 0 || d.receivables.pendingCodShipments > 0

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
    val container = adminContainer()
    val prefs = container.uiPrefs
    val vm = adminViewModel { DashboardViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val pictures by vm.pictures.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val sales by vm.sales.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val badges by container.badges.collectAsStateWithLifecycle()
    val savedOrder by prefs.homeCards.collectAsStateWithLifecycle()
    val hidden by prefs.hiddenCards.collectAsStateWithLifecycle()
    val recents by prefs.recents.collectAsStateWithLifecycle()
    val canDashboard = session.can(Perm.DASHBOARD)
    val canEditRates = session.can(Perm.SETTINGS_MANAGE)
    val role = homeRole(session)
    val snackbar = remember { SnackbarHostState() }
    var editingRate by remember { mutableStateOf<Dashboard.RateStatus?>(null) }
    var customizing by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load(canDashboard)
        onPauseOrDispose {}
    }

    val quick = quickActions(session, role, navigate, switchTab)
    val badgeList = badgeItems(session, badges, navigate, switchTab, openReturns, openStock)
    val d = (state as? Load.Ready)?.value
    val available = defaultCards(role).filter { id ->
        when (id) {
            HomeCard.QUICK -> quick.isNotEmpty()
            in HomeCard.needDashboard -> canDashboard
            else -> true
        }
    }
    val order = cardOrder(savedOrder, defaultCards(role)).filter { it in available }
    val shown = order.filter { it !in hidden }.filter { id ->
        when (id) {
            HomeCard.RECENTS -> recents.isNotEmpty()
            HomeCard.ALERTS -> d != null && hasAlerts(d)
            HomeCard.RATES -> d != null && d.rates.isNotEmpty()
            HomeCard.CASH, HomeCard.RECEIVABLES, HomeCard.MONTH, HomeCard.STATUSES -> d != null
            else -> true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GlassTopBar(
                title = { Text("الرئيسية") },
                actions = {
                    IconButton(onClick = { navigate(R.SEARCH) }) { Icon(Icons.Filled.Search, contentDescription = "بحث") }
                    IconButton(onClick = { customizing = true }) { Icon(Icons.Filled.Settings, contentDescription = "تخصيص الرئيسية") }
                    IconButton(onClick = { vm.refresh(canDashboard) }) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refresh(canDashboard) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                shown.forEach { id ->
                    item(key = id) {
                        when (id) {
                            HomeCard.SEARCH -> SearchPill { navigate(R.SEARCH) }
                            HomeCard.BADGES -> BadgesCard(badgeList, loaded = badges != null)
                            HomeCard.QUICK -> QuickActionsCard(quick)
                            HomeCard.RECENTS -> RecentsCard(
                                recents,
                                onOpen = { item -> recentRoute(item)?.let { route -> navigate(route) } },
                                onClear = prefs::clearRecents,
                            )
                            HomeCard.CHART -> {
                                val sl = sales
                                SalesChartCard((sl as? Load.Ready)?.value, failed = sl is Load.Failed, onRetry = vm::reloadSales)
                            }
                            HomeCard.KPI -> KpiCard(state, onRetry = { vm.load(canDashboard) }, openOrders = openOrders)
                            HomeCard.ALERTS -> if (d != null) Alerts(d, openOrders, openReturns, openStock, openSettlements)
                            HomeCard.RATES -> if (d != null) RatesCard(d.rates, pictures, canEdit = canEditRates && !busy) { editingRate = it }
                            HomeCard.CASH -> if (d != null) {
                                InfoCard {
                                    Text("النقدية والبنوك", style = MaterialTheme.typography.titleSmall)
                                    // Each box in its own currency (100 $), with the riyal value for foreign ones.
                                    d.cashAndBank.forEach {
                                        val label = listOfNotNull(it.icon, it.nameAr).joinToString(" ")
                                        SummaryRow(label, if (it.currency.isBase) it.shown else "${it.shown}  (≈ ${Money.format(it.balance)})")
                                    }
                                }
                            }
                            HomeCard.RECEIVABLES -> if (d != null) {
                                InfoCard {
                                    Text("المستحقات والالتزامات", style = MaterialTheme.typography.titleSmall)
                                    SummaryRow("على العملاء", Money.format(d.receivables.customers))
                                    SummaryRow("لدى شركات الشحن (تحصيل)", Money.format(d.receivables.couriersCod))
                                    SummaryRow("للموردين", Money.format(d.payables.suppliers))
                                    SummaryRow("مصروفات مستحقة", Money.format(d.payables.accruedExpenses))
                                    SummaryRow("دفعات مقدمة من العملاء", Money.format(d.payables.customerAdvances))
                                }
                            }
                            HomeCard.MONTH -> if (d != null) {
                                InfoCard {
                                    Text("هذا الشهر", style = MaterialTheme.typography.titleSmall)
                                    SummaryRow("صافي الإيرادات", Money.format(d.monthToDate.netRevenue))
                                    SummaryRow("مجمل الربح", Money.format(d.monthToDate.grossProfit))
                                    SummaryRow("المصروفات التشغيلية", Money.format(d.monthToDate.operatingExpenses))
                                    SummaryRow("صافي الربح", Money.format(d.monthToDate.netIncome), strong = true)
                                }
                            }
                            HomeCard.STATUSES -> if (d != null) {
                                InfoCard {
                                    Text("الطلبات حسب الحالة", style = MaterialTheme.typography.titleSmall)
                                    OrderStatus.entries.forEach { s -> d.ordersByStatus[s.name]?.let { SummaryRow(s.label, "$it") } }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    editingRate?.let { r ->
        QuickRateDialog(r, busy, onDismiss = { editingRate = null }) { rate -> vm.setRate(r.currency, rate) { editingRate = null } }
    }
    if (customizing) {
        CustomizeHomeDialog(
            order = order,
            hidden = hidden.filter { it in available }.toSet(),
            onDismiss = { customizing = false },
            onSave = { newOrder, newHidden ->
                prefs.setHomeCards(newOrder, newHidden)
                customizing = false
            },
            onReset = {
                prefs.setHomeCards(emptyList(), emptySet())
                customizing = false
            },
        )
    }
}

/** Big round buttons for the everyday jobs this user may do; a storekeeper sees stock jobs first. */
private fun quickActions(
    session: StaffSession.LoggedIn,
    role: HomeRole,
    navigate: (String) -> Unit,
    switchTab: (String) -> Unit,
): List<QuickAction> {
    val sale = QuickAction("بيع جديد", Icons.Filled.AddCircle) { switchTab(R.POS) }.takeIf { session.can(Perm.POS_SELL) }
    // Payments from customers are collected on the customer's account; the debtors list is where to start.
    val receipt = QuickAction("سند قبض", Icons.Filled.Face) { navigate(R.DEBTORS) }
        .takeIf { session.can(Perm.CUSTOMERS_READ) && session.can(Perm.POS_SELL) }
    val expense = QuickAction("مصروف", Icons.Filled.Edit) { navigate(R.NEW_EXPENSE) }.takeIf { session.can(Perm.EXPENSES_WRITE) }
    val importOrder = QuickAction("طلب استيراد", Icons.Filled.Send) { navigate(R.of(R.IMPORT_NEW, "-")) }.takeIf { session.can(Perm.PRODUCTS_WRITE) }
    val count = QuickAction("جرد", Icons.Filled.CheckCircle) { navigate(R.STOCK_COUNTS) }.takeIf { session.can(STOCK_COUNT) }
    val list = if (role == HomeRole.STOCK) listOf(count, importOrder, sale, receipt, expense) else listOf(sale, receipt, expense, importOrder, count)
    return list.filterNotNull()
}

/** Counters worth a tap: only those above zero that this user may open. */
private fun badgeItems(
    session: StaffSession.LoggedIn,
    b: HomeBadges?,
    navigate: (String) -> Unit,
    switchTab: (String) -> Unit,
    openReturns: () -> Unit,
    openStock: () -> Unit,
): List<BadgeItem> {
    if (b == null) return emptyList()
    val warn = Warning
    val gold = Liquid.GoldDeep
    val danger = Liquid.Danger
    return listOfNotNull(
        BadgeItem("طلبات جديدة", b.newOrders, warn) { switchTab(R.ORDERS) }.takeIf { b.newOrders > 0 && session.can(Perm.ORDERS_READ) },
        BadgeItem("طلبات شحن رصيد", b.topUps, gold) { navigate(R.TOP_UPS) }.takeIf { b.topUps > 0 && session.can(Perm.WALLET_MANAGE) },
        BadgeItem("تقييمات بانتظار المراجعة", b.reviews, gold) { navigate(R.REVIEWS) }.takeIf { b.reviews > 0 && session.can(Perm.PRODUCTS_WRITE) },
        BadgeItem("ورديات مفتوحة", b.openShifts, gold) { navigate(R.SHIFT) }
            .takeIf { b.openShifts > 0 && (session.can(Perm.POS_SELL) || session.can(Perm.CASHBOXES_MANAGE)) },
        BadgeItem("رسائل بانتظار الإرسال", b.smsWaiting, warn) { navigate(R.SMS) }.takeIf { b.smsWaiting > 0 && session.can(Perm.SETTINGS_MANAGE) },
        BadgeItem("مرتجعات", b.returns, warn, openReturns).takeIf { b.returns > 0 && session.can(Perm.RETURNS) },
        BadgeItem("نواقص المخزون", b.lowStock, danger, openStock).takeIf { b.lowStock > 0 && session.can(Perm.STOCK_READ) },
    )
}

/** Today's sales and this month's net profit; a spinner or a retry while the figures load. */
@Composable
private fun KpiCard(state: Load<Dashboard>, onRetry: () -> Unit, openOrders: () -> Unit) {
    when (state) {
        Load.Loading -> GlassCard(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        }
        is Load.Failed -> GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("تعذّر تحميل الأرقام", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
                TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
            }
        }
        is Load.Ready -> {
            val d = state.value
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
    val staleRate = d.rates.any { it.stale }
    if (items.isEmpty() && !staleRate) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (staleRate) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "سعر الصرف لم يُحدَّث اليوم",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
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
