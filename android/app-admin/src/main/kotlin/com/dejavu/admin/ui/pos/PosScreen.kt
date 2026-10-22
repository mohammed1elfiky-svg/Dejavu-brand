@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.barcode.rememberBarcodeScanner
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AccountBalance
import com.dejavu.core.admin.AdminCustomerSummary
import com.dejavu.core.admin.CashBox
import com.dejavu.core.admin.CashBoxKind
import com.dejavu.core.admin.DiscountType
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.PosItem
import com.dejavu.core.admin.PosProduct
import com.dejavu.core.admin.PosReceipt
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.PosSaleRequest
import com.dejavu.core.admin.PosShift
import com.dejavu.core.admin.PosTender
import com.dejavu.core.admin.PosVariant
import com.dejavu.core.admin.SaleType
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.math.BigDecimal
import java.math.RoundingMode

// ------------------------------------------------------------------ model ---

data class CartLine(val product: PosProduct, val variant: PosVariant, val qty: Int) {
    /** SAR, prices already include any running promotion. */
    val lineTotal: BigDecimal get() = Money.parse(variant.price).multiply(BigDecimal(qty))
}

data class PosCustomer(val id: String, val name: String, val phone: String?, val code: String?)

/** One amount handed over at the till, typed in [currency]. */
data class TenderInput(val currency: Currency, val amount: String)

/** Client mirror of the server's sale arithmetic (all SAR, except [changeAmount]). */
class PosTotals(
    val subtotal: BigDecimal,
    val discount: BigDecimal,
    val total: BigDecimal,
    val paid: BigDecimal,
    val owed: BigDecimal,
    val change: BigDecimal,
    /** [change] in the change currency. */
    val changeAmount: BigDecimal,
)

private val HUNDRED = BigDecimal(100)

private fun money(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

/** A typed amount that is positive and has no more decimals than [currency] allows; null otherwise. */
internal fun validAmount(text: String, currency: Currency): BigDecimal? =
    text.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= currency.decimals }

/** Keeps digits and one dot. */
internal fun decimalInput(text: String): String {
    val filtered = text.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    return if (dot < 0) filtered else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

/**
 * Same steps as the server (pos.service): price, invoice discount, then tenders in order.
 * A tender that exactly matches what is left counts as that SAR amount; a remainder smaller
 * than the last currency's smallest coin counts as paid; change too small to hand back is dropped.
 */
fun computeTotals(
    cart: List<CartLine>,
    invoice: Currency,
    rates: Map<Currency, BigDecimal>,
    discountType: DiscountType,
    discountValue: String,
    tenders: List<TenderInput>,
    changeCurrency: Currency,
): PosTotals {
    val rate = rates[invoice] ?: BigDecimal.ONE
    val subtotal = money(cart.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal) })
    val value = discountValue.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val discount = when {
        value == null -> BigDecimal.ZERO
        discountType == DiscountType.PERCENT -> subtotal.multiply(value.min(HUNDRED)).divide(HUNDRED, 2, RoundingMode.HALF_UP)
        else -> Money.toBase(value, invoice, rate)
    }.min(subtotal)
    val total = money(subtotal.subtract(discount))

    var paid = BigDecimal.ZERO
    var last: Pair<Currency, BigDecimal>? = null
    for (t in tenders) {
        val amount = t.amount.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
        val r = rates[t.currency] ?: continue
        val left = total.subtract(paid)
        val sar = if (left.signum() > 0 && amount.compareTo(Money.convert(left, t.currency, r)) == 0) money(left) else Money.toBase(amount, t.currency, r)
        paid = paid.add(sar)
        last = t.currency to r
    }
    val short = total.subtract(paid)
    if (last != null && short.signum() > 0 && Money.convert(short, last.first, last.second).signum() == 0) paid = total

    var change = if (paid > total) money(paid.subtract(total)) else BigDecimal.ZERO
    val changeRate = rates[changeCurrency] ?: BigDecimal.ONE
    var changeAmount = if (change.signum() > 0) Money.convert(change, changeCurrency, changeRate) else BigDecimal.ZERO
    if (change.signum() > 0 && changeAmount.signum() == 0) {
        change = BigDecimal.ZERO
        changeAmount = BigDecimal.ZERO
    }
    val owed = if (paid < total) money(total.subtract(paid)) else BigDecimal.ZERO
    return PosTotals(subtotal, discount, total, paid, owed, change, changeAmount)
}

// -------------------------------------------------------------- viewmodel ---

/** How the money of a sale is received. */
enum class PayMethod(val label: String, val wire: String) {
    CASH("نقداً (كاش)", "CASH"),
    TRANSFER("تحويل بنكي", "BANK_TRANSFER"),
    DEPOSIT("سند إيداع", "BANK_TRANSFER"),
}

