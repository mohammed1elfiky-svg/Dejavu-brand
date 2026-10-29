@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.orders

import com.dejavu.core.ui.theme.GlassTopBar
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.GoldBadge
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.customer.ui.components.GoldIconCircle
import androidx.compose.ui.text.font.FontWeight
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dejavu.core.data.userMessage
import com.dejavu.customer.ui.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                                if (order.awaitingApproval) {
                                    Text("بانتظار موافقة المتجر على الطلب بالآجل", style = MaterialTheme.typography.bodySmall, color = com.dejavu.core.ui.theme.Liquid.GoldDeep)
                                }
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
                order.shippingReceiptFileId?.takeIf { it.isNotBlank() }?.let { fileId ->
                    item { ShippingReceiptCard(fileId) }
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
                        if (!Money.isZero(order.shippingFee)) SummaryRow("الشحن", order.format(order.shippingFee))
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
                        val a = order.shippingAddress
                        val office = order.carrierOffice
                        when (order.deliveryMethod) {
                            com.dejavu.core.model.DeliveryMethods.PICKUP -> {
                                Text("الاستلام من المحل", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                                Text("نبلغك عندما يجهز طلبك للاستلام", style = MaterialTheme.typography.bodySmall)
                            }
                            com.dejavu.core.model.DeliveryMethods.OFFICE -> {
                                Text("الاستلام من مكتب ${order.carrier?.name ?: "شركة الشحن"}", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                                if (office != null) {
                                    Text("${office.name} · ${office.governorate}${office.city?.let { "، $it" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                                    Text(office.address, style = MaterialTheme.typography.bodySmall)
                                    if (office.phones.isNotEmpty()) Text("📞 ${office.phones.joinToString(" / ")}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            else -> {
                                Text("التوصيل إلى", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                                Text("${a.recipient} · ${a.phone}", style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(a.street, a.district, a.city, a.governorate).joinToString("، "), style = MaterialTheme.typography.bodySmall)
                                order.carrier?.let { Text("مع ${it.name}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
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

/** Photo of the shipping receipt (سند الإرسال) the store sent; a tap shows it full screen. */
@Composable
private fun ShippingReceiptCard(fileId: String) {
    val container = appContainer()
    var attempt by remember { mutableStateOf(0) }
    var enlarged by remember { mutableStateOf(false) }
    // Private to the customer: loaded with their token, not as a public image URL.
    val image by produceState<Load<ImageBitmap>>(Load.Loading, fileId, attempt) {
        value = Load.Loading
        val bytes = container.privateFile(fileId).getOrElse {
            value = Load.Failed(it.userMessage())
            return@produceState
        }
        val bitmap = withContext(Dispatchers.Default) { decodeScaled(bytes) }
        value = if (bitmap != null) Load.Ready(bitmap) else Load.Failed("تعذّر عرض صورة سند الإرسال")
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Text("سند الإرسال", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
        Spacer(Modifier.height(8.dp))
        when (val state = image) {
            Load.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Liquid.Gold)
            }
            is Load.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { attempt++ }) { Text("إعادة المحاولة", color = Liquid.GoldDeep) }
            }
            is Load.Ready -> {
                Image(
                    bitmap = state.value,
                    contentDescription = "سند الإرسال",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)).clickable { enlarged = true },
                )
                Spacer(Modifier.height(6.dp))
                Text("اضغط على الصورة لتكبيرها", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    val ready = image as? Load.Ready
    if (enlarged && ready != null) {
        Dialog(onDismissRequest = { enlarged = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable { enlarged = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = ready.value,
                    contentDescription = "سند الإرسال",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                IconButton(onClick = { enlarged = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = Color.White)
                }
            }
        }
    }
}

/** Decodes a photo, scaled down so its longer side stays within [maxSide] pixels. */
private fun decodeScaled(bytes: ByteArray, maxSide: Int = 2048): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** One step of the order tracking stepper. */
private data class TrackStep(val title: String, val at: String?, val state: StepState)

private enum class StepState { DONE, CURRENT, TODO, FAILED }

/** The stepper's steps: placed → confirmed → preparing → shipped → delivered, or a red end when cancelled / not received. */
private fun trackSteps(order: OrderDetail): List<TrackStep> {
    val flow = listOf(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.DELIVERED)
    val titles = listOf("تم الطلب", "تم التأكيد", "قيد التجهيز", "تم الشحن", "تم التسليم")
    // When each status was reached (the latest time, should it repeat).
    val reached = order.statusHistory.groupBy { it.toStatus }.mapValues { (_, v) -> v.maxOf { it.createdAt } }
    val shipment = order.shipments.firstOrNull()
    fun dateOf(status: OrderStatus): String? = when (status) {
        OrderStatus.PENDING -> order.placedAt
        OrderStatus.SHIPPED -> reached[status] ?: shipment?.shippedAt
        OrderStatus.DELIVERED -> reached[status] ?: order.deliveredAt ?: shipment?.deliveredAt ?: reached[OrderStatus.COMPLETED]
        else -> reached[status]
    }
    val ended = order.status == OrderStatus.CANCELLED || order.status == OrderStatus.RETURNED
    if (!ended) {
        val current = if (order.status == OrderStatus.COMPLETED) flow.lastIndex else flow.indexOf(order.status).coerceAtLeast(0)
        return flow.mapIndexed { i, status ->
            val state = when {
                i < current -> StepState.DONE
                i == current -> if (i == flow.lastIndex) StepState.DONE else StepState.CURRENT
                else -> StepState.TODO
            }
            TrackStep(titles[i], if (i <= current) dateOf(status) else null, state)
        }
    }
    // Cancelled or not received: the steps it got through, then the red end.
    val last = flow.indices.filter { i -> i == 0 || flow[i] in reached }.maxOrNull() ?: 0
    val through = (0..last).map { i -> TrackStep(titles[i], dateOf(flow[i]), StepState.DONE) }
    val endAt = reached[order.status] ?: order.cancelledAt.takeIf { order.status == OrderStatus.CANCELLED }
    val endTitle = if (order.status == OrderStatus.CANCELLED) "تم إلغاء الطلب" else "لم يتم الاستلام"
    return through + TrackStep(endTitle, endAt, StepState.FAILED)
}

/** Vertical tracking stepper: done steps in gold with a check, the current one glowing, the rest faded. */
@Composable
private fun Timeline(order: OrderDetail) {
    val steps = remember(order) { trackSteps(order) }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("تتبّع الطلب", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Column {
            steps.forEachIndexed { i, step ->
                val danger = MaterialTheme.colorScheme.error
                val dotColor = when (step.state) {
                    StepState.DONE, StepState.CURRENT -> Liquid.Gold
                    StepState.FAILED -> danger
                    StepState.TODO -> Liquid.InkSoft.copy(alpha = 0.35f)
                }
                val next = steps.getOrNull(i + 1)
                val lineColor = when {
                    next == null -> Color.Transparent
                    next.state == StepState.FAILED -> danger.copy(alpha = 0.5f)
                    next.state == StepState.TODO -> Liquid.InkSoft.copy(alpha = 0.25f)
                    else -> Liquid.Gold.copy(alpha = 0.7f)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (step.state) {
                            StepState.DONE -> Box(Modifier.size(24.dp).clip(CircleShape).background(Liquid.GoldBrush), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                            StepState.CURRENT -> Box(
                                Modifier.size(24.dp).clip(CircleShape).background(Liquid.GoldPale),
                                contentAlignment = Alignment.Center,
                            ) { Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor)) }
                            StepState.FAILED -> Box(Modifier.size(24.dp).clip(CircleShape).background(danger), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                            StepState.TODO -> Box(Modifier.padding(top = 5.dp).size(14.dp).clip(CircleShape).background(dotColor))
                        }
                        if (next != null) Box(Modifier.width(2.dp).height(30.dp).background(lineColor))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(top = 2.dp)) {
                        Text(
                            step.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (step.state == StepState.CURRENT || step.state == StepState.FAILED) FontWeight.Bold else FontWeight.Normal,
                            color = when (step.state) {
                                StepState.CURRENT -> Liquid.GoldDeep
                                StepState.FAILED -> danger
                                StepState.TODO -> MaterialTheme.colorScheme.onSurfaceVariant
                                StepState.DONE -> Liquid.Ink
                            },
                        )
                        step.at?.let { at ->
                            val text = formatDateTime(at)
                            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (step.state == StepState.CURRENT) {
                            Text("المرحلة الحالية", style = MaterialTheme.typography.labelSmall, color = Liquid.GoldDeep)
                        }
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
