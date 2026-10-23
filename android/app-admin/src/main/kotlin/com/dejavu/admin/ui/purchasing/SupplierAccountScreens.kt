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
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.SupplierAccount
import com.dejavu.core.admin.SupplierCommissionRequest
import com.dejavu.core.admin.SupplierPaymentVoucherRequest
import com.dejavu.core.admin.SupplierVoucher
import com.dejavu.core.admin.SupplierVoucherKind
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
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

class SupplierAccountViewModel(
    private val supplierId: String,
    private val repo: PurchasingRepository,
    private val treasury: TreasuryRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<SupplierAccount>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _name = MutableStateFlow<String?>(null)
    val name = _name.asStateFlow()
    private val _boxes = MutableStateFlow<List<CashBox>>(emptyList())

    /** Active USD boxes the payment can come from. */
    val boxes = _boxes.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        viewModelScope.launch { _state.value = repo.supplierAccount(supplierId).toLoad() }
        viewModelScope.launch { repo.supplier(supplierId).getOrNull()?.let { _name.value = it.name } }
        viewModelScope.launch {
            treasury.all(currency = Currency.of("USD")).getOrNull()?.let { list ->
                _boxes.value = list.filter { it.isActive && it.currency.code == "USD" }
            }
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
    val vm = adminViewModel(key = "supplier-account:$supplierId") { SupplierAccountViewModel(supplierId, it.purchasing, it.treasury) }
    val state by vm.state.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val boxes by vm.boxes.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val canPay = session.can(Perm.SUPPLIER_PAY)
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
        topBar = { BackTopBar(name?.let { "كشف حساب $it" } ?: "كشف حساب المورد", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { account ->
            val foreign = account.balances.firstOrNull { it.currency != "USD" }?.currency
                ?: account.vouchers.firstNotNullOfOrNull { it.foreignCurrency }
                ?: "CNY"
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("الرصيد مع المورد", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                        if (account.balances.isEmpty()) {
                            Text("لا توجد حركات بعد", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            account.balances.forEach { b ->
                                val sign = Money.parse(b.balance).signum()
                                val amount = inCurrency(Money.parse(b.balance).abs().toPlainString(), b.currency)
                                when {
                                    sign > 0 -> StatusChip("$amount · مستحق للمورد", Lux.GoldDeep)
                                    sign < 0 -> StatusChip("$amount · مدفوع زيادة", Success)
                                    else -> StatusChip("${b.currency} · مسدَّد", Lux.InkSoft)
                                }
                            }
                        }
                        account.lastRate?.let { SummaryRow("آخر سعر للدولار (من سندات الصرف)", "$it $foreign") }
                        if (canPay) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                Button(onClick = { paymentEdit = null; paymentOpen = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("سند صرف") }
                                OutlinedButton(onClick = { commissionEdit = null; commissionOpen = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("إضافة عمولة (مطابقة)") }
                            }
                        }
                    }
                }
                if (account.vouchers.isNotEmpty()) {
                    item { Text("السندات", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep) }
                }
                items(account.vouchers, key = { "v:" + it.id }) { v ->
                    VoucherCard(
                        v,
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
                PaymentVoucherDialog(
                    edit = paymentEdit,
                    lastRate = account.lastRate,
                    currency = paymentEdit?.foreignCurrency ?: foreign,
                    boxes = boxes,
                    busy = busy,
                    onDismiss = { paymentOpen = false },
                ) { usd, boxId, rate, applyTo, note ->
                    vm.pay(paymentEdit?.id, usd, boxId, rate, paymentEdit?.foreignCurrency ?: foreign, applyTo, note) { paymentOpen = false }
                }
            }
            if (commissionOpen) {
                CommissionDialog(edit = commissionEdit, busy = busy, onDismiss = { commissionOpen = false }) { usd, from, to, note ->
                    vm.commission(commissionEdit?.id, usd, from, to, note) { commissionOpen = false }
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
private fun VoucherCard(v: SupplierVoucher, canEdit: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
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
            Text(inCurrency(v.usdAmount, "USD"), style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
        }
        if (v.kind == SupplierVoucherKind.PAYMENT) {
            if (v.foreignPerUsd != null) {
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
    onSave: (usd: String, boxId: String, rate: String?, applyTo: String, note: String) -> Unit,
) {
    var usd by remember { mutableStateOf(edit?.usdAmount?.let { accNum(it)?.let(::accPlain) }.orEmpty()) }
    var rate by remember { mutableStateOf((edit?.foreignPerUsd ?: lastRate)?.let { accNum(it)?.let(::accPlain) }.orEmpty()) }
    var boxId by remember { mutableStateOf(edit?.cashBoxId ?: boxes.singleOrNull()?.id) }
    var goods by remember { mutableStateOf(edit == null || edit.foreignPerUsd != null) }
    var note by remember { mutableStateOf(edit?.note.orEmpty()) }
    val usdValue = accNum(usd)?.takeIf { it.signum() > 0 }
    val rateValue = accNum(rate)?.takeIf { it.signum() > 0 }
    val ok = usdValue != null && boxId != null && (!goods || rateValue != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit == null) "سند صرف للمورد" else "تعديل سند الصرف ${edit.number}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField("المبلغ بالدولار", usd, Modifier.fillMaxWidth()) { usd = it }
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
                onSave(accPlain(usdValue!!), boxId!!, if (goods) rateValue?.let(::accPlain) else null, if (goods) "GOODS" else "COMMISSION", note)
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
