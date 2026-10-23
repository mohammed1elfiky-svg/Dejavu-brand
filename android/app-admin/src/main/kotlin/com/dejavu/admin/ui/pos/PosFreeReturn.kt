@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.admin.print.Printers
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.barcode.rememberBarcodeScanner
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminCustomerSummary
import com.dejavu.core.admin.CashBox
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.FreeReturnItem
import com.dejavu.core.admin.FreeReturnRequest
import com.dejavu.core.admin.FreeReturnResult
import com.dejavu.core.admin.PosItem
import com.dejavu.core.admin.PosProduct
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.PosTender
import com.dejavu.core.admin.PosVariant
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal

// ------------------------------------------------------------------ model ---

/** A piece brought back without its invoice; [price] = value per piece typed by the cashier ("" = shelf price). */
data class FreeLine(val product: PosProduct, val variant: PosVariant, val qty: Int, val price: String = "", val restock: Boolean = true)

// -------------------------------------------------------------- viewmodel ---

class PosFreeReturnViewModel(
    private val pos: PosRepository,
    private val finance: FinanceRepository,
    private val treasury: TreasuryRepository,
) : BaseViewModel() {
    var currencies by mutableStateOf(listOf(Currency.SAR))
        private set
    var rates by mutableStateOf(mapOf(Currency.SAR to BigDecimal.ONE))
        private set

    /** Currency the returned pieces are valued in (and the exchange sale is made in). */
    var currency by mutableStateOf(Currency.SAR)
        private set
    val rate: BigDecimal get() = rates[currency] ?: BigDecimal.ONE

    // ---- customer (null = walk-in)
    var customer by mutableStateOf<PosCustomer?>(null)
        private set
    var customerQuery by mutableStateOf("")
        private set
    var customerResults by mutableStateOf<List<AdminCustomerSummary>>(emptyList())
        private set
    private var customerJob: Job? = null

    // ---- returned pieces
    var returned by mutableStateOf<List<FreeLine>>(emptyList())
        private set

    // ---- settlement
    var exchange by mutableStateOf(false)
    var refundTo by mutableStateOf(RefundTo.CASH)
    var refundCurrency by mutableStateOf(Currency.SAR)

    /** Boxes a cash refund can be paid from (any currency; filtered by [refundBoxes]). */
    var boxes by mutableStateOf<List<CashBox>>(emptyList())
        private set
    /** Box the refund is paid from; null = the cashier's own drawer. */
    var refundBoxId by mutableStateOf<String?>(null)

    // ---- product browser (all products, as a grid)
    var browseQuery by mutableStateOf("")
        private set
    var browseList by mutableStateOf<List<PosProduct>>(emptyList())
        private set
    var browseLoading by mutableStateOf(false)
        private set
    var browseError by mutableStateOf<String?>(null)
        private set
    private var browseJob: Job? = null

    // ---- product search: returned pieces (r) and new pieces (n)
    var rQuery by mutableStateOf("")
        private set
    var rResults by mutableStateOf<List<PosProduct>>(emptyList())
        private set
    var rSearching by mutableStateOf(false)
        private set
    var nQuery by mutableStateOf("")
        private set
    var nResults by mutableStateOf<List<PosProduct>>(emptyList())
        private set
    var nSearching by mutableStateOf(false)
        private set
    private var rJob: Job? = null
    private var nJob: Job? = null

    // ---- exchange
    var lines by mutableStateOf<List<CartLine>>(emptyList())
        private set
    var tenders by mutableStateOf(listOf(TenderInput(Currency.SAR, "")))
        private set

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<FreeReturnResult?>(null)
        private set
    /** Currency the refund was paid / kept in, and the returned value, for the result dialog. */
    var resultCurrency by mutableStateOf(Currency.SAR)
        private set
    var resultReturned by mutableStateOf(BigDecimal.ZERO)
        private set
    var resultValueCurrency by mutableStateOf(Currency.SAR)
        private set

    init {
        loadCurrencies()
        loadBoxes()
    }

    private fun loadBoxes() {
        viewModelScope.launch { boxes = treasury.loadRefundBoxes() }
    }

    /** Boxes of the refund currency. */
    val refundBoxes: List<CashBox> get() = boxes.filter { it.currency == refundCurrency }

    /** The picked box when it still matches the refund currency. */
    val refundBox: CashBox? get() = refundBoxId?.let { id -> refundBoxes.firstOrNull { it.id == id } }

    /** Money is handed back (not kept as credit): the refund box applies. */
    val paysOutCash: Boolean get() = effectiveRefundTo == RefundTo.CASH && (!exchange || totals.leftover.signum() > 0)

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
                    resetTenders()
                },
                { say(it.userMessage()) },
            )
        }
    }

    private fun resetTenders() {
        val cs = listOf(Currency.SAR, currency).distinct().filter { it in rates }
        tenders = cs.map { c -> TenderInput(c, tenders.firstOrNull { it.currency == c }?.amount.orEmpty()) }
    }

    fun pickCurrency(c: Currency) {
        if (c == currency) return
        currency = c
        // Typed values were in the old currency.
        returned = returned.map { it.copy(price = "") }
        if (refundCurrency != Currency.SAR) refundCurrency = c
        resetTenders()
        error = null
    }

    // ---- customer

    fun searchCustomers(q: String) {
        customerQuery = q
        customerJob?.cancel()
        if (q.isBlank()) {
            customerResults = emptyList()
            return
        }
        customerJob = viewModelScope.launch {
            delay(300)
            customerResults = finance.customers(q).getOrNull()?.items.orEmpty().filter { it.code != "CUS-WALKIN" }.take(8)
        }
    }

    fun pickCustomer(c: PosCustomer) {
        customer = c
        customerQuery = ""
        customerResults = emptyList()
        error = null
    }

    fun clearCustomer() {
        customer = null
        refundTo = RefundTo.CASH
    }

    fun quickCustomer(name: String, phone: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            pos.quickCustomer(name, phone).fold(
                { pickCustomer(PosCustomer(it.id, it.name, it.phone, it.code)) },
                { e -> say(e.userMessage()) },
            )
            busy = false
        }
    }

    // ---- search

    fun search(q: String, forNew: Boolean) {
        if (forNew) nQuery = q else rQuery = q
        (if (forNew) nJob else rJob)?.cancel()
        if (q.isBlank()) {
            if (forNew) {
                nResults = emptyList()
                nSearching = false
            } else {
                rResults = emptyList()
                rSearching = false
            }
            return
        }
        val job = viewModelScope.launch {
            delay(350)
            if (forNew) nSearching = true else rSearching = true
            pos.products(q).fold(
                { list ->
                    val top = list.take(30)
                    if (forNew) nResults = top else rResults = top
                    Unit
                },
                { e -> say(e.userMessage()) },
            )
            if (forNew) nSearching = false else rSearching = false
        }
        if (forNew) nJob = job else rJob = job
    }

    fun scan(code: String, forNew: Boolean, choose: (PosProduct) -> Unit) {
        viewModelScope.launch {
            pos.lookup(code).orSay()?.let { found ->
                val variant = found.variantId?.let { id -> found.product.variants.firstOrNull { it.id == id } }
                if (variant != null) add(found.product, variant, forNew) else tap(found.product, forNew, choose)
            }
        }
    }

    /** Adds a one-variant product straight away; otherwise asks [choose] to show the colour / size picker. */
    fun tap(product: PosProduct, forNew: Boolean, choose: (PosProduct) -> Unit) {
        when {
            product.variants.isEmpty() -> say("لا توجد مقاسات أو ألوان لهذا المنتج")
            product.variants.size == 1 -> add(product, product.variants.first(), forNew)
            else -> choose(product)
        }
    }

    fun add(product: PosProduct, variant: PosVariant, forNew: Boolean) {
        if (forNew) addNew(product, variant) else addReturned(product, variant)
    }

    // ---- product browser

    /** Loads the product grid the first time the browser opens. */
    fun openBrowser() {
        if (browseList.isEmpty() && !browseLoading) fetchBrowse(0)
    }

    fun browse(q: String) {
        browseQuery = q
        fetchBrowse(350)
    }

    fun retryBrowse() = fetchBrowse(0)

    private fun fetchBrowse(wait: Long) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            if (wait > 0) delay(wait)
            browseLoading = true
            browseError = null
            pos.products(browseQuery).fold(
                { browseList = it },
                { e -> browseError = e.userMessage() },
            )
            browseLoading = false
        }
    }

    /** Pieces of [product] already picked (returned or new). */
    fun picked(product: PosProduct, forNew: Boolean): Int =
        if (forNew) product.variants.sumOf { inLines(it.id) } else returned.filter { it.product.id == product.id }.sumOf { it.qty }

    // ---- returned pieces

    fun addReturned(product: PosProduct, variant: PosVariant) {
        returned = if (returned.any { it.variant.id == variant.id }) {
            returned.map { if (it.variant.id == variant.id) it.copy(qty = it.qty + 1) else it }
        } else {
            returned + FreeLine(product, variant, 1)
        }
        error = null
    }

    fun setReturnedQty(variantId: String, q: Int) {
        returned = if (q <= 0) returned.filterNot { it.variant.id == variantId } else returned.map { if (it.variant.id == variantId) it.copy(qty = q.coerceAtMost(999)) else it }
        error = null
    }

    fun setPrice(variantId: String, price: String) {
        returned = returned.map { if (it.variant.id == variantId) it.copy(price = decimalInput(price).take(12)) else it }
        error = null
    }

    fun toggleRestock(variantId: String) {
        returned = returned.map { if (it.variant.id == variantId) it.copy(restock = !it.restock) else it }
    }

    /** Shelf price of [v] in the return's currency. */
    fun shelf(v: PosVariant): BigDecimal = Money.convert(Money.parse(v.price), currency, rate)

    fun unitValue(l: FreeLine): BigDecimal = validAmount(l.price, currency) ?: shelf(l.variant)

    fun priceInvalid(l: FreeLine): Boolean = l.price.isNotBlank() && validAmount(l.price, currency) == null

    val returnedCount: Int get() = returned.sumOf { it.qty }

    /** Value of the returned pieces in [currency]. */
    val returnedShown: BigDecimal get() = returned.fold(BigDecimal.ZERO) { acc, l -> acc.add(unitValue(l).multiply(BigDecimal(l.qty))) }

    val returnedSar: BigDecimal get() = if (currency.isBase) returnedShown else Money.toBase(returnedShown, currency, rate)

    // ---- exchange

    val totals: ExchangeTotals get() = computeExchange(lines, currency, rates, returnedShown, tenders, refundCurrency)

    fun inLines(variantId: String): Int = lines.firstOrNull { it.variant.id == variantId }?.qty ?: 0

    fun addNew(product: PosProduct, variant: PosVariant) {
        val have = inLines(variant.id)
        if (have + 1 > variant.available) {
            say(if (variant.available <= 0) "${product.nameAr} (${variant.title}) غير متوفر" else "المتاح من ${product.nameAr} (${variant.title}) ${variant.available} فقط")
            return
        }
        lines = if (have == 0) lines + CartLine(product, variant, 1) else lines.map { if (it.variant.id == variant.id) it.copy(qty = it.qty + 1) else it }
        error = null
    }

    fun setLineQty(variantId: String, q: Int) {
        val line = lines.firstOrNull { it.variant.id == variantId } ?: return
        lines = when {
            q <= 0 -> lines.filterNot { it.variant.id == variantId }
            q > line.variant.available -> {
                say("المتاح ${line.variant.available} فقط")
                lines
            }
            else -> lines.map { if (it.variant.id == variantId) it.copy(qty = q) else it }
        }
        error = null
    }

    fun setTender(index: Int, amount: String) {
        tenders = tenders.mapIndexed { i, t -> if (i == index) t.copy(amount = decimalInput(amount)) else t }
        error = null
    }

    fun fillRemaining(index: Int) {
        val t = tenders.getOrNull(index) ?: return
        val r = rates[t.currency] ?: return
        val others = tenders.mapIndexed { i, x -> if (i == index) x.copy(amount = "") else x }
        val owed = computeExchange(lines, currency, rates, returnedShown, others, refundCurrency).owed
        val amount = if (owed.signum() > 0) Money.convert(owed, t.currency, r).stripTrailingZeros().toPlainString() else ""
        tenders = tenders.mapIndexed { i, x -> if (i == index) x.copy(amount = amount) else x }
        error = null
    }

    // ---- confirm

    val effectiveRefundTo: RefundTo get() = if (customer == null) RefundTo.CASH else refundTo

    fun confirm() {
        if (busy || returned.isEmpty()) return
        if (returned.any { priceInvalid(it) }) {
            error = "تحقق من قيمة القطع المرتجعة"
            return
        }
        val t = totals
        val to = effectiveRefundTo
        val payments = if (exchange) {
            tenders.mapNotNull { x -> validAmount(x.amount, x.currency)?.let { PosTender(x.currency, it.stripTrailingZeros().toPlainString(), method = "CASH") } }
        } else {
            emptyList()
        }
        val request = FreeReturnRequest(
            items = returned.map { l ->
                FreeReturnItem(l.variant.id, l.qty, unitPrice = validAmount(l.price, currency)?.stripTrailingZeros()?.toPlainString(), restock = l.restock)
            },
            customerId = customer?.id,
            currency = currency,
            refundTo = to.wire,
            refundCurrency = if (to == RefundTo.ACCOUNT) currency else refundCurrency,
            refundBoxId = refundBox?.id?.takeIf { paysOutCash },
            newItems = if (exchange) lines.map { PosItem(it.variant.id, it.qty) } else null,
            payments = payments.takeIf { exchange && it.isNotEmpty() },
            paymentMethod = "CASH".takeIf { exchange },
            changeCurrency = refundCurrency.takeIf { exchange && t.change.signum() > 0 },
        )
        resultCurrency = if (to == RefundTo.ACCOUNT) currency else refundCurrency
        resultReturned = returnedShown
        resultValueCurrency = currency
        busy = true
        error = null
        viewModelScope.launch {
            pos.returnWithoutInvoice(request).fold(
                { result = it },
                { e -> error = e.userMessage() },
            )
            busy = false
        }
    }

    fun reset() {
        result = null
        customer = null
        customerQuery = ""
        customerResults = emptyList()
        returned = emptyList()
        exchange = false
        refundTo = RefundTo.CASH
        refundBoxId = null
        rQuery = ""
        rResults = emptyList()
        nQuery = ""
        nResults = emptyList()
        lines = emptyList()
        tenders = tenders.map { it.copy(amount = "") }
        error = null
    }
}

