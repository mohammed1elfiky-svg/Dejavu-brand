@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.barcode.rememberBarcodeScanner
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminOrderItem
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.PosItem
import com.dejavu.core.admin.PosProduct
import com.dejavu.core.admin.PosReceipt
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.PosReturnItem
import com.dejavu.core.admin.PosReturnRequest
import com.dejavu.core.admin.PosReturnResult
import com.dejavu.core.admin.PosTender
import com.dejavu.core.admin.PosVariant
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Success
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

// ------------------------------------------------------------------ model ---

private const val WALK_IN = "CUS-WALKIN"

/** Where the value of the returned pieces goes (wire values of `refundTo`). */
enum class RefundTo(val wire: String) { CASH("CASH"), ACCOUNT("ACCOUNT") }

/** Client estimate of an exchange (server is authoritative). */
class ExchangeTotals(
    /** New pieces, SAR. */
    val newTotal: BigDecimal,
    /** New pieces in the sale's currency. */
    val newTotalShown: BigDecimal,
    /** Returned value used to pay the new pieces, in the sale's currency. */
    val credit: BigDecimal,
    /** Returned value not used (handed back or kept as credit), in the sale's currency. */
    val leftover: BigDecimal,
    /** What the customer still has to pay after credit and tenders, SAR. */
    val owed: BigDecimal,
    /** What the customer must add on top of the returned value (before tenders), SAR. */
    val toPay: BigDecimal,
    /** Overpaid tenders handed back, SAR, and in the change currency. */
    val change: BigDecimal,
    val changeAmount: BigDecimal,
)

private fun money2(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

/** SAR value of [qty] pieces of [item] at what was actually paid per piece. */
internal fun returnValue(item: AdminOrderItem, qty: Int): BigDecimal {
    if (qty <= 0 || item.quantity <= 0) return BigDecimal.ZERO
    return Money.parse(item.lineTotal).multiply(BigDecimal(qty)).divide(BigDecimal(item.quantity), 2, RoundingMode.HALF_UP)
}

/**
 * Mirror of the server's exchange: the new sale (in [invoice]) is paid first from the
 * returned value [returnedShown] (already in [invoice]), then by the [tenders] in order.
 */
internal fun computeExchange(
    lines: List<CartLine>,
    invoice: Currency,
    rates: Map<Currency, BigDecimal>,
    returnedShown: BigDecimal,
    tenders: List<TenderInput>,
    changeCurrency: Currency,
): ExchangeTotals {
    val rate = rates[invoice] ?: BigDecimal.ONE
    val total = money2(lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal) })
    val totalShown = Money.convert(total, invoice, rate)
    val credit = returnedShown.max(BigDecimal.ZERO).min(totalShown)
    val creditSar = when {
        credit.signum() == 0 -> BigDecimal.ZERO
        credit.compareTo(totalShown) == 0 -> total
        else -> Money.toBase(credit, invoice, rate)
    }
    val toPay = money2(total.subtract(creditSar).max(BigDecimal.ZERO))

    var paid = creditSar
    var last: Pair<Currency, BigDecimal>? = null
    for (t in tenders) {
        val amount = t.amount.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
        val r = rates[t.currency] ?: continue
        val left = total.subtract(paid)
        val sar = if (left.signum() > 0 && amount.compareTo(Money.convert(left, t.currency, r)) == 0) money2(left) else Money.toBase(amount, t.currency, r)
        paid = paid.add(sar)
        last = t.currency to r
    }
    val short = total.subtract(paid)
    if (last != null && short.signum() > 0 && Money.convert(short, last.first, last.second).signum() == 0) paid = total
    if (last == null && short.signum() > 0 && Money.convert(short, invoice, rate).signum() == 0) paid = total

    var change = if (paid > total) money2(paid.subtract(total)) else BigDecimal.ZERO
    val changeRate = rates[changeCurrency] ?: BigDecimal.ONE
    var changeAmount = if (change.signum() > 0) Money.convert(change, changeCurrency, changeRate) else BigDecimal.ZERO
    if (change.signum() > 0 && changeAmount.signum() == 0) {
        change = BigDecimal.ZERO
        changeAmount = BigDecimal.ZERO
    }
    val owed = if (paid < total) money2(total.subtract(paid)) else BigDecimal.ZERO
    return ExchangeTotals(total, totalShown, credit, returnedShown.subtract(credit).max(BigDecimal.ZERO), owed, toPay, change, changeAmount)
}

