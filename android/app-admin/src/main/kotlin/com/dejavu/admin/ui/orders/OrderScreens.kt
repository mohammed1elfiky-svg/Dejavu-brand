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
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.ListDetail
import com.dejavu.admin.ui.RecentItem
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.isWideScreen
import androidx.compose.runtime.LaunchedEffect
import com.dejavu.admin.ui.tint
import com.dejavu.core.admin.AdminOrderDetail
import com.dejavu.core.admin.WalletAdminRepository
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.ui.media.rememberImagePicker
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip

private val PAY_METHODS = listOf(PaymentMethod.CASH, PaymentMethod.INSTAPAY, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER, PaymentMethod.CARD)
private val REFUND_METHODS = listOf(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.INSTAPAY, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER)

// -------------------------------------------------------------- order list ---

/** Lists beside the order statuses: merchants' orders to approve, reservations to put aside. */
enum class OrderView { APPROVAL, HOLD }

class OrdersViewModel(private val repo: AdminOrderRepository, private val extras: com.dejavu.core.admin.SalesExtrasRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<Page<AdminOrderSummary>>>(Load.Loading)
    val state = _state.asStateFlow()
    var status: OrderStatus? = OrderStatus.PENDING
        private set
    var query: String = ""
        private set
    var view: OrderView? = null
        private set

    /** Reservations nobody put aside yet (pre-orders that arrived, deposits), shown with the orders. */
    private val _toHold = MutableStateFlow<Load<List<com.dejavu.core.admin.Layaway>>>(Load.Loading)
    val toHold = _toHold.asStateFlow()
    private val _holding = MutableStateFlow<String?>(null)
    val holding = _holding.asStateFlow()

    /** [choice]: an [OrderStatus], an [OrderView], or null for all. */
    fun choose(choice: Any?) {
        view = choice as? OrderView
        status = choice as? OrderStatus
        load(reset = true)
    }

    fun filter(status: OrderStatus?) = choose(status)

    fun hold(l: com.dejavu.core.admin.Layaway) {
        if (_holding.value != null) return
        _holding.value = l.id
        viewModelScope.launch {
            extras.holdLayaway(l.id).orSay()?.let {
                val list = (_toHold.value as? Load.Ready)?.value
                if (list != null) _toHold.value = Load.Ready(list.filterNot { x -> x.id == l.id })
                say("تم حجز قطع ${l.number} للعميل")
            }
            _holding.value = null
        }
    }

    fun search(text: String) {
        query = text
        load(reset = true)
    }

    fun clearFilters() {
        status = null
        view = null
        query = ""
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        if (view == OrderView.HOLD) {
            if (reset || _toHold.value !is Load.Ready) _toHold.value = Load.Loading
            viewModelScope.launch { _toHold.value = extras.layawaysToHold().map { it.items }.toLoad() }
            return
        }
        if (reset || _state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = fetch() }
    }

    private suspend fun fetch() = repo.list(status, query, awaitingApproval = view == OrderView.APPROVAL).toLoad()

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) {
        if (view == OrderView.HOLD) _toHold.value = extras.layawaysToHold().map { it.items }.toLoad() else _state.value = fetch()
    }
}

/**
 * The orders tab. On a wide screen with a [detailPane] the chosen order opens beside the list;
 * otherwise [openOrder] navigates to it.
 */
@Composable
fun OrdersScreen(openOrder: (String) -> Unit, detailPane: (@Composable (id: String, close: () -> Unit) -> Unit)? = null) {
    if (detailPane != null && isWideScreen()) {
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        ListDetail(
            list = { OrdersList(openOrder = { selected = it }, selected = selected) },
            detail = {
                val id = selected
                if (id == null) {
                    EmptyState(Icons.AutoMirrored.Filled.List, "اختر طلباً", "تظهر تفاصيل الطلب هنا")
                } else {
                    androidx.compose.runtime.key(id) { detailPane(id) { selected = null } }
                }
            },
        )
    } else {
        OrdersList(openOrder, selected = null)
    }
}

