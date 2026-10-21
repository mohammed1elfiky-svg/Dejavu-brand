@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.OrderRepository
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.model.Money
import com.dejavu.core.model.OrderDetail
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.OrderSummary
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.Load
import com.dejavu.customer.ui.appViewModel
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SectionTitle
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.label
import com.dejavu.core.ui.theme.Gold
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Composable
fun OrderStatus.color(): Color = when (this) {
    OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PROCESSING -> Warning
    OrderStatus.SHIPPED -> Gold
    OrderStatus.DELIVERED, OrderStatus.COMPLETED -> Success
    OrderStatus.CANCELLED, OrderStatus.RETURNED -> MaterialTheme.colorScheme.error
}

// ------------------------------------------------------------------ list ---

class OrdersViewModel(private val orders: OrderRepository, val session: SessionRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<OrderSummary>>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        if (!session.isLoggedIn) return
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = orders.orders().map { it.items }.toLoad() }
    }
}

@Composable
fun OrdersScreen(openOrder: (String) -> Unit, login: () -> Unit, shop: () -> Unit) {
    val vm = appViewModel { OrdersViewModel(it.orders, it.session) }
    val session by vm.session.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(session is SessionState.LoggedIn) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { CenterAlignedTopAppBar(title = { Text("طلباتي") }) }) { padding ->
        if (session !is SessionState.LoggedIn) {
            EmptyState(Icons.AutoMirrored.Filled.List, "سجّل الدخول لمتابعة طلباتك", action = "تسجيل الدخول", onAction = login, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            if (list.isEmpty()) {
                EmptyState(Icons.AutoMirrored.Filled.List, "لا توجد طلبات بعد", action = "تسوّق الآن", onAction = shop, modifier = Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { o ->
                        Card(onClick = { openOrder(o.number) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(o.number, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    StatusChip(o.status.label, o.status.color())
                                }
                                Text("${formatDate(o.placedAt)} · ${o.count?.items ?: 0} منتج", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(Money.format(o.grandTotal), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- detail ---

class OrderDetailViewModel(private val number: String, private val orders: OrderRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<OrderDetail>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _cancelling = MutableStateFlow(false)
    val cancelling = _cancelling.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = orders.order(number).toLoad() }
    }

    private val _cardPage = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Card payment page to open in the browser. */
    val cardPage = _cardPage.asSharedFlow()
    private val _paying = MutableStateFlow(false)
    val paying = _paying.asStateFlow()

    fun payByCard() {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            orders.payByCard(number).orSay()?.let { _cardPage.tryEmit(it.checkoutUrl) }
            _paying.value = false
        }
    }

    fun cancel(reason: String) {
        _cancelling.value = true
        viewModelScope.launch {
            orders.cancel(number, reason).orSay()?.let {
                _state.value = Load.Ready(it)
                say("تم إلغاء الطلب")
            }
            _cancelling.value = false
        }
    }
}

@Composable
fun OrderDetailScreen(number: String, justPlaced: Boolean, onBack: () -> Unit, requestReturn: () -> Unit, openProduct: (String) -> Unit) {
    val vm = appViewModel(key = "order:$number") { OrderDetailViewModel(number, it.orders) }
    val state by vm.state.collectAsStateWithLifecycle()
    val cancelling by vm.cancelling.collectAsStateWithLifecycle()
    val paying by vm.paying.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var askCancel by rememberSaveable { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(vm) { vm.cardPage.collect { runCatching { uriHandler.openUri(it) } } }
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("طلب $number", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { order ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (justPlaced) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Success.copy(alpha = 0.1f))) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Success)
                                Column {
                                    Text("تم استلام طلبك بنجاح", style = MaterialTheme.typography.titleSmall)
                                    Text("سنراجع الطلب ونؤكده قريباً", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDateTime(order.placedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${order.paymentMethod.label} · ${order.paymentStatus.label}", style = MaterialTheme.typography.bodySmall)
                        }
                        StatusChip(order.status.label, order.status.color())
                    }
                }
                item { Timeline(order) }
                order.shipments.firstOrNull()?.let { s ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("الشحن مع ${s.carrier}", style = MaterialTheme.typography.titleSmall)
                                s.trackingNumber?.let { Text("رقم التتبع: $it", style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
                item { SectionTitle("المنتجات") }
                items(order.items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth()) {
                        SummaryRow("${item.productName} × ${item.quantity}", Money.format(item.lineTotal))
                        Text(
                            listOfNotNull(item.color, item.size?.let { "مقاس $it" }, if (item.returnedQty > 0) "مرتجع: ${item.returnedQty}" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    HorizontalDivider()
                    SummaryRow("المجموع", Money.format(order.subtotal))
                    if (!Money.isZero(order.discountTotal)) SummaryRow("الخصم" + (order.coupon?.let { " (${it.code})" } ?: ""), "- ${Money.format(order.discountTotal)}", valueColor = Success)
                    SummaryRow("الشحن", Money.format(order.shippingFee))
                    SummaryRow("الإجمالي", Money.format(order.grandTotal), strong = true)
                }
                item {
                    Column(Modifier.padding(top = 4.dp)) {
                        Text("التوصيل إلى", style = MaterialTheme.typography.titleSmall)
                        val a = order.shippingAddress
                        Text("${a.recipient} · ${a.phone}", style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(a.street, a.district, a.city, a.governorate).joinToString("، "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                order.cancelReason?.let { item { Text("سبب الإلغاء: $it", color = MaterialTheme.colorScheme.error) } }
                if (order.canPayByCard) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.12f))) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("بانتظار الدفع بالبطاقة", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "أكمل الدفع لنبدأ تجهيز طلبك. بعد الدفع ارجع إلى التطبيق وسيتم تحديث الطلب تلقائياً.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = vm::payByCard, enabled = !paying, modifier = Modifier.fillMaxWidth()) {
                                    Text("ادفع الآن ${Money.format(Money.parse(order.grandTotal) - Money.parse(order.amountPaid))}")
                                }
                            }
                        }
                    }
                }
                if (order.canCancel || order.canReturn) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (order.canReturn) Button(onClick = requestReturn) { Text("طلب إرجاع") }
                            if (order.canCancel) OutlinedButton(onClick = { askCancel = true }, enabled = !cancelling) { Text("إلغاء الطلب") }
                        }
                    }
                }
            }
        }
    }

    if (askCancel) {
        ReasonDialog(
            title = "إلغاء الطلب",
            placeholder = "سبب الإلغاء",
            confirm = "تأكيد الإلغاء",
            onDismiss = { askCancel = false },
            onConfirm = { askCancel = false; vm.cancel(it) },
        )
    }
}

@Composable
private fun Timeline(order: OrderDetail) {
    val steps = listOf(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.DELIVERED)
    val reached = order.statusHistory.associate { it.toStatus to it.createdAt }
    val ended = order.status == OrderStatus.CANCELLED || order.status == OrderStatus.RETURNED
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            val shown = if (ended) steps.filter { it in reached } + order.status else steps
            shown.forEachIndexed { i, step ->
                val at = reached[step]
                val done = at != null
                Row(verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(14.dp).clip(CircleShape)
                                .background(if (done) step.color() else MaterialTheme.colorScheme.outline),
                        )
                        if (i < shown.lastIndex) Box(Modifier.width(2.dp).height(28.dp).background(MaterialTheme.colorScheme.outline))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(step.label, style = MaterialTheme.typography.bodyMedium, color = if (done) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (at != null) Text(formatDateTime(at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun ReasonDialog(title: String, placeholder: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = reason, onValueChange = { reason = it.take(255) }, placeholder = { Text(placeholder) }) },
        confirmButton = { TextButton(onClick = { onConfirm(reason.trim()) }, enabled = reason.trim().length >= 3) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

// ---------------------------------------------------------------- return ---

class ReturnRequestViewModel(private val number: String, private val orders: OrderRepository) : BaseViewModel() {
    private val _order = MutableStateFlow<Load<OrderDetail>>(Load.Loading)
    val order = _order.asStateFlow()
    private val _quantities = MutableStateFlow<Map<String, Int>>(emptyMap())
    val quantities = _quantities.asStateFlow()
    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    init { load() }

    fun load() {
        _order.value = Load.Loading
        viewModelScope.launch { _order.value = orders.order(number).toLoad() }
    }

    fun setQuantity(itemId: String, q: Int) {
        _quantities.value = _quantities.value + (itemId to q)
    }

    fun submit(reason: String) {
        if (_quantities.value.values.none { it > 0 }) return say("اختر قطعة واحدة على الأقل")
        if (reason.trim().length < 3) return say("اكتب سبب الإرجاع")
        _sending.value = true
        viewModelScope.launch {
            orders.requestReturn(number, reason, _quantities.value).orSay()?.let {
                say("تم إرسال طلب الإرجاع ${it.number}")
                _done.tryEmit(Unit)
            }
            _sending.value = false
        }
    }
}

@Composable
fun ReturnRequestScreen(number: String, onBack: () -> Unit, submitted: () -> Unit) {
    val vm = appViewModel(key = "return:$number") { ReturnRequestViewModel(number, it.orders) }
    val state by vm.order.collectAsStateWithLifecycle()
    val quantities by vm.quantities.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var reason by rememberSaveable { mutableStateOf("") }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.done.collect { submitted() } }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("طلب إرجاع", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { order ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("اختر القطع التي تريد إرجاعها", style = MaterialTheme.typography.titleSmall) }
                items(order.items.filter { it.returnableQty > 0 }, key = { it.id }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.productName, style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(item.color, item.size?.let { "مقاس $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                            val q = quantities[item.id] ?: 0
                            // Stepper starts at 0 here: picking nothing means "keep this item".
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.setQuantity(item.id, (q - 1).coerceAtLeast(0)) }, enabled = q > 0) { Text("−") }
                                Text("$q")
                                TextButton(onClick = { vm.setQuantity(item.id, (q + 1).coerceAtMost(item.returnableQty)) }, enabled = q < item.returnableQty) { Text("+") }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(500) },
                        label = { Text("سبب الإرجاع") },
                        placeholder = { Text("مثال: المقاس غير مناسب") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        "سيراجع فريقنا الطلب ثم يتواصل معك لاستلام القطع. يُرد المبلغ بعد فحصها.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { Button(onClick = { vm.submit(reason) }, enabled = !sending, modifier = Modifier.fillMaxWidth()) { Text("إرسال الطلب") } }
            }
        }
    }
}
