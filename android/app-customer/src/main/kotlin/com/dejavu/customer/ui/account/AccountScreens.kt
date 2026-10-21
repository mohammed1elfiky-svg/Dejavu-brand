@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.AddressRepository
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.OrderRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.data.Validators
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Address
import com.dejavu.core.model.AddressRequest
import com.dejavu.core.model.Money
import com.dejavu.core.model.ReturnStatus
import com.dejavu.core.model.SalesReturn
import com.dejavu.core.model.ShippingZone
import com.dejavu.customer.BuildConfig
import com.dejavu.customer.ui.BaseViewModel
import com.dejavu.customer.ui.Load
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.appViewModel
import com.dejavu.customer.ui.components.BackTopBar
import com.dejavu.customer.ui.components.CollectMessages
import com.dejavu.customer.ui.components.EmptyState
import com.dejavu.customer.ui.components.LoadContent
import com.dejavu.customer.ui.components.StatusChip
import com.dejavu.customer.ui.formatDate
import com.dejavu.customer.ui.label
import com.dejavu.customer.ui.theme.Gold
import com.dejavu.customer.ui.theme.Success
import com.dejavu.customer.ui.theme.Warning
import com.dejavu.customer.ui.toLoad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- account ---

@Composable
fun AccountScreen(login: () -> Unit, register: () -> Unit, addresses: () -> Unit, returns: () -> Unit, orders: () -> Unit, server: () -> Unit) {
    val container = appContainer()
    val session by container.session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { CenterAlignedTopAppBar(title = { Text("حسابي") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (val s = session) {
                is SessionState.LoggedIn -> {
                    ListItem(
                        headlineContent = { Text(s.customer?.name ?: "…", style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(listOfNotNull(s.customer?.phone, s.customer?.email).joinToString(" · ")) },
                        leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                    )
                    s.customer?.let { c ->
                        if (c.loyaltyPoints > 0) Text("نقاط الولاء: ${c.loyaltyPoints}", Modifier.padding(horizontal = 16.dp), color = Gold)
                    }
                    HorizontalDivider()
                    MenuRow(Icons.AutoMirrored.Filled.List, "طلباتي", orders)
                    MenuRow(Icons.Filled.LocationOn, "عناويني", addresses)
                    MenuRow(Icons.Filled.Refresh, "طلبات الإرجاع", returns)
                    HorizontalDivider()
                    MenuRow(Icons.Filled.Settings, "الخادم", server)
                    MenuRow(Icons.AutoMirrored.Filled.ExitToApp, "تسجيل الخروج") { scope.launch { container.session.logout() } }
                }
                SessionState.LoggedOut -> {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("أهلاً بك في Dejavu", style = MaterialTheme.typography.headlineSmall)
                        Text("سجّل الدخول لمتابعة طلباتك وحفظ عناوينك.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = login, modifier = Modifier.fillMaxWidth()) { Text("تسجيل الدخول") }
                        OutlinedButton(onClick = register, modifier = Modifier.fillMaxWidth()) { Text("إنشاء حساب جديد") }
                    }
                    HorizontalDivider()
                    MenuRow(Icons.Filled.Settings, "الخادم", server)
                }
            }
            Text(
                "الإصدار ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(text) }, leadingContent = { Icon(icon, contentDescription = null) }, modifier = Modifier.clickable(onClick = onClick))
}

// -------------------------------------------------------------- addresses ---

class AddressesViewModel(private val repo: AddressRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<Address>>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.list().toLoad() }
    }

    fun delete(a: Address) = viewModelScope.launch { repo.delete(a.id).orSay()?.let { load() } }

    fun makeDefault(a: Address) = viewModelScope.launch {
        repo.update(a.id, a.toRequest().copy(isDefault = true)).orSay()?.let { load() }
    }
}

fun Address.toRequest() = AddressRequest(
    recipient = recipient, phone = phone, governorate = governorate, city = city, street = street,
    label = label, district = district, building = building, floor = floor, apartment = apartment, landmark = landmark,
)

@Composable
fun AddressesScreen(onBack: () -> Unit, edit: (String?) -> Unit) {
    val vm = appViewModel { AddressesViewModel(it.addresses) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<Address?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("عناويني", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { FloatingActionButton(onClick = { edit(null) }) { Icon(Icons.Filled.Add, contentDescription = "إضافة عنوان") } },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            if (list.isEmpty()) {
                EmptyState(Icons.Filled.LocationOn, "لا توجد عناوين محفوظة", action = "إضافة عنوان", onAction = { edit(null) }, modifier = Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { a ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(a.label ?: a.recipient, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    if (a.isDefault) StatusChip("الافتراضي", Success)
                                }
                                Text(a.summary, style = MaterialTheme.typography.bodySmall)
                                Text("${a.recipient} · ${a.phone}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    if (!a.isDefault) TextButton(onClick = { vm.makeDefault(a) }) { Text("اجعله افتراضياً") }
                                    IconButton(onClick = { edit(a.id) }) { Icon(Icons.Filled.Edit, contentDescription = "تعديل") }
                                    IconButton(onClick = { confirmDelete = a }) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف العنوان؟") },
            text = { Text(a.summary) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.delete(a) }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("تراجع") } },
        )
    }
}

// ----------------------------------------------------------- address form ---

data class AddressForm(
    val label: String = "",
    val recipient: String = "",
    val phone: String = "",
    val governorate: String = "",
    val city: String = "",
    val district: String = "",
    val street: String = "",
    val building: String = "",
    val floor: String = "",
    val apartment: String = "",
    val landmark: String = "",
    val isDefault: Boolean = false,
    val showErrors: Boolean = false,
) {
    val errors: Map<String, String>
        get() = buildMap {
            Validators.name(recipient)?.let { put("recipient", it) }
            Validators.phone(phone)?.let { put("phone", it) }
            if (governorate.isBlank()) put("governorate", "اختر المحافظة")
            Validators.required(city, "المدينة / المنطقة")?.let { put("city", it) }
            Validators.required(street, "الشارع")?.let { put("street", it) }
        }

    fun toRequest() = AddressRequest(
        recipient = recipient.trim(), phone = phone.filterNot { it.isWhitespace() }, governorate = governorate, city = city.trim(), street = street.trim(),
        label = label.trim().ifBlank { null }, district = district.trim().ifBlank { null }, building = building.trim().ifBlank { null },
        floor = floor.trim().ifBlank { null }, apartment = apartment.trim().ifBlank { null }, landmark = landmark.trim().ifBlank { null },
        isDefault = isDefault.takeIf { it },
    )
}

class AddressFormViewModel(private val id: String?, private val repo: AddressRepository, private val catalog: CatalogRepository) : BaseViewModel() {
    private val _form = MutableStateFlow(AddressForm())
    val form = _form.asStateFlow()
    private val _zones = MutableStateFlow<List<ShippingZone>>(emptyList())
    val zones = _zones.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

    init {
        viewModelScope.launch { catalog.shippingZones().orSay()?.let { _zones.value = it } }
        if (id != null) {
            viewModelScope.launch {
                repo.list().orSay()?.firstOrNull { it.id == id }?.let { a ->
                    _form.value = AddressForm(
                        a.label.orEmpty(), a.recipient, a.phone, a.governorate, a.city, a.district.orEmpty(), a.street,
                        a.building.orEmpty(), a.floor.orEmpty(), a.apartment.orEmpty(), a.landmark.orEmpty(), a.isDefault,
                    )
                }
            }
        }
    }

    fun update(change: AddressForm.() -> AddressForm) {
        _form.value = _form.value.change()
    }

    fun save() {
        val f = _form.value
        if (f.errors.isNotEmpty()) {
            _form.value = f.copy(showErrors = true)
            return
        }
        _saving.value = true
        viewModelScope.launch {
            val result = if (id == null) repo.create(f.toRequest()) else repo.update(id, f.toRequest())
            result.onSuccess { _saved.tryEmit(Unit) }.onFailure { say(it.userMessage()) }
            _saving.value = false
        }
    }
}

@Composable
fun AddressFormScreen(id: String?, onDone: () -> Unit) {
    val vm = appViewModel(key = "address:$id") { AddressFormViewModel(id, it.addresses, it.catalog) }
    val form by vm.form.collectAsStateWithLifecycle()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickGovernorate by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.saved.collect { onDone() } }

    val err = { key: String -> if (form.showErrors) form.errors[key] else null }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(if (id == null) "عنوان جديد" else "تعديل العنوان", onDone) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field("اسم العنوان (اختياري)", form.label, placeholder = "المنزل، العمل…") { v -> vm.update { copy(label = v) } }
            Field("اسم المستلم", form.recipient, err("recipient")) { v -> vm.update { copy(recipient = v) } }
            Field("موبايل المستلم", form.phone, err("phone"), keyboard = KeyboardType.Phone) { v -> vm.update { copy(phone = v) } }

            // Read-only field that opens the list of governorates we deliver to.
            Box {
                OutlinedTextField(
                    value = form.governorate,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("المحافظة") },
                    isError = err("governorate") != null,
                    supportingText = err("governorate")?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable { pickGovernorate = true })
            }
            Field("المدينة / المنطقة", form.city, err("city")) { v -> vm.update { copy(city = v) } }
            Field("الحي (اختياري)", form.district) { v -> vm.update { copy(district = v) } }
            Field("الشارع ورقم العقار", form.street, err("street")) { v -> vm.update { copy(street = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("المبنى", form.building, modifier = Modifier.weight(1f)) { v -> vm.update { copy(building = v) } }
                Field("الدور", form.floor, modifier = Modifier.weight(1f)) { v -> vm.update { copy(floor = v) } }
                Field("الشقة", form.apartment, modifier = Modifier.weight(1f)) { v -> vm.update { copy(apartment = v) } }
            }
            Field("علامة مميزة (اختياري)", form.landmark) { v -> vm.update { copy(landmark = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = form.isDefault, onCheckedChange = { c -> vm.update { copy(isDefault = c) } })
                Text("اجعله العنوان الافتراضي")
            }
            Button(onClick = vm::save, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("حفظ العنوان") }
        }
    }

    if (pickGovernorate) {
        AlertDialog(
            onDismissRequest = { pickGovernorate = false },
            title = { Text("المحافظة") },
            text = {
                if (zones.isEmpty()) {
                    Text("لا توجد مناطق توصيل متاحة حالياً")
                } else {
                    LazyColumn {
                        items(zones, key = { it.governorate }) { z ->
                            ListItem(
                                headlineContent = { Text(z.governorate) },
                                supportingContent = { Text("الشحن ${Money.format(z.fee)} · ${z.estimatedDays} أيام") },
                                modifier = Modifier.clickable {
                                    vm.update { copy(governorate = z.governorate) }
                                    pickGovernorate = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickGovernorate = false }) { Text("إغلاق") } },
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String? = null,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- returns ---

class ReturnsViewModel(private val orders: OrderRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<SalesReturn>>>(Load.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = orders.returns().toLoad() }
    }
}

@Composable
fun ReturnsScreen(onBack: () -> Unit, openOrder: (String) -> Unit) {
    val vm = appViewModel { ReturnsViewModel(it.orders) }
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("طلبات الإرجاع", onBack) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            if (list.isEmpty()) {
                EmptyState(Icons.Filled.Refresh, "لا توجد طلبات إرجاع", "يمكنك طلب إرجاع من صفحة الطلب بعد استلامه", modifier = Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { r ->
                        Card(onClick = { openOrder(r.order.number) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.number, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    StatusChip(r.status.label, r.status.color())
                                }
                                Text("الطلب ${r.order.number} · ${formatDate(r.requestedAt)}", style = MaterialTheme.typography.bodySmall)
                                r.items.forEach { Text("• ${it.orderItem.productName} × ${it.quantity}", style = MaterialTheme.typography.bodySmall) }
                                Text("قيمة الإرجاع: ${Money.format(r.refundAmount)}", style = MaterialTheme.typography.bodyMedium)
                                r.rejectReason?.let { Text("سبب الرفض: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReturnStatus.color() = when (this) {
    ReturnStatus.REQUESTED, ReturnStatus.APPROVED -> Warning
    ReturnStatus.RECEIVED, ReturnStatus.REFUNDED -> Success
    ReturnStatus.REJECTED -> MaterialTheme.colorScheme.error
}

// ----------------------------------------------------------------- server ---

@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val settings = container.settings
    var url by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("الخادم", onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("عنوان خادم Dejavu الذي يتصل به التطبيق.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("رابط الخادم") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "• المحاكي على نفس الجهاز: http://10.0.2.2:3000\n• GitHub Codespaces: رابط المنفذ 3000 بعد جعله Public",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    settings.setBaseUrl(url)
                    url = settings.baseUrl
                    status = "جارٍ الاختبار…"
                    scope.launch {
                        status = container.catalog.categories(refresh = true).fold(
                            { "✓ متصل بنجاح (${it.size} أقسام)" },
                            { "✗ ${it.userMessage()}" },
                        )
                        container.session.restore()
                    }
                }) { Text("حفظ واختبار") }
                OutlinedButton(onClick = { url = settings.defaultBaseUrl }) { Text("الافتراضي") }
            }
            status?.let { Text(it) }
        }
    }
}
