@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.purchasing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.barcode.rememberBarcodeScanner
import com.dejavu.admin.ui.pos.LuxButton
import com.dejavu.admin.ui.pos.decimalInput
import com.dejavu.admin.ui.pos.validAmount
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.LedgerAccount
import com.dejavu.core.admin.PosProduct
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.PosVariant
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.QuickPurchaseItem
import com.dejavu.core.admin.QuickPurchaseRequest
import com.dejavu.core.admin.Supplier
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

// ------------------------------------------------------------------ model ---

/** One variant on the purchase invoice; [cost] is the typed SAR unit cost. */
data class PurchaseLine(val product: PosProduct, val variant: PosVariant, val qty: Int, val cost: String = "") {
    val unitCost: BigDecimal? get() = validAmount(cost, Currency.SAR)
    val lineTotal: BigDecimal get() = (unitCost ?: BigDecimal.ZERO).multiply(BigDecimal(qty)).setScale(2, RoundingMode.HALF_UP)
}

/** How the supplier is paid now. */
enum class PurchasePay(val label: String, val wire: String?) {
    ON_ACCOUNT("على الحساب (آجل)", null),
    CASH("نقداً", "CASH"),
    BANK("تحويل بنكي", "BANK_TRANSFER"),
}

private const val MAX_QTY = 100_000

private fun sar(v: BigDecimal): String = Money.formatBase(v.toPlainString())

// -------------------------------------------------------------- viewmodel ---

