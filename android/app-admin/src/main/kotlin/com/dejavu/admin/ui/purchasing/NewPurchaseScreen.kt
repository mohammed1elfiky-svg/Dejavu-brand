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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.dejavu.core.ui.theme.OutlinedButton
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
import androidx.compose.runtime.mutableStateListOf
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
import com.dejavu.core.admin.AdminCategory
import com.dejavu.core.admin.CashBox
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.LocalNewProduct
import com.dejavu.core.admin.LocalNewVariant
import com.dejavu.core.admin.PosProduct
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.PosVariant
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.QuickPurchaseItem
import com.dejavu.core.admin.QuickPurchaseRequest
import com.dejavu.core.admin.Supplier
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currencies
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.media.rememberImagePicker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

// ------------------------------------------------------------------ model ---

/** One variant on the purchase invoice; [cost] is the unit cost typed in the invoice currency (USD / CNY). */
data class PurchaseLine(val product: PosProduct, val variant: PosVariant, val qty: Int, val cost: String = "") {
    val unitCost: BigDecimal? get() = validAmount(cost, Currency.SAR)
    val lineTotal: BigDecimal get() = (unitCost ?: BigDecimal.ZERO).multiply(BigDecimal(qty)).setScale(2, RoundingMode.HALF_UP)
}

/** A product bought for the first time: created (SKU number + barcodes) when the invoice is issued. */
data class NewPurchaseItem(
    val nameAr: String,
    val categoryId: String,
    val categoryName: String,
    /** Selling price, SAR. */
    val basePrice: String,
    /** Purchase cost per piece, in the invoice currency (USD / CNY). */
    val cost: String,
    val imageUrl: String?,
    val variants: List<LocalNewVariant>,
    val key: String = "new:" + System.nanoTime(),
) {
    val pieces: Int get() = variants.sumOf { it.quantity }
    val unitCost: BigDecimal? get() = validAmount(cost, Currency.SAR)
    val lineTotal: BigDecimal get() = (unitCost ?: BigDecimal.ZERO).multiply(BigDecimal(pieces)).setScale(2, RoundingMode.HALF_UP)

    fun toRequest() = LocalNewProduct(
        categoryId = categoryId,
        nameAr = nameAr.trim(),
        basePrice = validAmount(basePrice, Currency.SAR)?.stripTrailingZeros()?.toPlainString() ?: basePrice,
        unitCost = unitCost?.stripTrailingZeros()?.toPlainString() ?: cost,
        variants = variants,
        imageUrl = imageUrl,
    )
}

/** How the supplier is paid now: on account, or dollars from a USD box (recorded as a payment voucher). */
enum class PurchasePay(val label: String, val wire: String?) {
    ON_ACCOUNT("على الحساب (آجل)", null),
    CASH("دفع الآن من صندوق دولار", "CASH"),
}

/** Suppliers are dealt with in dollars and yuan only; the server converts to SAR for costs and the books. */
enum class PurchaseCurrency(val code: String, val label: String) {
    USD("USD", "الدولار"),
    CNY("CNY", "اليوان"),
}

private const val MAX_QTY = 100_000

private fun sar(v: BigDecimal): String = Money.formatBase(v.toPlainString())

private fun usd(v: BigDecimal): String = Money.plain(v, Currency.of("USD"))

/** A typed positive rate (yuan per dollar), or null. */
private fun rateOf(text: String?): BigDecimal? = text?.trim()?.replace(',', '.')?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

// -------------------------------------------------------------- viewmodel ---

