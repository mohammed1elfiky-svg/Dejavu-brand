@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.purchasing

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RecentItem
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import androidx.compose.material.icons.filled.Person
import com.dejavu.admin.ui.label
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.admin.ui.tint
import com.dejavu.core.admin.AdminCategory
import com.dejavu.core.admin.AdminProduct
import com.dejavu.core.admin.AdminVariant
import com.dejavu.core.admin.ImportCosts
import com.dejavu.core.admin.ImportItemRequest
import com.dejavu.core.admin.ImportNewProduct
import com.dejavu.core.admin.ImportNewVariant
import com.dejavu.core.admin.ImportOrderRequest
import com.dejavu.core.admin.ImportReceiveRequest
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.PendingItems
import com.dejavu.core.admin.PurchaseOrderDetail
import com.dejavu.core.admin.PurchaseOrderStatus
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.Supplier
import com.dejavu.core.admin.SupplierDetail
import com.dejavu.core.admin.SupplierKind
import com.dejavu.core.data.userMessage
import com.dejavu.core.admin.UpdateImportOrderRequest
import com.dejavu.core.admin.VariantPlanner
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** A typed non-negative number, or null. */
private fun num(text: String): BigDecimal? = text.trim().replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

private fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

private fun sar(v: String) = Money.formatBase(v)

/** At most this many size × colour combinations for one new item. */
private const val MAX_COMBINATIONS = 80

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null, integer: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (!integer && (it == '.' || it == ',')) }.take(14)) },
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        isError = value.isNotBlank() && num(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun Thumb(url: String?, size: Int = 52) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(Lux.Champagne), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp))
    }
}

