@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.cart

import com.dejavu.core.ui.theme.GlassTopBar
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.GoldBadge
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.customer.ui.components.GlassBottomPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ShoppingCart
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.AddressRepository
import com.dejavu.core.data.CartRepository
import com.dejavu.core.data.ServerMessages
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.model.Cart
import com.dejavu.core.model.CartItem
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.customer.ui.appViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.LoadingBox
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.QuantityStepper
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.theme.Success
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CartViewModel(
    private val cartRepo: CartRepository,
    private val addresses: AddressRepository,
    val session: SessionRepository,
) : BaseViewModel() {
    val cart = cartRepo.cart
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var addressId: String? = null

    /** Re-quotes the cart, using the default address for the shipping estimate. */
    fun refresh() = launchBusy {
        if (addressId == null) {
            addressId = addresses.list().getOrNull()?.let { list -> (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.id }
        }
        cartRepo.refresh(addressId)
    }

    fun setQuantity(item: CartItem, q: Int) = launchBusy { requoted(cartRepo.setQuantity(item.variantId, q)) }
    fun remove(item: CartItem) = launchBusy { requoted(cartRepo.remove(item.variantId)) }
    fun applyCoupon(code: String) = launchBusy { requoted(cartRepo.applyCoupon(code)) }
    fun removeCoupon() = launchBusy { requoted(cartRepo.removeCoupon()) }

    // Item endpoints answer without a shipping estimate; fetch the full quote again.
    private suspend fun requoted(result: Result<Cart>): Result<Cart> =
        if (result.isSuccess && addressId != null) cartRepo.refresh(addressId) else result

    private fun launchBusy(block: suspend () -> Result<*>) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block().orSay()
            _busy.value = false
        }
    }
}

@Composable
fun CartScreen(openProduct: (String) -> Unit, checkout: () -> Unit, login: () -> Unit, shop: () -> Unit) {
    val vm = appViewModel { CartViewModel(it.cart, it.addresses, it.session) }
    val session by vm.session.state.collectAsStateWithLifecycle()
    val cart by vm.cart.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LaunchedEffect(session is SessionState.LoggedIn) { if (session is SessionState.LoggedIn) vm.refresh() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { GlassTopBar(title = { Text("السلة") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val c = cart
            if (c != null && c.items.isNotEmpty()) {
                GlassBottomPanel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("الإجمالي", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        GoldBadge(Money.format(c.totals.grandTotal))
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = checkout, enabled = c.canCheckout && !busy, modifier = Modifier.fillMaxWidth()) { Text("إتمام الطلب") }
                }
            }
        },
    ) { padding ->
        val c = cart
        when {
            session !is SessionState.LoggedIn ->
                EmptyState(Icons.Filled.ShoppingCart, "سجّل الدخول لعرض السلة", "احفظ اختياراتك وأكمل طلبك من أي جهاز", "تسجيل الدخول", login, Modifier.padding(padding))
            c == null -> LoadingBox(Modifier.padding(padding))
            c.items.isEmpty() ->
                EmptyState(Icons.Filled.ShoppingCart, "السلة فارغة", "تصفّح أحدث التشكيلات وأضف ما يعجبك", "تسوّق الآن", shop, Modifier.padding(padding))
            else -> CartContent(c, busy, vm, openProduct, Modifier.padding(padding))
        }
    }
}

@Composable
private fun CartContent(cart: Cart, busy: Boolean, vm: CartViewModel, openProduct: (String) -> Unit, modifier: Modifier) {
    var coupon by rememberSaveable { mutableStateOf("") }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Liquid.Gold, trackColor = Liquid.GoldPale) }
        if (cart.problems.isNotEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 14.dp) {
                    cart.problems.forEach { Text("• ${ServerMessages.translate(it)}", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        items(cart.items, key = { it.variantId }) { item ->
            CartRow(item, busy, onOpen = { openProduct(item.slug) }, onQuantity = { vm.setQuantity(item, it) }, onRemove = { vm.remove(item) })
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    if (cart.couponCode != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("كود الخصم: ${cart.couponCode}", Modifier.weight(1f), color = if (cart.couponProblem == null) Success else MaterialTheme.colorScheme.error)
                            IconButton(onClick = vm::removeCoupon) { Icon(Icons.Filled.Close, contentDescription = "إزالة الكود") }
                        }
                        cart.couponProblem?.let { Text(ServerMessages.translate(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = coupon,
                                onValueChange = { coupon = it.uppercase() },
                                placeholder = { Text("كود الخصم") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { vm.applyCoupon(coupon) }, enabled = coupon.isNotBlank() && !busy) { Text("تطبيق") }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Liquid.Gold.copy(alpha = 0.25f))
                    val t = cart.totals
                    SummaryRow("المجموع", Money.format(t.subtotal))
                    if (!Money.isZero(t.discountTotal)) SummaryRow("الخصم", "- ${Money.format(t.discountTotal)}", valueColor = Success)
                    SummaryRow(
                        "الشحن" + (cart.shipping?.let { " (${it.governorate}، ${it.estimatedDays} أيام)" } ?: ""),
                        if (cart.shipping == null) "يُحدد عند إتمام الطلب" else Money.format(t.shippingFee),
                    )
                    cart.shippingProblem?.let { Text(ServerMessages.translate(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun CartRow(item: CartItem, busy: Boolean, onOpen: () -> Unit, onQuantity: (Int) -> Unit, onRemove: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 10.dp, contentPadding = 12.dp, onClick = onOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductImage(item.imageUrl, Modifier.size(width = 72.dp, height = 96.dp).clip(MaterialTheme.shapes.medium))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.productName, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(item.color, item.size?.let { "مقاس $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(item.lineTotal), style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold)
                item.problem?.let { Text(ServerMessages.translate(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuantityStepper(item.quantity, onQuantity, max = minOf(20, maxOf(item.availableQty, item.quantity)), enabled = !busy)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onRemove, enabled = !busy) { Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = Liquid.InkSoft) }
                }
            }
        }
    }
}