class PosViewModel(
    private val pos: PosRepository,
    private val finance: FinanceRepository,
    private val treasury: TreasuryRepository,
) : BaseViewModel() {
    var products by mutableStateOf<Load<List<PosProduct>>>(Load.Loading)
        private set
    var query by mutableStateOf("")
        private set
    var currencies by mutableStateOf(listOf(Currency.SAR))
        private set
    var rates by mutableStateOf(mapOf(Currency.SAR to BigDecimal.ONE))
        private set

    var cart by mutableStateOf<List<CartLine>>(emptyList())
        private set
    var currency by mutableStateOf(Currency.SAR)
    var saleType by mutableStateOf(SaleType.CASH)
    var customer by mutableStateOf<PosCustomer?>(null)
        private set
    var customerBalances by mutableStateOf<List<AccountBalance>>(emptyList())
        private set
    var customerQuery by mutableStateOf("")
        private set
    var customerResults by mutableStateOf<List<AdminCustomerSummary>>(emptyList())
        private set
    var discountType by mutableStateOf(DiscountType.PERCENT)
    var discountValue by mutableStateOf("")
    var tenders by mutableStateOf(listOf(TenderInput(Currency.SAR, "")))
        private set
    var changeCurrency by mutableStateOf<Currency?>(null)
    var payMethod by mutableStateOf(PayMethod.CASH)
    var reference by mutableStateOf("")
    /** Bank / exchange-house boxes a transfer can land in. */
    var banks by mutableStateOf<List<CashBox>>(emptyList())
        private set
    /** Chosen bank box per currency (null = the first one of that currency). */
    var bankFor by mutableStateOf<Map<Currency, String>>(emptyMap())

    var busy by mutableStateOf(false)
        private set
    var checkoutError by mutableStateOf<String?>(null)
    var receipt by mutableStateOf<PosReceipt?>(null)
        private set
    var shift by mutableStateOf<Load<PosShift>?>(null)
        private set

    private var searchJob: Job? = null
    private var customerJob: Job? = null

    init {
        load()
        loadCurrencies()
        viewModelScope.launch {
            banks = treasury.safes().getOrNull().orEmpty().filter { it.kind == CashBoxKind.BANK && it.isActive }
        }
    }

    fun pickBank(currency: Currency, boxId: String) {
        bankFor = bankFor + (currency to boxId)
    }

    val totals: PosTotals
        get() = computeTotals(cart, currency, rates, discountType, discountValue, tenders, changeCurrency ?: currency)

    fun rateOf(c: Currency): BigDecimal? = rates[c]

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

    private fun loadCurrencies() {
        viewModelScope.launch {
            finance.activeCurrencies().fold(
                { cs ->
                    val ordered = cs.register()
                    val found = mutableMapOf(Currency.SAR to BigDecimal.ONE)
                    cs.currencies.forEach { cr ->
                        val c = Currency.of(cr.code.code)
                        val r = if (c.isBase) BigDecimal.ONE else cr.rate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                        if (r != null) found[c] = r
                    }
                    rates = found
                    currencies = (listOf(Currency.SAR) + ordered.map { Currency.of(it.code) }).distinct().filter { it in found }
                    // Two tender fields side by side by default: SAR and YER (when it has a rate).
                    val defaults = listOfNotNull(Currency.SAR, Currency.YER.takeIf { it in found })
                    if (tenders.all { it.amount.isBlank() }) tenders = defaults.map { TenderInput(it, "") }
                },
                { say(it.userMessage()) },
            )
        }
    }

    fun arrange(order: List<PosProduct>, done: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            pos.arrange(order.map { it.id }).orSay()?.let {
                products = Load.Ready(order)
                say("تم حفظ ترتيب المنتجات")
                done()
                fetch()
            }
            busy = false
        }
    }

    // ---- cart

    /** Tap on a tile: single-variant products go straight in the cart; others open the picker. */
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

    fun inCart(variantId: String): Int = cart.firstOrNull { it.variant.id == variantId }?.qty ?: 0

    fun add(product: PosProduct, variant: PosVariant) {
        val have = inCart(variant.id)
        if (have + 1 > variant.available) {
            say(if (variant.available <= 0) "${product.nameAr} (${variant.title}) غير متوفر" else "المتاح من ${product.nameAr} (${variant.title}) ${variant.available} فقط")
            return
        }
        cart = if (have == 0) cart + CartLine(product, variant, 1) else cart.map { if (it.variant.id == variant.id) it.copy(qty = it.qty + 1) else it }
    }

    fun setQty(variantId: String, qty: Int) {
        val line = cart.firstOrNull { it.variant.id == variantId } ?: return
        cart = when {
            qty <= 0 -> cart.filterNot { it.variant.id == variantId }
            qty > line.variant.available -> {
                say("المتاح ${line.variant.available} فقط")
                cart
            }
            else -> cart.map { if (it.variant.id == variantId) it.copy(qty = qty) else it }
        }
    }

    // ---- payment

    fun setTender(index: Int, amount: String) {
        tenders = tenders.mapIndexed { i, t -> if (i == index) t.copy(amount = decimalInput(amount)) else t }
        checkoutError = null
    }

    fun addTender(c: Currency) {
        if (tenders.none { it.currency == c }) tenders = tenders + TenderInput(c, "")
    }

    fun removeTender(index: Int) {
        tenders = tenders.filterIndexed { i, _ -> i != index }
    }

    /** Puts whatever is still owed (ignoring this field) into tender [index], in its currency. */
    fun fillRemaining(index: Int) {
        val t = tenders.getOrNull(index) ?: return
        val r = rates[t.currency] ?: return
        val others = tenders.mapIndexed { i, x -> if (i == index) x.copy(amount = "") else x }
        val owed = computeTotals(cart, currency, rates, discountType, discountValue, others, changeCurrency ?: currency).owed
        val amount = if (owed.signum() > 0) Money.convert(owed, t.currency, r).stripTrailingZeros().toPlainString() else ""
        tenders = tenders.mapIndexed { i, x -> if (i == index) x.copy(amount = amount) else x }
        checkoutError = null
    }

    // ---- customer

    fun searchCustomers(q: String) {
        customerQuery = q
        customerJob?.cancel()
        customerJob = viewModelScope.launch {
            if (q.isNotBlank()) delay(300)
            customerResults = finance.customers(q.ifBlank { null }).getOrNull()?.items.orEmpty()
                .filter { it.code != "CUS-WALKIN" }.take(20)
        }
    }

    fun pickCustomer(c: PosCustomer) {
        customer = c
        customerQuery = ""
        customerResults = emptyList()
        customerBalances = emptyList()
        checkoutError = null
        viewModelScope.launch {
            customerBalances = finance.customerAccount(c.id).getOrNull()?.balances.orEmpty()
        }
    }

    fun clearCustomer() {
        customer = null
        customerBalances = emptyList()
    }

    fun quickCustomer(name: String, phone: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            pos.quickCustomer(name, phone).fold(
                { pickCustomer(PosCustomer(it.id, it.name, it.phone, it.code)) },
                { e ->
                    checkoutError = e.userMessage()
                    say(e.userMessage())
                },
            )
            busy = false
        }
    }

    // ---- sale

    fun sell() {
        if (busy || cart.isEmpty()) return
        val t = totals
        val discount = discountValue.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        val request = PosSaleRequest(
            items = cart.map { PosItem(it.variant.id, it.qty) },
            saleType = saleType,
            currency = currency,
            customerId = customer?.id,
            discountType = discountType.takeIf { discount != null },
            discountValue = discount?.stripTrailingZeros()?.toPlainString(),
            payments = tenders.mapNotNull { x ->
                validAmount(x.amount, x.currency)?.let {
                    PosTender(
                        x.currency,
                        it.stripTrailingZeros().toPlainString(),
                        method = payMethod.wire,
                        bankBoxId = if (payMethod == PayMethod.CASH) null else bankFor[x.currency],
                    )
                }
            },
            changeCurrency = (changeCurrency ?: currency).takeIf { t.change.signum() > 0 },
            paymentMethod = payMethod.wire,
            reference = reference.trim().ifBlank { null }?.takeIf { payMethod != PayMethod.CASH },
        )
        busy = true
        checkoutError = null
        viewModelScope.launch {
            pos.sell(request).fold(
                {
                    receipt = it
                    fetch()
                },
                { e -> checkoutError = e.userMessage() },
            )
            busy = false
        }
    }

    fun newSale() {
        receipt = null
        cart = emptyList()
        customer = null
        customerBalances = emptyList()
        customerQuery = ""
        customerResults = emptyList()
        discountValue = ""
        saleType = SaleType.CASH
        changeCurrency = null
        checkoutError = null
        payMethod = PayMethod.CASH
        reference = ""
        tenders = tenders.map { it.copy(amount = "") }
    }

    fun loadShift() {
        shift = Load.Loading
        viewModelScope.launch { shift = pos.shift().toLoad() }
    }

    fun closeShift() {
        shift = null
    }
}

