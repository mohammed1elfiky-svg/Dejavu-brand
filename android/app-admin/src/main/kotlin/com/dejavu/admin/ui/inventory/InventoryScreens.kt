@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.inventory

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import com.dejavu.admin.ui.products.AddVariantDialog
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.Color
import com.dejavu.admin.ui.barcode.StickerData
import com.dejavu.admin.ui.products.StickerDialog
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.model.Currency
import com.dejavu.admin.ui.products.EditVariantDialog
import com.dejavu.core.admin.AdminVariant
import com.dejavu.core.admin.NewVariantRequest
import com.dejavu.core.admin.UpdateVariantRequest
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.core.admin.AdjustmentReason
import com.dejavu.core.admin.AdminProduct
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.LowStockRow
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.ProductStatus
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.StockLevel
import com.dejavu.core.admin.UpdateProductRequest
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
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

val AdjustmentReason.label: String
    get() = when (this) {
        AdjustmentReason.OPENING -> "رصيد افتتاحي"
        AdjustmentReason.COUNT_SURPLUS -> "زيادة في الجرد"
        AdjustmentReason.OTHER_IN -> "إضافة أخرى"
        AdjustmentReason.COUNT_SHORTAGE -> "عجز في الجرد"
        AdjustmentReason.DAMAGE -> "تالف"
        AdjustmentReason.OTHER_OUT -> "خصم آخر"
    }

private val ADJUSTMENT_REASONS = listOf(
    AdjustmentReason.COUNT_SURPLUS, AdjustmentReason.OTHER_IN,
    AdjustmentReason.COUNT_SHORTAGE, AdjustmentReason.DAMAGE, AdjustmentReason.OTHER_OUT,
)

val ProductStatus.label: String
    get() = when (this) {
        ProductStatus.DRAFT -> "مسودة"
        ProductStatus.ACTIVE -> "منشور"
        ProductStatus.ARCHIVED -> "مؤرشف"
    }

// -------------------------------------------------------------------- stock ---

class StockViewModel(private val repo: InventoryRepository) : BaseViewModel() {
    private val _levels = MutableStateFlow<Load<List<StockLevel>>>(Load.Loading)
    val levels = _levels.asStateFlow()
    private val _low = MutableStateFlow<List<LowStockRow>>(emptyList())
    val low = _low.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    var query = ""
        private set

    fun search(q: String) {
        query = q
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        if (reset || _levels.value !is Load.Ready) _levels.value = Load.Loading
        viewModelScope.launch { _levels.value = repo.levels(query).map { it.items }.toLoad() }
        viewModelScope.launch { repo.lowStock().getOrNull()?.let { _low.value = it } }
    }

    fun adjust(level: StockLevel, reason: AdjustmentReason, quantity: Int, note: String) {
        _busy.value = true
        viewModelScope.launch {
            repo.adjust(level.warehouseId, reason, level.variantId, quantity, note).orSay()?.let {
                say("تم ${it.number}: ${Money.format(it.totalValue)}")
                load()
            }
            _busy.value = false
        }
    }
}

