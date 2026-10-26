@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.layaway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ChipFilter
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.barcode.rememberBarcodeScanner
import com.dejavu.admin.ui.pos.QuickCustomerDialog
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminCustomerSummary
import com.dejavu.core.admin.CompleteLayawayRequest
import com.dejavu.core.admin.CreateLayawayRequest
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.Layaway
import com.dejavu.core.admin.LayawayDeposit
import com.dejavu.core.admin.PosItem
import com.dejavu.core.admin.PosProduct
import com.dejavu.core.admin.PosTender
import com.dejavu.core.admin.PosVariant
import com.dejavu.core.admin.SalesExtrasRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.Numerals
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.QuantityStepper
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// ============================================================ shared helpers ===
// Also used by the gift-card and damaged-goods screens.

/** Typed amount with Latin digits only ("١٢٫٥" → "12.5"). */
internal fun cleanAmount(text: String): String = Numerals.latin(text).replace('٫', '.').filter { it in '0'..'9' || it == '.' }.take(14)

/** A positive amount with no more decimals than [currency] allows. */
internal fun amountOk(text: String, currency: Currency): Boolean =
    text.toBigDecimalOrNull()?.let { it.signum() > 0 && it.stripTrailingZeros().scale() <= currency.decimals } == true

/** "20,000 ر.ي" for an amount in [currency]. */
internal fun moneyIn(amount: String?, currency: Currency): String = Money.plain(Money.parse(amount), currency)

/** The end of the day [days] days from today (store time), as an ISO instant. */
internal fun expiryIso(days: Int): String =
    LocalDate.now(STORE_ZONE).plusDays(days.toLong()).atTime(23, 59).atZone(STORE_ZONE).toInstant().toString()

private val STORE_ZONE: ZoneId = ZoneId.of("Asia/Aden")

/** Active currencies (base first), named the way the server describes them; SAR when they cannot be loaded. */
internal suspend fun FinanceRepository.activeCurrencyList(): List<Currency> =
    activeCurrencies().getOrNull()?.let { cs ->
        val active = cs.currencies.filter { it.isActive }.map { it.code.code }.toSet()
        cs.register().filter { it.code in active }
    }?.ifEmpty { null } ?: listOf(Currency.SAR)

/** One chip per currency. */
@Composable
internal fun CurrencyChips(currencies: List<Currency>, selected: Currency, enabled: Boolean = true, onSelect: (Currency) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        currencies.forEach { c ->
            FilterChip(selected = c == selected, onClick = { onSelect(c) }, enabled = enabled, label = { Text(c.nameAr) })
        }
    }
}

/** Amount field in [currency]. */
@Composable
internal fun AmountField(value: String, onChange: (String) -> Unit, currency: Currency, label: String = "المبلغ", modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(cleanAmount(it)) },
        label = { Text("$label (${currency.symbol})") },
        supportingText = if (currency.decimals == 0) ({ Text("بدون كسور") }) else null,
        isError = value.isNotEmpty() && !amountOk(value, currency),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** A customer chosen in [CustomerPickerDialog]. */
internal data class ChosenCustomer(val id: String, val name: String, val phone: String)

