@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.pricing

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.barcode.StickerData
import com.dejavu.admin.ui.products.StickerDialog
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminBrand
import com.dejavu.core.admin.AdminCategory
import com.dejavu.core.admin.AdminProduct
import com.dejavu.core.admin.BulkPriceRequest
import com.dejavu.core.admin.BulkPriceResult
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.ManagementRepository
import com.dejavu.core.admin.OfferProduct
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

// ------------------------------------------------------------ bulk prices ---

/** A product picked by hand for a bulk price change. */
data class PickedProduct(val id: String, val name: String, val price: String)

private enum class PickTarget { PRODUCTS, CATEGORIES, BRANDS }

private enum class PriceMode(val key: String, val label: String) {
    PERCENT("PERCENT", "نسبة %"),
    AMOUNT("AMOUNT", "مبلغ"),
    SET("SET", "سعر جديد"),
}

class BulkPriceViewModel(
    private val repo: ManagementRepository,
    private val inventory: InventoryRepository,
    private val finance: FinanceRepository,
) : BaseViewModel() {
    private val _products = MutableStateFlow<List<PickedProduct>>(emptyList())
    val products = _products.asStateFlow()
    private val _categoryIds = MutableStateFlow<Set<String>>(emptySet())
    val categoryIds = _categoryIds.asStateFlow()
    private val _brandIds = MutableStateFlow<Set<String>>(emptySet())
    val brandIds = _brandIds.asStateFlow()

    private val _categories = MutableStateFlow<Load<List<Pair<AdminCategory, Int>>>>(Load.Loading)
    val categories = _categories.asStateFlow()
    private val _brands = MutableStateFlow<Load<List<AdminBrand>>>(Load.Loading)
    val brands = _brands.asStateFlow()
    private val _search = MutableStateFlow<Load<List<AdminProduct>>>(Load.Loading)
    val search = _search.asStateFlow()
    private var searchJob: Job? = null

    /** The last preview and the request it was made for. */
    private val _preview = MutableStateFlow<Pair<BulkPriceRequest, BulkPriceResult>?>(null)
    val preview = _preview.asStateFlow()

    /** The last applied change (for printing the new labels). */
    private val _applied = MutableStateFlow<BulkPriceResult?>(null)
    val applied = _applied.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _yerRate = MutableStateFlow<String?>(null)
    val yerRate = _yerRate.asStateFlow()

    init {
        viewModelScope.launch {
            _yerRate.value = finance.activeCurrencies().getOrNull()?.rate(Currency.YER)?.rate?.takeIf { !Money.isZero(it) }
        }
        loadLists()
    }

    fun loadLists() {
        if (_categories.value !is Load.Ready) {
            _categories.value = Load.Loading
            viewModelScope.launch { _categories.value = inventory.categories().toLoad() }
        }
        if (_brands.value !is Load.Ready) {
            _brands.value = Load.Loading
            viewModelScope.launch { _brands.value = inventory.brands().toLoad() }
        }
    }

    /** Product search for the picker (debounced). */
    fun findProducts(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isNotBlank()) delay(300)
            if (_search.value !is Load.Ready) _search.value = Load.Loading
            _search.value = inventory.products(query = query, pageSize = 50).map { it.items }.toLoad()
        }
    }

    fun toggleProduct(p: AdminProduct) {
        val list = _products.value
        _products.value = if (list.any { it.id == p.id }) list.filterNot { it.id == p.id } else list + PickedProduct(p.id, p.nameAr, p.basePrice)
    }

    fun removeProduct(id: String) {
        _products.value = _products.value.filterNot { it.id == id }
    }

    fun toggleCategory(id: String) {
        _categoryIds.value = _categoryIds.value.let { if (id in it) it - id else it + id }
    }

    fun toggleBrand(id: String) {
        _brandIds.value = _brandIds.value.let { if (id in it) it - id else it + id }
    }

    fun runPreview(request: BulkPriceRequest) = act {
        repo.bulkPrice(request.copy(preview = true)).orSay()?.let { r ->
            _preview.value = request.copy(preview = true) to r
            if (r.count == 0) say("لا توجد منتجات ضمن الاختيار")
        }
    }

    fun applyPrices(request: BulkPriceRequest) = act {
        repo.bulkPrice(request.copy(preview = false)).orSay()?.let { r ->
            _preview.value = null
            _applied.value = r
            say("تم تعديل أسعار ${r.count} منتج")
        }
    }

    fun clearApplied() {
        _applied.value = null
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

/** Change many prices at once: pick products / categories / brands, preview, apply, print labels. */
@Composable
fun BulkPriceScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "bulk-price") { BulkPriceViewModel(it.management, it.inventory, it.finance) }
    val products by vm.products.collectAsStateWithLifecycle()
    val categoryIds by vm.categoryIds.collectAsStateWithLifecycle()
    val brandIds by vm.brandIds.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val brands by vm.brands.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val applied by vm.applied.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val yerRate by vm.yerRate.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)

    var picking by remember { mutableStateOf<PickTarget?>(null) }
    var mode by rememberSaveable { mutableStateOf(PriceMode.PERCENT) }
    var value by rememberSaveable { mutableStateOf("") }
    var increase by rememberSaveable { mutableStateOf(true) }
    var roundTo by rememberSaveable { mutableStateOf(0) }
    var keepOld by rememberSaveable { mutableStateOf(true) }
    var includeVariants by rememberSaveable { mutableStateOf(true) }
    var confirming by remember { mutableStateOf(false) }
    var printing by remember { mutableStateOf<List<StickerData>?>(null) }

    BackHandler(enabled = picking != null) { picking = null }

    when (picking) {
        PickTarget.PRODUCTS -> {
            ProductPicker(vm, products, onDone = { picking = null })
            return
        }
        PickTarget.CATEGORIES -> {
            ListPicker(
                title = "اختيار أقسام",
                state = categories,
                rows = { list -> list.map { (c, depth) -> PickRow(c.id, "${"  ".repeat(depth)}${if (depth > 0) "↳ " else ""}${c.nameAr}") } },
                selected = categoryIds,
                onToggle = vm::toggleCategory,
                onRetry = vm::loadLists,
                onDone = { picking = null },
            )
            return
        }
        PickTarget.BRANDS -> {
            ListPicker(
                title = "اختيار ماركات",
                state = brands,
                rows = { list -> list.map { b -> PickRow(b.id, b.name, "${b.productCount} منتج") } },
                selected = brandIds,
                onToggle = vm::toggleBrand,
                onRetry = vm::loadLists,
                onDone = { picking = null },
            )
            return
        }
        null -> Unit
    }

    val amount = value.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val valueOk = amount != null && (mode != PriceMode.PERCENT || increase || amount < BigDecimal(100))
    val hasTarget = products.isNotEmpty() || categoryIds.isNotEmpty() || brandIds.isNotEmpty()
    val signed = amount?.let { if (mode != PriceMode.SET && !increase) it.negate() else it }?.stripTrailingZeros()?.toPlainString()
    val request = if (valueOk && hasTarget && signed != null) {
        BulkPriceRequest(
            productIds = products.map { it.id }.ifEmpty { null },
            categoryIds = categoryIds.toList().ifEmpty { null },
            brandIds = brandIds.toList().ifEmpty { null },
            mode = mode.key,
            value = signed,
            roundTo = roundTo.takeIf { it > 0 },
            keepOldAsCompareAt = keepOld,
            includeVariants = includeVariants,
            preview = true,
        )
    } else null
    val currentPreview = preview?.takeIf { it.first == request }?.second
    val categoryNames = (categories as? Load.Ready)?.value?.associate { it.first.id to it.first.nameAr }.orEmpty()
    val brandNames = (brands as? Load.Ready)?.value?.associate { it.id to it.name }.orEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("تعديل الأسعار جماعياً", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            applied?.let { r ->
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("تم تعديل أسعار ${r.count} منتج", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                        val stickers = r.changedStickers(yerRate)
                        Text("${stickers.size} مقاس تغيّر سعره", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        Spacer(Modifier.size(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { printing = stickers }, enabled = stickers.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("طباعة ملصقات الأسعار الجديدة") }
                            TextButton(onClick = vm::clearApplied) { Text("إخفاء") }
                        }
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("المنتجات المشمولة", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.size(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { picking = PickTarget.PRODUCTS }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("اختيار منتجات", maxLines = 1)
                        }
                        OutlinedButton(onClick = { picking = PickTarget.CATEGORIES }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("اختيار أقسام", maxLines = 1)
                        }
                        OutlinedButton(onClick = { picking = PickTarget.BRANDS }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("اختيار ماركات", maxLines = 1)
                        }
                    }
                    if (!hasTarget) {
                        Text("اختر منتجات أو أقساماً أو ماركات", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 6.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        products.forEach { p ->
                            RemovableChip("${p.name} · ${Money.formatBase(p.price)}") { vm.removeProduct(p.id) }
                        }
                        categoryIds.forEach { id ->
                            RemovableChip("قسم: ${categoryNames[id] ?: "…"}") { vm.toggleCategory(id) }
                        }
                        brandIds.forEach { id ->
                            RemovableChip("ماركة: ${brandNames[id] ?: "…"}") { vm.toggleBrand(id) }
                        }
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("طريقة التعديل", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PriceMode.entries.forEach { m ->
                            FilterChip(selected = m == mode, onClick = { mode = m }, label = { Text(m.label) })
                        }
                    }
                    if (mode != PriceMode.SET) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = increase, onClick = { increase = true }, label = { Text("ارفع") })
                            FilterChip(selected = !increase, onClick = { increase = false }, label = { Text("اخفض") })
                        }
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { v -> value = v.filter { it.isDigit() || it == '.' }.take(10) },
                        label = {
                            Text(
                                when (mode) {
                                    PriceMode.PERCENT -> "النسبة %"
                                    PriceMode.AMOUNT -> "المبلغ (ر.س)"
                                    PriceMode.SET -> "السعر الجديد (ر.س)"
                                },
                            )
                        },
                        isError = value.isNotEmpty() && !valueOk,
                        supportingText = if (value.isNotEmpty() && !valueOk) ({ Text(if (mode == PriceMode.PERCENT && amount != null) "لا يمكن التخفيض بنسبة 100% أو أكثر" else "أدخل قيمة أكبر من صفر") }) else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("التقريب", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 5, 10).forEach { r ->
                            FilterChip(selected = r == roundTo, onClick = { roundTo = r }, label = { Text(if (r == 0) "بدون" else "$r") })
                        }
                    }
                    SwitchRow("إظهار السعر القديم مشطوباً عند التخفيض", keepOld) { keepOld = it }
                    SwitchRow("تطبيق على أسعار المقاسات الخاصة", includeVariants) { includeVariants = it }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { request?.let(vm::runPreview) }, enabled = request != null && !busy, modifier = Modifier.weight(1f)) { Text("معاينة") }
                        Button(onClick = { confirming = true }, enabled = currentPreview != null && currentPreview.count > 0 && !busy, modifier = Modifier.weight(1f)) { Text("تطبيق") }
                    }
                    if (request != null && currentPreview == null) {
                        Text("اضغط «معاينة» لرؤية الأسعار الجديدة قبل التطبيق", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    }
                }
            }
            currentPreview?.let { r ->
                item {
                    Text("المعاينة: ${r.count} منتج", style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
                }
                items(r.items, key = { "pv:${it.productId}" }) { row ->
                    PreviewRow(row)
                }
            }
        }
    }

    if (confirming) {
        val count = currentPreview?.count ?: 0
        ConfirmDialog(
            title = "تطبيق الأسعار الجديدة",
            text = "سيتم تعديل أسعار $count منتج. هل تريد المتابعة؟",
            confirm = "تطبيق",
            onDismiss = { confirming = false },
        ) { request?.let(vm::applyPrices) }
    }
    printing?.let { list ->
        StickerDialog("ملصقات الأسعار الجديدة", list) { printing = null }
    }
}

