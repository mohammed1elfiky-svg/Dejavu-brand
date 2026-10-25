@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.store

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.UndoableDeletes
import com.dejavu.admin.ui.showUndoDelete
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminReview
import com.dejavu.core.admin.StoreSetupRepository
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.ShippingZone
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ helpers ---

/** The methods the customer app can offer, in the order shown. */
private val STORE_METHODS = listOf(
    PaymentMethod.CASH_ON_DELIVERY,
    PaymentMethod.CARD,
    PaymentMethod.WALLET,
    PaymentMethod.BANK_TRANSFER,
    PaymentMethod.CUSTOMER_ACCOUNT,
)

private val PaymentMethod.storeLabel: String
    get() = when (this) {
        PaymentMethod.CASH_ON_DELIVERY -> "الدفع عند الاستلام"
        PaymentMethod.CARD -> "الدفع بالبطاقة"
        // In Yemen these are the mobile wallets (جيب، ون كاش، فلوسك…).
        PaymentMethod.WALLET -> "المحافظ الإلكترونية"
        PaymentMethod.BANK_TRANSFER -> "تحويل بنكي"
        PaymentMethod.CUSTOMER_ACCOUNT -> "رصيد المحفظة / الحساب"
        PaymentMethod.INSTAPAY -> "إنستاباي"
        PaymentMethod.CASH -> "نقداً"
    }

private val PaymentMethod.storeNote: String?
    get() = when (this) {
        PaymentMethod.CASH_ON_DELIVERY -> "يُعرض فقط للمحافظات التي فعّلت لها الدفع عند الاستلام بالأسفل."
        PaymentMethod.CARD -> "يعمل فقط عند إعداد بوابة دفع إلكتروني."
        PaymentMethod.WALLET -> "جيب، ون كاش، فلوسك وغيرها."
        PaymentMethod.CUSTOMER_ACCOUNT -> "يدفع العميل من رصيد حسابه لدى المتجر."
        else -> null
    }

// ------------------------------------------------------------- store setup ---

data class StoreSetup(val methods: List<PaymentMethod>, val zones: List<ShippingZone>)

class StoreSetupViewModel(private val repo: StoreSetupRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<StoreSetup>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** Governorates whose row is being saved. */
    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    val saving = _saving.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            val methods = repo.paymentMethods()
            val zones = repo.zones()
            _state.value = methods.mapCatching { m -> StoreSetup(m, zones.getOrThrow()) }.toLoad()
        }
    }

    fun setMethod(method: PaymentMethod, on: Boolean) {
        val current = (_state.value as? Load.Ready)?.value ?: return
        val next = if (on) {
            STORE_METHODS.filter { it == method || it in current.methods }
        } else {
            current.methods - method
        }
        if (next.isEmpty()) {
            say("يجب أن تبقى طريقة دفع واحدة على الأقل")
            return
        }
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.setPaymentMethods(next).orSay()?.let { saved ->
                update { it.copy(methods = saved) }
                say(if (on) "تم تفعيل ${method.storeLabel}" else "تم إيقاف ${method.storeLabel}")
            }
            _busy.value = false
        }
    }

    fun saveZone(zone: ShippingZone) {
        if (zone.governorate in _saving.value || _busy.value) return
        _saving.value = _saving.value + zone.governorate
        viewModelScope.launch {
            // Shipping is paid by the customer to the courier, so no fee is added to the order.
            repo.saveZone(zone.copy(fee = "0")).orSay()?.let(::upsert)
            _saving.value = _saving.value - zone.governorate
        }
    }

    /** Turns cash on delivery on or off for every governorate. */
    fun setCodForAll(on: Boolean) {
        val current = (_state.value as? Load.Ready)?.value ?: return
        if (_busy.value || _saving.value.isNotEmpty()) return
        val changes = current.zones.filter { it.codEnabled != on }
        if (changes.isEmpty()) {
            say(if (on) "الدفع عند الاستلام مفعّل لكل المحافظات" else "الدفع عند الاستلام موقوف لكل المحافظات")
            return
        }
        _busy.value = true
        viewModelScope.launch {
            var failed = 0
            for (z in changes) {
                val res = repo.saveZone(z.copy(codEnabled = on, fee = "0"))
                res.getOrNull()?.let(::upsert) ?: run { failed++ }
            }
            say(
                when {
                    failed == 0 && on -> "تم تفعيل الدفع عند الاستلام لكل المحافظات"
                    failed == 0 -> "تم إيقاف الدفع عند الاستلام لكل المحافظات"
                    else -> "تعذّر حفظ $failed من المحافظات، حاول مرة أخرى"
                },
            )
            _busy.value = false
        }
    }

    private fun upsert(zone: ShippingZone) = update { s ->
        val zones = if (s.zones.any { it.governorate == zone.governorate }) {
            s.zones.map { if (it.governorate == zone.governorate) zone else it }
        } else {
            s.zones + zone
        }
        s.copy(zones = zones)
    }

    private fun update(change: (StoreSetup) -> StoreSetup) {
        val s = (_state.value as? Load.Ready)?.value ?: return
        _state.value = Load.Ready(change(s))
    }
}

