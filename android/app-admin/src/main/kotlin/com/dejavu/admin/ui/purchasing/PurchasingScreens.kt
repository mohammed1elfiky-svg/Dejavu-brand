@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.purchasing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ChipFilter
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.JournalEntriesCard
import com.dejavu.admin.ui.MoneyDialog
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.Lux
import androidx.compose.ui.graphics.Color
import com.dejavu.admin.ui.label
import com.dejavu.admin.ui.tint
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.PurchaseOrderDetail
import com.dejavu.core.admin.PurchaseOrderStatus
import com.dejavu.core.admin.PurchaseOrderSummary
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.Supplier
import com.dejavu.core.model.Money
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.label
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Permission to create purchase orders (not in [Perm]). */
internal const val PURCHASES_WRITE = "purchases.write"

private val SUPPLIER_METHODS = listOf(PaymentMethod.BANK_TRANSFER, PaymentMethod.CASH, PaymentMethod.INSTAPAY, PaymentMethod.WALLET)

class PurchasingViewModel(private val repo: PurchasingRepository) : BaseViewModel() {
    private val _orders = MutableStateFlow<Load<List<PurchaseOrderSummary>>>(Load.Loading)
    val orders = _orders.asStateFlow()
    private val _suppliers = MutableStateFlow<List<Supplier>>(emptyList())
    val suppliers = _suppliers.asStateFlow()
    var status: PurchaseOrderStatus? = null
        private set

    fun filter(s: PurchaseOrderStatus?) {
        status = s
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        if (reset || _orders.value !is Load.Ready) _orders.value = Load.Loading
        viewModelScope.launch { _orders.value = repo.orders(status).map { it.items }.toLoad() }
        viewModelScope.launch { repo.suppliers().getOrNull()?.let { _suppliers.value = it.items } }
    }
}

@Composable
fun PurchasingScreen(onBack: () -> Unit, openOrder: (String) -> Unit, newPurchase: () -> Unit) {
    val vm = adminViewModel { PurchasingViewModel(it.purchasing) }
    val canWrite = adminContainer().session.can(PURCHASES_WRITE)
    val orders by vm.orders.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("المشتريات", onBack) },
        floatingActionButton = {
            if (canWrite) {
                ExtendedFloatingActionButton(
                    onClick = newPurchase,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("أمر شراء جديد") },
                    containerColor = Lux.GoldDeep,
                    contentColor = Color.White,
                )
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (suppliers.isNotEmpty()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        InfoCard {
                            Text("أرصدة الموردين", style = MaterialTheme.typography.titleSmall)
                            suppliers.forEach { SummaryRow("${it.name} (${it.code})", Money.format(it.balance)) }
                        }
                    }
                }
            }
            item { ChipFilter(listOf(null) + PurchaseOrderStatus.entries, vm.status, { it?.label ?: "الكل" }, vm::filter) }
            when (val s = orders) {
                Load.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                is Load.Failed -> item { TextButton(onClick = { vm.load() }) { Text("${s.message} — إعادة المحاولة") } }
                is Load.Ready -> {
                    if (s.value.isEmpty()) item { EmptyState(Icons.AutoMirrored.Filled.List, "لا توجد أوامر شراء") }
                    items(s.value, key = { it.id }) { po ->
                        Card(
                            onClick = { openOrder(po.id) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(po.number, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    StatusChip(po.status.label, po.status.tint())
                                }
                                Text("${po.supplier.name} · ${formatDate(po.orderDate)}", style = MaterialTheme.typography.bodySmall)
                                SummaryRow("الإجمالي / المدفوع", "${Money.format(po.grandTotal)} / ${Money.format(po.amountPaid)}")
                            }
                        }
                    }
                }
            }
        }
    }
}