class NewPurchaseViewModel(
    private val pos: PosRepository,
    private val purchasing: PurchasingRepository,
    private val finance: FinanceRepository,
) : BaseViewModel() {
    var products by mutableStateOf<Load<List<PosProduct>>>(Load.Loading)
        private set
    var query by mutableStateOf("")
        private set

    var lines by mutableStateOf<List<PurchaseLine>>(emptyList())
        private set

    var supplier by mutableStateOf<Supplier?>(null)
        private set
    var supplierQuery by mutableStateOf("")
        private set
    var supplierResults by mutableStateOf<List<Supplier>>(emptyList())
        private set

    var invoiceNo by mutableStateOf("")
    var shipping by mutableStateOf("")
    var note by mutableStateOf("")
    var receiveNow by mutableStateOf(true)
    var pay by mutableStateOf(PurchasePay.ON_ACCOUNT)
        private set
    /** Paid now (SAR); blank = the whole invoice. */
    var payAmount by mutableStateOf("")
    var accounts by mutableStateOf<List<LedgerAccount>>(emptyList())
        private set
    var accountId by mutableStateOf<String?>(null)

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    private var searchJob: Job? = null
    private var supplierJob: Job? = null

    init {
        load()
        searchSuppliers("")
        viewModelScope.launch { accounts = finance.cashAccounts() }
    }

    // ---- products

    fun load() {
        if (products !is Load.Ready) products = Load.Loading
        viewModelScope.launch { fetch() }
    }

    fun search(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            fetch()
        }
    }

    private suspend fun fetch() {
        pos.products(query).fold(
            { products = Load.Ready(it) },
            { e -> if (products is Load.Ready) say(e.userMessage()) else products = Load.Failed(e.userMessage()) },
        )
    }

    /** Tile tap: a single-variant product goes straight onto the invoice; others open the picker. */
    fun tap(product: PosProduct, pick: (PosProduct) -> Unit) {
        when {
            product.variants.isEmpty() -> say("لا توجد مقاسات أو ألوان لهذا المنتج")
            product.variants.size == 1 -> add(product, product.variants.first())
            else -> pick(product)
        }
    }

    fun scan(code: String, pick: (PosProduct) -> Unit) {
        viewModelScope.launch {
            pos.lookup(code).orSay()?.let { found ->
                val variant = found.variantId?.let { id -> found.product.variants.firstOrNull { it.id == id } }
                if (variant != null) add(found.product, variant) else tap(found.product, pick)
            }
        }
    }

    // ---- invoice lines

    fun qtyOf(variantId: String): Int = lines.firstOrNull { it.variant.id == variantId }?.qty ?: 0

    fun add(product: PosProduct, variant: PosVariant) {
        error = null
        val have = qtyOf(variant.id)
        lines = if (have == 0) {
            // A new size of a product already on the invoice starts at that product's cost.
            val cost = lines.firstOrNull { it.product.id == product.id && it.unitCost != null }?.cost.orEmpty()
            lines + PurchaseLine(product, variant, 1, cost)
        } else {
            lines.map { if (it.variant.id == variant.id) it.copy(qty = (it.qty + 1).coerceAtMost(MAX_QTY)) else it }
        }
    }

    /** One piece of every size / color of [product] that is not on the invoice yet. */
    fun addAll(product: PosProduct) {
        product.variants.filter { qtyOf(it.id) == 0 }.forEach { add(product, it) }
    }

    fun setQty(variantId: String, qty: Int) {
        error = null
        lines = if (qty <= 0) {
            lines.filterNot { it.variant.id == variantId }
        } else {
            lines.map { if (it.variant.id == variantId) it.copy(qty = qty.coerceAtMost(MAX_QTY)) else it }
        }
    }

    fun setCost(variantId: String, text: String) {
        error = null
        val clean = decimalInput(text).take(14)
        lines = lines.map { if (it.variant.id == variantId) it.copy(cost = clean) else it }
    }

    /** Copies [cost] to every line of [productId] (all its sizes and colors). */
    fun applyCostToProduct(productId: String, cost: String) {
        lines = lines.map { if (it.product.id == productId) it.copy(cost = cost) else it }
    }

    fun clear() {
        lines = emptyList()
        error = null
    }

    // ---- supplier

    fun searchSuppliers(q: String) {
        supplierQuery = q
        supplierJob?.cancel()
        supplierJob = viewModelScope.launch {
            if (q.isNotBlank()) delay(300)
            supplierResults = purchasing.suppliers(q.ifBlank { null }).getOrNull()?.items.orEmpty()
                .filter { it.isActive }.take(30)
        }
    }

    fun pickSupplier(s: Supplier) {
        supplier = s
        supplierQuery = ""
        error = null
    }

    fun clearSupplier() {
        supplier = null
        searchSuppliers("")
    }

    fun createSupplier(name: String, phone: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            purchasing.createSupplier(name, phone).fold(
                {
                    pickSupplier(it)
                    say("تمت إضافة المورد ${it.name}")
                },
                { e -> error = e.userMessage() },
            )
            busy = false
        }
    }

    // ---- payment

    fun choosePay(p: PurchasePay) {
        pay = p
        error = null
        if (p != PurchasePay.ON_ACCOUNT) accountId = defaultAccount(p)?.id
    }

    /** First account whose name looks like a cash box (for cash) or a bank (for transfers). */
    private fun defaultAccount(p: PurchasePay): LedgerAccount? {
        val words = if (p == PurchasePay.BANK) listOf("بنك", "مصرف", "bank") else listOf("صندوق", "نقد", "خزينة", "خزنة", "cash")
        return accounts.firstOrNull { a -> words.any { w -> a.nameAr.contains(w, ignoreCase = true) || a.nameEn.contains(w, ignoreCase = true) } }
            ?: accounts.firstOrNull()
    }

    // ---- totals

    val pieces: Int get() = lines.sumOf { it.qty }
    val subtotal: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal) }
    val shippingValue: BigDecimal get() = shipping.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ZERO
    val grandTotal: BigDecimal get() = subtotal.add(shippingValue).setScale(2, RoundingMode.HALF_UP)

    val shippingValid: Boolean get() = shipping.isBlank() || validAmount(shipping, Currency.SAR) != null
    val costsValid: Boolean get() = lines.all { it.unitCost != null }
    val payAmountValid: Boolean
        get() = pay == PurchasePay.ON_ACCOUNT || payAmount.isBlank() ||
            validAmount(payAmount, Currency.SAR)?.let { it <= grandTotal } == true

    /** Why the invoice cannot be issued yet; null when it can. */
    val blocker: String?
        get() = when {
            lines.isEmpty() -> "أضف أصنافاً من الشبكة أو امسح الباركود"
            supplier == null -> "اختر المورد"
            !costsValid -> "أدخل تكلفة الوحدة لكل صنف"
            !shippingValid -> "تكلفة الشحن غير صحيحة"
            !payAmountValid -> "المبلغ المدفوع يجب ألا يتجاوز الإجمالي"
            pay != PurchasePay.ON_ACCOUNT && accounts.isNotEmpty() && accountId == null -> "اختر الصندوق أو الحساب البنكي"
            else -> null
        }

    fun submit(created: (String) -> Unit) {
        if (busy || blocker != null) return
        val s = supplier ?: return
        val paid = validAmount(payAmount, Currency.SAR)
        val request = QuickPurchaseRequest(
            supplierId = s.id,
            items = lines.map { QuickPurchaseItem(it.variant.id, it.qty, it.unitCost!!.stripTrailingZeros().toPlainString()) },
            supplierInvoiceNo = invoiceNo.trim().ifBlank { null },
            shippingCost = validAmount(shipping, Currency.SAR)?.stripTrailingZeros()?.toPlainString(),
            note = note.trim().ifBlank { null },
            receiveNow = receiveNow,
            payMethod = pay.wire,
            payAmount = if (pay == PurchasePay.ON_ACCOUNT) null else paid?.stripTrailingZeros()?.toPlainString(),
            payAccountId = if (pay == PurchasePay.ON_ACCOUNT) null else accountId,
        )
        busy = true
        error = null
        viewModelScope.launch {
            purchasing.quickPurchase(request).fold(
                { po ->
                    say("تم إصدار فاتورة الشراء ${po.number}")
                    lines = emptyList()
                    created(po.id)
                },
                { e -> error = e.userMessage() },
            )
            busy = false
        }
    }
}