/** What the customer app offers: payment methods, delivery areas and cash on delivery. */
@Composable
fun StoreSetupScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "store-setup") { StoreSetupViewModel(it.storeSetup) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("إعدادات تطبيق العملاء", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { setup ->
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item(key = "methods") { PaymentMethodsCard(setup.methods, busy, vm::setMethod) }
                item(key = "zones-head") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("المحافظات والدفع عند الاستلام", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "رسوم الشحن يدفعها العميل للمندوب ولا تُضاف للطلب",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (PaymentMethod.CASH_ON_DELIVERY !in setup.methods) {
                            Text(
                                "الدفع عند الاستلام موقوف من طرق الدفع بالأعلى، فلن يظهر لأي محافظة.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Warning,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        val codCount = setup.zones.count { it.codEnabled }
                        Text(
                            "الدفع عند الاستلام مفعّل في $codCount من ${setup.zones.size} محافظة",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { vm.setCodForAll(true) },
                                enabled = !busy && saving.isEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text("تفعيل الاستلام للكل", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            OutlinedButton(
                                onClick = { vm.setCodForAll(false) },
                                enabled = !busy && saving.isEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text("إيقافه للكل", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                items(setup.zones, key = { "z:" + it.governorate }) { z ->
                    ZoneCard(z, enabled = !busy && z.governorate !in saving, saving = z.governorate in saving, onSave = vm::saveZone)
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodsCard(methods: List<PaymentMethod>, busy: Boolean, onToggle: (PaymentMethod, Boolean) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("طرق الدفع في التطبيق", style = MaterialTheme.typography.titleMedium)
        Text(
            "ما تفعّله هنا يظهر للعميل عند إتمام الطلب. يجب أن تبقى طريقة واحدة على الأقل.",
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        STORE_METHODS.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider(color = Lux.Hairline)
            val on = m in methods
            val last = on && methods.size == 1
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.storeLabel, style = MaterialTheme.typography.bodyLarge)
                    m.storeNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                }
                Switch(checked = on, onCheckedChange = { onToggle(m, it) }, enabled = !busy && !last)
            }
        }
    }
}

@Composable
private fun ZoneCard(z: ShippingZone, enabled: Boolean, saving: Boolean, onSave: (ShippingZone) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(z.governorate, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (!z.isActive) {
                StatusChip("التوصيل موقوف", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("التوصيل متاح", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = z.isActive, onCheckedChange = { onSave(z.copy(isActive = it)) }, enabled = enabled)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("الدفع عند الاستلام", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = z.codEnabled, onCheckedChange = { onSave(z.copy(codEnabled = it)) }, enabled = enabled)
        }
    }
}

// ----------------------------------------------------------------- reviews ---

private enum class ReviewTab(val status: String, val title: String) {
    PENDING("pending", "بانتظار الموافقة"),
    APPROVED("approved", "المنشورة"),
}

class ReviewsViewModel(private val repo: StoreSetupRepository, appScope: CoroutineScope) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<AdminReview>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()
    private val _tab = MutableStateFlow(0)
    val tab = _tab.asStateFlow()

    /** Reviews being published, hidden or deleted. */
    private val _acting = MutableStateFlow<Set<String>>(emptySet())
    val acting = _acting.asStateFlow()
    private var page = 1

    private val status: String get() = ReviewTab.entries[_tab.value].status

    fun select(index: Int) {
        if (index == _tab.value) return
        _tab.value = index
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        if (reset || _state.value !is Load.Ready) _state.value = Load.Loading
        page = 1
        val wanted = status
        viewModelScope.launch {
            val res = repo.reviews(wanted, 1)
            if (wanted != status) return@launch
            _hasMore.value = res.getOrNull()?.hasMore == true
            _state.value = res.map { it.items }.toLoad()
        }
    }

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) {
        val wanted = status
        val res = repo.reviews(wanted, 1)
        if (wanted == status) {
            page = 1
            _hasMore.value = res.getOrNull()?.hasMore == true
            _state.value = res.map { it.items }.toLoad()
        }
    }

    /** Deleted reviews wait a few seconds for "تراجع" before the server is asked. */
    val deletes = UndoableDeletes(appScope, viewModelScope) { say(it) }

    fun more() {
        val current = (_state.value as? Load.Ready)?.value ?: return
        if (_loadingMore.value || !_hasMore.value) return
        _loadingMore.value = true
        val wanted = status
        viewModelScope.launch {
            repo.reviews(wanted, page + 1).orSay()?.let { res ->
                if (wanted == status) {
                    page += 1
                    _state.value = Load.Ready(current + res.items.filter { n -> current.none { it.id == n.id } })
                    _hasMore.value = res.hasMore
                }
            }
            _loadingMore.value = false
        }
    }

    /** Publishes ([approved] = true) or hides the review; it then moves to the other tab. */
    fun approve(r: AdminReview, approved: Boolean) = act(r.id) {
        repo.approveReview(r.id, approved).orSay()?.let {
            remove(r.id)
            say(if (approved) "تم نشر التقييم" else "تم إخفاء التقييم")
        }
    }

    /** Hides the review now; it is deleted once the undo snackbar is gone. */
    fun delete(r: AdminReview) = deletes.hide(r.id) {
        repo.deleteReview(r.id).onSuccess { remove(r.id) }
    }

    override fun onCleared() {
        deletes.flush()
    }

    private fun remove(id: String) {
        val list = (_state.value as? Load.Ready)?.value ?: return
        _state.value = Load.Ready(list.filter { it.id != id })
    }

    private fun act(id: String, block: suspend () -> Unit) {
        if (id in _acting.value) return
        _acting.value = _acting.value + id
        viewModelScope.launch {
            block()
            _acting.value = _acting.value - id
        }
    }
}

/** Customers' product reviews: approve them for the store, hide or delete them. */
@Composable
fun ReviewsScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "reviews") { ReviewsViewModel(it.storeSetup, it.appScope) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val acting by vm.acting.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val hidden by vm.deletes.hidden.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableStateOf<String?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("آراء العملاء", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                ReviewTab.entries.forEachIndexed { i, t ->
                    Tab(selected = i == tab, onClick = { vm.select(i) }, text = { Text(t.title) })
                }
            }
            LoadContent(state, onRetry = { vm.load() }) { all ->
                val list = all.filterNot { it.id in hidden }
                RefreshList(refreshing, onRefresh = vm::refresh) {
                if (list.isEmpty()) {
                    EmptyList(
                        Icons.Filled.Star,
                        if (tab == 0) "لا توجد تقييمات بانتظار الموافقة" else "لا توجد تقييمات منشورة",
                        if (tab == 0) "تظهر هنا تقييمات العملاء الجديدة لتنشرها في التطبيق." else "التقييمات التي توافق عليها تظهر هنا وفي صفحة المنتج.",
                        action = if (tab == 1) "عرض التقييمات الجديدة" else null,
                        onAction = { vm.select(0) },
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.id }) { r ->
                            ReviewCard(
                                r,
                                busy = r.id in acting,
                                onZoom = { zoom = it },
                                onApprove = { vm.approve(r, it) },
                                onDelete = {
                                    vm.delete(r)
                                    scope.showUndoDelete(snackbar, vm.deletes, r.id, "تم حذف التقييم")
                                },
                            )
                        }
                        if (hasMore) {
                            item(key = "more") {
                                OutlinedButton(onClick = vm::more, enabled = !loadingMore, modifier = Modifier.fillMaxWidth()) {
                                    if (loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("عرض المزيد")
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    zoom?.let { url -> PhotoDialog(url) { zoom = null } }
}

@Composable
private fun ReviewCard(
    r: AdminReview,
    busy: Boolean,
    onZoom: (String) -> Unit,
    onApprove: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductImage(
                r.product?.images?.firstOrNull()?.url,
                Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                contentDescription = r.product?.nameAr,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    r.product?.nameAr ?: "منتج محذوف",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatDate(r.createdAt), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
            StatusChip(if (r.isApproved) "منشور" else "بانتظار الموافقة", if (r.isApproved) Success else Warning)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Lux.Hairline)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.customer?.name ?: "عميل", style = MaterialTheme.typography.bodyMedium)
                r.customer?.phone?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
            }
            Stars(r.rating)
        }
        r.comment?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        if (r.images.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                r.images.forEach { url ->
                    ProductImage(
                        url,
                        Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).clickable { onZoom(url) },
                        contentDescription = "صورة من العميل",
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.isApproved) {
                OutlinedButton(onClick = { onApprove(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("إخفاء") }
            } else {
                OutlinedButton(onClick = { onApprove(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("نشر") }
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("حذف", Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun Stars(rating: Int) {
    val n = rating.coerceIn(0, 5)
    Row {
        repeat(5) { i ->
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = if (i < n) Lux.Gold else Lux.Hairline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A customer's photo, full screen. */
@Composable
private fun PhotoDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
            AsyncImage(
                model = url,
                contentDescription = "صورة من العميل",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = Color.White)
            }
        }
    }
}