// -------------------------------------------------------------- viewmodel ---

class PosReturnViewModel(
    private val pos: PosRepository,
    private val finance: FinanceRepository,
) : BaseViewModel() {
    var currencies by mutableStateOf(listOf(Currency.SAR))
        private set
    var rates by mutableStateOf(mapOf(Currency.SAR to BigDecimal.ONE))
        private set

    // ---- the sale
    var number by mutableStateOf("")
    var finding by mutableStateOf(false)
        private set
    var findError by mutableStateOf<String?>(null)
        private set
    var sale by mutableStateOf<PosReceipt?>(null)
        private set

    // ---- what comes back
    /** Pieces returned per order item id. */
    var qty by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** Order item ids whose pieces are damaged (not put back on the shelf). */
    var damaged by mutableStateOf<Set<String>>(emptySet())
        private set
    var reason by mutableStateOf("")

    // ---- how it is settled
    var exchange by mutableStateOf(false)
    var refundTo by mutableStateOf(RefundTo.CASH)
    var refundCurrency by mutableStateOf(Currency.SAR)

    // ---- exchange
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<PosProduct>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var lines by mutableStateOf<List<CartLine>>(emptyList())
        private set
    var tenders by mutableStateOf<List<TenderInput>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<PosReturnResult?>(null)
        private set
    /** Currency the refund was paid in (for the result dialog). */
    var resultCurrency by mutableStateOf(Currency.SAR)
        private set

    private var searchJob: Job? = null

    init {
        loadCurrencies()
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
                    sale?.let { resetTenders(it) }
                },
                { say(it.userMessage()) },
            )
        }
    }

    // ---- sale

    val saleCurrency: Currency get() = sale?.currency ?: Currency.SAR

    /** The sale's locked rate (how its lines were priced). */
    val saleRate: BigDecimal get() = sale?.exchangeRate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE

    /** Today's rate of the sale currency (used by the new exchange sale); the sale's rate if none. */
    val currentRate: BigDecimal get() = rates[saleCurrency] ?: saleRate

    val isWalkIn: Boolean get() = sale?.customer?.code == WALK_IN || sale?.customer == null

    /** A credit (on-account) sale: the returned value always goes back to the account. */
    val isCreditSale: Boolean get() = sale?.let { s -> !Money.isZero(s.onAccount) || s.payments.any { it.method == PaymentMethod.CUSTOMER_ACCOUNT } } == true

    fun find(code: String = number) {
        val n = code.trim()
        if (n.isEmpty() || finding) return
        number = n
        finding = true
        findError = null
        viewModelScope.launch {
            pos.findSale(n).fold(
                { open(it) },
                { e -> findError = e.userMessage() },
            )
            finding = false
        }
    }

    private fun open(r: PosReceipt) {
        sale = r
        qty = emptyMap()
        damaged = emptySet()
        reason = ""
        exchange = false
        refundTo = RefundTo.CASH
        refundCurrency = r.currency
        lines = emptyList()
        query = ""
        results = emptyList()
        error = null
        resetTenders(r)
        if (r.items.none { (it.returnableQty ?: (it.quantity - it.returnedQty)) > 0 }) {
            findError = "كل قطع هذه الفاتورة أُرجعت مسبقاً"
        }
    }

    private fun resetTenders(r: PosReceipt) {
        val cs = listOf(Currency.SAR, r.currency).distinct().filter { it in rates }
        tenders = cs.map { c -> TenderInput(c, tenders.firstOrNull { it.currency == c }?.amount.orEmpty()) }
    }

    fun clearSale() {
        sale = null
        findError = null
        error = null
        number = ""
    }

    fun returnable(item: AdminOrderItem): Int = (item.returnableQty ?: (item.quantity - item.returnedQty)).coerceAtLeast(0)

    fun setQty(item: AdminOrderItem, q: Int) {
        qty = qty + (item.id to q.coerceIn(0, returnable(item)))
        error = null
    }

    fun toggleDamaged(itemId: String) {
        damaged = if (itemId in damaged) damaged - itemId else damaged + itemId
    }

    val returnedCount: Int get() = qty.values.sum()

    /** SAR value of everything being returned. */
    val returnedSar: BigDecimal
        get() = money2(sale?.items.orEmpty().fold(BigDecimal.ZERO) { acc, i -> acc.add(returnValue(i, qty[i.id] ?: 0)) })

    /** The same value in the sale's currency, at the sale's rate. */
    val returnedShown: BigDecimal get() = Money.convert(returnedSar, saleCurrency, saleRate)

    // ---- exchange

    val totals: ExchangeTotals
        get() = computeExchange(lines, saleCurrency, rates + (saleCurrency to currentRate), returnedShown, tenders, refundCurrency)

    fun search(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            searching = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            searching = true
            pos.products(q).fold(
                { results = it.take(30) },
                { e -> say(e.userMessage()) },
            )
            searching = false
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

    fun tap(product: PosProduct, pick: (PosProduct) -> Unit) {
        when {
            product.variants.isEmpty() -> say("لا توجد مقاسات أو ألوان لهذا المنتج")
            product.variants.size == 1 -> add(product, product.variants.first())
            else -> pick(product)
        }
    }

    fun inLines(variantId: String): Int = lines.firstOrNull { it.variant.id == variantId }?.qty ?: 0

    fun add(product: PosProduct, variant: PosVariant) {
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

    /** Puts what the customer still owes (ignoring this field) into tender [index]. */
    fun fillRemaining(index: Int) {
        val t = tenders.getOrNull(index) ?: return
        val r = (rates + (saleCurrency to currentRate))[t.currency] ?: return
        val others = tenders.mapIndexed { i, x -> if (i == index) x.copy(amount = "") else x }
        val owed = computeExchange(lines, saleCurrency, rates + (saleCurrency to currentRate), returnedShown, others, refundCurrency).owed
        val amount = if (owed.signum() > 0) Money.convert(owed, t.currency, r).stripTrailingZeros().toPlainString() else ""
        tenders = tenders.mapIndexed { i, x -> if (i == index) x.copy(amount = amount) else x }
        error = null
    }

    // ---- confirm

    fun confirm() {
        val s = sale ?: return
        if (busy || returnedCount == 0) return
        val t = totals
        val payments = if (exchange) {
            tenders.mapNotNull { x ->
                validAmount(x.amount, x.currency)?.let { PosTender(x.currency, it.stripTrailingZeros().toPlainString(), method = "CASH") }
            }
        } else {
            emptyList()
        }
        val to = if (isWalkIn) RefundTo.CASH else refundTo
        val request = PosReturnRequest(
            orderId = s.id,
            items = s.items.mapNotNull { i ->
                val q = qty[i.id] ?: 0
                if (q > 0) PosReturnItem(i.id, q, restock = i.id !in damaged) else null
            },
            reason = reason.trim().ifBlank { null },
            refundTo = to.wire,
            refundCurrency = refundCurrency,
            newItems = if (exchange) lines.map { PosItem(it.variant.id, it.qty) } else null,
            payments = payments.takeIf { exchange && it.isNotEmpty() },
            paymentMethod = "CASH".takeIf { exchange },
            changeCurrency = refundCurrency.takeIf { exchange && t.change.signum() > 0 },
        )
        // A credit sale, an exchange or a refund kept as credit stay in the sale's currency.
        resultCurrency = if (exchange || to == RefundTo.ACCOUNT || isCreditSale) s.currency else refundCurrency
        busy = true
        error = null
        viewModelScope.launch {
            pos.returnOrExchange(request).fold(
                { result = it },
                { e -> error = e.userMessage() },
            )
            busy = false
        }
    }

    fun reset() {
        result = null
        sale = null
        number = ""
        qty = emptyMap()
        damaged = emptySet()
        reason = ""
        exchange = false
        refundTo = RefundTo.CASH
        lines = emptyList()
        query = ""
        results = emptyList()
        tenders = tenders.map { it.copy(amount = "") }
        error = null
        findError = null
    }
}

// ----------------------------------------------------------------- screen ---

/** Returns and exchanges at the till: find the sale, pick the pieces, refund or swap them. */
@Composable
fun PosReturnScreen(@Suppress("UNUSED_PARAMETER") session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel { PosReturnViewModel(it.pos, it.finance) }
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<PosProduct?>(null) }
    CollectMessages(vm, snackbar)
    val scanReceipt = rememberBarcodeScanner { code -> vm.find(code) }
    val scanProduct = rememberBarcodeScanner { code -> vm.scan(code) { picking = it } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("مرتجع / استبدال", onBack) {
                if (vm.sale != null && vm.exchange) {
                    TextButton(onClick = scanProduct) { Text("باركود صنف") }
                } else {
                    TextButton(onClick = scanReceipt) { Text("مسح الفاتورة") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (vm.busy || vm.finding) LinearProgressIndicator(Modifier.fillMaxWidth())
                    FindSection(vm, scanReceipt)
                    vm.sale?.let { s ->
                        SaleSection(s, vm)
                        ModeSection(vm)
                        if (vm.exchange) {
                            ExchangeSection(vm, onScan = scanProduct, onPick = { picking = it })
                        } else {
                            RefundSection(vm)
                        }
                    }
                }
                if (vm.sale != null) SubmitBar(vm)
            }
        }
    }

    picking?.let { p ->
        ExchangeVariantPicker(p, vm, onDismiss = { picking = null }) { v ->
            vm.add(p, v)
            picking = null
        }
    }

    vm.result?.let { r -> ReturnResultDialog(r, vm, onNew = vm::reset) }
}

