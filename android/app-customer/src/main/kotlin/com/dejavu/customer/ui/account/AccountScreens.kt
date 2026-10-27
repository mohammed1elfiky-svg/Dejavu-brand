@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.account

import com.dejavu.core.ui.theme.GlassTopBar
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.GoldBadge
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.customer.ui.components.GlassRow
import com.dejavu.customer.ui.components.GoldIconCircle
import com.dejavu.customer.ui.components.goldChipColors
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import com.dejavu.core.ui.theme.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalUriHandler
import com.dejavu.core.network.normalizeBaseUrl
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.AddressRepository
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.data.Validators
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Address
import com.dejavu.core.model.AddressRequest
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.ShippingZone
import com.dejavu.core.model.StockAlert
import com.dejavu.customer.BuildConfig
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.Load
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.appViewModel
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.Tag
import com.dejavu.core.ui.label
import com.dejavu.core.ui.theme.Gold
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- account ---

@Composable
fun AccountScreen(
    login: () -> Unit,
    register: () -> Unit,
    addresses: () -> Unit,
    orders: () -> Unit,
    notifications: () -> Unit,
    wallet: () -> Unit,
    server: () -> Unit,
    stockAlerts: () -> Unit = {},
    wishlist: () -> Unit = {},
    about: () -> Unit = {},
    chat: () -> Unit = {},
    preOrders: () -> Unit = {},
) {
    val container = appContainer()
    val session by container.session.state.collectAsStateWithLifecycle()
    val config by container.extras.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val openPrivacy = { runCatching { uriHandler.openUri(normalizeBaseUrl(container.settings.baseUrl) + "privacy") } }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { GlassTopBar(title = { Text("حسابي") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val s = session) {
                is SessionState.LoggedIn -> {
                    GlassCard(Modifier.fillMaxWidth(), elevation = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            GoldIconCircle(Icons.Filled.Person, size = 56.dp)
                            Column(Modifier.weight(1f)) {
                                Text(s.customer?.name ?: "…", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    s.customer?.phone.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        s.customer?.let { c ->
                            if (c.loyaltyPoints > 0) GoldBadge("نقاط الولاء: ${c.loyaltyPoints}", Modifier.padding(top = 10.dp))
                            if (c.isMerchant) {
                                Row(
                                    Modifier.padding(top = 10.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Tag("حساب تاجر", Gold)
                                    Text(
                                        c.creditLimit?.let { "حد الشراء الآجل: ${Money.formatBase(it)}" } ?: "شراء آجل بدون حد",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "تظهر لك أسعار التاجر في المتجر، ويمكنك الدفع آجلاً على حسابك",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    MenuHeader("حسابي")
                    MenuRow(Icons.AutoMirrored.Filled.List, "طلباتي", orders)
                    MenuRow(Icons.Filled.AccountBox, "المحفظة / رصيدي", wallet)
                    MenuRow(Icons.Filled.LocationOn, "عناويني", addresses)
                    MenuRow(Icons.Filled.Notifications, "الإشعارات", notifications)
                    MenuRow(Icons.Filled.Notifications, "تنبيهات التوفر", stockAlerts)
                    MenuRow(Icons.Filled.Favorite, "المفضلة", wishlist)
                    MenuRow(Icons.Filled.DateRange, "حجوزاتي المسبقة", preOrders)
                    MenuHeader("المتجر")
                    if (config.chat.enabled) MenuRow(Icons.Filled.Face, "مساعد التسوق", chat)
                    MenuRow(Icons.Filled.Info, "عن المتجر", about)
                    MenuHeader("الإعدادات")
                    CurrencyRow()
                    if (BuildConfig.DEBUG) MenuRow(Icons.Filled.Settings, "الخادم", server)
                    MenuRow(Icons.Filled.Lock, "سياسة الخصوصية") { openPrivacy() }
                    MenuRow(Icons.AutoMirrored.Filled.ExitToApp, "تسجيل الخروج") { scope.launch { container.logout() } }
                    MenuRow(Icons.Filled.Delete, "حذف الحساب") { deleting = true }
                }
                SessionState.LoggedOut -> {
                    GlassCard(Modifier.fillMaxWidth(), elevation = 14.dp, contentPadding = 20.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            GoldIconCircle(Icons.Filled.Person, size = 56.dp)
                            Text("أهلاً بك في Dejavu", style = MaterialTheme.typography.headlineSmall)
                            Text("سجّل الدخول لمتابعة طلباتك وحفظ عناوينك.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = login, modifier = Modifier.fillMaxWidth()) { Text("تسجيل الدخول") }
                            OutlinedButton(onClick = register, modifier = Modifier.fillMaxWidth()) { Text("إنشاء حساب جديد") }
                        }
                    }
                    MenuHeader("المتجر")
                    if (config.chat.enabled) MenuRow(Icons.Filled.Face, "مساعد التسوق", chat)
                    MenuRow(Icons.Filled.Info, "عن المتجر", about)
                    MenuHeader("الإعدادات")
                    CurrencyRow()
                    if (BuildConfig.DEBUG) MenuRow(Icons.Filled.Settings, "الخادم", server)
                    MenuRow(Icons.Filled.Lock, "سياسة الخصوصية") { openPrivacy() }
                }
            }
            Text(
                "الإصدار ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (deleting) DeleteAccountDialog(onClose = { deleting = false })
}

/** Deleting the account for good: what goes, what stays, and the password once more. */
@Composable
private fun DeleteAccountDialog(onClose: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("حذف الحساب نهائياً") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "سيُحذف اسمك ورقم جوالك وعناوينك والسلة والمفضلة والإشعارات ومحادثات المساعد، ويُفقد رصيد المحفظة والنقاط. " +
                        "تبقى الفواتير السابقة بدون بياناتك لأسباب محاسبية. لا يمكن التراجع عن الحذف.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text("كلمة المرور للتأكيد") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null,
                    supportingText = error?.let { e -> { Text(e) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        container.session.deleteAccount(password)
                            .onSuccess { onClose() }
                            .onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
            ) { Text(if (busy) "جارٍ الحذف…" else "حذف حسابي", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("إلغاء") } },
    )
}

/** Display currency: the ones the store offers shoppers. Orders already placed keep the currency and rate they were placed with. */
@Composable
private fun CurrencyRow() {
    val currencies = appContainer().currencies
    val selected by currencies.selected.collectAsStateWithLifecycle()
    val rates by currencies.rates.collectAsStateWithLifecycle()
    val available by currencies.available.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val rateOf = { c: Currency -> rates?.rate(c)?.rate?.toBigDecimalOrNull() }
    val selectedRate = rateOf(selected)
    GlassRow(
        title = "العملة: ${selected.nameAr}",
        subtitle = selectedRate?.takeIf { !selected.isBase }?.let { r -> "1 ${Currency.SAR.symbol} = ${Money.plain(r, selected)}" },
        leading = { GoldIconCircle(Icons.Filled.ShoppingCart) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CurrencyArt(selected.code, rates?.rate(selected)?.imageUrl, height = 22.dp)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Liquid.GoldDeep)
            }
        },
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("عملة عرض الأسعار") },
            text = {
                Column {
                    available.forEach { c ->
                        val rate = rateOf(c)
                        val usable = c.isBase || rate != null
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = usable) { currencies.select(c); open = false }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c == selected, onClick = null, enabled = usable, colors = RadioButtonDefaults.colors(selectedColor = Liquid.GoldDeep))
                            CurrencyArt(c.code, rates?.rate(c)?.imageUrl, height = 22.dp, modifier = Modifier.padding(horizontal = 8.dp))
                            Column {
                                Text("${c.nameAr} (${c.symbol})")
                                if (!c.isBase && rate != null) {
                                    Text(
                                        "1 ${Currency.SAR.symbol} = ${Money.plain(rate, c)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "الأسعار محفوظة بالريال السعودي وتُحوَّل بسعر الصرف الحالي. عند إتمام الطلب يُثبَّت سعر الصرف في فاتورتك.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("إغلاق") } },
        )
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    GlassRow(
        title = text,
        leading = { GoldIconCircle(icon) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Liquid.GoldDeep) },
        onClick = onClick,
    )
}

/** Gold heading above a group of menu rows. */
@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Liquid.GoldDeep,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

// ----------------------------------------------------------- stock alerts ---

class StockAlertsViewModel(private val catalog: CatalogRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<StockAlert>>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = catalog.stockAlerts().toLoad() }
    }

    fun remove(a: StockAlert) = viewModelScope.launch {
        catalog.unwatchStock(a.variantId).orSay()?.let { _state.value = Load.Ready(it) }
    }
}

/** Sizes / colours the shopper asked to be told about when they are back in stock. */
@Composable
fun StockAlertsScreen(onBack: () -> Unit, openProduct: (String) -> Unit) {
    val vm = appViewModel { StockAlertsViewModel(it.catalog) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("تنبيهات التوفر", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            if (list.isEmpty()) {
                EmptyState(
                    Icons.Filled.Notifications,
                    "لا توجد تنبيهات توفر",
                    "اضغط «أعلمني عند التوفر» في صفحة منتج نفدت كميته وسنُعلمك عند توفره",
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.variantId }) { a ->
                        val slug = a.productSlug?.takeIf { it.isNotBlank() }
                        GlassRow(
                            title = a.productName ?: "منتج",
                            subtitle = listOfNotNull(a.color, a.size?.let { "مقاس $it" }).joinToString(" · ").ifBlank { null },
                            leading = { GoldIconCircle(Icons.Filled.Notifications, size = 36.dp) },
                            trailing = {
                                IconButton(onClick = { vm.remove(a) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "إزالة التنبيه", tint = Liquid.InkSoft)
                                }
                            },
                            onClick = slug?.let { s -> { openProduct(s) } },
                        )
                    }
                }
            }
        }
    }
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
    label = label, district = district, landmark = landmark,
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
        floatingActionButton = {
            FloatingActionButton(onClick = { edit(null) }, containerColor = Liquid.Gold, contentColor = androidx.compose.ui.graphics.Color.White) {
                Icon(Icons.Filled.Add, contentDescription = "إضافة عنوان")
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            if (list.isEmpty()) {
                EmptyState(Icons.Filled.LocationOn, "لا توجد عناوين محفوظة", action = "إضافة عنوان", onAction = { edit(null) }, modifier = Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { a ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    GoldIconCircle(Icons.Filled.LocationOn, size = 36.dp)
                                    Text(a.label ?: a.recipient, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    if (a.isDefault) StatusChip("الافتراضي", Success)
                                }
                                Text(a.summary, style = MaterialTheme.typography.bodySmall)
                                Text("${a.recipient} · ${a.phone}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    if (!a.isDefault) TextButton(onClick = { vm.makeDefault(a) }) { Text("اجعله افتراضياً", color = Liquid.GoldDeep) }
                                    IconButton(onClick = { edit(a.id) }) { Icon(Icons.Filled.Edit, contentDescription = "تعديل", tint = Liquid.GoldDeep) }
                                    IconButton(onClick = { confirmDelete = a }) { Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = Liquid.InkSoft) }
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
        label = label.trim().ifBlank { null }, district = district.trim().ifBlank { null }, landmark = landmark.trim().ifBlank { null },
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
        viewModelScope.launch { catalog.shippingZones().orSay()?.let { list -> _zones.value = list.filter { it.isActive } } }
        if (id != null) {
            viewModelScope.launch {
                repo.list().orSay()?.firstOrNull { it.id == id }?.let { a ->
                    _form.value = AddressForm(
                        a.label.orEmpty(), a.recipient, a.phone, a.governorate, a.city, a.district.orEmpty(), a.street,
                        a.landmark.orEmpty(), a.isDefault,
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("اسم العنوان (اختياري)", form.label, placeholder = "المنزل، العمل…") { v -> vm.update { copy(label = v) } }
                    Field("اسم المستلم", form.recipient, err("recipient")) { v -> vm.update { copy(recipient = v) } }
                    Field("موبايل المستلم", form.phone, err("phone"), placeholder = "771234567", keyboard = KeyboardType.Phone) { v -> vm.update { copy(phone = v) } }

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
                    Field("المدينة / المنطقة", form.city, err("city"), placeholder = "مثال: صنعاء، عدن") { v -> vm.update { copy(city = v) } }
                    Field("الحي (اختياري)", form.district) { v -> vm.update { copy(district = v) } }
                    Field("الشارع", form.street, err("street")) { v -> vm.update { copy(street = v) } }
                    Field("علامة مميزة (اختياري)", form.landmark) { v -> vm.update { copy(landmark = v) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = form.isDefault,
                            onCheckedChange = { c -> vm.update { copy(isDefault = c) } },
                            colors = CheckboxDefaults.colors(checkedColor = Liquid.GoldDeep),
                        )
                        Text("اجعله العنوان الافتراضي")
                    }
                }
            }
            Button(onClick = vm::save, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("حفظ العنوان") }
        }
    }

    if (pickGovernorate) {
        GovernoratePicker(
            zones = zones,
            selected = form.governorate,
            onPick = { g ->
                vm.update { copy(governorate = g) }
                pickGovernorate = false
            },
            onDismiss = { pickGovernorate = false },
        )
    }
}

/** Searchable list of the governorates we deliver to (active shipping zones). */
@Composable
fun GovernoratePicker(zones: List<ShippingZone>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(zones, query) {
        val q = query.trim()
        if (q.isEmpty()) zones else zones.filter { it.governorate.contains(q, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("المحافظة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (zones.isEmpty()) {
                    Text("لا توجد مناطق توصيل متاحة حالياً")
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("ابحث عن المحافظة") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (shown.isEmpty()) {
                        Text("لا توجد نتائج", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(shown, key = { it.governorate }) { z ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            z.governorate,
                                            color = if (z.governorate == selected) Liquid.GoldDeep else androidx.compose.ui.graphics.Color.Unspecified,
                                            fontWeight = if (z.governorate == selected) FontWeight.Bold else null,
                                        )
                                    },
                                    modifier = Modifier.clickable { onPick(z.governorate) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
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

// ----------------------------------------------------------------- server ---

@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val settings = container.settings
    var url by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("الخادم", onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).imePadding()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GoldIconCircle(Icons.Filled.Settings)
                        Text("عنوان خادم Dejavu الذي يتصل به التطبيق.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
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
                    status?.let { Text(it, color = Liquid.GoldDeep) }
                }
            }
        }
    }
}