// ----------------------------------------------------------------- screen ---

/** Return / exchange without the invoice: the cashier picks the returned pieces by hand. */
@Composable
internal fun FreeReturnContent(vm: PosFreeReturnViewModel, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf<Pair<PosProduct, Boolean>?>(null) }
    /** Product browser open for the returned pieces (false) or the new ones (true). */
    var browsing by remember { mutableStateOf<Boolean?>(null) }
    var newCustomer by remember { mutableStateOf(false) }
    val scanReturned = rememberBarcodeScanner { code -> vm.scan(code, forNew = false) { picking = it to false } }
    val scanNew = rememberBarcodeScanner { code -> vm.scan(code, forNew = true) { picking = it to true } }

    Column(modifier) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FreeCustomerSection(vm, onNewCustomer = { newCustomer = true })
            FreeReturnedSection(vm, onScan = scanReturned, onBrowse = { browsing = false }, onPick = { picking = it to false })
            FreeModeSection(vm)
            if (vm.exchange) {
                FreeExchangeSection(vm, onScan = scanNew, onBrowse = { browsing = true }, onPick = { picking = it to true })
            } else {
                FreeRefundSection(vm)
            }
        }
        FreeSubmitBar(vm)
    }

    browsing?.let { forNew ->
        ProductBrowserSheet(vm, forNew, onDismiss = { browsing = null }) { p -> vm.tap(p, forNew = forNew, choose = { picking = it to forNew }) }
    }

    picking?.let { (p, forNew) ->
        VariantSheet(
            p,
            vm.currency,
            vm.rate,
            left = if (forNew) ({ v -> v.available - vm.inLines(v.id) }) else null,
            onDismiss = { picking = null },
        ) { v ->
            vm.add(p, v, forNew)
            picking = null
        }
    }
    if (newCustomer) {
        QuickCustomerDialog(onDismiss = { newCustomer = false }) { name, phone -> vm.quickCustomer(name, phone) }
    }
    vm.result?.let { r -> FreeResultDialog(r, vm, onNew = vm::reset) }
}

