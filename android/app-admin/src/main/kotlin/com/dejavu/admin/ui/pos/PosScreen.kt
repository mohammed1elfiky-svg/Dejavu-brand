@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.pos

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.asImageBitmap
import com.dejavu.core.admin.PosSettings
import com.dejavu.core.model.Currencies
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.media.rememberImagePicker
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.util.UUID
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.print.Printers
import com.dejavu.core.ui.theme.GlassTopBar
import com.dejavu.core.ui.theme.GoldBadge
import com.dejavu.core.ui.theme.LiquidGoldButton
import com.dejavu.core.ui.theme.liquidClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import android.widget.Toast
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.ConfirmDialog
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
import com.dejavu.core.admin.PosQuote
import com.dejavu.core.admin.OffersRepository
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
import com.dejavu.core.model.CurrencyRate
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
    /** SAR shelf total, prices already include any running promotion. */
    val lineTotal: BigDecimal get() = Money.parse(variant.price).multiply(BigDecimal(qty))

    /** SAR unit price for [buyer] (merchants get wholesale / merchant discount). */
    fun unitPrice(buyer: PosCustomer?): BigDecimal = buyerPrice(product, variant, buyer)

    /** SAR line total for [buyer]. */
    fun lineTotal(buyer: PosCustomer?): BigDecimal = unitPrice(buyer).multiply(BigDecimal(qty))
}

data class PosCustomer(
    val id: String,
    val name: String,
    val phone: String?,
    val code: String?,
    val isMerchant: Boolean = false,
    /** Extra percent off for merchants. */
    val merchantDiscount: String = "0",
    /** Most the merchant may owe, SAR; null = no limit. */
    val creditLimit: String? = null,
)

internal fun AdminCustomerSummary.toPos() =
    PosCustomer(id, name, phone, code, isMerchant = isMerchant, merchantDiscount = merchantDiscount, creditLimit = creditLimit)

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
    /** Quantity offers ("قطعة + قطعة مجاناً") taken off [subtotal] before [discount]. */
    val offers: BigDecimal = BigDecimal.ZERO,
)

private val HUNDRED = BigDecimal(100)

private fun money(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

/**
 * Mirror of the server's `buyerPrice` (catalog/promotions.ts): merchants pay the product's
 * wholesale price (or the promoted shelf price when none) less their merchant discount.
 */
internal fun buyerPrice(product: PosProduct, variant: PosVariant, buyer: PosCustomer?): BigDecimal {
    val shelf = Money.parse(variant.price)
    if (buyer?.isMerchant != true) return shelf
    val base = product.wholesalePrice?.toBigDecimalOrNull() ?: shelf
    val off = (buyer.merchantDiscount.toBigDecimalOrNull() ?: BigDecimal.ZERO).max(BigDecimal.ZERO).min(HUNDRED)
    return base.multiply(HUNDRED.subtract(off)).divide(HUNDRED, 2, RoundingMode.HALF_UP)
}

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
    buyer: PosCustomer? = null,
    /** SAR the server's quantity offers take off (POST admin/pos/quote). */
    offers: BigDecimal = BigDecimal.ZERO,
): PosTotals {
    val rate = rates[invoice] ?: BigDecimal.ONE
    val subtotal = money(cart.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal(buyer)) })
    // Like the server's priceOrder: offers first, then the invoice discount on what is left.
    val offerTotal = money(offers.max(BigDecimal.ZERO).min(subtotal))
    val afterOffers = money(subtotal.subtract(offerTotal))
    val value = discountValue.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val discount = when {
        value == null -> BigDecimal.ZERO
        discountType == DiscountType.PERCENT -> afterOffers.multiply(value.min(HUNDRED)).divide(HUNDRED, 2, RoundingMode.HALF_UP)
        else -> Money.toBase(value, invoice, rate)
    }.min(afterOffers)
    val total = money(afterOffers.subtract(discount))

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
    return PosTotals(subtotal, discount, total, paid, owed, change, changeAmount, offerTotal)
}

// -------------------------------------------------------------- viewmodel ---

/** How the money of a sale is received. */
enum class PayMethod(val label: String, val wire: String) {
    CASH("نقداً (كاش)", "CASH"),
    TRANSFER("تحويل بنكي", "BANK_TRANSFER"),
    DEPOSIT("سند إيداع", "BANK_TRANSFER"),
}

/** Buttons / sections of the checkout the employee can reorder or hide (ids are stored on the server). */
enum class CheckoutButton(val id: String, val label: String, val hideable: Boolean = true) {
    CURRENCY("currency", "عملة الفاتورة وأسعار الصرف"),
    CASH("cash", "نقداً"),
    TRANSFER("transfer", "تحويل بنكي"),
    DEPOSIT("deposit", "سند إيداع"),
    CREDIT("credit", "آجل"),
    CUSTOMER("customer", "العميل"),
    DISCOUNT("discount", "الخصم"),
    AMOUNT("amount", "المبلغ المستلم والإجمالي", hideable = false),
    MULTI("multi", "الدفع بأكثر من عملة"),
    NOTE("note", "ملاحظة"),
    PRINT("print", "طباعة الفاتورة");

    val payMethod: PayMethod?
        get() = when (this) {
            CASH -> PayMethod.CASH
            TRANSFER -> PayMethod.TRANSFER
            DEPOSIT -> PayMethod.DEPOSIT
            else -> null
        }

    companion object {
        fun of(id: String): CheckoutButton? = entries.firstOrNull { it.id == id }

        /** The payment buttons, shown together in one row. */
        val PAYMENT = setOf(CASH, TRANSFER, DEPOSIT, CREDIT)
    }
}

/** The employee's checkout: buttons in [order], minus [hidden]. */
data class CheckoutLayout(val order: List<CheckoutButton>, val hidden: Set<CheckoutButton>) {
    fun isShown(b: CheckoutButton): Boolean = !b.hideable || b !in hidden

    val shown: List<CheckoutButton> get() = order.filter { isShown(it) }

    fun toSettings(saleCurrency: String?) = PosSettings(order.map { it.id }, hidden.filter { it.hideable }.map { it.id }, saleCurrency)

    companion object {
        val DEFAULT = CheckoutLayout(CheckoutButton.entries, emptySet())

        fun from(s: PosSettings?): CheckoutLayout {
            if (s == null) return DEFAULT
            val saved = s.checkoutButtons.mapNotNull { CheckoutButton.of(it) }.distinct()
            val order = saved + CheckoutButton.entries.filter { it !in saved }
            val hidden = s.hiddenButtons.mapNotNull { CheckoutButton.of(it) }.filter { it.hideable }.toSet()
            return CheckoutLayout(order, hidden)
        }
    }
}

/** A plain number for a text field: "1500", "12.5". */
private fun plainNumber(v: BigDecimal): String = v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