// ----------------------------------------------------------------- screen ---

/** The till: product grid, cart and multi-currency checkout. */
@Composable
fun PosScreen(session: StaffSession.LoggedIn, openBoxes: () -> Unit) {
    val vm = adminViewModel { PosViewModel(it.pos, it.finance, it.treasury) }
    val snackbar = remember { SnackbarHostState() }
    val canArrange = session.can(Perm.PRODUCTS_WRITE)
    var arranging by rememberSaveable { mutableStateOf(false) }
    var order by remember { mutableStateOf<List<PosProduct>>(emptyList()) }
    var picking by remember { mutableStateOf<PosProduct?>(null) }
    var showCheckout by rememberSaveable { mutableStateOf(false) }
    var newCustomer by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    val scan = rememberBarcodeScanner { code -> vm.scan(code) { picking = it } }

    LaunchedEffect(arranging, vm.products) {
        if (arranging) order = (vm.products as? Load.Ready)?.value.orEmpty()
    }
    LaunchedEffect(vm.receipt) { if (vm.receipt != null) showCheckout = false }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(if (arranging) "ترتيب المنتجات" else "نقطة البيع") },
                actions = {
                    TextButton(onClick = scan, enabled = !arranging) { Text("باركود") }
                    if (canArrange) {
                        IconButton(onClick = {
                            if (!arranging && vm.query.isNotBlank()) vm.search("")
                            arranging = !arranging
                        }) {
                            Icon(Icons.Filled.Menu, contentDescription = "ترتيب المنتجات", tint = if (arranging) Lux.Gold else Lux.Ink)
                        }
                    }
                    IconButton(onClick = vm::loadShift) { Icon(Icons.Filled.DateRange, contentDescription = "ملخص الوردية") }
                    IconButton(onClick = openBoxes) { Icon(Icons.Filled.Lock, contentDescription = "الصناديق") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 900.dp
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (arranging) {
                        ArrangeBar(
                            busy = vm.busy,
                            onCancel = { arranging = false },
                            onSave = { vm.arrange(order) { arranging = false } },
                        )
                    } else {
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
                    }
                    // The grid fills only the space left above the cart bar.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LoadContent(vm.products, onRetry = vm::load, modifier = Modifier.fillMaxSize()) { list ->
                            if (list.isEmpty() && !arranging) {
                                EmptyState(Icons.Filled.Search, "لا توجد منتجات مطابقة")
                            } else if (arranging) {
                                ArrangeGrid(order, vm.currency, vm.rateOf(vm.currency) ?: BigDecimal.ONE) { order = it }
                            } else {
                                ProductGrid(list, vm) { p -> vm.tap(p) { picking = it } }
                            }
                        }
                    }
                    if (!wide && !arranging && vm.cart.isNotEmpty()) {
                        CartBar(vm) { showCheckout = true }
                    }
                }
                if (wide && !arranging) {
                    GlassCard(Modifier.width(420.dp).fillMaxHeight().padding(8.dp), contentPadding = 0.dp) {
                        Text(
                            "الفاتورة",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        )
                        CheckoutContent(vm, onNewCustomer = { newCustomer = true }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    picking?.let { p ->
        VariantPicker(p, vm, onDismiss = { picking = null }) { v ->
            vm.add(p, v)
            picking = null
        }
    }

    if (showCheckout) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showCheckout = false }, sheetState = sheet, containerColor = Lux.Pearl) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("الفاتورة والدفع", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { showCheckout = false }) { Icon(Icons.Filled.Close, contentDescription = "إغلاق") }
            }
            CheckoutContent(vm, onNewCustomer = { newCustomer = true }, modifier = Modifier.fillMaxWidth().fillMaxHeight())
        }
    }

    if (newCustomer) {
        QuickCustomerDialog(onDismiss = { newCustomer = false }) { name, phone -> vm.quickCustomer(name, phone) }
    }

    vm.receipt?.let { r -> ReceiptDialog(r, onNewSale = vm::newSale) }

    vm.shift?.let { s -> ShiftDialog(s, onRetry = vm::loadShift, onDismiss = vm::closeShift, openBoxes = { vm.closeShift(); openBoxes() }) }
}

