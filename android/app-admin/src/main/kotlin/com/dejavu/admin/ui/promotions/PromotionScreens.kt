@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.promotions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminProduct
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.Promotion
import com.dejavu.core.admin.PromotionRepository
import com.dejavu.core.admin.PromotionRequest
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.SectionTitle
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// ------------------------------------------------------------------ helpers ---

private val zone: ZoneId = ZoneId.systemDefault()
private val shownFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy، h:mm a", Locale.forLanguageTag("ar")).withZone(zone)

private fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** "2026-10-22T09:00:00Z" → "22 أكتوبر 2026، 9:00 ص" in the phone's time zone. */
private fun shown(iso: String?): String = parseInstant(iso)?.let { shownFormat.format(it) } ?: ""

private fun iso(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

/** Valid discount: more than 0 and less than 100, up to 2 decimals. */
private fun percentOf(v: String): BigDecimal? =
    v.trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it < BigDecimal(100) && it.scale() <= 2 }

/** Price after [percent] off, rounded to the halala. */
private fun discounted(price: String, percent: BigDecimal?): String? {
    if (percent == null) return null
    val p = Money.parse(price)
    return p.multiply(BigDecimal(100).subtract(percent)).divide(BigDecimal(100), 2, RoundingMode.HALF_UP).toPlainString()
}

private fun BigDecimal.plainPercent(): String = stripTrailingZeros().toPlainString()

private enum class PromoState(val label: String) { RUNNING("يعمل الآن"), SCHEDULED("مجدول"), ENDED("منتهي"), PAUSED("متوقف") }

private fun Promotion.state(now: Instant = Instant.now()): PromoState = when {
    !isActive -> PromoState.PAUSED
    running -> PromoState.RUNNING
    parseInstant(endsAt)?.let { !it.isAfter(now) } == true -> PromoState.ENDED
    else -> PromoState.SCHEDULED
}

@Composable
private fun PromoState.tint(): Color = when (this) {
    PromoState.RUNNING -> Success
    PromoState.SCHEDULED -> Lux.GoldDeep
    PromoState.ENDED -> MaterialTheme.colorScheme.onSurfaceVariant
    PromoState.PAUSED -> Warning
}

private fun Promotion.toRequest(isActive: Boolean = this.isActive) = PromotionRequest(
    name = name,
    percent = percent,
    productIds = products.map { it.id },
    startsAt = startsAt,
    endsAt = endsAt,
    isActive = isActive,
)

// --------------------------------------------------------------------- list ---

