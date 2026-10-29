@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.deals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.layaway.cleanAmount
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminProduct
import com.dejavu.core.admin.Deal
import com.dejavu.core.admin.DealRequest
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.OffersRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.QuantityStepper
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private const val BUY_X_GET_Y = "BUY_X_GET_Y"
private const val BUNDLE = "BUNDLE"

private val zone: ZoneId = ZoneId.systemDefault()

private fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun isoOf(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

/** "200" from "200.00". */
private fun plainAmount(v: String?): String = v?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString().orEmpty()

// ==================================================================== list ===

class DealsViewModel(private val repo: OffersRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<Deal>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val refresher = Refresher()
    private var job: Job? = null

    /** Loads quietly when the list is already on screen. */
    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            val r = repo.deals()
            if (_state.value is Load.Ready) r.fold({ _state.value = Load.Ready(it) }, { say(it.userMessage()) }) else _state.value = r.toLoad()
        }
    }

    fun refresh() = refresher.run(viewModelScope) {
        repo.deals().fold({ _state.value = Load.Ready(it) }, { say(it.userMessage()) })
    }

    fun setActive(d: Deal, active: Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                repo.updateDeal(d.id, DealRequest(name = d.name, type = d.type, isActive = active, productIds = d.products.map { it.id }))
                    .orSay()?.let { updated ->
                        replace { list -> list.map { if (it.id == updated.id) updated else it } }
                        say(if (updated.isActive) "تم تفعيل العرض" else "تم إيقاف العرض")
                    }
            } finally {
                _busy.value = false
            }
        }
    }

    fun delete(d: Deal) {
        viewModelScope.launch {
            repo.deleteDeal(d.id).orSay()?.let {
                replace { list -> list.filterNot { it.id == d.id } }
                say("تم حذف العرض")
            }
        }
    }

    private fun replace(block: (List<Deal>) -> List<Deal>) {
        val current = _state.value
        if (current is Load.Ready) _state.value = Load.Ready(block(current.value))
    }
}

