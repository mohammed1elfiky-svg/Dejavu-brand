@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.finance

import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.dejavu.admin.ui.AccountPickerDialog
import com.dejavu.admin.ui.ChipFilter
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.pos.decimalInput
import com.dejavu.admin.ui.pos.validAmount
import com.dejavu.admin.ui.treasury.sourceLabel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.admin.ui.tint
import com.dejavu.core.admin.AdminCustomerDetail
import com.dejavu.core.admin.AdminCustomerSummary
import com.dejavu.core.admin.BalanceSheet
import com.dejavu.core.admin.CustomerAccount
import com.dejavu.core.admin.DashboardRepository
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.IncomeStatement
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.InventoryValuation
import com.dejavu.core.admin.LedgerAccount
import com.dejavu.core.admin.PendingCod
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.ReportSection
import com.dejavu.core.admin.RevenueReport
import com.dejavu.core.admin.SalesReport
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.UpdateCustomerRequest
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.label
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// ------------------------------------------------------------ settlements ---

class SettlementsViewModel(private val repo: FinanceRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<PendingCod>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _accounts = MutableStateFlow<List<LedgerAccount>>(emptyList())
    val accounts = _accounts.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.pendingCod().toLoad() }
        viewModelScope.launch { _accounts.value = repo.cashAccounts() }
    }

    fun settle(carrier: String, shipmentIds: Set<String>, account: LedgerAccount, fees: String, reference: String, done: () -> Unit) {
        if (_busy.value || shipmentIds.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            repo.settle(carrier, shipmentIds.toList(), account.id, fees, reference).orSay()?.let {
                say("تم توريد ${Money.format(it.netAmount)} إلى ${account.nameAr} (${it.number})")
                done()
                _state.value = repo.pendingCod().toLoad()
            }
            _busy.value = false
        }
    }
}

