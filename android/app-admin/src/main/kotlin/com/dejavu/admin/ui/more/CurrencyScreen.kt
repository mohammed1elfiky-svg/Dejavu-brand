@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.CurrencyRequest
import com.dejavu.core.admin.ExchangeRates
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.model.Currencies
import com.dejavu.core.model.Currency
import com.dejavu.core.model.CurrencyRate
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/** A rate: units per 1 SAR, more than 0, up to 6 decimals. */
private fun rateOf(v: String): BigDecimal? = v.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 6 }

private fun plainRate(rate: String?): String = rate?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: "—"

/** The registered currency for a server row (its symbol, name and decimals). */
private val CurrencyRate.currency: Currency get() = Currency.of(code.code)

private val CurrencyRate.title: String get() = nameAr ?: currency.nameAr

class CurrencyViewModel(private val repo: FinanceRepository, private val inventory: InventoryRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<Currencies>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _history = MutableStateFlow<Load<List<ExchangeRates.Entry>>>(Load.Loading)
    val history = _history.asStateFlow()
    private val _uploading = MutableStateFlow(false)
    val uploading = _uploading.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.allCurrencies().toLoad() }
    }

    fun setRate(code: String, rate: String, note: String, done: () -> Unit) = act {
        repo.setRate(Currency.of(code), rate, note).orSay()?.let {
            say("تم تحديث سعر الصرف. العمليات الجديدة ستُحسب به")
            done()
            refresh()
        }
    }

    fun update(code: String, request: CurrencyRequest, message: String = "تم الحفظ", done: () -> Unit = {}) = act {
        repo.updateCurrency(code, request).orSay()?.let {
            say(message)
            done()
            refresh()
        }
    }

    fun create(request: CurrencyRequest, done: () -> Unit) = act {
        repo.createCurrency(request).orSay()?.let {
            say("تمت إضافة ${it.nameAr ?: it.code.code}")
            done()
            refresh()
        }
    }

    /** Uploads a photo of the note / coin and hands back its URL (saved with the currency later). */
    fun uploadPicture(bytes: ByteArray, done: (String) -> Unit) {
        if (_uploading.value) return
        _uploading.value = true
        viewModelScope.launch {
            inventory.uploadImage(bytes).orSay()?.let { done(it.url) }
            _uploading.value = false
        }
    }

    fun loadHistory(code: String) {
        _history.value = Load.Loading
        viewModelScope.launch { _history.value = repo.rateHistory(code).toLoad() }
    }

    private suspend fun refresh() {
        repo.allCurrencies().getOrNull()?.let { _state.value = Load.Ready(it) }
    }

    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block()
            _busy.value = false
        }
    }
}

/** Currencies the store works with, their rates against SAR, and which ones shoppers see. */
@Composable
fun CurrencyScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel { CurrencyViewModel(it.finance, it.inventory) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val canEdit = session.can(Perm.SETTINGS_MANAGE)
    var rateFor by remember { mutableStateOf<CurrencyRate?>(null) }
    var editing by remember { mutableStateOf<CurrencyRate?>(null) }
    var historyFor by remember { mutableStateOf<CurrencyRate?>(null) }
    var adding by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("العملات وأسعار الصرف", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canEdit) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("إضافة عملة") },
                    containerColor = Lux.GoldDeep,
                    contentColor = Color.White,
                )
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { data ->
            val sorted = data.currencies.sortedWith(compareByDescending<CurrencyRate> { it.isBase }.thenBy { it.sortOrder })
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    Text(
                        "كل الأسعار والتكاليف والقيود بالريال السعودي. سعر الصرف = كم وحدة من العملة تساوي 1 ر.س، ويُثبَّت في كل عملية لحظة إنشائها.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                    )
                }
                items(sorted, key = { it.code.code }) { c ->
                    CurrencyCard(
                        c,
                        canEdit = canEdit,
                        busy = busy,
                        onShowInApp = { v ->
                            vm.update(c.code.code, CurrencyRequest(showInCustomerApp = v), if (v) "تظهر الآن في تطبيق العملاء" else "أُخفيت من تطبيق العملاء")
                        },
                        onActive = { v -> vm.update(c.code.code, CurrencyRequest(isActive = v), if (v) "تم تفعيل العملة" else "تم إيقاف العملة") },
                        onRate = { rateFor = c },
                        onEdit = { editing = c },
                        onHistory = {
                            historyFor = c
                            vm.loadHistory(c.code.code)
                        },
                    )
                }
            }
        }
    }

    rateFor?.let { c -> RateDialog(c, busy, onDismiss = { rateFor = null }) { rate, note -> vm.setRate(c.code.code, rate, note) { rateFor = null } } }
    editing?.let { c -> EditCurrencyDialog(c, busy, vm, onDismiss = { editing = null }) { req -> vm.update(c.code.code, req) { editing = null } } }
    historyFor?.let { c -> HistoryDialog(c, vm, onDismiss = { historyFor = null }) }
    if (adding) AddCurrencyDialog(busy, onDismiss = { adding = false }) { req -> vm.create(req) { adding = false } }
}

