@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.EmptyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dejavu.core.admin.ExpenseStatus
import com.dejavu.core.admin.JournalEntryView
import com.dejavu.core.admin.LedgerAccount
import com.dejavu.core.admin.PurchaseOrderStatus
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.ReturnStatus
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.label
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Gold
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning

// ------------------------------------------------------------------ colors ---

@Composable
fun OrderStatus.tint(): Color = when (this) {
    OrderStatus.PENDING -> Warning
    OrderStatus.CONFIRMED, OrderStatus.PROCESSING -> MaterialTheme.colorScheme.primary
    OrderStatus.SHIPPED -> Gold
    OrderStatus.DELIVERED, OrderStatus.COMPLETED -> Success
    OrderStatus.CANCELLED, OrderStatus.RETURNED -> MaterialTheme.colorScheme.error
}

@Composable
fun ReturnStatus.tint(): Color = when (this) {
    ReturnStatus.REQUESTED -> Warning
    ReturnStatus.APPROVED -> Gold
    ReturnStatus.RECEIVED, ReturnStatus.REFUNDED -> Success
    ReturnStatus.REJECTED -> MaterialTheme.colorScheme.error
}

val PurchaseOrderStatus.label: String
    get() = when (this) {
        PurchaseOrderStatus.DRAFT -> "مسودة"
        PurchaseOrderStatus.ORDERED -> "تم الطلب"
        PurchaseOrderStatus.PARTIALLY_RECEIVED -> "مستلم جزئياً"
        PurchaseOrderStatus.RECEIVED -> "مستلم"
        PurchaseOrderStatus.CANCELLED -> "ملغي"
    }

@Composable
fun PurchaseOrderStatus.tint(): Color = when (this) {
    PurchaseOrderStatus.DRAFT -> MaterialTheme.colorScheme.onSurfaceVariant
    PurchaseOrderStatus.ORDERED, PurchaseOrderStatus.PARTIALLY_RECEIVED -> Warning
    PurchaseOrderStatus.RECEIVED -> Success
    PurchaseOrderStatus.CANCELLED -> MaterialTheme.colorScheme.error
}

val ExpenseStatus.label: String
    get() = when (this) {
        ExpenseStatus.DRAFT -> "بانتظار الاعتماد"
        ExpenseStatus.APPROVED -> "معتمد، غير مدفوع"
        ExpenseStatus.PAID -> "مدفوع"
        ExpenseStatus.CANCELLED -> "ملغي"
    }

@Composable
fun ExpenseStatus.tint(): Color = when (this) {
    ExpenseStatus.DRAFT -> Warning
    ExpenseStatus.APPROVED -> Gold
    ExpenseStatus.PAID -> Success
    ExpenseStatus.CANCELLED -> MaterialTheme.colorScheme.error
}

/** Next-step button text for an order status change. */
val OrderStatus.actionLabel: String
    get() = when (this) {
        OrderStatus.CONFIRMED -> "تأكيد الطلب"
        OrderStatus.PROCESSING -> "بدء التجهيز"
        OrderStatus.SHIPPED -> "تسليم لشركة الشحن"
        OrderStatus.DELIVERED -> "تم التسليم للعميل"
        OrderStatus.COMPLETED -> "إغلاق الطلب"
        OrderStatus.CANCELLED -> "إلغاء الطلب"
        OrderStatus.RETURNED -> "رفض العميل الاستلام"
        OrderStatus.PENDING -> "إعادة للانتظار"
    }

// ----------------------------------------------------------- widgets ---

