@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.orders

import com.dejavu.core.ui.theme.GlassTopBar
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.GoldBadge
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.customer.ui.components.GoldIconCircle
import androidx.compose.ui.text.font.FontWeight
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
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
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
import com.dejavu.core.model.Currency
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
import com.dejavu.customer.ui.components.SectionTitle
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

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { GlassTopBar(title = { Text("طلباتي") }) }) { padding ->
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
                        GlassCard(Modifier.fillMaxWidth(), onClick = { openOrder(o.number) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                GoldIconCircle(Icons.AutoMirrored.Filled.List)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(o.number, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        StatusChip(o.status.label, o.status.color())
                                    }
                                    Text("${formatDate(o.placedAt)} · ${o.count?.items ?: 0} منتج", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(o.format(o.grandTotal), style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold)
                                }
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
fun OrderDetailScreen(number: String, justPlaced: Boolean, onBack: () -> Unit, openProduct: (String) -> Unit) {
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
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    GlassCard(Modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(formatDateTime(order.placedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${order.paymentMethod.label} · ${order.paymentStatus.label}", style = MaterialTheme.typography.bodySmall)
                            }
                            StatusChip(order.status.label, order.status.color())
                        }
                    }
                }
                item { Timeline(order) }
                order.shipments.firstOrNull()?.let { s ->
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("الشحن مع ${s.carrier}", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                            s.trackingNumber?.let { Text("رقم التتبع: $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                item { SectionTitle("المنتجات") }
                items(order.items, key = { it.id }) { item ->
                    GlassCard(Modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 14.dp) {
                        SummaryRow("${item.productName} × ${item.quantity}", order.format(item.lineTotal), valueColor = Liquid.GoldDeep)
                        Text(
                            listOfNotNull(item.color, item.size?.let { "مقاس $it" }, if (item.returnedQty > 0) "مرتجع: ${item.returnedQty}" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SummaryRow("المجموع", order.format(order.subtotal))
                        if (!Money.isZero(order.discountTotal)) SummaryRow("الخصم" + (order.coupon?.let { " (${it.code})" } ?: ""), "- ${order.format(order.discountTotal)}", valueColor = Success)
                        SummaryRow("الشحن", order.format(order.shippingFee))
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Liquid.Gold.copy(alpha = 0.25f))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("الإجمالي", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            GoldBadge(order.format(order.grandTotal))
                        }
                        if (order.currency != Currency.SAR) {
                            Text(
                                "سعر الصرف المثبّت في الفاتورة: 1 ر.س = ${Money.plain(Money.parse(order.exchangeRate), order.currency)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("التوصيل إلى", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                        val a = order.shippingAddress
                        Text("${a.recipient} · ${a.phone}", style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(a.street, a.district, a.city, a.governorate).joinToString("، "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                order.cancelReason?.let { item { Text("سبب الإلغاء: $it", color = MaterialTheme.colorScheme.error) } }
                if (order.canPayByCard) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("بانتظار الدفع بالبطاقة", style = MaterialTheme.typography.titleSmall, color = Warning)
                                Text(
                                    "أكمل الدفع لنبدأ تجهيز طلبك. بعد الدفع ارجع إلى التطبيق وسيتم تحديث الطلب تلقائياً.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = vm::payByCard, enabled = !paying, modifier = Modifier.fillMaxWidth()) {
                                    // Cards are charged in SAR, the base currency.
                                    Text("ادفع الآن ${Money.formatBase((Money.parse(order.grandTotal) - Money.parse(order.amountPaid)).toPlainString())}")
                                }
                            }
                        }
                    }
                }
                if (order.canCancel) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { askCancel = true }, enabled = !cancelling) { Text("إلغاء الطلب") }
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
    GlassCard(Modifier.fillMaxWidth()) {
        Column {
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
                        if (i < shown.lastIndex) Box(Modifier.width(2.dp).height(28.dp).background(if (done) Liquid.Gold.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline))
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