class PosViewModel(
    private val pos: PosRepository,
    private val finance: FinanceRepository,
    private val treasury: TreasuryRepository,
    private val offline: OfflineSales,
    private val offerRepo: OffersRepository,
) : BaseViewModel() {
    var products by mutableStateOf<Load<List<PosProduct>>>(Load.Loading)
        private set
    var query by mutableStateOf("")
        private set
    var currencies by mutableStateOf(listOf(Currency.SAR))
        private set
    var rates by mutableStateOf(mapOf(Currency.SAR to BigDecimal.ONE))
        private set
    /** Active non-base currencies (with or without a rate), for the quick rate editor. */
    var rateRows by mutableStateOf<List<CurrencyRate>>(emptyList())
        private set
    /** Uploaded note / coin picture per currency code. */
    var currencyImages by mutableStateOf<Map<String, String?>>(emptyMap())
        private set
    var rateBusy by mutableStateOf(false)
        private set

    var cart by mutableStateOf<List<CartLine>>(emptyList())
        private set
    var currency by mutableStateOf(Currency.SAR)
        private set
    var saleType by mutableStateOf(SaleType.CASH)
        private set
    var customer by mutableStateOf<PosCustomer?>(null)
        private set
    var customerBalances by mutableStateOf<List<AccountBalance>>(emptyList())
        private set
    var customerQuery by mutableStateOf("")
        private set
    var customerResults by mutableStateOf<List<AdminCustomerSummary>>(emptyList())
        private set
    var discountType by mutableStateOf(DiscountType.PERCENT)
        private set
    var discountValue by mutableStateOf("")
        private set
    /** Amount received in the invoice currency (one-field checkout); null = the amount due. */
    var receivedText by mutableStateOf<String?>(null)
        private set
    /** Several tender fields (one per currency) instead of the one received amount. */
    var multiCurrency by mutableStateOf(false)
        private set
    var tenders by mutableStateOf(listOf(TenderInput(Currency.SAR, "")))
        private set
    var changeCurrency by mutableStateOf<Currency?>(null)
    var payMethod by mutableStateOf(PayMethod.CASH)
        private set
    var reference by mutableStateOf("")
    var note by mutableStateOf("")
    /** Uploaded transfer / deposit slip photo. */
    var slipFileId by mutableStateOf<String?>(null)
        private set
    var slipPreview by mutableStateOf<ByteArray?>(null)
        private set
    var slipUploading by mutableStateOf(false)
        private set
    /** Print this sale's receipt (null = the printer's automatic setting). */
    var printAfterSale by mutableStateOf<Boolean?>(null)
    /** Bank / exchange-house boxes a transfer can land in. */
    var banks by mutableStateOf<List<CashBox>>(emptyList())
        private set
    /** Chosen bank box per currency (null = the first one of that currency). */
    var bankFor by mutableStateOf<Map<Currency, String>>(emptyMap())

    /** The employee's own checkout layout and default sale currency. */
    var layout by mutableStateOf(CheckoutLayout.DEFAULT)
        private set
    var saleCurrencyCode by mutableStateOf<String?>(null)
        private set
    var settingsBusy by mutableStateOf(false)
        private set

    /** Sale being corrected (its items are in the cart); checkout then replaces it. */
    var editing by mutableStateOf<PosReceipt?>(null)
        private set

    var busy by mutableStateOf(false)
        private set
    var checkoutError by mutableStateOf<String?>(null)
    var receipt by mutableStateOf<PosReceipt?>(null)
        private set
    /** [receipt] is a corrected sale. */
    var receiptEdited by mutableStateOf(false)
        private set
    var shift by mutableStateOf<Load<PosShift>?>(null)
        private set

    /** Sales rung up offline, waiting to be sent. */
    val pending: StateFlow<List<QueuedSale>> = offline.queue
    var syncing by mutableStateOf(false)
        private set

    private var searchJob: Job? = null
    private var customerJob: Job? = null
    private var quoteJob: Job? = null

    /** The server's quantity offers for a basket ([key]: items + customer id). */
    private data class QuotedBasket(val key: Pair<List<PosItem>, String?>, val quote: PosQuote)

    /** Last offers quote; null while none could be had (offline, failed). */
    private var quoted by mutableStateOf<QuotedBasket?>(null)

    private fun basketKey(): Pair<List<PosItem>, String?> = cart.map { PosItem(it.variant.id, it.qty) } to customer?.id

    /** The offers quote of the basket as it is now; null while it is being asked for or could not be had. */
    val appliedQuote: PosQuote?
        get() = quoted?.takeIf { it.key == basketKey() }?.quote

    /** Offers shown at the till (SAR off). */
    val appliedDeals: List<PosQuote.Applied>
        get() = appliedQuote?.deals.orEmpty()

    /** SAR the quantity offers take off the basket (0 without a fresh quote). */
    private val offerDiscount: BigDecimal
        get() = appliedQuote?.let { Money.parse(it.dealDiscount) } ?: BigDecimal.ZERO

    /** Asks the server (debounced) which quantity offers the basket gets; failures just show none. */
    private fun requote() {
        quoteJob?.cancel()
        val key = basketKey()
        if (key.first.isEmpty()) {
            quoted = null
            return
        }
        if (quoted?.key == key) return
        quoteJob = viewModelScope.launch {
            delay(400)
            val q = offerRepo.posQuote(key.first, key.second).getOrNull()
            quoted = q?.let { QuotedBasket(key, it) }
        }
    }
    /** The signed-in employee's till currency (code), applied until the cashier picks one. */
    private var preferredCurrency: String? = null
    private var currencyPicked = false
    private var settingsFromServer = false
    private var profileCurrency: String? = null
    private var showingCached = false
    /** Id of this checkout: sending the same sale again (retry, offline queue) books it once. */
    private var clientRef = UUID.randomUUID().toString()

    /** The coupon field shows only while the store has a coupon that can be used. */
    var couponsAvailable by mutableStateOf(false)
        private set
    var couponText by mutableStateOf("")
    /** The coupon applied to this sale (instead of a typed discount). */
    var coupon by mutableStateOf<com.dejavu.core.admin.PosCoupon?>(null)
        private set

    fun refreshCoupons() {
        viewModelScope.launch { pos.couponsAvailable().getOrNull()?.let { couponsAvailable = it.available } }
    }

    fun applyCoupon() {
        val code = couponText.trim()
        if (code.isEmpty() || busy) return
        viewModelScope.launch {
            pos.checkCoupon(code, customer?.id).orSay()?.let {
                coupon = it
                discountValue = ""
                receivedText = null
                checkoutError = null
                say("تم تطبيق الكوبون ${it.code}")
            }
        }
    }

    fun removeCoupon() {
        coupon = null
        couponText = ""
    }

    init {
        load()
        loadCurrencies()
        refreshCoupons()
        viewModelScope.launch {
            banks = treasury.safes().getOrNull().orEmpty().filter { it.kind == CashBoxKind.BANK && it.isActive }
        }
        viewModelScope.launch {
            pos.settings().onSuccess {
                settingsFromServer = true
                applySettings(it, it.saleCurrency ?: profileCurrency)
            }
        }
        syncOffline()
    }

    fun pickBank(currency: Currency, boxId: String) {
        bankFor = bankFor + (currency to boxId)
    }

    // ---- settings

    /** Layout from the signed-in profile, used until (or unless) the server's settings arrive. */
    fun useProfile(settings: PosSettings?, saleCurrency: String?) {
        profileCurrency = saleCurrency
        if (settingsFromServer) return
        applySettings(settings, settings?.saleCurrency ?: saleCurrency)
    }

    private fun applySettings(s: PosSettings?, saleCurrency: String?) {
        layout = CheckoutLayout.from(s)
        saleCurrencyCode = saleCurrency
        if (!layout.isShown(CheckoutButton.MULTI) && multiCurrency) setMulti(false)
        if (!layout.isShown(CheckoutButton.CREDIT) && saleType == SaleType.CREDIT && editing == null) saleType = SaleType.CASH
        if (cart.isEmpty() || !layout.isShown(payMethodButton(payMethod))) payMethod = defaultPayMethod()
        preferCurrency(saleCurrency)
    }

    private fun payMethodButton(m: PayMethod): CheckoutButton = CheckoutButton.entries.first { it.payMethod == m }

    private fun defaultPayMethod(): PayMethod = layout.shown.firstNotNullOfOrNull { it.payMethod } ?: PayMethod.CASH

    fun saveSettings(newLayout: CheckoutLayout, saleCurrency: String?, done: () -> Unit) {
        if (settingsBusy) return
        settingsBusy = true
        viewModelScope.launch {
            pos.saveSettings(newLayout.toSettings(saleCurrency)).fold(
                {
                    settingsFromServer = true
                    if (editing == null) currencyPicked = false
                    applySettings(it, it.saleCurrency ?: saleCurrency)
                    say("تم حفظ تخصيص صفحة الدفع")
                    done()
                },
                { e -> say(e.userMessage()) },
            )
            settingsBusy = false
        }
    }

    // ---- totals

    /**
     * The currency change is given in: the cashier's pick, else (like the server) the currency of
     * the cash tenders when they all share one, else the invoice currency.
     */
    val changeIn: Currency
        get() = changeCurrency ?: defaultChangeCurrency(paidTenders)

    private fun defaultChangeCurrency(list: List<TenderInput>): Currency {
        if (payMethod != PayMethod.CASH) return currency
        val used = list.filter { validAmount(it.amount, it.currency) != null }.map { it.currency }.distinct()
        return used.singleOrNull() ?: currency
    }

    private val invoiceRate: BigDecimal get() = rates[currency] ?: BigDecimal.ONE

    /** Totals with only the typed (%/amount) discount and nothing paid. */
    private val beforePayment: PosTotals
        get() {
            val c = coupon?.let(::couponDiscount)
            return if (c != null) {
                computeTotals(cart, currency, rates, c.first, c.second.toPlainString(), emptyList(), currency, customer, offerDiscount)
            } else {
                computeTotals(cart, currency, rates, discountType, discountValue, emptyList(), currency, customer, offerDiscount)
            }
        }

    /** The invoice before any discount (offers only): what a coupon is worked out on. */
    private val beforeDiscount: PosTotals
        get() = computeTotals(cart, currency, rates, discountType, "", emptyList(), currency, customer, offerDiscount)

    /** What the customer has to pay, in the invoice currency. */
    val dueInInvoice: BigDecimal
        get() = Money.convert(beforePayment.total, currency, invoiceRate)

    /** The received-amount field as shown: the cashier's text, else the amount due (blank for a credit sale). */
    val receivedShown: String
        get() = receivedText ?: if (saleType == SaleType.CREDIT || cart.isEmpty()) "" else plainNumber(dueInInvoice)

    /**
     * A received amount below the amount due becomes a discount (invoice currency); null when
     * there is none (paid in full, several currencies, or a credit sale where the rest is owed).
     */
    val receivedDiscount: BigDecimal?
        get() {
            if (multiCurrency || saleType == SaleType.CREDIT || cart.isEmpty()) return null
            val r = validAmount(receivedShown, currency) ?: return null
            val due = dueInInvoice
            return if (r < due) due.subtract(r) else null
        }

    /** Discount sent with the sale: the typed one, or (with a short received amount) the whole difference as an amount. */
    private val discountToSend: Pair<DiscountType, BigDecimal>?
        get() {
            if (receivedDiscount != null) {
                val received = validAmount(receivedShown, currency) ?: return null
                // The offers are the server's; the cashier's discount comes on top of them.
                val b = beforePayment
                val subtotal = Money.convert(b.subtotal.subtract(b.offers), currency, invoiceRate)
                val amount = subtotal.subtract(received)
                return if (amount.signum() > 0) DiscountType.AMOUNT to amount else null
            }
            coupon?.let { c -> return couponDiscount(c) }
            val typed = discountValue.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
            return discountType to typed
        }

    /** The coupon as the till shows it; the server works out the same discount on the sale. */
    private fun couponDiscount(c: com.dejavu.core.admin.PosCoupon): Pair<DiscountType, BigDecimal>? {
        val value = c.value.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val cap = c.maxDiscount?.toBigDecimalOrNull()
        if (c.type == "PERCENTAGE" && cap == null) return DiscountType.PERCENT to value
        val b = beforeDiscount
        val base = b.subtotal.subtract(b.offers)
        var sar = if (c.type == "PERCENTAGE") base.multiply(value).divide(BigDecimal(100), 2, RoundingMode.HALF_UP) else value
        if (cap != null && sar > cap) sar = cap
        if (sar > base) sar = base
        return DiscountType.AMOUNT to Money.convert(sar, currency, invoiceRate)
    }

    /** What was handed over: the one received amount, or the per-currency fields. */
    private val paidTenders: List<TenderInput>
        get() = if (multiCurrency) tenders else listOf(TenderInput(currency, receivedShown))

    val totals: PosTotals
        get() {
            val d = discountToSend
            return computeTotals(
                cart,
                currency,
                rates,
                d?.first ?: discountType,
                d?.second?.toPlainString() ?: "",
                paidTenders,
                changeIn,
                customer,
                offerDiscount,
            )
        }

    fun editReceived(text: String) {
        receivedText = decimalInput(text).take(20)
        checkoutError = null
    }

    fun editDiscount(type: DiscountType = discountType, value: String = discountValue) {
        discountType = type
        discountValue = decimalInput(value)
        receivedText = null
        checkoutError = null
    }

    /** Shows / hides the per-currency tender fields. */
    fun setMulti(on: Boolean) {
        multiCurrency = on
        receivedText = null
        changeCurrency = null
        if (on) {
            val cleared = tenders.map { it.copy(amount = "") }
            tenders = if (cleared.none { it.currency == currency }) listOf(TenderInput(currency, "")) + cleared else cleared
        }
        checkoutError = null
    }

    fun pickPayMethod(m: PayMethod) {
        payMethod = m
        checkoutError = null
    }

    fun toggleCredit() {
        saleType = if (saleType == SaleType.CREDIT) SaleType.CASH else SaleType.CREDIT
        receivedText = null
        checkoutError = null
    }

    /** Checkout opened: the received amount starts at the amount due again. */
    fun openCheckout() {
        receivedText = null
        checkoutError = null
    }

    /** The cashier picks the invoice currency (overrides the employee's default). */
    fun pickCurrency(c: Currency) {
        currency = c
        currencyPicked = true
        receivedText = null
        checkoutError = null
    }

    /** Sets the employee's default till currency; applied while the cashier hasn't picked one. */
    fun preferCurrency(code: String?) {
        preferredCurrency = code
        applyPreferredCurrency()
    }

    private fun applyPreferredCurrency() {
        if (currencyPicked) return
        val code = preferredCurrency ?: return
        currencies.firstOrNull { it.code == code }?.let {
            currency = it
            receivedText = null
        }
    }

    fun rateOf(c: Currency): BigDecimal? = rates[c]

    fun imageOf(c: Currency): String? = currencyImages[c.code]

    // ---- slip photo

    fun uploadSlip(bytes: ByteArray) {
        slipPreview = bytes
        slipFileId = null
        slipUploading = true
        viewModelScope.launch {
            pos.uploadSlip(bytes).fold(
                { slipFileId = it.id },
                { e ->
                    slipPreview = null
                    say(e.userMessage())
                },
            )
            slipUploading = false
        }
    }

    fun removeSlip() {
        slipFileId = null
        slipPreview = null
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
        val q = query
        pos.products(q).fold(
            { list ->
                products = Load.Ready(list)
                showingCached = false
                if (q.isBlank()) offline.saveProducts(list)
            },
            { e ->
                val cached = if (OfflineSales.isTransient(e)) offline.products() else null
                when {
                    cached != null -> {
                        products = Load.Ready(filterLocal(cached, q))
                        if (!showingCached) say("لا يوجد اتصال: تُعرض آخر قائمة منتجات محفوظة")
                        showingCached = true
                    }
                    products is Load.Ready -> say(e.userMessage())
                    else -> products = Load.Failed(e.userMessage())
                }
            },
        )
    }

    private fun filterLocal(list: List<PosProduct>, q: String): List<PosProduct> {
        val t = q.trim()
        if (t.isEmpty()) return list
        return list.filter { p ->
            p.nameAr.contains(t, ignoreCase = true) || p.nameEn.contains(t, ignoreCase = true) ||
                p.skuNumber?.toString() == t || p.variants.any { it.sku.equals(t, ignoreCase = true) || it.barcode == t }
        }
    }

    fun loadCurrencies() {
        viewModelScope.launch {
            finance.activeCurrencies().fold(
                { cs ->
                    applyCurrencies(cs)
                    offline.saveCurrencies(cs)
                },
                { e ->
                    val cached = if (OfflineSales.isTransient(e)) offline.currencies() else null
                    if (cached != null) {
                        cached.register()
                        applyCurrencies(cached)
                    } else {
                        say(e.userMessage())
                    }
                },
            )
        }
    }

    private fun applyCurrencies(cs: Currencies) {
        val ordered = cs.register()
        rateRows = cs.currencies.filter { !Currency.of(it.code.code).isBase }
        currencyImages = cs.currencies.associate { it.code.code to it.imageUrl }
        val found = mutableMapOf(Currency.SAR to BigDecimal.ONE)
        cs.currencies.forEach { cr ->
            val c = Currency.of(cr.code.code)
            val r = if (c.isBase) BigDecimal.ONE else cr.rate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            if (r != null) found[c] = r
        }
        rates = found
        currencies = (listOf(Currency.SAR) + ordered.map { Currency.of(it.code) }).distinct().filter { it in found }
        applyPreferredCurrency()
        // Two tender fields side by side by default: SAR and YER (when it has a rate).
        val defaults = listOfNotNull(Currency.SAR, Currency.YER.takeIf { it in found })
        if (!multiCurrency && tenders.all { it.amount.isBlank() }) tenders = defaults.map { TenderInput(it, "") }
    }

    /** Saves a new rate (units per 1 SAR) and reloads the till's currencies; [done] gets an error or null. */
    fun saveRate(c: Currency, rate: String, done: (String?) -> Unit) {
        if (rateBusy) return
        rateBusy = true
        viewModelScope.launch {
            finance.setRate(c, rate, note = "من شاشة الكاشير").fold(
                {
                    say("تم تحديث سعر ${c.nameAr}")
                    loadCurrencies()
                    done(null)
                },
                { e -> done(e.userMessage()) },
            )
            rateBusy = false
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
        val line = cart.firstOrNull { it.variant.id == variant.id }
        val have = line?.qty ?: 0
        // A line of a sale being corrected also counts the pieces that sale took.
        val available = line?.variant?.available ?: variant.available
        if (have + 1 > available) {
            say(if (available <= 0) "${product.nameAr} (${variant.title}) غير متوفر" else "المتاح من ${product.nameAr} (${variant.title}) $available فقط")
            return
        }
        cart = if (line == null) cart + CartLine(product, variant, 1) else cart.map { if (it.variant.id == variant.id) it.copy(qty = it.qty + 1) else it }
        receivedText = null
        requote()
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
        receivedText = null
        requote()
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
        val owed = computeTotals(cart, currency, rates, discountType, discountValue, others, changeCurrency ?: defaultChangeCurrency(others), customer, offerDiscount).owed
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
        receivedText = null
        checkoutError = null
        requote()
        viewModelScope.launch {
            customerBalances = finance.customerAccount(c.id).getOrNull()?.balances.orEmpty()
        }
    }

    fun clearCustomer() {
        customer = null
        customerBalances = emptyList()
        receivedText = null
        requote()
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
        if (busy || cart.isEmpty() || slipUploading) return
        val t = totals
        val discount = discountToSend
        val bank = payMethod != PayMethod.CASH
        val request = PosSaleRequest(
            items = cart.map { PosItem(it.variant.id, it.qty) },
            saleType = saleType,
            currency = currency,
            customerId = customer?.id,
            couponCode = coupon?.code,
            discountType = discount?.first?.takeIf { coupon == null },
            discountValue = discount?.second?.stripTrailingZeros()?.toPlainString()?.takeIf { coupon == null },
            payments = paidTenders.mapNotNull { x ->
                validAmount(x.amount, x.currency)?.let {
                    PosTender(
                        x.currency,
                        it.stripTrailingZeros().toPlainString(),
                        method = payMethod.wire,
                        bankBoxId = if (bank) bankFor[x.currency] else null,
                    )
                }
            },
            changeCurrency = changeIn.takeIf { t.change.signum() > 0 },
            note = note.trim().ifBlank { null },
            paymentMethod = payMethod.wire,
            reference = reference.trim().ifBlank { null }?.takeIf { bank },
            slipFileId = slipFileId?.takeIf { bank },
            clientRef = clientRef,
            // Offers only when the till showed them (fresh quote); otherwise the customer paid the
            // full price shown, so the server must not take them off (offline / failed quote).
            applyDeals = if (appliedQuote != null) null else false,
        )
        val editId = editing?.id
        busy = true
        checkoutError = null
        viewModelScope.launch {
            val result = if (editId != null) pos.editSale(editId, request) else pos.sell(request)
            result.fold(
                {
                    receiptEdited = editId != null
                    receipt = it
                    fetch()
                    syncOffline()
                },
                { e ->
                    if (editId == null && OfflineSales.isOffline(e)) {
                        saveOffline(request)
                    } else {
                        checkoutError = e.userMessage()
                    }
                },
            )
            busy = false
        }
    }

    /** No connection: the sale waits in the device's queue and the cart is cleared. */
    private suspend fun saveOffline(request: PosSaleRequest) {
        offline.add(request.copy(soldAt = Instant.now().toString()))
        // Take the pieces off the shown (and cached) stock so the next sale sees them gone.
        val sold = request.items.associate { it.variantId to it.quantity }
        fun List<PosProduct>.minusSold() = map { p ->
            if (p.variants.none { it.id in sold }) p else p.copy(variants = p.variants.map { v -> sold[v.id]?.let { v.copy(available = v.available - it) } ?: v })
        }
        (products as? Load.Ready)?.value?.let { products = Load.Ready(it.minusSold()) }
        offline.products()?.let { offline.saveProducts(it.minusSold()) }
        say("حُفظت الفاتورة بدون إنترنت وسترسل تلقائياً")
        newSale()
    }

    /** Sends the sales saved offline; [manual] also retries the ones the server refused before. */
    fun syncOffline(manual: Boolean = false) {
        if (syncing || offline.queue.value.isEmpty()) return
        syncing = true
        viewModelScope.launch {
            val r = offline.sync(pos, includeFailed = manual)
            if (r.sent > 0) {
                say("أُرسلت ${r.sent} فاتورة محفوظة")
                fetch()
            }
            if (manual) {
                when {
                    r.offline -> say("لا يوجد اتصال بالخادم بعد؛ ستُرسل الفواتير تلقائياً عند عودته")
                    r.firstError != null -> say("تعذّر إرسال فاتورة: ${r.firstError}")
                }
            }
            syncing = false
        }
    }

    fun removeQueued(clientRef: String) {
        viewModelScope.launch { offline.remove(clientRef) }
    }

    /** Loads a sale into the cart to correct it; checkout then replaces it ([PosRepository.editSale]). */
    fun startEdit(r: PosReceipt) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val known = (products as? Load.Ready)?.value.orEmpty() + offline.products().orEmpty()
            val lines = mutableListOf<CartLine>()
            var missing = 0
            r.items.groupBy { it.variantId }.forEach { (variantId, items) ->
                val qty = items.sumOf { it.quantity - it.returnedQty }
                if (qty <= 0) return@forEach
                val product = known.firstOrNull { p -> p.variants.any { it.id == variantId } }
                    ?: pos.lookup(items.first().sku).getOrNull()?.product?.takeIf { p -> p.variants.any { it.id == variantId } }
                val variant = product?.variants?.firstOrNull { it.id == variantId }
                if (product == null || variant == null) {
                    missing++
                } else {
                    // The pieces of this sale come back to stock when it is corrected.
                    lines += CartLine(product, variant.copy(available = variant.available.coerceAtLeast(0) + qty), qty)
                }
            }
            if (lines.isEmpty()) {
                say("تعذّر تحميل أصناف الفاتورة ${r.number}")
                busy = false
                return@launch
            }
            newSale()
            editing = r
            cart = lines
            requote()
            currency = r.currency
            currencyPicked = true
            if (!Money.isZero(r.onAccount)) saleType = SaleType.CREDIT
            val c = r.customer
            val customerId = c?.id
            if (c != null && customerId != null && c.code != "CUS-WALKIN") {
                val full = c.code?.let { code -> finance.customers(code).getOrNull()?.items?.firstOrNull { it.id == customerId } }
                pickCustomer(full?.toPos() ?: PosCustomer(customerId, c.name, c.phone, c.code))
            }
            if (missing > 0) say("تعذّر تحميل $missing من أصناف الفاتورة")
            busy = false
        }
    }

    /** Leaves edit mode without changing the sale. */
    fun cancelEdit() {
        newSale()
    }

    fun newSale() {
        val wasEditing = editing != null
        receipt = null
        receiptEdited = false
        editing = null
        cart = emptyList()
        customer = null
        customerBalances = emptyList()
        customerQuery = ""
        customerResults = emptyList()
        discountValue = ""
        coupon = null
        couponText = ""
        refreshCoupons()
        receivedText = null
        multiCurrency = false
        saleType = SaleType.CASH
        changeCurrency = null
        checkoutError = null
        payMethod = defaultPayMethod()
        reference = ""
        note = ""
        slipFileId = null
        slipPreview = null
        printAfterSale = null
        tenders = tenders.map { it.copy(amount = "") }
        clientRef = UUID.randomUUID().toString()
        requote()
        if (wasEditing) {
            currencyPicked = false
            applyPreferredCurrency()
        }
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
fun PosScreen(
    session: StaffSession.LoggedIn,
    openBoxes: () -> Unit,
    openReturns: () -> Unit,
    onLogout: (() -> Unit)? = null,
    openPrinters: (() -> Unit)? = null,
    openShift: (() -> Unit)? = null,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val vm = adminViewModel { PosViewModel(it.pos, it.finance, it.treasury, OfflineSales.get(context), it.offers) }
    val snackbar = remember { SnackbarHostState() }
    val canArrange = session.can(Perm.PRODUCTS_WRITE)
    var arranging by rememberSaveable { mutableStateOf(false) }
    var order by remember { mutableStateOf<List<PosProduct>>(emptyList()) }
    var picking by remember { mutableStateOf<PosProduct?>(null) }
    var showCheckout by rememberSaveable { mutableStateOf(false) }
    var newCustomer by remember { mutableStateOf(false) }
    val canEditRates = session.can(Perm.SETTINGS_MANAGE)
    var editRates by remember { mutableStateOf(false) }
    var customizing by remember { mutableStateOf(false) }
    var showSales by remember { mutableStateOf(false) }
    var showPending by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<PosReceipt?>(null) }
    val pending by vm.pending.collectAsStateWithLifecycle()
    CollectMessages(vm, snackbar)
    // The till opens with the employee's own checkout layout and sale currency (when it is active).
    val profile = session.profile
    LaunchedEffect(profile?.posSettings, profile?.saleCurrency) { vm.useProfile(profile?.posSettings, profile?.saleCurrency) }
    LaunchedEffect(Unit) { OfflineSales.scheduleIfPending(context) }

    // Receipt printer: automatic after each sale (if set up), or from the receipt dialog.
    val printerSettings by adminContainer().printers.settings.collectAsStateWithLifecycle()
    val printScope = rememberCoroutineScope()
    var printing by remember { mutableStateOf(false) }
    var printStatus by remember { mutableStateOf<String?>(null) }
    fun printReceipt(r: PosReceipt) {
        if (printing) return
        if (!printerSettings.receipt.target.isSet) {
            printStatus = "لم تُضبط طابعة الفواتير بعد: المزيد ← الطابعات"
            return
        }
        printing = true
        printStatus = "جارٍ الطباعة…"
        printScope.launch {
            val result = Printers.printReceipt(context, printerSettings, r)
            printing = false
            printStatus = result.fold({ "تمت طباعة الفاتورة ✓" }, { it.message ?: "تعذّرت الطباعة" })
        }
    }
    // WhatsApp: the receipt as a PDF, straight to the customer's chat when the phone is known.
    var sharing by remember { mutableStateOf(false) }
    fun sendWhatsApp(r: PosReceipt) {
        if (sharing) return
        sharing = true
        printScope.launch {
            runCatching {
                val file = receiptPdf(context, r)
                shareReceiptToWhatsApp(context, file, r.customer?.phone, "فاتورة DEJAVU رقم ${r.number}")
            }.onFailure { printStatus = "تعذّر إنشاء ملف الفاتورة" }
            sharing = false
        }
    }
    LaunchedEffect(vm.receipt?.id) {
        printStatus = null
        val r = vm.receipt ?: return@LaunchedEffect
        val auto = vm.printAfterSale ?: printerSettings.receipt.autoPrint
        if (auto && printerSettings.receipt.target.isSet) printReceipt(r)
    }
    val scan = rememberBarcodeScanner { code -> vm.scan(code) { picking = it } }

    LaunchedEffect(arranging, vm.products) {
        if (arranging) order = (vm.products as? Load.Ready)?.value.orEmpty()
    }
    LaunchedEffect(vm.receipt) { if (vm.receipt != null) showCheckout = false }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GlassTopBar(
                title = { Text(if (arranging) "ترتيب المنتجات" else "نقطة البيع") },
                actions = {
                    if (pending.isNotEmpty()) {
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.error)
                                .clickable { showPending = true; vm.syncOffline(manual = true) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                if (vm.syncing) "جارٍ الإرسال…" else "بانتظار الإرسال: ${pending.size}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    BarIcon(BarcodeIcon, "مسح الباركود", enabled = !arranging, onClick = scan)
                    if (canArrange) {
                        IconButton(onClick = {
                            if (!arranging && vm.query.isNotBlank()) vm.search("")
                            arranging = !arranging
                        }) {
                            Icon(Icons.Filled.Menu, contentDescription = "ترتيب المنتجات", tint = if (arranging) Lux.Gold else Lux.Ink)
                        }
                    }
                    if (canEditRates) {
                        IconButton(onClick = { vm.loadCurrencies(); editRates = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "تعديل سعر الصرف")
                        }
                    }
                    IconButton(onClick = { showSales = true }, enabled = !arranging) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "فواتير اليوم")
                    }
                    IconButton(onClick = openReturns, enabled = !arranging) { Icon(Icons.Filled.Refresh, contentDescription = "مرتجع / استبدال") }
                    IconButton(onClick = vm::loadShift) { Icon(Icons.Filled.DateRange, contentDescription = "ملخص الوردية") }
                    if (openShift != null) BarIcon(ShiftIcon, "الوردية: فتح / إغلاق", onClick = openShift)
                    IconButton(onClick = openBoxes) { Icon(Icons.Filled.Lock, contentDescription = "الصناديق") }
                    if (openPrinters != null) IconButton(onClick = openPrinters) { Icon(Icons.Filled.Settings, contentDescription = "الطابعات") }
                    if (onLogout != null) {
                        IconButton(onClick = { confirmLogout = true }) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "تسجيل الخروج", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 900.dp
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!arranging) EditBanner(vm, Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
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
                        CartBar(vm) { vm.openCheckout(); showCheckout = true }
                    }
                }
                if (wide && !arranging) {
                    GlassCard(Modifier.width(420.dp).fillMaxHeight().padding(8.dp), contentPadding = 0.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("الفاتورة", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { customizing = true }) { Icon(Icons.Filled.Settings, contentDescription = "تخصيص صفحة الدفع") }
                        }
                        CheckoutContent(
                            vm,
                            canEditRates = canEditRates,
                            onNewCustomer = { newCustomer = true },
                            onEditRates = { editRates = true },
                            modifier = Modifier.weight(1f),
                        )
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
                Text("الفاتورة والدفع", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { customizing = true }) { Icon(Icons.Filled.Settings, contentDescription = "تخصيص صفحة الدفع") }
                IconButton(onClick = { showCheckout = false }) { Icon(Icons.Filled.Close, contentDescription = "إغلاق") }
            }
            CheckoutContent(
                vm,
                canEditRates = canEditRates,
                onNewCustomer = { newCustomer = true },
                onEditRates = { editRates = true },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }

    if (customizing) CheckoutSettingsDialog(vm, onDismiss = { customizing = false })

    if (showSales) {
        PosSalesSheet(
            canAll = session.can(Perm.CASHBOXES_MANAGE),
            canEdit = session.can(PERM_EDIT_SALES),
            currencyImage = { code -> vm.currencyImages[code] },
            onDismiss = { showSales = false },
            onView = { r ->
                printStatus = null
                viewing = r
            },
            onEdit = { r ->
                showSales = false
                vm.startEdit(r)
            },
            onWhatsApp = { r -> sendWhatsApp(r) },
        )
    }

    if (showPending) PendingSalesDialog(vm, pending, onDismiss = { showPending = false })

    if (confirmLogout && onLogout != null) {
        ConfirmDialog("تسجيل الخروج", "هل تريد الخروج من حسابك على هذا الجهاز؟", "خروج", onDismiss = { confirmLogout = false }, onConfirm = onLogout)
    }

    if (newCustomer) {
        QuickCustomerDialog(onDismiss = { newCustomer = false }) { name, phone -> vm.quickCustomer(name, phone) }
    }

    if (editRates && canEditRates) RatesDialog(vm, onDismiss = { editRates = false })

    vm.receipt?.let { r ->
        ReceiptDialog(
            r,
            title = if (vm.receiptEdited) "تم تعديل الفاتورة ✓" else "تمت عملية البيع ✓",
            confirmText = "بيع جديد",
            onNewSale = vm::newSale,
            printing = printing,
            printStatus = printStatus,
            onPrint = { printReceipt(r) },
            sharing = sharing,
            onWhatsApp = { sendWhatsApp(r) },
        )
    }

    viewing?.let { r ->
        ReceiptDialog(
            r,
            title = "الفاتورة ${r.number}",
            confirmText = "إغلاق",
            onNewSale = { viewing = null },
            printing = printing,
            printStatus = printStatus,
            onPrint = { printReceipt(r) },
            sharing = sharing,
            onWhatsApp = { sendWhatsApp(r) },
        )
    }

    vm.shift?.let { s -> ShiftDialog(s, onRetry = vm::loadShift, onDismiss = vm::closeShift, openBoxes = { vm.closeShift(); openBoxes() }) }
}