// ---------------------------------------------------------------- sections ---

@Composable
private fun RSection(content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = 4.dp, contentPadding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun RChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        modifier = Modifier.height(32.dp),
    )
}

@Composable
private fun RLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Lux.GoldDeep,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun RRow(label: String, value: String, valueColor: Color = Color.Unspecified, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall
        Text(label, style = style)
        Text(value, style = style, color = valueColor)
    }
}

@Composable
private fun RStep(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (enabled) Lux.GoldLight else Lux.Champagne)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleSmall, color = if (enabled) Lux.Ink else Lux.InkSoft) }
}

@Composable
private fun RField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Lux.Ink),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                OutlinedTextFieldDefaults.Container(enabled = true, isError = isError, interactionSource = interaction, shape = RoundedCornerShape(12.dp))
            },
        )
    }
}

@Composable
private fun Thumb(name: String, image: String? = null) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Lux.Champagne),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            AsyncImage(model = image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.take(1), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
        }
    }
}

@Composable
private fun FindSection(vm: PosReturnViewModel, onScan: () -> Unit) {
    RSection {
        Text("رقم الفاتورة", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.number,
                onValueChange = { vm.number = it.take(40) },
                placeholder = { Text("POS-2026-000012 أو SO-…") },
                singleLine = true,
                isError = vm.findError != null && vm.sale == null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.find() }),
                trailingIcon = if (vm.sale != null) ({
                    IconButton(onClick = vm::clearSale) { Icon(Icons.Filled.Close, contentDescription = "مسح") }
                }) else null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.find() }, enabled = !vm.finding && vm.number.isNotBlank()) {
                Icon(Icons.Filled.Search, contentDescription = "بحث", tint = Lux.GoldDeep)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("يمكن مسح باركود / QR الفاتورة", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.weight(1f))
            RLink("مسح الفاتورة", onClick = onScan)
        }
        vm.findError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SaleSection(s: PosReceipt, vm: PosReturnViewModel) {
    val cur = s.currency
    RSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.number, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(formatDateTime(s.placedAt), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Lux.GoldBrush).padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(Money.plain(Money.parse(s.inCurrency.grandTotal), cur), color = Color.White, style = MaterialTheme.typography.titleSmall) }
        }
        RRow("العميل", s.customer?.let { c -> listOfNotNull(c.name, c.code?.takeIf { it != WALK_IN }).joinToString(" · ") } ?: "—")
        RRow("العملة", "${cur.nameAr} (${cur.symbol})" + if (!cur.isBase) " · السعر ${s.exchangeRate}" else "")
        if (!cur.isBase) RRow("يعادل", Money.formatBase(s.grandTotal))
        if (vm.isCreditSale) Text("فاتورة آجلة: تُخصم قيمة المرتجع من حساب العميل", style = MaterialTheme.typography.labelSmall, color = Lux.GoldDeep)
        HorizontalDivider(color = Lux.Hairline)
        Text("القطع المرتجعة", style = MaterialTheme.typography.labelLarge)
        s.items.forEach { i -> key(i.id) { ReturnLineRow(i, s, vm) } }
        HorizontalDivider(color = Lux.Hairline)
        OutlinedTextField(
            value = vm.reason,
            onValueChange = { vm.reason = it.take(300) },
            label = { Text("سبب الإرجاع (اختياري)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReturnLineRow(i: AdminOrderItem, s: PosReceipt, vm: PosReturnViewModel) {
    val max = vm.returnable(i)
    val q = vm.qty[i.id] ?: 0
    val unit = if (i.quantity > 0) Money.parse(i.lineTotal).divide(BigDecimal(i.quantity), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
    Column(Modifier.fillMaxWidth().alpha(if (max > 0) 1f else 0.5f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Thumb(i.productName)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(i.productName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(i.color, i.size, i.sku).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Lux.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${s.inInvoiceCurrency(unit.toPlainString())} للقطعة · المباع ${i.quantity} · " +
                        if (max > 0) "يمكن إرجاع $max" else "لا يمكن الإرجاع",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (max > 0) Lux.InkSoft else MaterialTheme.colorScheme.error,
                )
            }
            RStep("−", enabled = q > 0) { vm.setQty(i, q - 1) }
            Text("$q", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
            RStep("+", enabled = q < max) { vm.setQty(i, q + 1) }
        }
        if (q > 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { vm.toggleDamaged(i.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = i.id in vm.damaged, onCheckedChange = { vm.toggleDamaged(i.id) })
                    Text("تالف / لا يُعاد للمخزون", style = MaterialTheme.typography.bodySmall)
                }
                Text(s.inInvoiceCurrency(returnValue(i, q).toPlainString()), style = MaterialTheme.typography.labelLarge, color = Lux.GoldDeep)
            }
        }
    }
}

@Composable
private fun ModeSection(vm: PosReturnViewModel) {
    RSection {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("العملية:", style = MaterialTheme.typography.labelLarge)
            RChip("مرتجع", selected = !vm.exchange) { vm.exchange = false; vm.error = null }
            RChip("استبدال", selected = vm.exchange) { vm.exchange = true; vm.error = null }
        }
        val cur = vm.saleCurrency
        RRow("قيمة القطع المرتجعة (${vm.returnedCount})", Money.plain(vm.returnedShown, cur), valueColor = Lux.GoldDeep, strong = true)
        if (!cur.isBase) Text("يعادل ${Money.formatBase(vm.returnedSar.toPlainString())}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    }
}

/** Refund destination and currency chips (also used for an exchange's leftover). */
@Composable
private fun RefundChoices(vm: PosReturnViewModel, title: String) {
    if (vm.isCreditSale) {
        Text("تُخصم من مستحقات العميل (فاتورة آجلة)", style = MaterialTheme.typography.bodySmall, color = Lux.GoldDeep)
        return
    }
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RChip("نقداً من الصندوق", selected = vm.refundTo == RefundTo.CASH || vm.isWalkIn) { vm.refundTo = RefundTo.CASH; vm.error = null }
        RChip("رصيد للعميل", selected = vm.refundTo == RefundTo.ACCOUNT && !vm.isWalkIn, enabled = !vm.isWalkIn) {
            vm.refundTo = RefundTo.ACCOUNT
            vm.error = null
        }
    }
    if (vm.isWalkIn) Text("العميل نقدي (بدون حساب)؛ لا يمكن حفظ رصيد له", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    if (vm.refundTo == RefundTo.CASH || vm.isWalkIn) {
        val options = listOf(Currency.SAR, vm.saleCurrency).distinct()
        if (options.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("عملة الإرجاع:", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.align(Alignment.CenterVertically))
                options.forEach { c -> RChip("${c.nameAr} (${c.symbol})", selected = vm.refundCurrency == c) { vm.refundCurrency = c; vm.error = null } }
            }
        }
    }
}

@Composable
private fun RefundSection(vm: PosReturnViewModel) {
    RSection {
        RefundChoices(vm, "إرجاع المبلغ")
        if (vm.returnedCount > 0 && !vm.isCreditSale) {
            val toAccount = vm.refundTo == RefundTo.ACCOUNT && !vm.isWalkIn
            val c = if (toAccount) vm.saleCurrency else vm.refundCurrency
            val shown = if (c == vm.saleCurrency) Money.plain(vm.returnedShown, c) else Money.format(vm.returnedSar, c, vm.rates[c] ?: BigDecimal.ONE)
            RRow(if (toAccount) "يُضاف لرصيد العميل" else "يُرجَع للعميل نقداً", "≈ $shown", valueColor = Success, strong = true)
            Text("المبلغ النهائي يحدده الخادم (يشمل أي خصم على الفاتورة).", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
        }
    }
}

@Composable
private fun ExchangeSection(vm: PosReturnViewModel, onScan: () -> Unit, onPick: (PosProduct) -> Unit) {
    val cur = vm.saleCurrency
    val rate = vm.currentRate
    RSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("القطع البديلة", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            RLink("باركود", onClick = onScan)
        }
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::search,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (vm.query.isNotEmpty()) ({
                IconButton(onClick = { vm.search("") }) { Icon(Icons.Filled.Close, contentDescription = "مسح") }
            }) else null,
            placeholder = { Text("ابحث بالاسم أو رقم الصنف") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.query.isNotBlank() && !vm.searching && vm.results.isEmpty()) {
            Text("لا توجد منتجات مطابقة", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        if (vm.results.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                vm.results.forEach { p ->
                    key(p.id) {
                        val out = p.available <= 0
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !out) { vm.tap(p, onPick) }
                                .alpha(if (out) 0.5f else 1f).padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Thumb(p.nameAr, p.imageUrl ?: p.variants.firstNotNullOfOrNull { it.imageUrl })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.nameAr, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(p.skuNumber?.let { "#$it" }, if (out) "نفد" else "متاح ${p.available}").joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (out) MaterialTheme.colorScheme.error else Lux.InkSoft,
                                )
                            }
                            Text(Money.format(Money.parse(p.price), cur, rate), style = MaterialTheme.typography.labelLarge, color = Lux.GoldDeep)
                        }
                    }
                }
            }
        }
        if (vm.lines.isEmpty()) {
            Text("اختر القطع التي سيأخذها العميل بدلاً من المرتجعة.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        } else {
            HorizontalDivider(color = Lux.Hairline)
            vm.lines.forEach { l -> key(l.variant.id) { ExchangeLineRow(l, vm, cur, rate) } }
        }
    }

    if (vm.lines.isNotEmpty() && vm.returnedCount > 0) {
        val t = vm.totals
        RSection {
            RRow("قيمة المرتجع", Money.plain(vm.returnedShown, cur))
            RRow("قيمة القطع البديلة", Money.plain(t.newTotalShown, cur))
            if (t.toPay.signum() > 0) {
                RRow("على العميل دفع الفرق", Money.format(t.toPay, cur, rate), valueColor = MaterialTheme.colorScheme.error, strong = true)
                HorizontalDivider(color = Lux.Hairline)
                TenderFields(vm)
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
                RRow("يُرجَع للعميل", Money.plain(t.leftover, cur), valueColor = Success, strong = true)
                HorizontalDivider(color = Lux.Hairline)
                RefundChoices(vm, "الفرق المتبقي للعميل")
            } else {
                RRow("لا فرق", "—", valueColor = Success, strong = true)
            }
            Text("تقدير: الأسعار النهائية (العروض / سعر التاجر) يحددها الخادم.", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
        }
    }
}

@Composable
private fun ExchangeLineRow(l: CartLine, vm: PosReturnViewModel, cur: Currency, rate: BigDecimal) {
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

@Composable
private fun TenderFields(vm: PosReturnViewModel) {
    Text("المدفوع من العميل", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.tenders.forEachIndexed { i, tender ->
            val c = tender.currency
            Column(Modifier.weight(1f)) {
                RField(
                    value = tender.amount,
                    onValueChange = { vm.setTender(i, it) },
                    placeholder = if (c.decimals == 0) "${c.nameAr} بدون كسور" else c.nameAr,
                    isError = tender.amount.isNotBlank() && validAmount(tender.amount, c) == null,
                    trailing = { Text(c.symbol, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) },
                    modifier = Modifier.fillMaxWidth(),
                )
                RLink("المتبقي") { vm.fillRemaining(i) }
            }
        }
    }
}

@Composable
private fun SubmitBar(vm: PosReturnViewModel) {
    val t = vm.totals
    val tendersValid = vm.tenders.all { it.amount.isBlank() || validAmount(it.amount, it.currency) != null }
    val hint = when {
        vm.returnedCount == 0 -> "اختر القطع المرتجعة وكمياتها"
        vm.exchange && vm.lines.isEmpty() -> "اختر القطع البديلة"
        vm.exchange && !tendersValid -> "تحقق من المبالغ المدفوعة"
        vm.exchange && t.owed.signum() > 0 -> "المتبقي على العميل ${Money.format(t.owed, vm.saleCurrency, vm.currentRate)}"
        else -> null
    }
    val canConfirm = !vm.busy && hint == null
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentPadding = 10.dp) {
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)) }
        hint?.let { Text(it, color = Lux.InkSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)) }
        LuxButton(
            when {
                vm.busy -> "جارٍ الحفظ…"
                vm.exchange -> "تأكيد الاستبدال"
                else -> "تأكيد المرتجع · ${Money.plain(vm.returnedShown, vm.saleCurrency)}"
            },
            onClick = vm::confirm,
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------- variants ---

private fun hexColor(hex: String?): Color? = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

@Composable
private fun ExchangeVariantPicker(p: PosProduct, vm: PosReturnViewModel, onDismiss: () -> Unit, onPick: (PosVariant) -> Unit) {
    val cur = vm.saleCurrency
    val rate = vm.currentRate
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Lux.Pearl) {
        Text(p.nameAr, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Text("اختر اللون والمقاس", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(p.variants, key = { it.id }) { v ->
                val left = v.available - vm.inLines(v.id)
                GlassCard(Modifier.fillMaxWidth().alpha(if (left > 0) 1f else 0.5f), elevation = 4.dp, contentPadding = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = left > 0) { onPick(v) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val swatch = hexColor(v.colorHex)
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
                            Text(Money.format(Money.parse(v.price), cur, rate), style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
                            if (!cur.isBase) Text(Money.formatBase(v.price), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ result ---

@Composable
private fun ReturnResultDialog(r: PosReturnResult, vm: PosReturnViewModel, onNew: () -> Unit) {
    val saleCur = vm.saleCurrency
    val refundCur = vm.resultCurrency
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (r.sale != null) "تم الاستبدال ✓" else "تم المرتجع ✓") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.salesReturn?.let { ret ->
                    SummaryRow("رقم المرتجع", ret.number, strong = true)
                    SummaryRow("الفاتورة الأصلية", ret.order.number)
                    SummaryRow("قيمة المرتجع", Money.formatBase(ret.refundAmount))
                }
                val refundedSar = Money.parse(r.refunded.sar)
                if (r.sale == null) {
                    if (refundedSar.signum() == 0) {
                        SummaryRow("المُرجَع", "خُصم من مستحقات العميل", valueColor = Lux.GoldDeep)
                    } else {
                        val label = if (refundCur == saleCur && (vm.refundTo == RefundTo.ACCOUNT && !vm.isWalkIn || vm.isCreditSale)) "أُضيف لرصيد العميل" else "المُرجَع نقداً"
                        SummaryRow(label, Money.plain(Money.parse(r.refunded.currencyAmount), refundCur), strong = true, valueColor = Success)
                        if (!refundCur.isBase) Text("يعادل ${Money.formatBase(r.refunded.sar)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    }
                }
                r.sale?.let { s ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SummaryRow("قيمة المرتجع المحتسبة", Money.plain(Money.parse(r.refunded.currencyAmount), saleCur))
                    SummaryRow("فاتورة الاستبدال", s.number, strong = true)
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
                    val leftover = Money.parse(r.leftover)
                    if (leftover.signum() > 0) {
                        val kept = vm.isCreditSale || (vm.refundTo == RefundTo.ACCOUNT && !vm.isWalkIn)
                        SummaryRow(
                            if (kept) "بقي رصيداً للعميل" else "الفرق المُرجَع للعميل نقداً",
                            Money.plain(leftover, saleCur),
                            strong = true,
                            valueColor = Success,
                        )
                        if (!kept && vm.refundCurrency != saleCur) {
                            Text("يُصرف بعملة ${vm.refundCurrency.nameAr}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onNew,
                shape = RoundedCornerShape(16.dp),
            ) { Text("عملية جديدة") }
        },
    )
}