/** Search customers by name, phone or code (or add one quickly) and pick one. */
@Composable
internal fun CustomerPickerDialog(onDismiss: () -> Unit, onPick: (ChosenCustomer) -> Unit) {
    val container = adminContainer()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AdminCustomerSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(300)
        loading = true
        container.finance.customers(query.ifBlank { null }).fold(
            { page ->
                results = page.items.filter { it.code != "CUS-WALKIN" }.take(30)
                error = null
            },
            { e -> error = e.userMessage() },
        )
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختر العميل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(60) },
                    label = { Text("الاسم أو الهاتف أو الرمز") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(results, key = { it.id }) { c ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    onPick(ChosenCustomer(c.id, c.name, c.phone))
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text("${c.phone} · ${c.code}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                        HorizontalDivider()
                    }
                    if (!loading && results.isEmpty()) {
                        item { Text("لا يوجد عملاء مطابقون", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(vertical = 12.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { adding = true }) { Text("عميل جديد") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
    if (adding) {
        QuickCustomerDialog(onDismiss = { adding = false }) { name, phone ->
            scope.launch {
                container.pos.quickCustomer(name, phone).fold(
                    { c ->
                        onDismiss()
                        onPick(ChosenCustomer(c.id, c.name, c.phone))
                    },
                    { e -> error = e.userMessage() },
                )
            }
        }
    }
}

/**
 * Find pieces by name, SKU or barcode (typed or scanned) and pick a size / colour.
 * Stays open so several pieces can be added; [onPick] runs for each. With [requireStock]
 * options with nothing available cannot be picked.
 */
@Composable
internal fun PiecePickerDialog(title: String, onDismiss: () -> Unit, requireStock: Boolean = true, onPick: (PosProduct, PosVariant) -> Unit) {
    val pos = adminContainer().pos
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PosProduct>>(emptyList()) }
    var product by remember { mutableStateOf<PosProduct?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    fun pick(p: PosProduct, v: PosVariant) {
        onPick(p, v)
        added = "أُضيف: ${p.nameAr} (${v.title})"
    }
    val scan = rememberBarcodeScanner { code ->
        scope.launch {
            loading = true
            pos.lookup(code)
                .onSuccess { found ->
                    val v = found.variantId?.let { id -> found.product.variants.firstOrNull { it.id == id } } ?: found.product.variants.singleOrNull()
                    if (v != null && (!requireStock || v.available > 0)) {
                        pick(found.product, v)
                    } else if (v != null) {
                        error = "${found.product.nameAr} (${v.title}): غير متوفر"
                    } else if (found.product.variants.isEmpty()) {
                        error = "لا توجد مقاسات لهذا المنتج"
                    } else {
                        product = found.product
                    }
                }
                .onFailure { e -> error = e.userMessage() }
            loading = false
        }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        pos.products(query).fold(
            { list ->
                results = list.take(40)
                error = null
            },
            { e -> error = e.userMessage() },
        )
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it.take(60)
                            product = null
                        },
                        label = { Text("اسم القطعة أو الباركود") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = scan) { Text("مسح") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                added?.let { Text(it, color = Success, style = MaterialTheme.typography.labelMedium) }
                val p = product
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    if (p != null) {
                        item {
                            Text(
                                "→ ${p.nameAr}",
                                style = MaterialTheme.typography.titleSmall,
                                color = Lux.GoldDeep,
                                modifier = Modifier.fillMaxWidth().clickable { product = null }.padding(vertical = 8.dp),
                            )
                        }
                        items(p.variants, key = { it.id }) { v ->
                            val ok = !requireStock || v.available > 0
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = ok) { pick(p, v) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(v.title, style = MaterialTheme.typography.bodyLarge, color = if (ok) Color.Unspecified else Lux.InkSoft)
                                    Text(
                                        if (v.available > 0) "متاح ${v.available}" else "غير متوفر",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (v.available > 0) Lux.InkSoft else MaterialTheme.colorScheme.error,
                                    )
                                }
                                Text(Money.formatBase(v.price), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                            }
                            HorizontalDivider()
                        }
                    } else {
                        items(results, key = { it.id }) { r ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val only = r.variants.singleOrNull()
                                        when {
                                            r.variants.isEmpty() -> error = "لا توجد مقاسات لهذا المنتج"
                                            only != null && (!requireStock || only.available > 0) -> pick(r, only)
                                            else -> product = r
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ProductImage(r.imageUrl, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                                Column(Modifier.weight(1f)) {
                                    Text(r.nameAr, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${r.variants.size} خيار · متاح ${r.available}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                }
                                Text(Money.formatBase(r.price), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                            }
                            HorizontalDivider()
                        }
                        if (!loading && query.isNotBlank() && results.isEmpty()) {
                            item { Text("لا توجد نتائج", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(vertical = 12.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}

// ================================================================== labels ===

private fun layawayStatusLabel(s: String?): String = when (s) {
    "OPEN" -> "مفتوحة"
    "COMPLETED" -> "مكتملة"
    "CANCELLED" -> "ملغاة"
    else -> "الكل"
}

@Composable
private fun layawayStatusColor(s: String): Color = when (s) {
    "OPEN" -> Warning
    "COMPLETED" -> Success
    else -> MaterialTheme.colorScheme.error
}

/** "20,000 ر.ي (≈ 200 ر.س)" or "لا عربون". */
private fun paidText(l: Layaway): String {
    val c = l.paid.currency ?: return if (Money.isZero(l.paid.sar)) "لا عربون" else Money.formatBase(l.paid.sar)
    return if (c.isBase) moneyIn(l.paid.amount, c) else "${moneyIn(l.paid.amount, c)} (≈ ${Money.formatBase(l.paid.sar)})"
}

// ==================================================================== list ===

class LayawaysViewModel(private val repo: SalesExtrasRepository) : BaseViewModel() {
    private val _status = MutableStateFlow<String?>("OPEN")
    val status = _status.asStateFlow()
    private val _list = MutableStateFlow<Load<List<Layaway>>>(Load.Loading)
    val list = _list.asStateFlow()
    val refresher = Refresher()
    private var job: Job? = null

    fun setStatus(s: String?) {
        if (s == _status.value) return
        _status.value = s
        _list.value = Load.Loading
        load()
    }

    /** Loads quietly when the list is already on screen. */
    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            val r = repo.layaways(_status.value).map { it.items }
            if (_list.value is Load.Ready) r.fold({ _list.value = Load.Ready(it) }, { say(it.userMessage()) }) else _list.value = r.toLoad()
        }
    }

    fun refresh() = refresher.run(viewModelScope) {
        repo.layaways(_status.value).fold({ _list.value = Load.Ready(it.items) }, { say(it.userMessage()) })
    }
}

/** حجز بعربون: the list (open / done / cancelled). */
@Composable
fun LayawaysScreen(session: StaffSession.LoggedIn, onBack: () -> Unit, open: (String) -> Unit, newLayaway: () -> Unit) {
    val vm = adminViewModel { LayawaysViewModel(it.salesExtras) }
    val snackbar = remember { SnackbarHostState() }
    val state by vm.list.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("حجز بعربون", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = newLayaway,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("حجز جديد") },
                containerColor = Lux.GoldDeep,
                contentColor = Color.White,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ChipFilter(listOf<String?>("OPEN", "COMPLETED", "CANCELLED", null), status, ::layawayStatusLabel, vm::setStatus)
            LoadContent(state, onRetry = vm::load) { list ->
                RefreshList(refreshing, vm::refresh) {
                    if (list.isEmpty()) {
                        EmptyList(
                            Icons.Filled.ShoppingCart,
                            "لا توجد حجوزات",
                            "احجز قطعاً لعميل بعربون، وتبقى محجوزة له حتى يكمل المبلغ ويستلمها",
                            "حجز جديد",
                            newLayaway,
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(list, key = { it.id }) { l -> LayawayCard(l) { open(l.id) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayawayCard(l: Layaway, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.number, style = MaterialTheme.typography.titleSmall)
                Text("${l.customer.name} · ${l.customer.phone}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (l.expired) StatusChip("منتهي", MaterialTheme.colorScheme.error) else StatusChip(layawayStatusLabel(l.status), layawayStatusColor(l.status))
        }
        Text(
            "${l.items.sumOf { it.quantity }} قطعة · الإجمالي ${Money.formatBase(l.total)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text("المدفوع: ${paidText(l)}", style = MaterialTheme.typography.bodySmall, color = Success)
        if (l.status == "OPEN") {
            val rest = l.paid.currency?.takeIf { !it.isBase && l.remainingInCurrency != null }?.let { " (${moneyIn(l.remainingInCurrency, it)})" }.orEmpty()
            Text("المتبقي: ${Money.formatBase(l.remaining)}$rest", style = MaterialTheme.typography.bodySmall, color = Lux.GoldDeep, fontWeight = FontWeight.SemiBold)
        }
        l.expiresAt?.let {
            Text(
                if (l.expired) "منتهي منذ ${formatDate(it)}" else "ينتهي ${formatDate(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (l.expired) MaterialTheme.colorScheme.error else Lux.InkSoft,
            )
        }
    }
}

// ================================================================== detail ===

class LayawayViewModel(private val repo: SalesExtrasRepository, private val finance: FinanceRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<Layaway>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _currencies = MutableStateFlow(listOf(Currency.SAR))
    val currencies = _currencies.asStateFlow()
    private var loaded: String? = null

    fun load(id: String) {
        viewModelScope.launch {
            val r = repo.layaway(id)
            if (_state.value is Load.Ready && loaded == id) r.fold({ _state.value = Load.Ready(it) }, { say(it.userMessage()) }) else _state.value = r.toLoad()
            loaded = id
        }
        viewModelScope.launch { _currencies.value = finance.activeCurrencyList() }
    }

    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    fun pay(id: String, currency: Currency, amount: String) = act {
        repo.payLayaway(id, LayawayDeposit(currency, amount)).orSay()?.let {
            _state.value = Load.Ready(it)
            say("تم تسجيل الدفعة ${moneyIn(amount, currency)}")
        }
    }

    fun complete(id: String, tenders: List<PosTender>, method: String, note: String) = act {
        repo.completeLayaway(
            id,
            CompleteLayawayRequest(payments = tenders.ifEmpty { null }, paymentMethod = method, note = note.trim().ifBlank { null }),
        ).orSay()?.let { done ->
            _state.value = Load.Ready(done.layaway)
            val number = ((done.sale as? JsonObject)?.get("number") as? JsonPrimitive)?.contentOrNull
            say(if (number != null) "تم البيع — فاتورة رقم $number" else "تم تسليم القطع وإكمال البيع")
        }
    }

    fun cancel(id: String, reason: String) = act {
        repo.cancelLayaway(id, reason).orSay()?.let {
            _state.value = Load.Ready(it)
            say("أُلغي الحجز، والعربون رصيد في حساب العميل")
        }
    }
}

/** One reservation: items, instalments, add payment, complete the sale, cancel. */
@Composable
fun LayawayScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel(key = "layaway-$id") { LayawayViewModel(it.salesExtras, it.finance) }
    val snackbar = remember { SnackbarHostState() }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val currencies by vm.currencies.collectAsStateWithLifecycle()
    var paying by remember { mutableStateOf(false) }
    var completing by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm, id) {
        vm.load(id)
        onPauseOrDispose {}
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar((state as? Load.Ready)?.value?.number ?: "الحجز", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = { vm.load(id) }, modifier = Modifier.padding(padding)) { l ->
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l.number, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (l.expired) StatusChip("منتهي", MaterialTheme.colorScheme.error) else StatusChip(layawayStatusLabel(l.status), layawayStatusColor(l.status))
                        }
                        Text(l.customer.name, style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(l.customer.phone, l.customer.code).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        Text("أُنشئ ${formatDateTime(l.createdAt)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        l.expiresAt?.let {
                            Text(
                                if (l.expired) "انتهت المهلة ${formatDate(it)}" else "مهلة الاستلام حتى ${formatDate(it)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (l.expired) MaterialTheme.colorScheme.error else Lux.InkSoft,
                            )
                        }
                        l.closedAt?.let { Text("أُغلق ${formatDateTime(it)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                        l.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item {
                    InfoCard {
                        SummaryRow("الإجمالي", Money.formatBase(l.total), strong = true)
                        SummaryRow("المدفوع", paidText(l), valueColor = Success)
                        SummaryRow("المتبقي", Money.formatBase(l.remaining), valueColor = Lux.GoldDeep)
                        val c = l.paid.currency
                        if (c != null && !c.isBase && l.remainingInCurrency != null) {
                            SummaryRow("المتبقي بـ${c.nameAr} (بسعر اليوم)", moneyIn(l.remainingInCurrency, c))
                        }
                    }
                }
                item { Text("القطع المحجوزة", style = MaterialTheme.typography.titleSmall) }
                items(l.items, key = { it.variantId }) { i ->
                    GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProductImage(i.imageUrl, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(i.name.ifBlank { i.sku }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(i.color, i.size, i.sku.ifBlank { null }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Lux.InkSoft,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("× ${i.quantity}", style = MaterialTheme.typography.labelLarge)
                                Text(Money.formatBase(i.unitPrice), style = MaterialTheme.typography.bodySmall, color = Lux.GoldDeep)
                            }
                        }
                    }
                }
                item { Text("الدفعات", style = MaterialTheme.typography.titleSmall) }
                if (l.payments.isEmpty()) {
                    item { Text("لم تُدفع أي دفعة بعد", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                }
                items(l.payments, key = { it.id }) { p ->
                    InfoCard {
                        SummaryRow(formatDateTime(p.createdAt), moneyIn(p.amount, p.currency))
                    }
                }
                if (l.status == "OPEN") {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Button(onClick = { paying = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("دفعة إضافية") }
                            Button(onClick = { completing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("استلام وإكمال البيع") }
                            OutlinedButton(onClick = { cancelling = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("إلغاء الحجز", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                } else if (l.status == "COMPLETED") {
                    item { Text("تم تسليم القطع وتسجيل البيع", style = MaterialTheme.typography.bodyMedium, color = Success) }
                } else {
                    item {
                        Text(
                            "الحجز ملغى، وما دُفع منه بقي رصيداً في حساب العميل",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Lux.InkSoft,
                        )
                    }
                }
            }
            if (paying) {
                val fixed = l.payments.firstOrNull()?.currency ?: l.paid.currency
                DepositDialog(
                    title = "دفعة إضافية",
                    currencies = if (fixed != null) listOf(fixed) else currencies,
                    initialCurrency = fixed ?: currencies.first(),
                    hint = if (fixed != null) "الحجز يُدفع بـ${fixed.nameAr} فقط" else null,
                    initialAmount = { c -> if (c == fixed) l.remainingInCurrency.orEmpty() else if (c.isBase) Money.parse(l.remaining).stripTrailingZeros().toPlainString() else "" },
                    onDismiss = { paying = false },
                ) { c, amount -> vm.pay(l.id, c, amount) }
            }
            if (completing) {
                CompleteDialog(l, currencies, onDismiss = { completing = false }) { tenders, method, note -> vm.complete(l.id, tenders, method, note) }
            }
            if (cancelling) {
                CancelLayawayDialog(l, onDismiss = { cancelling = false }) { reason -> vm.cancel(l.id, reason) }
            }
        }
    }
}

/** Currency (fixed when only one) and amount of a deposit. */
@Composable
private fun DepositDialog(
    title: String,
    currencies: List<Currency>,
    initialCurrency: Currency,
    hint: String?,
    initialAmount: (Currency) -> String,
    onDismiss: () -> Unit,
    onConfirm: (Currency, String) -> Unit,
) {
    var currency by remember { mutableStateOf(initialCurrency) }
    var amount by remember { mutableStateOf(cleanAmount(initialAmount(initialCurrency))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currencies.size > 1) {
                    CurrencyChips(currencies, currency) {
                        currency = it
                        amount = cleanAmount(initialAmount(it))
                    }
                }
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                AmountField(amount, { amount = it }, currency)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(currency, amount) }, enabled = amountOk(amount, currency)) { Text("تسجيل الدفعة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

private class TenderRow(currency: Currency, amount: String) {
    var currency by mutableStateOf(currency)
    var amount by mutableStateOf(amount)
}

/** The money for what the deposits do not cover, then the sale. */
@Composable
private fun CompleteDialog(l: Layaway, currencies: List<Currency>, onDismiss: () -> Unit, onConfirm: (List<PosTender>, String, String) -> Unit) {
    val saleCurrency = l.payments.firstOrNull()?.currency ?: l.paid.currency ?: Currency.SAR
    val owed = Money.parse(l.remaining).signum() > 0
    val suggested = if (saleCurrency.isBase) Money.parse(l.remaining).stripTrailingZeros().toPlainString() else l.remainingInCurrency.orEmpty()
    val rows = remember { mutableStateListOf<TenderRow>().apply { if (owed) add(TenderRow(saleCurrency, cleanAmount(suggested))) } }
    var method by remember { mutableStateOf("CASH") }
    var note by remember { mutableStateOf("") }
    val choices = (listOf(saleCurrency) + currencies).distinct()
    val valid = rows.all { amountOk(it.amount, it.currency) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("استلام وإكمال البيع") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryRow("الإجمالي", Money.formatBase(l.total))
                SummaryRow("المدفوع (من حساب العميل)", paidText(l))
                if (owed) {
                    SummaryRow("المتبقي", Money.formatBase(l.remaining), strong = true)
                    if (!saleCurrency.isBase && l.remainingInCurrency != null) {
                        SummaryRow("بـ${saleCurrency.nameAr} (بسعر اليوم)", moneyIn(l.remainingInCurrency, saleCurrency), strong = true, valueColor = Lux.GoldDeep)
                    }
                    Text("المبلغ المستلم الآن", style = MaterialTheme.typography.titleSmall)
                    rows.forEachIndexed { index, row ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (choices.size > 1) CurrencyChips(choices, row.currency) { row.currency = it }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AmountField(row.amount, { row.amount = it }, row.currency, modifier = Modifier.weight(1f))
                                if (rows.size > 1) {
                                    IconButton(onClick = { rows.removeAt(index) }) { Icon(Icons.Filled.Close, contentDescription = "حذف") }
                                }
                            }
                        }
                    }
                    if (rows.size < 5) {
                        TextButton(onClick = { rows.add(TenderRow(saleCurrency, "")) }) { Text("+ عملة أخرى") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = method == "CASH", onClick = { method = "CASH" })
                        Text("نقداً", Modifier.clickable { method = "CASH" })
                        RadioButton(selected = method == "BANK_TRANSFER", onClick = { method = "BANK_TRANSFER" })
                        Text("تحويل بنكي", Modifier.clickable { method = "BANK_TRANSFER" })
                    }
                } else {
                    Text("الحجز مدفوع بالكامل، تُسلَّم القطع مباشرة", style = MaterialTheme.typography.bodyMedium, color = Success)
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(300) }, label = { Text("ملاحظة (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(rows.map { PosTender(it.currency, it.amount, method) }, method, note)
                },
                enabled = valid,
            ) { Text("إكمال البيع") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun CancelLayawayDialog(l: Layaway, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إلغاء الحجز ${l.number}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تعود القطع إلى المخزون. ما دفعه العميل (${paidText(l)}) لا يُرد نقداً هنا، بل يبقى رصيداً في حساب العميل يستخدمه في مشترياته القادمة.")
                OutlinedTextField(value = reason, onValueChange = { reason = it.take(300) }, label = { Text("السبب") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(reason.trim()) }) { Text("إلغاء الحجز", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

// ===================================================================== new ===

class NewLayawayViewModel(private val repo: SalesExtrasRepository, private val finance: FinanceRepository) : BaseViewModel() {
    internal var customer by mutableStateOf<ChosenCustomer?>(null)
    private var lines by mutableStateOf<List<LayawayLineView>>(emptyList())
    val pieces: List<LayawayLineView> get() = lines
    var currencies by mutableStateOf(listOf(Currency.SAR))
        private set
    var busy by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { currencies = finance.activeCurrencyList() }
    }

    val total: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc + Money.parse(l.price) * BigDecimal(l.qty) }

    fun add(p: PosProduct, v: PosVariant) {
        val have = lines.firstOrNull { it.variantId == v.id }
        lines = if (have == null) {
            lines + LayawayLineView(v.id, p.nameAr, v.title, v.imageUrl ?: p.imageUrl, v.price, v.available, 1)
        } else {
            lines.map { if (it.variantId == v.id) it.copy(qty = (it.qty + 1).coerceAtMost(it.available.coerceAtLeast(1))) else it }
        }
    }

    fun setQty(variantId: String, qty: Int) {
        lines = lines.map { if (it.variantId == variantId) it.copy(qty = qty.coerceAtLeast(1)) else it }
    }

    fun remove(variantId: String) {
        lines = lines.filterNot { it.variantId == variantId }
    }

    fun save(depositCurrency: Currency, depositAmount: String, expiryDays: Int?, note: String, done: (String) -> Unit) {
        val c = customer ?: return say("اختر العميل")
        if (lines.isEmpty()) return say("أضف قطعة واحدة على الأقل")
        if (depositAmount.isNotBlank() && !amountOk(depositAmount, depositCurrency)) return say("مبلغ العربون غير صحيح")
        if (busy) return
        busy = true
        viewModelScope.launch {
            repo.createLayaway(
                CreateLayawayRequest(
                    customerId = c.id,
                    items = lines.map { PosItem(it.variantId, it.qty) },
                    deposit = depositAmount.takeIf { it.isNotBlank() }?.let { LayawayDeposit(depositCurrency, it) },
                    expiresAt = expiryDays?.let(::expiryIso),
                    note = note.trim().ifBlank { null },
                ),
            ).orSay()?.let { done(it.id) }
            busy = false
        }
    }
}

private fun daysLabel(d: Int?): String = if (d == null) "بدون مهلة" else if (d <= 10) "$d أيام" else "$d يوماً"

/** A line of the reservation being written, as the screen shows it. */
data class LayawayLineView(val variantId: String, val name: String, val option: String, val imageUrl: String?, val price: String, val available: Int, val qty: Int)

/** Pick the customer and pieces, take the first deposit. */
@Composable
fun NewLayawayScreen(onBack: () -> Unit, created: (String) -> Unit) {
    val vm = adminViewModel { NewLayawayViewModel(it.salesExtras, it.finance) }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    var pickingCustomer by remember { mutableStateOf(false) }
    var pickingPieces by remember { mutableStateOf(false) }
    var currency by remember { mutableStateOf<Currency?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf<Int?>(7) }
    var note by rememberSaveable { mutableStateOf("") }
    val depositCurrency = currency ?: vm.currencies.first()
    val pieces = vm.pieces
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("حجز جديد", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                InfoCard {
                    Text("العميل", style = MaterialTheme.typography.titleSmall)
                    val c = vm.customer
                    if (c == null) {
                        OutlinedButton(onClick = { pickingCustomer = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  اختر العميل")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                Text(c.phone, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            }
                            TextButton(onClick = { pickingCustomer = true }) { Text("تغيير") }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("القطع (${pieces.sumOf { it.qty }})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickingPieces = true }) { Text("+ إضافة قطعة") }
                }
            }
            if (pieces.isEmpty()) {
                item {
                    Text(
                        "ابحث بالاسم أو امسح الباركود، ثم اختر المقاس واللون",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                    )
                }
            }
            items(pieces, key = { it.variantId }) { p ->
                GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProductImage(p.imageUrl, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.option} · ${Money.formatBase(p.price)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                        IconButton(onClick = { vm.remove(p.variantId) }) { Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("متاح ${p.available}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.weight(1f))
                        QuantityStepper(p.qty, { vm.setQty(p.variantId, it) }, max = p.available.coerceAtLeast(1))
                    }
                }
            }
            item {
                InfoCard {
                    SummaryRow("الإجمالي", Money.formatBase(vm.total.toPlainString()), strong = true, valueColor = Lux.GoldDeep)
                }
            }
            item {
                InfoCard {
                    Text("العربون الآن (اختياري)", style = MaterialTheme.typography.titleSmall)
                    if (vm.currencies.size > 1) CurrencyChips(vm.currencies, depositCurrency) { currency = it }
                    AmountField(amount, { amount = it }, depositCurrency, label = "مبلغ العربون")
                    Text(
                        "الدفعات التالية تكون بنفس العملة، ويُخصم العربون من قيمة البيع عند الاستلام",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lux.InkSoft,
                    )
                }
            }
            item {
                InfoCard {
                    Text("مهلة الاستلام", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf<Int?>(null, 3, 7, 14, 30).forEach { d ->
                            FilterChip(selected = days == d, onClick = { days = d }, label = { Text(daysLabel(d)) })
                        }
                    }
                    days?.let { Text("حتى ${formatDate(expiryIso(it))}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) }
                }
            }
            item {
                OutlinedTextField(value = note, onValueChange = { note = it.take(500) }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Button(
                    onClick = { vm.save(depositCurrency, amount, days, note, created) },
                    enabled = !vm.busy && vm.customer != null && pieces.isNotEmpty() && (amount.isBlank() || amountOk(amount, depositCurrency)),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("حفظ الحجز") }
            }
        }
    }
    if (pickingCustomer) CustomerPickerDialog(onDismiss = { pickingCustomer = false }) { vm.customer = it }
    if (pickingPieces) PiecePickerDialog("إضافة قطعة للحجز", onDismiss = { pickingPieces = false }) { p, v -> vm.add(p, v) }
}