@Composable
fun SettlementsScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val canSettle = session.can(Perm.PAYMENTS_RECORD)
    val vm = adminViewModel { SettlementsViewModel(it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var carrier by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var fees by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("توريدات شركات الشحن", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { pending ->
            if (pending.carriers.isEmpty()) {
                EmptyState(Icons.Filled.CheckCircle, "لا توجد مبالغ تحصيل معلقة", "كل شحنات الدفع عند الاستلام تم توريدها", modifier = Modifier.padding(padding))
                return@LoadContent
            }
            val current = carrier?.takeIf { c -> pending.carriers.any { it.carrier == c } } ?: pending.carriers.first().carrier
            val shipments = pending.shipments.filter { it.carrier == current }
            val chosen = shipments.filter { it.id in selected }
            val collected = chosen.fold(BigDecimal.ZERO) { acc, s -> acc + Money.parse(s.codAmount) }
            val feeValue = fees.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val validFees = fees.isBlank() || (feeValue.signum() >= 0 && feeValue.scale() <= 2 && feeValue <= collected)

            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    ChipFilter(pending.carriers.map { it.carrier }, current, { c ->
                        val info = pending.carriers.first { it.carrier == c }
                        "$c (${info.shipments}) · ${Money.format(info.collected)}"
                    }) { c ->
                        carrier = c
                        selected = emptySet()
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${shipments.size} شحنة مسلّمة بانتظار التوريد", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(onClick = {
                            selected = if (chosen.size == shipments.size) emptySet() else shipments.map { it.id }.toSet()
                        }) { Text(if (chosen.size == shipments.size) "إلغاء التحديد" else "تحديد الكل") }
                    }
                }
                items(shipments, key = { it.id }) { s ->
                    val checked = s.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (checked) selected - s.id else selected + s.id }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { selected = if (it) selected + s.id else selected - s.id })
                        Column(Modifier.weight(1f)) {
                            Text("${s.order.number} · ${s.order.customer.name}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(s.trackingNumber, s.deliveredAt?.let { "سُلّمت ${formatDate(it)}" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Money.format(s.codAmount), style = MaterialTheme.typography.titleSmall)
                    }
                }
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoCard {
                            SummaryRow("المحصّل (${chosen.size} شحنة)", Money.format(collected))
                            SummaryRow("عمولة شركة الشحن", Money.format(feeValue))
                            SummaryRow("الصافي المورَّد", Money.format(collected - feeValue), strong = true)
                        }
                        OutlinedTextField(
                            value = fees,
                            onValueChange = { fees = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("عمولة التحصيل (اختياري)") },
                            isError = !validFees,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = reference,
                            onValueChange = { reference = it.take(100) },
                            label = { Text("رقم التحويل / المرجع (اختياري)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { picking = true },
                            enabled = canSettle && !busy && chosen.isNotEmpty() && validFees,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (canSettle) "تسجيل التوريد" else "لا تملك صلاحية تسجيل التوريد") }
                    }
                }
            }

            if (picking) {
                AccountPickerDialog("استلام المبلغ في", accounts, onDismiss = { picking = false }) { account ->
                    vm.settle(current, chosen.map { it.id }.toSet(), account, fees, reference) {
                        selected = emptySet()
                        fees = ""
                        reference = ""
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- reports ---

enum class Period(val label: String) {
    THIS_MONTH("هذا الشهر"),
    LAST_MONTH("الشهر الماضي"),
    THIS_YEAR("هذا العام");

    /** Inclusive ISO `from`/`to` dates. */
    fun range(today: LocalDate = LocalDate.now()): Pair<String, String> = when (this) {
        THIS_MONTH -> today.withDayOfMonth(1) to today
        LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
        THIS_YEAR -> today.withDayOfYear(1) to today
    }.let { (a, b) -> a.toString() to b.toString() }
}

internal enum class SalesGroup(val key: String, val label: String) {
    PRODUCT("product", "حسب المنتج"),
    CATEGORY("category", "حسب القسم"),
    DAY("day", "حسب اليوم"),
}

class ReportsViewModel(private val repo: DashboardRepository, private val inventory: InventoryRepository) : BaseViewModel() {
    private val _valuation = MutableStateFlow<Load<InventoryValuation>>(Load.Loading)
    val valuation = _valuation.asStateFlow()
    private val _income = MutableStateFlow<Load<IncomeStatement>>(Load.Loading)
    val income = _income.asStateFlow()
    private val _balance = MutableStateFlow<Load<BalanceSheet>>(Load.Loading)
    val balance = _balance.asStateFlow()
    private val _sales = MutableStateFlow<Load<SalesReport>>(Load.Loading)
    val sales = _sales.asStateFlow()
    private val _revenue = MutableStateFlow<Load<RevenueReport>>(Load.Loading)
    val revenue = _revenue.asStateFlow()

    var period = Period.THIS_MONTH
        private set
    internal var group = SalesGroup.PRODUCT
        private set

    fun setPeriod(p: Period) {
        period = p
        _income.value = Load.Loading
        _sales.value = Load.Loading
        _revenue.value = Load.Loading
    }

    internal fun setGroup(g: SalesGroup) {
        group = g
        _sales.value = Load.Loading
    }

    fun loadIncome() {
        val (from, to) = period.range()
        viewModelScope.launch { _income.value = repo.incomeStatement(from, to).toLoad() }
    }

    fun loadBalance() {
        viewModelScope.launch { _balance.value = repo.balanceSheet().toLoad() }
    }

    fun loadSales() {
        val (from, to) = period.range()
        viewModelScope.launch { _sales.value = repo.sales(group.key, from, to).toLoad() }
    }

    fun loadRevenue() {
        val (from, to) = period.range()
        viewModelScope.launch { _revenue.value = repo.revenue(from, to).toLoad() }
    }

    fun loadValuation() {
        viewModelScope.launch { _valuation.value = inventory.valuation().toLoad() }
    }
}

@Composable
fun ReportsScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel { ReportsViewModel(it.dashboard, it.inventory) }
    val tabs = buildList {
        if (session.can(Perm.REPORTS_FINANCIAL)) {
            add("الأرباح والخسائر")
            add("المركز المالي")
        }
        if (session.can(Perm.REPORTS_SALES)) {
            add("المبيعات")
            add(REVENUE_TAB)
        }
        if (session.can(REPORTS_INVENTORY) || session.can(Perm.REPORTS_FINANCIAL)) add(INVENTORY_TAB)
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var period by remember { mutableStateOf(vm.period) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("التقارير", onBack) }) { padding ->
        Column(Modifier.padding(padding)) {
            if (tabs.isEmpty()) {
                EmptyState(Icons.Filled.Search, "لا توجد تقارير متاحة لحسابك")
                return@Column
            }
            val current = tabs[tab.coerceIn(tabs.indices)]
            PrimaryScrollableTabRow(selectedTabIndex = tab.coerceIn(tabs.indices), edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = i == tab, onClick = { tab = i }, text = { Text(t) }) }
            }
            if (current != "المركز المالي" && current != INVENTORY_TAB) {
                ChipFilter(Period.entries, period, { it?.label.orEmpty() }) { p ->
                    if (p != null) {
                        vm.setPeriod(p)
                        period = p
                    }
                }
            }
            when (current) {
                "الأرباح والخسائر" -> IncomeTab(vm, period)
                "المركز المالي" -> BalanceTab(vm)
                INVENTORY_TAB -> InventoryValueTab(vm)
                REVENUE_TAB -> RevenueTab(vm, period)
                else -> SalesTab(vm, period)
            }
        }
    }
}

