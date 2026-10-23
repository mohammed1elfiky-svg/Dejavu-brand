@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.barcode.rememberBarcodeScanner
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.CreateStockCountRequest
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.PosProduct
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.PosVariant
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.StockCountDetail
import com.dejavu.core.admin.StockCountSummary
import com.dejavu.core.admin.Warehouse
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** Creating and deleting a count (the server's permission; not in [Perm]). */
private const val STOCK_COUNT = "stock.count"

/** One counted variant in a new count. */
data class CountLine(
    val variantId: String,
    val name: String,
    val option: String,
    val sku: String,
    val imageUrl: String?,
    val qty: Int,
)

class StockCountsViewModel(private val repo: InventoryRepository, private val pos: PosRepository) : BaseViewModel() {
    private val _list = MutableStateFlow<Load<List<StockCountSummary>>>(Load.Loading)
    val list = _list.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private var page = 1

    private val _warehouses = MutableStateFlow<List<Warehouse>>(emptyList())
    val warehouses = _warehouses.asStateFlow()

    private val _detail = MutableStateFlow<Load<StockCountDetail>>(Load.Loading)
    val detail = _detail.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    // ---- the count being taken
    private val _lines = MutableStateFlow<List<CountLine>>(emptyList())
    val lines = _lines.asStateFlow()
    private val _warehouseId = MutableStateFlow<String?>(null)
    val warehouseId = _warehouseId.asStateFlow()
    private val _found = MutableStateFlow<Load<List<PosProduct>>?>(null)

    /** Manual search results (null = nothing searched yet). */
    val found = _found.asStateFlow()
    private var searchJob: Job? = null

    fun load() {
        if (_list.value !is Load.Ready) _list.value = Load.Loading
        viewModelScope.launch { fetch(reset = true) }
        if (_warehouses.value.isEmpty()) {
            viewModelScope.launch {
                repo.warehouses().getOrNull()?.let { all ->
                    val active = all.filter { it.isActive }
                    _warehouses.value = active
                    if (_warehouseId.value == null) _warehouseId.value = (active.firstOrNull { it.isDefault } ?: active.firstOrNull())?.id
                }
            }
        }
    }

    fun more() {
        viewModelScope.launch { fetch(reset = false) }
    }

    private suspend fun fetch(reset: Boolean) {
        val next = if (reset) 1 else page + 1
        repo.stockCounts(next).fold(
            { p ->
                page = next
                val before = if (reset) emptyList() else (_list.value as? Load.Ready)?.value.orEmpty()
                _list.value = Load.Ready(before + p.items)
                _hasMore.value = p.hasMore
            },
            { e -> if (reset) _list.value = Load.Failed(e.userMessage()) else say(e.userMessage()) },
        )
    }

    fun open(id: String) {
        _detail.value = Load.Loading
        viewModelScope.launch { _detail.value = repo.stockCount(id).toLoad() }
    }

    fun chooseWarehouse(id: String) {
        _warehouseId.value = id
    }

    /**
     * A scanned / typed barcode or SKU: +1 to that variant ([added] then runs); [pick] asks
     * which option when the code is a whole product's.
     */
    fun scan(code: String, pick: (PosProduct) -> Unit, added: () -> Unit = {}) {
        val c = code.trim()
        if (c.isEmpty()) return
        viewModelScope.launch {
            pos.lookup(c).orSay()?.let { found ->
                val variant = found.variantId?.let { id -> found.product.variants.firstOrNull { it.id == id } }
                    ?: found.product.variants.singleOrNull()
                when {
                    variant != null -> {
                        add(found.product, variant, 1)
                        added()
                    }
                    found.product.variants.isEmpty() -> say("لا توجد مقاسات لهذا المنتج")
                    else -> pick(found.product)
                }
            }
        }
    }

    /** Adds [by] pieces of [variant] to the count. */
    fun add(product: PosProduct, variant: PosVariant, by: Int) {
        val have = _lines.value.firstOrNull { it.variantId == variant.id }
        _lines.value = if (have == null) {
            listOf(
                CountLine(
                    variantId = variant.id,
                    name = product.nameAr,
                    option = listOfNotNull(variant.color, variant.size).joinToString(" · "),
                    sku = variant.sku,
                    imageUrl = variant.imageUrl ?: product.imageUrl,
                    qty = by.coerceAtLeast(0),
                ),
            ) + _lines.value
        } else {
            // The last scanned line moves to the top.
            listOf(have.copy(qty = (have.qty + by).coerceAtLeast(0))) + _lines.value.filterNot { it.variantId == variant.id }
        }
        say("${product.nameAr}${variant.title.let { " ($it)" }}: ${(have?.qty ?: 0) + by}")
    }

    fun setQty(variantId: String, qty: Int) {
        _lines.value = _lines.value.map { if (it.variantId == variantId) it.copy(qty = qty.coerceIn(0, 99_999)) else it }
    }

    fun remove(variantId: String) {
        _lines.value = _lines.value.filterNot { it.variantId == variantId }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _found.value = null
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _found.value = Load.Loading
            _found.value = pos.products(query).toLoad()
        }
    }

    fun discardDraft() {
        _lines.value = emptyList()
        _found.value = null
    }

    fun save(note: String, done: (String) -> Unit) {
        val wh = _warehouseId.value ?: return say("اختر المستودع")
        val items = _lines.value
        if (items.isEmpty()) return say("لم يُجرد أي صنف بعد")
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.createStockCount(
                CreateStockCountRequest(wh, items.map { CreateStockCountRequest.Line(it.variantId, it.qty) }, note.trim().ifBlank { null }),
            ).orSay()?.let { d ->
                say("تم حفظ الجرد ${d.number}")
                _lines.value = emptyList()
                _found.value = null
                _detail.value = Load.Ready(d)
                fetch(reset = true)
                done(d.id)
            }
            _busy.value = false
        }
    }

    /** Books the differences (surplus in, shortage out) at average cost. */
    fun post(id: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.postStockCount(id).orSay()?.let { d ->
                say("تم ترحيل فروقات الجرد ${d.number}")
                _detail.value = Load.Ready(d)
                fetch(reset = true)
            }
            _busy.value = false
        }
    }

    fun delete(id: String, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.deleteStockCount(id).orSay()?.let {
                say("تم حذف الجرد")
                fetch(reset = true)
                done()
            }
            _busy.value = false
        }
    }
}