/** Permission to delete or correct a till sale. */
private const val PERM_EDIT_SALES = "pos.edit_sales"

/** "Correcting invoice X — cancel" while a sale is loaded for editing. */
@Composable
private fun EditBanner(vm: PosViewModel, modifier: Modifier = Modifier) {
    val r = vm.editing ?: return
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Lux.Champagne).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, tint = Lux.GoldDeep, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "تعديل الفاتورة ${r.number}",
            style = MaterialTheme.typography.labelLarge,
            color = Lux.Ink,
            modifier = Modifier.weight(1f),
        )
        SmallLink("إلغاء", enabled = !vm.busy, onClick = vm::cancelEdit)
    }
}

/** Sales saved without internet: send now, see why one was refused, or drop it. */
@Composable
private fun PendingSalesDialog(vm: PosViewModel, pending: List<QueuedSale>, onDismiss: () -> Unit) {
    var dropping by remember { mutableStateOf<QueuedSale?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("فواتير بانتظار الإرسال") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending.isEmpty()) Text("أُرسلت كل الفواتير ✓", color = Success)
                if (vm.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                pending.forEach { q ->
                    val r = q.request
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(formatDateTime(r.soldAt).ifBlank { "فاتورة محفوظة" }, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${r.items.sumOf { it.quantity }} قطعة" + (r.currency?.let { " · ${it.nameAr}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Lux.InkSoft,
                                )
                            }
                            if (q.error != null) SmallLink("حذف") { dropping = q }
                        }
                        q.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.syncOffline(manual = true) }, enabled = pending.isNotEmpty() && !vm.syncing) { Text("إرسال الآن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
    dropping?.let { q ->
        ConfirmDialog(
            "حذف الفاتورة المحفوظة",
            "رفض الخادم هذه الفاتورة. حذفها يلغيها نهائياً ولن تُرسل.",
            "حذف",
            onDismiss = { dropping = null },
            onConfirm = {
                vm.removeQueued(q.clientRef)
                dropping = null
            },
        )
    }
}

/** "تخصيص صفحة الدفع": order and show/hide the checkout buttons, and the default sale currency. */
@Composable
private fun CheckoutSettingsDialog(vm: PosViewModel, onDismiss: () -> Unit) {
    var order by remember { mutableStateOf(vm.layout.order) }
    var hidden by remember { mutableStateOf(vm.layout.hidden) }
    var saleCurrency by remember { mutableStateOf(vm.saleCurrencyCode) }
    fun move(i: Int, by: Int) {
        val j = i + by
        if (j !in order.indices) return
        order = order.toMutableList().apply { add(j, removeAt(i)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تخصيص صفحة الدفع") },
        text = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("عملة البيع الافتراضية", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        vm.currencies.forEach { c ->
                            SmallChip(
                                c.nameAr,
                                selected = (saleCurrency ?: Currency.SAR.code) == c.code,
                                leading = { CurrencyArt(c.code, vm.imageOf(c), height = 14.dp) },
                            ) { saleCurrency = c.code }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Lux.Hairline)
                    Text("الأزرار والأقسام", style = MaterialTheme.typography.titleSmall)
                    Text("رتّبها بالأسهم وأخفِ ما لا تستخدمه.", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                    order.forEachIndexed { i, b ->
                        val shown = !b.hideable || b !in hidden
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                b.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (shown) Lux.Ink else Lux.InkSoft,
                                modifier = Modifier.weight(1f),
                            )
                            SmallIcon(Icons.Filled.KeyboardArrowUp, "أعلى", tint = if (i > 0) Lux.Ink else Lux.Hairline) { move(i, -1) }
                            SmallIcon(Icons.Filled.KeyboardArrowDown, "أسفل", tint = if (i < order.lastIndex) Lux.Ink else Lux.Hairline) { move(i, 1) }
                            Spacer(Modifier.width(6.dp))
                            if (b.hideable) {
                                Switch(checked = shown, onCheckedChange = { on -> hidden = if (on) hidden - b else hidden + b })
                            } else {
                                Text("دائماً", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.width(52.dp), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    SmallLink("استعادة الترتيب الافتراضي") {
                        order = CheckoutLayout.DEFAULT.order
                        hidden = emptySet()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.saveSettings(CheckoutLayout(order, hidden), saleCurrency, onDismiss) },
                enabled = !vm.settingsBusy,
            ) { Text(if (vm.settingsBusy) "جارٍ الحفظ…" else "حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
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
        modifier.fillMaxWidth().alpha(if (out) 0.55f else 1f).then(if (tappable) Modifier.liquidClickable(onClick = onClick) else Modifier),
        elevation = if (lifted) 22.dp else 8.dp,
        contentPadding = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
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
            Text(p.nameAr, style = MaterialTheme.typography.titleSmall, color = Lux.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.skuNumber?.let { Text("#$it", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GoldBadge(Money.format(Money.parse(p.price), currency, rate))
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

/** Tight card used by the checkout so the whole form fits one phone screen. */
@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = 4.dp, contentPadding = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** Small selectable chip (30dp high). */
@Composable
private fun SmallChip(text: String, selected: Boolean, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        leadingIcon = leading,
        modifier = Modifier.height(30.dp),
    )
}

/** Small gold text link. */
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

/** Small clickable icon (no 48dp touch-target padding). */
@Composable
private fun SmallIcon(icon: ImageVector, description: String, tint: Color = Lux.InkSoft, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp)) }
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
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Lux.Ink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        interactionSource = interaction,
        cursorBrush = SolidColor(Lux.GoldDeep),
        modifier = modifier.height(40.dp).alpha(if (enabled) 1f else 0.45f),
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = inner,
            enabled = enabled,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interaction,
            isError = isError,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, maxLines = 1) },
            trailingIcon = trailing,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            container = {
                OutlinedTextFieldDefaults.Container(enabled = enabled, isError = isError, interactionSource = interaction, shape = shape)
            },
        )
    }
}

/** Gold "تاجر" badge. */
@Composable
private fun MerchantBadge() {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Lux.GoldBrush).padding(horizontal = 8.dp, vertical = 1.dp),
    ) { Text("تاجر", color = Color.White, style = MaterialTheme.typography.labelSmall) }
}

/** A currency's note / coin picture and symbol, e.g. as a field's trailing tag. */
@Composable
private fun CurrencyTag(c: Currency, vm: PosViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CurrencyArt(c.code, vm.imageOf(c), height = 14.dp)
        Text(c.symbol, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    }
}

@Composable
private fun CheckoutContent(vm: PosViewModel, canEditRates: Boolean, onNewCustomer: () -> Unit, onEditRates: () -> Unit, modifier: Modifier = Modifier) {
    val t = vm.totals
    val inv = vm.currency
    val rate = vm.rateOf(inv) ?: BigDecimal.ONE
    fun inInvoice(sar: BigDecimal) = Money.format(sar, inv, rate)
    val discountNumber = vm.discountValue.trim().toBigDecimalOrNull()
    val discountValid = vm.discountValue.isBlank() || when (vm.discountType) {
        DiscountType.PERCENT -> discountNumber != null && discountNumber.signum() >= 0 && discountNumber <= HUNDRED
        DiscountType.AMOUNT -> discountNumber != null && discountNumber.signum() >= 0 &&
            discountNumber.stripTrailingZeros().scale() <= inv.decimals && discountNumber <= Money.convert(t.subtotal.subtract(t.offers), inv, rate)
    }
    val tendersValid = if (vm.multiCurrency) {
        vm.tenders.all { it.amount.isBlank() || validAmount(it.amount, it.currency) != null }
    } else {
        vm.receivedShown.isBlank() || validAmount(vm.receivedShown, inv) != null || vm.receivedShown.toBigDecimalOrNull()?.signum() == 0
    }
    val credit = vm.saleType == SaleType.CREDIT
    val canSell = vm.cart.isNotEmpty() && !vm.busy && !vm.slipUploading && discountValid && tendersValid &&
        (if (credit) vm.customer != null else t.owed.signum() == 0)
    val layout = vm.layout
    val shown = layout.shown
    val payButtons = shown.filter { it in CheckoutButton.PAYMENT }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    Column(modifier) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        EditBanner(vm)

        // ---- lines (own scroll, capped so the form stays visible)
        Section {
            if (vm.cart.isEmpty()) {
                Text("السلة فارغة. اختر المنتجات من الشبكة أو امسح الباركود.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            } else {
                Text("الأصناف (${vm.cart.sumOf { it.qty }})", style = MaterialTheme.typography.labelLarge)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    vm.cart.forEach { l -> key(l.variant.id) { CartLineRow(l, vm, inv, rate) } }
                }
                vm.appliedDeals.forEach { d -> DealRow(d, inv, rate) }
            }
        }

        // ---- the employee's own order of buttons and sections
        // The payment buttons share one row, placed where the first of them is.
        val payAnchor = payButtons.firstOrNull()
        shown.forEach { b ->
            key(b.id) {
                when (b) {
                    CheckoutButton.CURRENCY -> CurrencySection(vm, canEditRates, onEditRates)
                    CheckoutButton.CASH, CheckoutButton.TRANSFER, CheckoutButton.DEPOSIT, CheckoutButton.CREDIT -> if (b == payAnchor) {
                        Section { PayMethodSection(vm, payButtons) }
                    }
                    CheckoutButton.CUSTOMER -> CustomerSection(vm, credit, onNewCustomer)
                    CheckoutButton.DISCOUNT -> DiscountSection(vm, discountValid)
                    CheckoutButton.AMOUNT -> Section {
                        TotalsRows(vm, t)
                        HorizontalDivider(color = Lux.Hairline)
                        if (vm.multiCurrency) TenderFieldsSection(vm) else ReceivedField(vm, credit, tendersValid)
                        PaymentSummary(vm, t, credit)
                    }
                    CheckoutButton.MULTI -> OutlinedButton(
                        onClick = { vm.setMulti(!vm.multiCurrency) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text(if (vm.multiCurrency) "الدفع بعملة واحدة" else "الدفع بأكثر من عملة", style = MaterialTheme.typography.labelLarge) }
                    CheckoutButton.NOTE -> Section {
                        CompactField(
                            value = vm.note,
                            onValueChange = { vm.note = it.take(300) },
                            placeholder = "ملاحظة على الفاتورة (اختياري)",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CheckoutButton.PRINT -> PrintToggle(vm)
                }
                Unit
            }
        }
        // A credit sale needs the customer even when the customer section is hidden; bank payments need their details.
        if (!layout.isShown(CheckoutButton.CUSTOMER) && (credit || vm.customer != null)) CustomerSection(vm, credit, onNewCustomer)
        if (payButtons.isEmpty() && vm.payMethod != PayMethod.CASH) Section { PayMethodSection(vm, emptyList()) }
    }
    // ---- submit (always visible)
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentPadding = 10.dp) {
        vm.checkoutError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)) }
        val hint = when {
            vm.cart.isEmpty() -> "أضف منتجات أولاً"
            credit && vm.customer == null -> "اختر حساب العميل للبيع الآجل"
            vm.slipUploading -> "جارٍ رفع صورة الإيصال…"
            !credit && t.owed.signum() > 0 -> "المتبقي ${inInvoice(t.owed)} — أكمل المبلغ أو اختر آجل"
            else -> null
        }
        hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)) }
        LuxButton(
            when {
                vm.busy -> "جارٍ الحفظ…"
                vm.editing != null -> "حفظ التعديل · ${inInvoice(t.total)}"
                else -> "إتمام الفاتورة · ${inInvoice(t.total)}"
            },
            onClick = vm::sell,
            enabled = canSell,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    }
    }
}