// ----------------------------------------------------------------- screen ---

/** Till-style purchase: product grid on one side, the supplier's invoice on the other. */
@Composable
fun NewPurchaseScreen(onBack: () -> Unit, created: (String) -> Unit) {
    val vm = adminViewModel { NewPurchaseViewModel(it.pos, it.purchasing, it.finance) }
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<PosProduct?>(null) }
    var showInvoice by rememberSaveable { mutableStateOf(false) }
    var newSupplier by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    val scan = rememberBarcodeScanner { code -> vm.scan(code) { picking = it } }
    val onCreated: (String) -> Unit = { id ->
        showInvoice = false
        created(id)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("فاتورة شراء جديدة", onBack) {
                TextButton(onClick = scan) { Text("باركود") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = vm::search,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = if (vm.query.isNotEmpty()) ({
                            IconButton(onClick = { vm.search("") }) { Icon(Icons.Filled.Close, contentDescription = "مسح") }
                        }) else null,
                        placeholder = { Text("ابحث بالاسم أو رقم الصنف") },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LoadContent(vm.products, onRetry = vm::load, modifier = Modifier.fillMaxSize()) { list ->
                            if (list.isEmpty()) {
                                EmptyState(Icons.Filled.Search, "لا توجد منتجات مطابقة")
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(150.dp),
                                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(list, key = { it.id }) { p ->
                                        val onInvoice = p.variants.sumOf { vm.qtyOf(it.id) }
                                        PurchaseTile(p, onInvoice) { vm.tap(p) { picking = it } }
                                    }
                                }
                            }
                        }
                    }
                    if (!wide && vm.lines.isNotEmpty()) {
                        InvoiceBar(vm) { showInvoice = true }
                    }
                }
                if (wide) {
                    GlassCard(Modifier.width(440.dp).fillMaxHeight().padding(8.dp), contentPadding = 0.dp) {
                        Text(
                            "فاتورة الشراء",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        )
                        InvoiceContent(vm, onNewSupplier = { newSupplier = true }, onCreated = onCreated, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    picking?.let { p ->
        PurchaseVariantPicker(p, vm, onDismiss = { picking = null })
    }

    if (showInvoice) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showInvoice = false }, sheetState = sheet, containerColor = Lux.Pearl) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("فاتورة الشراء", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { showInvoice = false }) { Icon(Icons.Filled.Close, contentDescription = "إغلاق") }
            }
            InvoiceContent(
                vm,
                onNewSupplier = { newSupplier = true },
                onCreated = onCreated,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }

    if (newSupplier) {
        QuickSupplierDialog(onDismiss = { newSupplier = false }) { name, phone -> vm.createSupplier(name, phone) }
    }
}

// ------------------------------------------------------------------ grid ---

