@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.preorders

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ChipFilter
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.layaway.ChosenCustomer
import com.dejavu.admin.ui.layaway.CustomerPickerDialog
import com.dejavu.admin.ui.layaway.PiecePickerDialog
import com.dejavu.core.admin.UpdatePreOrderRequest
import com.dejavu.core.model.Money
import androidx.compose.foundation.verticalScroll
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminPreOrder
import com.dejavu.core.admin.CreatePreOrderRequest
import com.dejavu.core.admin.IncomingVariant
import com.dejavu.core.admin.OffersRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.QuantityStepper
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What staff type for a pre-order: a size from the catalogue, or a description of the piece. */
internal data class PreOrderFields(
    val variantId: String? = null,
    val variantLabel: String = "",
    val itemName: String = "",
    val itemSize: String = "",
    val itemColor: String = "",
    val quantity: Int = 1,
    val unitPrice: String = "",
    val note: String = "",
)

private fun preOrderStatusLabel(s: String?): String = when (s) {
    "WAITING" -> "بانتظار الوصول"
    "NOTIFIED" -> "وصلت"
    "CONVERTED" -> "تم"
    "CANCELLED" -> "ملغاة"
    else -> "الكل"
}

@Composable
private fun preOrderStatusColor(s: String) = when (s) {
    "WAITING" -> Warning
    "NOTIFIED" -> Lux.GoldDeep
    "CONVERTED" -> Success
    else -> MaterialTheme.colorScheme.error
}

private fun variantText(size: String?, color: String?): String =
    listOfNotNull(size?.let { "مقاس $it" }, color).joinToString(" · ")

class PreOrdersViewModel(private val repo: OffersRepository) : BaseViewModel() {
    private val _status = MutableStateFlow<String?>("WAITING")
    val status = _status.asStateFlow()
    private val _list = MutableStateFlow<Load<List<AdminPreOrder>>>(Load.Loading)
    val list = _list.asStateFlow()
    private val _incoming = MutableStateFlow<Load<List<IncomingVariant>>>(Load.Loading)
    val incoming = _incoming.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val refresher = Refresher()
    private var listJob: Job? = null
    private var incomingJob: Job? = null

    fun setStatus(s: String?) {
        if (s == _status.value) return
        _status.value = s
        _list.value = Load.Loading
        loadList()
    }

    fun load() {
        loadList()
        loadIncoming()
    }

    /** Loads quietly when the list is already on screen. */
    fun loadList() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val r = repo.preOrders(_status.value)
            if (_list.value is Load.Ready) r.fold({ _list.value = Load.Ready(it) }, { say(it.userMessage()) }) else _list.value = r.toLoad()
        }
    }

    fun loadIncoming() {
        incomingJob?.cancel()
        incomingJob = viewModelScope.launch {
            val r = repo.incoming()
            if (_incoming.value is Load.Ready) r.fold({ _incoming.value = Load.Ready(it) }, { say(it.userMessage()) }) else _incoming.value = r.toLoad()
        }
    }

    fun refresh() = refresher.run(viewModelScope) {
        repo.preOrders(_status.value).fold({ _list.value = Load.Ready(it) }, { say(it.userMessage()) })
        repo.incoming().fold({ _incoming.value = Load.Ready(it) }, { say(it.userMessage()) })
    }

    /** Turns the pre-order into a layaway; [opened] gets the layaway id. */
    fun convert(p: AdminPreOrder, opened: (String) -> Unit) = act {
        repo.convertPreOrder(p.id).orSay()?.let { r ->
            say("تم حجز القطع للعميلة ${r.layaway.customer.name} (${r.layaway.number})")
            loadList()
            opened(r.layaway.id)
        }
    }

    fun cancel(p: AdminPreOrder) = act {
        repo.cancelPreOrder(p.id).orSay()?.let {
            say("تم إلغاء الحجز ${p.number}")
            loadList()
            loadIncoming()
        }
    }

    internal fun create(v: IncomingVariant, customer: ChosenCustomer, quantity: Int, note: String) = act {
        repo.createPreOrder(CreatePreOrderRequest(customer.id, v.variantId, quantity, note.trim().ifBlank { null })).orSay()?.let { p ->
            say("تم تسجيل الحجز ${p.number} لـ ${customer.name}")
            loadList()
            loadIncoming()
        }
    }

    /** A piece not in the app yet (or any piece), for a customer; put aside at once when it arrives. */
    internal fun createFree(customer: ChosenCustomer, f: PreOrderFields) = act {
        repo.createPreOrder(
            CreatePreOrderRequest(
                customerId = customer.id,
                variantId = f.variantId,
                quantity = f.quantity,
                note = f.note.trim().ifBlank { null },
                itemName = f.itemName.trim().ifBlank { null },
                itemSize = f.itemSize.trim().ifBlank { null },
                itemColor = f.itemColor.trim().ifBlank { null },
                unitPrice = f.unitPrice.trim().ifBlank { null },
            ),
        ).orSay()?.let { p ->
            say("تم تسجيل الطلب المسبق ${p.number} لـ ${customer.name}")
            loadList()
        }
    }

    internal fun update(p: AdminPreOrder, f: PreOrderFields) = act {
        repo.updatePreOrder(
            p.id,
            UpdatePreOrderRequest(
                variantId = f.variantId,
                quantity = f.quantity,
                note = f.note.trim(),
                itemName = f.itemName.trim(),
                itemSize = f.itemSize.trim(),
                itemColor = f.itemColor.trim(),
                unitPrice = f.unitPrice.trim().ifBlank { null },
            ),
        ).orSay()?.let {
            say("تم حفظ الطلب ${p.number}")
            loadList()
        }
    }

    /** "وصلت": the customer is told now (or, taken by staff, the pieces are put aside). */
    fun arrived(p: AdminPreOrder) = act {
        repo.preOrderArrived(p.id).orSay()?.let {
            say(if (it.status == "CONVERTED") "حُجزت القطع وأُبلغ العميل أن طلبه قيد التجهيز" else "أُبلغ العميل بوصول طلبه ليؤكده")
            loadList()
        }
    }

    fun checkArrivals() = act {
        repo.checkArrivals().orSay()?.let {
            say(if (it.handled > 0) "تمت معالجة ${it.handled} طلب مسبق وصلت قطعه" else "لا توجد طلبات مسبقة وصلت قطعها")
            loadList()
        }
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
}