// ------------------------------------------------------------------ grid ---

@Composable
private fun ProductGrid(list: List<PosProduct>, vm: PosViewModel, onTap: (PosProduct) -> Unit) {
    val rate = vm.rateOf(vm.currency) ?: BigDecimal.ONE
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(list, key = { it.id }) { p ->
            val inCart = p.variants.sumOf { vm.inCart(it.id) }
            ProductTile(p, vm.currency, rate, inCart = inCart, onClick = { onTap(p) })
        }
    }
}

@Composable
private fun ArrangeGrid(order: List<PosProduct>, currency: Currency, rate: BigDecimal, onOrder: (List<PosProduct>) -> Unit) {
    val gridState = rememberLazyGridState()
    var current by remember { mutableStateOf(order) }
    LaunchedEffect(order) { current = order }
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        current = current.toMutableList().apply { add(to.index, removeAt(from.index)) }
        onOrder(current)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(current, key = { it.id }) { p ->
            ReorderableItem(reorderState, key = p.id) { isDragging ->
                ProductTile(
                    p,
                    currency,
                    rate,
                    inCart = 0,
                    onClick = {},
                    lifted = isDragging,
                    tappable = false,
                    modifier = Modifier.longPressDraggableHandle(),
                )
            }
        }
    }
}

@Composable
private fun ArrangeBar(busy: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentPadding = 12.dp) {
        Text("اضغط مطولاً على المنتج ثم اسحبه إلى مكانه الجديد", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LuxButton("حفظ الترتيب", onClick = onSave, enabled = !busy, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCancel, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("إلغاء") }
        }
    }
}