/** Invoice currency chips and today's rates. */
@Composable
private fun CurrencySection(vm: PosViewModel, canEditRates: Boolean, onEditRates: () -> Unit) {
    Section {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            vm.currencies.forEach { c ->
                SmallChip(c.symbol, selected = c == vm.currency, leading = { CurrencyArt(c.code, vm.imageOf(c), height = 14.dp) }) { vm.pickCurrency(c) }
            }
        }
        val rateLine = vm.currencies.filter { !it.isBase }.mapNotNull { c -> vm.rateOf(c)?.let { Money.plain(it, c) } }
        if (rateLine.isNotEmpty() || canEditRates) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (rateLine.isEmpty()) "لا توجد أسعار صرف" else "1 ر.س = ${rateLine.joinToString(" · ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lux.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (canEditRates) SmallLink("تعديل السعر", onClick = onEditRates)
            }
        }
    }
}

/** The typed invoice discount (% or amount). */
@Composable
private fun DiscountSection(vm: PosViewModel, valid: Boolean) {
    // A coupon code, only while the store has a live coupon.
    if (vm.couponsAvailable || vm.coupon != null) {
        Section {
            val c = vm.coupon
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("كوبون", style = MaterialTheme.typography.labelLarge)
                if (c != null) {
                    Text(
                        "${c.code} · " + if (c.type == "PERCENTAGE") "${c.value}%" else Money.formatBase(c.value),
                        Modifier.weight(1f),
                        color = Success,
                    )
                    SmallLink("إزالة", onClick = vm::removeCoupon)
                } else {
                    CompactField(
                        value = vm.couponText,
                        onValueChange = { vm.couponText = it.uppercase().take(40) },
                        placeholder = "كود الخصم",
                        modifier = Modifier.weight(1f),
                    )
                    SmallLink("تطبيق", onClick = vm::applyCoupon)
                }
            }
        }
    }
    if (vm.coupon != null) return
    Section {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("خصم", style = MaterialTheme.typography.labelLarge)
            SmallChip("%", selected = vm.discountType == DiscountType.PERCENT) { vm.editDiscount(type = DiscountType.PERCENT) }
            SmallChip("مبلغ", selected = vm.discountType == DiscountType.AMOUNT) { vm.editDiscount(type = DiscountType.AMOUNT) }
            CompactField(
                value = vm.discountValue,
                onValueChange = { vm.editDiscount(value = it) },
                placeholder = if (vm.discountType == DiscountType.PERCENT) "النسبة %" else "المبلغ (${vm.currency.symbol})",
                isError = !valid,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Subtotal, discount and total of the invoice. */
@Composable
private fun TotalsRows(vm: PosViewModel, t: PosTotals) {
    val inv = vm.currency
    val rate = vm.rateOf(inv) ?: BigDecimal.ONE
    CompactRow("المجموع", Money.format(t.subtotal, inv, rate))
    if (t.offers.signum() > 0) CompactRow("العروض", "− ${Money.format(t.offers, inv, rate)}", Success)
    if (t.discount.signum() > 0) CompactRow("الخصم", "− ${Money.format(t.discount, inv, rate)}", MaterialTheme.colorScheme.error)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("الإجمالي", style = MaterialTheme.typography.titleSmall)
            if (!inv.isBase) Text("يعادل ${Money.formatBase(t.total.toPlainString())}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
        }
        CurrencyArt(inv.code, vm.imageOf(inv), height = 18.dp)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Lux.GoldBrush).padding(horizontal = 12.dp, vertical = 4.dp),
        ) { Text(Money.format(t.total, inv, rate), color = Color.White, style = MaterialTheme.typography.titleSmall) }
    }
}

