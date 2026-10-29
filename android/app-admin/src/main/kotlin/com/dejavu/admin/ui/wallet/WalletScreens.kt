@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.wallet

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ChipFilter
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.TextDialog
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.UndoableDeletes
import com.dejavu.admin.ui.showUndoDelete
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminTopUp
import com.dejavu.core.admin.BankAccountRequest
import com.dejavu.core.admin.BankTransferRow
import com.dejavu.core.admin.CashBox
import com.dejavu.core.admin.CashBoxKind
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.media.rememberImagePicker
import coil3.compose.AsyncImage
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.admin.ui.treasury.BoxAvatar
import com.dejavu.core.admin.WalletAdminRepository
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.StoreBankAccount
import com.dejavu.core.model.TopUpStatus
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ helpers ---

private val TopUpStatus.label: String
    get() = when (this) {
        TopUpStatus.PENDING -> "بانتظار المراجعة"
        TopUpStatus.APPROVED -> "تمت الموافقة"
        TopUpStatus.REJECTED -> "مرفوض"
    }

@Composable
private fun TopUpStatus.tint(): Color = when (this) {
    TopUpStatus.PENDING -> Warning
    TopUpStatus.APPROVED -> Success
    TopUpStatus.REJECTED -> MaterialTheme.colorScheme.error
}

/** The registered instance (server symbol, name and decimals) of a currency read off the wire. */
private val Currency.fresh: Currency get() = Currency.of(code)

private fun amountIn(amount: String, currency: Currency): String = Money.plain(Money.parse(amount), currency.fresh)

private val CashBoxKind.label: String
    get() = when (this) {
        CashBoxKind.CASHIER -> "صندوق كاشير"
        CashBoxKind.VAULT -> "خزينة"
        CashBoxKind.BANK -> "حساب بنكي"
    }

// ------------------------------------------------------------------- list ---

class TopUpsViewModel(private val repo: WalletAdminRepository, private val finance: FinanceRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<AdminTopUp>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()
    var status: TopUpStatus? = TopUpStatus.PENDING
        private set
    /** "للتحقق": credited automatically, not yet checked against the bank. */
    var toVerify = false
        private set
    private var page = 1
    private var registered = false

    /** Whether top-ups are credited at once (null until loaded). */
    private val _autoApprove = MutableStateFlow<Boolean?>(null)
    val autoApprove = _autoApprove.asStateFlow()

    fun loadSettings() {
        viewModelScope.launch { repo.settings().getOrNull()?.let { _autoApprove.value = it.autoApprove } }
    }

    fun setAutoApprove(on: Boolean) {
        viewModelScope.launch {
            repo.setAutoApprove(on).orSay()?.let {
                _autoApprove.value = it.autoApprove
                say(if (it.autoApprove) "يُضاف المبلغ لرصيد العميل فور إرسال الحوالة، وتتحقق منها لاحقاً" else "تنتظر طلبات الشحن موافقتك قبل إضافة المبلغ")
            }
        }
    }

    fun select(s: TopUpStatus?, verify: Boolean = false) {
        status = s
        toVerify = verify
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        if (reset || _state.value !is Load.Ready) _state.value = Load.Loading
        page = 1
        viewModelScope.launch {
            if (!registered) registered = finance.activeCurrencies().isSuccess
            val res = repo.list(status, 1, toVerify)
            _hasMore.value = res.getOrNull()?.hasMore == true
            _state.value = res.map { it.items }.toLoad()
        }
    }

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) {
        val s = status
        val res = repo.list(s, 1, toVerify)
        if (s == status) {
            page = 1
            _hasMore.value = res.getOrNull()?.hasMore == true
            _state.value = res.map { it.items }.toLoad()
        }
    }

    fun more() {
        val current = (_state.value as? Load.Ready)?.value ?: return
        if (_loadingMore.value || !_hasMore.value) return
        _loadingMore.value = true
        viewModelScope.launch {
            repo.list(status, page + 1, toVerify).orSay()?.let { res ->
                page += 1
                _state.value = Load.Ready(current + res.items.filter { n -> current.none { it.id == n.id } })
                _hasMore.value = res.hasMore
            }
            _loadingMore.value = false
        }
    }
}

