@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.AddressRepository
import com.dejavu.core.data.CartRepository
import com.dejavu.core.data.CurrencyRepository
import com.dejavu.core.data.OrderRepository
import com.dejavu.core.data.ServerMessages
import com.dejavu.core.data.WalletRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Address
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.Wallet
import com.dejavu.core.network.ApiException
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.appViewModel
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.SectionTitle
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.label
import com.dejavu.core.ui.theme.Success
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class CheckoutState(
    val addresses: List<Address>? = null,
    val methods: List<PaymentMethod> = listOf(PaymentMethod.CASH_ON_DELIVERY),
    val addressId: String? = null,
    val method: PaymentMethod = PaymentMethod.CASH_ON_DELIVERY,
    val note: String = "",
    val placing: Boolean = false,
    val problems: List<String> = emptyList(),
    /** Wallet balances, for paying from the account; null until loaded (or if it failed). */
    val wallet: Wallet? = null,
)

class CheckoutViewModel(
    private val addressRepo: AddressRepository,
    private val cartRepo: CartRepository,
    private val orders: OrderRepository,
    private val currencies: CurrencyRepository,
    private val walletRepo: WalletRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow(CheckoutState())
    val state = _state.asStateFlow()
    val cart = cartRepo.cart
    /** Placed order number, plus the card page to open when paying by card. */
    private val _placed = MutableSharedFlow<Pair<String, String?>>(extraBufferCapacity = 1)
    val placed = _placed.asSharedFlow()

    init {
        viewModelScope.launch { _state.value = _state.value.copy(methods = orders.paymentMethods()) }
        // Fresh rate before invoicing, so the total shown is the one the order gets.
        viewModelScope.launch { currencies.refresh() }
    }

    /** Balances for "pay from wallet"; reloaded when coming back from a top-up. */
    fun loadWallet() {
        viewModelScope.launch {
            walletRepo.wallet().getOrNull()?.let { _state.value = _state.value.copy(wallet = it) }
        }
    }

    fun loadAddresses() {
        viewModelScope.launch {
            val list = addressRepo.list().orSay() ?: return@launch
            val current = _state.value.addressId?.takeIf { id -> list.any { it.id == id } }
            val chosen = current ?: (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.id
            _state.value = _state.value.copy(addresses = list, addressId = chosen)
            chosen?.let { cartRepo.refresh(it).orSay() }
        }
    }

    fun chooseAddress(id: String) {
        _state.value = _state.value.copy(addressId = id)
        viewModelScope.launch { cartRepo.refresh(id).orSay() }
    }

    fun chooseMethod(m: PaymentMethod) {
        _state.value = _state.value.copy(method = m)
    }

    fun setNote(note: String) {
        _state.value = _state.value.copy(note = note.take(500))
    }

    fun place() {
        val s = _state.value
        val addressId = s.addressId ?: return say("اختر عنوان التوصيل")
        if (s.placing) return
        _state.value = s.copy(placing = true, problems = emptyList())
        viewModelScope.launch {
            orders.checkout(addressId, s.method, s.note, currencies.checkoutCurrency())
                .onSuccess { order ->
                    // The order exists either way; if the card page fails to open, it can be retried from the order.
                    val url = if (order.canPayByCard) orders.payByCard(order.number).orSay()?.checkoutUrl else null
                    _placed.tryEmit(order.number to url)
                }
                .onFailure { e ->
                    val problems = (e as? ApiException)?.problems.orEmpty().map(ServerMessages::translate)
                    _state.value = _state.value.copy(problems = problems)
                    say(e.userMessage())
                    cartRepo.refresh(addressId)
                }
            _state.value = _state.value.copy(placing = false)
        }
    }
}

@Composable
fun CheckoutScreen(onBack: () -> Unit, addAddress: () -> Unit, topUp: () -> Unit, placed: (String) -> Unit) {
    val vm = appViewModel { CheckoutViewModel(it.addresses, it.cart, it.orders, it.currencies, it.wallet) }
    val rates by appContainer().currencies.rates.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val cart by vm.cart.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(vm) {
        vm.placed.collect { (number, cardUrl) ->
            cardUrl?.let { runCatching { uriHandler.openUri(it) } }
            placed(number)
        }
    }
    // Reload when coming back from "add address".
    LifecycleResumeEffect(vm) {
        vm.loadAddresses()
        vm.loadWallet()
        onPauseOrDispose {}
    }

    // What paying from the wallet would take: the total in the invoice (display) currency
    // versus the balance in that currency. The server has the final say.
    val display = rates.let { Money.display }
    val walletBalance = state.wallet?.let { Money.parse(it.available(display.currency)) }
    val orderTotal = cart?.let { Money.convert(Money.parse(it.totals.grandTotal), display.currency, display.rate) }
    val walletShort = walletBalance != null && orderTotal != null && walletBalance < orderTotal

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("إتمام الطلب", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.padding(16.dp)) {
                    cart?.let { SummaryRow("الإجمالي", Money.format(it.totals.grandTotal), strong = true) }
                    // Recompose when the rate refreshes.
                    rates?.let {
                        val d = Money.display
                        if (d.currency != Currency.SAR) {
                            Text(
                                "بـ${d.currency.nameAr} بسعر 1 ر.س = ${Money.plain(d.rate, d.currency)}، ويُثبَّت هذا السعر في فاتورتك",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = vm::place,
                        enabled = !state.placing && state.addressId != null && cart?.canCheckout == true &&
                            !(state.method == PaymentMethod.CUSTOMER_ACCOUNT && walletShort),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        if (state.placing) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text("تأكيد الطلب")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (state.problems.isNotEmpty()) {
                item {
                    Card(Modifier.padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp)) { state.problems.forEach { Text("• $it") } }
                    }
                }
            }
            item { SectionTitle("عنوان التوصيل") }
            val addresses = state.addresses
            if (addresses == null) {
                item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            } else {
                items(addresses, key = { it.id }) { a ->
                    Choice(selected = a.id == state.addressId, onClick = { vm.chooseAddress(a.id) }) {
                        Text(a.label ?: a.recipient, style = MaterialTheme.typography.titleSmall)
                        Text(a.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(a.phone, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    OutlinedButton(onClick = addAddress, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("إضافة عنوان جديد")
                    }
                }
            }

            item { SectionTitle("طريقة الدفع") }
            items(state.methods) { m ->
                if (m == PaymentMethod.CUSTOMER_ACCOUNT) {
                    Choice(selected = m == state.method, onClick = { vm.chooseMethod(m) }, enabled = !walletShort) {
                        Text("الدفع من رصيد المحفظة", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "رصيدك المتاح: " + (walletBalance?.let { Money.plain(it.max(BigDecimal.ZERO), display.currency) } ?: "…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (walletShort) {
                            Text(
                                "الرصيد بـ${display.currency.nameAr} لا يكفي لإجمالي الطلب (${orderTotal?.let { Money.plain(it, display.currency) }})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = topUp) { Text("شحن الرصيد") }
                        } else {
                            Text(
                                "يُخصم إجمالي الطلب من رصيدك فور التأكيد",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Choice(selected = m == state.method, onClick = { vm.chooseMethod(m) }) {
                        Text(m.label, style = MaterialTheme.typography.titleSmall)
                        methodHint(m)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { SectionTitle("ملاحظات (اختياري)") }
            item {
                OutlinedTextField(
                    value = state.note,
                    onValueChange = vm::setNote,
                    placeholder = { Text("مثال: الاتصال قبل الوصول") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            cart?.let { c ->
                item { SectionTitle("ملخص الطلب") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        c.items.forEach { SummaryRow("${it.productName} × ${it.quantity}", Money.format(it.lineTotal)) }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        SummaryRow("المجموع", Money.format(c.totals.subtotal))
                        if (!Money.isZero(c.totals.discountTotal)) SummaryRow("الخصم", "- ${Money.format(c.totals.discountTotal)}", valueColor = Success)
                        SummaryRow("الشحن", if (c.shipping == null) "—" else Money.format(c.totals.shippingFee))
                        c.shippingProblem?.let { Text(ServerMessages.translate(it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

private fun methodHint(m: PaymentMethod): String? = when (m) {
    PaymentMethod.CASH_ON_DELIVERY -> null
    PaymentMethod.CUSTOMER_ACCOUNT -> "يُخصم إجمالي الطلب من رصيدك فور التأكيد"
    PaymentMethod.CARD -> "ادفع بأمان على صفحة الدفع الإلكتروني بعد تأكيد الطلب (فيزا، ماستركارد، مدى). يُخصم المبلغ بالريال السعودي"
    else -> "سيتواصل معك فريقنا بتفاصيل الدفع لتأكيد الطلب"
}

@Composable
private fun Choice(selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .selectable(selected = selected, onClick = onClick, enabled = enabled, role = Role.RadioButton),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f)) { content() }
        }
    }
}
