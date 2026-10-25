package com.dejavu.admin.ui.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import com.dejavu.core.ui.EmptyState
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.Debtor
import com.dejavu.core.admin.Debtors
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.SystemRepository
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.Tag
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.Gold
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DebtorsViewModel(private val repo: SystemRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<Debtors>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _minDays = MutableStateFlow<Int?>(null)

    /** Only customers who have not paid for at least this many days (null = all). */
    val minDays = _minDays.asStateFlow()
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        val days = _minDays.value
        viewModelScope.launch {
            val res = repo.debtors(days).toLoad()
            if (days == _minDays.value) _state.value = res
            (res as? Load.Ready)?.value?.items?.map { it.id }?.toSet()?.let { ids -> _selected.value = _selected.value intersect ids }
        }
    }

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) {
        val days = _minDays.value
        val res = repo.debtors(days).toLoad()
        if (days == _minDays.value) _state.value = res
        (res as? Load.Ready)?.value?.items?.map { it.id }?.toSet()?.let { ids -> _selected.value = _selected.value intersect ids }
    }

    fun filter(days: Int?) {
        if (days == _minDays.value) return
        _minDays.value = days
        _state.value = Load.Loading
        load()
    }

    fun toggle(id: String) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    fun selectAll(ids: List<String>) {
        _selected.value = if (_selected.value.containsAll(ids)) emptySet() else ids.toSet()
    }

    fun remind() {
        val ids = _selected.value.toList()
        if (ids.isEmpty() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.remind(ids).orSay()?.let { r ->
                say(if (r.skipped.isEmpty()) "أُرسلت ${r.queued} رسالة" else "أُرسلت ${r.queued} رسالة · تُخطّي ${r.skipped.size} (بلا رقم أو بلا دين)")
                _selected.value = emptySet()
            }
            _busy.value = false
        }
    }
}

private val DAY_FILTERS = listOf<Int?>(null, 30, 60)

private fun dayFilterLabel(days: Int?) = if (days == null) "الكل" else "أكثر من $days يوم"

/** Customers who owe the store, with SMS reminders of their debt. */
@Composable
fun DebtorsScreen(@Suppress("UNUSED_PARAMETER") session: StaffSession.LoggedIn, onBack: () -> Unit, openCustomer: (String) -> Unit) {
    val vm = adminViewModel { DebtorsViewModel(it.system) }
    val state by vm.state.collectAsStateWithLifecycle()
    val minDays by vm.minDays.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("العملاء المدينون", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text("إرسال تذكير برسالة (${selected.size})")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DAY_FILTERS.forEach { d -> FilterChip(selected = d == minDays, onClick = { vm.filter(d) }, label = { Text(dayFilterLabel(d)) }) }
            }
            LoadContent(state, onRetry = vm::load) { data ->
                RefreshList(refreshing, onRefresh = vm::refresh) {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "total") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("إجمالي المستحق على العملاء", style = MaterialTheme.typography.bodyMedium, color = Lux.InkSoft)
                            Text(Money.formatBase(data.totalOwed), style = MaterialTheme.typography.headlineSmall, color = Lux.GoldDeep)
                            Text("${data.items.size} عميل", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                    }
                    if (data.items.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                Icons.Filled.CheckCircle,
                                "لا يوجد عملاء مدينون",
                                if (minDays != null) "لا ديون متأخرة أكثر من $minDays يوم" else "كل العملاء سددوا ما عليهم",
                                action = if (minDays != null) "عرض الكل" else null,
                                onAction = { vm.filter(null) },
                                modifier = Modifier.height(320.dp),
                            )
                        }
                    } else {
                        item(key = "all") {
                            val ids = data.items.map { it.id }
                            TextButton(onClick = { vm.selectAll(ids) }) {
                                Text(if (selected.containsAll(ids)) "إلغاء تحديد الكل" else "تحديد الكل")
                            }
                        }
                    }
                    items(data.items, key = { it.id }) { d ->
                        DebtorRow(d, checked = d.id in selected, onCheck = { vm.toggle(d.id) }, onOpen = { openCustomer(d.id) })
                    }
                }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            "إرسال تذكير",
            "ستُرسل رسالة نصية إلى ${selected.size} عميل بمبلغ الدين المستحق عليه، من هاتف بوابة الرسائل.",
            "إرسال",
            onDismiss = { confirm = false },
        ) { vm.remind() }
    }
}

@Composable
private fun DebtorRow(d: Debtor, checked: Boolean, onCheck: () -> Unit, onOpen: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 12.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onCheck() })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (d.isMerchant) Tag("تاجر", Gold)
                }
                Text(
                    listOfNotNull(d.code, d.phone?.let { "‎$it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
                Text(
                    when (val days = d.daysSincePayment) {
                        null -> "لم يسدد أي دفعة بعد"
                        0 -> "آخر سداد: اليوم"
                        else -> "آخر سداد قبل $days يوم${d.lastPaymentAt?.let { " (${formatDate(it)})" } ?: ""}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if ((d.daysSincePayment ?: Int.MAX_VALUE) >= 60) MaterialTheme.colorScheme.error else Lux.InkSoft,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                d.balances.forEach { b ->
                    val c = Currency.of(b.currency)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(Money.plain(Money.parse(b.balance), c), style = MaterialTheme.typography.titleSmall)
                        CurrencyArt(c.code, height = 18.dp)
                    }
                }
                if (d.balances.size > 1) Text("≈ ${Money.formatBase(d.owedSar)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
        }
    }
}
