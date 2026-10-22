@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.dejavu.admin.ui.inventory

import com.dejavu.core.ui.theme.GlassTopBar
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import com.dejavu.admin.ui.products.AddVariantDialog
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import com.dejavu.core.ui.theme.OutlinedButton
import androidx.compose.ui.graphics.Color
import com.dejavu.admin.ui.barcode.StickerData
import com.dejavu.admin.ui.products.StickerDialog
import androidx.compose.ui.platform.LocalContext
import com.dejavu.admin.ui.products.openLink
import com.dejavu.admin.ui.products.openVideo
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
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.ProductStatus
import com.dejavu.core.admin.StaffSession
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.style.TextOverflow
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminCategory
import com.dejavu.core.admin.Warehouse
import com.dejavu.core.model.Page
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged

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

private const val STOCK_PAGE_SIZE = 100

/** Products loaded so far for the stock tab (paged, 100 at a time). */
data class StockPage(
    val products: List<AdminProduct> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
)

private enum class StockFlag { OK, LOW, OUT }

private fun AdminVariant.flag(): StockFlag = when {
    available <= 0 -> StockFlag.OUT
    available <= lowStockThreshold -> StockFlag.LOW
    else -> StockFlag.OK
}

private val AdminVariant.reserved: Int get() = stockLevels.sumOf { it.reserved }

/** Out when nothing sellable is left; low when any (active) variant is at or under its threshold. */
private fun AdminProduct.flag(): StockFlag {
    val active = variants.filter { it.isActive }.ifEmpty { variants }
    if (active.isEmpty() || active.all { it.flag() == StockFlag.OUT }) return StockFlag.OUT
    return if (active.any { it.flag() != StockFlag.OK }) StockFlag.LOW else StockFlag.OK
}

class StockViewModel(private val repo: InventoryRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<StockPage>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _categories = MutableStateFlow<List<Pair<AdminCategory, Int>>>(emptyList())
    val categories = _categories.asStateFlow()
    private val _warehouses = MutableStateFlow<List<Warehouse>>(emptyList())
    val warehouses = _warehouses.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    var query = ""
        private set
    var categoryId: String? = null
        private set
    private var job: Job? = null

    fun search(q: String) {
        query = q
        load(reset = true)
    }

    fun selectCategory(id: String?) {
        if (id == categoryId) return
        categoryId = id
        load(reset = true)
    }

    /** First load (or [reset]) shows a spinner; otherwise re-fetches the pages already shown in place. */
    fun load(reset: Boolean = false) {
        if (_categories.value.isEmpty()) viewModelScope.launch { repo.categories().getOrNull()?.let { _categories.value = it } }
        if (_warehouses.value.isEmpty()) viewModelScope.launch { repo.warehouses().getOrNull()?.let { list -> _warehouses.value = list.filter { it.isActive } } }
        val current = (_state.value as? Load.Ready)?.value
        if (reset || current == null) {
            _state.value = Load.Loading
            job?.cancel()
            job = viewModelScope.launch {
                _state.value = repo.products(query, page = 1, categoryId = categoryId, pageSize = STOCK_PAGE_SIZE)
                    .map { StockPage(it.items, it.total, it.page, it.hasMore) }
                    .toLoad()
            }
        } else {
            refresh(current.page)
        }
    }

    private fun refresh(pages: Int) {
        job?.cancel()
        job = viewModelScope.launch {
            val items = mutableListOf<AdminProduct>()
            var last: Page<AdminProduct>? = null
            for (p in 1..pages.coerceAtLeast(1)) {
                val page = repo.products(query, page = p, categoryId = categoryId, pageSize = STOCK_PAGE_SIZE).getOrElse {
                    // Keep what is on screen; only a first load surfaces the error.
                    val shown = (_state.value as? Load.Ready)?.value
                    _state.value = if (shown != null) Load.Ready(shown.copy(loadingMore = false)) else Result.failure<StockPage>(it).toLoad()
                    return@launch
                }
                items += page.items
                last = page
                if (!page.hasMore) break
            }
            last?.let { lp -> _state.value = Load.Ready(StockPage(items.distinctBy { it.id }, lp.total, lp.page, lp.hasMore)) }
        }
    }

    fun loadMore() {
        val current = (_state.value as? Load.Ready)?.value ?: return
        if (!current.hasMore || current.loadingMore || job?.isActive == true) return
        _state.value = Load.Ready(current.copy(loadingMore = true))
        job = viewModelScope.launch {
            val next = repo.products(query, page = current.page + 1, categoryId = categoryId, pageSize = STOCK_PAGE_SIZE).orSay()
            val now = (_state.value as? Load.Ready)?.value ?: current
            _state.value = Load.Ready(
                if (next == null) now.copy(loadingMore = false)
                else StockPage((now.products + next.items).distinctBy { it.id }, next.total, next.page, next.hasMore),
            )
        }
    }

    fun adjust(variantId: String, warehouseId: String, reason: AdjustmentReason, quantity: Int, note: String) {
        _busy.value = true
        viewModelScope.launch {
            repo.adjust(warehouseId, reason, variantId, quantity, note).orSay()?.let {
                say("تم ${it.number}: ${Money.format(it.totalValue)}")
                load()
            }
            _busy.value = false
        }
    }
}