@Composable
private fun IncomeTab(vm: ReportsViewModel, period: Period) {
    val state by vm.income.collectAsStateWithLifecycle()
    LaunchedEffect(period) { vm.loadIncome() }
    LoadContent(state, onRetry = vm::loadIncome) { r ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Section("الإيرادات", r.revenue) }
            item { Section("تكلفة المبيعات", r.costOfSales) }
            item {
                InfoCard {
                    SummaryRow("مجمل الربح", Money.format(r.grossProfit), strong = true, valueColor = signColor(r.grossProfit))
                    r.grossMarginPct?.let { SummaryRow("هامش مجمل الربح", "$it٪") }
                }
            }
            item { Section("المصروفات التشغيلية", r.operatingExpenses) }
            item {
                InfoCard {
                    SummaryRow("صافي الربح", Money.format(r.netIncome), strong = true, valueColor = signColor(r.netIncome))
                    r.netMarginPct?.let { SummaryRow("هامش صافي الربح", "$it٪") }
                }
            }
        }
    }
}

@Composable
private fun BalanceTab(vm: ReportsViewModel) {
    val state by vm.balance.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadBalance() }
    LoadContent(state, onRetry = vm::loadBalance) { b ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("حتى ${formatDate(LocalDate.now().toString())}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Section("الأصول", b.assets) }
            item { Section("الالتزامات", b.liabilities) }
            item {
                InfoCard {
                    Text("حقوق الملكية", style = MaterialTheme.typography.titleSmall)
                    b.equity.lines.forEach { SummaryRow(it.nameAr, Money.format(it.amount)) }
                    SummaryRow("أرباح الفترة الحالية", Money.format(b.equity.currentEarnings))
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SummaryRow("الإجمالي", Money.format(b.equity.total), strong = true)
                }
            }
            item {
                InfoCard {
                    SummaryRow("إجمالي الالتزامات وحقوق الملكية", Money.format(b.totalLiabilitiesAndEquity), strong = true)
                    Text(
                        if (b.balanced) "✓ الميزانية متوازنة" else "✗ الميزانية غير متوازنة — راجع القيود",
                        color = if (b.balanced) Success else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SalesTab(vm: ReportsViewModel, period: Period) {
    val state by vm.sales.collectAsStateWithLifecycle()
    var group by remember { mutableStateOf(vm.group) }
    LaunchedEffect(period, group) { vm.loadSales() }
    Column {
        ChipFilter(SalesGroup.entries, group, { it?.label.orEmpty() }) { g ->
            if (g != null) {
                vm.setGroup(g)
                group = g
            }
        }
        LoadContent(state, onRetry = vm::loadSales) { r ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    InfoCard {
                        SummaryRow("صافي المبيعات", Money.format(r.totals.netSales), strong = true)
                        SummaryRow("تكلفة البضاعة", Money.format(r.totals.costOfGoods))
                        SummaryRow("مجمل الربح", Money.format(r.totals.grossProfit), valueColor = signColor(r.totals.grossProfit))
                        SummaryRow("الخصومات الممنوحة", Money.format(r.totals.discounts))
                        SummaryRow("القطع المباعة", "${r.totals.units}")
                    }
                }
                if (r.groups.isEmpty()) item { EmptyState(Icons.Filled.Search, "لا توجد مبيعات في هذه الفترة") }
                items(r.groups, key = { it.key }) { g ->
                    InfoCard {
                        Text(if (group == SalesGroup.DAY) formatDate(g.label) else g.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${g.orders} طلب · ${g.units} قطعة" + if (g.returnedUnits > 0) " · ${g.returnedUnits} مرتجع" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SummaryRow("صافي المبيعات", Money.format(g.netSales))
                        SummaryRow("مجمل الربح", Money.format(g.grossProfit), valueColor = signColor(g.grossProfit))
                    }
                }
            }
        }
    }
}

/** Permission for inventory reports (not in [Perm]). */
internal const val REPORTS_INVENTORY = "reports.inventory"

private const val INVENTORY_TAB = "قيمة المخزون"

private const val REVENUE_TAB = "الإيرادات"

/** Till vs app sales, each split into retail and merchants (SAR, net of tax and returns). */
@Composable
private fun RevenueTab(vm: ReportsViewModel, period: Period) {
    val state by vm.revenue.collectAsStateWithLifecycle()
    LaunchedEffect(period) { vm.loadRevenue() }
    LoadContent(state, onRetry = vm::loadRevenue) { r ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "من ${r.from.take(10)} إلى ${r.to.take(10)} · بالريال السعودي، صافي من الضريبة والمرتجعات",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            }
            item { RevenueChannelCard("مبيعات الكاشير", r.pos) }
            item { RevenueChannelCard("مبيعات التطبيق", r.app) }
            item { RevenueChannelCard("الإجمالي", RevenueReport.Channel(retail = r.retail, merchants = r.merchants, total = r.total)) }
            item {
                InfoCard {
                    Text("مبيعات التاجرات (الكاشير + التطبيق)", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                    RevenueCellRows(r.merchants)
                }
            }
        }
    }
}

@Composable
private fun RevenueChannelCard(title: String, channel: RevenueReport.Channel) {
    InfoCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
        Text("قطاعي", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        RevenueCellRows(channel.retail)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Lux.Hairline)
        Text("التاجرات", style = MaterialTheme.typography.titleSmall)
        RevenueCellRows(channel.merchants)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Lux.Hairline)
        SummaryRow("الإيراد الكلي", Money.formatBase(channel.total.revenue), strong = true)
        SummaryRow("مجمل الربح الكلي", Money.formatBase(channel.total.grossProfit), valueColor = signColor(channel.total.grossProfit))
    }
}

@Composable
private fun RevenueCellRows(cell: RevenueReport.Cell) {
    Text("${cell.orders} طلب · ${cell.units} قطعة", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
    SummaryRow("الإيراد", Money.formatBase(cell.revenue))
    SummaryRow("التكلفة", Money.formatBase(cell.cost))
    SummaryRow("مجمل الربح", Money.formatBase(cell.grossProfit), valueColor = signColor(cell.grossProfit))
}

@Composable
private fun InventoryValueTab(vm: ReportsViewModel) {
    val state by vm.valuation.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadValuation() }
    LoadContent(state, onRetry = vm::loadValuation) { v ->
        val totalCost = Money.parse(v.totals.costValue)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("تقرير قيمة المخزون", style = MaterialTheme.typography.titleMedium)
                    Text("حتى ${formatDate(LocalDate.now().toString())} · بالريال السعودي", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Lux.Hairline)
                    SummaryRow("عدد الأصناف", "${v.totals.products}")
                    SummaryRow("عدد القطع", "${v.totals.pieces}")
                    SummaryRow("إجمالي التكلفة", Money.formatBase(v.totals.costValue), strong = true)
                    SummaryRow("إجمالي سعر البيع المتوقع", Money.formatBase(v.totals.saleValue))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("الربح المتوقع", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        GoldPill { Text(Money.formatBase(v.totals.expectedProfit), color = Color.White, style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
            if (v.categories.isEmpty()) item { EmptyState(Icons.Filled.Search, "لا يوجد مخزون حالياً") }
            items(v.categories, key = { it.categoryId }) { c ->
                val cost = Money.parse(c.costValue)
                val share = if (totalCost.signum() > 0) cost.divide(totalCost, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) else 0f
                GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.nameAr, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${(share * 100).toInt()}٪ من التكلفة", style = MaterialTheme.typography.labelSmall, color = Lux.GoldDeep, fontWeight = FontWeight.SemiBold)
                    }
                    Text("${c.products} صنف · ${c.pieces} قطعة", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Lux.Champagne),
                    ) {
                        Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(50)).background(Lux.GoldBrush))
                    }
                    SummaryRow("التكلفة", Money.formatBase(c.costValue))
                    SummaryRow("قيمة البيع", Money.formatBase(c.saleValue))
                    SummaryRow("الربح المتوقع", Money.formatBase(c.expectedProfit), valueColor = signColor(c.expectedProfit))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, section: ReportSection) {
    InfoCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        section.lines.forEach { SummaryRow("${it.code} ${it.nameAr}", Money.format(it.amount)) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SummaryRow("الإجمالي", Money.format(section.total), strong = true)
    }
}

@Composable
private fun signColor(amount: String) = if (Money.parse(amount).signum() < 0) MaterialTheme.colorScheme.error else Success

// ------------------------------------------------------------- customers ---

class CustomersViewModel(private val repo: FinanceRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<AdminCustomerSummary>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private var query = ""
    private var page = 1
    private var search: Job? = null

    fun search(q: String) {
        query = q
        search?.cancel()
        search = viewModelScope.launch {
            delay(350)
            fetch(reset = true)
        }
    }

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { fetch(reset = true) }
    }

    fun more() {
        viewModelScope.launch { fetch(reset = false) }
    }

    private suspend fun fetch(reset: Boolean) {
        val next = if (reset) 1 else page + 1
        repo.customers(query, next).fold(
            { p ->
                page = next
                val before = if (reset) emptyList() else (_state.value as? Load.Ready)?.value.orEmpty()
                _state.value = Load.Ready(before + p.items)
                _hasMore.value = p.page * p.pageSize < p.total
            },
            { e -> if (reset) _state.value = Load.Failed(e.userMessage()) else say(e.userMessage()) },
        )
    }
}

@Composable
fun CustomersScreen(onBack: () -> Unit, openCustomer: (String) -> Unit) {
    val vm = adminViewModel { CustomersViewModel(it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("العملاء", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.search(it)
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("الاسم أو رقم الهاتف أو الكود") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            LoadContent(state, onRetry = vm::load) { customers ->
                if (customers.isEmpty()) {
                    EmptyState(Icons.Filled.Person, "لا يوجد عملاء مطابقون")
                    return@LoadContent
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(customers, key = { it.id }) { c ->
                        Card(
                            onClick = { openCustomer(c.id) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                                        if (c.isMerchant) MerchantBadge()
                                    }
                                    Text("${c.code} · ${c.phone}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                c.count?.let { Text("${it.orders} طلب", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                    if (hasMore) {
                        item {
                            OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("عرض المزيد") }
                        }
                    }
                }
            }
        }
    }
}

class CustomerDetailViewModel(private val id: String, private val repo: FinanceRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<AdminCustomerDetail>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _account = MutableStateFlow<Load<CustomerAccount>>(Load.Loading)
    val account = _account.asStateFlow()
    private val _currencies = MutableStateFlow(listOf(Currency.SAR))
    val currencies = _currencies.asStateFlow()
    private val _rates = MutableStateFlow<Map<Currency, BigDecimal>>(emptyMap())

    /** Units per 1 SAR of each active currency with a rate (to suggest exchange amounts). */
    val rates = _rates.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.customer(id).toLoad() }
        loadAccount()
        viewModelScope.launch {
            repo.activeCurrencies().onSuccess { cs ->
                _currencies.value = (listOf(Currency.SAR) + cs.register().map { Currency.of(it.code) }).distinct()
                _rates.value = cs.currencies.mapNotNull { r ->
                    r.rate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { Currency.of(r.code.code) to it }
                }.toMap() + (Currency.SAR to BigDecimal.ONE)
            }
        }
    }

    fun loadAccount() {
        if (_account.value !is Load.Ready) _account.value = Load.Loading
        viewModelScope.launch { _account.value = repo.customerAccount(id).toLoad() }
    }

    /** Merchant flag, extra discount (%) and credit limit (SAR, "" = no limit). */
    fun saveMerchant(isMerchant: Boolean, discount: String, creditLimit: String, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val request = if (!isMerchant) {
                UpdateCustomerRequest(isMerchant = false)
            } else {
                UpdateCustomerRequest(
                    isMerchant = true,
                    merchantDiscount = discount.trim().ifBlank { "0" },
                    // The server treats "" as "no limit".
                    creditLimit = creditLimit.trim(),
                )
            }
            repo.updateCustomer(id, request).fold(
                { c ->
                    _state.value = Load.Ready(c)
                    say(if (c.isMerchant) "تم حفظ حساب التاجر" else "تم إلغاء حساب التاجر")
                    done()
                },
                { e ->
                    val m = e.message.orEmpty()
                    say(
                        when {
                            "Merchant discount must be between" in m -> "خصم التاجر يجب أن يكون بين 0 و 99.99%"
                            "walk-in customer cannot be changed" in m -> "لا يمكن تعديل العميل النقدي"
                            else -> e.userMessage()
                        },
                    )
                },
            )
            _busy.value = false
        }
    }

    /** Money taken from the customer at the till: settles their debt, any excess becomes wallet credit. */
    fun collect(currency: Currency, amount: String, sendSms: Boolean, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.customerReceipt(id, currency, amount, sendSms = sendSms).orSay()?.let {
                val sms = if (sendSms) "، وأُضيفت رسالة نصية للعميل إلى قائمة الإرسال" else ""
                say("تم تحصيل ${Money.plain(Money.parse(amount), currency)}$sms")
                done()
                _account.value = repo.customerAccount(id).toLoad()
                repo.customer(id).onSuccess { c -> _state.value = Load.Ready(c) }
            }
            _busy.value = false
        }
    }

    /** Exchanges [amount] of [from] in the customer's account for [toAmount] of [to]. */
    fun exchange(from: Currency, amount: String, to: Currency, toAmount: String, note: String, sendSms: Boolean, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.customerExchange(id, from, amount, to, toAmount, note, sendSms).orSay()?.let { r ->
                val sms = if (r.smsQueued) "، وأُضيفت رسالة نصية للعميل إلى قائمة الإرسال" else ""
                say("تمت المصارفة: ${Money.plain(Money.parse(amount), from)} ← ${Money.plain(Money.parse(toAmount), to)}$sms")
                done()
                _account.value = repo.customerAccount(id).toLoad()
                repo.customer(id).onSuccess { c -> _state.value = Load.Ready(c) }
            }
            _busy.value = false
        }
    }

    /** A balance from before the system ([owes] = the customer owes the store). */
    fun openingBalance(currency: Currency, amount: String, owes: Boolean, note: String, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.openingBalance(id, currency, amount, owes, note).orSay()?.let {
                say("تم تسجيل الرصيد الافتتاحي ${Money.plain(Money.parse(amount), currency)}")
                done()
                _account.value = repo.customerAccount(id).toLoad()
                repo.customer(id).onSuccess { c -> _state.value = Load.Ready(c) }
            }
            _busy.value = false
        }
    }
}