class NewPurchaseViewModel(
    private val pos: PosRepository,
    private val purchasing: PurchasingRepository,
    private val finance: FinanceRepository,
    private val inventory: InventoryRepository,
    private val treasury: TreasuryRepository,
) : BaseViewModel() {
    var products by mutableStateOf<Load<List<PosProduct>>>(Load.Loading)
        private set
    var query by mutableStateOf("")
        private set

    var lines by mutableStateOf<List<PurchaseLine>>(emptyList())
        private set

    /** Products not in the app yet, added with "+ صنف جديد". */
    var newItems by mutableStateOf<List<NewPurchaseItem>>(emptyList())
        private set
    var categories by mutableStateOf<List<Pair<AdminCategory, Int>>>(emptyList())
        private set
    var uploading by mutableStateOf(false)
        private set

    var supplier by mutableStateOf<Supplier?>(null)
        private set
    var supplierQuery by mutableStateOf("")
        private set
    var supplierResults by mutableStateOf<List<Supplier>>(emptyList())
        private set

    var invoiceNo by mutableStateOf("")

    /** Photo of the supplier's invoice (instead of, or with, its number). */
    val invoicePhoto = InvoicePhoto(viewModelScope, purchasing) { say(it) }
    var shipping by mutableStateOf("")
    var note by mutableStateOf("")
    var receiveNow by mutableStateOf(true)
    var pay by mutableStateOf(PurchasePay.ON_ACCOUNT)
        private set
    /** Paid now, in USD; blank = the whole invoice. */
    var payAmount by mutableStateOf("")
    /** Active USD boxes the payment can come from. */
    var boxes by mutableStateOf<List<CashBox>>(emptyList())
        private set
    var boxId by mutableStateOf<String?>(null)

    /** Currency the prices and shipping are typed in. */
    var currency by mutableStateOf(PurchaseCurrency.USD)
        private set
    /** CNY: yuan per dollar, prefilled from the supplier's last payment voucher. */
    var cnyRate by mutableStateOf("")
    /** Yuan per dollar of the supplier's latest payment voucher (the server's fallback). */
    var supplierLastRate by mutableStateOf<String?>(null)
        private set
    /** The app's currencies (rates per 1 SAR and pictures); null until loaded. */
    var rates by mutableStateOf<Currencies?>(null)
        private set

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    private var searchJob: Job? = null
    private var supplierJob: Job? = null

    init {
        load()
        searchSuppliers("")
        viewModelScope.launch { finance.activeCurrencies().getOrNull()?.let { rates = it } }
        viewModelScope.launch {
            treasury.all(currency = Currency.of("USD")).getOrNull()?.let { list ->
                boxes = list.filter { it.isActive && it.currency.code == "USD" }
                if (boxId == null || boxes.none { it.id == boxId }) boxId = boxes.firstOrNull()?.id
            }
        }
        loadCategories()
    }

    fun loadCategories() {
        if (categories.isNotEmpty()) return
        viewModelScope.launch { inventory.categories().getOrNull()?.let { categories = it } }
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
        newItems = emptyList()
        error = null
    }

    // ---- new products

    /** Adds [item], or replaces the one with the same key (edit). */
    fun saveNewItem(item: NewPurchaseItem) {
        error = null
        newItems = if (newItems.any { it.key == item.key }) newItems.map { if (it.key == item.key) item else it } else newItems + item
    }

    fun removeNewItem(key: String) {
        error = null
        newItems = newItems.filterNot { it.key == key }
    }

    fun upload(bytes: ByteArray, then: (String) -> Unit) {
        uploading = true
        viewModelScope.launch {
            inventory.uploadImage(bytes).orSay()?.let { then(it.url) }
            uploading = false
        }
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
        supplierLastRate = null
        viewModelScope.launch {
            val last = purchasing.supplierAccount(s.id).getOrNull()?.lastRate
            if (supplier?.id != s.id) return@launch
            supplierLastRate = last
            rateOf(last)?.let { cnyRate = it.stripTrailingZeros().toPlainString() }
        }
    }

    fun clearSupplier() {
        supplier = null
        supplierLastRate = null
        searchSuppliers("")
    }

    fun chooseCurrency(c: PurchaseCurrency) {
        currency = c
        error = null
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
        if (p != PurchasePay.ON_ACCOUNT && boxId == null) boxId = boxes.firstOrNull()?.id
    }

    // ---- currency

    val invoiceCurrency: Currency get() = Currency.of(currency.code)

    /** An amount typed in the invoice currency, formatted in it. */
    fun money(v: BigDecimal): String = Money.plain(v, invoiceCurrency)

    fun artUrl(code: String): String? = rates?.rate(Currency.of(code))?.imageUrl

    /** Yuan per dollar used for the invoice: the typed one, else the supplier's last. */
    val yuanPerUsd: BigDecimal? get() = rateOf(cnyRate) ?: rateOf(supplierLastRate)

    /** [v] (invoice currency) in dollars; null when the yuan rate is unknown. */
    fun toUsd(v: BigDecimal): BigDecimal? = when (currency) {
        PurchaseCurrency.USD -> v
        PurchaseCurrency.CNY -> yuanPerUsd?.let { v.divide(it, 2, RoundingMode.HALF_UP) }
    }

    /** [v] (invoice currency) in SAR at the app's dollar rate; null when a rate is missing. */
    fun toSar(v: BigDecimal): BigDecimal? {
        val usdPerSar = rateOf(rates?.rate(Currency.of("USD"))?.rate) ?: return null
        val inUsd = when (currency) {
            PurchaseCurrency.USD -> v
            PurchaseCurrency.CNY -> yuanPerUsd?.let { v.divide(it, 8, RoundingMode.HALF_UP) } ?: return null
        }
        return inUsd.divide(usdPerSar, 2, RoundingMode.HALF_UP)
    }

    // ---- totals

    val pieces: Int get() = lines.sumOf { it.qty } + newItems.sumOf { it.pieces }
    val itemCount: Int get() = lines.size + newItems.size
    val isEmpty: Boolean get() = lines.isEmpty() && newItems.isEmpty()
    val subtotal: BigDecimal
        get() = newItems.fold(lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal) }) { acc, n -> acc.add(n.lineTotal) }
    val shippingValue: BigDecimal get() = shipping.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ZERO
    val grandTotal: BigDecimal get() = subtotal.add(shippingValue).setScale(2, RoundingMode.HALF_UP)

    val shippingValid: Boolean get() = shipping.isBlank() || validAmount(shipping, Currency.SAR) != null
    val costsValid: Boolean get() = lines.all { it.unitCost != null } && newItems.all { it.unitCost != null }

    /** The whole invoice in dollars (what "pay all" pays); null when the yuan rate is unknown. */
    val grandTotalUsd: BigDecimal? get() = toUsd(grandTotal)?.setScale(2, RoundingMode.HALF_UP)

    /** Dollars paid now: the typed amount, else the whole invoice. */
    val paidUsd: BigDecimal? get() = validAmount(payAmount, Currency.SAR) ?: grandTotalUsd

    val rateValid: Boolean get() = currency != PurchaseCurrency.CNY || (cnyRate.isBlank() && rateOf(supplierLastRate) != null) || rateOf(cnyRate) != null
    val payAmountValid: Boolean
        get() = pay == PurchasePay.ON_ACCOUNT || payAmount.isBlank() ||
            validAmount(payAmount, Currency.SAR)?.let { paid -> grandTotalUsd?.let { paid <= it } ?: true } == true

    /** Why the invoice cannot be issued yet; null when it can. */
    val blocker: String?
        get() = when {
            isEmpty -> "أضف أصنافاً من الشبكة أو امسح الباركود أو أضف صنفاً جديداً"
            supplier == null -> "اختر المورد"
            !costsValid -> "أدخل تكلفة الوحدة لكل صنف"
            !shippingValid -> "تكلفة الشحن غير صحيحة"
            !rateValid -> "أدخل سعر الدولار باليوان"
            !payAmountValid -> "المبلغ المدفوع يجب ألا يتجاوز الإجمالي"
            invoicePhoto.uploading -> "جارٍ رفع صورة فاتورة المورد…"
            pay != PurchasePay.ON_ACCOUNT && boxes.isEmpty() -> "لا يوجد صندوق بالدولار — أنشئ صندوقاً بعملة USD من شاشة الخزينة"
            pay != PurchasePay.ON_ACCOUNT && boxId == null -> "اختر صندوق الدولار"
            else -> null
        }

    fun submit(created: (String) -> Unit) {
        if (busy || blocker != null) return
        val s = supplier ?: return
        val paid = paidUsd
        val payNow = pay != PurchasePay.ON_ACCOUNT
        val request = QuickPurchaseRequest(
            supplierId = s.id,
            items = lines.map { QuickPurchaseItem(it.variant.id, it.qty, it.unitCost!!.stripTrailingZeros().toPlainString()) },
            newProducts = newItems.map { it.toRequest() },
            supplierInvoiceNo = invoiceNo.trim().ifBlank { null },
            shippingCost = validAmount(shipping, Currency.SAR)?.stripTrailingZeros()?.toPlainString(),
            note = note.trim().ifBlank { null },
            receiveNow = receiveNow,
            payMethod = if (payNow) "CASH" else null,
            payAmount = if (payNow) paid?.stripTrailingZeros()?.toPlainString() else null,
            payAccountId = null,
            currency = currency.code,
            foreignPerUsd = if (currency == PurchaseCurrency.CNY) rateOf(cnyRate)?.stripTrailingZeros()?.toPlainString() else null,
            payBoxId = if (payNow) boxId else null,
            invoiceImageUrl = invoicePhoto.url,
        )
        busy = true
        error = null
        viewModelScope.launch {
            purchasing.quickPurchase(request).fold(
                { po ->
                    say("تم إصدار فاتورة الشراء ${po.number}")
                    lines = emptyList()
                    newItems = emptyList()
                    invoicePhoto.clear()
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
    val vm = adminViewModel { NewPurchaseViewModel(it.pos, it.purchasing, it.finance, it.inventory, it.treasury) }
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<PosProduct?>(null) }
    var showInvoice by rememberSaveable { mutableStateOf(false) }
    var newSupplier by remember { mutableStateOf(false) }
    var addingNew by remember { mutableStateOf(false) }
    var editingNew by remember { mutableStateOf<NewPurchaseItem?>(null) }
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
                TextButton(onClick = { vm.loadCategories(); addingNew = true }) { Text("+ صنف جديد") }
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
                    if (!wide && !vm.isEmpty) {
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
                        InvoiceContent(
                            vm,
                            onNewSupplier = { newSupplier = true },
                            onCreated = onCreated,
                            onAddNew = { vm.loadCategories(); addingNew = true },
                            onEditNew = { editingNew = it },
                            modifier = Modifier.weight(1f),
                        )
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
                onAddNew = { vm.loadCategories(); addingNew = true },
                onEditNew = { editingNew = it },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }

    if (newSupplier) {
        QuickSupplierDialog(onDismiss = { newSupplier = false }) { name, phone -> vm.createSupplier(name, phone) }
    }

    if (addingNew) {
        NewProductDialog(vm, initial = null, onDismiss = { addingNew = false })
    }
    editingNew?.let { item ->
        NewProductDialog(vm, initial = item, onDismiss = { editingNew = null })
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
                Text("${vm.itemCount} صنف · ${vm.pieces} قطعة", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                Text(vm.money(vm.grandTotal), style = MaterialTheme.typography.titleLarge, color = Lux.GoldDeep)
                vm.toSar(vm.grandTotal)?.let { SarHint(it) }
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
private fun InvoiceContent(
    vm: NewPurchaseViewModel,
    onNewSupplier: () -> Unit,
    onCreated: (String) -> Unit,
    onAddNew: () -> Unit,
    onEditNew: (NewPurchaseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(modifier) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                // ---- lines
                Section {
                    if (vm.isEmpty) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "الفاتورة فارغة. اختر المنتجات من الشبكة أو امسح الباركود.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Lux.InkSoft,
                                modifier = Modifier.weight(1f),
                            )
                            SmallLink("+ صنف جديد", onClick = onAddNew)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("الأصناف (${vm.pieces} قطعة)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            SmallLink("+ صنف جديد", onClick = onAddNew)
                            SmallLink("تفريغ", onClick = vm::clear)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            vm.newItems.forEachIndexed { i, n ->
                                key(n.key) {
                                    if (i > 0) HorizontalDivider(color = Lux.Hairline)
                                    NewItemRow(n, money = vm::money, onEdit = { onEditNew(n) }, onRemove = { vm.removeNewItem(n.key) })
                                }
                            }
                            vm.lines.forEachIndexed { i, l ->
                                key(l.variant.id) {
                                    if (i > 0 || vm.newItems.isNotEmpty()) HorizontalDivider(color = Lux.Hairline)
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
                    CurrencySwitch(vm)
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
                            placeholder = "الشحن (${vm.invoiceCurrency.symbol})",
                            isError = !vm.shippingValid,
                            keyboardType = KeyboardType.Decimal,
                            trailing = { Text(vm.invoiceCurrency.symbol, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    InvoicePhotoField(vm.invoicePhoto, Modifier.fillMaxWidth())
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
                    CompactRow("عدد الأصناف", "${vm.itemCount}")
                    CompactRow("عدد القطع", "${vm.pieces}")
                    CompactRow("قيمة البضاعة", vm.money(vm.subtotal))
                    if (vm.shippingValue.signum() > 0) CompactRow("الشحن", vm.money(vm.shippingValue))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("الإجمالي", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        GoldPill { Text(vm.money(vm.grandTotal), color = Color.White, style = MaterialTheme.typography.titleSmall) }
                    }
                    vm.toSar(vm.grandTotal)?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SarHint(it) } }
                    if (vm.currency == PurchaseCurrency.CNY) {
                        vm.grandTotalUsd?.let { CompactRow("بالدولار", usd(it)) }
                    }
                    if (vm.pay != PurchasePay.ON_ACCOUNT) {
                        val totalUsd = vm.grandTotalUsd
                        val paid = vm.paidUsd
                        if (totalUsd != null && paid != null) {
                            val rest = totalUsd.subtract(paid).max(BigDecimal.ZERO)
                            CompactRow("المدفوع الآن", usd(paid.min(totalUsd)))
                            if (rest.signum() > 0) CompactRow("المتبقي على الحساب", usd(rest), Lux.GoldDeep)
                        }
                    } else if (!vm.isEmpty) {
                        CompactRow("على حساب المورد", vm.money(vm.grandTotal), Lux.GoldDeep)
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
                    if (vm.busy) "جارٍ الحفظ…" else "إصدار فاتورة الشراء · ${vm.money(vm.grandTotal)}",
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
                placeholder = "تكلفة الوحدة (${vm.invoiceCurrency.symbol})",
                isError = l.cost.isNotBlank() && l.unitCost == null,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            Text(
                vm.money(l.lineTotal),
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
            SmallLink("تطبيق ${vm.money(cost)} على كل مقاسات المنتج") { vm.applyCostToProduct(l.product.id, l.cost) }
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
                        isError = !vm.isEmpty,
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
            Text("يُسجَّل الدفع كسند صرف بالدولار في كشف حساب المورد", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            if (vm.boxes.isEmpty()) {
                Text(
                    "لا يوجد صندوق بالدولار — أنشئ صندوقاً بعملة USD من شاشة الخزينة",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CurrencyArt("USD", vm.artUrl("USD"), height = 14.dp)
                    Text("من صندوق الدولار:", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    vm.boxes.forEach { b ->
                        SmallChip("${b.name} · ${b.shownBalance}", selected = b.id == vm.boxId) { vm.boxId = b.id; vm.error = null }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactField(
                    value = vm.payAmount,
                    onValueChange = { vm.payAmount = decimalInput(it).take(14); vm.error = null },
                    placeholder = "المبلغ بالدولار (الكل ${vm.grandTotalUsd?.let { usd(it) } ?: "—"})",
                    isError = !vm.payAmountValid,
                    keyboardType = KeyboardType.Decimal,
                    trailing = { Text("USD", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) },
                    modifier = Modifier.weight(1f),
                )
                SmallLink("الكل") { vm.payAmount = "" }
            }
        }
    }
}

/** "عملة الفاتورة": dollars or yuan (no SAR); for yuan, the dollar rate in yuan. */
@Composable
private fun CurrencySwitch(vm: NewPurchaseViewModel) {
    Text("عملة الفاتورة", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PurchaseCurrency.entries.forEach { c ->
            FilterChip(
                selected = vm.currency == c,
                onClick = { vm.chooseCurrency(c) },
                label = { Text(c.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                leadingIcon = { CurrencyArt(c.code, vm.artUrl(c.code), height = 14.dp) },
                modifier = Modifier.height(30.dp),
            )
        }
    }
    if (vm.currency == PurchaseCurrency.CNY) {
        CompactField(
            value = vm.cnyRate,
            onValueChange = { vm.cnyRate = decimalInput(it).take(12); vm.error = null },
            placeholder = "سعر الدولار باليوان" + (vm.supplierLastRate?.let { " (آخر سعر $it)" } ?: ""),
            isError = !vm.rateValid,
            keyboardType = KeyboardType.Decimal,
            trailing = { Text("CNY / 1 USD", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "سعر الدولار باليوان" + if (vm.supplierLastRate != null) " — مأخوذ من آخر سند صرف للمورد، ويمكن تعديله" else "",
            style = MaterialTheme.typography.labelSmall,
            color = Lux.InkSoft,
        )
    }
}

/** "≈ 1,200 ر.س": the SAR value of an amount typed in the invoice currency. */
@Composable
private fun SarHint(v: BigDecimal) {
    Text("≈ ${sar(v)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
}

/** A product bought for the first time, tagged "جديد"; edited or removed like any other line. */
@Composable
private fun NewItemRow(n: NewPurchaseItem, money: (BigDecimal) -> String, onEdit: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Lux.Champagne),
                contentAlignment = Alignment.Center,
            ) {
                if (n.imageUrl != null) {
                    AsyncImage(model = n.imageUrl, contentDescription = n.nameAr, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(n.nameAr.take(1), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        n.nameAr,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Lux.GoldBrush).padding(horizontal = 8.dp, vertical = 1.dp),
                    ) { Text("جديد", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                }
                val price = validAmount(n.basePrice, Currency.SAR) ?: BigDecimal.ZERO
                Text(
                    "${n.categoryName} · بيع ${sar(price)} · " +
                        n.variants.joinToString("، ") { v -> listOfNotNull(v.size, v.color).joinToString(" ").ifBlank { "قطعة" } + " ×${v.quantity}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Lux.InkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SmallLink("تعديل", onClick = onEdit)
            SmallIcon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.error, onClick = onRemove)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${n.pieces} قطعة × ${n.unitCost?.let { money(it) } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (n.unitCost == null) MaterialTheme.colorScheme.error else Lux.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(money(n.lineTotal), style = MaterialTheme.typography.labelLarge, color = Lux.GoldDeep)
        }
    }
}

// ----------------------------------------------------------------- dialogs ---

/** One size / colour row of a new product. */
private data class NewVariantRow(val size: String = "", val color: String = "", val qty: String = "1", val barcode: String = "")

/** "صنف جديد": photo, name, category, sale price (SAR) and cost (invoice currency), then sizes / colours / quantities with optional barcodes. */
@Composable
private fun NewProductDialog(vm: NewPurchaseViewModel, initial: NewPurchaseItem?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.nameAr.orEmpty()) }
    var categoryId by rememberSaveable { mutableStateOf(initial?.categoryId) }
    var basePrice by rememberSaveable { mutableStateOf(initial?.basePrice.orEmpty()) }
    var cost by rememberSaveable { mutableStateOf(initial?.cost.orEmpty()) }
    var imageUrl by rememberSaveable { mutableStateOf(initial?.imageUrl) }
    var pickingCategory by remember { mutableStateOf(false) }
    val rows = remember {
        mutableStateListOf<NewVariantRow>().apply {
            if (initial == null || initial.variants.isEmpty()) {
                add(NewVariantRow())
            } else {
                initial.variants.forEach { v -> add(NewVariantRow(v.size.orEmpty(), v.color.orEmpty(), v.quantity.toString(), v.barcode.orEmpty())) }
            }
        }
    }
    var scanRow by remember { mutableStateOf<Int?>(null) }
    val scanner = rememberBarcodeScanner { code ->
        scanRow?.let { i -> if (i in rows.indices) rows[i] = rows[i].copy(barcode = code.take(64)) }
        scanRow = null
    }
    val picker = rememberImagePicker(maxSide = 1200) { bytes -> vm.upload(bytes) { imageUrl = it } }
    val category = vm.categories.firstOrNull { it.first.id == categoryId }?.first
    val categoryName = category?.nameAr ?: initial?.takeIf { it.categoryId == categoryId }?.categoryName
    val parsed = rows.map { r ->
        LocalNewVariant(
            size = r.size.trim().ifBlank { null },
            color = r.color.trim().ifBlank { null },
            barcode = r.barcode.trim().ifBlank { null },
            quantity = r.qty.toIntOrNull() ?: 0,
        )
    }
    val barcodes = parsed.mapNotNull { it.barcode }
    val problem = when {
        name.trim().length < 2 -> "اكتب اسم الصنف"
        categoryId == null || categoryName == null -> "اختر القسم"
        validAmount(basePrice, Currency.SAR) == null -> "أدخل سعر البيع"
        validAmount(cost, Currency.SAR) == null -> "أدخل تكلفة الشراء للقطعة"
        parsed.any { it.quantity !in 1..MAX_QTY } -> "أدخل كمية صحيحة لكل مقاس / لون"
        parsed.map { it.size to it.color }.toSet().size != parsed.size -> "المقاس واللون مكرران في أكثر من سطر"
        barcodes.toSet().size != barcodes.size -> "الباركود مكرر"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "صنف جديد" else "تعديل الصنف الجديد") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Lux.Champagne),
                        contentAlignment = Alignment.Center,
                    ) {
                        val url = imageUrl
                        if (url != null) {
                            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Text(name.trim().take(1).ifBlank { "+" }, style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedButton(onClick = picker.fromGallery, enabled = !vm.uploading) {
                            if (vm.uploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (imageUrl == null) "صورة الصنف" else "تغيير الصورة")
                        }
                        if (imageUrl != null) TextButton(onClick = { imageUrl = null }) { Text("إزالة الصورة", color = MaterialTheme.colorScheme.error) }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, label = { Text("اسم الصنف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.loadCategories(); pickingCategory = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(categoryName?.let { "القسم: $it" } ?: "اختر القسم")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = basePrice,
                        onValueChange = { basePrice = decimalInput(it).take(14) },
                        label = { Text("سعر البيع (ر.س)") },
                        isError = basePrice.isNotBlank() && validAmount(basePrice, Currency.SAR) == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cost,
                        onValueChange = { cost = decimalInput(it).take(14) },
                        label = { Text("تكلفة القطعة (${vm.invoiceCurrency.symbol})") },
                        isError = cost.isNotBlank() && validAmount(cost, Currency.SAR) == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("المقاسات والألوان والكميات", style = MaterialTheme.typography.labelLarge)
                rows.forEachIndexed { i, r ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (i > 0) HorizontalDivider(color = Lux.Hairline)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = r.size,
                                onValueChange = { rows[i] = r.copy(size = it.take(30)) },
                                label = { Text("المقاس") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = r.color,
                                onValueChange = { rows[i] = r.copy(color = it.take(40)) },
                                label = { Text("اللون") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = r.qty,
                                onValueChange = { rows[i] = r.copy(qty = it.filter { c -> c.isDigit() }.take(6)) },
                                label = { Text("الكمية") },
                                isError = (r.qty.toIntOrNull() ?: 0) !in 1..MAX_QTY,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(0.8f),
                            )
                            if (rows.size > 1) {
                                IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                        OutlinedTextField(
                            value = r.barcode,
                            onValueChange = { rows[i] = r.copy(barcode = it.trim().take(64)) },
                            label = { Text("باركود المصنع (اختياري)") },
                            supportingText = if (r.barcode.isBlank()) ({ Text("فارغ = يولّد النظام باركوداً") }) else null,
                            trailingIcon = {
                                TextButton(onClick = {
                                    scanRow = i
                                    scanner()
                                }) { Text("مسح") }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                TextButton(onClick = { rows += NewVariantRow() }) { Text("+ مقاس / لون آخر") }
                val pieces = parsed.sumOf { it.quantity.coerceAtLeast(0) }
                val unit = validAmount(cost, Currency.SAR)
                if (unit != null && pieces > 0) {
                    CompactRow("الإجمالي ($pieces قطعة)", vm.money(unit.multiply(BigDecimal(pieces))), Lux.GoldDeep)
                }
                Text(
                    "يُنشأ الصنف برقم SKU وباركود عند إصدار الفاتورة، ويُعرض للبيع بعد الاستلام.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lux.InkSoft,
                )
                problem?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null && !vm.uploading,
                onClick = {
                    val cid = categoryId
                    if (problem == null && cid != null && categoryName != null) {
                        val item = NewPurchaseItem(
                            nameAr = name.trim(),
                            categoryId = cid,
                            categoryName = categoryName,
                            basePrice = basePrice.trim(),
                            cost = cost.trim(),
                            imageUrl = imageUrl,
                            variants = parsed,
                        )
                        vm.saveNewItem(if (initial != null) item.copy(key = initial.key) else item)
                        onDismiss()
                    }
                },
            ) { Text(if (initial == null) "إضافة للفاتورة" else "حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
    if (pickingCategory) {
        AlertDialog(
            onDismissRequest = { pickingCategory = false },
            title = { Text("القسم") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (vm.categories.isEmpty()) Text("لا توجد أقسام بعد")
                    vm.categories.forEach { (c, depth) ->
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