@Composable
private fun CurrencyCard(
    c: CurrencyRate,
    canEdit: Boolean,
    busy: Boolean,
    onShowInApp: (Boolean) -> Unit,
    onActive: (Boolean) -> Unit,
    onRate: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
) {
    val cur = c.currency
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrencyArt(c.code.code, c.imageUrl, height = 32.dp)
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(c.code.code, c.nameEn).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            }
            if (c.isBase) GoldPill { Text("العملة الأساسية", color = Color.White, style = MaterialTheme.typography.labelMedium) }
            if (!c.isActive) Text("موقوفة", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Lux.Hairline)
        SummaryRow("الرمز", c.symbol ?: cur.symbol)
        SummaryRow("الخانات العشرية", "${c.decimals ?: cur.decimals}")
        if (c.isBase) {
            SummaryRow("سعر الصرف", "الأساس (1)")
        } else if (c.rate != null) {
            SummaryRow("سعر الصرف", "1 ر.س = ${plainRate(c.rate)} ${c.symbol ?: cur.symbol}", strong = true)
            c.updatedAt?.let { SummaryRow("آخر تحديث", formatDateTime(it)) }
            SummaryRow("مثال: 100 ر.س", Money.format("100", cur, c.rate))
        } else {
            Text("لم يُحدَّد سعر صرف بعد؛ لا يمكن التعامل بهذه العملة.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("تظهر في تطبيق العملاء", Modifier.weight(1f))
            Switch(checked = c.showInCustomerApp, onCheckedChange = onShowInApp, enabled = canEdit && !busy && !c.isBase)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("مفعّلة", Modifier.weight(1f))
            Switch(checked = c.isActive, onCheckedChange = onActive, enabled = canEdit && !busy && !c.isBase)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canEdit && !c.isBase) OutlinedButton(onClick = onRate, enabled = !busy) { Text("تعديل السعر") }
            if (canEdit) TextButton(onClick = onEdit, enabled = !busy) { Text("تعديل") }
            if (!c.isBase) TextButton(onClick = onHistory) { Text("السجل") }
        }
    }
}