/** Stock counts (جرد): the list, a new count by scanning, and a saved count with its differences. */
@Composable
fun StockCountsScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel { StockCountsViewModel(it.inventory, it.pos) }
    val snackbar = remember { SnackbarHostState() }
    var creating by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    BackHandler(enabled = creating || openId != null) {
        if (openId != null) {
            openId = null
        } else {
            creating = false
        }
    }

    val id = openId
    when {
        id != null -> CountDetail(vm, id, session, snackbar, onBack = { openId = null })
        creating -> NewCount(vm, snackbar, onBack = { creating = false }) { saved ->
            creating = false
            openId = saved
        }
        else -> CountList(vm, session, snackbar, onBack, onNew = { creating = true }) { openId = it }
    }
}

@Composable
private fun CountList(
    vm: StockCountsViewModel,
    session: StaffSession.LoggedIn,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val state by vm.list.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("الجرد", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (session.can(STOCK_COUNT)) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("جرد جديد") },
                    containerColor = Lux.GoldDeep,
                    contentColor = Color.White,
                )
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { counts ->
            if (counts.isEmpty()) {
                EmptyState(
                    Icons.AutoMirrored.Filled.List,
                    "لا يوجد جرد بعد",
                    "اضغط «جرد جديد» وامسح باركود القطع الموجودة على الرف",
                    modifier = Modifier.padding(padding),
                )
                return@LoadContent
            }
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(counts, key = { it.id }) { c ->
                    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 14.dp, onClick = { onOpen(c.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(c.number, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(formatDate(c.countedAt), c.warehouse?.name, c.counts?.let { "${it.items} صنف" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Lux.InkSoft,
                                )
                                c.countedBy?.name?.let { Text("بواسطة $it", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                            }
                            if (c.isPosted) StatusChip("مُرحَّل", Success) else StatusChip("غير مُرحَّل", MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
                if (hasMore) {
                    item { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text("عرض المزيد") } }
                }
            }
        }
    }
}

@Composable
private fun NewCount(vm: StockCountsViewModel, snackbar: SnackbarHostState, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val lines by vm.lines.collectAsStateWithLifecycle()
    val warehouses by vm.warehouses.collectAsStateWithLifecycle()
    val warehouseId by vm.warehouseId.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var continuous by rememberSaveable { mutableStateOf(true) }
    var picking by remember { mutableStateOf<PosProduct?>(null) }
    // Whether the option being picked came from the camera (so scanning resumes after it).
    var fromCamera by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Bumped after each scan to open the camera again (continuous scanning).
    var rescan by remember { mutableIntStateOf(0) }
    val scan = rememberBarcodeScanner { c ->
        vm.scan(
            c,
            pick = {
                fromCamera = true
                picking = it
            },
        ) { if (continuous) rescan++ }
    }
    LaunchedEffect(rescan) {
        if (rescan > 0) {
            delay(500)
            if (picking == null) scan()
        }
    }
    fun leave() {
        if (lines.isEmpty()) {
            onBack()
        } else {
            confirmDiscard = true
        }
    }
    BackHandler { leave() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("جرد جديد", { leave() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                InfoCard {
                    Text("المستودع", style = MaterialTheme.typography.titleSmall)
                    if (warehouses.isEmpty()) Text("جاري تحميل المستودعات…", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        warehouses.forEach { w ->
                            FilterChip(
                                selected = w.id == warehouseId,
                                onClick = { vm.chooseWarehouse(w.id) },
                                enabled = lines.isEmpty(),
                                label = { Text(w.name) },
                            )
                        }
                    }
                    if (lines.isNotEmpty()) Text("لا يمكن تغيير المستودع بعد بدء الجرد", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
            }
            item {
                InfoCard {
                    Button(onClick = scan, enabled = warehouseId != null, modifier = Modifier.fillMaxWidth()) { Text("مسح الباركود بالكاميرا") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("مسح متواصل (تفتح الكاميرا بعد كل قطعة)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = continuous, onCheckedChange = { continuous = it })
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.take(64) },
                        label = { Text("باركود أو SKU") },
                        singleLine = true,
                        enabled = warehouseId != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            vm.scan(code, pick = {
                                fromCamera = false
                                picking = it
                            })
                            code = ""
                        }),
                        trailingIcon = {
                            TextButton(onClick = {
                                vm.scan(code, pick = {
                                    fromCamera = false
                                    picking = it
                                })
                                code = ""
                            }, enabled = code.isNotBlank()) { Text("+1") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { searching = true }, enabled = warehouseId != null, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text("بحث وإضافة يدوياً", Modifier.padding(start = 6.dp))
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الأصناف المجرودة", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${lines.size} صنف · ${lines.sumOf { it.qty }} قطعة", style = MaterialTheme.typography.labelMedium, color = Lux.GoldDeep)
                }
            }
            if (lines.isEmpty()) item { Text("امسح أول قطعة لبدء الجرد", color = Lux.InkSoft) }
            items(lines, key = { it.variantId }) { l -> CountLineRow(l, onQty = { vm.setQty(l.variantId, it) }, onRemove = { vm.remove(l.variantId) }) }
            if (lines.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(255) },
                        label = { Text("ملاحظة (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        "الأصناف غير الممسوحة لا تدخل في الجرد. بعد الحفظ تظهر الفروقات مقابل المسجّل، ولا يتغير المخزون حتى «ترحيل الفروقات».",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                    )
                }
                item {
                    Button(onClick = { vm.save(note, onSaved) }, enabled = !busy && warehouseId != null, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White) else Text("حفظ")
                    }
                }
            }
        }
    }

    picking?.let { p ->
        CountVariantDialog(p, onDismiss = { picking = null }) { v, qty ->
            vm.add(p, v, qty)
            picking = null
            if (continuous && fromCamera) rescan++
        }
    }
    if (searching) {
        CountSearchDialog(vm, onDismiss = { searching = false }) { p ->
            searching = false
            fromCamera = false
            picking = p
        }
    }
    if (confirmDiscard) {
        ConfirmDialog("الخروج من الجرد", "سيُلغى ما جردته (${lines.size} صنف) ولن يُحفظ.", "خروج", onDismiss = { confirmDiscard = false }) {
            vm.discardDraft()
            onBack()
        }
    }
}

@Composable
private fun CountLineRow(l: CountLine, onQty: (Int) -> Unit, onRemove: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProductImage(l.imageUrl, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), l.name)
            Column(Modifier.weight(1f)) {
                Text(l.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(listOf(l.option, l.sku).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
            OutlinedTextField(
                value = "${l.qty}",
                onValueChange = { v -> onQty(v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(76.dp),
            )
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "حذف") }
        }
    }
}

/** Which option (size / colour) of [product] was counted, and how many pieces. */
@Composable
private fun CountVariantDialog(product: PosProduct, onDismiss: () -> Unit, onAdd: (PosVariant, Int) -> Unit) {
    var chosen by remember { mutableStateOf(product.variants.singleOrNull()) }
    var qty by rememberSaveable { mutableStateOf("1") }
    val n = qty.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(product.nameAr) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                product.variants.forEach { v ->
                    FilterChip(
                        selected = chosen?.id == v.id,
                        onClick = { chosen = v },
                        label = { Text("${v.title} · ${v.sku}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = qty,
                    onValueChange = { qty = it.filter(Char::isDigit).take(5) },
                    label = { Text("عدد القطع") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let { v -> n?.let { onAdd(v, it) } } }, enabled = chosen != null && n != null) { Text("إضافة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun CountSearchDialog(vm: StockCountsViewModel, onDismiss: () -> Unit, onPick: (PosProduct) -> Unit) {
    val found by vm.found.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("بحث عن صنف") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it.take(64)
                        vm.search(query)
                    },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("الاسم أو SKU") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (val f = found) {
                        null -> Text("اكتب اسم الصنف للبحث", color = Lux.InkSoft, style = MaterialTheme.typography.bodySmall)
                        Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        is Load.Failed -> Text(f.message, color = MaterialTheme.colorScheme.error)
                        is Load.Ready -> {
                            if (f.value.isEmpty()) Text("لا توجد نتائج", color = Lux.InkSoft)
                            f.value.forEach { p ->
                                Card(
                                    onClick = { onPick(p) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ProductImage(p.imageUrl, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), p.nameAr)
                                        Column(Modifier.weight(1f)) {
                                            Text(p.nameAr, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                            Text("${p.variants.size} خيار", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                        }
                                    }
                                }
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
private fun CountDetail(vm: StockCountsViewModel, id: String, session: StaffSession.LoggedIn, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val state by vm.detail.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmPost by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if ((state as? Load.Ready)?.value?.id != id) vm.open(id)
    }
    val canPost = session.can(STOCK_COUNT) && session.can(Perm.STOCK_ADJUST)
    val canDelete = session.can(STOCK_COUNT)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar((state as? Load.Ready)?.value?.number ?: "الجرد", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = { vm.open(id) }, modifier = Modifier.padding(padding)) { d ->
            if (d.id != id) return@LoadContent
            val surplus = d.items.filter { it.difference > 0 }
            val shortage = d.items.filter { it.difference < 0 }
            val surplusValue = surplus.fold(BigDecimal.ZERO) { acc, i -> acc + Money.parse(i.differenceValue).abs() }
            val shortageValue = shortage.fold(BigDecimal.ZERO) { acc, i -> acc + Money.parse(i.differenceValue).abs() }
            val net = surplusValue - shortageValue
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.number, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (d.isPosted) StatusChip("مُرحَّل", Success) else StatusChip("غير مُرحَّل", MaterialTheme.colorScheme.tertiary)
                        }
                        Text(
                            listOfNotNull(formatDateTime(d.countedAt), d.warehouse?.name, d.countedBy?.name).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                        )
                        d.postedAt?.let { Text("رُحِّل ${formatDateTime(it)}", style = MaterialTheme.typography.bodySmall, color = Success) }
                        d.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Lux.Hairline)
                        SummaryRow("الأصناف المجرودة", "${d.items.size}")
                        SummaryRow("مطابقة", "${d.items.count { it.difference == 0 }}")
                        SummaryRow("زيادة (${surplus.sumOf { it.difference }} قطعة)", Money.formatBase(surplusValue.toPlainString()), valueColor = Success)
                        SummaryRow(
                            "عجز (${shortage.sumOf { -it.difference }} قطعة)",
                            Money.formatBase(shortageValue.toPlainString()),
                            valueColor = MaterialTheme.colorScheme.error,
                        )
                        SummaryRow(
                            "صافي الفرق",
                            (if (net.signum() < 0) "−" else "") + Money.formatBase(net.abs().toPlainString()),
                            strong = true,
                            valueColor = if (net.signum() < 0) MaterialTheme.colorScheme.error else Success,
                        )
                    }
                }
                if (!d.isPosted) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canPost) {
                                Button(onClick = { confirmPost = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("ترحيل الفروقات") }
                            }
                            if (canDelete) {
                                OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    Text("حذف", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if (!canPost) Text("ترحيل الفروقات يحتاج صلاحية تعديل المخزون", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    }
                }
                // Differences first (largest first), matching lines after.
                val ordered = d.items.sortedWith(compareByDescending<StockCountDetail.Item> { kotlin.math.abs(it.difference) }.thenBy { it.variant.product.nameAr })
                items(ordered, key = { it.id }) { i -> CountResultRow(i) }
            }
        }
    }

    if (confirmPost) {
        ConfirmDialog(
            "ترحيل الفروقات",
            "سيُعدَّل المخزون ليطابق الجرد: تُضاف الزيادة ويُخصم العجز وتُسجَّل قيمتهما في الحسابات. لا يمكن التراجع بعد الترحيل.",
            "ترحيل",
            onDismiss = { confirmPost = false },
        ) { vm.post(id) }
    }
    if (confirmDelete) {
        ConfirmDialog("حذف الجرد", "سيُحذف هذا الجرد ولن يتغير المخزون.", "حذف", onDismiss = { confirmDelete = false }) {
            vm.delete(id, onBack)
        }
    }
}

@Composable
private fun CountResultRow(i: StockCountDetail.Item) {
    val color = when {
        i.difference > 0 -> Success
        i.difference < 0 -> MaterialTheme.colorScheme.error
        else -> Lux.InkSoft
    }
    GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(i.variant.product.nameAr, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    listOfNotNull(i.variant.color, i.variant.size, i.variant.sku).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            }
            if (i.difference != 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        (if (i.difference > 0) "+" else "−") + "${kotlin.math.abs(i.difference)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = color,
                    )
                    Text(Money.formatBase(Money.parse(i.differenceValue).abs().toPlainString()), style = MaterialTheme.typography.bodySmall, color = color)
                }
            } else {
                GoldPill { Text("مطابق", color = Color.White, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("المسجّل ${i.systemQty}", style = MaterialTheme.typography.bodySmall)
            Text("المجرود ${i.countedQty}", style = MaterialTheme.typography.bodySmall)
            Text(
                when {
                    i.difference > 0 -> "زيادة"
                    i.difference < 0 -> "عجز"
                    else -> "لا فرق"
                },
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}
