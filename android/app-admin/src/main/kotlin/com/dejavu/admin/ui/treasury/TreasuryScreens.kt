@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.treasury

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.pos.LuxButton
import com.dejavu.admin.ui.pos.decimalInput
import com.dejavu.admin.ui.pos.validAmount
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.CashBox
import com.dejavu.core.admin.CashBoxEntry
import com.dejavu.core.admin.CashBoxKind
import com.dejavu.core.admin.CashTransfer
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

// ----------------------------------------------------------------- shared ---

/** Arabic label of a ledger entry's source document. */
internal fun sourceLabel(sourceType: String, note: String?): String = when (sourceType) {
    "SALES_ORDER", "CUSTOMER_PAYMENT" -> "مبيعات/مقبوضات"
    "CASH_TRANSFER" -> "تحويل"
    "WALLET_TOPUP" -> "شحن رصيد عميل"
    "OPENING_BALANCE" -> "رصيد افتتاحي"
    "SALES_RETURN" -> "مرتجع مبيعات"
    "EXPENSE" -> "مصروف"
    "SUPPLIER_PAYMENT" -> "دفعة لمورد"
    "COURIER_SETTLEMENT" -> "توريد شركة شحن"
    else -> note?.takeIf { it.isNotBlank() } ?: sourceType
}

val CashBoxKind.label: String
    get() = when (this) {
        CashBoxKind.CASHIER -> "درج كاشير"
        CashBoxKind.VAULT -> "خزينة"
        CashBoxKind.BANK -> "حساب بنكي"
    }

/** Active currencies with a usable rate (units per 1 SAR), SAR first. */
internal suspend fun FinanceRepository.rateTable(): Result<List<Pair<Currency, BigDecimal>>> =
    activeCurrencies().map { cs ->
        val ordered = cs.register().map { Currency.of(it.code) }
        val rates = cs.currencies.mapNotNull { cr ->
            val c = Currency.of(cr.code.code)
            val r = if (c.isBase) BigDecimal.ONE else cr.rate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            r?.let { c to it }
        }.toMap()
        (listOf(Currency.SAR) + ordered).distinct().mapNotNull { c -> (if (c.isBase) BigDecimal.ONE else rates[c])?.let { c to it } }
    }

/** [amount] of [from] in [to] at the official rates (units per SAR). */
internal fun officialConvert(amount: BigDecimal, from: Currency, rFrom: BigDecimal, to: Currency, rTo: BigDecimal): BigDecimal =
    amount.multiply(rTo).divide(rFrom, to.decimals, RoundingMode.HALF_UP)

private fun plainRate(v: BigDecimal): String = v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

@Composable
internal fun BoxSelector(label: String, options: List<CashBox>, selected: CashBox?, onSelect: (CashBox) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = options.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    selected?.let { "${it.name} · ${it.shownBalance}" } ?: if (options.isEmpty()) "لا توجد صناديق متاحة" else "اختر",
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { b ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(b.name)
                                Text(
                                    listOfNotNull(b.kind.label, b.currency.nameAr, b.user?.name, b.shownBalance).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Lux.InkSoft,
                                )
                            }
                        },
                        onClick = {
                            onSelect(b)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AmountField(value: String, onChange: (String) -> Unit, label: String, currency: Currency, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(decimalInput(it)) },
        label = { Text("$label (${currency.symbol})") },
        isError = value.isNotBlank() && validAmount(value, currency) == null,
        supportingText = if (currency.decimals == 0) ({ Text("بدون كسور") }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun BigDecimal.plainAmount(): String = stripTrailingZeros().toPlainString()

// -------------------------------------------------------------- boxes list ---

class CashBoxesViewModel(
    private val treasury: TreasuryRepository,
    private val finance: FinanceRepository,
    private val manager: Boolean,
) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<CashBox>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _safes = MutableStateFlow<List<CashBox>>(emptyList())
    val safes = _safes.asStateFlow()
    private val _rates = MutableStateFlow<List<Pair<Currency, BigDecimal>>>(listOf(Currency.SAR to BigDecimal.ONE))
    val rates = _rates.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = (if (manager) treasury.all() else treasury.mine()).toLoad() }
        if (!manager) viewModelScope.launch { treasury.safes().onSuccess { _safes.value = it } }
        viewModelScope.launch { finance.rateTable().onSuccess { _rates.value = it } }
    }

    fun transfer(from: CashBox, to: CashBox, amount: String, toAmount: String?, note: String, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            treasury.transfer(from.id, to.id, amount, toAmount, note).orSay()?.let {
                say("تم التحويل ${it.number}: ${Money.plain(Money.parse(it.fromAmount), from.currency)} إلى ${to.name}")
                done()
                _state.value = (if (manager) treasury.all() else treasury.mine()).toLoad()
            }
            _busy.value = false
        }
    }

    fun create(name: String, kind: CashBoxKind, currency: Currency, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            treasury.create(name, kind, currency).orSay()?.let {
                say("تمت إضافة ${it.name}")
                done()
                _state.value = treasury.all().toLoad()
            }
            _busy.value = false
        }
    }
}