@Composable
private fun ColorDot(hex: String) {
    val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
    Box(Modifier.size(16.dp).background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

// ============================================================== suppliers ===

class SuppliersViewModel(private val repo: PurchasingRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<Supplier>>>(Load.Loading)
    val state = _state.asStateFlow()

    /** Null = all accounts. */
    var kind by mutableStateOf<SupplierKind?>(null)
        private set

    fun filter(k: SupplierKind?) {
        kind = k
        _state.value = Load.Loading
        load()
    }

    fun load() {
        val k = kind
        viewModelScope.launch { _state.value = repo.suppliers(kind = k).map { it.items }.toLoad() }
    }

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) {
        val k = kind
        val res = repo.suppliers(kind = k).map { it.items }.toLoad()
        if (k == kind) _state.value = res
    }

    fun create(name: String, phone: String, commission: String, kind: SupplierKind, then: (String) -> Unit) {
        viewModelScope.launch {
            repo.createSupplier(name, phone, commission, kind).orSay()?.let {
                say("تمت إضافة ${it.name}")
                load()
                then(it.id)
            }
        }
    }
}

/** "مورد" (goods, yuan) or "شركة شحن" (freight and customs, dollars). */
internal val SupplierKind.kindLabel: String
    get() = when (this) {
        SupplierKind.SUPPLIER -> "مورد"
        SupplierKind.SHIPPING_COMPANY -> "شركة شحن"
    }

/** Suppliers with their balance; opens a supplier's file. */
@Composable
fun SuppliersScreen(canManage: Boolean, onBack: () -> Unit, openSupplier: (String) -> Unit) {
    val vm = adminViewModel { SuppliersViewModel(it.purchasing) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("الموردون والاستيراد", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canManage) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    containerColor = Lux.GoldDeep,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("حساب جديد") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.kind == null, onClick = { vm.filter(null) }, label = { Text("الكل") })
                FilterChip(selected = vm.kind == SupplierKind.SUPPLIER, onClick = { vm.filter(SupplierKind.SUPPLIER) }, label = { Text("الموردون") })
                FilterChip(selected = vm.kind == SupplierKind.SHIPPING_COMPANY, onClick = { vm.filter(SupplierKind.SHIPPING_COMPANY) }, label = { Text("شركات الشحن") })
            }
            LoadContent(state, onRetry = vm::load) { list ->
                RefreshList(refreshing, onRefresh = vm::refresh) {
                if (list.isEmpty()) {
                    val shipping = vm.kind == SupplierKind.SHIPPING_COMPANY
                    EmptyList(
                        Icons.Filled.Person,
                        if (shipping) "لا توجد شركات شحن بعد" else "لا يوجد موردون بعد",
                        if (shipping) "أضف شركة الشحن لتسجيل الشحن الخارجي والجمارك عليها" else "أضف موردك لتسجيل طلبيات الاستيراد وحسابه",
                        action = if (canManage) (if (shipping) "إضافة شركة شحن" else "إضافة مورد") else null,
                        onAction = { adding = true },
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.id }) { s ->
                            GlassCard(Modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 14.dp, onClick = { openSupplier(s.id) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(s.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            StatusChip(s.kind.kindLabel, if (s.kind == SupplierKind.SHIPPING_COMPANY) Lux.InkSoft else Lux.GoldDeep)
                                        }
                                        Text("${s.code}${s.phone?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                    }
                                    Text(sar(s.balance), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
    // Commission is billed in USD per period on the supplier's account, not as a percent.
    if (adding) {
        SupplierDialog(initialKind = vm.kind ?: SupplierKind.SUPPLIER, onDismiss = { adding = false }) { name, phone, kind ->
            adding = false
            vm.create(name, phone, "", kind, openSupplier)
        }
    }
}

@Composable
private fun SupplierDialog(initialKind: SupplierKind, onDismiss: () -> Unit, onSave: (String, String, SupplierKind) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    val ok = name.trim().length >= 2
    val shipping = kind == SupplierKind.SHIPPING_COMPANY
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (shipping) "شركة شحن جديدة" else "مورد جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("نوع الحساب", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !shipping, onClick = { kind = SupplierKind.SUPPLIER }, label = { Text("مورد") })
                    FilterChip(selected = shipping, onClick = { kind = SupplierKind.SHIPPING_COMPANY }, label = { Text("شركة شحن") })
                }
                Text(
                    if (shipping) "شركة الشحن: حسابها بالدولار، ويُسجَّل عليها الشحن الخارجي والجمارك عند وصول البضاعة"
                    else "المورد: حسابه باليوان، ويُسجَّل عليه سعر البضاعة والشحن الداخلي",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(150) },
                    label = { Text(if (shipping) "اسم شركة الشحن" else "اسم المورد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.take(30) },
                    label = { Text("الهاتف / واتساب (اختياري)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!shipping) Text("العمولة تُضاف بالدولار من كشف حساب المورد عند المطابقة", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, phone, kind) }, enabled = ok) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

// ========================================================== supplier file ===

class SupplierViewModel(private val id: String, private val repo: PurchasingRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<Pair<SupplierDetail, PendingItems>>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val supplier = async { repo.supplier(id) }
            val pending = async { repo.pendingItems(id) }
            _state.value = runCatching { supplier.await().getOrThrow() to pending.await().getOrThrow() }.toLoad()
        }
    }
}

/** A supplier's file: balance, the account statement, goods still being shipped and the orders. */
@Composable
fun SupplierScreen(
    id: String,
    canManage: Boolean,
    canOrder: Boolean,
    onBack: () -> Unit,
    openOrder: (String) -> Unit,
    newImport: (String) -> Unit,
    openAccount: (String) -> Unit,
) {
    val vm = adminViewModel(key = "supplier:$id") { SupplierViewModel(id, it.purchasing) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    val uiPrefs = adminContainer().uiPrefs
    val loaded = (state as? Load.Ready)?.value?.first
    LaunchedEffect(loaded?.id) {
        if (loaded != null) uiPrefs.addRecent(RecentItem("supplier", id, loaded.name, "${loaded.kind.kindLabel} · ${loaded.code}"))
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar((state as? Load.Ready)?.value?.first?.name ?: "المورد", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canOrder) {
                ExtendedFloatingActionButton(
                    onClick = { newImport(id) },
                    containerColor = Lux.GoldDeep,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("طلبية استيراد") },
                )
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { (s, pending) ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("${s.code}${s.phone?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        SummaryRow("الرصيد المستحق للمورد", sar(s.balance), strong = true, valueColor = Lux.GoldDeep)
                        Button(onClick = { openAccount(id) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("كشف حساب المورد") }
                        Text("سندات الصرف بالدولار وسعر اليوان، وعمولة المطابقة", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("البضائع قيد الشحن / الانتظار (${pending.totalPieces} قطعة)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                        if (pending.items.isNotEmpty()) {
                            TextButton(enabled = !exporting, onClick = {
                                exporting = true
                                scope.launch {
                                    runCatching {
                                        val rows = pending.items.map { ChecklistRow(it.product.nameAr, it.option, it.variant.sku, it.pendingQty, it.product.imageUrl) }
                                        sharePdf(context, checklistPdf(context, "بضائع قيد الانتظار — ${s.name}", "${pending.items.size} صنف · ${pending.totalPieces} قطعة", rows), "بضائع ${s.name}")
                                    }
                                    exporting = false
                                }
                            }) { Text(if (exporting) "جارٍ التجهيز…" else "PDF للمطابقة") }
                        }
                    }
                }
                if (pending.items.isEmpty()) {
                    item { Text("لا توجد بضائع معلقة لدى هذا المورد", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                }
                items(pending.items, key = { it.id }) { p ->
                    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp, onClick = { openOrder(p.purchaseOrder.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Thumb(p.product.imageUrl)
                            Column(Modifier.weight(1f)) {
                                Text(p.product.nameAr, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(p.option, p.variant.sku).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                Text("${p.purchaseOrder.number} · ${formatDate(p.purchaseOrder.orderDate)}${if (p.createdProduct) " · صنف جديد" else ""}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${p.pendingQty}", style = MaterialTheme.typography.titleLarge, color = Lux.GoldDeep)
                                p.unitPriceForeign?.let { Text("$it ${p.purchaseOrder.foreignCurrency ?: ""}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) }
                            }
                        }
                    }
                }
                if (s.purchaseOrders.isNotEmpty()) item { Text("الطلبيات", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep) }
                items(s.purchaseOrders, key = { "po:" + it.id }) { po ->
                    GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp, onClick = { openOrder(po.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(po.number, style = MaterialTheme.typography.titleSmall)
                                Text("${formatDate(po.orderDate)} · ${sar(po.grandTotal)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            }
                            StatusChip(po.status.label, po.status.tint())
                        }
                    }
                }
            }
        }
    }
}

// ========================================================= import lines ===

/** One line of the order being written or edited. */
sealed interface ImportLine {
    val key: String
    val pieces: Int
    val priceForeign: String

    /** Factory name / note shown on the supplier's statement. */
    val note: String

    /** An item that exists already (including new items an order being edited created). */
    data class Existing(
        val variantId: String,
        val name: String,
        val option: String,
        val sku: String,
        val imageUrl: String?,
        val quantity: String,
        override val priceForeign: String,
        override val note: String = "",
        val createdProduct: Boolean = false,
    ) : ImportLine {
        override val key get() = variantId
        override val pieces get() = quantity.toIntOrNull() ?: 0
    }

    data class New(
        val nameAr: String,
        val categoryId: String,
        val categoryName: String,
        val basePrice: String,
        override val priceForeign: String,
        val imageUrl: String?,
        val variants: List<ImportNewVariant>,
        override val note: String = "",
        override val key: String = "new:" + System.nanoTime(),
    ) : ImportLine {
        override val pieces get() = variants.sumOf { it.quantity }
    }
}

/** The lines of an import order and the item pickers, shared by writing and editing an order. */
abstract class ImportLinesViewModel(protected val inventory: InventoryRepository) : BaseViewModel() {
    private val _categories = MutableStateFlow<List<Pair<AdminCategory, Int>>>(emptyList())
    val categories = _categories.asStateFlow()
    private val _results = MutableStateFlow<List<AdminProduct>>(emptyList())
    val results = _results.asStateFlow()
    private val _uploading = MutableStateFlow(false)
    val uploading = _uploading.asStateFlow()

    var currency by mutableStateOf("CNY")
    val lines = mutableStateListOf<ImportLine>()

    protected fun loadCategories() {
        viewModelScope.launch { inventory.categories().getOrNull()?.let { _categories.value = it } }
    }

    fun search(q: String) {
        viewModelScope.launch { _results.value = inventory.products(q, pageSize = 30).getOrNull()?.items.orEmpty() }
    }

    fun addExisting(p: AdminProduct, v: AdminVariant) {
        if (lines.any { it.key == v.id }) return say("هذا المقاس مضاف بالفعل")
        lines += ImportLine.Existing(
            variantId = v.id,
            name = p.nameAr,
            option = listOfNotNull(v.size, v.color).joinToString(" · "),
            sku = v.sku,
            imageUrl = p.images.firstOrNull()?.url,
            quantity = "1",
            priceForeign = "",
        )
    }

    fun addNew(line: ImportLine.New) {
        lines += line
    }

    fun update(index: Int, line: ImportLine) {
        if (index in lines.indices) lines[index] = line
    }

    fun remove(index: Int) {
        if (index in lines.indices) lines.removeAt(index)
    }

    fun upload(bytes: ByteArray, then: (String) -> Unit) {
        _uploading.value = true
        viewModelScope.launch {
            inventory.uploadImage(bytes).orSay()?.let { then(it.url) }
            _uploading.value = false
        }
    }

    /** Goods value in the order currency. */
    fun goodsValue(): BigDecimal = lines.fold(BigDecimal.ZERO) { acc, l -> acc + (num(l.priceForeign) ?: BigDecimal.ZERO) * BigDecimal(l.pieces) }

    /** Says what is missing and returns false, or true when every line is complete. */
    protected fun linesValid(): Boolean {
        if (lines.isEmpty()) {
            say("أضف صنفاً واحداً على الأقل")
            return false
        }
        if (lines.any { it.pieces <= 0 }) {
            say("اكتب الكمية لكل صنف")
            return false
        }
        if (lines.any { num(it.priceForeign) == null }) {
            say("اكتب سعر الشراء لكل صنف")
            return false
        }
        return true
    }

    protected fun itemRequests(): List<ImportItemRequest> = lines.filterIsInstance<ImportLine.Existing>().map {
        ImportItemRequest(it.variantId, it.pieces, plain(num(it.priceForeign)!!), note = it.note.trim().ifBlank { null })
    }

    protected fun newProductRequests(): List<ImportNewProduct> = lines.filterIsInstance<ImportLine.New>().map {
        ImportNewProduct(it.categoryId, it.nameAr.trim(), it.basePrice.trim(), plain(num(it.priceForeign)!!), it.variants, imageUrl = it.imageUrl, note = it.note.trim().ifBlank { null })
    }
}

/** The "+ existing item / + new item" buttons and one card per line; [locked] (goods arrived) keeps lines and quantities as they are. */
private fun LazyListScope.importLineItems(vm: ImportLinesViewModel, addExisting: () -> Unit, addNew: () -> Unit, locked: Boolean = false) {
    if (!locked) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = addExisting, modifier = Modifier.weight(1f)) { Text("+ صنف موجود") }
                OutlinedButton(onClick = addNew, modifier = Modifier.weight(1f)) { Text("+ صنف جديد") }
            }
        }
    }
    if (vm.lines.isEmpty() && !locked) {
        item { Text("أضف الأصناف المطلوبة من المورد. الأصناف الجديدة تُحفظ كمسودة ولا تظهر في الكاشير أو المتجر إلا بعد الاستلام.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
    }
    itemsIndexed(vm.lines, key = { _, l -> l.key }) { i, l ->
        ImportLineCard(l, vm.currency, onChange = { vm.update(i, it) }, onRemove = { vm.remove(i) }, locked = locked)
    }
}

@Composable
private fun ImportLineCard(l: ImportLine, currency: String, onChange: (ImportLine) -> Unit, onRemove: () -> Unit, locked: Boolean = false) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (l) {
                is ImportLine.Existing -> Thumb(l.imageUrl, 44)
                is ImportLine.New -> Thumb(l.imageUrl, 44)
            }
            Column(Modifier.weight(1f)) {
                when (l) {
                    is ImportLine.Existing -> {
                        Text("${l.name}${if (l.createdProduct) " (صنف جديد)" else ""}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(l.option, l.sku).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    }
                    is ImportLine.New -> {
                        Text("${l.nameAr} (جديد)", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${l.categoryName} · بيع ${sar(l.basePrice)} · " + l.variants.joinToString("، ") { v -> listOfNotNull(v.size, v.color).joinToString(" ").ifBlank { "قطعة" } + " ×${v.quantity}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                        )
                    }
                }
            }
            if (!locked) IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                locked -> Text("${l.pieces} قطعة", Modifier.weight(1f).padding(top = 18.dp), style = MaterialTheme.typography.titleSmall)
                l is ImportLine.Existing -> NumberField("الكمية", l.quantity, Modifier.weight(1f), integer = true) { onChange(l.copy(quantity = it)) }
                else -> Text("${l.pieces} قطعة", Modifier.weight(1f).padding(top = 18.dp), style = MaterialTheme.typography.titleSmall)
            }
            NumberField("سعر القطعة ($currency)", l.priceForeign, Modifier.weight(1.3f)) { p ->
                onChange(
                    when (l) {
                        is ImportLine.Existing -> l.copy(priceForeign = p)
                        is ImportLine.New -> l.copy(priceForeign = p)
                    },
                )
            }
        }
        OutlinedTextField(
            value = l.note,
            onValueChange = { n ->
                val v = n.take(200)
                onChange(
                    when (l) {
                        is ImportLine.Existing -> l.copy(note = v)
                        is ImportLine.New -> l.copy(note = v)
                    },
                )
            },
            label = { Text("اسم المصنع / ملاحظة") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ===================================== invoice photo and shipping company ===

/** The bytes of a private file ("admin/files/<id>"), e.g. a supplier invoice photo, or null. */
internal suspend fun PurchasingRepository.privateFileBytes(url: String): ByteArray? =
    url.trimEnd('/').substringAfterLast('/').ifBlank { null }?.let { file(it).getOrNull() }

/** The supplier-invoice photo of an order being written or edited: uploaded at once, sent as invoiceImageUrl. */
class InvoicePhoto(private val scope: CoroutineScope, private val repo: PurchasingRepository, private val onError: (String) -> Unit) {
    var preview by mutableStateOf<ByteArray?>(null)
        private set

    /** "admin/files/<id>" once uploaded. */
    var url by mutableStateOf<String?>(null)
        private set
    var uploading by mutableStateOf(false)
        private set

    /** The photo the order had when it was opened. */
    private var saved: String? = null

    fun upload(bytes: ByteArray) {
        preview = bytes
        url = null
        uploading = true
        scope.launch {
            repo.uploadInvoiceImage(bytes).fold(
                { url = it.url },
                { e ->
                    preview = null
                    onError(e.userMessage())
                },
            )
            uploading = false
        }
    }

    fun remove() {
        url = null
        preview = null
    }

    /** Shows an order's saved photo. */
    fun showSaved(savedUrl: String?) {
        saved = savedUrl?.ifBlank { null }
        url = saved
        preview = null
        saved?.let { u -> scope.launch { val bytes = repo.privateFileBytes(u); if (url == u) preview = bytes } }
    }

    /** For an update: null = unchanged, "" = removed, else the new photo. */
    fun change(): String? = when (url) {
        saved -> null
        null -> ""
        else -> url
    }

    /** Clears it (after a purchase was saved). */
    fun clear() {
        saved = null
        remove()
    }
}

/** "صورة فاتورة المورد": camera or gallery, thumbnail with change / remove. */
@Composable
internal fun InvoicePhotoField(photo: InvoicePhoto, modifier: Modifier = Modifier) {
    val picker = rememberImagePicker { bytes -> photo.upload(bytes) }
    val preview = photo.preview
    val has = preview != null || photo.url != null
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (has) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Lux.Champagne), contentAlignment = Alignment.Center) {
                val bitmap = remember(preview) { preview?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                if (bitmap != null) Image(bitmap, contentDescription = "صورة فاتورة المورد", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (photo.uploading || (bitmap == null && photo.url != null)) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        photo.uploading -> "جارٍ رفع صورة الفاتورة…"
                        photo.url != null -> "صورة فاتورة المورد ✓"
                        else -> "صورة فاتورة المورد"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (photo.url != null) Success else Lux.InkSoft,
                )
                Row {
                    TextButton(onClick = picker.fromCamera, enabled = !photo.uploading) { Text("تغيير") }
                    TextButton(onClick = photo::remove, enabled = !photo.uploading) { Text("إزالة", color = MaterialTheme.colorScheme.error) }
                }
            }
        } else {
            Text("صورة فاتورة المورد", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = picker.fromCamera) { Text("تصوير") }
            TextButton(onClick = picker.fromGallery) { Text("من المعرض") }
        }
    }
}

/** Picks the shipping company (kind SHIPPING_COMPANY) owed the freight and customs; null = none. */
@Composable
internal fun ShippingCompanyField(companies: List<Supplier>, selectedId: String?, onPick: (String?) -> Unit, modifier: Modifier = Modifier, allowNone: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val selected = companies.firstOrNull { it.id == selectedId }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    selected != null -> "شركة الشحن: ${selected.name}"
                    selectedId != null -> "شركة الشحن: …"
                    else -> "اختر شركة الشحن"
                },
            )
        }
        Text("الشحن الخارجي والجمارك تُسجل على شركة الشحن بالدولار", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 2.dp))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("شركة الشحن") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (companies.isEmpty()) Text("أضف شركة شحن من شاشة الموردين (نوع الحساب: شركة شحن)", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    companies.forEach { c ->
                        Text(
                            c.name,
                            color = if (c.id == selectedId) Lux.GoldDeep else Color.Unspecified,
                            modifier = Modifier.fillMaxWidth().clickable { open = false; onPick(c.id) }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("إغلاق") } },
            dismissButton = if (allowNone && selectedId != null) {
                { TextButton(onClick = { open = false; onPick(null) }) { Text("بدون شركة") } }
            } else {
                null
            },
        )
    }
}

// ======================================================= new import order ===

class NewImportViewModel(
    private val presetSupplierId: String?,
    private val repo: PurchasingRepository,
    inventory: InventoryRepository,
) : ImportLinesViewModel(inventory) {
    private val _suppliers = MutableStateFlow<List<Supplier>>(emptyList())
    val suppliers = _suppliers.asStateFlow()
    private val _shippers = MutableStateFlow<List<Supplier>>(emptyList())

    /** Shipping companies (freight and customs, in dollars). */
    val shippers = _shippers.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created = _created.asSharedFlow()

    var supplierId by mutableStateOf(presetSupplierId)

    /** Optional: the rate is fixed by the payment vouchers. */
    var foreignPerUsd by mutableStateOf("")
    var sarPerUsd by mutableStateOf("")
    var domestic by mutableStateOf("")
    var invoiceNo by mutableStateOf("")
    var note by mutableStateOf("")
    var shippingCompanyId by mutableStateOf<String?>(null)
    val invoicePhoto = InvoicePhoto(viewModelScope, repo) { say(it) }

    /** The supplier's latest payment-voucher rate (used for the estimate when none is typed). */
    var lastRate by mutableStateOf<String?>(null)
        private set

    fun load() {
        viewModelScope.launch { repo.suppliers(kind = SupplierKind.SUPPLIER).getOrNull()?.items?.let { _suppliers.value = it } }
        viewModelScope.launch { repo.suppliers(kind = SupplierKind.SHIPPING_COMPANY).getOrNull()?.items?.let { list -> _shippers.value = list.filter { it.isActive } } }
        loadCategories()
        supplierId?.let(::loadLastRate)
    }

    private fun loadLastRate(id: String) {
        lastRate = null
        viewModelScope.launch { lastRate = repo.supplierAccount(id).getOrNull()?.lastRate }
    }

    fun pickSupplier(s: Supplier) {
        supplierId = s.id
        loadLastRate(s.id)
    }

    /** Goods value in the order currency and its rough SAR equivalent (before freight), when a rate is known. */
    fun estimate(): Pair<BigDecimal, BigDecimal?> {
        val value = goodsValue()
        val rate = (num(foreignPerUsd) ?: lastRate?.let(::num))?.takeIf { it.signum() > 0 } ?: return value to null
        val sarRate = num(sarPerUsd)?.takeIf { it.signum() > 0 } ?: BigDecimal("3.75")
        return value to (value + (num(domestic) ?: BigDecimal.ZERO)).multiply(sarRate).divide(rate, 2, java.math.RoundingMode.HALF_UP)
    }

    fun save() {
        val sid = supplierId ?: return say("اختر المورد")
        if (foreignPerUsd.isNotBlank() && num(foreignPerUsd)?.signum() != 1) return say("سعر صرف الدولار غير صحيح")
        if (!linesValid()) return
        if (invoicePhoto.uploading) return say("انتظر حتى يكتمل رفع صورة الفاتورة")
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val request = ImportOrderRequest(
                supplierId = sid,
                foreignCurrency = currency.trim().uppercase().ifBlank { null },
                foreignPerUsd = num(foreignPerUsd)?.takeIf { it.signum() > 0 }?.let(::plain),
                sarPerUsd = num(sarPerUsd)?.takeIf { it.signum() > 0 }?.let(::plain),
                domesticShipping = num(domestic)?.let(::plain),
                supplierInvoiceNo = invoiceNo.trim().ifBlank { null },
                note = note.trim().ifBlank { null },
                items = itemRequests(),
                newProducts = newProductRequests(),
                invoiceImageUrl = invoicePhoto.url,
                shippingCompanyId = shippingCompanyId,
            )
            repo.createImport(request).orSay()?.let {
                say("تم إنشاء طلبية ${it.number}")
                _created.tryEmit(it.id)
            }
            _busy.value = false
        }
    }
}

/** Writes an import order: existing items or brand-new ones, priced in the supplier's currency. */
@Composable
fun NewImportScreen(supplierId: String?, onBack: () -> Unit, created: (String) -> Unit) {
    val vm = adminViewModel(key = "import-new:${supplierId.orEmpty()}") { NewImportViewModel(supplierId, it.purchasing, it.inventory) }
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val shippers by vm.shippers.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickingSupplier by remember { mutableStateOf(false) }
    var addingExisting by remember { mutableStateOf(false) }
    var addingNew by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm) { vm.created.collect { created(it) } }
    val (value, sarEstimate) = vm.estimate()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("طلبية استيراد جديدة", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            GlassCard(Modifier.fillMaxWidth().padding(12.dp), contentPadding = 12.dp) {
                SummaryRow("قيمة البضاعة", "${plain(value)} ${vm.currency}")
                sarEstimate?.let { SummaryRow("تقديري بالريال (قبل الشحن الخارجي)", sar(it.toPlainString()), strong = true, valueColor = Lux.GoldDeep) }
                Text("قيمة البضاعة والشحن الداخلي تُسجَّل على حساب المورد بعملته الآن", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                Button(onClick = vm::save, enabled = !busy && !vm.invoicePhoto.uploading, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("حفظ الطلبية (تبقى لدى المورد حتى الاستلام)")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    val s = suppliers.firstOrNull { it.id == vm.supplierId }
                    OutlinedButton(onClick = { pickingSupplier = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(s?.let { "المورد: ${it.name}" } ?: "اختر المورد")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = vm.currency,
                            onValueChange = { vm.currency = it.filter(Char::isLetter).take(3).uppercase() },
                            label = { Text("العملة") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            "${vm.currency} لكل 1 دولار",
                            vm.foreignPerUsd,
                            Modifier.weight(1.4f),
                            hint = "اختياري — يُحدَّد من سند الصرف" + (vm.lastRate?.let { " (آخر سعر $it)" } ?: ""),
                        ) { vm.foreignPerUsd = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("ريال لكل 1 دولار", vm.sarPerUsd, Modifier.weight(1f), hint = "فارغ = سعر الإعدادات") { vm.sarPerUsd = it }
                        NumberField("الشحن الداخلي (${vm.currency})", vm.domestic, Modifier.weight(1f), hint = "داخل بلد المورد") { vm.domestic = it }
                    }
                    OutlinedTextField(
                        value = vm.invoiceNo,
                        onValueChange = { vm.invoiceNo = it.take(60) },
                        label = { Text("رقم فاتورة المورد (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    InvoicePhotoField(vm.invoicePhoto, Modifier.fillMaxWidth().padding(top = 4.dp))
                    ShippingCompanyField(shippers, vm.shippingCompanyId, onPick = { vm.shippingCompanyId = it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    OutlinedTextField(
                        value = vm.note,
                        onValueChange = { vm.note = it.take(500) },
                        label = { Text("ملاحظة على الطلبية (اختياري)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            importLineItems(vm, addExisting = { addingExisting = true }, addNew = { addingNew = true })
        }
    }

    if (pickingSupplier) {
        AlertDialog(
            onDismissRequest = { pickingSupplier = false },
            title = { Text("المورد") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (suppliers.isEmpty()) Text("أضف الموردين من شاشة الموردين أولاً")
                    suppliers.forEach { s ->
                        Text(
                            s.name,
                            modifier = Modifier.fillMaxWidth().clickable { pickingSupplier = false; vm.pickSupplier(s) }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingSupplier = false }) { Text("إغلاق") } },
        )
    }
    if (addingExisting) ExistingItemDialog(vm, onDismiss = { addingExisting = false })
    if (addingNew) NewItemDialog(vm, categories, onDismiss = { addingNew = false })
}

@Composable
private fun ExistingItemDialog(vm: ImportLinesViewModel, onDismiss: () -> Unit) {
    val results by vm.results.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { vm.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("صنف موجود") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("الاسم أو SKU أو الباركود") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    results.forEach { p ->
                        Text(p.nameAr, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.variants.filter { it.isActive }.forEach { v ->
                                FilterChip(selected = vm.lines.any { it.key == v.id }, onClick = { vm.addExisting(p, v) }, label = { Text(v.title) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}

/** Sizes × colours → one variant each, like the product editor; then a quantity per combination. */
@Composable
private fun NewItemDialog(vm: ImportLinesViewModel, categories: List<Pair<AdminCategory, Int>>, onDismiss: () -> Unit) {
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var basePrice by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var imageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var sizesText by rememberSaveable { mutableStateOf("") }
    var colorsText by rememberSaveable { mutableStateOf("") }
    var bulkQty by rememberSaveable { mutableStateOf("") }
    var pickingCategory by remember { mutableStateOf(false) }
    // Quantity typed per "size|color"; a combination not typed yet orders 1 piece.
    val quantities = remember { mutableStateMapOf<String, String>() }
    val picker = rememberImagePicker(maxSide = 1200) { bytes -> vm.upload(bytes) { imageUrl = it } }
    val category = categories.firstOrNull { it.first.id == categoryId }?.first
    val sizes = VariantPlanner.split(sizesText)
    val colors = VariantPlanner.split(colorsText)
    val combos = VariantPlanner.plan("item", sizes, colors)
    fun keyOf(p: VariantPlanner.Planned) = "${p.size.orEmpty()}|${p.color.orEmpty()}"
    val parsed = combos.map { p -> ImportNewVariant(p.size, p.color, p.colorHex, quantity = (quantities[keyOf(p)] ?: "1").toIntOrNull() ?: 0) }
    val ordered = parsed.filter { it.quantity > 0 }
    val blank = combos.any { (quantities[keyOf(it)] ?: "1").isBlank() }
    val ok = name.trim().length >= 2 && category != null && num(basePrice)?.signum() == 1 && num(price) != null &&
        ordered.isNotEmpty() && !blank && combos.size <= MAX_COMBINATIONS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("صنف جديد") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Thumb(imageUrl, 64)
                    OutlinedButton(onClick = picker.fromGallery, enabled = !uploading) {
                        if (uploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (imageUrl == null) "صورة الصنف" else "تغيير الصورة")
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, label = { Text("اسم الصنف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pickingCategory = true }, modifier = Modifier.fillMaxWidth()) { Text(category?.let { "القسم: ${it.nameAr}" } ?: "اختر القسم") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("سعر الشراء (${vm.currency})", price, Modifier.weight(1f)) { price = it }
                    NumberField("سعر البيع (ر.س)", basePrice, Modifier.weight(1f)) { basePrice = it }
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text("اسم المصنع / ملاحظة") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("المقاسات والألوان", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = sizesText,
                    onValueChange = { sizesText = it.take(300) },
                    label = { Text("المقاسات (افصل بفاصلة)") },
                    supportingText = { Text("مثال: S, M, L أو 38, 40 · فارغ = مقاس واحد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VariantPlanner.SIZE_PRESETS.forEach { (label, preset) ->
                        FilterChip(selected = false, onClick = { sizesText = preset.joinToString(", ") }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = colorsText,
                    onValueChange = { colorsText = it.take(300) },
                    label = { Text("الألوان (افصل بفاصلة)") },
                    supportingText = { Text("اختر من الألوان أدناه أو اكتب لوناً آخر · فارغ = بدون لون") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VariantPlanner.COLORS.forEach { (colorName, codeHex) ->
                        val selected = colorName in colors
                        FilterChip(
                            selected = selected,
                            onClick = { colorsText = (if (selected) colors - colorName else colors + colorName).joinToString("، ") },
                            label = { Text(colorName) },
                            leadingIcon = { ColorDot(codeHex.second) },
                        )
                    }
                }

                Text("الكمية لكل مقاس / لون (${combos.size})", style = MaterialTheme.typography.labelLarge)
                if (combos.size > MAX_COMBINATIONS) {
                    Text("عدد كبير من التركيبات — قلّل المقاسات أو الألوان (الحد $MAX_COMBINATIONS)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } else {
                    if (combos.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NumberField("كمية للكل", bulkQty, Modifier.weight(1f), integer = true) { bulkQty = it }
                            TextButton(enabled = bulkQty.toIntOrNull() != null, onClick = { combos.forEach { quantities[keyOf(it)] = bulkQty } }) { Text("تطبيق على الكل") }
                        }
                    }
                    combos.forEach { p ->
                        val k = keyOf(p)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            p.colorHex?.let { ColorDot(it) }
                            Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            NumberField("الكمية", quantities[k] ?: "1", Modifier.weight(0.8f), integer = true) { quantities[k] = it }
                        }
                    }
                    if (combos.size > 1) Text("اكتب 0 لتركيبة غير مطلوبة", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok && !uploading, onClick = {
                vm.addNew(ImportLine.New(name, category!!.id, category.nameAr, plain(num(basePrice)!!), price, imageUrl, ordered, note = note.trim()))
                onDismiss()
            }) { Text("إضافة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
    if (pickingCategory) {
        AlertDialog(
            onDismissRequest = { pickingCategory = false },
            title = { Text("القسم") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (categories.isEmpty()) Text("لا توجد أقسام بعد")
                    categories.forEach { (c, depth) ->
                        Text(
                            "${"   ".repeat(depth)}${c.nameAr}",
                            modifier = Modifier.fillMaxWidth().clickable { pickingCategory = false; categoryId = c.id }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingCategory = false }) { Text("إغلاق") } },
        )
    }
}

// ====================================================== edit import order ===

/** True when an import order can be changed: any status except cancelled (after arrival only prices, notes and costs). */
internal fun PurchaseOrderDetail.importEditable(): Boolean = isImport && status != PurchaseOrderStatus.CANCELLED

/** The goods (or some of them) arrived: lines and quantities stay as they are. */
internal fun PurchaseOrderDetail.importArrived(): Boolean =
    status == PurchaseOrderStatus.RECEIVED || status == PurchaseOrderStatus.PARTIALLY_RECEIVED || items.any { it.receivedQty > 0 }

class EditImportViewModel(
    private val id: String,
    private val repo: PurchasingRepository,
    inventory: InventoryRepository,
) : ImportLinesViewModel(inventory) {
    private val _order = MutableStateFlow<Load<PurchaseOrderDetail>>(Load.Loading)
    val order = _order.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()
    private val _shippers = MutableStateFlow<List<Supplier>>(emptyList())

    /** Shipping companies (freight and customs, in dollars). */
    val shippers = _shippers.asStateFlow()

    var domestic by mutableStateOf("")
    var invoiceNo by mutableStateOf("")
    var note by mutableStateOf("")
    var shippingCompanyId by mutableStateOf<String?>(null)
    val invoicePhoto = InvoicePhoto(viewModelScope, repo) { say(it) }

    /** Goods arrived: only prices, notes, inland shipping, the invoice and the shipping company change. */
    var locked by mutableStateOf(false)
        private set
    private var savedShippingCompanyId: String? = null
    private var filled = false

    fun load() {
        loadCategories()
        viewModelScope.launch { repo.suppliers(kind = SupplierKind.SHIPPING_COMPANY).getOrNull()?.items?.let { list -> _shippers.value = list.filter { it.isActive } } }
        viewModelScope.launch {
            val r = repo.order(id)
            r.getOrNull()?.let { po ->
                locked = po.importArrived()
                if (!filled) {
                    filled = true
                    currency = po.foreignCurrency ?: "CNY"
                    domestic = num(po.domesticShipping)?.let(::plain).orEmpty()
                    invoiceNo = po.supplierInvoiceNo.orEmpty()
                    note = po.note.orEmpty()
                    shippingCompanyId = po.shippingCompanyId
                    savedShippingCompanyId = po.shippingCompanyId
                    invoicePhoto.showSaved(po.invoiceImageUrl)
                    lines.clear()
                    lines.addAll(
                        po.items.map {
                            ImportLine.Existing(
                                variantId = it.variantId,
                                name = it.variant.product.nameAr,
                                option = it.option,
                                sku = it.variant.sku,
                                imageUrl = it.variant.product.images.firstOrNull()?.url,
                                quantity = it.quantity.toString(),
                                priceForeign = it.unitPriceForeign?.let(::num)?.let(::plain).orEmpty(),
                                note = it.note.orEmpty(),
                                createdProduct = it.createdProduct,
                            )
                        },
                    )
                }
            }
            _order.value = r.toLoad()
        }
    }

    fun save() {
        if (!linesValid()) return
        if (invoicePhoto.uploading) return say("انتظر حتى يكتمل رفع صورة الفاتورة")
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val request = UpdateImportOrderRequest(
                items = itemRequests(),
                // After arrival the lines stay as they are (no new items).
                newProducts = if (locked) emptyList() else newProductRequests(),
                domesticShipping = plain(num(domestic) ?: BigDecimal.ZERO),
                supplierInvoiceNo = invoiceNo.trim().ifBlank { null },
                note = note.trim().ifBlank { null },
                invoiceImageUrl = invoicePhoto.change(),
                shippingCompanyId = shippingCompanyId?.takeIf { it != savedShippingCompanyId },
            )
            repo.updateImport(id, request).orSay()?.let {
                say("تم حفظ تعديل الطلبية ${it.number}")
                _saved.tryEmit(it.id)
            }
            _busy.value = false
        }
    }
}

/** Changes an import order: before arrival lines, quantities, prices, notes and costs; after arrival prices, notes, inland shipping and the invoice. */
@Composable
fun EditImportScreen(id: String, onBack: () -> Unit, saved: () -> Unit) {
    val vm = adminViewModel(key = "import-edit:$id") { EditImportViewModel(id, it.purchasing, it.inventory) }
    val order by vm.order.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val shippers by vm.shippers.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var addingExisting by remember { mutableStateOf(false) }
    var addingNew by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm) { vm.saved.collect { saved() } }
    val editable = (order as? Load.Ready)?.value?.importEditable() == true

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar((order as? Load.Ready)?.value?.number?.let { "تعديل الطلبية $it" } ?: "تعديل الطلبية", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (editable) {
                GlassCard(Modifier.fillMaxWidth().padding(12.dp), contentPadding = 12.dp) {
                    SummaryRow("قيمة البضاعة", "${plain(vm.goodsValue())} ${vm.currency}", strong = true, valueColor = Lux.GoldDeep)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = vm::save, enabled = !busy && !vm.invoicePhoto.uploading, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("حفظ التعديلات") }
                }
            }
        },
    ) { padding ->
        LoadContent(order, onRetry = vm::load, modifier = Modifier.padding(padding)) { po ->
            if (!po.importEditable()) {
                Column(Modifier.padding(padding).padding(16.dp)) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("لا يمكن تعديل هذه الطلبية", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text("التعديل متاح لطلبيات الاستيراد غير الملغاة فقط.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("رجوع") }
                    }
                }
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (vm.locked) {
                        item {
                            GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
                                Text("البضاعة وصلت: يمكن تعديل الأسعار والملاحظات والشحن الداخلي وصورة الفاتورة فقط", style = MaterialTheme.typography.bodyMedium, color = Lux.GoldDeep)
                            }
                        }
                    }
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("${po.supplier.name} · ${formatDate(po.orderDate)} · ${vm.currency}", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                NumberField("الشحن الداخلي (${vm.currency})", vm.domestic, Modifier.weight(1f), hint = "داخل بلد المورد") { vm.domestic = it }
                                OutlinedTextField(
                                    value = vm.invoiceNo,
                                    onValueChange = { vm.invoiceNo = it.take(60) },
                                    label = { Text("رقم فاتورة المورد (اختياري)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            InvoicePhotoField(vm.invoicePhoto, Modifier.fillMaxWidth().padding(top = 4.dp))
                            ShippingCompanyField(
                                shippers,
                                vm.shippingCompanyId,
                                onPick = { vm.shippingCompanyId = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                allowNone = po.shippingCompanyId == null,
                            )
                            OutlinedTextField(
                                value = vm.note,
                                onValueChange = { vm.note = it.take(500) },
                                label = { Text("ملاحظة على الطلبية (اختياري)") },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                    importLineItems(vm, addExisting = { addingExisting = true }, addNew = { addingNew = true }, locked = vm.locked)
                }
            }
        }
    }
    if (addingExisting && !vm.locked) ExistingItemDialog(vm, onDismiss = { addingExisting = false })
    if (addingNew && !vm.locked) NewItemDialog(vm, categories, onDismiss = { addingNew = false })
}

// ============================================================== receiving ===

/** What was typed for one order line on arrival. */
data class CountRow(val itemId: String, val counted: String, val cbm: String)

class ImportReceiveViewModel(private val id: String, private val repo: PurchasingRepository) : BaseViewModel() {
    private val _order = MutableStateFlow<Load<PurchaseOrderDetail>>(Load.Loading)
    val order = _order.asStateFlow()
    private val _costs = MutableStateFlow<ImportCosts?>(null)

    /** Latest landed-cost preview for what is typed now. */
    val costs = _costs.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _done = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    private val _shippers = MutableStateFlow<List<Supplier>>(emptyList())

    /** Shipping companies (freight and customs, in dollars). */
    val shippers = _shippers.asStateFlow()

    val rows = mutableStateListOf<CountRow>()

    /** Owed the freight and customs; the order's own by default. */
    var shippingCompanyId by mutableStateOf<String?>(null)
    var totalCbm by mutableStateOf("")
    var cbmRate by mutableStateOf("")
    var foreignPerUsd by mutableStateOf("")
    var sarPerUsd by mutableStateOf("")
    var commission by mutableStateOf("")
    var domestic by mutableStateOf("")

    fun load() {
        viewModelScope.launch { repo.suppliers(kind = SupplierKind.SHIPPING_COMPANY).getOrNull()?.items?.let { list -> _shippers.value = list.filter { it.isActive } } }
        viewModelScope.launch {
            val r = repo.order(id)
            r.getOrNull()?.let { po ->
                if (rows.isEmpty()) {
                    rows.addAll(po.items.map { CountRow(it.id, it.quantity.toString(), "") })
                    shippingCompanyId = po.shippingCompanyId
                    foreignPerUsd = po.foreignPerUsd?.let { plain(BigDecimal(it)) }.orEmpty()
                    sarPerUsd = po.sarPerUsd?.let { plain(BigDecimal(it)) }.orEmpty()
                    commission = plain(BigDecimal(po.commissionPercent))
                    domestic = plain(BigDecimal(po.domesticShipping))
                }
            }
            _order.value = r.toLoad()
        }
    }

    /** Any edit makes the old preview stale. */
    fun edit(change: () -> Unit) {
        change()
        _costs.value = null
    }

    private fun request(): ImportReceiveRequest? {
        val rate = num(cbmRate)
        if (rate == null) {
            say("اكتب سعر شحن وجمارك المتر المكعب (بالدولار)")
            return null
        }
        if (rows.any { it.counted.toIntOrNull() == null }) {
            say("اكتب الكمية المستلمة لكل صنف")
            return null
        }
        return ImportReceiveRequest(
            items = rows.map { ImportReceiveRequest.Line(it.itemId, it.counted.toInt(), num(it.cbm)?.let(::plain)) },
            cbmRateUsd = plain(rate),
            totalCbm = num(totalCbm)?.let(::plain),
            foreignPerUsd = num(foreignPerUsd)?.takeIf { it.signum() > 0 }?.let(::plain),
            sarPerUsd = num(sarPerUsd)?.takeIf { it.signum() > 0 }?.let(::plain),
            commissionPercent = num(commission)?.let(::plain),
            domesticShipping = num(domestic)?.let(::plain),
            shippingCompanyId = shippingCompanyId,
        )
    }

    fun preview() {
        val req = request() ?: return
        act { repo.importCosts(id, req).orSay()?.let { _costs.value = it } }
    }

    fun receive() {
        val req = request() ?: return
        act {
            repo.importReceive(id, req).orSay()?.let {
                say("تم الاستلام: الأصناف الآن في المخزون والكاشير بتكلفتها الجديدة")
                _done.tryEmit(it.id)
            }
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

/** Arrival of an import shipment: count each line, enter the CBM, see the cost per piece, confirm. */
@Composable
fun ImportReceiveScreen(id: String, onBack: () -> Unit, done: () -> Unit) {
    val vm = adminViewModel(key = "import-receive:$id") { ImportReceiveViewModel(id, it.purchasing) }
    val order by vm.order.collectAsStateWithLifecycle()
    val costs by vm.costs.collectAsStateWithLifecycle()
    val shippers by vm.shippers.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm) { vm.done.collect { done() } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("استلام ومطابقة الشحنة", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            GlassCard(Modifier.fillMaxWidth().padding(12.dp), contentPadding = 12.dp) {
                costs?.let { c ->
                    SummaryRow("البضاعة + العمولة + الشحن الداخلي", sar(Money.parse(c.totals.goods).add(Money.parse(c.totals.commission)).add(Money.parse(c.totals.domestic)).toPlainString()))
                    SummaryRow("الشحن الخارجي والجمارك (${c.totals.cbm} م³)", sar(c.totals.freight))
                    SummaryRow("إجمالي التكلفة الواصلة", sar(c.totals.total), strong = true, valueColor = Lux.GoldDeep)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = vm::preview, enabled = !busy, modifier = Modifier.weight(1f)) { Text("احسب التكلفة") }
                    Button(onClick = { confirm = true }, enabled = !busy && costs != null, modifier = Modifier.weight(1.4f)) { Text("استلام وتأكيد المطابقة") }
                }
            }
        },
    ) { padding ->
        LoadContent(order, onRetry = vm::load, modifier = Modifier.padding(padding)) { po ->
            val cur = po.foreignCurrency ?: "CNY"
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("${po.number} · ${po.supplier.name}", style = MaterialTheme.typography.titleSmall)
                        Text("تكلفة القطعة = سعر الشراء بالريال + العمولة + نصيبها من الشحن الداخلي + نصيبها من الشحن الخارجي والجمارك حسب الـ CBM", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            NumberField("إجمالي CBM للشحنة", vm.totalCbm, Modifier.weight(1f), hint = "م³") { v -> vm.edit { vm.totalCbm = v } }
                            NumberField("سعر الـ CBM (\$)", vm.cbmRate, Modifier.weight(1f), hint = "شحن + جمارك") { v -> vm.edit { vm.cbmRate = v } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("$cur لكل 1 دولار", vm.foreignPerUsd, Modifier.weight(1f)) { v -> vm.edit { vm.foreignPerUsd = v } }
                            NumberField("ريال لكل 1 دولار", vm.sarPerUsd, Modifier.weight(1f)) { v -> vm.edit { vm.sarPerUsd = v } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("عمولة المورد %", vm.commission, Modifier.weight(1f)) { v -> vm.edit { vm.commission = v } }
                            NumberField("الشحن الداخلي ($cur)", vm.domestic, Modifier.weight(1f)) { v -> vm.edit { vm.domestic = v } }
                        }
                        ShippingCompanyField(shippers, vm.shippingCompanyId, onPick = { v -> vm.edit { vm.shippingCompanyId = v } }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
                item { Text("طابق الكميات الفعلية المستلمة", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep) }
                items(po.items, key = { it.id }) { line ->
                    val index = vm.rows.indexOfFirst { it.itemId == line.id }
                    val row = vm.rows.getOrNull(index)
                    val cost = costs?.lines?.firstOrNull { it.itemId == line.id }
                    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Thumb(line.variant.product.images.firstOrNull()?.url, 48)
                            Column(Modifier.weight(1f)) {
                                Text(line.variant.product.nameAr, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(line.option, line.variant.sku).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                Text("مطلوب ${line.quantity} · ${line.unitPriceForeign ?: "—"} $cur للقطعة", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                            }
                        }
                        if (row != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField("المستلم فعلاً", row.counted, Modifier.weight(1f), integer = true) { v -> vm.edit { vm.rows[index] = row.copy(counted = v) } }
                                NumberField("CBM الصنف (اختياري)", row.cbm, Modifier.weight(1f)) { v -> vm.edit { vm.rows[index] = row.copy(cbm = v) } }
                            }
                            val counted = row.counted.toIntOrNull()
                            if (counted != null && counted != line.quantity) {
                                Text(if (counted < line.quantity) "ناقص ${line.quantity - counted} قطعة" else "أكثر من المطلوب", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        cost?.let { c ->
                            if (c.quantity > 0) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("بضاعة ${sar(c.goods)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                    Text("عمولة ${sar(c.commission)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                    Text("داخلي ${sar(c.domestic)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                    Text("خارجي ${sar(c.freight)} (${c.cbm} م³)", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                }
                                SummaryRow("تكلفة القطعة الفعلية", sar(c.unitCost), strong = true, valueColor = Lux.GoldDeep)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("استلام وتأكيد المطابقة") },
            text = {
                val shipper = shippers.firstOrNull { it.id == vm.shippingCompanyId }?.name
                Text(
                    "ستدخل الكميات المستلمة إلى المخزون وتظهر في الكاشير والمتجر بالتكلفة المحسوبة. قيمة البضاعة على حساب المورد، " +
                        (if (shipper != null) "والشحن الخارجي والجمارك تُسجَّل على حساب $shipper بالدولار." else "ولم تُحدَّد شركة شحن للشحن الخارجي والجمارك.") +
                        " لا يمكن تعديل كميات الاستلام بعد التأكيد.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; vm.receive() }) { Text("تأكيد الاستلام") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("تراجع") } },
        )
    }
}
