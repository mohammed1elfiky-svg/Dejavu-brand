@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.purchasing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.Supplier
import com.dejavu.core.admin.SupplierDetail
import com.dejavu.core.admin.UpdateSupplierRequest
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
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

// ============================================================== suppliers ===

class SuppliersViewModel(private val repo: PurchasingRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<Supplier>>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch { _state.value = repo.suppliers().map { it.items }.toLoad() }
    }

    fun create(name: String, phone: String, commission: String, then: (String) -> Unit) {
        viewModelScope.launch {
            repo.createSupplier(name, phone, commission).orSay()?.let {
                say("تمت إضافة ${it.name}")
                load()
                then(it.id)
            }
        }
    }
}

/** Suppliers with their balance and commission; opens a supplier's file. */
@Composable
fun SuppliersScreen(canManage: Boolean, onBack: () -> Unit, openSupplier: (String) -> Unit) {
    val vm = adminViewModel { SuppliersViewModel(it.purchasing) }
    val state by vm.state.collectAsStateWithLifecycle()
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
                    text = { Text("مورد جديد") },
                )
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            if (list.isEmpty()) {
                EmptyState(Icons.Filled.Add, "لا يوجد موردون بعد", modifier = Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { s ->
                        GlassCard(Modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 14.dp, onClick = { openSupplier(s.id) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${s.code}${s.phone?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(sar(s.balance), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                                    if (!Money.isZero(s.commissionPercent)) Text("عمولة ${s.commissionPercent}%", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) SupplierDialog(onDismiss = { adding = false }) { name, phone, commission -> adding = false; vm.create(name, phone, commission, openSupplier) }
}

@Composable
private fun SupplierDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var commission by rememberSaveable { mutableStateOf("") }
    val ok = name.trim().length >= 2 && (commission.isBlank() || num(commission)?.let { it < BigDecimal(100) } == true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مورد جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(150) }, label = { Text("اسم المورد") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.take(30) },
                    label = { Text("الهاتف / واتساب (اختياري)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberField("نسبة عمولة المورد %", commission, Modifier.fillMaxWidth(), hint = "تُضاف إلى تكلفة البضاعة المستوردة منه") { commission = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, phone, commission) }, enabled = ok) { Text("حفظ") } },
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

    fun setCommission(percent: String) {
        viewModelScope.launch {
            repo.updateSupplier(id, UpdateSupplierRequest(commissionPercent = percent.ifBlank { "0" })).orSay()?.let {
                say("تم حفظ نسبة العمولة")
                load()
            }
        }
    }
}

/** A supplier's file: balance, commission, goods still being shipped and the orders. */
@Composable
fun SupplierScreen(id: String, canManage: Boolean, canOrder: Boolean, onBack: () -> Unit, openOrder: (String) -> Unit, newImport: (String) -> Unit) {
    val vm = adminViewModel(key = "supplier:$id") { SupplierViewModel(id, it.purchasing) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
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
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("${s.code}${s.phone?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        SummaryRow("الرصيد المستحق للمورد", sar(s.balance), strong = true, valueColor = Lux.GoldDeep)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("نسبة العمولة: ${s.commissionPercent}%", Modifier.weight(1f))
                            if (canManage) TextButton(onClick = { editing = true }) { Text("تعديل") }
                        }
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
            if (editing) {
                var value by rememberSaveable { mutableStateOf(s.commissionPercent) }
                AlertDialog(
                    onDismissRequest = { editing = false },
                    title = { Text("نسبة عمولة المورد") },
                    text = { NumberField("النسبة %", value, Modifier.fillMaxWidth()) { value = it } },
                    confirmButton = {
                        TextButton(enabled = value.isBlank() || num(value)?.let { it < BigDecimal(100) } == true, onClick = { editing = false; vm.setCommission(value) }) { Text("حفظ") }
                    },
                    dismissButton = { TextButton(onClick = { editing = false }) { Text("إلغاء") } },
                )
            }
        }
    }
}

// ======================================================= new import order ===

/** One line of the order being written. */
sealed interface ImportLine {
    val key: String
    val pieces: Int
    val priceForeign: String

    data class Existing(val product: AdminProduct, val variant: AdminVariant, val quantity: String, override val priceForeign: String) : ImportLine {
        override val key get() = variant.id
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
        override val key: String = "new:" + System.nanoTime(),
    ) : ImportLine {
        override val pieces get() = variants.sumOf { it.quantity }
    }
}

class NewImportViewModel(
    private val presetSupplierId: String?,
    private val repo: PurchasingRepository,
    private val inventory: InventoryRepository,
) : BaseViewModel() {
    private val _suppliers = MutableStateFlow<List<Supplier>>(emptyList())
    val suppliers = _suppliers.asStateFlow()
    private val _categories = MutableStateFlow<List<Pair<AdminCategory, Int>>>(emptyList())
    val categories = _categories.asStateFlow()
    private val _results = MutableStateFlow<List<AdminProduct>>(emptyList())
    val results = _results.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _uploading = MutableStateFlow(false)
    val uploading = _uploading.asStateFlow()
    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created = _created.asSharedFlow()

    var supplierId by mutableStateOf(presetSupplierId)
    var currency by mutableStateOf("CNY")
    var foreignPerUsd by mutableStateOf("7.2")
    var sarPerUsd by mutableStateOf("")
    var commission by mutableStateOf("")
    var domestic by mutableStateOf("")
    var invoiceNo by mutableStateOf("")
    val lines = mutableStateListOf<ImportLine>()

    fun load() {
        viewModelScope.launch {
            repo.suppliers().getOrNull()?.items?.let { list ->
                _suppliers.value = list
                list.firstOrNull { it.id == supplierId }?.let { if (commission.isBlank()) commission = it.commissionPercent }
            }
        }
        viewModelScope.launch { inventory.categories().getOrNull()?.let { _categories.value = it } }
    }

    fun pickSupplier(s: Supplier) {
        supplierId = s.id
        commission = s.commissionPercent
    }

    fun search(q: String) {
        viewModelScope.launch { _results.value = inventory.products(q, pageSize = 30).getOrNull()?.items.orEmpty() }
    }

    fun addExisting(p: AdminProduct, v: AdminVariant) {
        if (lines.any { it.key == v.id }) return say("هذا المقاس مضاف بالفعل")
        lines += ImportLine.Existing(p, v, "1", "")
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

    /** Goods value in the order currency and its rough SAR equivalent (before freight). */
    fun estimate(): Pair<BigDecimal, BigDecimal?> {
        val value = lines.fold(BigDecimal.ZERO) { acc, l -> acc + (num(l.priceForeign) ?: BigDecimal.ZERO) * BigDecimal(l.pieces) }
        val rate = num(foreignPerUsd)?.takeIf { it.signum() > 0 } ?: return value to null
        val sarRate = num(sarPerUsd)?.takeIf { it.signum() > 0 } ?: BigDecimal("3.75")
        val pct = (num(commission) ?: BigDecimal.ZERO).divide(BigDecimal(100))
        return value to (value * (BigDecimal.ONE + pct) + (num(domestic) ?: BigDecimal.ZERO)).multiply(sarRate).divide(rate, 2, java.math.RoundingMode.HALF_UP)
    }

    fun save() {
        val sid = supplierId ?: return say("اختر المورد")
        if (lines.isEmpty()) return say("أضف صنفاً واحداً على الأقل")
        if (num(foreignPerUsd)?.signum() != 1) return say("اكتب سعر صرف الدولار بعملة المورد")
        if (lines.any { it.pieces <= 0 }) return say("اكتب الكمية لكل صنف")
        if (lines.any { num(it.priceForeign) == null }) return say("اكتب سعر الشراء لكل صنف")
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val request = ImportOrderRequest(
                supplierId = sid,
                foreignCurrency = currency.trim().uppercase().ifBlank { null },
                foreignPerUsd = plain(num(foreignPerUsd)!!),
                sarPerUsd = num(sarPerUsd)?.takeIf { it.signum() > 0 }?.let(::plain),
                commissionPercent = num(commission)?.let(::plain),
                domesticShipping = num(domestic)?.let(::plain),
                supplierInvoiceNo = invoiceNo.trim().ifBlank { null },
                items = lines.filterIsInstance<ImportLine.Existing>().map { ImportItemRequest(it.variant.id, it.pieces, plain(num(it.priceForeign)!!)) },
                newProducts = lines.filterIsInstance<ImportLine.New>().map {
                    ImportNewProduct(it.categoryId, it.nameAr.trim(), it.basePrice.trim(), plain(num(it.priceForeign)!!), it.variants, imageUrl = it.imageUrl)
                },
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
                Button(onClick = vm::save, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
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
                        NumberField("${vm.currency} لكل 1 دولار", vm.foreignPerUsd, Modifier.weight(1.4f)) { vm.foreignPerUsd = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("ريال لكل 1 دولار", vm.sarPerUsd, Modifier.weight(1f), hint = "فارغ = سعر الإعدادات") { vm.sarPerUsd = it }
                        NumberField("عمولة المورد %", vm.commission, Modifier.weight(1f)) { vm.commission = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("الشحن الداخلي (${vm.currency})", vm.domestic, Modifier.weight(1f), hint = "داخل بلد المورد") { vm.domestic = it }
                        OutlinedTextField(
                            value = vm.invoiceNo,
                            onValueChange = { vm.invoiceNo = it.take(60) },
                            label = { Text("رقم فاتورة المورد") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { addingExisting = true }, modifier = Modifier.weight(1f)) { Text("+ صنف موجود") }
                    OutlinedButton(onClick = { addingNew = true }, modifier = Modifier.weight(1f)) { Text("+ صنف جديد") }
                }
            }
            if (vm.lines.isEmpty()) {
                item { Text("أضف الأصناف المطلوبة من المورد. الأصناف الجديدة تُحفظ كمسودة ولا تظهر في الكاشير أو المتجر إلا بعد الاستلام.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
            }
            itemsIndexed(vm.lines, key = { _, l -> l.key }) { i, l ->
                GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (l) {
                            is ImportLine.Existing -> Thumb(l.product.images.firstOrNull()?.url, 44)
                            is ImportLine.New -> Thumb(l.imageUrl, 44)
                        }
                        Column(Modifier.weight(1f)) {
                            when (l) {
                                is ImportLine.Existing -> {
                                    Text(l.product.nameAr, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${l.variant.title} · ${l.variant.sku}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
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
                        IconButton(onClick = { vm.remove(i) }) { Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (l is ImportLine.Existing) {
                            NumberField("الكمية", l.quantity, Modifier.weight(1f), integer = true) { vm.update(i, l.copy(quantity = it)) }
                        } else {
                            Text("${l.pieces} قطعة", Modifier.weight(1f).padding(top = 18.dp), style = MaterialTheme.typography.titleSmall)
                        }
                        NumberField("سعر القطعة (${vm.currency})", l.priceForeign, Modifier.weight(1.3f)) { p ->
                            vm.update(i, if (l is ImportLine.Existing) l.copy(priceForeign = p) else (l as ImportLine.New).copy(priceForeign = p))
                        }
                    }
                }
            }
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
                            "${s.name}${if (!Money.isZero(s.commissionPercent)) " · عمولة ${s.commissionPercent}%" else ""}",
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
private fun ExistingItemDialog(vm: NewImportViewModel, onDismiss: () -> Unit) {
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

@Composable
private fun NewItemDialog(vm: NewImportViewModel, categories: List<Pair<AdminCategory, Int>>, onDismiss: () -> Unit) {
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var basePrice by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var imageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingCategory by remember { mutableStateOf(false) }
    val variants = remember { mutableStateListOf(Triple("", "", "1")) }
    val picker = rememberImagePicker(maxSide = 1200) { bytes -> vm.upload(bytes) { imageUrl = it } }
    val category = categories.firstOrNull { it.first.id == categoryId }?.first
    val parsed = variants.map { (size, color, qty) -> ImportNewVariant(size.trim().ifBlank { null }, color.trim().ifBlank { null }, quantity = qty.toIntOrNull() ?: 0) }
    val ok = name.trim().length >= 2 && category != null && num(basePrice)?.signum() == 1 && num(price) != null && parsed.all { it.quantity > 0 } &&
        parsed.map { it.size to it.color }.toSet().size == parsed.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("صنف جديد") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text("المقاسات والألوان والكميات", style = MaterialTheme.typography.labelLarge)
                variants.forEachIndexed { i, (size, color, qty) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = size, onValueChange = { variants[i] = Triple(it.take(30), color, qty) }, label = { Text("المقاس") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = color, onValueChange = { variants[i] = Triple(size, it.take(40), qty) }, label = { Text("اللون") }, singleLine = true, modifier = Modifier.weight(1f))
                        NumberField("الكمية", qty, Modifier.weight(0.8f), integer = true) { variants[i] = Triple(size, color, it) }
                        if (variants.size > 1) IconButton(onClick = { variants.removeAt(i) }) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
                    }
                }
                TextButton(onClick = { variants += Triple("", "", "1") }) { Text("+ مقاس / لون آخر") }
            }
        },
        confirmButton = {
            TextButton(enabled = ok && !uploading, onClick = {
                vm.addNew(ImportLine.New(name, category!!.id, category.nameAr, plain(num(basePrice)!!), price, imageUrl, parsed))
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

    val rows = mutableStateListOf<CountRow>()
    var totalCbm by mutableStateOf("")
    var cbmRate by mutableStateOf("")
    var foreignPerUsd by mutableStateOf("")
    var sarPerUsd by mutableStateOf("")
    var commission by mutableStateOf("")
    var domestic by mutableStateOf("")

    fun load() {
        viewModelScope.launch {
            val r = repo.order(id)
            r.getOrNull()?.let { po ->
                if (rows.isEmpty()) {
                    rows.addAll(po.items.map { CountRow(it.id, it.quantity.toString(), "") })
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
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            text = { Text("ستدخل الكميات المستلمة إلى المخزون وتظهر في الكاشير والمتجر بالتكلفة المحسوبة، وتُسجَّل على حساب المورد. لا يمكن تعديل الاستلام بعد التأكيد.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.receive() }) { Text("تأكيد الاستلام") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("تراجع") } },
        )
    }
}