private fun percentText(p: String): String = p.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: p

@Composable
private fun ProductTile(
    p: PosProduct,
    currency: Currency,
    rate: BigDecimal,
    inCart: Int,
    onClick: () -> Unit,
    lifted: Boolean = false,
    tappable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val out = p.available <= 0
    GlassCard(
        modifier.fillMaxWidth().alpha(if (out) 0.55f else 1f),
        elevation = if (lifted) 22.dp else 8.dp,
        contentPadding = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().then(if (tappable) Modifier.clickable(onClick = onClick) else Modifier).padding(8.dp)) {
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
                p.promotion?.let { promo ->
                    GoldPill(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                        Text("-${percentText(promo.percent)}%", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (inCart > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(Lux.Ink),
                        contentAlignment = Alignment.Center,
                    ) { Text("$inCart", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                }
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCCFFFFFF))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (out) "نفد" else "متاح ${p.available}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (out) MaterialTheme.colorScheme.error else Lux.InkSoft,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(p.nameAr, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.skuNumber?.let { Text("#$it", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    Money.format(Money.parse(p.price), currency, rate),
                    style = MaterialTheme.typography.titleMedium,
                    color = Lux.GoldDeep,
                    fontWeight = FontWeight.Bold,
                )
                if (Money.parse(p.price) < Money.parse(p.basePrice)) {
                    Text(
                        Money.format(Money.parse(p.basePrice), currency, rate),
                        style = MaterialTheme.typography.labelSmall,
                        color = Lux.InkSoft,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                    )
                }
            }
            if (!currency.isBase) Text(Money.formatBase(p.price), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
        }
    }
}

@Composable
private fun CartBar(vm: PosViewModel, onOpen: () -> Unit) {
    val t = vm.totals
    val rate = vm.rateOf(vm.currency) ?: BigDecimal.ONE
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = Lux.GoldDeep)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${vm.cart.sumOf { it.qty }} قطعة", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                Text(Money.format(t.total, vm.currency, rate), style = MaterialTheme.typography.titleLarge, color = Lux.GoldDeep)
            }
            LuxButton("متابعة الدفع ←", onClick = onOpen, modifier = Modifier.width(170.dp))
        }
    }
}

// ---------------------------------------------------------------- variants ---

private fun parseHex(hex: String?): Color? = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