class PurchaseOrderViewModel(private val id: String, private val repo: PurchasingRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<PurchaseOrderDetail>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.order(id).toLoad() }
    }

    fun receive(quantities: Map<String, Int>) {
        if (quantities.values.none { it > 0 }) return say("حدد الكميات المستلمة")
        act {
            repo.receive(id, quantities).orSay()?.let {
                _state.value = Load.Ready(it)
                say("تم استلام البضاعة وإضافتها للمخزون")
            }
        }
    }

    fun pay(po: PurchaseOrderDetail, amount: String, method: PaymentMethod, reference: String) = act {
        repo.pay(po.supplier.id!!, method, amount, po.id, reference).orSay()?.let {
            say("تم سداد ${Money.format(amount)}")
            _state.value = repo.order(id).toLoad()
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

@Composable
fun PurchaseOrderScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel(key = "po:$id") { PurchaseOrderViewModel(id, it.purchasing) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val receiving = remember { mutableStateMapOf<String, Int>() }
    var paying by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar((state as? Load.Ready)?.value?.number ?: "أمر شراء", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { po ->
            val canReceive = po.canReceive && session.can(Perm.PURCHASES_RECEIVE)
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(po.supplier.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            StatusChip(po.status.label, po.status.tint())
                        }
                        po.supplierInvoiceNo?.let { Text("فاتورة المورد: $it", style = MaterialTheme.typography.bodySmall) }
                        Text(formatDate(po.orderDate), style = MaterialTheme.typography.bodySmall)
                        SummaryRow("البضاعة", Money.format(po.subtotal))
                        SummaryRow("الشحن", Money.format(po.shippingCost))
                        SummaryRow("الإجمالي", Money.format(po.grandTotal), strong = true)
                        SummaryRow("المدفوع", Money.format(po.amountPaid))
                        SummaryRow("المتبقي للمورد", Money.format(po.balanceDue), strong = true)
                    }
                }
                item { Text(if (canReceive) "حدد الكميات التي وصلت الآن" else "البنود", style = MaterialTheme.typography.titleSmall) }
                items(po.items, key = { it.id }) { line ->
                    InfoCard {
                        Text(line.variant.product.nameAr, style = MaterialTheme.typography.titleSmall)
                        Text("${line.variant.sku} · ${Money.format(line.unitCost)} للقطعة", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("مستلم ${line.receivedQty} من ${line.quantity}", Modifier.weight(1f))
                            if (canReceive && line.remaining > 0) {
                                val q = receiving[line.id] ?: 0
                                TextButton(onClick = { receiving[line.id] = (q - 1).coerceAtLeast(0) }, enabled = q > 0) { Text("−") }
                                Text("$q")
                                TextButton(onClick = { receiving[line.id] = (q + 1).coerceAtMost(line.remaining) }, enabled = q < line.remaining) { Text("+") }
                                TextButton(onClick = { receiving[line.id] = line.remaining }) { Text("الكل") }
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canReceive) {
                            Button(onClick = { vm.receive(receiving.toMap()); receiving.clear() }, enabled = !busy && receiving.values.any { it > 0 }) { Text("تأكيد الاستلام") }
                        }
                        if (session.can(Perm.SUPPLIER_PAY) && Money.parse(po.balanceDue).signum() > 0 && po.status != PurchaseOrderStatus.CANCELLED) {
                            OutlinedButton(onClick = { paying = true }, enabled = !busy) { Text("سداد للمورد") }
                        }
                    }
                }
                if (po.payments.isNotEmpty()) {
                    item {
                        InfoCard {
                            Text("المدفوعات", style = MaterialTheme.typography.titleSmall)
                            po.payments.forEach { SummaryRow("${it.number} · ${it.method.label}", Money.format(it.amount)) }
                        }
                    }
                }
                item { JournalEntriesCard(po.journalEntries) }
            }
            if (paying) MoneyDialog("سداد للمورد", SUPPLIER_METHODS, po.balanceDue, "سداد", { paying = false }) { amount, method, ref -> vm.pay(po, amount, method, ref) }
        }
    }
}