@Composable
private fun PurchaseTile(p: PosProduct, onInvoice: Int, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 0.dp) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Lux.Champagne),
                contentAlignment = Alignment.Center,
            ) {
                val image = p.imageUrl ?: p.variants.firstNotNullOfOrNull { it.imageUrl }
                if (image != null) {
                    AsyncImage(model = image, contentDescription = p.nameAr, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(p.nameAr.take(1), style = MaterialTheme.typography.headlineSmall, color = Lux.GoldDeep)
                }
                if (onInvoice > 0) {
                    GoldPill(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                        Text("+$onInvoice", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                val stock = p.available
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCCFFFFFF))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (stock <= 0) "المخزون 0" else "المخزون $stock",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stock <= 0) MaterialTheme.colorScheme.error else Lux.InkSoft,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(p.nameAr, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.skuNumber?.let { Text("#$it", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) }
                Text("${p.variants.size} مقاس/لون", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
        }
    }
}

@Composable
private fun InvoiceBar(vm: NewPurchaseViewModel, onOpen: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = Lux.GoldDeep)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${vm.lines.size} صنف · ${vm.pieces} قطعة", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                Text(sar(vm.grandTotal), style = MaterialTheme.typography.titleLarge, color = Lux.GoldDeep)
            }
            LuxButton("فاتورة الشراء ←", onClick = onOpen, modifier = Modifier.width(170.dp))
        }
    }
}

// ---------------------------------------------------------------- variants ---