/** Labels of every variant whose price changed, with the new price. */
private fun BulkPriceResult.changedStickers(yerRate: String?): List<StickerData> = items.flatMap { p ->
    p.variants.filter { Money.parse(it.oldPrice).compareTo(Money.parse(it.newPrice)) != 0 }.map { v ->
        StickerData(
            sku = v.sku,
            name = p.name,
            variant = listOfNotNull(v.color, v.size).joinToString(" · ").ifBlank { null },
            priceSar = Money.formatBase(v.newPrice),
            priceYer = yerRate?.let { Money.format(v.newPrice, Currency.YER, it) },
        )
    }
}

@Composable
private fun PreviewRow(item: BulkPriceResult.Item) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.skuNumber?.let { Text("#$it", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
            }
            PriceChange(item.oldPrice, item.newPrice)
        }
        val special = item.variants.filter { Money.parse(it.oldPrice).compareTo(Money.parse(item.oldPrice)) != 0 }
        special.forEach { v ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(v.color, v.size).joinToString(" · ").ifBlank { v.sku },
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                    modifier = Modifier.weight(1f),
                )
                PriceChange(v.oldPrice, v.newPrice)
            }
        }
    }
}

@Composable
private fun PriceChange(old: String, new: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Money.formatBase(old), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, textDecoration = TextDecoration.LineThrough)
        Text("←", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        Text(Money.formatBase(new), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RemovableChip(text: String, onRemove: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onRemove,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "إزالة", modifier = Modifier.size(16.dp)) },
    )
}