/** Customers' wallet top-up requests (deposit slips) by status. */
@Composable
fun TopUpsScreen(onBack: () -> Unit, open: (String) -> Unit, openTransfers: () -> Unit = {}) {
    val vm = adminViewModel { TopUpsViewModel(it.wallet, it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var status by rememberSaveable { mutableStateOf(vm.status) }
    var verify by rememberSaveable { mutableStateOf(vm.toVerify) }
    val autoApprove by vm.autoApprove.collectAsStateWithLifecycle()
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        vm.loadSettings()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("طلبات شحن حسابات العملاء", onBack) {
                TextButton(onClick = openTransfers) { Text("سجل الحوالات", color = Lux.GoldDeep) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            autoApprove?.let { on ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("إضافة المبلغ تلقائياً عند إرسال الحوالة", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (on) "يُضاف للرصيد فوراً وتظهر الحوالة في «للتحقق»" else "تنتظر الطلبات موافقتك",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                        )
                    }
                    androidx.compose.material3.Switch(checked = on, onCheckedChange = vm::setAutoApprove)
                }
            }
            // "VERIFY" = credited automatically, waiting for a check; then by status.
            ChipFilter(
                options = listOf("VERIFY", TopUpStatus.PENDING.name, TopUpStatus.APPROVED.name, TopUpStatus.REJECTED.name, null),
                selected = if (verify) "VERIFY" else status?.name,
                label = { if (it == "VERIFY") "للتحقق" else it?.let { n -> TopUpStatus.valueOf(n).label } ?: "الكل" },
                onSelect = {
                    verify = it == "VERIFY"
                    status = it?.takeIf { n -> n != "VERIFY" }?.let { n -> TopUpStatus.valueOf(n) }
                    vm.select(status, verify)
                },
            )
            LoadContent(state, onRetry = { vm.load() }) { list ->
                RefreshList(refreshing, onRefresh = vm::refresh) {
                if (list.isEmpty()) {
                    if (verify) {
                        EmptyList(Icons.Filled.Email, "لا توجد حوالات للتحقق", "تظهر هنا الحوالات التي أُضيفت لرصيد العملاء تلقائياً حتى تتأكد من وصولها")
                    } else if (status == TopUpStatus.PENDING) {
                        EmptyList(Icons.Filled.Email, "لا توجد طلبات بانتظار المراجعة", "تظهر هنا طلبات شحن الرصيد التي يرسلها العملاء مع صورة الإيصال")
                    } else {
                        EmptyList(
                            Icons.Filled.Email,
                            "لا توجد طلبات",
                            action = if (status != null) "عرض الكل" else null,
                            onAction = {
                                status = null
                                vm.select(null)
                            },
                        )
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.id }) { t -> TopUpRow(t) { open(t.id) } }
                        if (hasMore) {
                            item {
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
}

@Composable
private fun TopUpRow(t: AdminTopUp, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t.number, style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                Text(
                    listOfNotNull(t.customer?.name, t.customer?.phone).joinToString(" · ").ifBlank { "عميل" },
                    style = MaterialTheme.typography.titleSmall,
                )
                t.bankAccount?.let { Text("${it.bankName} · ${it.accountNumber}", style = MaterialTheme.typography.bodySmall) }
                t.reference?.let { Text("مرجع: $it", style = MaterialTheme.typography.bodySmall) }
                Text(formatDateTime(t.createdAt), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(amountIn(t.amount, t.currency), style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
                if (t.toVerify) StatusChip("للتحقق", Warning) else StatusChip(t.status.label, t.status.tint())
            }
        }
    }
}

// ----------------------------------------------------------------- detail ---

class TopUpViewModel(
    private val id: String,
    private val repo: WalletAdminRepository,
    private val treasury: TreasuryRepository,
    private val finance: FinanceRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<AdminTopUp>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _receipt = MutableStateFlow<Load<ImageBitmap>>(Load.Loading)
    val receipt = _receipt.asStateFlow()
    private val _boxes = MutableStateFlow<Load<List<CashBox>>>(Load.Loading)
    val boxes = _boxes.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var receiptFor: String? = null

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            finance.activeCurrencies()
            val res = repo.get(id)
            _state.value = res.toLoad()
            res.getOrNull()?.let { loadReceipt(it.receiptFileId) }
        }
    }

    fun loadReceipt(fileId: String, force: Boolean = false) {
        if (!force && receiptFor == fileId && _receipt.value is Load.Ready) return
        receiptFor = fileId
        _receipt.value = Load.Loading
        viewModelScope.launch {
            val bytes = repo.receipt(fileId)
            _receipt.value = bytes.fold(
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

    /** Active bank accounts and safes in the top-up's currency: where the deposit landed. */
    fun loadBoxes(currency: Currency) {
        _boxes.value = Load.Loading
        viewModelScope.launch {
            _boxes.value = treasury.all(currency = currency).map { list ->
                list.filter { it.isActive && (it.kind == CashBoxKind.BANK || it.kind == CashBoxKind.VAULT) }
                    .sortedBy { if (it.kind == CashBoxKind.BANK) 0 else 1 }
            }.toLoad()
        }
    }

    fun approve(cashBoxId: String, amount: String?) = act {
        repo.approve(id, cashBoxId, amount).orSay()?.let {
            _state.value = Load.Ready(it)
            say("تمت الموافقة وأُضيف المبلغ إلى رصيد العميل")
        }
    }

    fun verify() = act {
        repo.verify(id).orSay()?.let {
            _state.value = Load.Ready(it)
            say("تم التحقق من وصول الحوالة")
        }
    }

    fun reverse(reason: String) = act {
        repo.reverse(id, reason).orSay()?.let {
            _state.value = Load.Ready(it)
            say("أُلغي الشحن وخُصم المبلغ من رصيد العميل")
        }
    }

    fun reject(reason: String) = act {
        repo.reject(id, reason).orSay()?.let {
            _state.value = Load.Ready(it)
            say("تم رفض الطلب")
        }
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

/** One top-up: the deposit slip, the customer, and approve (into a bank/safe) or reject. */
@Composable
fun TopUpScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel(key = "top-up:$id") { TopUpViewModel(id, it.wallet, it.treasury, it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val receipt by vm.receipt.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var approving by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf(false) }
    var reversing by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    val canManage = session.can(Perm.WALLET_MANAGE)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar((state as? Load.Ready)?.value?.number ?: "طلب شحن", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { t ->
            val cur = t.currency.fresh
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("المبلغ", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                                Text(amountIn(t.amount, cur), style = MaterialTheme.typography.headlineSmall, color = Lux.GoldDeep)
                            }
                            StatusChip(t.status.label, t.status.tint())
                        }
                        Text(formatDateTime(t.createdAt), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    }
                }
                item {
                    ReceiptCard(receipt, onRetry = { vm.loadReceipt(t.receiptFileId, force = true) }, onZoom = { zoom = true })
                }
                item {
                    InfoCard {
                        Text("العميل", style = MaterialTheme.typography.titleSmall)
                        val c = t.customer
                        if (c == null) {
                            Text("—")
                        } else {
                            SummaryRow("الاسم", c.name)
                            c.phone?.let { SummaryRow("الجوال", it) }
                            c.code?.let { SummaryRow("رقم العميل", it) }
                        }
                        if (t.balances.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Lux.Hairline)
                            Text("الأرصدة الحالية", style = MaterialTheme.typography.labelLarge)
                            t.balances.forEach { b ->
                                val bc = b.currency.fresh
                                val bal = Money.parse(b.balance)
                                val text = when {
                                    bal.signum() < 0 -> "له ${Money.plain(bal.negate(), bc)}"
                                    bal.signum() > 0 -> "عليه ${Money.plain(bal, bc)}"
                                    else -> Money.plain(bal, bc)
                                }
                                SummaryRow(bc.nameAr, text, valueColor = if (bal.signum() > 0) MaterialTheme.colorScheme.error else Color.Unspecified)
                                if (!Money.isZero(b.available)) SummaryRow("المتاح للشراء", Money.plain(Money.parse(b.available), bc))
                            }
                        }
                    }
                }
                item {
                    InfoCard {
                        Text("بيانات الإيداع", style = MaterialTheme.typography.titleSmall)
                        SummaryRow("العملة", cur.nameAr)
                        t.bankAccount?.let { SummaryRow("إلى حساب", "${it.bankName} · ${it.accountNumber}") }
                        t.reference?.let { SummaryRow("رقم المرجع", it) }
                        t.note?.let { Text("ملاحظة العميل: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (t.status != TopUpStatus.PENDING) {
                    item {
                        InfoCard {
                            Text(if (t.status == TopUpStatus.APPROVED) "تمت الموافقة" else "تم الرفض", style = MaterialTheme.typography.titleSmall, color = t.status.tint())
                            t.reviewedBy?.let { SummaryRow("بواسطة", it.name) }
                            t.reviewedAt?.let { SummaryRow("التاريخ", formatDateTime(it)) }
                            t.cashBox?.let { SummaryRow("أُودِع في", it.name) }
                            t.rejectReason?.let { Text("السبب: $it", color = MaterialTheme.colorScheme.error) }
                            if (t.autoApproved) {
                                Text(
                                    if (t.verifiedAt != null) "أُضيف تلقائياً وتم التحقق منه" else "أُضيف تلقائياً — تحقق من وصول المبلغ إلى الحساب",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (t.verifiedAt != null) Success else Warning,
                                )
                            }
                        }
                    }
                    if (t.toVerify && canManage) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = vm::verify,
                                    enabled = !busy,
                                    colors = ButtonDefaults.buttonColors(containerColor = Success),
                                    modifier = Modifier.weight(1f),
                                ) { Text("وصل المبلغ ✓") }
                                OutlinedButton(onClick = { reversing = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    Text("لم يصل — إلغاء الشحن", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                } else if (canManage) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    vm.loadBoxes(cur)
                                    approving = true
                                },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = Success),
                                modifier = Modifier.weight(1f),
                            ) { Text("موافقة") }
                            OutlinedButton(onClick = { rejecting = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("رفض", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    val topUp = (state as? Load.Ready)?.value
    if (approving && topUp != null) {
        ApproveDialog(topUp, vm, onDismiss = { approving = false }) { boxId, amount -> vm.approve(boxId, amount) }
    }
    if (rejecting) {
        TextDialog("رفض طلب الشحن", "سبب الرفض (يظهر للعميل)", "رفض", onDismiss = { rejecting = false }) { vm.reject(it) }
    }
    if (reversing) {
        TextDialog("إلغاء الشحن", "السبب (يظهر للعميل) — يُخصم المبلغ من رصيده", "إلغاء الشحن", onDismiss = { reversing = false }) { vm.reverse(it) }
    }
    val image = (receipt as? Load.Ready)?.value
    if (zoom && image != null) ZoomDialog(image) { zoom = false }
}

@Composable
private fun ReceiptCard(receipt: Load<ImageBitmap>, onRetry: () -> Unit, onZoom: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("سند الإيداع", Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.titleSmall)
            if (receipt is Load.Ready) TextButton(onClick = onZoom) { Text("تكبير") }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 480.dp).clip(RoundedCornerShape(16.dp)).background(Lux.Champagne),
            contentAlignment = Alignment.Center,
        ) {
            when (receipt) {
                Load.Loading -> CircularProgressIndicator(Modifier.padding(32.dp))
                is Load.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(receipt.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
                }
                is Load.Ready -> Image(
                    receipt.value,
                    contentDescription = "سند الإيداع",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onZoom),
                )
            }
        }
    }
}

/** Full-screen slip with pinch to zoom and drag. */
@Composable
private fun ZoomDialog(image: ImageBitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                image,
                contentDescription = "سند الإيداع",
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

@Composable
private fun ApproveDialog(t: AdminTopUp, vm: TopUpViewModel, onDismiss: () -> Unit, onApprove: (boxId: String, amount: String?) -> Unit) {
    val boxes by vm.boxes.collectAsStateWithLifecycle()
    val cur = t.currency.fresh
    var boxId by rememberSaveable { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableStateOf(Money.parse(t.amount).stripTrailingZeros().toPlainString()) }
    val value = amount.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= cur.decimals }
    val changed = value != null && value.compareTo(Money.parse(t.amount)) != 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الموافقة على الشحن") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("أين وصل المبلغ؟", style = MaterialTheme.typography.labelLarge)
                when (val b = boxes) {
                    Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is Load.Failed -> Text(b.message, color = MaterialTheme.colorScheme.error)
                    is Load.Ready -> {
                        if (b.value.isEmpty()) {
                            Text("لا يوجد حساب بنكي أو خزينة مفعّلة بعملة ${cur.nameAr}. أضفها من الصناديق والخزائن.", color = MaterialTheme.colorScheme.error)
                        }
                        b.value.forEach { box ->
                            Row(Modifier.fillMaxWidth().clickable { boxId = box.id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = box.id == boxId, onClick = { boxId = box.id })
                                BoxAvatar(box, 32.dp)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(box.name)
                                    Text(
                                        "${box.kind.label} · الرصيد ${Money.plain(Money.parse(box.balance), cur)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Lux.InkSoft,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("المبلغ المستلم فعلاً (${cur.symbol})") },
                    supportingText = { Text(if (changed) "سيُعتمد مبلغ مختلف عن طلب العميل" else "عدّله إذا اختلف عن المبلغ الظاهر في السند") },
                    isError = value == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = boxId != null && value != null,
                onClick = {
                    onDismiss()
                    onApprove(boxId!!, if (changed) value!!.toPlainString() else null)
                },
            ) { Text("موافقة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

// ---------------------------------------------------------- bank accounts ---

class BankAccountsViewModel(
    private val repo: WalletAdminRepository,
    private val finance: FinanceRepository,
    private val inventory: InventoryRepository,
    appScope: CoroutineScope,
) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<StoreBankAccount>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _currencies = MutableStateFlow(listOf(Currency.SAR, Currency.YER))
    val currencies = _currencies.asStateFlow()
    /** Uploaded picture of each currency (code → url). */
    private val _currencyImages = MutableStateFlow<Map<String, String?>>(emptyMap())
    val currencyImages = _currencyImages.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _uploading = MutableStateFlow(false)
    val uploading = _uploading.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            finance.activeCurrencies().getOrNull()?.let { c ->
                _currencies.value = c.register().map { it.fresh }
                _currencyImages.value = c.currencies.associate { it.code.code to it.imageUrl }
            }
            _state.value = repo.bankAccounts().toLoad()
        }
    }

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) { _state.value = repo.bankAccounts().toLoad() }

    /** Deleted accounts wait a few seconds for "تراجع" before the server is asked. */
    val deletes = UndoableDeletes(appScope, viewModelScope) { say(it) }

    /** Uploads the account's picture and hands back its URL (saved with the account later). */
    fun uploadPicture(bytes: ByteArray, done: (String) -> Unit) {
        if (_uploading.value) return
        _uploading.value = true
        viewModelScope.launch {
            inventory.uploadImage(bytes).orSay()?.let { done(it.url) }
            _uploading.value = false
        }
    }

    fun save(existing: StoreBankAccount?, request: BankAccountRequest, done: () -> Unit) = act {
        val res = if (existing == null) repo.createBankAccount(request) else repo.updateBankAccount(existing.id, request)
        res.orSay()?.let {
            say(if (existing == null) "تمت إضافة الحساب" else "تم حفظ الحساب")
            done()
            repo.bankAccounts().getOrNull()?.let { list -> _state.value = Load.Ready(list) }
        }
    }

    fun setActive(a: StoreBankAccount, active: Boolean) = save(a, a.toRequest().copy(isActive = active)) {}

    /**
     * Hides the account now and deletes it once the undo snackbar is gone; one already used by
     * top-ups is hidden from the app by the server instead, and comes back here as inactive.
     */
    fun delete(a: StoreBankAccount) = deletes.hide(a.id) {
        repo.deleteBankAccount(a.id).onSuccess { res ->
            if (res.hidden) {
                say("الحساب مستخدم في طلبات شحن سابقة، فتم إخفاؤه من التطبيق بدل حذفه")
                deletes.show(a.id)
            }
            repo.bankAccounts().getOrNull()?.let { list -> _state.value = Load.Ready(list) }
        }
    }

    override fun onCleared() {
        deletes.flush()
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

private fun StoreBankAccount.toRequest() = BankAccountRequest(
    bankName = bankName,
    accountName = accountName,
    accountNumber = accountNumber,
    currency = currency,
    iban = iban,
    instructions = instructions,
    isActive = isActive,
    currencies = accepted,
)

/** Store bank accounts shown to customers for deposits. */
@Composable
fun BankAccountsScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel { BankAccountsViewModel(it.wallet, it.finance, it.inventory, it.appScope) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val hidden by vm.deletes.hidden.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val currencies by vm.currencies.collectAsStateWithLifecycle()
    val currencyImages by vm.currencyImages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val canEdit = session.can(Perm.SETTINGS_MANAGE)
    var editing by remember { mutableStateOf<StoreBankAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<StoreBankAccount?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("الحسابات البنكية للمتجر", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canEdit) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("إضافة حساب") },
                    containerColor = Lux.GoldDeep,
                    contentColor = Color.White,
                )
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { all ->
            val list = all.filterNot { it.id in hidden }
            RefreshList(refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding)) {
            if (list.isEmpty()) {
                EmptyList(
                    Icons.Filled.Home,
                    "لا توجد حسابات بنكية",
                    "أضف حسابات المتجر ليحوّل إليها العملاء عند شحن أرصدتهم.",
                    action = if (canEdit) "إضافة حساب" else null,
                    onAction = { adding = true },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    items(list, key = { it.id }) { a ->
                        BankAccountCard(
                            a,
                            canEdit,
                            busy,
                            currencyImages,
                            onEdit = { editing = a },
                            onActive = { vm.setActive(a, it) },
                            onDelete = { deleting = a },
                        )
                    }
                }
            }
            }
        }
    }

    if (adding) {
        BankAccountDialog(null, currencies, currencyImages, busy, uploading, vm::uploadPicture, onDismiss = { adding = false }) { req ->
            vm.save(null, req) { adding = false }
        }
    }
    editing?.let { a ->
        key(a.id) {
            BankAccountDialog(a, currencies, currencyImages, busy, uploading, vm::uploadPicture, onDismiss = { editing = null }) { req ->
                vm.save(a, req) { editing = null }
            }
        }
    }
    deleting?.let { a ->
        ConfirmDialog(
            "حذف الحساب",
            "حذف حساب ${a.bankName} (${a.accountNumber})؟ إن كان مستخدماً في طلبات شحن سابقة فسيُخفى من التطبيق بدل حذفه.",
            "حذف",
            onDismiss = { deleting = null },
        ) {
            vm.delete(a)
            scope.showUndoDelete(snackbar, vm.deletes, a.id, "تم حذف حساب ${a.bankName}")
        }
    }
}

@Composable
private fun BankAccountCard(
    a: StoreBankAccount,
    canEdit: Boolean,
    busy: Boolean,
    currencyImages: Map<String, String?>,
    onEdit: () -> Unit,
    onActive: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val cur = a.currency.fresh
    GlassCard(Modifier.fillMaxWidth(), onClick = if (canEdit) onEdit else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!a.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = a.imageUrl,
                    contentDescription = a.bankName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Lux.Champagne),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(a.bankName, style = MaterialTheme.typography.titleMedium)
                Text(a.accountName, style = MaterialTheme.typography.bodyMedium)
            }
            GoldPill { Text(cur.nameAr, color = Color.White, style = MaterialTheme.typography.labelMedium) }
        }
        FlowRow(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            a.accepted.forEach { code ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CurrencyArt(code, currencyImages[code], height = 18.dp)
                    Text(Currency.of(code).nameAr, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Lux.Hairline)
        SummaryRow("رقم الحساب", a.accountNumber)
        a.iban?.let { SummaryRow("الآيبان", it) }
        a.instructions?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (a.isActive) "يظهر للعملاء" else "مخفي عن العملاء", Modifier.weight(1f))
            Switch(checked = a.isActive, onCheckedChange = onActive, enabled = canEdit && !busy)
        }
        if (canEdit) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("تعديل", Modifier.padding(start = 6.dp))
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
}

@Composable
private fun BankAccountDialog(
    existing: StoreBankAccount?,
    currencies: List<Currency>,
    currencyImages: Map<String, String?>,
    busy: Boolean,
    uploading: Boolean,
    onUpload: (ByteArray, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (BankAccountRequest) -> Unit,
) {
    var bankName by rememberSaveable { mutableStateOf(existing?.bankName.orEmpty()) }
    var accountName by rememberSaveable { mutableStateOf(existing?.accountName.orEmpty()) }
    var accountNumber by rememberSaveable { mutableStateOf(existing?.accountNumber.orEmpty()) }
    var iban by rememberSaveable { mutableStateOf(existing?.iban.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(existing?.instructions.orEmpty()) }
    // Selected currencies in the order picked (the first is the main one), kept as "SAR,YER".
    var codesText by rememberSaveable {
        mutableStateOf((existing?.accepted ?: listOf(currencies.firstOrNull()?.code ?: Currency.SAR.code)).joinToString(","))
    }
    val codes = codesText.split(",").filter { it.isNotBlank() }
    var image by rememberSaveable { mutableStateOf(existing?.imageUrl?.takeIf { it.isNotBlank() }) }
    var active by rememberSaveable { mutableStateOf(existing?.isActive ?: true) }
    val options = (currencies + codes.map { Currency.of(it).fresh }).distinctBy { it.code }
    val picker = rememberImagePicker { bytes -> onUpload(bytes) { url -> image = url } }
    val valid = bankName.trim().length >= 2 && accountName.trim().length >= 2 && accountNumber.trim().length >= 3 && codes.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "حساب بنكي جديد" else "تعديل الحساب") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Input("اسم البنك", bankName) { bankName = it.take(100) }
                Input("اسم صاحب الحساب", accountName) { accountName = it.take(100) }
                Input("رقم الحساب", accountNumber) { accountNumber = it.take(50) }
                Input("الآيبان (اختياري)", iban) { iban = it.uppercase().filter { c -> !c.isWhitespace() }.take(34) }
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it.take(500) },
                    label = { Text("تعليمات للعميل (اختياري)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("العملات التي يستقبلها الحساب", style = MaterialTheme.typography.labelLarge)
                Text("أول عملة تختارها هي العملة الرئيسية.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEach { c ->
                        val selected = c.code in codes
                        val main = codes.firstOrNull() == c.code
                        FilterChip(
                            selected = selected,
                            onClick = {
                                codesText = (if (selected) codes - c.code else codes + c.code).joinToString(",")
                            },
                            leadingIcon = { CurrencyArt(c.code, currencyImages[c.code], height = 16.dp) },
                            label = { Text(if (main) "${c.nameAr} · رئيسية" else c.nameAr) },
                        )
                    }
                }
                Text("صورة الحساب (اختياري)", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Lux.Champagne),
                        contentAlignment = Alignment.Center,
                    ) {
                        val url = image
                        if (url != null) {
                            AsyncImage(model = url, contentDescription = "صورة الحساب", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        if (uploading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    Column {
                        Row {
                            TextButton(onClick = picker.fromCamera, enabled = !uploading) { Text("تصوير") }
                            TextButton(onClick = picker.fromGallery, enabled = !uploading) { Text("من المعرض") }
                        }
                        if (image != null) {
                            TextButton(onClick = { image = null }, enabled = !uploading) {
                                Text("إزالة الصورة", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("يظهر للعملاء", Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy && !uploading,
                onClick = {
                    onSave(
                        BankAccountRequest(
                            bankName = bankName.trim(),
                            accountName = accountName.trim(),
                            accountNumber = accountNumber.trim(),
                            currency = Currency.of(codes.first()),
                            iban = iban.trim().ifBlank { null },
                            instructions = instructions.trim().ifBlank { null },
                            isActive = active,
                            currencies = codes,
                            // "" removes a picture the account had.
                            imageUrl = image ?: if (existing?.imageUrl.isNullOrBlank()) null else "",
                        ),
                    )
                },
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun Input(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

// ------------------------------------------------------- transfer register ---

class BankTransfersViewModel(private val repo: WalletAdminRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<BankTransferRow>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private var page = 1
    private var query: String? = null
    private var job: kotlinx.coroutines.Job? = null

    fun search(q: String?) {
        query = q
        page = 1
        job?.cancel()
        job = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val res = repo.transfers(query, 1)
            _hasMore.value = res.getOrNull()?.hasMore == true
            _state.value = res.map { it.items }.toLoad()
        }
    }

    fun more() {
        val current = (_state.value as? Load.Ready)?.value ?: return
        viewModelScope.launch {
            repo.transfers(query, page + 1).orSay()?.let { res ->
                page += 1
                _state.value = Load.Ready(current + res.items)
                _hasMore.value = res.hasMore
            }
        }
    }
}

/** Every accepted bank transfer by its reference number: the same one is never accepted twice. */
@Composable
fun BankTransfersScreen(onBack: () -> Unit, openTopUp: (String) -> Unit) {
    val vm = adminViewModel(key = "bank-transfers") { BankTransfersViewModel(it.wallet) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    CollectMessages(vm, snackbar)
    LaunchedEffect(query) { vm.search(query) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("سجل الحوالات", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            com.dejavu.core.ui.theme.OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                label = { Text("ابحث برقم الحوالة أو البنك") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Text(
                "أرقام الحوالات المقبولة من التطبيق والكاشير. الحوالة نفسها (أو صورة الإشعار نفسها) لا تُقبل مرتين.",
                style = MaterialTheme.typography.bodySmall,
                color = Lux.InkSoft,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LoadContent(state, onRetry = { vm.search(query) }) { list ->
                if (list.isEmpty()) {
                    EmptyList(Icons.Filled.Email, "لا توجد حوالات", "تظهر هنا كل حوالة تُقبل مع رقمها")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.id }) { t ->
                            GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp, onClick = t.topUpId?.let { id -> { openTopUp(id) } }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(t.reference ?: "بدون رقم (صورة الإشعار فقط)", style = MaterialTheme.typography.titleSmall)
                                        val where = when (t.source) {
                                            "TOP_UP" -> "شحن رصيد من التطبيق"
                                            "POS" -> "بيع في الكاشير"
                                            else -> "سند قبض في الكاشير"
                                        }
                                        Text(listOfNotNull(where, t.bankName, t.customer?.name).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                        Text(formatDateTime(t.createdAt) + if (t.extracted) " · قُرئ من الصورة" else "", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                    }
                                    val amount = t.amount
                                    val code = t.currency
                                    if (amount != null && code != null) {
                                        Text(amountIn(amount, Currency.of(code)), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                                    }
                                }
                            }
                        }
                        if (hasMore) item { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text("عرض المزيد") } }
                    }
                }
            }
        }
    }
}