/** Pre-orders on pieces still on their way, and what can still be pre-ordered. */
@Composable
fun PreOrdersScreen(session: StaffSession.LoggedIn, onBack: () -> Unit, openLayaway: (String) -> Unit) {
    val canManage = session.can("layaways.manage")
    if (!canManage) {
        Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("الحجز المسبق", onBack) }) { padding ->
            EmptyState(
                Icons.Filled.Lock,
                "لا تملك صلاحية الحجوزات",
                "اطلب من المدير صلاحية «إدارة الحجوزات» لرؤية الحجز المسبق",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    val vm = adminViewModel(key = "pre-orders") { PreOrdersViewModel(it.offers) }
    val list by vm.list.collectAsStateWithLifecycle()
    val incoming by vm.incoming.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var cancelling by remember { mutableStateOf<AdminPreOrder?>(null) }
    var reserving by remember { mutableStateOf<IncomingVariant?>(null) }
    var customer by remember { mutableStateOf<ChosenCustomer?>(null) }
    // New pre-order for a piece not in the app yet: first the customer, then the details.
    var newFor by remember { mutableStateOf<ChosenCustomer?>(null) }
    var pickingCustomer by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AdminPreOrder?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    val dial: (String) -> Unit = { phone ->
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("الحجز المسبق", onBack) {
                TextButton(onClick = { pickingCustomer = true }, enabled = !busy) { Text("طلب مسبق جديد", color = Lux.GoldDeep) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("الحجوزات") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("القادم في الشحنات") })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tab == 0) {
                ChipFilter(listOf<String?>("WAITING", "NOTIFIED", "CONVERTED", "CANCELLED", null), status, ::preOrderStatusLabel, vm::setStatus)
                OutlinedButton(onClick = vm::checkArrivals, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("فحص الواصل الآن وإبلاغ العملاء")
                }
                LoadContent(list, onRetry = vm::loadList) { rows ->
                    RefreshList(refreshing, vm::refresh) {
                        if (rows.isEmpty()) {
                            EmptyList(
                                Icons.Filled.ShoppingCart,
                                "لا توجد حجوزات مسبقة",
                                "احجز لعميلة قطعة قادمة في الشحنات من تبويب «القادم في الشحنات»، وتُبلَّغ تلقائياً عند وصولها",
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(rows, key = { it.id }) { p ->
                                    PreOrderCard(
                                        p = p,
                                        enabled = !busy,
                                        onConvert = { vm.convert(p, openLayaway) },
                                        onCancel = { cancelling = p },
                                        onCall = { dial(p.customer.phone) },
                                        onOpenLayaway = { p.layawayId?.let(openLayaway) },
                                        onEdit = { editing = p },
                                        onArrived = { vm.arrived(p) },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LoadContent(incoming, onRetry = vm::loadIncoming) { rows ->
                    RefreshList(refreshing, vm::refresh) {
                        if (rows.isEmpty()) {
                            EmptyList(
                                Icons.Filled.DateRange,
                                "لا توجد قطع قادمة",
                                "القطع في أوامر الشراء التي لم تُستلم بعد تظهر هنا ويمكن حجزها للعملاء قبل وصولها",
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(rows, key = { it.variantId }) { v ->
                                    IncomingCard(v, enabled = !busy) { reserving = v }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickingCustomer) {
        CustomerPickerDialog(onDismiss = { pickingCustomer = false }, onPick = { newFor = it })
    }
    newFor?.let { c ->
        PreOrderFieldsDialog(title = "طلب مسبق لـ ${c.name}", initial = PreOrderFields(), onDismiss = { newFor = null }) { f ->
            newFor = null
            vm.createFree(c, f)
        }
    }
    editing?.let { p ->
        PreOrderFieldsDialog(
            title = "تعديل ${p.number}",
            initial = PreOrderFields(
                variantId = p.variantId,
                variantLabel = if (p.variantId != null) listOfNotNull(p.name, p.size, p.color).joinToString(" · ") else "",
                itemName = p.itemName.orEmpty(),
                itemSize = p.itemSize.orEmpty(),
                itemColor = p.itemColor.orEmpty(),
                quantity = p.quantity,
                unitPrice = p.unitPrice.orEmpty(),
                note = p.note.orEmpty(),
            ),
            onDismiss = { editing = null },
        ) { f ->
            editing = null
            vm.update(p, f)
        }
    }

    cancelling?.let { p ->
        ConfirmDialog(
            title = "إلغاء الحجز",
            text = "سيتم إلغاء حجز ${p.customer.name} لـ «${p.name}». هل تريد المتابعة؟",
            confirm = "إلغاء الحجز",
            onDismiss = { cancelling = null },
        ) { vm.cancel(p) }
    }

    val target = reserving
    if (target != null) {
        val chosen = customer
        if (chosen == null) {
            CustomerPickerDialog(
                // The picker calls onDismiss right before onPick, so onPick puts the piece back.
                onDismiss = { reserving = null },
                onPick = {
                    customer = it
                    reserving = target
                },
            )
        } else {
            ReserveDialog(
                v = target,
                customer = chosen,
                onDismiss = {
                    reserving = null
                    customer = null
                },
            ) { qty, note ->
                reserving = null
                customer = null
                vm.create(target, chosen, qty, note)
            }
        }
    }
}

@Composable
private fun PreOrderCard(
    p: AdminPreOrder,
    enabled: Boolean,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onCall: () -> Unit,
    onOpenLayaway: () -> Unit,
    onEdit: () -> Unit,
    onArrived: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProductImage(p.imageUrl, Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)), p.name)
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val variant = variantText(p.size, p.color)
                if (variant.isNotBlank()) Text(variant, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                Text("الكمية: ${p.quantity} · ${p.number}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
            StatusChip(if (p.awaitingConfirmation) "بانتظار تأكيد العميل" else preOrderStatusLabel(p.status), preOrderStatusColor(p.status))
        }
        val tags = listOfNotNull(
            if (p.byStaff) "من المحل" else "من التطبيق",
            if (p.variantId == null) "غير مربوط بمنتج بعد" else null,
            p.unitPrice?.let { "السعر المتفق: ${Money.formatBase(it)}" },
        )
        Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.customer.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(p.customer.phone, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
            IconButton(onClick = onCall) { Icon(Icons.Filled.Phone, contentDescription = "اتصال", tint = Lux.GoldDeep) }
        }
        val open = p.status == "WAITING" || p.status == "NOTIFIED"
        if (open) {
            p.expectedDate?.let { Text("الوصول المتوقع: ${formatDate(it)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
            Text(
                "المتوفر الآن على الرف: ${p.inStock}",
                style = MaterialTheme.typography.bodySmall,
                color = if (p.inStock >= p.quantity) Success else Lux.InkSoft,
                fontWeight = if (p.inStock >= p.quantity) FontWeight.SemiBold else FontWeight.Normal,
            )
            p.note?.takeIf { it.isNotBlank() }?.let { Text("ملاحظة: $it", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.status == "WAITING" && p.variantId != null) {
                    Button(onClick = onArrived, enabled = enabled && p.inStock >= p.quantity, modifier = Modifier.weight(1f)) {
                        Text("وصلت", maxLines = 1)
                    }
                } else {
                    Button(onClick = onConvert, enabled = enabled && p.variantId != null && p.inStock >= p.quantity, modifier = Modifier.weight(1f)) {
                        Text("حجز القطع للعميلة", maxLines = 1)
                    }
                }
                OutlinedButton(onClick = onEdit, enabled = enabled) { Text("تعديل") }
                OutlinedButton(onClick = onCancel, enabled = enabled) { Text("إلغاء") }
            }
            if (p.inStock < p.quantity) {
                Text("يمكن حجز القطع عند وصولها إلى المخزون", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
        } else {
            Text("أُنشئ ${formatDate(p.createdAt)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            if (p.status == "CONVERTED" && p.layawayId != null) {
                TextButton(onClick = onOpenLayaway) { Text("فتح الحجز بعربون") }
            }
        }
    }
}

@Composable
private fun IncomingCard(v: IncomingVariant, enabled: Boolean, onReserve: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProductImage(v.imageUrl, Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)), v.name)
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val variant = variantText(v.size, v.color)
                Text(variant.ifBlank { v.sku }, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                v.expectedDate?.let { Text("الوصول المتوقع: ${formatDate(it)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure("قادم", "${v.incoming}", Modifier.weight(1f))
            Figure("محجوز", "${v.claimed}", Modifier.weight(1f))
            Figure("متاح للحجز", "${v.open}", Modifier.weight(1f), highlight = v.open > 0)
        }
        Button(onClick = onReserve, enabled = enabled && v.open > 0, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(if (v.open > 0) "حجز لعميل" else "محجوز بالكامل")
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = if (highlight) Lux.GoldDeep else MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    }
}

@Composable
private fun ReserveDialog(v: IncomingVariant, customer: ChosenCustomer, onDismiss: () -> Unit, onConfirm: (Int, String) -> Unit) {
    val max = v.open.coerceIn(1, 20)
    var qty by rememberSaveable { mutableStateOf(1) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حجز لعميل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(v.name, style = MaterialTheme.typography.titleSmall)
                val variant = variantText(v.size, v.color)
                if (variant.isNotBlank()) Text(variant, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                Text("العميل: ${customer.name} · ${customer.phone}", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("الكمية", Modifier.weight(1f))
                    QuantityStepper(qty, { qty = it.coerceIn(1, max) }, max = max)
                }
                Text("متاح للحجز: ${v.open}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                Spacer(Modifier.width(1.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    label = { Text("ملاحظة (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(qty.coerceIn(1, max), note) }) { Text("حجز") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

/** The piece (a size from the catalogue, or a description), quantity, agreed price and note. */
@Composable
private fun PreOrderFieldsDialog(title: String, initial: PreOrderFields, onDismiss: () -> Unit, onSave: (PreOrderFields) -> Unit) {
    var f by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    val valid = (f.variantId != null || f.itemName.trim().length >= 2) && f.quantity in 1..50 &&
        (f.unitPrice.isBlank() || f.unitPrice.trim().toBigDecimalOrNull()?.let { it.signum() > 0 } == true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("القطعة من المنتجات (اختياري)", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.variantLabel.ifBlank { "غير مربوطة بمنتج" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { picking = true }) { Text(if (f.variantId == null) "اختيار" else "تغيير") }
                    if (f.variantId != null) TextButton(onClick = { f = f.copy(variantId = null, variantLabel = "") }) { Text("إزالة") }
                }
                Text("أو وصف قطعة لم تُضف للتطبيق بعد", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(f.itemName, { f = f.copy(itemName = it.take(150)) }, label = { Text("اسم القطعة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(f.itemSize, { f = f.copy(itemSize = it.take(30)) }, label = { Text("المقاس") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(f.itemColor, { f = f.copy(itemColor = it.take(40)) }, label = { Text("اللون") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("الكمية", Modifier.weight(1f))
                    QuantityStepper(f.quantity, { f = f.copy(quantity = it.coerceIn(1, 50)) }, max = 50)
                }
                OutlinedTextField(
                    f.unitPrice,
                    { f = f.copy(unitPrice = it.filter { c -> c.isDigit() || c == '.' }.take(10)) },
                    label = { Text("السعر المتفق للقطعة (ر.س، اختياري)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(f.note, { f = f.copy(note = it.take(300)) }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth())
                Text(
                    "عند وصول القطع وربطها بالمنتج تُحجز للعميل تلقائياً ويصله إشعار أن طلبه قيد التجهيز.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lux.InkSoft,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(f) }, enabled = valid) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
    if (picking) {
        PiecePickerDialog("اختر القطعة", onDismiss = { picking = false }, requireStock = false) { product, variant ->
            picking = false
            f = f.copy(variantId = variant.id, variantLabel = listOfNotNull(product.nameAr, variant.size, variant.color).joinToString(" · "))
        }
    }
}