@Composable
fun CustomerDetailScreen(id: String, onBack: () -> Unit, openOrder: (String) -> Unit) {
    val vm = adminViewModel(key = "customer:$id") { CustomerDetailViewModel(id, it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val currencies by vm.currencies.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val canCollect = adminContainer().session.can(Perm.POS_SELL)
    val canEdit = adminContainer().session.can(Perm.CUSTOMERS_WRITE)
    val canOpening = adminContainer().session.can("journal.write")
    val canExchange = canEdit && adminContainer().session.can(Perm.PAYMENTS_RECORD)
    val snackbar = remember { SnackbarHostState() }
    var collecting by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var exchanging by remember { mutableStateOf(false) }
    val hasPhone = (state as? Load.Ready)?.value?.phone?.isNotBlank() == true
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    if (collecting) {
        CollectDialog(currencies, hasPhone, onDismiss = { collecting = false }) { currency, amount, sendSms ->
            vm.collect(currency, amount, sendSms) { collecting = false }
        }
    }
    if (exchanging) {
        CustomerExchangeDialog(currencies, rates, hasPhone, busy, onDismiss = { exchanging = false }) { from, amount, to, toAmount, note, sendSms ->
            vm.exchange(from, amount, to, toAmount, note, sendSms) { exchanging = false }
        }
    }
    if (opening) {
        OpeningBalanceDialog(currencies, busy, onDismiss = { opening = false }) { currency, amount, owes, note ->
            vm.openingBalance(currency, amount, owes, note) { opening = false }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar((state as? Load.Ready)?.value?.name ?: "عميل", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { c ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            if (c.isMerchant) MerchantBadge()
                        }
                        Text(listOfNotNull(c.code, c.phone, c.email).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (canEdit || c.isMerchant) {
                    item(key = "merchant:${c.isMerchant}:${c.merchantDiscount}:${c.creditLimit}") {
                        MerchantCard(c, canEdit = canEdit, busy = busy) { merchant, discount, limit, done -> vm.saveMerchant(merchant, discount, limit, done) }
                    }
                }
                item {
                    InfoCard {
                        SummaryRow("عدد الطلبات", "${c.lifetime.orders}")
                        SummaryRow("إجمالي المشتريات", Money.format(c.lifetime.spent))
                        val balance = Money.parse(c.ledgerBalance)
                        SummaryRow(
                            when {
                                balance.signum() > 0 -> "مستحق على العميل"
                                balance.signum() < 0 -> "رصيد دائن للعميل"
                                else -> "الرصيد"
                            },
                            Money.format(balance.abs()),
                            strong = true,
                            valueColor = if (balance.signum() > 0) MaterialTheme.colorScheme.error else Success,
                        )
                    }
                }
                item {
                    CustomerAccountCard(
                        account,
                        canCollect = canCollect && !busy,
                        onRetry = vm::loadAccount,
                        onCollect = { collecting = true },
                        onOpening = if (canOpening) ({ opening = true }) else null,
                        onExchange = if (canExchange && currencies.size > 1) ({ exchanging = true }) else null,
                    )
                }
                item { Text("آخر الطلبات", style = MaterialTheme.typography.titleSmall) }
                if (c.orders.isEmpty()) item { Text("لا توجد طلبات بعد", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(c.orders, key = { it.id }) { o ->
                    Card(
                        onClick = { openOrder(o.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(o.number, style = MaterialTheme.typography.titleSmall)
                                Text("${formatDate(o.placedAt)} · ${Money.format(o.grandTotal)}", style = MaterialTheme.typography.bodySmall)
                            }
                            StatusChip(o.status.label, o.status.tint())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MerchantBadge() {
    GoldPill { Text("تاجر", color = Color.White, style = MaterialTheme.typography.labelMedium) }
}

private fun plainNumber(v: String?): String = v?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString().orEmpty()

/** Merchant (wholesale) account: wholesale prices less [AdminCustomerDetail.merchantDiscount], buying on credit up to the limit. */
@Composable
private fun MerchantCard(
    c: AdminCustomerDetail,
    canEdit: Boolean,
    busy: Boolean,
    onSave: (isMerchant: Boolean, discount: String, creditLimit: String, done: () -> Unit) -> Unit,
) {
    var merchant by rememberSaveable { mutableStateOf(c.isMerchant) }
    var discount by rememberSaveable { mutableStateOf(plainNumber(c.merchantDiscount).takeIf { it != "0" }.orEmpty()) }
    var limit by rememberSaveable { mutableStateOf(plainNumber(c.creditLimit)) }
    val discountValue = discount.trim().toBigDecimalOrNull()
    val discountOk = discount.isBlank() || (discountValue != null && discountValue.signum() >= 0 && discountValue < BigDecimal(100) && discountValue.stripTrailingZeros().scale() <= 2)
    val limitValue = limit.trim().toBigDecimalOrNull()
    val limitOk = limit.isBlank() || (limitValue != null && limitValue.signum() >= 0 && limitValue.stripTrailingZeros().scale() <= 2)
    val changed = merchant != c.isMerchant ||
        (discountValue ?: BigDecimal.ZERO).compareTo(c.merchantDiscount.toBigDecimalOrNull() ?: BigDecimal.ZERO) != 0 ||
        plainNumber(limit.trim()) != plainNumber(c.creditLimit)

    InfoCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("حساب تاجر", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (c.isMerchant) MerchantBadge()
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("تاجر (أسعار الجملة والبيع الآجل)", Modifier.weight(1f))
            Switch(checked = merchant, onCheckedChange = { merchant = it }, enabled = canEdit && !busy)
        }
        if (merchant) {
            OutlinedTextField(
                value = discount,
                onValueChange = { discount = decimalInput(it).take(6) },
                label = { Text("خصم إضافي على سعر التاجر (%)") },
                isError = !discountOk,
                enabled = canEdit && !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = decimalInput(it).take(14) },
                label = { Text("حد الائتمان (ر.س)") },
                placeholder = { Text("بدون حد") },
                supportingText = { Text(if (limit.isBlank()) "فارغ = بدون حد للبيع الآجل" else "أقصى مبلغ يمكن أن يكون مستحقاً على التاجر") },
                isError = !limitOk,
                enabled = canEdit && !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (c.isMerchant) {
            Text("سيعود العميل لأسعار البيع العادية ولن يستطيع الشراء بالآجل", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        if (canEdit && changed) {
            Button(onClick = { onSave(merchant, discount, limit) {} }, enabled = !busy && discountOk && limitOk) { Text("حفظ") }
        }
    }
}

/** Balances per currency on the customer's account (+ they owe the store, − the store holds their money). */
@Composable
private fun CustomerAccountCard(
    state: Load<CustomerAccount>,
    canCollect: Boolean,
    onRetry: () -> Unit,
    onCollect: () -> Unit,
    onOpening: (() -> Unit)? = null,
    onExchange: (() -> Unit)? = null,
) {
    InfoCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("حساب العميل", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (canCollect) {
                Button(onClick = onCollect) { Text("تحصيل دفعة") }
            }
        }
        if (onOpening != null || onExchange != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpening != null) {
                    OutlinedButton(onClick = onOpening, modifier = Modifier.weight(1f)) { Text("رصيد افتتاحي") }
                }
                if (onExchange != null) {
                    OutlinedButton(onClick = onExchange, modifier = Modifier.weight(1f)) { Text("مصارفة") }
                }
            }
        }
        when (state) {
            Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            is Load.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
            }
            is Load.Ready -> {
                val acc = state.value
                val balances = acc.balances.filter { Money.parse(it.balance).signum() != 0 }
                if (balances.isEmpty()) {
                    Text("لا يوجد رصيد مستحق أو دائن", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                balances.forEach { b ->
                    val v = Money.parse(b.balance)
                    SummaryRow(
                        if (v.signum() > 0) "مدين على العميل (${b.currency.nameAr})" else "رصيد للعميل (${b.currency.nameAr})",
                        Money.plain(v.abs(), b.currency),
                        strong = true,
                        valueColor = if (v.signum() > 0) MaterialTheme.colorScheme.error else Success,
                    )
                }
                if (acc.entries.items.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("آخر الحركات", style = MaterialTheme.typography.titleSmall)
                    acc.entries.items.take(10).forEach { e ->
                        val v = Money.parse(e.amount)
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sourceLabel(e.sourceType, e.note), style = MaterialTheme.typography.bodyMedium)
                                Text(formatDateTime(e.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                (if (v.signum() > 0) "+" else "−") + Money.plain(v.abs(), e.currency),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (v.signum() > 0) MaterialTheme.colorScheme.error else Success,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectDialog(currencies: List<Currency>, hasPhone: Boolean, onDismiss: () -> Unit, onConfirm: (Currency, String, Boolean) -> Unit) {
    var currency by remember { mutableStateOf(currencies.firstOrNull() ?: Currency.SAR) }
    var amount by rememberSaveable { mutableStateOf("") }
    var sendSms by rememberSaveable { mutableStateOf(hasPhone) }
    val value = validAmount(amount, currency)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحصيل دفعة من العميل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.forEach { c -> FilterChip(selected = c == currency, onClick = { currency = c }, label = { Text(c.nameAr) }) }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = decimalInput(it) },
                    label = { Text("المبلغ (${currency.symbol})") },
                    isError = amount.isNotBlank() && value == null,
                    supportingText = if (currency.decimals == 0) ({ Text("بدون كسور") }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    "يُسدَّد به دين العميل بهذه العملة، وأي زيادة تصبح رصيداً له. يُستلم المبلغ في درجك.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SmsCheckbox(sendSms, enabled = hasPhone) { sendSms = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value != null) onConfirm(currency, value.stripTrailingZeros().toPlainString(), sendSms && hasPhone) }, enabled = value != null) { Text("تحصيل") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

/** Balance the customer had before the system: in a currency, owed by them or held for them. */
@Composable
private fun OpeningBalanceDialog(
    currencies: List<Currency>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (currency: Currency, amount: String, owes: Boolean, note: String) -> Unit,
) {
    var currency by remember { mutableStateOf(currencies.firstOrNull() ?: Currency.SAR) }
    var amount by rememberSaveable { mutableStateOf("") }
    var owes by rememberSaveable { mutableStateOf(true) }
    var note by rememberSaveable { mutableStateOf("") }
    val value = validAmount(amount, currency)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("رصيد افتتاحي") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("العملة", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.forEach { c -> FilterChip(selected = c == currency, onClick = { currency = c }, label = { Text(c.nameAr) }) }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = decimalInput(it) },
                    label = { Text("المبلغ (${currency.symbol})") },
                    isError = amount.isNotBlank() && value == null,
                    supportingText = if (currency.decimals == 0) ({ Text("بدون كسور") }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("الاتجاه", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                listOf(true to "العميل مدين للمحل", false to "رصيد دائن للعميل").forEach { (o, text) ->
                    Row(Modifier.fillMaxWidth().clickable { owes = o }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = owes == o, onClick = { owes = o })
                        Text(text)
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(255) },
                    label = { Text("ملاحظة (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "يُسجَّل رصيد ما قبل النظام بقيد افتتاحي دون أي حركة على الصناديق.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (value != null) onConfirm(currency, value.stripTrailingZeros().toPlainString(), owes, note.trim()) },
                enabled = value != null && !busy,
            ) { Text("تسجيل") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

/** "Text the customer" option of the account vouchers (sent from the store's gateway phone). */
@Composable
private fun SmsCheckbox(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
        Column {
            Text("إرسال رسالة نصية للعميل")
            if (!enabled) Text("لا يوجد رقم جوال للعميل", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Suggested amount of [to] for [amount] of [from], from the rates (units per 1 SAR); null without both rates. */
private fun exchangeSuggestion(amount: BigDecimal?, from: Currency, to: Currency, rates: Map<Currency, BigDecimal>): BigDecimal? {
    if (amount == null) return null
    val fromRate = rates[from] ?: return null
    val toRate = rates[to] ?: return null
    return amount.multiply(toRate).divide(fromRate, to.decimals, RoundingMode.HALF_UP).takeIf { it.signum() > 0 }
}

/** Currency exchange inside the customer's account: their [from] balance becomes [to] balance. */
@Composable
private fun CustomerExchangeDialog(
    currencies: List<Currency>,
    rates: Map<Currency, BigDecimal>,
    hasPhone: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (from: Currency, amount: String, to: Currency, toAmount: String, note: String, sendSms: Boolean) -> Unit,
) {
    var from by remember { mutableStateOf(currencies.firstOrNull() ?: Currency.SAR) }
    var to by remember { mutableStateOf(currencies.firstOrNull { it != from } ?: Currency.SAR) }
    var amount by rememberSaveable { mutableStateOf("") }
    var toAmount by rememberSaveable { mutableStateOf("") }
    // Until the user types the received amount, it follows the rates.
    var toEdited by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var sendSms by rememberSaveable { mutableStateOf(hasPhone) }
    val value = validAmount(amount, from)
    val suggested = exchangeSuggestion(value, from, to, rates)
    val toText = if (toEdited) toAmount else suggested?.stripTrailingZeros()?.toPlainString().orEmpty()
    val toValue = validAmount(toText, to)
    val ok = value != null && toValue != null && from != to && !busy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مصارفة في حساب العميل") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("من عملة", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.forEach { c ->
                        FilterChip(
                            selected = c == from,
                            onClick = {
                                if (c != from) {
                                    if (c == to) to = from
                                    from = c
                                    toEdited = false
                                }
                            },
                            label = { Text(c.nameAr) },
                        )
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = decimalInput(it) },
                    label = { Text("المبلغ (${from.symbol})") },
                    isError = amount.isNotBlank() && value == null,
                    supportingText = if (from.decimals == 0) ({ Text("بدون كسور") }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("إلى عملة", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.filter { it != from }.forEach { c ->
                        FilterChip(
                            selected = c == to,
                            onClick = {
                                if (c != to) {
                                    to = c
                                    toEdited = false
                                }
                            },
                            label = { Text(c.nameAr) },
                        )
                    }
                }
                OutlinedTextField(
                    value = toText,
                    onValueChange = {
                        toAmount = decimalInput(it)
                        toEdited = true
                    },
                    label = { Text("المبلغ المقابل (${to.symbol})") },
                    isError = toText.isNotBlank() && toValue == null,
                    supportingText = {
                        Text(
                            when {
                                suggested == null -> "أدخل المبلغ بالعملة الأخرى"
                                toEdited -> "حسب السعر: ${Money.plain(suggested, to)}"
                                else -> "محسوب من أسعار الصرف، يمكنك تعديله"
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(255) },
                    label = { Text("ملاحظة (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SmsCheckbox(sendSms, enabled = hasPhone) { sendSms = it }
                Text(
                    "يُخصم المبلغ من رصيد العميل بالعملة الأولى ويُضاف المقابل لرصيده بالعملة الثانية، دون أي حركة على الصناديق.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (value != null && toValue != null) {
                        onConfirm(from, value.stripTrailingZeros().toPlainString(), to, toValue.stripTrailingZeros().toPlainString(), note.trim(), sendSms && hasPhone)
                    }
                },
                enabled = ok,
            ) { Text("مصارفة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}