/** One line of a category / brand picker. */
private data class PickRow(val id: String, val title: String, val subtitle: String? = null)

/** Full-screen multi-select of products: search, checkboxes, current price. */
@Composable
private fun ProductPicker(vm: BulkPriceViewModel, picked: List<PickedProduct>, onDone: () -> Unit) {
    val state by vm.search.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { vm.findProducts(query) }
    val pickedIds = picked.map { it.id }.toSet()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("اختيار منتجات (${picked.size})", onDone) {
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

/** Full-screen multi-select of categories or brands with a local filter. */
@Composable
private fun <T> ListPicker(
    title: String,
    state: Load<List<T>>,
    rows: (List<T>) -> List<PickRow>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("$title (${selected.size})", onDone) {
                TextButton(onClick = onDone) { Text("تم") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                placeholder = { Text("بحث") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LoadContent(state, onRetry = onRetry) { list ->
                val all = rows(list)
                val shown = if (query.isBlank()) all else all.filter { it.title.contains(query.trim(), ignoreCase = true) }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(r.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = r.id in selected, onCheckedChange = { onToggle(r.id) })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.bodyLarge)
                                r.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- offers ---

class OffersViewModel(private val repo: ManagementRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<OfferProduct>>>(Load.Loading)
    val state = _state.asStateFlow()
    val refresher = Refresher()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.onOffer().toLoad() }
    }

    fun refresh() = refresher.run(viewModelScope) { _state.value = repo.onOffer().toLoad() }
}

private enum class OfferFilter(val label: String) {
    ALL("الكل"),
    PROMOTION("عرض ترويجي"),
    COMPARE_AT("سعر مشطوب"),
}

/** Products on offer now (promotion or struck-through price). */
@Composable
fun OffersScreen(onBack: () -> Unit, openProduct: (String) -> Unit) {
    val vm = adminViewModel(key = "offers") { OffersViewModel(it.management) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(OfferFilter.ALL) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("المنتجات المعروضة بخصم", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OfferFilter.entries.forEach { f ->
                    FilterChip(selected = f == filter, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            LoadContent(state, onRetry = vm::load) { all ->
                val list = when (filter) {
                    OfferFilter.ALL -> all
                    OfferFilter.PROMOTION -> all.filter { it.promotion != null }
                    OfferFilter.COMPARE_AT -> all.filter { it.promotion == null }
                }
                RefreshList(refreshing, onRefresh = vm::refresh) {
                    if (list.isEmpty()) {
                        EmptyList(Icons.Filled.Star, "لا توجد منتجات بخصم الآن", "المنتجات ضمن عرض ترويجي أو بسعر مشطوب تظهر هنا")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Text("${list.size} منتج", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                            items(list, key = { it.productId }) { p -> OfferRow(p) { openProduct(p.productId) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferRow(p: OfferProduct, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProductImage(p.imageUrl, Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)), p.name)
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(p.skuNumber?.let { "#$it" }, p.category.nameAr).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Money.formatBase(p.was), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, textDecoration = TextDecoration.LineThrough)
                    Text(Money.formatBase(p.price), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                }
                val promo = p.promotion
                Text(
                    if (promo != null) {
                        "عرض: ${promo.name}" + (promo.endsAt?.let { " · ينتهي ${formatDate(it)}" } ?: "")
                    } else {
                        "سعر مشطوب"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            GoldPill { Text("-${percentText(p.percentOff)}%", color = Color.White, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

private fun percentText(v: String): String =
    v.toBigDecimalOrNull()?.setScale(0, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: v