/** One received-amount field in the invoice currency; less than due becomes a discount. */
@Composable
private fun ReceivedField(vm: PosViewModel, credit: Boolean, valid: Boolean) {
    val inv = vm.currency
    Text(if (credit) "المدفوع الآن (والباقي على حساب العميل)" else "المبلغ المستلم", style = MaterialTheme.typography.labelLarge)
    CompactField(
        value = vm.receivedShown,
        onValueChange = vm::editReceived,
        placeholder = if (credit) "دفعة مقدمة (اختياري) ${inv.symbol}" else "المبلغ المستلم (${inv.symbol})",
        isError = !valid,
        keyboardType = KeyboardType.Decimal,
        trailing = { CurrencyTag(inv, vm) },
        modifier = Modifier.fillMaxWidth(),
    )
    vm.receivedDiscount?.let { d ->
        Text("خصم: ${Money.plain(d, inv)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    }
}

/** One quantity offer the basket gets: "🎁 name ×times −discount", in green. */
@Composable
private fun DealRow(d: PosQuote.Applied, inv: Currency, rate: BigDecimal) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Success.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "🎁 ${d.name}" + if (d.times > 1) " ×${d.times}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = Success,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("−${Money.format(Money.parse(d.discount), inv, rate)}", style = MaterialTheme.typography.bodySmall, color = Success, fontWeight = FontWeight.SemiBold)
    }
}