class PromotionsViewModel(private val repo: PromotionRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<Promotion>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.list().toLoad() }
    }

    fun setActive(p: Promotion, active: Boolean) = act {
        repo.update(p.id, p.toRequest(isActive = active)).orSay()?.let { updated ->
            replace(updated)
            say(if (active) "تم تفعيل العرض" else "تم إيقاف العرض")
        }
    }

    fun delete(p: Promotion) = act {
        repo.delete(p.id).orSay()?.let {
            (_state.value as? Load.Ready)?.let { s -> _state.value = Load.Ready(s.value.filter { it.id != p.id }) }
            say("تم حذف العرض")
        }
    }

    private fun replace(p: Promotion) {
        (_state.value as? Load.Ready)?.let { s -> _state.value = Load.Ready(s.value.map { if (it.id == p.id) p else it }) }
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

/** Percent-off promotions on chosen products. [open] gets "new" or a promotion id. */
@Composable
fun PromotionsScreen(session: StaffSession.LoggedIn, onBack: () -> Unit, open: (String) -> Unit) {
    val vm = adminViewModel { PromotionsViewModel(it.promotions) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val canManage = session.can(Perm.PROMOTIONS_MANAGE)
    var deleting by remember { mutableStateOf<Promotion?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("العروض والخصومات", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canManage) {
                ExtendedFloatingActionButton(
                    onClick = { open("new") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("عرض جديد") },
                    containerColor = Lux.GoldDeep,
                    contentColor = Color.White,
                )
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            if (list.isEmpty()) {
                EmptyState(
                    Icons.Filled.Favorite,
                    "لا توجد عروض بعد",
                    "أنشئ عرضاً بنسبة خصم على منتجات مختارة، يظهر للعملاء وفي الكاشير تلقائياً.",
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    Modifier.padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    items(list, key = { it.id }) { p ->
                        PromotionCard(
                            p,
                            canManage = canManage,
                            busy = busy,
                            onOpen = { open(p.id) },
                            onToggle = { vm.setActive(p, it) },
                            onDelete = { deleting = p },
                        )
                    }
                }
            }
        }
    }

    deleting?.let { p ->
        ConfirmDialog(
            "حذف العرض",
            "سيُحذف عرض \"${p.name}\" وتعود أسعار منتجاته (${p.products.size}) إلى طبيعتها.",
            "حذف",
            onDismiss = { deleting = null },
        ) { vm.delete(p) }
    }
}

@Composable
private fun PromotionCard(
    p: Promotion,
    canManage: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val st = p.state()
    GlassCard(Modifier.fillMaxWidth(), onClick = if (canManage) onOpen else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GoldPill { Text("خصم ${Money.parse(p.percent).plainPercent()}٪", color = Color.White, style = MaterialTheme.typography.titleMedium) }
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.products.size} منتج", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
            StatusChip(st.label, st.tint())
        }
        Text(
            "من ${shown(p.startsAt)}" + (p.endsAt?.let { " · حتى ${shown(it)}" } ?: " · بدون تاريخ انتهاء"),
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (p.products.isNotEmpty()) {
            Text(
                p.products.take(4).joinToString("، ") { it.nameAr } + if (p.products.size > 4) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canManage) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("مفعّل", Modifier.weight(1f))
                Switch(checked = p.isActive, onCheckedChange = onToggle, enabled = !busy)
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// --------------------------------------------------------------------- edit ---

/** A product chosen for the promotion. */
data class PickedProduct(val id: String, val nameAr: String, val basePrice: String, val imageUrl: String?, val skuNumber: Int?)

data class PromotionForm(
    val name: String = "",
    val percent: String = "",
    /** ISO instant; null = now (new promotion). */
    val startsAt: String? = null,
    val endsAt: String? = null,
    val isActive: Boolean = true,
    val products: List<PickedProduct> = emptyList(),
    /** End date the saved promotion has; the API cannot clear it once set. */
    val savedEndsAt: String? = null,
)

private fun AdminProduct.picked() = PickedProduct(id, nameAr, basePrice, images.firstOrNull()?.url, skuNumber)

class PromotionEditViewModel(
    private val id: String?,
    private val repo: PromotionRepository,
    private val inventory: InventoryRepository,
) : BaseViewModel() {
    private val _ready = MutableStateFlow<Load<Unit>>(if (id == null) Load.Ready(Unit) else Load.Loading)
    val ready = _ready.asStateFlow()
    private val _form = MutableStateFlow(PromotionForm())
    val form = _form.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

    private val _results = MutableStateFlow<List<AdminProduct>>(emptyList())
    val results = _results.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private var query = ""
    private var page = 1
    val isNew = id == null

    fun load() {
        if (_results.value.isEmpty()) search("")
        if (id == null || _ready.value is Load.Ready) return
        _ready.value = Load.Loading
        viewModelScope.launch {
            _ready.value = repo.list().mapCatching { list ->
                val p = list.firstOrNull { it.id == id } ?: error("العرض غير موجود")
                _form.value = PromotionForm(
                    name = p.name,
                    percent = Money.parse(p.percent).plainPercent(),
                    startsAt = p.startsAt,
                    endsAt = p.endsAt,
                    savedEndsAt = p.endsAt,
                    isActive = p.isActive,
                    products = p.products.map { PickedProduct(it.id, it.nameAr, it.basePrice, it.images.firstOrNull()?.url, it.skuNumber) },
                )
            }.toLoad()
        }
    }

    fun edit(change: (PromotionForm) -> PromotionForm) {
        _form.value = change(_form.value)
    }

    fun toggle(p: PickedProduct) = edit { f ->
        f.copy(products = if (f.products.any { it.id == p.id }) f.products.filter { it.id != p.id } else f.products + p)
    }

    fun search(q: String) {
        query = q
        page = 1
        fetch(reset = true)
    }

    fun more() {
        if (_searching.value || !_hasMore.value) return
        page += 1
        fetch(reset = false)
    }

    private fun fetch(reset: Boolean) {
        _searching.value = true
        viewModelScope.launch {
            inventory.products(query, page).orSay()?.let { res ->
                _results.value = if (reset) res.items else _results.value + res.items.filter { n -> _results.value.none { it.id == n.id } }
                _hasMore.value = res.hasMore
            }
            _searching.value = false
        }
    }

    fun save() {
        val f = _form.value
        val problem = when {
            f.name.trim().length < 2 -> "اكتب اسم العرض"
            percentOf(f.percent) == null -> "نسبة الخصم بين 0 و 100 (مثل 20)"
            f.products.isEmpty() -> "اختر منتجاً واحداً على الأقل"
            f.endsAt != null && parseInstant(f.endsAt)?.let { end -> !end.isAfter(parseInstant(f.startsAt) ?: Instant.now()) } == true ->
                "تاريخ الانتهاء يجب أن يكون بعد البداية"
            else -> null
        }
        if (problem != null) return say(problem)
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val request = PromotionRequest(
                name = f.name.trim(),
                percent = percentOf(f.percent)!!.toPlainString(),
                productIds = f.products.map { it.id },
                startsAt = f.startsAt,
                endsAt = f.endsAt,
                isActive = f.isActive,
            )
            val result = if (id == null) repo.create(request) else repo.update(id, request)
            result.orSay()?.let {
                say("تم حفظ العرض")
                _saved.tryEmit(Unit)
            }
            _busy.value = false
        }
    }
}

/** Create ([id] null) or edit a promotion. */
@Composable
fun PromotionEditScreen(id: String?, onBack: () -> Unit) {
    val vm = adminViewModel(key = "promotion:${id ?: "new"}") { PromotionEditViewModel(id, it.promotions, it.inventory) }
    val ready by vm.ready.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf<DateField?>(null) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm) { vm.saved.collect { onBack() } }
    val percent = percentOf(form.percent)
    val selectedIds = form.products.map { it.id }.toSet()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(if (vm.isNew) "عرض جديد" else "تعديل العرض", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = vm::save,
                    enabled = ready is Load.Ready && !busy,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("حفظ العرض (${form.products.size} منتج)")
                }
            }
        },
    ) { padding ->
        LoadContent(ready, onRetry = vm::load, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = form.name,
                                onValueChange = { v -> vm.edit { it.copy(name = v.take(100)) } },
                                label = { Text("اسم العرض") },
                                placeholder = { Text("مثال: تخفيضات نهاية الموسم") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = form.percent,
                                onValueChange = { v -> vm.edit { it.copy(percent = v.filter { c -> c.isDigit() || c == '.' }.take(6)) } },
                                label = { Text("نسبة الخصم ٪") },
                                isError = form.percent.isNotBlank() && percent == null,
                                supportingText = { Text(if (form.percent.isNotBlank() && percent == null) "بين 0 و 100" else "مثال: 20 تعني خصم 20٪") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val canClearStart = vm.isNew && form.startsAt != null
                            val canClearEnd = form.endsAt != null && form.savedEndsAt == null
                            DateRow(
                                "البداية",
                                form.startsAt?.let(::shown) ?: "الآن",
                                onPick = { picking = DateField.START },
                                onClear = if (canClearStart) ({ vm.edit { it.copy(startsAt = null) } }) else null,
                            )
                            DateRow(
                                "النهاية",
                                form.endsAt?.let(::shown) ?: "بدون تاريخ انتهاء",
                                onPick = { picking = DateField.END },
                                onClear = if (canClearEnd) ({ vm.edit { it.copy(endsAt = null) } }) else null,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("العرض مفعّل", Modifier.weight(1f))
                                Switch(checked = form.isActive, onCheckedChange = { v -> vm.edit { it.copy(isActive = v) } })
                            }
                        }
                    }
                }
                item { SectionTitle("المنتجات المختارة (${form.products.size})") }
                item {
                    if (form.products.isEmpty()) {
                        Text("ابحث عن المنتجات أدناه واخترها.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    } else {
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            form.products.forEach { p ->
                                InputChip(
                                    selected = true,
                                    onClick = { vm.toggle(p) },
                                    label = { Text(p.nameAr, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "إزالة", modifier = Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                }
                item { SectionTitle("إضافة منتجات") }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("ابحث بالاسم أو SKU") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                if (searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                items(results, key = { "r:" + it.id }) { p ->
                    val picked = p.picked()
                    ProductPickRow(picked, selected = p.id in selectedIds, percent = percent) { vm.toggle(picked) }
                }
                if (hasMore) {
                    item {
                        OutlinedButton(onClick = vm::more, enabled = !searching, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("عرض المزيد") }
                    }
                }
            }
        }
    }

    picking?.let { field ->
        val current = parseInstant(if (field == DateField.START) form.startsAt else form.endsAt)
        DateTimePickerDialog(
            initial = current ?: if (field == DateField.END) Instant.now().plus(7, ChronoUnit.DAYS) else Instant.now(),
            endOfDay = field == DateField.END && current == null,
            onDismiss = { picking = null },
        ) { instant ->
            picking = null
            vm.edit { if (field == DateField.START) it.copy(startsAt = iso(instant)) else it.copy(endsAt = iso(instant)) }
        }
    }
}

private enum class DateField { START, END }

@Composable
private fun DateRow(label: String, value: String, onPick: () -> Unit, onClear: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        if (onClear != null) TextButton(onClick = onClear) { Text("مسح") }
        OutlinedButton(onClick = onPick) { Text("تغيير") }
    }
}

@Composable
private fun ProductPickRow(p: PickedProduct, selected: Boolean, percent: BigDecimal?, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        ProductImage(p.imageUrl, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(p.nameAr, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            p.skuNumber?.let { Text("SKU $it", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) }
        }
        Column(horizontalAlignment = Alignment.End) {
            val after = discounted(p.basePrice, percent)
            if (after != null) {
                Text(Money.formatBase(after), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                Text(
                    Money.formatBase(p.basePrice),
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = TextDecoration.LineThrough,
                    color = Lux.InkSoft,
                )
            } else {
                Text(Money.formatBase(p.basePrice), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Date, then time, in the phone's time zone. [endOfDay] starts the time at 23:59 (a fresh end date). */
@Composable
private fun DateTimePickerDialog(initial: Instant, endOfDay: Boolean, onDismiss: () -> Unit, onPick: (Instant) -> Unit) {
    val local = initial.atZone(zone)
    // The date picker works in UTC midnights.
    val dateState = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val timeState = rememberTimePickerState(
        initialHour = if (endOfDay) 23 else local.hour,
        initialMinute = if (endOfDay) 59 else local.minute,
        is24Hour = false,
    )
    var step by remember { mutableStateOf(0) }
    if (step == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { step = 1 }, enabled = dateState.selectedDateMillis != null) { Text("التالي") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
        ) { DatePicker(state = dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("الوقت") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateState.selectedDateMillis ?: return@TextButton
                    val date: LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(date.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant())
                }) { Text("تم") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("رجوع") } },
        )
    }
}