/**
 * The "المخزون" tab: products grouped by category, one row per product with its total stock;
 * a row expands to its variants (size/color) with per-variant quantities and stock adjustment.
 * [openProduct], when given, adds a "تفاصيل الصنف" button that opens the product page.
 */
@Composable
fun StockScreen(session: StaffSession.LoggedIn, openProducts: () -> Unit, openProduct: ((String) -> Unit)? = null) {
    val vm = adminViewModel { StockViewModel(it.inventory) }
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val warehouses by vm.warehouses.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf(vm.query) }
    var onlyLow by rememberSaveable { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf<Pair<AdminProduct, AdminVariant>?>(null) }
    val canAdjust = session.can(Perm.STOCK_ADJUST)
    val canProducts = session.can(Perm.PRODUCTS_READ)
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GlassTopBar(
                title = { Text("المخزون") },
                navigationIcon = {
                    IconButton(onClick = { vm.load() }) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") }
                },
                actions = { if (canProducts) TextButton(onClick = openProducts) { Text("المنتجات") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("اسم المنتج أو SKU أو الباركود") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "low") {
                    FilterChip(
                        selected = onlyLow,
                        onClick = { onlyLow = !onlyLow },
                        label = { Text("نواقص") },
                        leadingIcon = { Icon(Icons.Filled.Warning, contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
                item(key = "all") {
                    FilterChip(selected = vm.categoryId == null, onClick = { vm.selectCategory(null) }, label = { Text("الكل") })
                }
                items(categories, key = { "c:${it.first.id}" }) { (c, _) ->
                    FilterChip(selected = vm.categoryId == c.id, onClick = { vm.selectCategory(c.id) }, label = { Text(c.nameAr) })
                }
            }
            LoadContent(state, onRetry = { vm.load(reset = true) }) { page ->
                StockList(
                    page = page,
                    categories = categories,
                    onlyLow = onlyLow,
                    canAdjust = canAdjust,
                    onAdjust = { p, v -> adjusting = p to v },
                    openProduct = openProduct?.takeIf { canProducts },
                    openProducts = openProducts.takeIf { canProducts },
                    loadMore = vm::loadMore,
                )
            }
        }
    }

    adjusting?.let { (p, v) ->
        AdjustDialog(p, v, warehouses, onDismiss = { adjusting = null }) { warehouseId, reason, qty, note ->
            vm.adjust(v.id, warehouseId, reason, qty, note)
        }
    }
}

@Composable
private fun StockList(
    page: StockPage,
    categories: List<Pair<AdminCategory, Int>>,
    onlyLow: Boolean,
    canAdjust: Boolean,
    onAdjust: (AdminProduct, AdminVariant) -> Unit,
    openProduct: ((String) -> Unit)?,
    openProducts: (() -> Unit)?,
    loadMore: () -> Unit,
) {
    val shown = remember(page.products, onlyLow) {
        if (onlyLow) page.products.filter { it.flag() != StockFlag.OK } else page.products
    }
    val groups = remember(shown, categories) {
        val order = categories.withIndex().associate { (i, c) -> c.first.id to i }
        shown.groupBy { it.category.id }.entries.sortedBy { order[it.key] ?: Int.MAX_VALUE }.map { it.value }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (nearEnd, _) -> if (nearEnd) loadMore() }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "summary") { StockSummary(page) }
        if (shown.isEmpty()) {
            item(key = "empty") {
                if (page.hasMore) {
                    Text(
                        "لا نواقص في ما حُمّل حتى الآن…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    EmptyState(Icons.Filled.ShoppingCart, if (onlyLow) "لا توجد نواقص" else "لا توجد أصناف", modifier = Modifier.height(280.dp))
                }
            }
        }
        groups.forEach { group ->
            val category = group.first().category
            stickyHeader(key = "h:${category.id}", contentType = "header") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Lux.Pearl.copy(alpha = 0.96f))
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Lux.GoldDeep, modifier = Modifier.size(18.dp))
                    Text(category.nameAr, style = MaterialTheme.typography.titleSmall, color = Lux.Ink, modifier = Modifier.weight(1f))
                    Text(
                        "${group.size} صنف · ${group.sumOf { p -> p.variants.sumOf { it.onHand } }} قطعة",
                        style = MaterialTheme.typography.labelMedium,
                        color = Lux.InkSoft,
                    )
                }
            }
            items(group, key = { it.id }, contentType = { "product" }) { p ->
                ProductStockCard(p, canAdjust, onAdjust, openProduct, openProducts)
            }
        }
        if (page.hasMore || page.loadingMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    if (page.loadingMore) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    else TextButton(onClick = loadMore) { Text("تحميل المزيد") }
                }
            }
        }
    }
}