/** Quantity offers: buy X get Y free, sets at one price. */
@Composable
fun DealsScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val vm = adminViewModel(key = "deals") { DealsViewModel(it.offers) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<Deal?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("عروض الكمية", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { open("new") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("عرض جديد") },
                containerColor = Lux.GoldDeep,
                contentColor = Color.White,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LoadContent(state, onRetry = vm::load) { list ->
                RefreshList(refreshing, vm::refresh) {
                    if (list.isEmpty()) {
                        EmptyList(
                            Icons.Filled.Star,
                            "لا توجد عروض بعد",
                            "أنشئ عروضاً مثل «قطعة وقطعة مجاناً»، «قطعتين والثالثة مجاناً» أو «طقم حذاء + شنطة بسعر واحد»، وتُطبَّق تلقائياً في الكاشير وتطبيق العملاء",
                            "عرض جديد",
                        ) { open("new") }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(list, key = { it.id }) { d ->
                                DealCard(
                                    d = d,
                                    enabled = !busy,
                                    onClick = { open(d.id) },
                                    onToggle = { vm.setActive(d, it) },
                                    onDelete = { deleting = d },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { d ->
        ConfirmDialog(
            title = "حذف العرض",
            text = "سيتم حذف «${d.name}» ولن يُطبَّق على المبيعات الجديدة. هل تريد المتابعة؟",
            confirm = "حذف",
            onDismiss = { deleting = null },
        ) { vm.delete(d) }
    }
}

@Composable
private fun DealCard(d: Deal, enabled: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (d.label.isNotBlank()) {
                    GoldPill { Text(d.label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.size(6.dp))
                }
                Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = d.isActive, onCheckedChange = onToggle, enabled = enabled)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            d.products.take(5).forEach { p ->
                ProductImage(p.imageUrl, Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)), p.nameAr)
            }
            if (d.products.size > 5) Text("+${d.products.size - 5}", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
            Spacer(Modifier.width(4.dp))
            Text("${d.products.size} منتج", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        Text(
            "من ${formatDate(d.startsAt)}" + (d.endsAt?.let { " · حتى ${formatDate(it)}" } ?: " · بدون تاريخ انتهاء"),
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 6.dp),
        )
        val where = listOfNotNull(if (d.atPos) "الكاشير" else null, if (d.online) "التطبيق" else null).joinToString(" · ").ifBlank { "غير معروض في أي مكان" }
        Text("يُطبَّق في: $where", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        val limits = listOfNotNull(
            d.maxPieces?.let { "القطع ${d.usedPieces}/$it" },
            d.maxCustomers?.let { "العملاء ${d.usedCustomers}/$it" },
        )
        if (limits.isNotEmpty()) {
            Text("لأول المشترين: ${limits.joinToString(" · ")}${if (d.exhausted) " · نفد العرض" else ""}", style = MaterialTheme.typography.bodySmall, color = if (d.exhausted) MaterialTheme.colorScheme.error else Lux.GoldDeep)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val now = Instant.now()
            when {
                d.running -> StatusChip("يعمل الآن", Success)
                !d.isActive -> StatusChip("متوقف", MaterialTheme.colorScheme.onSurfaceVariant)
                parseInstant(d.startsAt)?.isAfter(now) == true -> StatusChip("لم يبدأ بعد", Warning)
                else -> StatusChip("انتهى", MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ==================================================================== edit ===

/** A product chosen for the offer. */
data class DealPick(val id: String, val name: String, val price: String, val imageUrl: String?)

data class DealForm(
    val type: String = BUY_X_GET_Y,
    val buyQty: Int = 1,
    val freeQty: Int = 1,
    val name: String = "قطعة وقطعة مجاناً",
    /** The name was typed by hand, so it no longer follows the preset. */
    val nameEdited: Boolean = false,
    val bundlePrice: String = "",
    /** null = now. */
    val startsAt: String? = null,
    /** null = no end. */
    val endsAt: String? = null,
    val isActive: Boolean = true,
    val atPos: Boolean = true,
    val online: Boolean = true,
    val products: List<DealPick> = emptyList(),
    /** "" = no limit. */
    val maxPieces: String = "",
    val maxCustomers: String = "",
)

private fun suggestedName(f: DealForm): String = if (f.type == BUNDLE) {
    val price = f.bundlePrice.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    if (price != null) "طقم بسعر ${price.stripTrailingZeros().toPlainString()}" else "طقم بسعر واحد"
} else {
    when (f.buyQty to f.freeQty) {
        1 to 1 -> "قطعة وقطعة مجاناً"
        2 to 1 -> "قطعتين والثالثة مجاناً"
        3 to 1 -> "3 قطع والرابعة مجاناً"
        2 to 2 -> "قطعتين وقطعتين مجاناً"
        else -> "اشترِ ${f.buyQty} واحصل على ${f.freeQty} مجاناً"
    }
}

private fun Deal.toForm() = DealForm(
    type = type,
    buyQty = buyQty,
    freeQty = freeQty,
    name = name,
    nameEdited = true,
    bundlePrice = plainAmount(bundlePrice),
    startsAt = startsAt,
    endsAt = endsAt,
    isActive = isActive,
    atPos = atPos,
    online = online,
    products = products.map { DealPick(it.id, it.nameAr, it.basePrice, it.imageUrl) },
    maxPieces = maxPieces?.toString().orEmpty(),
    maxCustomers = maxCustomers?.toString().orEmpty(),
)

class DealEditViewModel(
    private val id: String?,
    private val offers: OffersRepository,
    private val inventory: InventoryRepository,
) : BaseViewModel() {
    val isNew: Boolean get() = id == null

    private val _form = MutableStateFlow(DealForm())
    val form = _form.asStateFlow()
    private val _loaded = MutableStateFlow<Load<Unit>>(if (id == null) Load.Ready(Unit) else Load.Loading)
    val loaded = _loaded.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    private val _search = MutableStateFlow<Load<List<AdminProduct>>>(Load.Loading)
    val search = _search.asStateFlow()
    private var searchJob: Job? = null

    init {
        if (id != null) load()
    }

    fun load() {
        val dealId = id ?: return
        _loaded.value = Load.Loading
        viewModelScope.launch {
            offers.deals().fold(
                { list ->
                    val d = list.firstOrNull { it.id == dealId }
                    if (d == null) {
                        _loaded.value = Load.Failed("العرض غير موجود")
                    } else {
                        _form.value = d.toForm()
                        _loaded.value = Load.Ready(Unit)
                    }
                },
                { _loaded.value = Load.Failed(it.userMessage()) },
            )
        }
    }

    /** Applies [block]; the name follows the preset until it is typed by hand. */
    fun edit(block: (DealForm) -> DealForm) {
        val f = block(_form.value)
        _form.value = if (f.nameEdited) f else f.copy(name = suggestedName(f))
    }

    fun findProducts(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isNotBlank()) delay(300)
            if (_search.value !is Load.Ready) _search.value = Load.Loading
            _search.value = inventory.products(query = query, pageSize = 50).map { it.items }.toLoad()
        }
    }

    fun toggleProduct(p: AdminProduct) = edit { f ->
        val list = f.products
        f.copy(products = if (list.any { it.id == p.id }) list.filterNot { it.id == p.id } else list + DealPick(p.id, p.nameAr, p.basePrice, p.images.firstOrNull()?.url))
    }

    fun removeProduct(productId: String) = edit { f -> f.copy(products = f.products.filterNot { it.id == productId }) }

    /** Why the form cannot be saved yet, or null. */
    fun problem(f: DealForm): String? {
        val start = parseInstant(f.startsAt) ?: Instant.now()
        val end = parseInstant(f.endsAt)
        return when {
            f.name.trim().length < 2 -> "اكتب اسم العرض"
            f.products.isEmpty() -> "اختر منتجات العرض"
            f.type == BUNDLE && f.products.size < 2 -> "الطقم يحتاج منتجين على الأقل"
            f.type == BUNDLE && (f.bundlePrice.toBigDecimalOrNull()?.signum() ?: 0) <= 0 -> "أدخل سعر الطقم"
            end != null && !end.isAfter(start) -> "تاريخ الانتهاء يجب أن يكون بعد البداية"
            else -> null
        }
    }

    fun save() {
        val f = _form.value
        problem(f)?.let {
            say(it)
            return
        }
        if (_busy.value) return
        _busy.value = true
        val request = DealRequest(
            name = f.name.trim(),
            type = f.type,
            buyQty = if (f.type == BUY_X_GET_Y) f.buyQty else null,
            freeQty = if (f.type == BUY_X_GET_Y) f.freeQty else null,
            bundlePrice = if (f.type == BUNDLE) f.bundlePrice.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() else null,
            startsAt = f.startsAt,
            // "" clears the end date.
            endsAt = f.endsAt ?: "",
            isActive = f.isActive,
            atPos = f.atPos,
            online = f.online,
            productIds = f.products.map { it.id },
            // 0 = no limit.
            maxPieces = f.maxPieces.toIntOrNull() ?: 0,
            maxCustomers = f.maxCustomers.toIntOrNull() ?: 0,
        )
        viewModelScope.launch {
            try {
                val r = if (id == null) offers.createDeal(request) else offers.updateDeal(id, request)
                r.orSay()?.let {
                    say(if (id == null) "تم إنشاء العرض" else "تم حفظ العرض")
                    _saved.value = true
                }
            } finally {
                _busy.value = false
            }
        }
    }
}

private enum class DateField { START, END }

/** New offer ([id] null) or edit one. */
@Composable
fun DealEditScreen(id: String?, onBack: () -> Unit) {
    val vm = adminViewModel(key = "deal-${id ?: "new"}") { DealEditViewModel(id, it.offers, it.inventory) }
    val form by vm.form.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by rememberSaveable { mutableStateOf(false) }
    var dateField by remember { mutableStateOf<DateField?>(null) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(saved) { if (saved) onBack() }
    BackHandler(enabled = picking) { picking = false }

    if (picking) {
        DealProductPicker(vm, form.products, onDone = { picking = false })
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(if (vm.isNew) "عرض جديد" else "تعديل العرض", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(loaded, onRetry = vm::load, modifier = Modifier.padding(padding)) {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Text("نوع العرض", style = MaterialTheme.typography.titleSmall) }
                item {
                    TypeCard(
                        title = "اشترِ واحصل مجاناً",
                        subtitle = "مثل «قطعة وقطعة مجاناً» أو «قطعتين والثالثة مجاناً»",
                        selected = form.type == BUY_X_GET_Y,
                    ) { vm.edit { it.copy(type = BUY_X_GET_Y) } }
                }
                item {
                    TypeCard(
                        title = "طقم بسعر واحد",
                        subtitle = "مثل «حذاء + شنطة بسعر 250»",
                        selected = form.type == BUNDLE,
                    ) { vm.edit { it.copy(type = BUNDLE) } }
                }
                item {
                    if (form.type == BUY_X_GET_Y) BuyGetCard(form, vm) else BundleCard(form, vm)
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = form.name,
                            onValueChange = { v -> vm.edit { it.copy(name = v.take(100), nameEdited = true) } },
                            label = { Text("اسم العرض") },
                            supportingText = { Text("يظهر للعملاء وعلى الفاتورة") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item { ProductsCard(form, vm, onPick = { picking = true }) }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("المدة", style = MaterialTheme.typography.titleSmall)
                        DateRow(
                            label = "يبدأ",
                            value = form.startsAt?.let(::formatDate) ?: "الآن",
                            onClick = { dateField = DateField.START },
                            onClear = if (vm.isNew && form.startsAt != null) ({ vm.edit { it.copy(startsAt = null) } }) else null,
                        )
                        DateRow(
                            label = "ينتهي",
                            value = form.endsAt?.let(::formatDate) ?: "بدون تاريخ انتهاء",
                            onClick = { dateField = DateField.END },
                            onClear = if (form.endsAt != null) ({ vm.edit { it.copy(endsAt = null) } }) else null,
                        )
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("أين يُطبَّق", style = MaterialTheme.typography.titleSmall)
                        SwitchRow("الكاشير", form.atPos) { v -> vm.edit { it.copy(atPos = v) } }
                        SwitchRow("تطبيق العملاء", form.online) { v -> vm.edit { it.copy(online = v) } }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        SwitchRow("فعّال", form.isActive) { v -> vm.edit { it.copy(isActive = v) } }
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("لأول المشترين فقط (اختياري)", style = MaterialTheme.typography.titleSmall)
                        Text("الأسبقية لمن يطلب أولاً؛ يتوقف العرض تلقائياً عند الوصول للعدد. اتركه فارغاً بدون حد.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        com.dejavu.core.ui.theme.OutlinedTextField(
                            value = form.maxPieces,
                            onValueChange = { v -> vm.edit { it.copy(maxPieces = v.filter(Char::isDigit).take(6)) } },
                            label = { Text("أول عدد من القطع") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        com.dejavu.core.ui.theme.OutlinedTextField(
                            value = form.maxCustomers,
                            onValueChange = { v -> vm.edit { it.copy(maxCustomers = v.filter(Char::isDigit).take(6)) } },
                            label = { Text("أول عدد من العملاء") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    val problem = vm.problem(form)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                        Button(onClick = vm::save, enabled = problem == null && !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (vm.isNew) "إنشاء العرض" else "حفظ التعديلات")
                        }
                    }
                }
            }
        }
    }

    dateField?.let { field ->
        val current = parseInstant(if (field == DateField.START) form.startsAt else form.endsAt)
        DatePickDialog(
            initial = current ?: if (field == DateField.END) Instant.now().plus(7, ChronoUnit.DAYS) else Instant.now(),
            onDismiss = { dateField = null },
        ) { date ->
            dateField = null
            if (field == DateField.START) {
                val start = if (date == LocalDate.now(zone)) Instant.now() else date.atStartOfDay(zone).toInstant()
                vm.edit { it.copy(startsAt = isoOf(start)) }
            } else {
                vm.edit { it.copy(endsAt = isoOf(date.atTime(23, 59).atZone(zone).toInstant())) }
            }
        }
    }
}

@Composable
private fun TypeCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = if (selected) 10.dp else 4.dp, contentPadding = 12.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) Lux.GoldDeep else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
        }
    }
}

private val presets = listOf(1 to 1, 2 to 1, 3 to 1, 2 to 2)

@Composable
private fun BuyGetCard(form: DealForm, vm: DealEditViewModel) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("الكميات", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            presets.forEach { (buy, free) ->
                FilterChip(
                    selected = form.buyQty == buy && form.freeQty == free,
                    onClick = { vm.edit { it.copy(buyQty = buy, freeQty = free) } },
                    label = { Text("$buy + $free") },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("يشتري", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            QuantityStepper(form.buyQty, { v -> vm.edit { it.copy(buyQty = v.coerceIn(1, 20)) } }, max = 20)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ويحصل مجاناً على", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            QuantityStepper(form.freeQty, { v -> vm.edit { it.copy(freeQty = v.coerceIn(1, 20)) } }, max = 20)
        }
        Text(
            "عند شراء ${form.buyQty + form.freeQty} قطع من منتجات العرض تكون ${form.freeQty} منها مجاناً. القطعة الأرخص هي المجانية.",
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun BundleCard(form: DealForm, vm: DealEditViewModel) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("سعر الطقم", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = form.bundlePrice,
            onValueChange = { v -> vm.edit { it.copy(bundlePrice = cleanAmount(v)) } },
            label = { Text("سعر الطقم كاملاً (ر.س)") },
            isError = form.bundlePrice.isNotEmpty() && (form.bundlePrice.toBigDecimalOrNull()?.signum() ?: 0) <= 0,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "يجب اختيار منتجين على الأقل، وتُحسب قطعة من كل منتج",
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ProductsCard(form: DealForm, vm: DealEditViewModel, onPick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("منتجات العرض (${form.products.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("اختيار المنتجات") }
        }
        if (form.products.isEmpty()) {
            Text(
                if (form.type == BUNDLE) "اختر منتجات الطقم (منتجين على الأقل)" else "اختر المنتجات التي يشملها العرض",
                style = MaterialTheme.typography.bodySmall,
                color = Lux.InkSoft,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            form.products.forEach { p ->
                FilterChip(
                    selected = true,
                    onClick = { vm.removeProduct(p.id) },
                    label = { Text("${p.name} · ${Money.formatBase(p.price)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "إزالة", modifier = Modifier.size(16.dp)) },
                )
            }
        }
        if (form.type == BUNDLE && form.products.isNotEmpty()) {
            val sum = form.products.fold(BigDecimal.ZERO) { acc, p -> acc + Money.parse(p.price) }
            val price = form.bundlePrice.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("مجموع الأسعار", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    Money.formatBase(sum.toPlainString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lux.InkSoft,
                    textDecoration = if (price != null && price < sum) TextDecoration.LineThrough else TextDecoration.None,
                )
            }
            if (price != null) {
                SummaryRow("سعر الطقم", Money.formatBase(price.toPlainString()), strong = true, valueColor = Lux.GoldDeep)
                val saving = sum - price
                if (saving.signum() > 0) {
                    SummaryRow("توفير العميل", Money.formatBase(saving.toPlainString()), valueColor = Success)
                } else {
                    Text("سعر الطقم ليس أقل من مجموع الأسعار", style = MaterialTheme.typography.bodySmall, color = Warning)
                }
            }
        }
    }
}

@Composable
private fun DateRow(label: String, value: String, onClick: () -> Unit, onClear: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.DateRange, contentDescription = null, tint = Lux.GoldDeep, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        if (onClear != null) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "مسح") }
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A calendar day (the picker works in UTC midnights). */
@Composable
private fun DatePickDialog(initial: Instant, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val local = initial.atZone(zone).toLocalDate()
    val state = rememberDatePickerState(initialSelectedDateMillis = local.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("تم") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    ) { DatePicker(state = state) }
}

/** Full-screen multi-select of products: search, checkboxes, current price. */
@Composable
private fun DealProductPicker(vm: DealEditViewModel, picked: List<DealPick>, onDone: () -> Unit) {
    val state by vm.search.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { vm.findProducts(query) }
    val pickedIds = picked.map { it.id }.toSet()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("اختيار المنتجات (${picked.size})", onDone) {
                TextButton(onClick = onDone) { Text("تم") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                placeholder = { Text("ابحث بالاسم أو الرقم") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LoadContent(state, onRetry = { vm.findProducts(query) }) { list ->
                if (list.isEmpty()) {
                    EmptyList(Icons.Filled.Search, "لا توجد نتائج")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(list, key = { it.id }) { p ->
                            val checked = p.id in pickedIds
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.toggleProduct(p) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { vm.toggleProduct(p) })
                                ProductImage(p.images.firstOrNull()?.url, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), p.nameAr)
                                Column(Modifier.weight(1f)) {
                                    Text(p.nameAr, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(p.skuNumber?.let { "#$it" }, p.category.nameAr).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Lux.InkSoft,
                                    )
                                }
                                Text(Money.formatBase(p.basePrice), style = MaterialTheme.typography.titleSmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