@Composable
private fun OrdersList(openOrder: (String) -> Unit, selected: String?) {
    val vm = adminViewModel { OrdersViewModel(it.orders, it.salesExtras) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toHold by vm.toHold.collectAsStateWithLifecycle()
    val holding by vm.holding.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
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
            ChipFilter<Any>(
                listOf(OrderView.APPROVAL, OrderView.HOLD, null) + OrderStatus.entries,
                vm.view ?: vm.status,
                {
                    when (it) {
                        OrderView.APPROVAL -> "بانتظار موافقتك"
                        OrderView.HOLD -> "حجوزات للتجهيز"
                        is OrderStatus -> it.label
                        else -> "الكل"
                    }
                },
                vm::choose,
            )
            if (vm.view == OrderView.HOLD) {
                LoadContent(toHold, onRetry = { vm.load() }) { list ->
                    RefreshList(refreshing, onRefresh = vm::refresh) {
                        if (list.isEmpty()) {
                            EmptyList(Icons.AutoMirrored.Filled.List, "لا توجد حجوزات للتجهيز", "تظهر هنا الطلبات المسبقة التي وصلت والحجوزات بعربون حتى يحجز أحد الموظفين قطعها")
                        } else {
                            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(list, key = { it.id }) { l -> HoldRow(l, busy = holding == l.id) { vm.hold(l) } }
                            }
                        }
                    }
                }
                return@Column
            }
            LoadContent(state, onRetry = { vm.load() }) { page ->
                RefreshList(refreshing, onRefresh = vm::refresh) {
                    if (page.items.isEmpty()) {
                        val filtered = vm.status != null || vm.query.isNotBlank()
                        EmptyList(
                            Icons.AutoMirrored.Filled.List,
                            "لا توجد طلبات",
                            if (filtered) "لا طلبات تطابق البحث أو الحالة المختارة" else "ستظهر هنا طلبات المتجر ونقطة البيع",
                            action = if (filtered) "عرض كل الطلبات" else null,
                            onAction = {
                                query = ""
                                vm.clearFilters()
                            },
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Text("${page.total} طلب", style = MaterialTheme.typography.labelMedium) }
                            items(page.items, key = { it.id }) { o -> OrderRow(o, o.id == selected) { openOrder(o.id) } }
                        }
                    }
                }
            }
        }
    }
}

/** A reservation to put aside: its pieces, the customer, and "تم حجز القطع". */
@Composable
private fun HoldRow(l: com.dejavu.core.admin.Layaway, busy: Boolean, onHold: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.number, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusChip("بانتظار الحجز", com.dejavu.core.ui.theme.Warning)
            }
            Text("${l.customer.name} · ${l.customer.phone}", style = MaterialTheme.typography.bodyMedium)
            l.items.forEach { i ->
                Text(
                    listOfNotNull(i.name, i.size?.let { "مقاس $it" }, i.color, "× ${i.quantity}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            l.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(formatDateTime(l.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onHold, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("تم حجز القطع") }
        }
    }
}

