@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.round9

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.BlockedPhone
import com.dejavu.core.admin.CustomerCart
import com.dejavu.core.admin.DormantCouponRequest
import com.dejavu.core.admin.DormantCustomer
import com.dejavu.core.admin.Person
import com.dejavu.core.admin.Round9Repository
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ carts ---

class CartsViewModel(repo: Round9Repository) : LoadViewModel<List<CustomerCart>>({ repo.carts() })

/** What customers have in their carts right now, newest first. */
@Composable
fun CartsScreen(onBack: () -> Unit, openCustomer: (String) -> Unit) {
    val vm = adminViewModel(key = "carts") { CartsViewModel(it.round9) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("سلات العملاء", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { carts ->
                if (carts.isEmpty()) {
                    EmptyList(Icons.Filled.ShoppingCart, "لا توجد سلات", "تظهر هنا المنتجات التي أضافها العملاء ولم يطلبوها بعد")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            val pieces = carts.sumOf { it.pieces }
                            Text("${carts.size} سلة · $pieces قطعة", style = MaterialTheme.typography.titleSmall, color = Lux.InkSoft)
                        }
                        items(carts, key = { it.id }) { c ->
                            GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp, onClick = { openCustomer(c.customer.id) }) {
                                Text("${c.customer.name}${c.customer.username?.let { " · @$it" } ?: ""}", style = MaterialTheme.typography.titleSmall)
                                Text("${c.customer.phone} · آخر تعديل ${formatDateTime(c.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                c.items.forEach { i ->
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AsyncImage(model = i.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                                        Column(Modifier.weight(1f)) {
                                            Text(i.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(listOfNotNull(i.size, i.color, i.sku).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                        }
                                        Text("× ${i.quantity}", style = MaterialTheme.typography.titleSmall)
                                        Text(Money.plain(Money.parse(i.price), Currency.SAR), style = MaterialTheme.typography.bodySmall, color = Lux.GoldDeep)
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

// --------------------------------------------------------- blocked phones ---

class BlockedPhonesViewModel(private val repo: Round9Repository) : LoadViewModel<List<BlockedPhone>>({ repo.blockedPhones() }) {
    fun block(phone: String, reason: String) = act("حُظر الرقم") { repo.blockPhone(phone, reason) }
    fun unblock(id: String) = act("أُلغي الحظر") { repo.unblockPhone(id) }
}

/** Numbers that can never sign up again (their account, if any, is blocked too). */
@Composable
fun BlockedPhonesScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "blocked-phones") { BlockedPhonesViewModel(it.round9) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var unblocking by remember { mutableStateOf<BlockedPhone?>(null) }
    CollectMessages(vm, snackbar)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("الأرقام المحظورة", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }, containerColor = Lux.Gold) { Icon(Icons.Filled.Add, "حظر رقم") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { list ->
                if (list.isEmpty()) {
                    EmptyList(Icons.Filled.Lock, "لا توجد أرقام محظورة", "الرقم المحظور لا يستطيع التسجيل أو الدخول. تقدر تحظر عميلاً من صفحته أيضاً.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.id }) { b ->
                            GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(b.phone, style = MaterialTheme.typography.titleSmall)
                                        Text(listOfNotNull(b.reason, formatDate(b.createdAt)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                    }
                                    TextButton(onClick = { unblocking = b }, enabled = !busy) { Text("إلغاء الحظر") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        var phone by rememberSaveable { mutableStateOf("") }
        var reason by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("حظر رقم") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' }.take(16) }, label = { Text("رقم الجوال") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    OutlinedTextField(value = reason, onValueChange = { reason = it.take(200) }, label = { Text("السبب (اختياري)") })
                }
            },
            confirmButton = { TextButton(onClick = { adding = false; vm.block(phone, reason) }, enabled = phone.length >= 7) { Text("حظر") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("تراجع") } },
        )
    }
    unblocking?.let { b ->
        ConfirmDialog("إلغاء حظر ${b.phone}؟", "يستطيع التسجيل من جديد، ويُفتح حسابه إن كان محظوراً.", "إلغاء الحظر", onDismiss = { unblocking = null }) { vm.unblock(b.id) }
    }
}

// -------------------------------------------------------------- instagram ---

class InstagramViewModel(private val repo: Round9Repository) : LoadViewModel<List<Person>>({ repo.instagramPending() }) {
    fun verify(id: String, code: String) = act("تم التوثيق ✔") { repo.verifyInstagram(id, code) }
    fun reject(id: String) = act("رُفض الطلب") { repo.rejectInstagram(id) }
}

/**
 * Customers who said their username is their Instagram account: each one sends a 4-digit code
 * to the store's Instagram by DM; type the code they sent to give the username the badge.
 */
@Composable
fun InstagramScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "instagram") { InstagramViewModel(it.round9) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var verifying by remember { mutableStateOf<Person?>(null) }
    CollectMessages(vm, snackbar)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("توثيق يوزرات انستجرام", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { list ->
                if (list.isEmpty()) {
                    EmptyList(Icons.Filled.Star, "لا توجد طلبات توثيق", "عند التسجيل يختار العميل «هذا يوزري في انستجرام» ويرسل كوداً من 4 أرقام إلى حساب المتجر")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text(
                                "افتح رسائل انستجرام للمتجر، وتأكد أن الرسالة جاءت من نفس اليوزر، ثم أدخل الكود الذي أرسله.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Lux.InkSoft,
                            )
                        }
                        items(list, key = { it.id }) { p ->
                            GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("@${p.username ?: ""}", style = MaterialTheme.typography.titleMedium)
                                        Text("${p.name} · ${p.phone}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                    }
                                    Button(onClick = { verifying = p }, enabled = !busy) { Text("إدخال الكود") }
                                }
                                TextButton(onClick = { vm.reject(p.id) }, enabled = !busy) { Text("رفض الطلب") }
                            }
                        }
                    }
                }
            }
        }
    }
    verifying?.let { p ->
        var code by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { verifying = null },
            title = { Text("كود @${p.username ?: ""}") },
            text = {
                OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(4) }, label = { Text("الكود (4 أرقام)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = { TextButton(onClick = { verifying = null; vm.verify(p.id, code) }, enabled = code.length == 4) { Text("توثيق") } },
            dismissButton = { TextButton(onClick = { verifying = null }) { Text("تراجع") } },
        )
    }
}

// ---------------------------------------------------------------- dormant ---

class DormantViewModel(private val repo: Round9Repository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<DormantCustomer>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    var days = 60
        private set

    init {
        load(days)
    }

    fun load(days: Int = this.days) {
        this.days = days
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.dormant(days).toLoad() }
    }

    fun send(request: DormantCouponRequest, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.dormantCoupon(request).orSay()?.let {
                say("أُرسل كود خصم إلى ${it.sent} عميل")
                done()
                _state.value = repo.dormant(days).toLoad()
            }
            _busy.value = false
        }
    }
}

/** Customers who stopped buying, and a personal "وحشتنا" coupon for them. */
@Composable
fun DormantCustomersScreen(onBack: () -> Unit, openCustomer: (String) -> Unit) {
    val vm = adminViewModel(key = "dormant") { DormantViewModel(it.round9) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val selected = remember { mutableStateListOf<String>() }
    var days by rememberSaveable { mutableStateOf(vm.days) }
    var composing by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("العملاء الخاملون", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 90, 180).forEach { d ->
                    FilterChip(selected = days == d, onClick = { days = d; selected.clear(); vm.load(d) }, label = { Text("$d يوم") })
                }
            }
            LoadContent(state, onRetry = { vm.load() }) { list ->
                if (list.isEmpty()) {
                    EmptyList(Icons.Filled.Face, "لا يوجد عملاء خاملون", "كل من اشترى سابقاً اشترى خلال آخر $days يوم")
                } else {
                    Column(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${list.size} عميل لم يشترِ منذ $days يوماً أو أكثر", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        if (selected.size == list.size) selected.clear() else { selected.clear(); selected.addAll(list.map { it.id }) }
                                    }) { Text(if (selected.size == list.size) "إلغاء التحديد" else "تحديد الكل") }
                                }
                            }
                            items(list, key = { it.id }) { c ->
                                GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 10.dp) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = c.id in selected, onCheckedChange = { on -> if (on) selected.add(c.id) else selected.remove(c.id) })
                                        Column(Modifier.weight(1f).clickable { openCustomer(c.id) }) {
                                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                                            Text("${c.phone} · ${c.orders} طلب · آخر شراء ${formatDate(c.lastOrderAt)} (${c.daysAway} يوم)", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                            c.openCoupon?.let { Text("لديه كود لم يستخدمه: $it", style = MaterialTheme.typography.labelSmall, color = Lux.GoldDeep) }
                                        }
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { composing = true },
                            enabled = selected.isNotEmpty() && !busy,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text("إرسال كود خصم (${selected.size})") }
                    }
                }
            }
        }
    }
    if (composing) {
        CouponDialog(count = selected.size, onDismiss = { composing = false }) { type, value, validDays, message ->
            vm.send(DormantCouponRequest(customerIds = selected.toList(), type = type, value = value, validDays = validDays, message = message.takeIf { it.isNotBlank() })) {
                composing = false
                selected.clear()
            }
        }
    }
}

