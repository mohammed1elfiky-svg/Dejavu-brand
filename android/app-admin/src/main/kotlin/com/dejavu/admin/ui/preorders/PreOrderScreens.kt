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

    fun create(v: IncomingVariant, customer: ChosenCustomer, quantity: Int, note: String) = act {
        repo.createPreOrder(CreatePreOrderRequest(customer.id, v.variantId, quantity, note.trim().ifBlank { null })).orSay()?.let { p ->
            say("تم تسجيل الحجز ${p.number} لـ ${customer.name}")
            loadList()
            loadIncoming()
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
        topBar = { BackTopBar("الحجز المسبق", onBack) },
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
            StatusChip(preOrderStatusLabel(p.status), preOrderStatusColor(p.status))
        }
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
                Button(onClick = onConvert, enabled = enabled && p.inStock >= p.quantity, modifier = Modifier.weight(1f)) {
                    Text("حجز القطع للعميلة", maxLines = 1)
                }
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