@Composable
private fun OrderRow(o: AdminOrderSummary, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.number, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (o.awaitingApproval) StatusChip("بانتظار موافقتك", com.dejavu.core.ui.theme.Warning) else StatusChip(o.status.label, o.status.tint())
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

class OrderDetailViewModel(
    private val id: String,
    private val repo: AdminOrderRepository,
    private val files: WalletAdminRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<AdminOrderDetail>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    /** Photo of the shipping receipt (سند الإرسال); null while the order has none. */
    private val _shipPhoto = MutableStateFlow<Load<ImageBitmap>?>(null)
    val shipPhoto = _shipPhoto.asStateFlow()
    private var shipPhotoFor: String? = null
    private val _sendingReceipt = MutableStateFlow(false)
    val sendingReceipt = _sendingReceipt.asStateFlow()
    /** Set right after the order is marked shipped: offer to photograph the shipping receipt. */
    private val _suggestReceipt = MutableStateFlow(false)
    val suggestReceipt = _suggestReceipt.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            val res = repo.get(id)
            _state.value = res.toLoad()
            res.getOrNull()?.let { loadShipPhoto(it.shippingReceiptFileId) }
        }
    }

    fun loadShipPhoto(fileId: String?, force: Boolean = false) {
        if (fileId == null) {
            shipPhotoFor = null
            _shipPhoto.value = null
            return
        }
        if (!force && shipPhotoFor == fileId && _shipPhoto.value is Load.Ready) return
        shipPhotoFor = fileId
        _shipPhoto.value = Load.Loading
        viewModelScope.launch {
            _shipPhoto.value = files.receipt(fileId).fold(
                onSuccess = { data ->
                    val image = withContext(Dispatchers.Default) {
                        runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull()
                    }
                    if (image != null) Load.Ready(image) else Load.Failed("تعذّر عرض صورة السند")
                },
                onFailure = { Result.failure<ImageBitmap>(it).toLoad() },
            )
        }
    }

    /** Uploads the photo of the shipping receipt; the customer is notified and sees it on the order. */
    fun sendShippingReceipt(bytes: ByteArray) {
        if (_sendingReceipt.value) return
        _sendingReceipt.value = true
        _suggestReceipt.value = false
        viewModelScope.launch {
            repo.sendShippingReceipt(id, bytes).orSay()?.let {
                _state.value = Load.Ready(it)
                say("أُرسل سند الإرسال للعميل")
                loadShipPhoto(it.shippingReceiptFileId, force = true)
            }
            _sendingReceipt.value = false
        }
    }

    fun dismissReceiptSuggestion() {
        _suggestReceipt.value = false
    }

    fun changeStatus(status: OrderStatus, note: String? = null, carrier: String? = null, tracking: String? = null) =
        act("تم: ${status.label}", then = { o -> if (status == OrderStatus.SHIPPED && o.isAppOrder) { _suggestReceipt.value = true } }) {
            repo.changeStatus(id, status, note, carrier, tracking)
        }

    /** Hands the order to the carrier; a receipt photo taken in the dialog is sent right after. */
    fun ship(carrier: String, tracking: String, receipt: ByteArray?) =
        act("تم: ${OrderStatus.SHIPPED.label}", then = { o ->
            if (receipt != null) {
                sendShippingReceipt(receipt)
            } else if (o.isAppOrder) {
                _suggestReceipt.value = true
            }
        }) {
            repo.changeStatus(id, OrderStatus.SHIPPED, null, carrier, tracking)
        }

    fun approve() = act("تمت الموافقة على طلب التاجر وتسجيله على حسابه") { repo.approve(id) }
    fun reject(reason: String?) = act("تم رفض الطلب") { repo.reject(id, reason) }
    fun editItems(quantities: Map<String, Int>) = act("تم تعديل الكميات") { repo.editItems(id, quantities) }

    fun pay(amount: String, method: PaymentMethod, reference: String, currency: Currency) =
        act("تم تسجيل الدفعة") { repo.recordPayment(id, method, amount, reference, currency = currency) }

    fun refund(amount: String, method: PaymentMethod, reason: String, currency: Currency) =
        act("تم رد المبلغ") { repo.refund(id, method, amount, reason, currency = currency) }

    private fun act(done: String, then: (AdminOrderDetail) -> Unit = {}, block: suspend () -> Result<AdminOrderDetail>) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block().orSay()?.let {
                _state.value = Load.Ready(it)
                say(done)
                then(it)
            }
            _busy.value = false
        }
    }
}