@Composable
fun StockScreen(session: StaffSession.LoggedIn, openProducts: () -> Unit) {
    val vm = adminViewModel { StockViewModel(it.inventory) }
    val levels by vm.levels.collectAsStateWithLifecycle()
    val low by vm.low.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf(vm.query) }
    var onlyLow by rememberSaveable { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf<StockLevel?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("المخزون") },
                actions = { if (session.can(Perm.PRODUCTS_READ)) TextButton(onClick = openProducts) { Text("المنتجات") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("SKU أو اسم المنتج") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !onlyLow, onClick = { onlyLow = false }, label = { Text("الكل") })
                FilterChip(selected = onlyLow, onClick = { onlyLow = true }, label = { Text("نواقص (${low.size})") })
            }
            LoadContent(levels, onRetry = { vm.load() }) { list ->
                val lowIds = low.map { it.variantId }.toSet()
                val shown = if (onlyLow) list.filter { it.variantId in lowIds } else list
                if (shown.isEmpty()) {
                    EmptyState(Icons.Filled.ShoppingCart, if (onlyLow) "لا توجد نواقص" else "لا توجد أصناف")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { "${it.variantId}:${it.warehouseId}" }) { l ->
                            Card(
                                onClick = { if (session.can(Perm.STOCK_ADJUST)) adjusting = l },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(l.variant.product.nameAr, style = MaterialTheme.typography.titleSmall)
                                        Text("${l.variant.sku} · ${listOfNotNull(l.variant.size, l.variant.color).joinToString(" / ")}", style = MaterialTheme.typography.bodySmall)
                                        Text("${l.warehouse.name} · القيمة ${Money.format(l.stockValue)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        val isLow = l.variantId in lowIds
                                        Text("${l.available}", style = MaterialTheme.typography.headlineSmall, color = if (isLow) MaterialTheme.colorScheme.error else Success)
                                        Text("متاح من ${l.onHand}", style = MaterialTheme.typography.labelSmall)
                                        if (l.reserved > 0) Text("محجوز ${l.reserved}", style = MaterialTheme.typography.labelSmall, color = Warning)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    adjusting?.let { level -> AdjustDialog(level, onDismiss = { adjusting = null }) { reason, qty, note -> vm.adjust(level, reason, qty, note) } }
}

@Composable
private fun AdjustDialog(level: StockLevel, onDismiss: () -> Unit, onConfirm: (AdjustmentReason, Int, String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf(AdjustmentReason.DAMAGE) }
    var qty by rememberSaveable { mutableStateOf("1") }
    var note by rememberSaveable { mutableStateOf("") }
    val quantity = qty.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسوية مخزون") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${level.variant.product.nameAr} · ${level.variant.sku} (متاح ${level.available})", style = MaterialTheme.typography.bodySmall)
                ADJUSTMENT_REASONS.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { reason = r }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = r == reason, onClick = { reason = r })
                        Text(r.label)
                    }
                }
                OutlinedTextField(
                    value = qty, onValueChange = { qty = it.filter(Char::isDigit) }, label = { Text("الكمية") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text("ملاحظة") }, singleLine = true)
                Text("يُسجَّل قيد محاسبي تلقائياً بمتوسط التكلفة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(reason, quantity!!, note.trim()) }, enabled = quantity != null) { Text("تسجيل") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

// ----------------------------------------------------------------- products ---

class ProductsViewModel(private val repo: InventoryRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<AdminProduct>>>(Load.Loading)
    val state = _state.asStateFlow()
    var query = ""
        private set

    fun search(q: String) {
        query = q
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        if (reset || _state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.products(query).map { it.items }.toLoad() }
    }
}

@Composable
fun ProductsScreen(onBack: () -> Unit, openProduct: (String) -> Unit, newProduct: (() -> Unit)?) {
    val vm = adminViewModel { ProductsViewModel(it.inventory) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(vm.query) }
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("المنتجات", onBack) },
        floatingActionButton = {
            if (newProduct != null) {
                ExtendedFloatingActionButton(
                    onClick = newProduct,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("منتج جديد") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("بحث") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            LoadContent(state, onRetry = { vm.load() }) { list ->
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { p ->
                        Card(onClick = { openProduct(p.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProductImage(p.images.firstOrNull()?.url, Modifier.size(56.dp).clip(MaterialTheme.shapes.small))
                                Column(Modifier.weight(1f)) {
                                    Text(p.nameAr, style = MaterialTheme.typography.titleSmall)
                                    Text("${p.skuNumber?.let { "SKU $it · " } ?: ""}${p.category.nameAr} · ${Money.format(p.basePrice)}", style = MaterialTheme.typography.bodySmall)
                                    Text("المتاح: ${p.variants.sumOf { it.available }} قطعة", style = MaterialTheme.typography.bodySmall)
                                }
                                StatusChip(p.status.label, if (p.status == ProductStatus.ACTIVE) Success else Warning)
                            }
                        }
                    }
                }
            }
        }
    }
}

class ProductDetailViewModel(
    private val id: String,
    private val repo: InventoryRepository,
    private val finance: FinanceRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<AdminProduct>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _yerRate = MutableStateFlow<String?>(null)

    /** Current YER rate (units per 1 SAR) for the second price on stickers; null when unknown. */
    val yerRate = _yerRate.asStateFlow()

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.product(id).toLoad() }
        if (_yerRate.value == null) {
            viewModelScope.launch {
                _yerRate.value = finance.activeCurrencies().getOrNull()?.rate(Currency.YER)?.rate?.takeIf { !Money.isZero(it) }
            }
        }
    }

    fun update(request: UpdateProductRequest) = act {
        repo.updateProduct(id, request).orSay()?.let {
            _state.value = Load.Ready(it)
            say("تم الحفظ")
        }
    }

    fun addVariant(request: NewVariantRequest) = act {
        repo.addVariant(id, request).orSay()?.let {
            say("تمت إضافة ${it.title} (${it.sku})")
            _state.value = repo.product(id).toLoad()
        }
    }

    fun updateVariant(variantId: String, request: UpdateVariantRequest) = act {
        repo.updateVariant(variantId, request).orSay()?.let {
            say("تم حفظ ${it.title}")
            _state.value = repo.product(id).toLoad()
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

/** What the barcode label of [v] shows: SKU, name, "color · size", price in SAR and (when a rate is set) YER. */
private fun AdminProduct.sticker(v: AdminVariant, yerRate: String?): StickerData {
    val price = v.price ?: basePrice
    return StickerData(
        sku = v.sku,
        name = nameAr,
        variant = listOfNotNull(v.color, v.size).joinToString(" · ").ifBlank { null },
        priceSar = Money.formatBase(price),
        priceYer = yerRate?.let { Money.format(price, Currency.YER, it) },
    )
}

@Composable
fun ProductDetailScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit, edit: () -> Unit) {
    val vm = adminViewModel(key = "product:$id") { ProductDetailViewModel(id, it.inventory, it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val yerRate by vm.yerRate.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AdminVariant?>(null) }
    var printing by remember { mutableStateOf<List<AdminVariant>?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    val canEdit = session.can(Perm.PRODUCTS_WRITE)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar((state as? Load.Ready)?.value?.nameAr ?: "", onBack) {
                if (canEdit && state is Load.Ready) IconButton(onClick = edit) { Icon(Icons.Filled.Edit, contentDescription = "تعديل") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { p ->
            var price by rememberSaveable(p.basePrice) { mutableStateOf(p.basePrice) }
            var compareAt by rememberSaveable(p.compareAtPrice) { mutableStateOf(p.compareAtPrice.orEmpty()) }
            var cost by rememberSaveable(p.costPrice) { mutableStateOf(p.costPrice) }
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.nameEn, style = MaterialTheme.typography.bodySmall)
                                Text("القسم: ${p.category.nameAr}")
                            }
                            GoldPill { Text("SKU ${p.skuNumber ?: "—"}", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                        }
                        SummaryRow("التكلفة", Money.formatBase(p.costPrice))
                        if (canEdit) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("مميز في الصفحة الرئيسية", Modifier.weight(1f))
                                Switch(checked = p.isFeatured, onCheckedChange = { vm.update(UpdateProductRequest(isFeatured = it)) }, enabled = !busy)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProductStatus.entries.forEach { s ->
                                    FilterChip(selected = p.status == s, onClick = { vm.update(UpdateProductRequest(status = s)) }, label = { Text(s.label) }, enabled = !busy)
                                }
                            }
                            OutlinedTextField(
                                value = price, onValueChange = { price = it }, label = { Text("السعر (شامل الضريبة)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = compareAt, onValueChange = { compareAt = it }, label = { Text("السعر قبل الخصم (اختياري)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = cost, onValueChange = { cost = it }, label = { Text("التكلفة (ر.س)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                            )
                            val costValid = cost.isBlank() || cost.trim().toBigDecimalOrNull()?.let { it.signum() >= 0 && it.scale() <= 2 } == true
                            Button(
                                onClick = {
                                    vm.update(
                                        UpdateProductRequest(
                                            basePrice = price.trim(),
                                            compareAtPrice = compareAt.trim().ifBlank { null },
                                            costPrice = cost.trim().ifBlank { null },
                                        ),
                                    )
                                },
                                enabled = !busy && price.toBigDecimalOrNull() != null && costValid,
                            ) { Text("حفظ الأسعار") }
                        } else {
                            SummaryRow("السعر", Money.format(p.basePrice))
                        }
                    }
                }
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("الصور (${p.images.size}/10)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (canEdit) TextButton(onClick = edit) { Text("إدارة الصور") }
                        }
                        if (p.images.isEmpty()) {
                            Text("لا توجد صور بعد", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(p.images) { img ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        ProductImage(img.url, Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)))
                                        Text(img.color ?: "عام", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("المقاسات والألوان (${p.variants.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (canEdit) TextButton(onClick = { adding = true }, enabled = !busy) { Text("+ إضافة") }
                    }
                }
                if (p.variants.isNotEmpty()) {
                    item {
                        OutlinedButton(onClick = { printing = p.variants.filter { it.isActive }.ifEmpty { p.variants } }, modifier = Modifier.fillMaxWidth()) {
                            Text("طباعة ملصقات كل المقاسات")
                        }
                    }
                }
                items(p.variants, key = { it.id }) { v ->
                    GlassCard(
                        Modifier.fillMaxWidth().clickable(enabled = canEdit) { editing = v },
                        elevation = 6.dp,
                        contentPadding = 14.dp,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${v.title}${if (!v.isActive) " · موقوف" else ""}", style = MaterialTheme.typography.titleSmall)
                                Text("SKU: ${v.sku}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "باركود المصنع: ${v.barcode ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "متوسط التكلفة ${Money.formatBase(v.avgCost)}${v.price?.let { " · سعر خاص ${Money.format(it)}" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${v.available}", style = MaterialTheme.typography.titleLarge, color = if (v.available <= v.lowStockThreshold) MaterialTheme.colorScheme.error else Success)
                                Text("متاح", style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { printing = listOf(v) }) { Text("طباعة ملصق الباركود") }
                            }
                        }
                    }
                }
            }
        }
    }

    val product = (state as? Load.Ready)?.value
    if (adding && product != null) AddVariantDialog(product, onDismiss = { adding = false }, onSave = vm::addVariant)
    editing?.let { v -> EditVariantDialog(v, onDismiss = { editing = null }, onSave = { vm.updateVariant(v.id, it) }) }
    val toPrint = printing
    if (toPrint != null && product != null) {
        val stickers = remember(toPrint, product, yerRate) { toPrint.map { product.sticker(it, yerRate) } }
        StickerDialog(
            title = if (toPrint.size == 1) "ملصق ${toPrint.first().sku}" else "ملصقات ${product.nameAr}",
            stickers = stickers,
            onDismiss = { printing = null },
        )
    }
}