private fun parseHex(hex: String?): Color? = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/** Stays open so several sizes / colors can be added in one go. */
@Composable
private fun PurchaseVariantPicker(p: PosProduct, vm: NewPurchaseViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Lux.Pearl) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.nameAr, style = MaterialTheme.typography.titleLarge)
                Text("اختر اللون والمقاس — كل ضغطة تضيف قطعة", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
            TextButton(onClick = { vm.addAll(p) }) { Text("كل المقاسات") }
            TextButton(onClick = onDismiss) { Text("تم") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(p.variants, key = { it.id }) { v ->
                val qty = vm.qtyOf(v.id)
                GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.add(p, v) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val swatch = parseHex(v.colorHex)
                        if (swatch != null) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(swatch).border(1.dp, Lux.Hairline, CircleShape))
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(v.title, style = MaterialTheme.typography.titleSmall)
                            Text("المخزون الحالي ${v.available} · ${v.sku}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                        if (qty > 0) {
                            GoldPill { Text("+$qty", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                        } else {
                            Text("إضافة", style = MaterialTheme.typography.labelLarge, color = Lux.GoldDeep)
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- invoice ---

@Composable
private fun InvoiceContent(vm: NewPurchaseViewModel, onNewSupplier: () -> Unit, onCreated: (String) -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(modifier) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                // ---- lines
                Section {
                    if (vm.lines.isEmpty()) {
                        Text("الفاتورة فارغة. اختر المنتجات من الشبكة أو امسح الباركود.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("الأصناف (${vm.pieces} قطعة)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            SmallLink("تفريغ", onClick = vm::clear)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            vm.lines.forEachIndexed { i, l ->
                                key(l.variant.id) {
                                    if (i > 0) HorizontalDivider(color = Lux.Hairline)
                                    LineRow(l, vm)
                                }
                            }
                        }
                    }
                }

                // ---- supplier
                SupplierSection(vm, onNewSupplier)

                // ---- invoice details
                Section {
                    Text("بيانات الفاتورة", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactField(
                            value = vm.invoiceNo,
                            onValueChange = { vm.invoiceNo = it.take(60) },
                            placeholder = "رقم فاتورة المورد",
                            modifier = Modifier.weight(1f),
                        )
                        CompactField(
                            value = vm.shipping,
                            onValueChange = { vm.shipping = decimalInput(it).take(14); vm.error = null },
                            placeholder = "الشحن (ر.س)",
                            isError = !vm.shippingValid,
                            keyboardType = KeyboardType.Decimal,
                            trailing = { Text("ر.س", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CompactField(
                        value = vm.note,
                        onValueChange = { vm.note = it.take(500) },
                        placeholder = "ملاحظة (اختياري)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("استلام البضاعة الآن", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (vm.receiveNow) "تُضاف الكميات إلى المخزون فوراً" else "يبقى الأمر بانتظار الاستلام",
                                style = MaterialTheme.typography.labelSmall,
                                color = Lux.InkSoft,
                            )
                        }
                        Switch(
                            checked = vm.receiveNow,
                            onCheckedChange = { vm.receiveNow = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Lux.GoldDeep),
                        )
                    }
                }

                // ---- payment
                PaymentSection(vm)

                // ---- totals
                Section {
                    CompactRow("عدد الأصناف", "${vm.lines.size}")
                    CompactRow("عدد القطع", "${vm.pieces}")
                    CompactRow("قيمة البضاعة", sar(vm.subtotal))
                    if (vm.shippingValue.signum() > 0) CompactRow("الشحن", sar(vm.shippingValue))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("الإجمالي", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        GoldPill { Text(sar(vm.grandTotal), color = Color.White, style = MaterialTheme.typography.titleSmall) }
                    }
                    if (vm.pay != PurchasePay.ON_ACCOUNT) {
                        val paid = validAmount(vm.payAmount, Currency.SAR) ?: vm.grandTotal
                        val rest = vm.grandTotal.subtract(paid).max(BigDecimal.ZERO)
                        CompactRow("المدفوع الآن", sar(paid.min(vm.grandTotal)))
                        if (rest.signum() > 0) CompactRow("المتبقي على الحساب", sar(rest), Lux.GoldDeep)
                    } else if (vm.lines.isNotEmpty()) {
                        CompactRow("على حساب المورد", sar(vm.grandTotal), Lux.GoldDeep)
                    }
                }
            }

            // ---- submit (always visible)
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentPadding = 10.dp) {
                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                }
                val hint = vm.blocker
                hint?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                }
                LuxButton(
                    if (vm.busy) "جارٍ الحفظ…" else "إصدار فاتورة الشراء · ${sar(vm.grandTotal)}",
                    onClick = { vm.submit(onCreated) },
                    enabled = hint == null && !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Thumbnail, name · variant and delete; then qty stepper, unit cost and line total. */
@Composable
private fun LineRow(l: PurchaseLine, vm: NewPurchaseViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Lux.Champagne),
                contentAlignment = Alignment.Center,
            ) {
                val image = l.variant.imageUrl ?: l.product.imageUrl
                if (image != null) {
                    AsyncImage(model = image, contentDescription = l.product.nameAr, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(l.product.nameAr.take(1), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(l.product.nameAr, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${l.variant.title} · المخزون ${l.variant.available}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, maxLines = 1)
            }
            SmallIcon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.error) { vm.setQty(l.variant.id, 0) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepButton("−", enabled = true) { vm.setQty(l.variant.id, l.qty - 1) }
            var qtyText by remember(l.qty) { mutableStateOf(l.qty.toString()) }
            CompactField(
                value = qtyText,
                onValueChange = { t ->
                    qtyText = t.filter { it.isDigit() }.take(6)
                    qtyText.toIntOrNull()?.takeIf { it > 0 }?.let { vm.setQty(l.variant.id, it) }
                },
                placeholder = "الكمية",
                keyboardType = KeyboardType.Number,
                isError = qtyText.toIntOrNull()?.takeIf { it > 0 } == null,
                modifier = Modifier.width(60.dp),
            )
            StepButton("+", enabled = l.qty < MAX_QTY) { vm.setQty(l.variant.id, l.qty + 1) }
            Spacer(Modifier.width(4.dp))
            CompactField(
                value = l.cost,
                onValueChange = { vm.setCost(l.variant.id, it) },
                placeholder = "تكلفة الوحدة (ر.س)",
                isError = l.cost.isNotBlank() && l.unitCost == null,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            Text(
                sar(l.lineTotal),
                style = MaterialTheme.typography.labelLarge,
                color = Lux.GoldDeep,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 72.dp).padding(start = 4.dp),
            )
        }
        val siblings = vm.lines.count { it.product.id == l.product.id }
        val cost = l.unitCost
        if (siblings > 1 && cost != null && vm.lines.any { it.product.id == l.product.id && it.unitCost?.compareTo(cost) != 0 }) {
            SmallLink("تطبيق ${sar(cost)} على كل مقاسات المنتج") { vm.applyCostToProduct(l.product.id, l.cost) }
        }
    }
}

@Composable
private fun SupplierSection(vm: NewPurchaseViewModel, onNewSupplier: () -> Unit) {
    Section {
        Text("المورد", style = MaterialTheme.typography.labelLarge)
        val s = vm.supplier
        if (s != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Lux.GoldBrush),
                    contentAlignment = Alignment.Center,
                ) { Text(s.name.take(1), color = Color.White, style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(s.code, s.phone).joinToString(" · ") + " · الرصيد ${Money.formatBase(s.balance)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lux.InkSoft,
                        maxLines = 1,
                    )
                }
                SmallIcon(Icons.Filled.Close, "تغيير المورد", onClick = vm::clearSupplier)
            }
        } else {
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExposedDropdownMenuBox(
                    expanded = open,
                    onExpandedChange = { e -> open = e; if (e) vm.searchSuppliers(vm.supplierQuery) },
                    modifier = Modifier.weight(1f),
                ) {
                    CompactField(
                        value = vm.supplierQuery,
                        onValueChange = { vm.searchSuppliers(it); open = true },
                        placeholder = "ابحث عن المورد بالاسم أو الهاتف",
                        isError = vm.lines.isNotEmpty(),
                        trailing = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                    )
                    ExposedDropdownMenu(expanded = open && vm.supplierResults.isNotEmpty(), onDismissRequest = { open = false }) {
                        vm.supplierResults.forEach { r ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(r.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            listOfNotNull(r.code, r.phone).joinToString(" · ") + " · ${Money.formatBase(r.balance)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Lux.InkSoft,
                                        )
                                    }
                                },
                                onClick = {
                                    open = false
                                    vm.pickSupplier(r)
                                },
                            )
                        }
                    }
                }
                SmallLink("+ مورد جديد", onClick = onNewSupplier)
            }
        }
    }
}

