@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.purchasing

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.CashBox
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.SupplierAccount
import com.dejavu.core.admin.SupplierCommissionRequest
import com.dejavu.core.admin.SupplierKind
import com.dejavu.core.admin.SupplierOpeningBalanceRequest
import com.dejavu.core.admin.SupplierPaymentVoucherRequest
import com.dejavu.core.admin.SupplierVoucher
import com.dejavu.core.admin.SupplierVoucherKind
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** A typed positive-or-zero number, or null. */
private fun accNum(text: String?): BigDecimal? = text?.trim()?.replace(',', '.')?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

private fun accPlain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

/** "1,200 CNY" in the amount's own currency. */
private fun inCurrency(amount: String?, currency: String?): String = Money.plain(Money.parse(amount), Currency.of(currency?.ifBlank { null } ?: "USD"))

/** yyyy-MM-dd (or an ISO timestamp) → a date, or null. */
private fun dayOf(text: String?): LocalDate? = text?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun sourceLabel(type: String) = when (type) {
    "PURCHASE_ORDER" -> "طلبية"
    "SHORTAGE" -> "نقص في الاستلام"
    "VOUCHER" -> "سند"
    else -> type
}

/** Suppliers are dealt with in yuan and dollars only; the server works out the SAR value. */
private val OPENING_CURRENCIES = listOf("CNY", "USD")

