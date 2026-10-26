@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.giftcards

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ChipFilter
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.barcode.rememberBarcodeScanner
import com.dejavu.admin.ui.layaway.AmountField
import com.dejavu.admin.ui.layaway.CurrencyChips
import com.dejavu.admin.ui.layaway.CustomerPickerDialog
import com.dejavu.admin.ui.layaway.activeCurrencyList
import com.dejavu.admin.ui.layaway.amountOk
import com.dejavu.admin.ui.layaway.expiryIso
import com.dejavu.admin.ui.layaway.moneyIn
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.GiftCard
import com.dejavu.core.admin.GiftCardCheck
import com.dejavu.core.admin.GiftCardPage
import com.dejavu.core.admin.IssueGiftCardRequest
import com.dejavu.core.admin.SalesExtrasRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private fun cardStatusLabel(s: String?): String = when (s) {
    "ACTIVE" -> "فعالة"
    "REDEEMED" -> "مستخدمة"
    "CANCELLED" -> "ملغاة"
    else -> "الكل"
}

@Composable
private fun cardStatusColor(s: String): Color = when (s) {
    "ACTIVE" -> Success
    "REDEEMED" -> Lux.GoldDeep
    else -> MaterialTheme.colorScheme.error
}

private fun shareText(code: String, value: String) = "بطاقة هدية ديجافو\nالرمز: $code\nالقيمة: $value"

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("gift card", code))
}

private fun shareCard(context: Context, code: String, value: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText(code, value))
    }
    runCatching { context.startActivity(Intent.createChooser(send, "مشاركة البطاقة")) }
}

class GiftCardsViewModel(private val repo: SalesExtrasRepository, private val finance: FinanceRepository) : BaseViewModel() {
    private val _status = MutableStateFlow<String?>("ACTIVE")
    val status = _status.asStateFlow()
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private val _page = MutableStateFlow<Load<GiftCardPage>>(Load.Loading)
    val page = _page.asStateFlow()
    private val _currencies = MutableStateFlow(listOf(Currency.SAR))
    val currencies = _currencies.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** The card just sold, to show its code. */
    private val _issued = MutableStateFlow<GiftCard?>(null)
    val issued = _issued.asStateFlow()

    /** Result of the last code check. */
    private val _checked = MutableStateFlow<GiftCardCheck?>(null)
    val checked = _checked.asStateFlow()
    val refresher = Refresher()
    private var job: Job? = null

    init {
        viewModelScope.launch { _currencies.value = finance.activeCurrencyList() }
    }

    fun setStatus(s: String?) {
        if (s == _status.value) return
        _status.value = s
        _page.value = Load.Loading
        load()
    }

    fun setQuery(q: String) {
        _query.value = q
        load(debounce = true)
    }

    fun load(debounce: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(350)
            val r = repo.giftCards(_status.value, _query.value)
            if (_page.value is Load.Ready) r.fold({ _page.value = Load.Ready(it) }, { say(it.userMessage()) }) else _page.value = r.toLoad()
        }
    }

    fun refresh() = refresher.run(viewModelScope) {
        repo.giftCards(_status.value, _query.value).fold({ _page.value = Load.Ready(it) }, { say(it.userMessage()) })
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

    fun issue(currency: Currency, amount: String, buyer: String, note: String, expiryDays: Int?) = act {
        repo.issueGiftCard(
            IssueGiftCardRequest(
                currency = currency,
                amount = amount,
                buyerName = buyer.trim().ifBlank { null },
                note = note.trim().ifBlank { null },
                expiresAt = expiryDays?.let(::expiryIso),
            ),
        ).orSay()?.let {
            _issued.value = it
            load()
        }
    }

    fun issuedSeen() {
        _issued.value = null
    }

    fun check(code: String) = act {
        _checked.value = null
        repo.checkGiftCard(code).orSay()?.let { _checked.value = it }
    }

    fun clearCheck() {
        _checked.value = null
    }

    fun redeem(code: String, customerId: String, customerName: String, done: () -> Unit) = act {
        repo.redeemGiftCard(code, customerId).orSay()?.let {
            say("أُضيفت ${moneyIn(it.amount, it.currency)} إلى حساب $customerName")
            _checked.value = null
            done()
            load()
        }
    }

    fun cancel(card: GiftCard) = act {
        repo.cancelGiftCard(card.id).orSay()?.let {
            say("أُلغيت البطاقة ${card.code}")
            load()
        }
    }
}

