@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.orders

import com.dejavu.core.ui.theme.GlassTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ChipFilter
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.JournalEntriesCard
import com.dejavu.admin.ui.MoneyDialog
import com.dejavu.admin.ui.TextDialog
import com.dejavu.admin.ui.actionLabel
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.tint
import com.dejavu.core.admin.AdminOrderDetail
import com.dejavu.core.admin.AdminOrderRepository
import com.dejavu.core.admin.AdminOrderSummary
import com.dejavu.core.admin.AdminReturnDetail
import com.dejavu.core.admin.AdminReturnRepository
import com.dejavu.core.admin.AdminReturnSummary
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.Page
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.ReturnStatus
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val PAY_METHODS = listOf(PaymentMethod.CASH, PaymentMethod.INSTAPAY, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER, PaymentMethod.CARD)
private val REFUND_METHODS = listOf(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.INSTAPAY, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER)

// -------------------------------------------------------------- order list ---

class OrdersViewModel(private val repo: AdminOrderRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<Page<AdminOrderSummary>>>(Load.Loading)
    val state = _state.asStateFlow()
    var status: OrderStatus? = OrderStatus.PENDING
        private set
    var query: String = ""
        private set

    fun filter(status: OrderStatus?) {
        this.status = status
        load(reset = true)
    }

    fun search(text: String) {
        query = text
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        if (reset || _state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.list(status, query).toLoad() }
    }
}