/** One label/value line in small type. */
@Composable
private fun CompactRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

/** Thumbnail, name + variant, unit price, qty stepper, line total and delete, in one row. */
@Composable
private fun CartLineRow(l: CartLine, vm: PosViewModel, inv: Currency, rate: BigDecimal) {
    val buyer = vm.customer
    val unit = l.unitPrice(buyer)
    val shelf = Money.parse(l.variant.price)
    val merchantPrice = buyer?.isMerchant == true && unit.compareTo(shelf) != 0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Lux.Champagne),
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
            Text(
                "${l.product.nameAr} · ${l.variant.title}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Money.format(unit, inv, rate), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, maxLines = 1)
                if (merchantPrice) {
                    Text("سعر التاجر", style = MaterialTheme.typography.labelSmall, color = Lux.GoldDeep, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        StepButton("−", enabled = true) { vm.setQty(l.variant.id, l.qty - 1) }
        Text("${l.qty}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        StepButton("+", enabled = l.qty < l.variant.available) { vm.setQty(l.variant.id, l.qty + 1) }
        Text(
            Money.format(l.lineTotal(buyer), inv, rate),
            style = MaterialTheme.typography.labelLarge,
            color = Lux.GoldDeep,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 64.dp).padding(start = 6.dp),
        )
        SmallIcon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.error) { vm.setQty(l.variant.id, 0) }
    }
}