/** Ordered in the customer app (till sales are numbered POS-…). */
private val AdminOrderDetail.isAppOrder: Boolean get() = !number.startsWith("POS-")

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
    val vm = adminViewModel(key = "order:$id") { OrderDetailViewModel(id, it.orders, it.wallet) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val shipPhoto by vm.shipPhoto.collectAsStateWithLifecycle()
    val sendingReceipt by vm.sendingReceipt.collectAsStateWithLifecycle()
    val suggestReceipt by vm.suggestReceipt.collectAsStateWithLifecycle()
    val receiptPicker = rememberImagePicker { bytes -> vm.sendShippingReceipt(bytes) }
    var zoom by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<OrderDialog?>(null) }
    var editingQuantities by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    val uiPrefs = adminContainer().uiPrefs
    val loaded = (state as? Load.Ready)?.value
    LaunchedEffect(loaded?.id) {
        if (loaded != null) uiPrefs.addRecent(RecentItem("order", id, loaded.number, loaded.customer.name))
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

                // A merchant's order on credit: the owner approves, changes quantities or rejects first.
                if (o.awaitingApproval && session.can(Perm.ORDERS_UPDATE)) {
                    item {
                        InfoCard {
                            Text("طلب تاجر بالآجل بانتظار موافقتك", style = MaterialTheme.typography.titleSmall, color = com.dejavu.core.ui.theme.Warning)
                            Text("عند الموافقة يُسجَّل الطلب على حساب التاجر ويُؤكَّد للتجهيز.", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = vm::approve, enabled = !busy) { Text("موافقة") }
                                OutlinedButton(onClick = { editingQuantities = true }, enabled = !busy) { Text("تعديل الكميات") }
                                OutlinedButton(onClick = { rejecting = true }, enabled = !busy) { Text("رفض", color = MaterialTheme.colorScheme.error) }
                            }
                        }
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
                        val office = o.carrierOffice
                        when (o.deliveryMethod) {
                            "PICKUP" -> Text("🏬 يستلمه العميل من المحل", color = Lux.GoldDeep, style = MaterialTheme.typography.titleSmall)
                            "OFFICE" -> {
                                Text("📦 استلام من مكتب ${o.carrier?.name ?: a.carrier ?: "شركة الشحن"}", color = Lux.GoldDeep, style = MaterialTheme.typography.titleSmall)
                                if (office != null) {
                                    Text("${office.name} · ${office.governorate}${office.city?.let { " / $it" } ?: ""} · ${office.address}", style = MaterialTheme.typography.bodySmall)
                                    if (office.phones.isNotEmpty()) Text("هاتف المكتب: ${office.phones.joinToString(" / ")}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            else -> o.carrier?.let { Text("شركة الشحن المختارة: ${it.name}", style = MaterialTheme.typography.bodySmall, color = Lux.GoldDeep) }
                        }
                        if (o.deliveryMethod == "HOME") Text(
                            listOfNotNull(a.street, a.building?.let { "مبنى $it" }, a.floor?.let { "دور $it" }, a.apartment?.let { "شقة $it" }, a.district, a.city, a.governorate).joinToString("، "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        a.landmark?.let { Text("علامة: $it", style = MaterialTheme.typography.bodySmall) }
                        Text("المستلم: ${a.recipient} · ${a.phone}", style = MaterialTheme.typography.bodySmall)
                        if (a.latitude != null && a.longitude != null) {
                            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            androidx.compose.material3.TextButton(onClick = { runCatching { uriHandler.openUri("https://maps.google.com/?q=${a.latitude},${a.longitude}") } }) {
                                Text("📍 فتح موقع العميل على الخريطة")
                            }
                        }
                        o.customerNote?.let { Text("ملاحظة العميل: $it", color = MaterialTheme.colorScheme.secondary) }
                        if (o.isAppOrder && o.deliveryMethod != "PICKUP") ShippingLabelButton(o)
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
                if (o.isAppOrder && (session.can(Perm.ORDERS_UPDATE) || o.shippingReceiptFileId != null)) {
                    item {
                        ShippingReceiptCard(
                            hasReceipt = o.shippingReceiptFileId != null,
                            photo = shipPhoto,
                            canSend = session.can(Perm.ORDERS_UPDATE),
                            sending = sendingReceipt,
                            onCamera = receiptPicker.fromCamera,
                            onGallery = receiptPicker.fromGallery,
                            onRetry = { vm.loadShipPhoto(o.shippingReceiptFileId, force = true) },
                            onZoom = { zoom = true },
                        )
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
                OrderDialog.Ship -> ShipDialog(o, onDismiss = { dialog = null }) { carrier, tracking, receipt -> vm.ship(carrier, tracking, receipt) }
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
            if (editingQuantities) {
                EditQuantitiesDialog(o, onDismiss = { editingQuantities = false }) { changes ->
                    editingQuantities = false
                    if (changes.isNotEmpty()) vm.editItems(changes)
                }
            }
            if (rejecting) {
                TextDialog("رفض طلب التاجر", "السبب (يظهر للتاجر، اختياري)", "رفض الطلب", { rejecting = false }, minLength = 0) { vm.reject(it) }
            }

            if (suggestReceipt && o.isAppOrder) {
                AlertDialog(
                    onDismissRequest = vm::dismissReceiptSuggestion,
                    title = { Text("تصوير سند الإرسال") },
                    text = { Text("تم شحن الطلب. صوّر سند الإرسال الآن ليصل للعميل ويراه في التطبيق.") },
                    confirmButton = {
                        TextButton(onClick = { vm.dismissReceiptSuggestion(); receiptPicker.fromCamera() }) { Text("تصوير") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { vm.dismissReceiptSuggestion(); receiptPicker.fromGallery() }) { Text("من المعرض") }
                            TextButton(onClick = vm::dismissReceiptSuggestion) { Text("لاحقاً") }
                        }
                    },
                )
            }
        }
    }
    val image = (shipPhoto as? Load.Ready)?.value
    if (zoom && image != null) PhotoZoomDialog(image) { zoom = false }
}

/** "سند الإرسال": the photo sent to the customer (tap to enlarge), with take / replace. */
@OptIn(ExperimentalLayoutApi::class)
/** New quantity per line of a merchant's order (0 removes the line). */
@Composable
private fun EditQuantitiesDialog(order: AdminOrderDetail, onDismiss: () -> Unit, onSave: (Map<String, Int>) -> Unit) {
    val quantities = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>().apply { order.items.forEach { put(it.id, it.quantity) } } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل الكميات") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                order.items.forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listOfNotNull(i.productName, i.size, i.color).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        val q = quantities[i.id] ?: i.quantity
                        androidx.compose.material3.IconButton(onClick = { quantities[i.id] = (q - 1).coerceAtLeast(0) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        Text("$q", style = MaterialTheme.typography.titleMedium)
                        androidx.compose.material3.IconButton(onClick = { quantities[i.id] = q + 1 }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    }
                }
                Text("0 يحذف المنتج من الطلب. السعر والخصم لكل قطعة يبقيان كما هما.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(quantities.filter { (id, q) -> order.items.first { it.id == id }.quantity != q }) },
                enabled = quantities.values.any { it > 0 },
            ) { Text("حفظ") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun ShippingReceiptCard(
    hasReceipt: Boolean,
    photo: Load<ImageBitmap>?,
    canSend: Boolean,
    sending: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRetry: () -> Unit,
    onZoom: () -> Unit,
) {
    InfoCard {
        Text("سند الإرسال", style = MaterialTheme.typography.titleSmall)
        if (hasReceipt && photo != null) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).clip(RoundedCornerShape(16.dp)).background(Lux.Champagne),
                contentAlignment = Alignment.Center,
            ) {
                when (photo) {
                    Load.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                    is Load.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                        Text(photo.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
                    }
                    is Load.Ready -> Image(
                        photo.value,
                        contentDescription = "سند الإرسال",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onZoom),
                    )
                }
            }
            Text("أُرسل للعميل ويظهر له في الطلب. اضغط الصورة للتكبير.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("صوّر سند شركة الشحن ليُرسل للعميل ويراه في التطبيق.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canSend) {
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCamera, enabled = !sending) { Text(if (hasReceipt) "استبدال بصورة جديدة" else "تصوير سند الإرسال") }
                OutlinedButton(onClick = onGallery, enabled = !sending) { Text("من المعرض") }
            }
        }
    }
}

/** Full-screen photo with pinch to zoom and drag. */
@Composable
private fun PhotoZoomDialog(image: ImageBitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                image,
                contentDescription = "سند الإرسال",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoomBy, _ ->
                            scale = (scale * zoomBy).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShipDialog(order: AdminOrderDetail, onDismiss: () -> Unit, onShip: (carrier: String, tracking: String, receipt: ByteArray?) -> Unit) {
    val repo = adminContainer().round9
    var carriers by remember { mutableStateOf<List<com.dejavu.core.admin.Carrier>>(emptyList()) }
    LaunchedEffect(Unit) { carriers = repo.carriers().getOrNull().orEmpty().filter { it.isActive } }
    var carrier by rememberSaveable { mutableStateOf(order.carrier?.name ?: order.shippingAddress.carrier ?: "") }
    var tracking by rememberSaveable { mutableStateOf("") }
    var receipt by remember { mutableStateOf<ByteArray?>(null) }
    val picker = rememberImagePicker { bytes -> receipt = bytes }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسليم لشركة الشحن") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("سيتم خصم المخزون وتسجيل قيد المبيعات والتكلفة.", style = MaterialTheme.typography.bodySmall)
                if (order.paymentMethod == PaymentMethod.CASH_ON_DELIVERY) {
                    Text("المطلوب تحصيله: ${if (order.isForeign) order.inOrderCurrency(order.balanceDue) else Money.format(order.balanceDue)}", color = MaterialTheme.colorScheme.secondary)
                }
                if (carriers.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        carriers.forEach { c -> FilterChip(selected = carrier == c.name, onClick = { carrier = c.name }, label = { Text(c.name) }) }
                    }
                }
                OutlinedTextField(value = carrier, onValueChange = { carrier = it }, label = { Text("شركة الشحن") }, singleLine = true)
                OutlinedTextField(value = tracking, onValueChange = { tracking = it }, label = { Text("رقم التتبع (اختياري)") }, singleLine = true)
                Text("صورة سند الشحن", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = picker.fromCamera) { Text("📷 تصوير") }
                    OutlinedButton(onClick = picker.fromGallery) { Text("من المعرض") }
                    if (receipt != null) Text("✓ أُرفقت", color = Success)
                }
                if (order.isAppOrder) Text("تُرسل صورة السند للعميل ويراها في تفاصيل طلبه.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onShip(carrier.trim(), tracking.trim(), receipt) }, enabled = carrier.isNotBlank()) { Text("شحن") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

/** Prints the parcel label: customer name, phone, address and the order number. */
@Composable
private fun ShippingLabelButton(o: AdminOrderDetail) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = adminContainer()
    val settings by container.printers.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var printing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val a = o.shippingAddress
    val office = o.carrierOffice
    val address = if (o.deliveryMethod == "OFFICE" && office != null) {
        "مكتب ${office.name} · ${office.governorate}${office.city?.let { "، $it" } ?: ""} · ${office.address}"
    } else {
        listOfNotNull(a.governorate, a.city, a.district, a.street, a.building?.let { "مبنى $it" }, a.landmark).joinToString("، ")
    }
    val collect = if (o.paymentMethod == PaymentMethod.CASH_ON_DELIVERY && !Money.isZero(o.balanceDue)) {
        if (o.isForeign) o.inOrderCurrency(o.balanceDue) else Money.format(o.balanceDue)
    } else {
        null
    }
    OutlinedButton(
        onClick = {
            printing = true
            scope.launch {
                val label = com.dejavu.admin.print.ShippingLabel(o.number, a.recipient, a.phone, address, o.carrier?.name ?: a.carrier, collect)
                status = com.dejavu.admin.print.Printers.printShippingLabel(context, settings, label).fold({ "طُبع ملصق الشحن ✓" }, { it.message ?: "تعذّرت الطباعة" })
                printing = false
            }
        },
        enabled = !printing,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (printing) "جارٍ الطباعة…" else "🖨 طباعة ملصق الشحن") }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
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

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) { _state.value = repo.list(status?.name).toLoad() }
}

@Composable
fun ReturnsScreen(onBack: () -> Unit, openReturn: (String) -> Unit) {
    val vm = adminViewModel { ReturnsViewModel(it.returns) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("المرتجعات", onBack) }) { padding ->
        Column(Modifier.padding(padding)) {
            ChipFilter(listOf(null) + ReturnStatus.entries, vm.status, { it?.label ?: "الكل" }, vm::filter)
            LoadContent(state, onRetry = { vm.load() }) { page ->
                RefreshList(refreshing, onRefresh = vm::refresh) {
                if (page.items.isEmpty()) {
                    EmptyList(
                        Icons.AutoMirrored.Filled.List,
                        "لا توجد مرتجعات",
                        if (vm.status != null) "لا مرتجعات بهذه الحالة" else "تظهر هنا طلبات الإرجاع من العملاء",
                        action = if (vm.status != null) "عرض الكل" else null,
                        onAction = { vm.filter(null) },
                    )
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