@Composable
private fun VariantPicker(p: PosProduct, vm: PosViewModel, onDismiss: () -> Unit, onPick: (PosVariant) -> Unit) {
    val rate = vm.rateOf(vm.currency) ?: BigDecimal.ONE
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Lux.Pearl) {
        Text(p.nameAr, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Text("اختر اللون والمقاس", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(p.variants, key = { it.id }) { v ->
                val left = v.available - vm.inCart(v.id)
                GlassCard(Modifier.fillMaxWidth().alpha(if (left > 0) 1f else 0.5f), elevation = 4.dp, contentPadding = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = left > 0) { onPick(v) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val swatch = parseHex(v.colorHex)
                        if (swatch != null) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(swatch).border(1.dp, Lux.Hairline, CircleShape))
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(v.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (left > 0) "متاح $left" else "غير متوفر",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (left > 0) Lux.InkSoft else MaterialTheme.colorScheme.error,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Money.format(Money.parse(v.price), vm.currency, rate), style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
                            if (!vm.currency.isBase) Text(Money.formatBase(v.price), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- checkout ---

@Composable
private fun CheckoutContent(vm: PosViewModel, onNewCustomer: () -> Unit, modifier: Modifier = Modifier) {
    val t = vm.totals
    val inv = vm.currency
    val rate = vm.rateOf(inv) ?: BigDecimal.ONE
    fun inInvoice(sar: BigDecimal) = Money.format(sar, inv, rate)
    val discountNumber = vm.discountValue.trim().toBigDecimalOrNull()
    val discountValid = vm.discountValue.isBlank() || when (vm.discountType) {
        DiscountType.PERCENT -> discountNumber != null && discountNumber.signum() >= 0 && discountNumber <= HUNDRED
        DiscountType.AMOUNT -> discountNumber != null && discountNumber.signum() >= 0 &&
            discountNumber.stripTrailingZeros().scale() <= inv.decimals && discountNumber <= Money.convert(t.subtotal, inv, rate)
    }
    val tendersValid = vm.tenders.all { it.amount.isBlank() || validAmount(it.amount, it.currency) != null }
    val credit = vm.saleType == SaleType.CREDIT
    val canSell = vm.cart.isNotEmpty() && !vm.busy && discountValid && tendersValid &&
        (if (credit) vm.customer != null else t.owed.signum() == 0)

    Column(modifier) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

        // ---- lines
        if (vm.cart.isEmpty()) {
            item { Text("السلة فارغة. اختر المنتجات من الشبكة أو امسح الباركود.", color = Lux.InkSoft) }
        }
        items(vm.cart, key = { it.variant.id }) { l ->
            GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.product.nameAr, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${l.variant.title} · ${Money.format(Money.parse(l.variant.price), inv, rate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                        )
                    }
                    Text(inInvoice(l.lineTotal), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−", enabled = true) { vm.setQty(l.variant.id, l.qty - 1) }
                    Text("${l.qty}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                    StepButton("+", enabled = l.qty < l.variant.available) { vm.setQty(l.variant.id, l.qty + 1) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.setQty(l.variant.id, 0) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ---- currency and sale type
        item {
            InfoCard {
                Text("عملة الفاتورة", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.currencies.forEach { c ->
                        FilterChip(selected = c == inv, onClick = { vm.currency = c; vm.checkoutError = null }, label = { Text("${c.nameAr} (${c.symbol})") })
                    }
                }
                if (!inv.isBase) {
                    Text("1 ر.س = ${Money.plain(rate, inv)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
                Spacer(Modifier.height(4.dp))
                Text("نوع البيع", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !credit, onClick = { vm.saleType = SaleType.CASH; vm.checkoutError = null }, label = { Text("نقداً") })
                    FilterChip(selected = credit, onClick = { vm.saleType = SaleType.CREDIT; vm.checkoutError = null }, label = { Text("آجل") })
                }
            }
        }

        // ---- customer
        item { CustomerSection(vm, credit, onNewCustomer) }

        // ---- discount
        item {
            InfoCard {
                Text("خصم على الفاتورة", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.discountType == DiscountType.PERCENT, onClick = { vm.discountType = DiscountType.PERCENT }, label = { Text("%") })
                    FilterChip(selected = vm.discountType == DiscountType.AMOUNT, onClick = { vm.discountType = DiscountType.AMOUNT }, label = { Text("مبلغ") })
                    OutlinedTextField(
                        value = vm.discountValue,
                        onValueChange = { vm.discountValue = decimalInput(it) },
                        label = { Text(if (vm.discountType == DiscountType.PERCENT) "النسبة %" else "المبلغ (${inv.symbol})") },
                        isError = !discountValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- totals
        item {
            InfoCard {
                SummaryRow("المجموع", inInvoice(t.subtotal))
                if (t.discount.signum() > 0) SummaryRow("الخصم", "− ${inInvoice(t.discount)}", valueColor = MaterialTheme.colorScheme.error)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("الإجمالي", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    GoldPill { Text(inInvoice(t.total), color = Color.White, style = MaterialTheme.typography.titleMedium) }
                }
                if (!inv.isBase) Text("يعادل ${Money.formatBase(t.total.toPlainString())}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
        }

        // ---- how the money comes in, then the amounts
        item { PayMethodSection(vm) }
        item { TendersSection(vm, t, credit) }
    }
    // ---- submit (always visible)
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentPadding = 12.dp) {
        vm.checkoutError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp)) }
        val hint = when {
            vm.cart.isEmpty() -> "أضف منتجات أولاً"
            credit && vm.customer == null -> "اختر حساب العميل للبيع الآجل"
            !credit && t.owed.signum() > 0 -> "المتبقي ${inInvoice(t.owed)} — أكمل المبلغ أو اختر آجل"
            else -> null
        }
        hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp)) }
        LuxButton(
            if (vm.busy) "جارٍ الحفظ…" else "إتمام الفاتورة · ${inInvoice(t.total)}",
            onClick = vm::sell,
            enabled = canSell,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    }
}

@Composable
private fun PayMethodSection(vm: PosViewModel) {
    InfoCard {
        Text("طريقة الدفع", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PayMethod.entries.forEach { m ->
                FilterChip(selected = vm.payMethod == m, onClick = { vm.payMethod = m; vm.checkoutError = null }, label = { Text(m.label) })
            }
        }
        if (vm.payMethod != PayMethod.CASH) {
            OutlinedTextField(
                value = vm.reference,
                onValueChange = { vm.reference = it.take(100) },
                label = { Text(if (vm.payMethod == PayMethod.DEPOSIT) "رقم سند الإيداع" else "رقم الحوالة / المرجع") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // One bank account per currency being paid in.
            val used = vm.tenders.filter { it.amount.isNotBlank() }.map { it.currency }.ifEmpty { listOf(vm.currency) }.distinct()
            used.forEach { c ->
                val options = vm.banks.filter { it.currency == c }
                if (options.isEmpty()) {
                    Text("لا يوجد حساب بنكي بعملة ${c.nameAr}؛ أضفه من الصناديق والخزائن", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("الحساب البنكي (${c.nameAr})", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                    val chosen = vm.bankFor[c] ?: options.first().id
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { b -> FilterChip(selected = b.id == chosen, onClick = { vm.pickBank(c, b.id) }, label = { Text(b.name) }) }
                    }
                }
            }
        } else {
            Text("يدخل المبلغ في صندوقك بعملته، ويُصرف الباقي منه.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(if (enabled) Lux.GoldLight else Lux.Champagne)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) Lux.Ink else Lux.InkSoft) }
}

@Composable
private fun CustomerSection(vm: PosViewModel, credit: Boolean, onNewCustomer: () -> Unit) {
    InfoCard {
        Text(if (credit) "العميل (مطلوب للبيع الآجل)" else "العميل (اختياري)", style = MaterialTheme.typography.titleSmall)
        val c = vm.customer
        if (c != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Lux.GoldDeep)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(c.code, c.phone).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
                IconButton(onClick = vm::clearCustomer) { Icon(Icons.Filled.Close, contentDescription = "إزالة العميل") }
            }
            vm.customerBalances.filter { Money.parse(it.balance).signum() != 0 }.forEach { b ->
                val v = Money.parse(b.balance)
                SummaryRow(
                    if (v.signum() > 0) "مستحق على العميل" else "رصيد للعميل",
                    Money.plain(v.abs(), b.currency),
                    valueColor = if (v.signum() > 0) MaterialTheme.colorScheme.error else Success,
                )
            }
        } else {
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { e -> open = e; if (e) vm.searchCustomers(vm.customerQuery) }) {
                OutlinedTextField(
                    value = vm.customerQuery,
                    onValueChange = { vm.searchCustomers(it); open = true },
                    label = { Text("حساب العميل") },
                    placeholder = { Text("اختر أو ابحث بالاسم أو الهاتف") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                )
                ExposedDropdownMenu(expanded = open && vm.customerResults.isNotEmpty(), onDismissRequest = { open = false }) {
                    vm.customerResults.forEach { r ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(r.name, style = MaterialTheme.typography.bodyMedium)
                                    Text("${r.code} · ${r.phone}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                }
                            },
                            onClick = {
                                open = false
                                vm.pickCustomer(PosCustomer(r.id, r.name, r.phone, r.code))
                            },
                        )
                    }
                }
            }
            TextButton(onClick = onNewCustomer) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("عميل جديد")
            }
        }
    }
}

@Composable
private fun TendersSection(vm: PosViewModel, t: PosTotals, credit: Boolean) {
    val inv = vm.currency
    val rate = vm.rateOf(inv) ?: BigDecimal.ONE
    var adding by remember { mutableStateOf(false) }
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("المدفوع", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val more = vm.currencies.filter { c -> vm.tenders.none { it.currency == c } }
            if (more.isNotEmpty()) {
                Box {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "إضافة عملة دفع") }
                    DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                        more.forEach { c ->
                            DropdownMenuItem(text = { Text("${c.nameAr} (${c.symbol})") }, onClick = {
                                vm.addTender(c)
                                adding = false
                            })
                        }
                    }
                }
            }
        }
        val defaults = setOf(Currency.SAR, Currency.YER)
        vm.tenders.forEachIndexed { i, tender ->
            val c = tender.currency
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tender.amount,
                    onValueChange = { vm.setTender(i, it) },
                    label = { Text("${c.nameAr} (${c.symbol})") },
                    isError = tender.amount.isNotBlank() && validAmount(tender.amount, c) == null,
                    supportingText = if (c.decimals == 0) ({ Text("بدون كسور") }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.fillRemaining(i) }) { Text("المتبقي") }
                if (c !in defaults) {
                    IconButton(onClick = { vm.removeTender(i) }) { Icon(Icons.Filled.Close, contentDescription = "حذف") }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        val others = vm.currencies.filter { it != inv }
        when {
            t.owed.signum() > 0 -> {
                SummaryRow(
                    if (credit) "على حساب العميل" else "المتبقي",
                    Money.format(t.owed, inv, rate),
                    strong = true,
                    valueColor = if (credit) Lux.GoldDeep else MaterialTheme.colorScheme.error,
                )
                Equivalents(t.owed, others, vm)
            }
            t.change.signum() > 0 -> {
                val changeCur = vm.changeCurrency ?: inv
                SummaryRow("الباقي للعميل", Money.plain(t.changeAmount, changeCur), strong = true, valueColor = Success)
                Equivalents(t.change, vm.currencies.filter { it != changeCur }, vm)
                Text("عملة الباقي", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.currencies.forEach { c ->
                        FilterChip(selected = c == changeCur, onClick = { vm.changeCurrency = c }, label = { Text(c.nameAr) })
                    }
                }
            }
            vm.cart.isNotEmpty() -> SummaryRow("المتبقي", Money.format(BigDecimal.ZERO, inv, rate), strong = true, valueColor = Success)
        }
    }
}

@Composable
private fun Equivalents(amountSar: BigDecimal, currencies: List<Currency>, vm: PosViewModel) {
    val shown = currencies.mapNotNull { c -> vm.rateOf(c)?.let { Money.format(amountSar, c, it) } }
    if (shown.isNotEmpty()) {
        Text("يعادل: ${shown.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
    }
}

// ----------------------------------------------------------------- dialogs ---

@Composable
private fun QuickCustomerDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    val valid = name.trim().length >= 2 && phone.count { it.isDigit() } >= 6
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("عميل جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("الاسم") }, singleLine = true)
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' }.take(20) },
                    label = { Text("رقم الهاتف") },
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

@Composable
private fun ReceiptDialog(r: PosReceipt, onNewSale: () -> Unit) {
    val cur = r.currency
    fun inCur(v: String) = Money.plain(Money.parse(v), cur)
    AlertDialog(
        onDismissRequest = {},
        title = { Text("تمت عملية البيع ✓") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SummaryRow("رقم الفاتورة", r.number, strong = true)
                SummaryRow("التاريخ", formatDateTime(r.placedAt))
                r.customer?.let { SummaryRow("العميل", it.name) }
                r.cashier?.let { SummaryRow("الكاشير", it.name) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                r.items.forEach { i ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(i.productName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(i.color, i.size).joinToString(" · ").ifBlank { i.sku } + " × ${i.quantity}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Lux.InkSoft,
                            )
                        }
                        Text(r.inInvoiceCurrency(i.lineTotal), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SummaryRow("المجموع", inCur(r.inCurrency.subtotal))
                if (!Money.isZero(r.inCurrency.discountTotal)) SummaryRow("الخصم", "− ${inCur(r.inCurrency.discountTotal)}")
                SummaryRow("الإجمالي", inCur(r.inCurrency.grandTotal), strong = true, valueColor = Lux.GoldDeep)
                if (!cur.isBase) {
                    Text("يعادل ${Money.formatBase(r.grandTotal)} · السعر ${r.exchangeRate}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
                if (r.payments.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                r.payments.forEach { p ->
                    val label = when {
                        p.isChange -> "الباقي المُرجَع"
                        p.method == PaymentMethod.CUSTOMER_ACCOUNT -> "على حساب العميل"
                        else -> "مدفوع نقداً"
                    }
                    val value = p.currencyAmount?.let { Money.plain(Money.parse(it).abs(), p.currency) } ?: Money.formatBase(Money.parse(p.amount).abs().toPlainString())
                    SummaryRow(label, value, valueColor = if (p.isChange) Success else Color.Unspecified)
                }
                if (!Money.isZero(r.onAccount) && r.payments.none { it.method == PaymentMethod.CUSTOMER_ACCOUNT }) {
                    SummaryRow("على حساب العميل", r.inInvoiceCurrency(r.onAccount), valueColor = Lux.GoldDeep)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onNewSale,
                colors = ButtonDefaults.buttonColors(containerColor = Lux.GoldDeep, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) { Text("بيع جديد") }
        },
    )
}

@Composable
private fun ShiftDialog(state: Load<PosShift>, onRetry: () -> Unit, onDismiss: () -> Unit, openBoxes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ملخص الوردية") },
        text = {
            when (state) {
                Load.Loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> Column {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
                }
                is Load.Ready -> {
                    val s = state.value
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SummaryRow("عدد الفواتير", "${s.count}", strong = true)
                        SummaryRow("إجمالي المبيعات", Money.formatBase(s.total), strong = true, valueColor = Lux.GoldDeep)
                        if (s.boxes.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text("أدراجي", style = MaterialTheme.typography.titleSmall)
                            s.boxes.forEach { b -> SummaryRow(b.name, b.shownBalance, valueColor = Lux.GoldDeep) }
                        }
                        if (s.sales.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text("آخر الفواتير", style = MaterialTheme.typography.titleSmall)
                            s.sales.forEach { x ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(x.number, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            listOfNotNull(formatDateTime(x.placedAt), x.customer?.name).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Lux.InkSoft,
                                        )
                                    }
                                    Text(Money.plain(Money.parse(x.grandTotalInCurrency), x.currency), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
        dismissButton = { TextButton(onClick = openBoxes) { Text("الصناديق") } },
    )
}

// ----------------------------------------------------------------- shared ---

/** Gold primary button of the white & gold admin theme. */
@Composable
internal fun LuxButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Lux.GoldDeep, contentColor = Color.White),
    ) { Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