/** Gift cards: sell, check, redeem for a customer, cancel unused ones. */
@Composable
fun GiftCardsScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel { GiftCardsViewModel(it.salesExtras, it.finance) }
    val snackbar = remember { SnackbarHostState() }
    val state by vm.page.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val issued by vm.issued.collectAsStateWithLifecycle()
    val currencies by vm.currencies.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selling by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<GiftCard?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("بطاقات الهدايا", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selling = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("بيع بطاقة") }
                OutlinedButton(onClick = { checking = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("فحص / استخدام بطاقة") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setQuery(it.take(40)) },
                label = { Text("بحث بالرمز أو اسم المشتري") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            ChipFilter(listOf<String?>("ACTIVE", "REDEEMED", "CANCELLED", null), status, ::cardStatusLabel, vm::setStatus)
            LoadContent(state, onRetry = { vm.load() }) { page ->
                RefreshList(refreshing, vm::refresh) {
                    if (page.items.isEmpty() && page.outstanding.isEmpty()) {
                        EmptyList(Icons.Filled.Star, "لا توجد بطاقات", "بيع بطاقة هدية بمبلغ، ويحصل المشتري على رمز يضيف القيمة لحساب من يستخدمه")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (page.outstanding.isNotEmpty()) {
                                item {
                                    InfoCard {
                                        Text("قيمة البطاقات غير المستخدمة", style = MaterialTheme.typography.titleSmall)
                                        page.outstanding.forEach { o ->
                                            SummaryRow("${o.cards} بطاقة", moneyIn(o.amount, o.currency), valueColor = Lux.GoldDeep)
                                        }
                                    }
                                }
                            }
                            if (page.items.isEmpty()) {
                                item { Text("لا توجد بطاقات بهذا الفلتر", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(8.dp)) }
                            }
                            items(page.items, key = { it.id }) { c ->
                                GiftCardRow(
                                    c,
                                    onShare = { shareCard(context, c.code, moneyIn(c.amount, c.currency)) },
                                    onCancel = { cancelling = c },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (selling) {
        SellDialog(currencies, onDismiss = { selling = false }) { c, amount, buyer, note, days -> vm.issue(c, amount, buyer, note, days) }
    }
    issued?.let { card -> IssuedDialog(card, onDismiss = vm::issuedSeen) }
    if (checking) {
        CheckDialog(vm, onDismiss = {
            checking = false
            vm.clearCheck()
        })
    }
    cancelling?.let { c ->
        ConfirmDialog(
            title = "إلغاء البطاقة ${c.code}",
            text = "تُلغى البطاقة (${moneyIn(c.amount, c.currency)}) ولا يمكن استخدامها بعد ذلك. يُعكس قيد بيعها، أي يخرج مبلغها من الصندوق لرده للمشتري.",
            confirm = "إلغاء البطاقة",
            onDismiss = { cancelling = null },
        ) { vm.cancel(c) }
    }
}

@Composable
private fun GiftCardRow(c: GiftCard, onShare: () -> Unit, onCancel: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.code, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text(moneyIn(c.amount, c.currency), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
            }
            StatusChip(cardStatusLabel(c.status), cardStatusColor(c.status))
        }
        val lines = listOfNotNull(
            c.buyerName?.let { "المشتري: $it" },
            "بيعت ${formatDate(c.createdAt)}",
            c.expiresAt?.let { "تنتهي ${formatDate(it)}" },
            c.redeemedBy?.let { r -> "استُخدمت لحساب ${r.name}" + (c.redeemedAt?.let { " · ${formatDateTime(it)}" } ?: "") },
            c.note,
        )
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
        if (c.status == "ACTIVE") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!c.usable) Text("منتهية", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "مشاركة", tint = Lux.GoldDeep) }
                TextButton(onClick = onCancel) { Text("إلغاء", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun SellDialog(currencies: List<Currency>, onDismiss: () -> Unit, onConfirm: (Currency, String, String, String, Int?) -> Unit) {
    var currency by remember { mutableStateOf(currencies.first()) }
    var amount by rememberSaveable { mutableStateOf("") }
    var buyer by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("بيع بطاقة هدية") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currencies.size > 1) CurrencyChips(currencies, currency) { currency = it }
                AmountField(amount, { amount = it }, currency, label = "قيمة البطاقة")
                OutlinedTextField(value = buyer, onValueChange = { buyer = it.take(100) }, label = { Text("اسم المشتري (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it.take(300) }, label = { Text("ملاحظة (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("صلاحية البطاقة", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Int?>(null, 30, 90, 180, 365).forEach { d ->
                        FilterChip(
                            selected = days == d,
                            onClick = { days = d },
                            label = {
                                Text(
                                    when (d) {
                                        null -> "بدون انتهاء"
                                        30 -> "شهر"
                                        90 -> "3 أشهر"
                                        180 -> "6 أشهر"
                                        else -> "سنة"
                                    },
                                )
                            },
                        )
                    }
                }
                Text("يُستلم المبلغ نقداً في صندوقك بعملة البطاقة", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(currency, amount, buyer, note, days) }, enabled = amountOk(amount, currency)) { Text("بيع البطاقة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun IssuedDialog(card: GiftCard, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val value = moneyIn(card.amount, card.currency)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تم بيع البطاقة") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("رمز البطاقة", style = MaterialTheme.typography.labelLarge, color = Lux.InkSoft)
                Text(
                    card.code,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Lux.GoldDeep,
                    textAlign = TextAlign.Center,
                )
                Text(value, style = MaterialTheme.typography.titleLarge)
                card.expiresAt?.let { Text("صالحة حتى ${formatDate(it)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyCode(context, card.code) }) { Text("نسخ الرمز") }
                    Button(onClick = { shareCard(context, card.code, value) }) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" مشاركة")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}

@Composable
private fun CheckDialog(vm: GiftCardsViewModel, onDismiss: () -> Unit) {
    val checked by vm.checked.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val scan = rememberBarcodeScanner { c ->
        code = c.uppercase()
        vm.check(c)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("فحص / استخدام بطاقة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            code = it.uppercase().take(30)
                            vm.clearCheck()
                        },
                        label = { Text("رمز البطاقة") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = scan) { Text("مسح") }
                }
                OutlinedButton(onClick = { vm.check(code) }, enabled = !busy && code.trim().length >= 4, modifier = Modifier.fillMaxWidth()) { Text("فحص") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                checked?.let { r ->
                    InfoCard {
                        Text(r.code, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        SummaryRow("القيمة", moneyIn(r.amount, r.currency), strong = true, valueColor = Lux.GoldDeep)
                        SummaryRow("الحالة", cardStatusLabel(r.status))
                        r.expiresAt?.let { SummaryRow("تنتهي", formatDate(it)) }
                        if (r.usable) {
                            Text("البطاقة صالحة للاستخدام", color = Success, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(r.reason ?: "لا يمكن استخدام هذه البطاقة", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (r.usable) {
                        Button(onClick = { picking = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("اختيار العميل وإضافة الرصيد") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
    if (picking) {
        CustomerPickerDialog(onDismiss = { picking = false }) { customer ->
            val r = checked ?: return@CustomerPickerDialog
            vm.redeem(r.code, customer.id, customer.name) { code = "" }
        }
    }
}