class SupplierAccountViewModel(
    private val supplierId: String,
    private val repo: PurchasingRepository,
    private val treasury: TreasuryRepository,
    private val finance: FinanceRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<SupplierAccount>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _name = MutableStateFlow<String?>(null)
    val name = _name.asStateFlow()
    private val _shipping = MutableStateFlow(false)

    /** A shipping company: its account is in dollars (freight and customs), no yuan rate or commission. */
    val shipping = _shipping.asStateFlow()
    private val _boxes = MutableStateFlow<List<CashBox>>(emptyList())

    /** Active USD boxes the payment can come from. */
    val boxes = _boxes.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _art = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Uploaded currency pictures by code (others use the built-in art). */
    val art = _art.asStateFlow()

    fun load() {
        viewModelScope.launch { _state.value = repo.supplierAccount(supplierId).toLoad() }
        viewModelScope.launch {
            repo.supplier(supplierId).getOrNull()?.let { s ->
                _name.value = s.name
                // The supplier file does not carry the kind: look it up among the shipping companies.
                repo.suppliers(s.name, kind = SupplierKind.SHIPPING_COMPANY).getOrNull()?.let { page ->
                    _shipping.value = page.items.any { it.id == supplierId }
                }
            }
        }
        viewModelScope.launch {
            treasury.all(currency = Currency.of("USD")).getOrNull()?.let { list ->
                _boxes.value = list.filter { it.isActive && it.currency.code == "USD" }
            }
        }
        viewModelScope.launch {
            finance.activeCurrencies().getOrNull()?.let { cs ->
                _art.value = cs.currencies.mapNotNull { c -> c.imageUrl?.ifBlank { null }?.let { c.code.code to it } }.toMap()
            }
        }
    }

    /** Balance from before the system; [weOwe] = we owe the supplier, else the supplier owes us. */
    fun openingBalance(currency: String, amount: String, weOwe: Boolean, sarAmount: String?, foreignPerUsd: String?, note: String, done: () -> Unit) = act {
        val request = SupplierOpeningBalanceRequest(
            currency = currency,
            amount = amount,
            direction = if (weOwe) "WE_OWE" else "SUPPLIER_OWES",
            sarAmount = sarAmount,
            foreignPerUsd = if (currency == "CNY") foreignPerUsd else null,
            note = note.trim().ifBlank { null },
        )
        repo.supplierOpeningBalance(supplierId, request).orSay()?.let {
            say("تم تسجيل الرصيد الافتتاحي")
            done()
            refresh()
        }
    }

    fun pay(editId: String?, usd: String, boxId: String, rate: String?, currency: String, applyTo: String, note: String, done: () -> Unit) = act {
        val request = SupplierPaymentVoucherRequest(
            supplierId = supplierId,
            usdAmount = usd,
            cashBoxId = boxId,
            foreignPerUsd = if (applyTo == "GOODS") rate else null,
            foreignCurrency = if (applyTo == "GOODS") currency else null,
            applyTo = applyTo,
            note = note.trim().ifBlank { null },
        )
        repo.paymentVoucher(request, editId).orSay()?.let {
            say(if (editId == null) "تم تسجيل سند الصرف ${it.number}" else "تم تعديل سند الصرف ${it.number}")
            done()
            refresh()
        }
    }

    fun commission(editId: String?, usd: String, from: LocalDate, to: LocalDate, note: String, done: () -> Unit) = act {
        val request = SupplierCommissionRequest(supplierId, usd, from.toString(), to.toString(), note.trim().ifBlank { null })
        repo.commissionVoucher(request, editId).orSay()?.let {
            say(if (editId == null) "تمت إضافة العمولة ${it.number}" else "تم تعديل العمولة ${it.number}")
            done()
            refresh()
        }
    }

    fun cancel(v: SupplierVoucher) = act {
        repo.cancelVoucher(v.id).orSay()?.let {
            say("تم حذف السند ${v.number}")
            refresh()
        }
    }

    private suspend fun refresh() {
        repo.supplierAccount(supplierId).getOrNull()?.let { _state.value = Load.Ready(it) }
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

/** The supplier's own statement (yuan and dollars): orders, shortages, payment and commission vouchers. */
@Composable
fun SupplierAccountScreen(supplierId: String, session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel(key = "supplier-account:$supplierId") { SupplierAccountViewModel(supplierId, it.purchasing, it.treasury, it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val shipping by vm.shipping.collectAsStateWithLifecycle()
    val boxes by vm.boxes.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val art by vm.art.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val canPay = session.can(Perm.SUPPLIER_PAY)
    val canOpening = canPay && session.can("journal.write")
    var openingOpen by remember { mutableStateOf(false) }
    var paymentOpen by remember { mutableStateOf(false) }
    var paymentEdit by remember { mutableStateOf<SupplierVoucher?>(null) }
    var commissionOpen by remember { mutableStateOf(false) }
    var commissionEdit by remember { mutableStateOf<SupplierVoucher?>(null) }
    var deleting by remember { mutableStateOf<SupplierVoucher?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(name?.let { "كشف حساب $it" } ?: if (shipping) "كشف حساب شركة الشحن" else "كشف حساب المورد", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { account ->
            val foreign = account.balances.firstOrNull { it.currency != "USD" }?.currency
                ?: account.vouchers.firstNotNullOfOrNull { v -> v.foreignCurrency?.takeIf { it != "USD" } }
                ?: "CNY"
            val party = if (shipping) "شركة الشحن" else "المورد"
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("الرصيد مع $party", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                            StatusChip(if (shipping) "شركة شحن · بالدولار" else "مورد · باليوان", if (shipping) Lux.InkSoft else Lux.GoldDeep)
                        }
                        if (shipping) {
                            Text("يُسجَّل عليها الشحن الخارجي والجمارك عند وصول البضاعة، وتُسدَّد بسند صرف بالدولار", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                        if (account.balances.isEmpty()) {
                            Text("لا توجد حركات بعد", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            account.balances.forEach { b ->
                                val sign = Money.parse(b.balance).signum()
                                val amount = inCurrency(Money.parse(b.balance).abs().toPlainString(), b.currency)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CurrencyArt(b.currency, art[b.currency], height = 20.dp)
                                    when {
                                        sign > 0 -> StatusChip("$amount · مستحق لـ$party", Lux.GoldDeep)
                                        sign < 0 -> StatusChip("$amount · مدفوع زيادة", Success)
                                        else -> StatusChip("${b.currency} · مسدَّد", Lux.InkSoft)
                                    }
                                }
                            }
                        }
                        if (!shipping) account.lastRate?.let { SummaryRow("آخر سعر للدولار (من سندات الصرف)", "$it $foreign") }
                        if (canPay) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                Button(onClick = { paymentEdit = null; paymentOpen = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (shipping) "سند صرف بالدولار" else "سند صرف") }
                                if (!shipping) {
                                    OutlinedButton(onClick = { commissionEdit = null; commissionOpen = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("إضافة عمولة (مطابقة)") }
                                }
                            }
                        }
                        if (canOpening) {
                            OutlinedButton(onClick = { openingOpen = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("رصيد افتتاحي") }
                        }
                    }
                }
                if (account.vouchers.isNotEmpty()) {
                    item { Text("السندات", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep) }
                }
                items(account.vouchers, key = { "v:" + it.id }) { v ->
                    VoucherCard(
                        v,
                        art = art,
                        canEdit = canPay && !v.isCancelled && !busy,
                        onEdit = {
                            if (v.kind == SupplierVoucherKind.PAYMENT) {
                                paymentEdit = v
                                paymentOpen = true
                            } else {
                                commissionEdit = v
                                commissionOpen = true
                            }
                        },
                        onDelete = { deleting = v },
                    )
                }
                item { Text("حركات الحساب", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep) }
                if (account.entries.isEmpty()) {
                    item { Text("لا توجد حركات", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                }
                items(account.entries, key = { "e:" + it.id }) { e ->
                    val sign = Money.parse(e.amount).signum()
                    GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.note?.ifBlank { null } ?: sourceLabel(e.sourceType), style = MaterialTheme.typography.bodyMedium)
                                Text("${formatDate(e.createdAt)} · ${sourceLabel(e.sourceType)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                            }
                            CurrencyArt(e.currency, art[e.currency], height = 18.dp, modifier = Modifier.padding(end = 6.dp))
                            Text(
                                (if (sign > 0) "+" else if (sign < 0) "−" else "") + inCurrency(Money.parse(e.amount).abs().toPlainString(), e.currency),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (sign > 0) Lux.GoldDeep else Success,
                            )
                        }
                    }
                }
            }

            if (paymentOpen) {
                // A shipping company is paid in dollars against its USD balance (no yuan rate, no commission).
                val payCurrency = if (shipping) "USD" else paymentEdit?.foreignCurrency ?: foreign
                PaymentVoucherDialog(
                    edit = paymentEdit,
                    lastRate = account.lastRate,
                    currency = payCurrency,
                    boxes = boxes,
                    busy = busy,
                    shipping = shipping,
                    onDismiss = { paymentOpen = false },
                ) { usd, boxId, rate, applyTo, note ->
                    vm.pay(paymentEdit?.id, usd, boxId, rate, payCurrency, applyTo, note) { paymentOpen = false }
                }
            }
            if (commissionOpen) {
                CommissionDialog(edit = commissionEdit, busy = busy, onDismiss = { commissionOpen = false }) { usd, from, to, note ->
                    vm.commission(commissionEdit?.id, usd, from, to, note) { commissionOpen = false }
                }
            }
            if (openingOpen) {
                OpeningBalanceDialog(
                    currencies = OPENING_CURRENCIES,
                    initial = if (shipping) "USD" else foreign,
                    party = party,
                    lastRate = account.lastRate,
                    art = art,
                    busy = busy,
                    onDismiss = { openingOpen = false },
                ) { currency, amount, weOwe, sar, rate, note ->
                    vm.openingBalance(currency, amount, weOwe, sar, rate, note) { openingOpen = false }
                }
            }
            deleting?.let { v ->
                AlertDialog(
                    onDismissRequest = { deleting = null },
                    title = { Text("حذف السند ${v.number}") },
                    text = { Text("سيُلغى السند ويُعكس أثره على حساب المورد${if (v.kind == SupplierVoucherKind.PAYMENT) " والصندوق" else ""}. هل أنت متأكد؟") },
                    confirmButton = {
                        TextButton(onClick = { deleting = null; vm.cancel(v) }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { deleting = null }) { Text("تراجع") } },
                )
            }
        }
    }
}

@Composable
private fun VoucherCard(v: SupplierVoucher, art: Map<String, String>, canEdit: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().alpha(if (v.isCancelled) 0.5f else 1f), elevation = 6.dp, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        v.number,
                        style = MaterialTheme.typography.titleSmall,
                        textDecoration = if (v.isCancelled) TextDecoration.LineThrough else null,
                    )
                    StatusChip(if (v.kind == SupplierVoucherKind.PAYMENT) "سند صرف" else "عمولة", if (v.kind == SupplierVoucherKind.PAYMENT) Lux.GoldDeep else Lux.InkSoft)
                    if (v.isCancelled) StatusChip("محذوف", MaterialTheme.colorScheme.error)
                }
                Text(formatDate(v.createdAt), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
            CurrencyArt("USD", art["USD"], height = 18.dp, modifier = Modifier.padding(end = 6.dp))
            Text(inCurrency(v.usdAmount, "USD"), style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
        }
        if (v.kind == SupplierVoucherKind.PAYMENT) {
            if (v.foreignCurrency == "USD") {
                Text("سداد من الرصيد بالدولار", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            } else if (v.foreignPerUsd != null) {
                Text(
                    "سعر الدولار ${v.foreignPerUsd} ${v.foreignCurrency.orEmpty()} · ${inCurrency(v.foreignAmount, v.foreignCurrency)} من البضاعة",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            } else {
                Text("سداد للعمولة", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
        } else if (v.periodFrom != null || v.periodTo != null) {
            Text("الفترة من ${v.periodFrom?.take(10).orEmpty()} إلى ${v.periodTo?.take(10).orEmpty()}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        v.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (canEdit) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "تعديل", tint = Lux.GoldDeep) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun AmountField(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(14)) },
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        isError = value.isNotBlank() && accNum(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** New or corrected payment voucher: dollars from a USD box, at the day's yuan rate. */
@Composable
private fun PaymentVoucherDialog(
    edit: SupplierVoucher?,
    lastRate: String?,
    currency: String,
    boxes: List<CashBox>,
    busy: Boolean,
    onDismiss: () -> Unit,
    shipping: Boolean = false,
    onSave: (usd: String, boxId: String, rate: String?, applyTo: String, note: String) -> Unit,
) {
    var usd by remember { mutableStateOf(edit?.usdAmount?.let { accNum(it)?.let(::accPlain) }.orEmpty()) }
    var rate by remember { mutableStateOf((edit?.foreignPerUsd ?: lastRate)?.let { accNum(it)?.let(::accPlain) }.orEmpty()) }
    var boxId by remember { mutableStateOf(edit?.cashBoxId ?: boxes.singleOrNull()?.id) }
    var goods by remember { mutableStateOf(edit == null || edit.foreignPerUsd != null) }
    var note by remember { mutableStateOf(edit?.note.orEmpty()) }
    val usdValue = accNum(usd)?.takeIf { it.signum() > 0 }
    val rateValue = accNum(rate)?.takeIf { it.signum() > 0 }
    val ok = usdValue != null && boxId != null && (shipping || !goods || rateValue != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit == null) (if (shipping) "سند صرف لشركة الشحن" else "سند صرف للمورد") else "تعديل سند الصرف ${edit.number}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField("المبلغ بالدولار", usd, Modifier.fillMaxWidth()) { usd = it }
                if (shipping) {
                    Text("يُخصم المبلغ من رصيد شركة الشحن بالدولار (الشحن الخارجي والجمارك)", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    usdValue?.let { SummaryRow("يُخصم من حساب شركة الشحن", inCurrency(accPlain(it), "USD"), strong = true, valueColor = Lux.GoldDeep) }
                } else {
                    Text("تطبيق على:", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = goods, onClick = { goods = true }, label = { Text("البضاعة") })
                        FilterChip(selected = !goods, onClick = { goods = false }, label = { Text("العمولة") })
                    }
                    if (goods) {
                        AmountField("سعر الدولار ($currency لكل 1 دولار)", rate, Modifier.fillMaxWidth(), hint = lastRate?.let { "آخر سعر: $it" }) { rate = it }
                        if (usdValue != null && rateValue != null) {
                            SummaryRow("يُخصم من حساب البضاعة", inCurrency(usdValue.multiply(rateValue).setScale(2, RoundingMode.HALF_UP).toPlainString(), currency), strong = true, valueColor = Lux.GoldDeep)
                        }
                    } else {
                        Text("يُخصم المبلغ من عمولة المورد بالدولار", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    }
                }
                Text("الصرف من صندوق دولار", style = MaterialTheme.typography.labelLarge)
                if (boxes.isEmpty()) {
                    Text("لا يوجد صندوق بالدولار — أنشئ صندوقاً بعملة USD من شاشة الخزينة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    boxes.forEach { b ->
                        FilterChip(selected = boxId == b.id, onClick = { boxId = b.id }, label = { Text("${b.name} · ${b.shownBalance}") })
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(300) }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = ok && !busy, onClick = {
                when {
                    // Dollars one-for-one against the shipping company's USD balance.
                    shipping -> onSave(accPlain(usdValue!!), boxId!!, null, "GOODS", note)
                    else -> onSave(accPlain(usdValue!!), boxId!!, if (goods) rateValue?.let(::accPlain) else null, if (goods) "GOODS" else "COMMISSION", note)
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** A balance with the supplier from before the system, in yuan or dollars; the SAR value is worked out by the server. */
@Composable
private fun OpeningBalanceDialog(
    currencies: List<String>,
    initial: String,
    lastRate: String?,
    art: Map<String, String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    party: String = "المورد",
    onSave: (currency: String, amount: String, weOwe: Boolean, sarAmount: String?, foreignPerUsd: String?, note: String) -> Unit,
) {
    var currency by remember { mutableStateOf(initial.takeIf { it in currencies } ?: currencies.first()) }
    var amount by remember { mutableStateOf("") }
    var weOwe by remember { mutableStateOf(true) }
    var sar by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf(lastRate?.let { accNum(it)?.let(::accPlain) }.orEmpty()) }
    var note by remember { mutableStateOf("") }
    // The server takes up to 2 decimals.
    val amountValue = accNum(amount)?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 2 }
    val sarValue = accNum(sar)?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 2 }
    val rateValue = accNum(rate)?.takeIf { it.signum() > 0 }
    val isCny = currency == "CNY"
    val sarInvalid = sar.isNotBlank() && sarValue == null
    val rateInvalid = isCny && rate.isNotBlank() && rateValue == null
    val ok = amountValue != null && !sarInvalid && !rateInvalid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("رصيد افتتاحي لـ$party") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("العملة", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    currencies.forEach { c ->
                        FilterChip(
                            selected = currency == c,
                            onClick = { currency = c },
                            label = { Text(if (c == "CNY") "اليوان" else if (c == "USD") "الدولار" else c) },
                            leadingIcon = { CurrencyArt(c, art[c], height = 16.dp) },
                        )
                    }
                }
                AmountField("المبلغ ($currency)", amount, Modifier.fillMaxWidth()) { amount = it }
                Text("الاتجاه", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = weOwe, onClick = { weOwe = true }, label = { Text("نحن مدينون لـ$party") })
                    FilterChip(selected = !weOwe, onClick = { weOwe = false }, label = { Text("$party مدين لنا") })
                }
                Text(
                    if (weOwe) "يُضاف المبلغ إلى ما هو مستحق لـ$party" else "يُسجَّل كمبلغ مدفوع مقدماً لـ$party",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
                if (isCny) {
                    AmountField(
                        "سعر الدولار باليوان (اختياري)",
                        rate,
                        Modifier.fillMaxWidth(),
                        hint = if (lastRate != null) "آخر سعر من سندات الصرف: $lastRate" else "فارغ = سعر آخر سند صرف للمورد",
                    ) { rate = it }
                }
                AmountField(
                    "القيمة بالريال السعودي (اختياري)",
                    sar,
                    Modifier.fillMaxWidth(),
                    hint = "اتركه فارغاً ليُحسب تلقائياً بسعر الصرف",
                ) { sar = it }
                OutlinedTextField(value = note, onValueChange = { note = it.take(255) }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = ok && !busy, onClick = {
                onSave(currency, accPlain(amountValue!!), weOwe, sarValue?.let(::accPlain), if (isCny) rateValue?.let(::accPlain) else null, note)
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** The agent's commission in dollars for a period, added when matching the account. */
@Composable
private fun CommissionDialog(
    edit: SupplierVoucher?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (usd: String, from: LocalDate, to: LocalDate, note: String) -> Unit,
) {
    val month = remember { YearMonth.now() }
    var usd by remember { mutableStateOf(edit?.usdAmount?.let { accNum(it)?.let(::accPlain) }.orEmpty()) }
    var from by remember { mutableStateOf(dayOf(edit?.periodFrom) ?: month.atDay(1)) }
    var to by remember { mutableStateOf(dayOf(edit?.periodTo) ?: month.atEndOfMonth()) }
    var note by remember { mutableStateOf(edit?.note.orEmpty()) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val usdValue = accNum(usd)?.takeIf { it.signum() > 0 }
    val ok = usdValue != null && !to.isBefore(from)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit == null) "إضافة عمولة (مطابقة)" else "تعديل العمولة ${edit.number}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField("العمولة بالدولار", usd, Modifier.fillMaxWidth()) { usd = it }
                Text("فترة المطابقة", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = 0 }, modifier = Modifier.weight(1f)) { Text("من $from") }
                    OutlinedButton(onClick = { picking = 1 }, modifier = Modifier.weight(1f)) { Text("إلى $to") }
                }
                if (to.isBefore(from)) Text("تاريخ النهاية قبل البداية", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(value = note, onValueChange = { note = it.take(300) }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = ok && !busy, onClick = { onSave(accPlain(usdValue!!), from, to, note) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
    when (picking) {
        0 -> DayPickerDialog(from, onDismiss = { picking = null }) { from = it; picking = null }
        1 -> DayPickerDialog(to, onDismiss = { picking = null }) { to = it; picking = null }
    }
}

@Composable
private fun DayPickerDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The date picker works in UTC midnights.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
                enabled = state.selectedDateMillis != null,
            ) { Text("تم") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    ) { DatePicker(state = state) }
}