@Composable
private fun RateDialog(c: CurrencyRate, busy: Boolean, onDismiss: () -> Unit, onSave: (rate: String, note: String) -> Unit) {
    var rate by rememberSaveable { mutableStateOf(c.rate?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var note by rememberSaveable { mutableStateOf("") }
    val value = rateOf(rate)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("سعر ${c.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it.filter { ch -> ch.isDigit() || ch == '.' }.take(20) },
                    label = { Text("كم ${c.symbol ?: c.currency.symbol} يساوي 1 ر.س؟") },
                    isError = rate.isNotBlank() && value == null,
                    supportingText = { Text("حتى 6 خانات عشرية") },
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
                value?.let {
                    Text("100 ر.س = ${Money.format(BigDecimal(100), c.currency, it)}", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                }
                Text(
                    "يُطبَّق على الطلبات والفواتير الجديدة فقط؛ السابقة تبقى بسعرها.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value!!.toPlainString(), note.trim()) }, enabled = value != null && !busy) { Text("حفظ السعر") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun EditCurrencyDialog(c: CurrencyRate, busy: Boolean, vm: CurrencyViewModel, onDismiss: () -> Unit, onSave: (CurrencyRequest) -> Unit) {
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    // The picture: the current one, a new upload, or "" for the built-in drawing.
    var imageUrl by rememberSaveable { mutableStateOf(c.imageUrl.orEmpty()) }
    val picker = rememberImagePicker(maxSide = 800) { bytes -> vm.uploadPicture(bytes) { imageUrl = it } }
    var nameAr by rememberSaveable { mutableStateOf(c.title) }
    var nameEn by rememberSaveable { mutableStateOf(c.nameEn.orEmpty()) }
    var symbol by rememberSaveable { mutableStateOf(c.symbol ?: c.currency.symbol) }
    var decimals by rememberSaveable { mutableStateOf("${c.decimals ?: c.currency.decimals}") }
    var sortOrder by rememberSaveable { mutableStateOf("${c.sortOrder}") }
    val dec = decimals.toIntOrNull()?.takeIf { it in 0..4 }
    val sort = sortOrder.toIntOrNull()
    val valid = nameAr.trim().length >= 2 && symbol.trim().isNotEmpty() && dec != null && sort != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل ${c.code.code}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("الاسم بالعربية", nameAr) { nameAr = it.take(60) }
                TextInput("الاسم بالإنجليزية (اختياري)", nameEn) { nameEn = it.take(60) }
                TextInput("الرمز", symbol) { symbol = it.take(10) }
                TextInput("الخانات العشرية (0–4)", decimals, number = true) { decimals = it }
                TextInput("ترتيب العرض", sortOrder, number = true) { sortOrder = it }
                Text("صورة العملة", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CurrencyArt(c.code.code, imageUrl.ifBlank { null }, height = 44.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = picker.fromGallery, enabled = !uploading && !busy) {
                            if (uploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("رفع صورة")
                        }
                        if (imageUrl.isNotBlank()) TextButton(onClick = { imageUrl = "" }, enabled = !uploading) { Text("استخدام الصورة الافتراضية") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy && !uploading,
                onClick = {
                    onSave(
                        CurrencyRequest(
                            nameAr = nameAr.trim(),
                            nameEn = nameEn.trim().ifBlank { null },
                            symbol = symbol.trim(),
                            decimals = dec,
                            sortOrder = sort,
                            // Only sent when changed; "" removes the uploaded picture.
                            imageUrl = imageUrl.takeIf { it != c.imageUrl.orEmpty() },
                        ),
                    )
                },
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun AddCurrencyDialog(busy: Boolean, onDismiss: () -> Unit, onSave: (CurrencyRequest) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var nameAr by rememberSaveable { mutableStateOf("") }
    var nameEn by rememberSaveable { mutableStateOf("") }
    var symbol by rememberSaveable { mutableStateOf("") }
    var decimals by rememberSaveable { mutableStateOf("2") }
    var rate by rememberSaveable { mutableStateOf("") }
    var showInApp by rememberSaveable { mutableStateOf(false) }
    val codeOk = Regex("^[A-Z][A-Z0-9_]{1,9}$").matches(code)
    val dec = decimals.toIntOrNull()?.takeIf { it in 0..4 }
    val rateValue = rateOf(rate)
    val valid = codeOk && nameAr.trim().length >= 2 && dec != null && (rate.isBlank() || rateValue != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة عملة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("رمز العملة (مثل USD)", code, error = code.isNotBlank() && !codeOk) {
                    code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(10)
                }
                TextInput("الاسم بالعربية", nameAr) { nameAr = it.take(60) }
                TextInput("الاسم بالإنجليزية (اختياري)", nameEn) { nameEn = it.take(60) }
                TextInput("الرمز المختصر (مثل \$)", symbol) { symbol = it.take(10) }
                TextInput("الخانات العشرية (0–4)", decimals, number = true) { decimals = it }
                TextInput("سعر الصرف: كم وحدة = 1 ر.س (اختياري)", rate, decimal = true, error = rate.isNotBlank() && rateValue == null) { rate = it }
                if (rateValue != null && dec != null && codeOk) {
                    val amount = BigDecimal(100).multiply(rateValue).setScale(dec, RoundingMode.HALF_UP).toPlainString()
                    Text("100 ر.س = $amount ${symbol.trim().ifBlank { code }}", style = MaterialTheme.typography.bodySmall, color = Lux.GoldDeep)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("تظهر في تطبيق العملاء", Modifier.weight(1f))
                    Switch(checked = showInApp, onCheckedChange = { showInApp = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    onSave(
                        CurrencyRequest(
                            code = code,
                            nameAr = nameAr.trim(),
                            nameEn = nameEn.trim().ifBlank { null },
                            symbol = symbol.trim().ifBlank { null },
                            decimals = dec,
                            showInCustomerApp = showInApp,
                            rate = rateValue?.toPlainString(),
                        ),
                    )
                },
            ) { Text("إضافة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun HistoryDialog(c: CurrencyRate, vm: CurrencyViewModel, onDismiss: () -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("سجل أسعار ${c.title}") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                when (val h = history) {
                    Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is Load.Failed -> Text(h.message, color = MaterialTheme.colorScheme.error)
                    is Load.Ready -> {
                        if (h.value.isEmpty()) Text("لا يوجد سجل بعد")
                        h.value.forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("1 ر.س = ${plainRate(e.rate)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(formatDateTime(e.effectiveAt), style = MaterialTheme.typography.bodySmall)
                            }
                            val by = listOfNotNull(e.createdBy?.name, e.note).joinToString(" · ")
                            if (by.isNotEmpty()) Text(by, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Lux.Hairline)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

@Composable
private fun TextInput(
    label: String,
    value: String,
    number: Boolean = false,
    decimal: Boolean = false,
    error: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (decimal) v.filter { it.isDigit() || it == '.' } else if (number) v.filter(Char::isDigit) else v) },
        label = { Text(label) },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                decimal -> KeyboardType.Decimal
                number -> KeyboardType.Number
                else -> KeyboardType.Text
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
