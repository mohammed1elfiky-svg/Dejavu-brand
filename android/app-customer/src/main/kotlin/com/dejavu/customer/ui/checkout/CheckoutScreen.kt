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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.AddressRepository
import com.dejavu.core.data.CartRepository
import com.dejavu.core.data.OrderRepository
import com.dejavu.core.data.ServerMessages
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Address
import com.dejavu.core.model.Money
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.network.ApiException
import com.dejavu.customer.ui.BaseViewModel
import com.dejavu.customer.ui.appViewModel
import com.dejavu.customer.ui.components.BackTopBar
import com.dejavu.customer.ui.components.CollectMessages
import com.dejavu.customer.ui.components.SectionTitle
import com.dejavu.customer.ui.components.SummaryRow
import com.dejavu.customer.ui.label
import com.dejavu.customer.ui.theme.Success
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Payment methods offered at checkout. Card payments wait for the payment-gateway integration. */
val CHECKOUT_METHODS = listOf(PaymentMethod.CASH_ON_DELIVERY, PaymentMethod.INSTAPAY, PaymentMethod.WALLET, PaymentMethod.BANK_TRANSFER)

data class CheckoutState(
    val addresses: List<Address>? = null,
    val addressId: String? = null,
    val method: PaymentMethod = PaymentMethod.CASH_ON_DELIVERY,
    val note: String = "",
    val placing: Boolean = false,
    val problems: List<String> = emptyList(),
)

class CheckoutViewModel(
    private val addressRepo: AddressRepository,
    private val cartRepo: CartRepository,
    private val orders: OrderRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow(CheckoutState())
    val state = _state.asStateFlow()
    val cart = cartRepo.cart
    private val _placed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val placed = _placed.asSharedFlow()

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
            orders.checkout(addressId, s.method, s.note)
                .onSuccess { _placed.tryEmit(it.number) }
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
fun CheckoutScreen(onBack: () -> Unit, addAddress: () -> Unit, placed: (String) -> Unit) {
    val vm = appViewModel { CheckoutViewModel(it.addresses, it.cart, it.orders) }
    val state by vm.state.collectAsStateWithLifecycle()
    val cart by vm.cart.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.placed.collect { placed(it) } }
    // Reload when coming back from "add address".
    LifecycleResumeEffect(vm) {
        vm.loadAddresses()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("إتمام الطلب", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.padding(16.dp)) {
                    cart?.let { SummaryRow("الإجمالي", Money.format(it.totals.grandTotal), strong = true) }
                    Button(
                        onClick = vm::place,
                        enabled = !state.placing && state.addressId != null && cart?.canCheckout == true,
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
            items(CHECKOUT_METHODS) { m ->
                Choice(selected = m == state.method, onClick = { vm.chooseMethod(m) }) {
                    Text(m.label, style = MaterialTheme.typography.titleSmall)
                    if (m != PaymentMethod.CASH_ON_DELIVERY) {
                        Text("سيتواصل معك فريقنا بتفاصيل الدفع لتأكيد الطلب", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun Choice(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f)) { content() }
        }
    }
}