@Composable
private fun PaymentSection(vm: NewPurchaseViewModel) {
    Section {
        Text("الدفع للمورد", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PurchasePay.entries.forEach { p -> SmallChip(p.label, selected = vm.pay == p) { vm.choosePay(p) } }
        }
        if (vm.pay != PurchasePay.ON_ACCOUNT) {
            if (vm.accounts.isEmpty()) {
                Text("سيُستخدم الحساب الافتراضي للدفع", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            } else {
                Text(
                    if (vm.pay == PurchasePay.BANK) "من الحساب البنكي:" else "من الصندوق:",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lux.InkSoft,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    vm.accounts.forEach { a -> SmallChip(a.nameAr, selected = a.id == vm.accountId) { vm.accountId = a.id; vm.error = null } }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactField(
                    value = vm.payAmount,
                    onValueChange = { vm.payAmount = decimalInput(it).take(14); vm.error = null },
                    placeholder = "المبلغ المدفوع (الكل ${sar(vm.grandTotal)})",
                    isError = !vm.payAmountValid,
                    keyboardType = KeyboardType.Decimal,
                    trailing = { Text("ر.س", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) },
                    modifier = Modifier.weight(1f),
                )
                SmallLink("الكل") { vm.payAmount = "" }
            }
        }
    }
}

// ----------------------------------------------------------------- dialogs ---

@Composable
private fun QuickSupplierDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    val valid = name.trim().length >= 2 && (phone.isBlank() || phone.count { it.isDigit() } >= 6)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مورد جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("اسم المورد") }, singleLine = true)
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' }.take(20) },
                    label = { Text("رقم الهاتف (اختياري)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onCreate(name.trim(), phone.trim()) }, enabled = valid) { Text("إضافة واختيار") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

// ------------------------------------------------------------- small parts ---

@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = 4.dp, contentPadding = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun SmallChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        modifier = Modifier.height(30.dp),
    )
}

@Composable
private fun SmallLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) Lux.GoldDeep else Lux.InkSoft,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun SmallIcon(icon: ImageVector, description: String, tint: Color = Lux.InkSoft, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(if (enabled) Lux.GoldLight else Lux.Champagne)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleSmall, color = if (enabled) Lux.Ink else Lux.InkSoft) }
}

@Composable
private fun CompactRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

/** ~40dp outlined text field with a placeholder instead of a floating label. */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Lux.Ink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        interactionSource = interaction,
        cursorBrush = SolidColor(Lux.GoldDeep),
        modifier = modifier.height(40.dp),
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = inner,
            enabled = true,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interaction,
            isError = isError,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, maxLines = 1) },
            trailingIcon = trailing,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            container = {
                OutlinedTextFieldDefaults.Container(enabled = true, isError = isError, interactionSource = interaction, shape = shape)
            },
        )
    }
}