@Composable
private fun CouponDialog(count: Int, onDismiss: () -> Unit, onSend: (type: String, value: String, validDays: Int, message: String) -> Unit) {
    var type by rememberSaveable { mutableStateOf("PERCENTAGE") }
    var value by rememberSaveable { mutableStateOf("10") }
    var validDays by rememberSaveable { mutableStateOf("14") }
    var message by rememberSaveable { mutableStateOf("") }
    val v = value.toBigDecimalOrNull()
    val valid = (type == "FREE_SHIPPING" || (v != null && v.signum() > 0 && (type != "PERCENTAGE" || v.toInt() <= 90))) && (validDays.toIntOrNull() ?: 0) in 1..365
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("كود «وحشتنا» لـ $count عميل") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = type == "PERCENTAGE", onClick = { type = "PERCENTAGE" }, label = { Text("نسبة %") })
                    FilterChip(selected = type == "FIXED_AMOUNT", onClick = { type = "FIXED_AMOUNT" }, label = { Text("مبلغ") })
                    FilterChip(selected = type == "FREE_SHIPPING", onClick = { type = "FREE_SHIPPING" }, label = { Text("شحن مجاني") })
                }
                if (type != "FREE_SHIPPING") {
                    OutlinedTextField(value = value, onValueChange = { value = it.filter { c -> c.isDigit() || c == '.' }.take(7) }, label = { Text(if (type == "PERCENTAGE") "النسبة %" else "المبلغ (ر.س)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                OutlinedTextField(value = validDays, onValueChange = { validDays = it.filter(Char::isDigit).take(3) }, label = { Text("صالح لعدد أيام") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = message, onValueChange = { message = it.take(300) }, label = { Text("نص الإشعار (اختياري)") })
                Text("لكل عميل كود خاص به لا يعمل لغيره، يصله في إشعار.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
        },
        confirmButton = { TextButton(onClick = { onSend(type, if (type == "FREE_SHIPPING") "0" else value, validDays.toInt(), message) }, enabled = valid) { Text("إرسال") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}