/** Payment buttons (in the employee's order), then bank details and the slip photo for a transfer / deposit. */
@Composable
private fun PayMethodSection(vm: PosViewModel, buttons: List<CheckoutButton>) {
    if (buttons.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons.forEach { b ->
                val method = b.payMethod
                if (method != null) {
                    PayTile(b.label, payIcon(b), selected = vm.payMethod == method, modifier = Modifier.weight(1f)) { vm.pickPayMethod(method) }
                } else {
                    PayTile(b.label, payIcon(b), selected = vm.saleType == SaleType.CREDIT, modifier = Modifier.weight(1f), onClick = vm::toggleCredit)
                }
            }
        }
    }
    if (vm.payMethod != PayMethod.CASH) {
        CompactField(
            value = vm.reference,
            onValueChange = { vm.reference = it.take(100) },
            placeholder = if (vm.payMethod == PayMethod.DEPOSIT) "رقم سند الإيداع" else "رقم الحوالة / المرجع",
            modifier = Modifier.fillMaxWidth(),
        )
        SlipPhoto(vm)
        // One bank account per currency being paid in.
        val paid = if (vm.multiCurrency) vm.tenders.filter { it.amount.isNotBlank() }.map { it.currency } else listOf(vm.currency)
        val used = paid.ifEmpty { listOf(vm.currency) }.distinct()
        used.forEach { c ->
            val options = vm.banks.filter { it.currency == c }
            if (options.isEmpty()) {
                Text("لا يوجد حساب بنكي بعملة ${c.nameAr}؛ أضفه من الصناديق والخزائن", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            } else {
                val chosen = vm.bankFor[c] ?: options.first().id
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("بنك ${c.symbol}:", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.align(Alignment.CenterVertically))
                    options.forEach { b -> SmallChip(b.name, selected = b.id == chosen) { vm.pickBank(c, b.id) } }
                }
            }
        }
    }
}

private fun payIcon(b: CheckoutButton): ImageVector = when (b) {
    CheckoutButton.CASH -> CashIcon
    CheckoutButton.TRANSFER -> BankIcon
    CheckoutButton.DEPOSIT -> SlipIcon
    else -> CreditIcon
}

/** Big payment-method tile: a 32dp icon over its label, gold when selected. */
@Composable
private fun PayTile(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(if (selected) Lux.Gold.copy(alpha = 0.22f) else Lux.Champagne.copy(alpha = 0.6f))
            .border(if (selected) 2.dp else 1.dp, if (selected) Lux.GoldDeep else Lux.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Lux.GoldDeep else Lux.InkSoft, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Lux.GoldDeep else Lux.Ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Top-bar icon button; a long press shows its [label]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = label,
                onLongClick = { Toast.makeText(context, label, Toast.LENGTH_SHORT).show() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Lux.Ink, modifier = Modifier.size(24.dp).alpha(if (enabled) 1f else 0.38f))
    }
}

// Simple line icons (material-icons-core has no cash / bank / barcode glyphs).

private fun lineIcon(name: String, build: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        )
        .build()

/** A circle of radius [r] around ([cx], [cy]) as two arcs. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx + r, y1 = cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx - r, y1 = cy)
    close()
}

private val CashIcon: ImageVector by lazy {
    lineIcon("cash") {
        moveTo(2.5f, 6.5f); lineTo(21.5f, 6.5f); lineTo(21.5f, 17.5f); lineTo(2.5f, 17.5f); close()
        circle(12f, 12f, 3f)
        moveTo(5.5f, 9.5f); lineTo(5.5f, 9.5f)
        moveTo(18.5f, 14.5f); lineTo(18.5f, 14.5f)
    }
}

private val CreditIcon: ImageVector by lazy {
    lineIcon("credit") {
        // Hourglass: paid later.
        moveTo(6.5f, 3f); lineTo(17.5f, 3f)
        moveTo(6.5f, 21f); lineTo(17.5f, 21f)
        moveTo(7.5f, 3f); lineTo(7.5f, 6.5f); lineTo(12f, 12f); lineTo(7.5f, 17.5f); lineTo(7.5f, 21f)
        moveTo(16.5f, 3f); lineTo(16.5f, 6.5f); lineTo(12f, 12f); lineTo(16.5f, 17.5f); lineTo(16.5f, 21f)
        moveTo(10f, 18.5f); lineTo(14f, 18.5f)
    }
}

private val BankIcon: ImageVector by lazy {
    lineIcon("bank") {
        moveTo(3f, 9f); lineTo(12f, 4f); lineTo(21f, 9f); close()
        moveTo(6f, 11.5f); lineTo(6f, 17f)
        moveTo(10f, 11.5f); lineTo(10f, 17f)
        moveTo(14f, 11.5f); lineTo(14f, 17f)
        moveTo(18f, 11.5f); lineTo(18f, 17f)
        moveTo(3f, 20f); lineTo(21f, 20f)
    }
}

private val SlipIcon: ImageVector by lazy {
    lineIcon("slip") {
        moveTo(6f, 3f); lineTo(14.5f, 3f); lineTo(18.5f, 7f); lineTo(18.5f, 21f); lineTo(6f, 21f); close()
        moveTo(14.5f, 3f); lineTo(14.5f, 7f); lineTo(18.5f, 7f)
        moveTo(9f, 11f); lineTo(15.5f, 11f)
        moveTo(9f, 14.5f); lineTo(15.5f, 14.5f)
        moveTo(9f, 18f); lineTo(12.5f, 18f)
    }
}

private val BarcodeIcon: ImageVector by lazy {
    lineIcon("barcode") {
        // Scan frame corners.
        moveTo(3f, 8f); lineTo(3f, 4f); lineTo(7f, 4f)
        moveTo(17f, 4f); lineTo(21f, 4f); lineTo(21f, 8f)
        moveTo(21f, 16f); lineTo(21f, 20f); lineTo(17f, 20f)
        moveTo(7f, 20f); lineTo(3f, 20f); lineTo(3f, 16f)
        // Bars.
        moveTo(7f, 8f); lineTo(7f, 16f)
        moveTo(10f, 8f); lineTo(10f, 16f)
        moveTo(12.5f, 8f); lineTo(12.5f, 16f)
        moveTo(15f, 8f); lineTo(15f, 16f)
        moveTo(17.5f, 8f); lineTo(17.5f, 16f)
    }
}

private val ShiftIcon: ImageVector by lazy {
    lineIcon("shift") {
        circle(12f, 12f, 9f)
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f)
    }
}

/** "تصوير الإيصال": camera or gallery, uploaded at once; thumbnail with remove. */
@Composable
private fun SlipPhoto(vm: PosViewModel) {
    val picker = rememberImagePicker { bytes -> vm.uploadSlip(bytes) }
    val preview = vm.slipPreview
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (preview != null) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Lux.Champagne), contentAlignment = Alignment.Center) {
                val bitmap = remember(preview) { BitmapFactory.decodeByteArray(preview, 0, preview.size)?.asImageBitmap() }
                if (bitmap != null) Image(bitmap, contentDescription = "صورة الإيصال", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (vm.slipUploading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        vm.slipUploading -> "جارٍ رفع الإيصال…"
                        vm.slipFileId != null -> "تم إرفاق صورة الإيصال ✓"
                        else -> "صورة الإيصال"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (vm.slipFileId != null) Success else Lux.InkSoft,
                )
                Row {
                    SmallLink("تغيير", enabled = !vm.slipUploading, onClick = picker.fromCamera)
                    SmallLink("إزالة", enabled = !vm.slipUploading, onClick = vm::removeSlip)
                }
            }
        } else {
            SmallLink("تصوير الإيصال", onClick = picker.fromCamera)
            SmallLink("من المعرض", onClick = picker.fromGallery)
        }
    }
}