@Composable
private fun FreeCustomerSection(vm: PosFreeReturnViewModel, onNewCustomer: () -> Unit) {
    RSection {
        val c = vm.customer
        if (c != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Lux.GoldDeep, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(c.code, c.phone).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, maxLines = 1)
                }
                IconButton(onClick = vm::clearCustomer, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "إزالة العميل", tint = Lux.InkSoft, modifier = Modifier.size(18.dp))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("العميل: نقدي (افتراضي)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                RLink("+ عميل جديد", onClick = onNewCustomer)
            }
            OutlinedTextField(
                value = vm.customerQuery,
                onValueChange = vm::searchCustomers,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("ابحث عن عميل بالاسم أو الهاتف (اختياري)") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            vm.customerResults.forEach { r ->
                key(r.id) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.pickCustomer(r.toPos()) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${r.code} · ${r.phone}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                    }
                }
            }
        }
        if (vm.currencies.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("العملة:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
                vm.currencies.forEach { c2 -> RChip("${c2.nameAr} (${c2.symbol})", selected = vm.currency == c2) { vm.pickCurrency(c2) } }
            }
        }
    }
}

/** Search box + matching products; [enabled] decides which ones can be tapped. */
@Composable
private fun ProductSearch(
    query: String,
    onQuery: (String) -> Unit,
    searching: Boolean,
    results: List<PosProduct>,
    cur: Currency,
    rate: BigDecimal,
    enabled: (PosProduct) -> Boolean,
    onTap: (PosProduct) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) ({
            IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "مسح") }
        }) else null,
        placeholder = { Text("ابحث بالاسم أو رقم الصنف") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (query.isNotBlank() && !searching && results.isEmpty()) {
        Text("لا توجد منتجات مطابقة", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
    }
    if (results.isNotEmpty()) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            results.forEach { p ->
                key(p.id) {
                    val ok = enabled(p)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = ok) { onTap(p) }
                            .alpha(if (ok) 1f else 0.5f).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(p.nameAr, p.imageUrl ?: p.variants.firstNotNullOfOrNull { it.imageUrl })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.nameAr, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(p.skuNumber?.let { "#$it" }, if (p.available <= 0) "نفد" else "متاح ${p.available}").joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = Lux.InkSoft,
                            )
                        }
                        Text(Money.format(Money.parse(p.price), cur, rate), style = MaterialTheme.typography.labelLarge, color = Lux.GoldDeep)
                    }
                }
            }
        }
    }
}