/** Every drawer, safe and bank account (managers), or the cashier's own drawers. */
@Composable
fun CashBoxesScreen(session: StaffSession.LoggedIn, onBack: () -> Unit, openBox: (String) -> Unit, openExchange: () -> Unit) {
    val manager = session.can(Perm.CASHBOXES_MANAGE)
    val vm = adminViewModel(key = "boxes:$manager") { CashBoxesViewModel(it.treasury, it.finance, manager) }
    val state by vm.state.collectAsStateWithLifecycle()
    val safes by vm.safes.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var transferring by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar(if (manager) "الصناديق والخزائن" else "صناديقي", onBack) {
                IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { boxes ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item { TotalsCard(boxes) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LuxButton("تحويل / سحب", onClick = { transferring = true }, enabled = boxes.isNotEmpty() && !busy)
                        if (manager) {
                            OutlinedButton(onClick = { creating = true }, shape = RoundedCornerShape(18.dp), modifier = Modifier.height(52.dp)) {
                                Text("إضافة خزينة/حساب بنكي")
                            }
                            OutlinedButton(onClick = openExchange, shape = RoundedCornerShape(18.dp), modifier = Modifier.height(52.dp)) {
                                Text("صرف العملات")
                            }
                        }
                    }
                }
                if (boxes.isEmpty()) item { EmptyState(Icons.Filled.Lock, "لا توجد صناديق") }

                val cashier = boxes.filter { it.kind == CashBoxKind.CASHIER }.groupBy { it.user?.name ?: "بدون كاشير" }
                cashier.forEach { (owner, list) ->
                    item(key = "h:cashier:$owner") { GroupTitle(if (manager) "صناديق الكاشير · $owner" else "أدراجي") }
                    items(list, key = { it.id }) { b -> BoxCard(b, showOwner = false) { openBox(b.id) } }
                }
                val vaults = boxes.filter { it.kind == CashBoxKind.VAULT }
                if (vaults.isNotEmpty()) {
                    item(key = "h:vault") { GroupTitle("الخزائن") }
                    items(vaults, key = { it.id }) { b -> BoxCard(b, showOwner = true) { openBox(b.id) } }
                }
                val banks = boxes.filter { it.kind == CashBoxKind.BANK }
                if (banks.isNotEmpty()) {
                    item(key = "h:bank") { GroupTitle("البنوك") }
                    items(banks, key = { it.id }) { b -> BoxCard(b, showOwner = true) { openBox(b.id) } }
                }
            }

            if (transferring) {
                val active = boxes.filter { it.isActive }
                // A cashier's list is already only their own drawers (cash-boxes/mine).
                val froms = if (manager) active else active.filter { it.kind == CashBoxKind.CASHIER }
                TransferDialog(
                    froms = froms,
                    targets = { from -> (if (manager) active else safes.filter { it.isActive }).filter { it.id != from.id } },
                    rates = rates.toMap(),
                    onDismiss = { transferring = false },
                ) { from, to, amount, toAmount, note -> vm.transfer(from, to, amount, toAmount, note) { transferring = false } }
            }
            if (creating) {
                CreateBoxDialog(rates.map { it.first }, onDismiss = { creating = false }) { name, kind, currency ->
                    vm.create(name, kind, currency) { creating = false }
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun TotalsCard(boxes: List<CashBox>) {
    if (boxes.isEmpty()) return
    val byCurrency = boxes.groupBy { it.currency }
    val book = boxes.fold(BigDecimal.ZERO) { acc, b -> acc.add(Money.parse(b.bookValue)) }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("الإجمالي حسب العملة", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        byCurrency.forEach { (c, list) ->
            val sum = list.fold(BigDecimal.ZERO) { acc, b -> acc.add(Money.parse(b.balance)) }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.nameAr, Modifier.weight(1f))
                Text(Money.plain(sum, c), style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep, fontWeight = FontWeight.Bold)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("القيمة الدفترية", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            GoldPill { Text(Money.formatBase(book.toPlainString()), color = Color.White, style = MaterialTheme.typography.titleSmall) }
        }
    }
}

@Composable
private fun BoxCard(b: CashBox, showOwner: Boolean, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(b.kind.label, b.currency.nameAr, b.user?.name?.takeIf { showOwner }, b.account?.code, "موقوف".takeIf { !b.isActive })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(b.shownBalance, style = MaterialTheme.typography.headlineSmall, color = Lux.GoldDeep, fontWeight = FontWeight.Bold)
                    if (!b.currency.isBase) {
                        Text("دفترياً ${Money.formatBase(b.bookValue)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferDialog(
    froms: List<CashBox>,
    targets: (CashBox) -> List<CashBox>,
    rates: Map<Currency, BigDecimal>,
    onDismiss: () -> Unit,
    onConfirm: (from: CashBox, to: CashBox, amount: String, toAmount: String?, note: String) -> Unit,
) {
    var fromId by rememberSaveable { mutableStateOf(froms.firstOrNull()?.id) }
    val from = froms.firstOrNull { it.id == fromId }
    val tos = from?.let(targets).orEmpty()
    var toId by rememberSaveable { mutableStateOf<String?>(null) }
    val to = tos.firstOrNull { it.id == toId }
    var amount by rememberSaveable { mutableStateOf("") }
    var toAmount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val exchange = from != null && to != null && from.currency != to.currency
    val amountValue = from?.let { validAmount(amount, it.currency) }
    val toValue = to?.let { validAmount(toAmount, it.currency) }
    val valid = from != null && to != null && amountValue != null && (!exchange || toValue != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحويل / سحب") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BoxSelector("من صندوق", froms, from) {
                    fromId = it.id
                    if (toId == it.id) toId = null
                }
                BoxSelector("إلى", tos, to) { toId = it.id }
                if (from != null) {
                    AmountField(amount, { amount = it }, "المبلغ", from.currency)
                    Text("الرصيد المتاح: ${from.shownBalance}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
                if (exchange && from != null && to != null) {
                    AmountField(toAmount, { toAmount = it }, "المبلغ المستلم", to.currency)
                    val rFrom = rates[from.currency]
                    val rTo = rates[to.currency]
                    if (amountValue != null && rFrom != null && rTo != null) {
                        TextButton(onClick = {
                            toAmount = officialConvert(amountValue, from.currency, rFrom, to.currency, rTo).plainAmount()
                        }) { Text("حسب السعر الرسمي") }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(255) },
                    label = { Text("ملاحظة (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (from != null && to != null && amountValue != null) {
                        onConfirm(from, to, amountValue.plainAmount(), if (exchange) toValue?.plainAmount() else null, note.trim())
                    }
                },
                enabled = valid,
            ) { Text("تحويل") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun CreateBoxDialog(currencies: List<Currency>, onDismiss: () -> Unit, onCreate: (String, CashBoxKind, Currency) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(CashBoxKind.VAULT) }
    var currency by remember { mutableStateOf(currencies.firstOrNull() ?: Currency.SAR) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة خزينة أو حساب بنكي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("النوع", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(CashBoxKind.VAULT, CashBoxKind.BANK).forEach { k ->
                        FilterChip(selected = k == kind, onClick = { kind = k }, label = { Text(k.label) })
                    }
                }
                Text("العملة", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.forEach { c -> FilterChip(selected = c == currency, onClick = { currency = c }, label = { Text(c.nameAr) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim(), kind, currency) }, enabled = name.trim().length >= 2) { Text("إضافة") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

// --------------------------------------------------------------- one box ---

class CashBoxViewModel(private val id: String, private val treasury: TreasuryRepository, private val manager: Boolean) : BaseViewModel() {
    private val _box = MutableStateFlow<Load<CashBox>>(Load.Loading)
    val box = _box.asStateFlow()
    private val _entries = MutableStateFlow<List<CashBoxEntry>>(emptyList())
    val entries = _entries.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()
    private var page = 1

    fun load() {
        if (_box.value !is Load.Ready) _box.value = Load.Loading
        viewModelScope.launch {
            val found = findBox()
            _box.value = found.toLoad()
            if (found.isSuccess) fetch(reset = true)
        }
    }

    private suspend fun findBox(): Result<CashBox> {
        val lists = if (manager) listOf(treasury.all()) else listOf(treasury.mine(), treasury.safes())
        lists.forEach { r -> r.getOrNull()?.firstOrNull { it.id == id }?.let { return Result.success(it) } }
        val failure = lists.firstNotNullOfOrNull { it.exceptionOrNull() }
        return Result.failure(failure ?: IllegalStateException("الصندوق غير موجود أو لا تملك صلاحية عرضه"))
    }

    fun more() {
        if (_loadingMore.value) return
        viewModelScope.launch { fetch(reset = false) }
    }

    private suspend fun fetch(reset: Boolean) {
        val next = if (reset) 1 else page + 1
        _loadingMore.value = true
        treasury.entries(id, next).fold(
            { p ->
                page = next
                _entries.value = (if (reset) emptyList() else _entries.value) + p.items
                _hasMore.value = p.hasMore
            },
            { e -> say(e.userMessage()) },
        )
        _loadingMore.value = false
    }
}

/** One drawer, safe or bank: balance and its movements. */
@Composable
fun CashBoxScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val manager = session.can(Perm.CASHBOXES_MANAGE)
    val vm = adminViewModel(key = "box:$id") { CashBoxViewModel(id, it.treasury, manager) }
    val state by vm.box.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar((state as? Load.Ready)?.value?.name ?: "الصندوق", onBack) {
                IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { box ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            listOfNotNull(box.kind.label, box.currency.nameAr, box.user?.name, box.account?.code).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("الرصيد الحالي", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                        Text(box.shownBalance, style = MaterialTheme.typography.headlineMedium, color = Lux.GoldDeep, fontWeight = FontWeight.Bold)
                        SummaryRow("القيمة الدفترية", Money.formatBase(box.bookValue))
                        if (!box.isActive) Text("هذا الصندوق موقوف", color = MaterialTheme.colorScheme.error)
                    }
                }
                item { Text("الحركات", style = MaterialTheme.typography.titleMedium) }
                if (entries.isEmpty() && !loadingMore) item { Text("لا توجد حركات بعد", color = Lux.InkSoft) }
                items(entries, key = { it.id }) { e -> EntryRow(e, box.currency) }
                if (loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (hasMore && !loadingMore) {
                    item { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text("عرض المزيد") } }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: CashBoxEntry, currency: Currency) {
    val v = Money.parse(e.amount)
    val tint = if (v.signum() >= 0) Success else MaterialTheme.colorScheme.error
    GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sourceLabel(e.sourceType, e.note), style = MaterialTheme.typography.titleSmall)
                val sub = listOfNotNull(e.note?.takeIf { it.isNotBlank() && it != sourceLabel(e.sourceType, e.note) }, formatDateTime(e.createdAt))
                Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((if (v.signum() > 0) "+" else "−") + Money.plain(v.abs(), currency), style = MaterialTheme.typography.titleSmall, color = tint)
                if (!currency.isBase) Text(Money.formatBase(Money.parse(e.baseAmount).abs().toPlainString()), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
        }
    }
}

// --------------------------------------------------------------- exchange ---

class ExchangeViewModel(private val treasury: TreasuryRepository, private val finance: FinanceRepository) : BaseViewModel() {
    private val _boxes = MutableStateFlow<Load<List<CashBox>>>(Load.Loading)
    val boxes = _boxes.asStateFlow()
    private val _rates = MutableStateFlow<List<Pair<Currency, BigDecimal>>>(listOf(Currency.SAR to BigDecimal.ONE))
    val rates = _rates.asStateFlow()
    private val _history = MutableStateFlow<List<CashTransfer>>(emptyList())
    val history = _history.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var page = 1

    fun load() {
        if (_boxes.value !is Load.Ready) _boxes.value = Load.Loading
        viewModelScope.launch { _boxes.value = treasury.all().toLoad() }
        viewModelScope.launch { finance.rateTable().onSuccess { _rates.value = it } }
        viewModelScope.launch { fetch(reset = true) }
    }

    fun more() {
        viewModelScope.launch { fetch(reset = false) }
    }

    private suspend fun fetch(reset: Boolean) {
        val next = if (reset) 1 else page + 1
        treasury.transfers(exchangesOnly = true, page = next).fold(
            { p ->
                page = next
                _history.value = (if (reset) emptyList() else _history.value) + p.items.filter { it.isExchange }
                _hasMore.value = p.hasMore
            },
            { e -> say(e.userMessage()) },
        )
    }

    fun exchange(from: CashBox, to: CashBox, sold: String, received: String, note: String, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            treasury.transfer(from.id, to.id, sold, received, note).orSay()?.let { t ->
                val diff = Money.parse(t.fxDifference)
                say(
                    "تم الصرف ${t.number}" + when {
                        diff.signum() > 0 -> " · ربح ${Money.formatBase(t.fxDifference)}"
                        diff.signum() < 0 -> " · خسارة ${Money.formatBase(diff.abs().toPlainString())}"
                        else -> ""
                    },
                )
                done()
                _boxes.value = treasury.all().toLoad()
                fetch(reset = true)
            }
            _busy.value = false
        }
    }
}

/** Selling one currency for another between two boxes, with the realised FX gain or loss. */
@Composable
fun ExchangeScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    if (!session.can(Perm.CASHBOXES_MANAGE)) {
        Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("صرف وتداول العملات", onBack) }) { padding ->
            EmptyState(Icons.Filled.Lock, "لا تملك صلاحية صرف العملات", "هذه الشاشة لمديري الصناديق فقط", modifier = Modifier.padding(padding))
        }
        return
    }
    val vm = adminViewModel { ExchangeViewModel(it.treasury, it.finance) }
    val state by vm.boxes.collectAsStateWithLifecycle()
    val rateList by vm.rates.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var fromId by rememberSaveable { mutableStateOf<String?>(null) }
    var toId by rememberSaveable { mutableStateOf<String?>(null) }
    var sold by rememberSaveable { mutableStateOf("") }
    var received by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("صرف وتداول العملات", onBack) {
                IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { all ->
            val rates = rateList.toMap()
            val active = all.filter { it.isActive }
            val from = active.firstOrNull { it.id == fromId }
            val targets = active.filter { from == null || it.currency != from.currency }
            val to = targets.firstOrNull { it.id == toId }
            val soldValue = from?.let { validAmount(sold, it.currency) }
            val receivedValue = to?.let { validAmount(received, it.currency) }
            val rFrom = from?.let { rates[it.currency] }
            val rTo = to?.let { rates[it.currency] }

            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    InfoCard {
                        Text("أسعار الصرف الرسمية", style = MaterialTheme.typography.titleSmall)
                        rateList.filter { !it.first.isBase }.forEach { (c, r) ->
                            SummaryRow("${c.nameAr} (${c.code})", "1 ر.س = ${Money.plain(r, c)}")
                        }
                        if (rateList.none { !it.first.isBase }) Text("لا توجد عملات أخرى مفعّلة", color = Lux.InkSoft)
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("عملية صرف جديدة", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            BoxSelector("من صندوق (العملة المباعة)", active, from) {
                                fromId = it.id
                                if (to != null && to.currency == it.currency) toId = null
                            }
                            BoxSelector("إلى صندوق (العملة المستلمة)", targets.filter { it.id != fromId }, to) { toId = it.id }
                            if (from != null) {
                                AmountField(sold, { sold = it }, "المبلغ المباع", from.currency)
                                Text("المتاح: ${from.shownBalance}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            }
                            if (to != null) {
                                AmountField(received, { received = it }, "المبلغ المستلم", to.currency)
                            }
                            if (from != null && to != null && soldValue != null && rFrom != null && rTo != null) {
                                TextButton(onClick = {
                                    received = officialConvert(soldValue, from.currency, rFrom, to.currency, rTo).plainAmount()
                                }) { Text("حسب السعر الرسمي") }
                            }
                            if (from != null && to != null && rFrom != null && rTo != null) {
                                RateComparison(from.currency, rFrom, to.currency, rTo, soldValue, receivedValue)
                            }
                            OutlinedTextField(
                                value = note,
                                onValueChange = { note = it.take(255) },
                                label = { Text("ملاحظة (اختياري)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            LuxButton(
                                "تنفيذ الصرف",
                                onClick = {
                                    if (from != null && to != null && soldValue != null && receivedValue != null) {
                                        vm.exchange(from, to, soldValue.plainAmount(), receivedValue.plainAmount(), note.trim()) {
                                            sold = ""
                                            received = ""
                                            note = ""
                                        }
                                    }
                                },
                                enabled = !busy && from != null && to != null && soldValue != null && receivedValue != null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                item { Text("آخر عمليات الصرف", style = MaterialTheme.typography.titleMedium) }
                if (history.isEmpty()) item { Text("لا توجد عمليات صرف بعد", color = Lux.InkSoft) }
                items(history, key = { it.id }) { t -> ExchangeRow(t) }
                if (hasMore) item { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text("عرض المزيد") } }
            }
        }
    }
}

@Composable
private fun RateComparison(from: Currency, rFrom: BigDecimal, to: Currency, rTo: BigDecimal, sold: BigDecimal?, received: BigDecimal?) {
    val official = rTo.divide(rFrom, 8, RoundingMode.HALF_UP)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SummaryRow("السعر الرسمي", "1 ${from.symbol} = ${plainRate(official)} ${to.symbol}")
        if (sold != null && received != null) {
            val implied = received.divide(sold, 8, RoundingMode.HALF_UP)
            SummaryRow("سعر التنفيذ", "1 ${from.symbol} = ${plainRate(implied)} ${to.symbol}", strong = true, valueColor = Lux.GoldDeep)
            // The same deal expressed against 1 SAR for the non-base side.
            when {
                from.isBase -> SummaryRow("مقابل 1 ر.س", "${plainRate(implied)} ${to.symbol} (الرسمي ${plainRate(rTo)})")
                to.isBase -> SummaryRow(
                    "مقابل 1 ر.س",
                    "${plainRate(sold.divide(received, 8, RoundingMode.HALF_UP))} ${from.symbol} (الرسمي ${plainRate(rFrom)})",
                )
                else -> {
                    val soldSar = Money.toBase(sold, from, rFrom)
                    if (soldSar.signum() > 0) {
                        SummaryRow("مقابل 1 ر.س", "${plainRate(received.divide(soldSar, 8, RoundingMode.HALF_UP))} ${to.symbol} (الرسمي ${plainRate(rTo)})")
                    }
                }
            }
            val diff = Money.toBase(received, to, rTo).subtract(Money.toBase(sold, from, rFrom))
            if (diff.signum() != 0) {
                SummaryRow(
                    if (diff.signum() > 0) "ربح تقديري حسب السعر الرسمي" else "خسارة تقديرية حسب السعر الرسمي",
                    Money.formatBase(diff.abs().toPlainString()),
                    valueColor = if (diff.signum() > 0) Success else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ExchangeRow(t: CashTransfer) {
    val diff = Money.parse(t.fxDifference)
    GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.number, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(formatDateTime(t.createdAt), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        Text("${t.fromBox.name} ← ${t.toBox.name}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Money.plain(Money.parse(t.fromAmount), t.fromBox.currency), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(6.dp))
            Text("مقابل", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            Spacer(Modifier.width(6.dp))
            Text(Money.plain(Money.parse(t.toAmount), t.toBox.currency), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
        }
        if (diff.signum() != 0) {
            SummaryRow(
                if (diff.signum() > 0) "ربح فروق العملة" else "خسارة فروق العملة",
                Money.formatBase(diff.abs().toPlainString()),
                valueColor = if (diff.signum() > 0) Success else MaterialTheme.colorScheme.error,
            )
        } else {
            Text("بدون فروق عملة", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        t.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
    }
}