/** Print this sale's receipt after checkout (default: the printer's automatic setting). */
@Composable
private fun PrintToggle(vm: PosViewModel) {
    val settings by adminContainer().printers.settings.collectAsStateWithLifecycle()
    val on = vm.printAfterSale ?: settings.receipt.autoPrint
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallChip("طباعة الفاتورة بعد البيع", selected = on) { vm.printAfterSale = !on }
        if (on && !settings.receipt.target.isSet) {
            Text("لم تُضبط طابعة الفواتير", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Several tender fields, one per currency (two per row). */
@Composable
private fun TenderFieldsSection(vm: PosViewModel) {
    var adding by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("المدفوع بأكثر من عملة", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        val more = vm.currencies.filter { c -> vm.tenders.none { it.currency == c } }
        if (more.isNotEmpty()) {
            Box {
                SmallLink("+ عملة", onClick = { adding = true })
                DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                    more.forEach { c ->
                        DropdownMenuItem(
                            text = { Text("${c.nameAr} (${c.symbol})") },
                            leadingIcon = { CurrencyArt(c.code, vm.imageOf(c), height = 16.dp) },
                            onClick = {
                                vm.addTender(c)
                                adding = false
                            },
                        )
                    }
                }
            }
        }
    }
    val defaults = setOf(Currency.SAR, Currency.YER, vm.currency)
    // Two tender fields per row (SAR and YER side by side by default).
    vm.tenders.withIndex().chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (i, tender) ->
                val c = tender.currency
                Column(Modifier.weight(1f)) {
                    CompactField(
                        value = tender.amount,
                        onValueChange = { vm.setTender(i, it) },
                        placeholder = if (c.decimals == 0) "${c.nameAr} بدون كسور" else c.nameAr,
                        isError = tender.amount.isNotBlank() && validAmount(tender.amount, c) == null,
                        keyboardType = KeyboardType.Decimal,
                        trailing = { CurrencyTag(c, vm) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallLink("المتبقي") { vm.fillRemaining(i) }
                        Spacer(Modifier.weight(1f))
                        if (c !in defaults) SmallLink("حذف") { vm.removeTender(i) }
                    }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** What is still owed (or put on the account), or the change to hand back and its currency. */
@Composable
private fun PaymentSummary(vm: PosViewModel, t: PosTotals, credit: Boolean) {
    val inv = vm.currency
    val rate = vm.rateOf(inv) ?: BigDecimal.ONE
    val others = vm.currencies.filter { it != inv }
    when {
        t.owed.signum() > 0 -> {
            CompactRow(
                if (credit) "على حساب العميل" else "المتبقي",
                Money.format(t.owed, inv, rate),
                valueColor = if (credit) Lux.GoldDeep else MaterialTheme.colorScheme.error,
            )
            Equivalents(t.owed, others, vm)
        }
        t.change.signum() > 0 -> {
            val changeCur = vm.changeIn
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("الباقي للعميل", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                CurrencyArt(changeCur.code, vm.imageOf(changeCur), height = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(Money.plain(t.changeAmount, changeCur), style = MaterialTheme.typography.bodySmall, color = Success, fontWeight = FontWeight.SemiBold)
            }
            Equivalents(t.change, vm.currencies.filter { it != changeCur }, vm)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("عملة الباقي:", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.align(Alignment.CenterVertically))
                vm.currencies.forEach { c ->
                    SmallChip(c.symbol, selected = c == changeCur, leading = { CurrencyArt(c.code, vm.imageOf(c), height = 12.dp) }) { vm.changeCurrency = c }
                }
            }
        }
        vm.cart.isNotEmpty() -> CompactRow("المتبقي", Money.format(BigDecimal.ZERO, inv, rate), valueColor = Success)
    }
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
private fun CustomerSection(vm: PosViewModel, credit: Boolean, onNewCustomer: () -> Unit) {
    Section {
        val c = vm.customer
        if (c != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Lux.GoldDeep, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (c.isMerchant) MerchantBadge()
                    }
                    Text(listOfNotNull(c.code, c.phone).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, maxLines = 1)
                }
                SmallIcon(Icons.Filled.Close, "إزالة العميل", onClick = vm::clearCustomer)
            }
            if (c.isMerchant) {
                val off = c.merchantDiscount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                val parts = listOfNotNull(
                    off?.let { "خصم التاجر ${it.stripTrailingZeros().toPlainString()}%" },
                    c.creditLimit?.takeIf { it.isNotBlank() }?.let { "حد الائتمان ${Money.formatBase(it)}" },
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Lux.GoldDeep)
            }
            vm.customerBalances.filter { Money.parse(it.balance).signum() != 0 }.forEach { b ->
                val v = Money.parse(b.balance)
                CompactRow(
                    if (v.signum() > 0) "مستحق على العميل" else "رصيد للعميل",
                    Money.plain(v.abs(), b.currency),
                    valueColor = if (v.signum() > 0) MaterialTheme.colorScheme.error else Success,
                )
            }
        } else {
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExposedDropdownMenuBox(
                    expanded = open,
                    onExpandedChange = { e -> open = e; if (e) vm.searchCustomers(vm.customerQuery) },
                    modifier = Modifier.weight(1f),
                ) {
                    CompactField(
                        value = vm.customerQuery,
                        onValueChange = { vm.searchCustomers(it); open = true },
                        placeholder = if (credit) "العميل (مطلوب للآجل) — ابحث بالاسم أو الهاتف" else "العميل (اختياري) — ابحث بالاسم أو الهاتف",
                        isError = credit,
                        trailing = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                    )
                    ExposedDropdownMenu(expanded = open && vm.customerResults.isNotEmpty(), onDismissRequest = { open = false }) {
                        vm.customerResults.forEach { r ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(r.name, style = MaterialTheme.typography.bodyMedium)
                                            if (r.isMerchant) MerchantBadge()
                                        }
                                        Text("${r.code} · ${r.phone}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                    }
                                },
                                onClick = {
                                    open = false
                                    vm.pickCustomer(r.toPos())
                                },
                            )
                        }
                    }
                }
                SmallLink("+ عميل جديد", onClick = onNewCustomer)
            }
        }
    }
}

@Composable
private fun Equivalents(amountSar: BigDecimal, currencies: List<Currency>, vm: PosViewModel) {
    val shown = currencies.mapNotNull { c -> vm.rateOf(c)?.let { Money.format(amountSar, c, it) } }
    if (shown.isNotEmpty()) {
        Text("يعادل: ${shown.joinToString(" · ")}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    }
}

/** Quick exchange-rate editor for managers: units per 1 SAR, up to 6 decimals, saved one currency at a time. */
@Composable
private fun RatesDialog(vm: PosViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("أسعار الصرف") },
        text = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("كم وحدة تساوي 1 ر.س؟ يُطبَّق على الفواتير الجديدة فقط.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    if (vm.rateRows.isEmpty()) Text("لا توجد عملات أخرى مفعّلة", style = MaterialTheme.typography.bodyMedium)
                    vm.rateRows.forEach { row -> key(row.code.code) { RateRow(row, vm) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

@Composable
private fun RateRow(row: CurrencyRate, vm: PosViewModel) {
    val c = Currency.of(row.code.code)
    val current = row.rate?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString().orEmpty()
    var text by remember(current) { mutableStateOf(current) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val value = text.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 6 }
    val changed = value != null && value.compareTo(current.toBigDecimalOrNull() ?: BigDecimal.ZERO) != 0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${c.nameAr} (${c.symbol})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            CompactField(
                value = text,
                onValueChange = { text = decimalInput(it).take(20); status = null },
                placeholder = "السعر",
                isError = text.isNotBlank() && value == null,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.width(110.dp),
            )
            TextButton(
                onClick = {
                    vm.saveRate(c, value!!.stripTrailingZeros().toPlainString()) { err ->
                        status = if (err == null) true to "تم الحفظ" else false to err
                    }
                },
                enabled = changed && !vm.rateBusy,
            ) { Text("حفظ") }
        }
        status?.let { (ok, msg) ->
            Text(msg, style = MaterialTheme.typography.labelSmall, color = if (ok) Success else MaterialTheme.colorScheme.error)
        }
        if (row.rate == null) Text("لا يوجد سعر بعد", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    }
}

// ----------------------------------------------------------------- dialogs ---

@Composable
internal fun QuickCustomerDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
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
private fun ReceiptDialog(
    r: PosReceipt,
    title: String,
    confirmText: String,
    onNewSale: () -> Unit,
    printing: Boolean,
    printStatus: String?,
    onPrint: () -> Unit,
    sharing: Boolean = false,
    onWhatsApp: (() -> Unit)? = null,
) {
    val cur = r.currency
    fun inCur(v: String) = Money.plain(Money.parse(v), cur)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
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
                printStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 6.dp)) }
                if (onWhatsApp != null) {
                    OutlinedButton(
                        onClick = onWhatsApp,
                        enabled = !sharing,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(if (sharing) "جارٍ تجهيز الفاتورة…" else "إرسال واتساب", color = WhatsAppGreen)
                    }
                    if (r.customer?.phone.isNullOrBlank()) {
                        Text("لا يوجد رقم للعميل؛ ستختار المحادثة في واتساب.", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onNewSale,
                shape = RoundedCornerShape(16.dp),
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onPrint, enabled = !printing) { Text(if (printing) "جارٍ الطباعة…" else "طباعة الفاتورة") }
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
internal fun LuxButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    LiquidGoldButton(text, onClick, modifier, enabled = enabled, height = 52.dp)