@Composable
private fun StockSummary(page: StockPage) {
    val pieces = page.products.sumOf { p -> p.variants.sumOf { it.onHand } }
    val available = page.products.sumOf { p -> p.variants.sumOf { it.available } }
    val low = page.products.count { it.flag() == StockFlag.LOW }
    val out = page.products.count { it.flag() == StockFlag.OUT }
    val plus = if (page.hasMore) "+" else ""
    GlassCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SummaryStat("المنتجات", "${page.total}")
            SummaryStat("القطع", "$pieces$plus")
            SummaryStat("المتاح", "$available$plus")
            GoldPill {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$low$plus", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("نواقص", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (out > 0 || page.hasMore) {
            Text(
                listOfNotNull(
                    "نفد مخزون $out صنف".takeIf { out > 0 },
                    "الأرقام لأول ${page.products.size} من ${page.total} — مرّر للمزيد".takeIf { page.hasMore },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Lux.InkSoft,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Lux.Ink)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    }
}

@Composable
private fun ProductStockCard(
    p: AdminProduct,
    canAdjust: Boolean,
    onAdjust: (AdminProduct, AdminVariant) -> Unit,
    openProduct: ((String) -> Unit)?,
    openProducts: (() -> Unit)?,
) {
    var expanded by rememberSaveable(p.id) { mutableStateOf(false) }
    val onHand = p.variants.sumOf { it.onHand }
    val available = p.variants.sumOf { it.available }
    val flag = p.flag()
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProductImage(p.images.firstOrNull()?.url, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.nameAr, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "SKU ${p.skuNumber ?: "—"} · ${p.variants.size} متغير",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (flag) {
                    StockFlag.OUT -> StatusChip("نفد المخزون", MaterialTheme.colorScheme.error)
                    StockFlag.LOW -> StatusChip("مخزون منخفض", Warning)
                    StockFlag.OK -> Unit
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$onHand",
                    style = MaterialTheme.typography.titleLarge,
                    color = when (flag) {
                        StockFlag.OUT -> MaterialTheme.colorScheme.error
                        StockFlag.LOW -> Warning
                        StockFlag.OK -> Success
                    },
                )
                Text("متاح $available", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "إخفاء المقاسات" else "عرض المقاسات",
                tint = Lux.GoldDeep,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = Lux.Hairline)
                if (p.variants.isEmpty()) {
                    Text("لا توجد مقاسات أو ألوان", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
                p.variants.forEach { v -> VariantStockRow(v, onAdjust = if (canAdjust) ({ onAdjust(p, v) }) else null) }
                if (openProduct != null) {
                    OutlinedButton(onClick = { openProduct(p.id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Info, contentDescription = null, Modifier.size(18.dp))
                        Text("تفاصيل الصنف", Modifier.padding(start = 8.dp))
                    }
                } else if (openProducts != null) {
                    TextButton(onClick = openProducts, modifier = Modifier.fillMaxWidth()) { Text("إدارة المنتجات") }
                }
            }
        }
    }
}

@Composable
private fun VariantStockRow(v: AdminVariant, onAdjust: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Lux.Champagne.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ColorDot(v.colorHex)
        Column(Modifier.weight(1f)) {
            Text(
                "${v.title}${if (!v.isActive) " · موقوف" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (v.isActive) Lux.Ink else Lux.InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "SKU ${v.sku}${v.barcode?.takeIf { it.isNotBlank() }?.let { " · باركود $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = Lux.InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${v.available}",
                style = MaterialTheme.typography.titleMedium,
                color = when (v.flag()) {
                    StockFlag.OUT -> MaterialTheme.colorScheme.error
                    StockFlag.LOW -> Warning
                    StockFlag.OK -> Success
                },
            )
            Text(
                "الكل ${v.onHand}${if (v.reserved > 0) " · محجوز ${v.reserved}" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = if (v.reserved > 0) Warning else Lux.InkSoft,
            )
        }
        if (onAdjust != null) {
            IconButton(onClick = onAdjust, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "تسوية مخزون", tint = Lux.GoldDeep, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ColorDot(hex: String?) {
    val color = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    if (color == null) {
        Box(Modifier.size(14.dp))
    } else {
        Box(Modifier.size(14.dp).background(color, CircleShape).border(1.dp, Lux.Hairline, CircleShape))
    }
}

@Composable
private fun AdjustDialog(
    product: AdminProduct,
    variant: AdminVariant,
    warehouses: List<Warehouse>,
    onDismiss: () -> Unit,
    onConfirm: (warehouseId: String, AdjustmentReason, Int, String) -> Unit,
) {
    // Warehouses this variant can be adjusted in: the known active ones, else the ones it has stock rows in.
    val choices = remember(warehouses, variant) {
        warehouses.map { it.id to it.name }.ifEmpty { variant.stockLevels.map { it.warehouseId to "مستودع" } }
    }
    val initial = remember(choices, variant) {
        variant.stockLevels.filter { l -> choices.any { it.first == l.warehouseId } }.maxByOrNull { it.onHand }?.warehouseId
            ?: warehouses.firstOrNull { it.isDefault }?.id
            ?: choices.firstOrNull()?.first
    }
    var warehouseId by rememberSaveable(initial) { mutableStateOf(initial) }
    var reason by rememberSaveable { mutableStateOf(AdjustmentReason.DAMAGE) }
    var qty by rememberSaveable { mutableStateOf("1") }
    var note by rememberSaveable { mutableStateOf("") }
    val quantity = qty.toIntOrNull()?.takeIf { it > 0 }
    val level = variant.stockLevels.firstOrNull { it.warehouseId == warehouseId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسوية مخزون") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${product.nameAr} · ${variant.title} · ${variant.sku} (متاح ${level?.let { it.onHand - it.reserved } ?: 0})",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (choices.size > 1) {
                    Text("المستودع", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(choices, key = { it.first }) { (id, name) ->
                            FilterChip(selected = warehouseId == id, onClick = { warehouseId = id }, label = { Text(name) })
                        }
                    }
                } else if (choices.isEmpty()) {
                    Text("تعذّر تحميل المستودعات", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
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
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm(warehouseId!!, reason, quantity!!, note.trim()) },
                enabled = quantity != null && warehouseId != null,
            ) { Text("تسجيل") }
        },
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
            var wholesale by rememberSaveable(p.wholesalePrice) { mutableStateOf(p.wholesalePrice.orEmpty()) }
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                p.brand?.takeIf { it.isNotBlank() }?.let { Text("الماركة: $it", style = MaterialTheme.typography.bodySmall) }
                                Text("القسم: ${p.category.nameAr}")
                            }
                            GoldPill { Text("SKU ${p.skuNumber ?: "—"}", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                        }
                        SummaryRow("ظهور المنتج", p.visibility.label)
                        val context = LocalContext.current
                        val instagram = p.instagramUrl?.takeIf { it.isNotBlank() }
                        val video = p.videoUrl?.takeIf { it.isNotBlank() }
                        if (instagram != null || video != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (video != null) OutlinedButton(onClick = { openVideo(context, video) }) { Text("تشغيل الفيديو") }
                                if (instagram != null) OutlinedButton(onClick = { openLink(context, instagram) }) { Text("منشور إنستغرام") }
                            }
                        }
                        SummaryRow("التكلفة", Money.formatBase(p.costPrice))
                        SummaryRow("سعر التاجر", p.wholesalePrice?.let { Money.format(it) } ?: "غير محدد")
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
                                value = price, onValueChange = { price = it }, label = { Text("سعر البيع") }, singleLine = true,
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
                            OutlinedTextField(
                                value = wholesale, onValueChange = { wholesale = it }, label = { Text("سعر التاجر (ر.س)") }, singleLine = true,
                                supportingText = { Text("فارغ = التجار يدفعون سعر البيع") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                            )
                            val wholesaleValid = wholesale.isBlank() || wholesale.trim().toBigDecimalOrNull()?.let { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 2 } == true
                            val costValid = cost.isBlank() || cost.trim().toBigDecimalOrNull()?.let { it.signum() >= 0 && it.scale() <= 2 } == true
                            Button(
                                onClick = {
                                    vm.update(
                                        UpdateProductRequest(
                                            basePrice = price.trim(),
                                            compareAtPrice = compareAt.trim().ifBlank { null },
                                            costPrice = cost.trim().ifBlank { null },
                                            // "" clears it; an unset one stays unset.
                                            wholesalePrice = wholesale.trim().ifBlank { if (p.wholesalePrice != null) "" else null },
                                        ),
                                    )
                                },
                                enabled = !busy && price.toBigDecimalOrNull() != null && costValid && wholesaleValid,
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