@Composable
fun OrdersScreen(openOrder: (String) -> Unit) {
    val vm = adminViewModel { OrdersViewModel(it.orders) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(vm.query) }
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { GlassTopBar(title = { Text("الطلبات") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("رقم الطلب أو اسم/موبايل العميل") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ChipFilter(listOf(null) + OrderStatus.entries, vm.status, { it?.label ?: "الكل" }, vm::filter)
            LoadContent(state, onRetry = { vm.load() }) { page ->
                if (page.items.isEmpty()) {
                    EmptyState(Icons.AutoMirrored.Filled.List, "لا توجد طلبات")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Text("${page.total} طلب", style = MaterialTheme.typography.labelMedium) }
                        items(page.items, key = { it.id }) { o -> OrderRow(o) { openOrder(o.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(o: AdminOrderSummary, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.number, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusChip(o.status.label, o.status.tint())
            }
            Text("${o.customer.name} · ${o.customer.phone.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
            Row {
                Text("${formatDateTime(o.placedAt)} · ${o.paymentMethod.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(o.totalShown, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ------------------------------------------------------------ order detail ---

class OrderDetailViewModel(private val id: String, private val repo: AdminOrderRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<AdminOrderDetail>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.get(id).toLoad() }
    }

    fun changeStatus(status: OrderStatus, note: String? = null, carrier: String? = null, tracking: String? = null) =
        act("تم: ${status.label}") { repo.changeStatus(id, status, note, carrier, tracking) }

    fun pay(amount: String, method: PaymentMethod, reference: String, currency: Currency) =
        act("تم تسجيل الدفعة") { repo.recordPayment(id, method, amount, reference, currency = currency) }

    fun refund(amount: String, method: PaymentMethod, reason: String, currency: Currency) =
        act("تم رد المبلغ") { repo.refund(id, method, amount, reason, currency = currency) }

    private fun act(done: String, block: suspend () -> Result<AdminOrderDetail>) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block().orSay()?.let {
                _state.value = Load.Ready(it)
                say(done)
            }
            _busy.value = false
        }
    }
}

private sealed interface OrderDialog {
    data class Confirm(val status: OrderStatus) : OrderDialog
    data object Ship : OrderDialog
    data object Cancel : OrderDialog
    data object Refuse : OrderDialog
    data object Pay : OrderDialog
    data object Refund : OrderDialog
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderDetailScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit, openReturn: (String) -> Unit) {
    val vm = adminViewModel(key = "order:$id") { OrderDetailViewModel(id, it.orders) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<OrderDialog?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar((state as? Load.Ready)?.value?.number ?: "الطلب", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { o ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDateTime(o.placedAt), style = MaterialTheme.typography.bodySmall)
                            Text("${o.paymentMethod.label} · ${o.paymentStatus.label}", style = MaterialTheme.typography.bodySmall)
                        }
                        StatusChip(o.status.label, o.status.tint())
                    }
                }

                // Actions allowed by the order's state and the user's permissions
                val canUpdate = session.can(Perm.ORDERS_UPDATE)
                val actions = o.nextStatuses.filter { s ->
                    canUpdate && (s != OrderStatus.CANCELLED || session.can(Perm.ORDERS_CANCEL))
                }
                if (actions.isNotEmpty() || o.canTakePayment || o.canRefund) {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions.forEach { s ->
                                val destructive = s == OrderStatus.CANCELLED || s == OrderStatus.RETURNED
                                if (destructive) {
                                    OutlinedButton(onClick = { dialog = if (s == OrderStatus.CANCELLED) OrderDialog.Cancel else OrderDialog.Refuse }, enabled = !busy) {
                                        Text(s.actionLabel, color = MaterialTheme.colorScheme.error)
                                    }
                                } else {
                                    Button(onClick = { dialog = if (s == OrderStatus.SHIPPED) OrderDialog.Ship else OrderDialog.Confirm(s) }, enabled = !busy) { Text(s.actionLabel) }
                                }
                            }
                            if (o.canTakePayment && session.can(Perm.PAYMENTS_RECORD) && o.paymentMethod != PaymentMethod.CASH_ON_DELIVERY) {
                                OutlinedButton(onClick = { dialog = OrderDialog.Pay }, enabled = !busy) { Text("تسجيل دفعة") }
                            }
                            if (o.canRefund && session.can(Perm.PAYMENTS_RECORD)) {
                                OutlinedButton(onClick = { dialog = OrderDialog.Refund }, enabled = !busy) { Text("رد المبلغ") }
                            }
                        }
                    }
                }

                item {
                    InfoCard {
                        Text("العميل", style = MaterialTheme.typography.titleSmall)
                        Text("${o.customer.name} · ${o.customer.phone.orEmpty()}")
                        val a = o.shippingAddress
                        Text(
                            listOfNotNull(a.street, a.building?.let { "مبنى $it" }, a.floor?.let { "دور $it" }, a.apartment?.let { "شقة $it" }, a.district, a.city, a.governorate).joinToString("، "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        a.landmark?.let { Text("علامة: $it", style = MaterialTheme.typography.bodySmall) }
                        Text("المستلم: ${a.recipient} · ${a.phone}", style = MaterialTheme.typography.bodySmall)
                        o.customerNote?.let { Text("ملاحظة العميل: $it", color = MaterialTheme.colorScheme.secondary) }
                    }
                }
                item {
                    InfoCard {
                        Text("المنتجات", style = MaterialTheme.typography.titleSmall)
                        o.items.forEach { i ->
                            SummaryRow("${i.productName} (${listOfNotNull(i.size, i.color).joinToString("/")}) × ${i.quantity}", Money.format(i.lineTotal))
                            Text("${i.sku}${if (i.returnedQty > 0) " · مرتجع ${i.returnedQty}" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    InfoCard {
                        SummaryRow("المجموع", Money.format(o.subtotal))
                        if (!Money.isZero(o.discountTotal)) SummaryRow("الخصم", "- ${Money.format(o.discountTotal)}")
                        SummaryRow("الشحن", Money.format(o.shippingFee))
                        SummaryRow("الإجمالي", Money.format(o.grandTotal), strong = true)
                        SummaryRow("المدفوع", Money.format(o.amountPaid), valueColor = Success)
                        if (!Money.isZero(o.returnedTotal)) SummaryRow("قيمة المرتجعات", Money.format(o.returnedTotal))
                        SummaryRow("المتبقي", Money.format(o.balanceDue), strong = true)
                        if (o.isForeign) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text("فاتورة العميل بـ${o.currency.nameAr}", style = MaterialTheme.typography.titleSmall)
                            SummaryRow("الإجمالي", o.inOrderCurrency(o.grandTotal))
                            SummaryRow("المدفوع", o.inOrderCurrency(o.amountPaid), valueColor = Success)
                            SummaryRow("المتبقي", o.inOrderCurrency(o.balanceDue), strong = true)
                            Text(
                                "سعر الصرف المثبّت عند الطلب: 1 ر.س = ${Money.plain(Money.parse(o.exchangeRate), o.currency)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    InfoCard {
                        Text("الربحية", style = MaterialTheme.typography.titleSmall)
                        SummaryRow("صافي الإيراد", Money.format(o.profit.netRevenue))
                        SummaryRow("تكلفة البضاعة", Money.format(o.profit.costOfGoods))
                        SummaryRow("مجمل الربح", Money.format(o.profit.grossProfit), strong = true)
                    }
                }
                if (o.shipments.isNotEmpty()) {
                    item {
                        InfoCard {
                            Text("الشحن", style = MaterialTheme.typography.titleSmall)
                            o.shipments.forEach { s ->
                                Text("${s.carrier}${s.trackingNumber?.let { " · $it" } ?: ""}")
                                if (!Money.isZero(s.codAmount)) Text("تحصيل: ${Money.format(s.codAmount)}${if (s.settlementId != null) " · تم التوريد" else ""}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (o.payments.isNotEmpty()) {
                    item {
                        InfoCard {
                            Text("المدفوعات", style = MaterialTheme.typography.titleSmall)
                            o.payments.forEach { p ->
                                val attempt = when (p.status) {
                                    "PENDING" -> " · بانتظار البوابة"
                                    "FAILED" -> " · مرفوض"
                                    "CANCELLED" -> " · محاولة ملغاة"
                                    else -> ""
                                }
                                SummaryRow(
                                    "${p.number} · ${p.method.label}$attempt",
                                    p.shown,
                                    valueColor = when {
                                        !p.isSettled -> MaterialTheme.colorScheme.onSurfaceVariant
                                        p.isRefund -> MaterialTheme.colorScheme.error
                                        else -> Success
                                    },
                                )
                            }
                        }
                    }
                }
                if (o.returns.isNotEmpty()) {
                    item {
                        InfoCard {
                            Text("المرتجعات", style = MaterialTheme.typography.titleSmall)
                            o.returns.forEach { r ->
                                TextButton(onClick = { openReturn(r.id) }) { Text("${r.number} · ${r.status.label} · ${Money.format(r.refundAmount)}") }
                            }
                        }
                    }
                }
                item {
                    InfoCard {
                        Text("سجل الحالة", style = MaterialTheme.typography.titleSmall)
                        o.statusHistory.forEach { h ->
                            Text("${h.toStatus.label} · ${formatDateTime(h.createdAt)}${h.changedBy?.let { " · ${it.name}" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                            h.note?.let { Text("  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                item { JournalEntriesCard(o.journalEntries) }
            }

            when (val d = dialog) {
                is OrderDialog.Confirm -> ConfirmDialog(d.status.actionLabel, "${o.number} · ${o.customer.name}", "تأكيد", { dialog = null }) { vm.changeStatus(d.status) }
                OrderDialog.Ship -> ShipDialog(o, onDismiss = { dialog = null }) { carrier, tracking -> vm.changeStatus(OrderStatus.SHIPPED, carrier = carrier, tracking = tracking) }
                OrderDialog.Cancel -> TextDialog("إلغاء الطلب", "سبب الإلغاء", "إلغاء الطلب", { dialog = null }) { vm.changeStatus(OrderStatus.CANCELLED, note = it) }
                OrderDialog.Refuse -> TextDialog(
                    "رفض العميل الاستلام",
                    "ملاحظة (مثلاً: العميل لم يرد)",
                    "تأكيد",
                    { dialog = null },
                ) { vm.changeStatus(OrderStatus.RETURNED, note = it) }
                OrderDialog.Pay -> MoneyDialog(
                    "تسجيل دفعة", PAY_METHODS, o.balanceDue, "تسجيل", { dialog = null },
                    currencies = o.currencies,
                    amountIn = { c -> if (c == Currency.SAR) o.balanceDue else o.balanceDueInCurrency },
                    onConfirmIn = { amount, method, ref, currency -> vm.pay(amount, method, ref, currency) },
                )
                OrderDialog.Refund -> MoneyDialog(
                    "رد مبلغ للعميل", REFUND_METHODS, o.amountPaid, "رد المبلغ", { dialog = null }, askReason = true,
                    currencies = o.currencies,
                    amountIn = { c -> if (c == Currency.SAR) o.amountPaid else Money.convert(Money.parse(o.amountPaid), c, Money.parse(o.exchangeRate)).toPlainString() },
                    onConfirmIn = { amount, method, reason, currency -> vm.refund(amount, method, reason, currency) },
                )
                null -> {}
            }
        }
    }
}

@Composable
private fun ShipDialog(order: AdminOrderDetail, onDismiss: () -> Unit, onShip: (carrier: String, tracking: String) -> Unit) {
    var carrier by rememberSaveable { mutableStateOf("Bosta") }
    var tracking by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسليم لشركة الشحن") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("سيتم خصم المخزون وتسجيل قيد المبيعات والتكلفة.", style = MaterialTheme.typography.bodySmall)
                if (order.paymentMethod == PaymentMethod.CASH_ON_DELIVERY) {
                    Text("المطلوب تحصيله: ${if (order.isForeign) order.inOrderCurrency(order.balanceDue) else Money.format(order.balanceDue)}", color = MaterialTheme.colorScheme.secondary)
                }
                OutlinedTextField(value = carrier, onValueChange = { carrier = it }, label = { Text("شركة الشحن") }, singleLine = true)
                OutlinedTextField(value = tracking, onValueChange = { tracking = it }, label = { Text("رقم التتبع (اختياري)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onShip(carrier.trim(), tracking.trim()) }, enabled = carrier.isNotBlank()) { Text("شحن") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

// ------------------------------------------------------------------ returns ---

class ReturnsViewModel(private val repo: AdminReturnRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<Page<AdminReturnSummary>>>(Load.Loading)
    val state = _state.asStateFlow()
    var status: ReturnStatus? = ReturnStatus.REQUESTED
        private set

    fun filter(s: ReturnStatus?) {
        status = s
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        if (reset || _state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.list(status?.name).toLoad() }
    }
}

@Composable
fun ReturnsScreen(onBack: () -> Unit, openReturn: (String) -> Unit) {
    val vm = adminViewModel { ReturnsViewModel(it.returns) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("المرتجعات", onBack) }) { padding ->
        Column(Modifier.padding(padding)) {
            ChipFilter(listOf(null) + ReturnStatus.entries, vm.status, { it?.label ?: "الكل" }, vm::filter)
            LoadContent(state, onRetry = { vm.load() }) { page ->
                if (page.items.isEmpty()) {
                    EmptyState(Icons.AutoMirrored.Filled.List, "لا توجد مرتجعات")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(page.items, key = { it.id }) { r ->
                            Card(onClick = { openReturn(r.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${r.number} · ${r.order.number}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        StatusChip(r.status.label, r.status.tint())
                                    }
                                    Text("${r.customer.name} · ${formatDate(r.requestedAt)}", style = MaterialTheme.typography.bodySmall)
                                    Text(r.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(Money.format(r.refundAmount), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class ReturnDetailViewModel(private val id: String, private val repo: AdminReturnRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<AdminReturnDetail>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.get(id).toLoad() }
    }

    fun approve() = act("تمت الموافقة") { repo.approve(id) }
    fun reject(reason: String) = act("تم رفض الطلب") { repo.reject(id, reason) }
    fun receive(restock: Map<String, Boolean>) = act("تم استلام القطع") { repo.receive(id, restock) }
    fun refund(method: PaymentMethod) = act("تم رد المبلغ") { repo.refund(id, method) }

    private fun act(done: String, block: suspend () -> Result<AdminReturnDetail>) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block().orSay()?.let {
                _state.value = Load.Ready(it)
                say(done)
            }
            _busy.value = false
        }
    }
}

@Composable
fun ReturnDetailScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel(key = "return:$id") { ReturnDetailViewModel(id, it.returns) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var reject by remember { mutableStateOf(false) }
    var refund by remember { mutableStateOf(false) }
    // Return-line id → restock? Defaults to yes; untick damaged pieces.
    val restock = remember { mutableStateMapOf<String, Boolean>() }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("مرتجع", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { r ->
            val manage = session.can(Perm.RETURNS)
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.number} · الطلب ${r.order.number}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            StatusChip(r.status.label, r.status.tint())
                        }
                        Text("${r.customer.name} · ${r.customer.phone.orEmpty()}")
                        Text("السبب: ${r.reason}", style = MaterialTheme.typography.bodySmall)
                        r.rejectReason?.let { Text("سبب الرفض: $it", color = MaterialTheme.colorScheme.error) }
                    }
                }
                item {
                    InfoCard {
                        Text("القطع", style = MaterialTheme.typography.titleSmall)
                        r.items.forEach { line ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${line.orderItem.productName} × ${line.quantity}")
                                    Text("${line.orderItem.sku} · ${Money.format(line.refundAmount)}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (r.status == ReturnStatus.APPROVED && manage) {
                                    Checkbox(checked = restock[line.id] ?: true, onCheckedChange = { restock[line.id] = it })
                                    Text("صالح للبيع", style = MaterialTheme.typography.bodySmall)
                                } else if (r.status == ReturnStatus.RECEIVED || r.status == ReturnStatus.REFUNDED) {
                                    Text(if (line.restock) "أعيد للمخزون" else "تالف", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        SummaryRow("قيمة الإرجاع", Money.format(r.refundAmount), strong = true)
                        if (r.status == ReturnStatus.RECEIVED) SummaryRow("المستحق رده نقداً", Money.format(r.refundDue))
                    }
                }
                if (manage) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (r.status) {
                                ReturnStatus.REQUESTED -> {
                                    Button(onClick = vm::approve, enabled = !busy) { Text("موافقة") }
                                    OutlinedButton(onClick = { reject = true }, enabled = !busy) { Text("رفض") }
                                }
                                ReturnStatus.APPROVED -> {
                                    Button(onClick = { vm.receive(r.items.associate { it.id to (restock[it.id] ?: true) }) }, enabled = !busy) { Text("تم استلام القطع") }
                                    OutlinedButton(onClick = { reject = true }, enabled = !busy) { Text("رفض") }
                                }
                                ReturnStatus.RECEIVED ->
                                    if (session.can(Perm.PAYMENTS_RECORD) && !Money.isZero(r.refundDue)) {
                                        Button(onClick = { refund = true }, enabled = !busy) { Text("رد ${Money.format(r.refundDue)}") }
                                    }
                                else -> {}
                            }
                        }
                    }
                }
                item { JournalEntriesCard(r.journalEntries) }
            }
            if (reject) TextDialog("رفض المرتجع", "سبب الرفض", "رفض", { reject = false }) { vm.reject(it) }
            if (refund) {
                AlertDialog(
                    onDismissRequest = { refund = false },
                    title = { Text("طريقة رد المبلغ") },
                    text = {
                        Column {
                            REFUND_METHODS.forEach { m ->
                                TextButton(onClick = { refund = false; vm.refund(m) }, modifier = Modifier.fillMaxWidth()) { Text(m.label) }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { refund = false }) { Text("إغلاق") } },
                )
            }
        }
    }
}