@Composable
private fun FreeReturnedSection(vm: PosFreeReturnViewModel, onScan: () -> Unit, onBrowse: () -> Unit, onPick: (PosProduct) -> Unit) {
    val cur = vm.currency
    RSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("القطع المرتجعة", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            RLink("تصفح المنتجات", onClick = onBrowse)
            RLink("باركود", onClick = onScan)
        }
        ProductSearch(
            query = vm.rQuery,
            onQuery = { vm.search(it, forNew = false) },
            searching = vm.rSearching,
            results = vm.rResults,
            cur = cur,
            rate = vm.rate,
            enabled = { it.variants.isNotEmpty() },
            onTap = { p -> vm.tap(p, forNew = false, choose = onPick) },
        )
        if (vm.returned.isEmpty()) {
            Text("ابحث عن الصنف المرتجع أو تصفح المنتجات أو امسح باركوده، ثم حدد الكمية والقيمة.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        } else {
            HorizontalDivider(color = Lux.Hairline)
            vm.returned.forEach { l -> key(l.variant.id) { FreeLineRow(l, vm) } }
        }
    }
}

/** Searchable grid of all products; tapping one adds it (or asks for its colour / size). */
@Composable
private fun ProductBrowserSheet(vm: PosFreeReturnViewModel, forNew: Boolean, onDismiss: () -> Unit, onTap: (PosProduct) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { vm.openBrowser() }
    val cur = vm.currency
    val rate = vm.rate
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Lux.Pearl) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("تصفح المنتجات", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (forNew) "اختر القطع التي يأخذها العميل" else "اختر القطع المرتجعة",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                    )
                }
                TextButton(onClick = onDismiss) { Text("تم") }
            }
            OutlinedTextField(
                value = vm.browseQuery,
                onValueChange = vm::browse,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (vm.browseQuery.isNotEmpty()) ({
                    IconButton(onClick = { vm.browse("") }) { Icon(Icons.Filled.Close, contentDescription = "مسح") }
                }) else null,
                placeholder = { Text("ابحث بالاسم أو رقم الصنف") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (vm.browseLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val error = vm.browseError
        when {
            error != null && vm.browseList.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Button(onClick = vm::retryBrowse, shape = RoundedCornerShape(16.dp)) { Text("إعادة المحاولة") }
            }
            !vm.browseLoading && vm.browseList.isEmpty() -> Text(
                "لا توجد منتجات مطابقة",
                style = MaterialTheme.typography.bodyMedium,
                color = Lux.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(130.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(vm.browseList, key = { it.id }) { p ->
                    val ok = if (forNew) p.available > 0 else p.variants.isNotEmpty()
                    BrowseTile(p, cur, rate, picked = vm.picked(p, forNew), enabled = ok, onClick = { onTap(p) })
                }
            }
        }
    }
}

@Composable
private fun BrowseTile(p: PosProduct, cur: Currency, rate: BigDecimal, picked: Int, enabled: Boolean, onClick: () -> Unit) {
    val out = p.available <= 0
    GlassCard(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        elevation = 4.dp,
        contentPadding = 0.dp,
        onClick = if (enabled) onClick else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Lux.Champagne),
                contentAlignment = Alignment.Center,
            ) {
                val image = p.imageUrl ?: p.variants.firstNotNullOfOrNull { it.imageUrl }
                if (image != null) {
                    AsyncImage(model = image, contentDescription = p.nameAr, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(p.nameAr.take(1), style = MaterialTheme.typography.headlineSmall, color = Lux.GoldDeep)
                }
                if (picked > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Lux.Ink),
                        contentAlignment = Alignment.Center,
                    ) { Text("$picked", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                }
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCCFFFFFF))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (out) "نفد" else "متاح ${p.available}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (out) MaterialTheme.colorScheme.error else Lux.InkSoft,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(p.nameAr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.skuNumber?.let { Text("#$it", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) }
            Text(Money.format(Money.parse(p.price), cur, rate), style = MaterialTheme.typography.labelLarge, color = Lux.GoldDeep)
        }
    }
}