@Composable
fun <T> ChipFilter(options: List<T?>, selected: T?, label: (T?) -> String, onSelect: (T?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        items(options) { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

/** Frosted-glass section card used across the admin screens. */
@Composable
fun InfoCard(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** The accounting entries a document produced, so staff see its effect on the books. */
@Composable
fun JournalEntriesCard(entries: List<JournalEntryView>) {
    if (entries.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    InfoCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("القيود المحاسبية (${entries.size})", style = MaterialTheme.typography.titleSmall)
            Text(if (open) "إخفاء" else "عرض", color = MaterialTheme.colorScheme.secondary)
        }
        if (open) {
            entries.forEach { e ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("${e.number} · ${formatDate(e.entryDate)}${if (e.status == "REVERSED") " · معكوس" else ""}", style = MaterialTheme.typography.labelMedium)
                Text(e.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                e.lines.forEach { l ->
                    val debit = !Money.isZero(l.debit)
                    Row(Modifier.fillMaxWidth()) {
                        Text("${if (debit) "من ح/" else "إلى ح/"} ${l.account.nameAr}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(Money.format(if (debit) l.debit else l.credit), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ dialogs ---

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
fun TextDialog(
    title: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    initial: String = "",
    minLength: Int = 3,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it.take(255) }, label = { Text(label) }) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(value.trim()) }, enabled = value.trim().length >= minLength) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

/**
 * Amount + method (+ optional reference/reason) for payments, refunds and supplier payments.
 * With several [currencies] (a YER order) the amount can be entered in either; [amountIn]
 * gives the suggested amount per currency and [onConfirmIn] receives the chosen one.
 */
@Composable
fun MoneyDialog(
    title: String,
    methods: List<PaymentMethod>,
    initialAmount: String,
    confirm: String,
    onDismiss: () -> Unit,
    askReason: Boolean = false,
    currencies: List<Currency> = listOf(Currency.SAR),
    amountIn: (Currency) -> String = { initialAmount },
    onConfirmIn: ((amount: String, method: PaymentMethod, note: String, currency: Currency) -> Unit)? = null,
    onConfirm: (amount: String, method: PaymentMethod, note: String) -> Unit = { _, _, _ -> },
) {
    var currency by rememberSaveable { mutableStateOf(currencies.first()) }
    var amount by rememberSaveable { mutableStateOf(amountIn(currencies.first())) }
    var method by rememberSaveable { mutableStateOf(methods.first()) }
    var note by rememberSaveable { mutableStateOf("") }
    val valid = amount.toBigDecimalOrNull()?.let { it.signum() > 0 && it.scale() <= currency.decimals } == true &&
        (!askReason || note.trim().length >= 3)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currencies.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        currencies.forEach { c ->
                            FilterChip(
                                selected = c == currency,
                                onClick = {
                                    currency = c
                                    amount = amountIn(c)
                                },
                                label = { Text(c.nameAr) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("المبلغ (${currency.symbol})") },
                    supportingText = if (currency.decimals == 0) ({ Text("بدون كسور") }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                methods.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { method = m }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = m == method, onClick = { method = m })
                        Text(m.label)
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(100) }, label = { Text(if (askReason) "السبب" else "مرجع (اختياري)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    if (onConfirmIn != null) onConfirmIn(amount, method, note.trim(), currency) else onConfirm(amount, method, note.trim())
                },
                enabled = valid,
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
fun AccountPickerDialog(title: String, accounts: List<LedgerAccount>, onDismiss: () -> Unit, onPick: (LedgerAccount) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (accounts.isEmpty()) Text("لا توجد حسابات نقدية متاحة لحسابك")
                accounts.forEach { a ->
                    Text(
                        "${a.code} · ${a.nameAr}",
                        modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onPick(a) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

// ------------------------------------------------------ pull to refresh ---

/**
 * The "pulled to refresh" flag of a list ViewModel. [run] keeps it on while the reload runs;
 * the list stays on screen meanwhile (no full-screen spinner).
 */
class Refresher {
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun run(scope: CoroutineScope, block: suspend () -> Unit) {
        if (_refreshing.value) return
        _refreshing.value = true
        scope.launch {
            try {
                block()
            } finally {
                _refreshing.value = false
            }
        }
    }
}

/** A list that reloads when pulled down. The content must scroll (a LazyColumn or [EmptyList]). */
@Composable
fun RefreshList(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        content()
    }
}

/** [EmptyState] that can still be pulled to refresh inside [RefreshList]. */
@Composable
fun EmptyList(icon: ImageVector, title: String, message: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { EmptyState(icon, title, message, action, onAction, Modifier.fillParentMaxSize()) }
    }
}

// ------------------------------------------------------------ undo delete ---

/**
 * Deletes that can be undone. [hide] hides the item at once; the real delete runs when the undo
 * snackbar closes without "تراجع" ([confirm]), after [UNDO_WINDOW_MS] at the latest, or when the
 * ViewModel is cleared ([flush]). The delete itself runs in [appScope] so it still happens after
 * leaving the screen. On failure the item comes back and [onError] gets the message.
 */
class UndoableDeletes(
    private val appScope: CoroutineScope,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private class Pending(val delete: suspend () -> Result<*>, val timer: Job)

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /** Ids hidden from the list: waiting for undo, or deleted. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()
    private val pending = mutableMapOf<String, Pending>()

    fun hide(id: String, delete: suspend () -> Result<*>) {
        confirm(id)
        _hidden.update { it + id }
        val timer = scope.launch {
            delay(UNDO_WINDOW_MS)
            confirm(id)
        }
        pending[id] = Pending(delete, timer)
    }

    /** Brings the item back if its delete has not been sent yet. */
    fun undo(id: String) {
        val p = pending.remove(id) ?: return
        p.timer.cancel()
        _hidden.update { it - id }
    }

    /** Shows [id] again, e.g. when the server kept the item (hid it instead of deleting it). */
    fun show(id: String) {
        _hidden.update { it - id }
    }

    /** Sends the delete now. */
    fun confirm(id: String) {
        val p = pending.remove(id) ?: return
        appScope.launch {
            p.delete().onFailure { e ->
                _hidden.update { it - id }
                onError(e.userMessage())
            }
        }
        p.timer.cancel()
    }

    /** Sends every delete still waiting; call from onCleared. */
    fun flush() {
        pending.keys.toList().forEach(::confirm)
    }

    companion object {
        const val UNDO_WINDOW_MS = 10_000L
    }
}

/** Shows "تم الحذف" with "تراجع"; undoes the delete of [id] or sends it when the snackbar closes. */
fun CoroutineScope.showUndoDelete(snackbar: SnackbarHostState, deletes: UndoableDeletes, id: String, message: String = "تم الحذف") {
    launch {
        val result = snackbar.showSnackbar(message, actionLabel = "تراجع", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) deletes.undo(id) else deletes.confirm(id)
    }
}

// --------------------------------------------------------------- two pane ---

/** Tablets and wide windows (≥ 840dp) show a list and its details side by side. */
@Composable
fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

/** List on one side, details on the other (start/end follow RTL). */
@Composable
fun ListDetail(list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.42f).fillMaxHeight()) { list() }
        VerticalDivider()
        Box(Modifier.weight(0.58f).fillMaxHeight()) { detail() }
    }
}