@Composable
private fun FreeLineRow(l: FreeLine, vm: PosFreeReturnViewModel) {
    val cur = vm.currency
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Thumb(l.product.nameAr, l.variant.imageUrl ?: l.product.imageUrl)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(l.product.nameAr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(l.variant.title, "سعر الرف ${Money.plain(vm.shelf(l.variant), cur)}").joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Lux.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RStep("−", enabled = true) { vm.setReturnedQty(l.variant.id, l.qty - 1) }
            Text("${l.qty}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
            RStep("+", enabled = true) { vm.setReturnedQty(l.variant.id, l.qty + 1) }
            IconButton(onClick = { vm.setReturnedQty(l.variant.id, 0) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RField(
                value = l.price,
                onValueChange = { vm.setPrice(l.variant.id, it) },
                placeholder = "القيمة للقطعة: ${Money.plain(vm.shelf(l.variant), cur)}",
                isError = vm.priceInvalid(l),
                trailing = { Text(cur.symbol, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) },
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.toggleRestock(l.variant.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = l.restock, onCheckedChange = { vm.toggleRestock(l.variant.id) })
                Text("يُعاد للمخزون", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "الإجمالي ${Money.plain(vm.unitValue(l).multiply(BigDecimal(l.qty)), cur)}",
            style = MaterialTheme.typography.labelLarge,
            color = Lux.GoldDeep,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FreeModeSection(vm: PosFreeReturnViewModel) {
    RSection {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("العملية:", style = MaterialTheme.typography.labelLarge)
            RChip("مرتجع", selected = !vm.exchange) { vm.exchange = false; vm.error = null }
            RChip("استبدال", selected = vm.exchange) { vm.exchange = true; vm.error = null }
        }
        val cur = vm.currency
        RRow("قيمة القطع المرتجعة (${vm.returnedCount})", Money.plain(vm.returnedShown, cur), valueColor = Lux.GoldDeep, strong = true)
        if (!cur.isBase) Text("يعادل ${Money.formatBase(vm.returnedSar.toPlainString())}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    }
}

@Composable
private fun FreeRefundChoices(vm: PosFreeReturnViewModel, title: String) {
    val walkIn = vm.customer == null
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RChip("نقداً من الصندوق", selected = vm.effectiveRefundTo == RefundTo.CASH) { vm.refundTo = RefundTo.CASH; vm.error = null }
        RChip("رصيد للعميل", selected = vm.effectiveRefundTo == RefundTo.ACCOUNT, enabled = !walkIn) {
            vm.refundTo = RefundTo.ACCOUNT
            vm.error = null
        }
    }
    if (walkIn) Text("لحفظ المبلغ رصيداً اختر عميلاً مسجّلاً", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    if (vm.effectiveRefundTo == RefundTo.CASH) {
        val options = listOf(Currency.SAR, vm.currency).distinct()
        if (options.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("عملة الإرجاع:", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.align(Alignment.CenterVertically))
                options.forEach { c -> RChip("${c.nameAr} (${c.symbol})", selected = vm.refundCurrency == c) { vm.refundCurrency = c; vm.error = null } }
            }
        }
        RefundBoxPicker(vm.refundBoxes, vm.refundBox) { vm.refundBoxId = it?.id; vm.error = null }
    }
}

@Composable
private fun FreeRefundSection(vm: PosFreeReturnViewModel) {
    RSection {
        FreeRefundChoices(vm, "إرجاع المبلغ")
        if (vm.returnedCount > 0) {
            val toAccount = vm.effectiveRefundTo == RefundTo.ACCOUNT
            val c = if (toAccount) vm.currency else vm.refundCurrency
            val shown = if (c == vm.currency) Money.plain(vm.returnedShown, c) else Money.format(vm.returnedSar, c, vm.rates[c] ?: BigDecimal.ONE)
            RRow(if (toAccount) "يُضاف لرصيد العميل" else "يُرجَع للعميل نقداً", "≈ $shown", valueColor = Success, strong = true)
        }
    }
}

@Composable
private fun FreeExchangeSection(vm: PosFreeReturnViewModel, onScan: () -> Unit, onBrowse: () -> Unit, onPick: (PosProduct) -> Unit) {
    val cur = vm.currency
    val rate = vm.rate
    RSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("القطع التي يأخذها العميل", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            RLink("تصفح المنتجات", onClick = onBrowse)
            RLink("باركود", onClick = onScan)
        }
        ProductSearch(
            query = vm.nQuery,
            onQuery = { vm.search(it, forNew = true) },
            searching = vm.nSearching,
            results = vm.nResults,
            cur = cur,
            rate = rate,
            enabled = { it.available > 0 },
            onTap = { p -> vm.tap(p, forNew = true, choose = onPick) },
        )
        if (vm.lines.isEmpty()) {
            Text("اختر القطع الجديدة بدلاً من المرتجعة.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        } else {
            HorizontalDivider(color = Lux.Hairline)
            vm.lines.forEach { l ->
                key(l.variant.id) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(l.product.nameAr, l.variant.imageUrl ?: l.product.imageUrl)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${l.product.nameAr} · ${l.variant.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Money.format(Money.parse(l.variant.price), cur, rate)} · متاح ${l.variant.available}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, maxLines = 1)
                        }
                        RStep("−", enabled = true) { vm.setLineQty(l.variant.id, l.qty - 1) }
                        Text("${l.qty}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                        RStep("+", enabled = l.qty < l.variant.available) { vm.setLineQty(l.variant.id, l.qty + 1) }
                        Text(
                            Money.format(l.lineTotal, cur, rate),
                            style = MaterialTheme.typography.labelLarge,
                            color = Lux.GoldDeep,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.widthIn(min = 64.dp).padding(start = 6.dp),
                        )
                        IconButton(onClick = { vm.setLineQty(l.variant.id, 0) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (vm.lines.isNotEmpty() && vm.returnedCount > 0) {
        val t = vm.totals
        RSection {
            RRow("قيمة المرتجع", Money.plain(vm.returnedShown, cur))
            RRow("قيمة القطع الجديدة", Money.plain(t.newTotalShown, cur))
            if (t.toPay.signum() > 0) {
                RRow("على العميل دفع الفرق", Money.format(t.toPay, cur, rate), valueColor = MaterialTheme.colorScheme.error, strong = true)
                HorizontalDivider(color = Lux.Hairline)
                TenderFields(vm.tenders, vm::setTender, vm::fillRemaining)
                when {
                    t.owed.signum() > 0 -> RRow("المتبقي", Money.format(t.owed, cur, rate), valueColor = MaterialTheme.colorScheme.error)
                    t.change.signum() > 0 -> {
                        RRow("الباقي للعميل", Money.plain(t.changeAmount, vm.refundCurrency), valueColor = Success)
                        val options = listOf(Currency.SAR, cur).distinct()
                        if (options.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("عملة الباقي:", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.align(Alignment.CenterVertically))
                                options.forEach { c -> RChip(c.symbol, selected = vm.refundCurrency == c) { vm.refundCurrency = c } }
                            }
                        }
                    }
                    else -> RRow("المتبقي", Money.plain(BigDecimal.ZERO, cur), valueColor = Success)
                }
            } else if (t.leftover.signum() > 0) {
                RRow("يتبقى للعميل", Money.plain(t.leftover, cur), valueColor = Success, strong = true)
                HorizontalDivider(color = Lux.Hairline)
                FreeRefundChoices(vm, "الفرق المتبقي للعميل")
            } else {
                RRow("لا فرق", "—", valueColor = Success, strong = true)
            }
            Text("تقدير: الأسعار النهائية (العروض / سعر التاجر) يحددها الخادم.", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
        }
    }
}

@Composable
private fun FreeSubmitBar(vm: PosFreeReturnViewModel) {
    val t = vm.totals
    val tendersValid = vm.tenders.all { it.amount.isBlank() || validAmount(it.amount, it.currency) != null }
    val hint = when {
        vm.returnedCount == 0 -> "أضف القطع المرتجعة وكمياتها"
        vm.returned.any { vm.priceInvalid(it) } -> "تحقق من قيمة القطع المرتجعة"
        vm.exchange && vm.lines.isEmpty() -> "اختر القطع الجديدة"
        vm.exchange && !tendersValid -> "تحقق من المبالغ المدفوعة"
        vm.exchange && t.owed.signum() > 0 -> "المتبقي على العميل ${Money.format(t.owed, vm.currency, vm.rate)}"
        else -> null
    }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentPadding = 10.dp) {
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)) }
        hint?.let { Text(it, color = Lux.InkSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)) }
        LuxButton(
            when {
                vm.busy -> "جارٍ الحفظ…"
                vm.exchange -> "تأكيد الاستبدال بدون فاتورة"
                else -> "تأكيد المرتجع · ${Money.plain(vm.returnedShown, vm.currency)}"
            },
            onClick = vm::confirm,
            enabled = !vm.busy && hint == null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------------------ result ---

@Composable
private fun FreeResultDialog(r: FreeReturnResult, vm: PosFreeReturnViewModel, onNew: () -> Unit) {
    val valueCur = vm.resultValueCurrency
    val refundCur = vm.resultCurrency
    val printerSettings by adminContainer().printers.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var printing by remember { mutableStateOf(false) }
    var printStatus by remember { mutableStateOf<String?>(null) }

    val sale = r.sale
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (sale != null) "تم الاستبدال ✓" else "تم المرتجع ✓") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SummaryRow("رقم العملية", r.number, strong = true)
                SummaryRow("قيمة القطع المرتجعة", Money.plain(vm.resultReturned, valueCur))
                val kept = Money.parse(r.keptAsCredit)
                val leftover = Money.parse(r.leftover)
                if (r.sale == null) {
                    if (kept.signum() > 0) {
                        SummaryRow("أُضيف لرصيد العميل", Money.plain(kept, valueCur), strong = true, valueColor = Success)
                    } else {
                        SummaryRow("المُرجَع نقداً", Money.plain(Money.parse(r.refunded.currencyAmount), refundCur), strong = true, valueColor = Success)
                        if (!refundCur.isBase) Text("يعادل ${Money.formatBase(r.refunded.amount)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    }
                }
                r.sale?.let { s ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SummaryRow("فاتورة البيع الجديدة", s.number, strong = true)
                    s.items.forEach { i ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${i.productName} · ${listOfNotNull(i.color, i.size).joinToString(" · ").ifBlank { i.sku }} × ${i.quantity}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(s.inInvoiceCurrency(i.lineTotal), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    SummaryRow("الإجمالي", Money.plain(Money.parse(s.inCurrency.grandTotal), s.currency), strong = true, valueColor = Lux.GoldDeep)
                    s.payments.forEach { p ->
                        val label = when {
                            p.isChange -> "الباقي المُرجَع"
                            p.method == PaymentMethod.CUSTOMER_ACCOUNT -> "من قيمة المرتجع"
                            else -> "مدفوع نقداً"
                        }
                        val value = p.currencyAmount?.let { Money.plain(Money.parse(it).abs(), p.currency) } ?: Money.formatBase(Money.parse(p.amount).abs().toPlainString())
                        SummaryRow(label, value, valueColor = if (p.isChange) Success else Color.Unspecified)
                    }
                    if (kept.signum() > 0) {
                        SummaryRow("بقي رصيداً للعميل", Money.plain(kept, valueCur), strong = true, valueColor = Success)
                    } else if (leftover.signum() > 0) {
                        SummaryRow("الفرق المُرجَع للعميل نقداً", Money.plain(leftover, valueCur), strong = true, valueColor = Success)
                        if (refundCur != valueCur) Text("يُصرف بعملة ${refundCur.nameAr}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    }
                    printStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                }
            }
        },
        confirmButton = {
            Button(onClick = onNew, shape = RoundedCornerShape(16.dp)) { Text("عملية جديدة") }
        },
        dismissButton = if (sale == null) null else ({
                TextButton(
                    onClick = {
                        if (!printerSettings.receipt.target.isSet) {
                            printStatus = "لم تُضبط طابعة الفواتير بعد: المزيد ← الطابعات"
                        } else if (!printing) {
                            printing = true
                            printStatus = "جارٍ الطباعة…"
                            scope.launch {
                                val res = Printers.printReceipt(context, printerSettings, sale)
                                printing = false
                                printStatus = res.fold({ "تمت طباعة الفاتورة ✓" }, { it.message ?: "تعذّرت الطباعة" })
                            }
                        }
                    },
                    enabled = !printing,
                ) { Text("طباعة الفاتورة") }
        }),
    )
}
