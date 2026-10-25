@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.purchasing

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dejavu.core.admin.PurchaseKind
import com.dejavu.core.admin.SupplierAccount
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
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
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import androidx.compose.foundation.layout.height
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
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.label
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Permission to create purchase orders (not in [Perm]). */
internal const val PURCHASES_WRITE = "purchases.write"

private val SUPPLIER_METHODS = listOf(PaymentMethod.BANK_TRANSFER, PaymentMethod.CASH, PaymentMethod.INSTAPAY, PaymentMethod.WALLET)

/** An amount in the order's own (supplier) currency, e.g. "1,200 USD". */
private fun inForeign(amount: java.math.BigDecimal, code: String): String = Money.plain(amount, Currency.of(code))

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

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) {
        val s = status
        repo.suppliers().getOrNull()?.let { _suppliers.value = it.items }
        val res = repo.orders(s).map { it.items }.toLoad()
        if (s == status) _orders.value = res
    }
}

@Composable
fun PurchasingScreen(onBack: () -> Unit, openOrder: (String) -> Unit, newPurchase: () -> Unit, openSuppliers: () -> Unit = {}, newImport: (() -> Unit)? = null) {
    val vm = adminViewModel { PurchasingViewModel(it.purchasing) }
    val canWrite = adminContainer().session.can(PURCHASES_WRITE)
    val orders by vm.orders.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
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
        RefreshList(refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = openSuppliers, modifier = Modifier.weight(1f)) { Text("الموردون والبضائع المعلقة") }
                    if (canWrite && newImport != null) OutlinedButton(onClick = newImport, modifier = Modifier.weight(1f)) { Text("طلبية استيراد") }
                }
            }
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
                    if (s.value.isEmpty()) {
                        item {
                            EmptyState(
                                Icons.AutoMirrored.Filled.List,
                                "لا توجد أوامر شراء",
                                if (vm.status != null) "لا أوامر بهذه الحالة" else "سجّل مشترياتك من الموردين لتدخل المخزون وتُقيَّد في الحسابات",
                                action = when {
                                    vm.status != null -> "عرض الكل"
                                    canWrite -> "أمر شراء جديد"
                                    else -> null
                                },
                                onAction = { if (vm.status != null) vm.filter(null) else newPurchase() },
                                modifier = Modifier.height(320.dp),
                            )
                        }
                    }
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
                                Text("${po.supplier.name} · ${formatDate(po.orderDate)}${if (po.kind == PurchaseKind.IMPORT) " · استيراد" else ""}", style = MaterialTheme.typography.bodySmall)
                                SummaryRow("الإجمالي / المدفوع", "${Money.format(po.grandTotal)} / ${Money.format(po.amountPaid)}")
                            }
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
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires once the order was deleted. */
    val deleted = _deleted.asSharedFlow()
    private val _invoiceImage = MutableStateFlow<ByteArray?>(null)

    /** The supplier's invoice photo, once loaded. */
    val invoiceImage = _invoiceImage.asStateFlow()
    private val _invoiceImageFailed = MutableStateFlow(false)

    /** The invoice photo could not be loaded. */
    val invoiceImageFailed = _invoiceImageFailed.asStateFlow()
    private val _shippingCompany = MutableStateFlow<String?>(null)

    /** Name of the order's shipping company. */
    val shippingCompany = _shippingCompany.asStateFlow()
    private var shownImage: String? = null
    private var shownCompany: String? = null

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            val r = repo.order(id)
            _state.value = r.toLoad()
            r.getOrNull()?.let(::loadExtras)
        }
    }

    /** The invoice photo and the shipping company's name (each fetched once per value). */
    private fun loadExtras(po: PurchaseOrderDetail) {
        val image = po.invoiceImageUrl?.ifBlank { null }
        if (image != shownImage) {
            shownImage = image
            _invoiceImage.value = null
            _invoiceImageFailed.value = false
            if (image != null) {
                viewModelScope.launch {
                    val bytes = repo.privateFileBytes(image)
                    if (shownImage == image) {
                        _invoiceImage.value = bytes
                        _invoiceImageFailed.value = bytes == null
                    }
                }
            }
        }
        val company = po.shippingCompanyId?.ifBlank { null }
        if (company != shownCompany) {
            shownCompany = company
            _shippingCompany.value = null
            if (company != null) viewModelScope.launch { repo.supplier(company).getOrNull()?.let { if (shownCompany == company) _shippingCompany.value = it.name } }
        }
    }

    /** The supplier's account balances (per currency, + = owed to the supplier) for the order PDF. */
    suspend fun supplierBalances(supplierId: String): List<SupplierAccount.Balance> = repo.supplierAccount(supplierId).orSay()?.balances.orEmpty()

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

    /** Deletes an order nothing was received for (it leaves the supplier account). */
    fun delete() = act {
        repo.cancelOrder(id).orSay()?.let {
            say("تم حذف الطلبية ${it.number}")
            _deleted.tryEmit(Unit)
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
fun PurchaseOrderScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit, openImportReceive: (String) -> Unit = {}, editImport: (String) -> Unit = {}) {
    val vm = adminViewModel(key = "po:$id") { PurchaseOrderViewModel(id, it.purchasing) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val receiving = remember { mutableStateMapOf<String, Int>() }
    var paying by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportingInvoice by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val invoiceImage by vm.invoiceImage.collectAsStateWithLifecycle()
    val invoiceImageFailed by vm.invoiceImageFailed.collectAsStateWithLifecycle()
    val shippingCompany by vm.shippingCompany.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.deleted.collect { onBack() } }
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar((state as? Load.Ready)?.value?.number ?: "أمر شراء", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { po ->
            val canReceive = po.canReceive && session.can(Perm.PURCHASES_RECEIVE) && !po.isImport
            val nothingReceived = po.items.all { it.receivedQty == 0 }
            val canWrite = session.can(PURCHASES_WRITE)
            // Import orders can be changed at any time (after arrival: prices, notes, inland shipping and the invoice only).
            val canEdit = canWrite && po.importEditable()
            val canDelete = canWrite && nothingReceived && (po.status == PurchaseOrderStatus.DRAFT || po.status == PurchaseOrderStatus.ORDERED)
            // Prices typed in the supplier's currency (USD / CNY); SAR values come second.
            val fx = po.foreignCurrency?.ifBlank { null }
            val fxGoods = if (fx != null && po.items.all { it.unitPriceForeign != null }) {
                po.items.fold(java.math.BigDecimal.ZERO) { acc, l -> acc.add(Money.parse(l.unitPriceForeign).multiply(java.math.BigDecimal(l.quantity))) }
            } else {
                null
            }
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(po.supplier.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            StatusChip(po.status.label, po.status.tint())
                        }
                        po.supplierInvoiceNo?.ifBlank { null }?.let { Text("فاتورة المورد: $it", style = MaterialTheme.typography.bodySmall) }
                        Text(formatDate(po.orderDate), style = MaterialTheme.typography.bodySmall)
                        if (po.invoiceImageUrl?.isNotBlank() == true) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                val bitmap = remember(invoiceImage) { invoiceImage?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                                Box(
                                    Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Lux.Champagne).clickable(enabled = bitmap != null) { viewingImage = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (bitmap != null) {
                                        Image(bitmap, contentDescription = "صورة فاتورة المورد", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    } else if (!invoiceImageFailed) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                }
                                Text(if (invoiceImageFailed) "تعذّر تحميل صورة فاتورة المورد" else "صورة فاتورة المورد (اضغط للتكبير)", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            }
                        }
                        if (fx != null && !po.isImport) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CurrencyArt(fx, height = 18.dp)
                                Text("فاتورة بعملة $fx", style = MaterialTheme.typography.labelLarge, color = Lux.GoldDeep)
                            }
                            fxGoods?.let { SummaryRow("البضاعة ($fx)", inForeign(it, fx), strong = true, valueColor = Lux.GoldDeep) }
                            if (fx != "USD") po.foreignPerUsd?.let { SummaryRow("سعر الدولار", "$it $fx") }
                            po.sarPerUsd?.let { SummaryRow("سعر الدولار بالريال", "$it ر.س") }
                        }
                        SummaryRow(if (fx != null) "البضاعة (ر.س)" else "البضاعة", Money.format(po.subtotal))
                        SummaryRow("الشحن", Money.format(po.shippingCost))
                        SummaryRow("الإجمالي", Money.format(po.grandTotal), strong = true)
                        SummaryRow("المدفوع", Money.format(po.amountPaid))
                        SummaryRow("المتبقي للمورد", Money.format(po.balanceDue), strong = true)
                    }
                }
                if (po.isImport) {
                    item {
                        InfoCard {
                            Text("طلبية استيراد · ${po.foreignCurrency ?: ""}", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                            po.foreignPerUsd?.let { SummaryRow("سعر الدولار", "$it ${po.foreignCurrency ?: ""} · ${po.sarPerUsd ?: "—"} ر.س") }
                            if (!Money.isZero(po.commissionPercent)) SummaryRow("عمولة المورد", "${po.commissionPercent}%")
                            if (!Money.isZero(po.domesticShipping)) SummaryRow("الشحن الداخلي", "${po.domesticShipping} ${po.foreignCurrency ?: ""}")
                            if (po.shippingCompanyId != null) SummaryRow("شركة الشحن", shippingCompany ?: "…")
                            po.totalCbm?.let { SummaryRow("الحجم / سعر CBM", "$it م³ · ${po.cbmRateUsd ?: "—"} \$") }
                            if (!Money.isZero(po.freightCost)) SummaryRow("الشحن الخارجي والجمارك", Money.format(po.freightCost))
                            if (po.canReceive && session.can(Perm.PURCHASES_RECEIVE)) {
                                Button(onClick = { openImportReceive(po.id) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("استلام ومطابقة الشحنة") }
                            } else if (po.canReceive) {
                                Text("البضاعة لدى المورد بانتظار الشحن", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            }
                        }
                    }
                }
                if (canEdit || canDelete) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canEdit) OutlinedButton(onClick = { editImport(po.id) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("تعديل الطلبية") }
                            if (canDelete) {
                                OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    Text("حذف الطلبية", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        enabled = !exporting,
                        onClick = {
                            exporting = true
                            scope.launch {
                                runCatching {
                                    val rows = po.items.map {
                                        val option = listOfNotNull(it.option.ifBlank { null }, it.note?.ifBlank { null }).joinToString(" · ")
                                        ChecklistRow(it.variant.product.nameAr, option, it.variant.sku, it.quantity, it.variant.product.images.firstOrNull()?.url)
                                    }
                                    sharePdf(context, checklistPdf(context, "قائمة مطابقة ${po.number}", "${po.supplier.name} · ${formatDate(po.orderDate)}", rows), "مطابقة ${po.number}")
                                }
                                exporting = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (exporting) "جارٍ تجهيز الملف…" else "ملف PDF للمطابقة (مع الصور)") }
                }
                if (po.isImport) {
                    item {
                        OutlinedButton(
                            enabled = !exportingInvoice,
                            onClick = {
                                exportingInvoice = true
                                scope.launch {
                                    runCatching {
                                        val balances = po.supplier.id?.let { vm.supplierBalances(it) }.orEmpty()
                                        sharePdf(context, orderPdf(context, po, balances), "فاتورة ${po.number}")
                                    }
                                    exportingInvoice = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (exportingInvoice) "جارٍ تجهيز الفاتورة…" else "فاتورة PDF (${po.foreignCurrency ?: "CNY"} مع رصيد الحساب)") }
                    }
                }
                item { Text(if (canReceive) "حدد الكميات التي وصلت الآن" else "البنود", style = MaterialTheme.typography.titleSmall) }
                items(po.items, key = { it.id }) { line ->
                    InfoCard {
                        Text(line.variant.product.nameAr, style = MaterialTheme.typography.titleSmall)
                        line.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.GoldDeep) }
                        val foreignUnit = line.unitPriceForeign
                        if (fx != null && foreignUnit != null) {
                            val unit = Money.parse(foreignUnit)
                            Text(
                                "${line.option.ifBlank { "" }}${if (line.option.isNotBlank()) " · " else ""}${line.variant.sku} · ${inForeign(unit, fx)} للقطعة · الإجمالي ${inForeign(unit.multiply(java.math.BigDecimal(line.quantity)), fx)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("بالريال: ${Money.format(line.unitCost)} للقطعة · ${Money.format(line.lineTotal)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        } else {
                            Text("${line.option.ifBlank { "" }}${if (line.option.isNotBlank()) " · " else ""}${line.variant.sku} · ${Money.format(line.unitCost)} للقطعة", style = MaterialTheme.typography.bodySmall)
                            line.unitPriceForeign?.let { Text("سعر المورد: $it ${po.foreignCurrency ?: ""}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                        }
                        line.landedUnitCost?.let { Text("التكلفة الفعلية للقطعة: ${Money.format(it)}", style = MaterialTheme.typography.bodySmall, color = Lux.GoldDeep) }
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
                        if (fx == null && session.can(Perm.SUPPLIER_PAY) && Money.parse(po.balanceDue).signum() > 0 && po.status != PurchaseOrderStatus.CANCELLED) {
                            OutlinedButton(onClick = { paying = true }, enabled = !busy) { Text("سداد للمورد") }
                        }
                    }
                }
                if (fx != null && Money.parse(po.balanceDue).signum() > 0 && po.status != PurchaseOrderStatus.CANCELLED) {
                    item {
                        Text("ادفع للمورد من كشف حساب المورد (سند صرف بالدولار)", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
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
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("حذف الطلبية ${po.number}") },
                    text = {
                        Text(
                            "ستُحذف الطلبية وتخرج من حساب المورد" +
                                (if (po.items.any { it.createdProduct }) "، وتُؤرشف الأصناف الجديدة التي أُنشئت لها" else "") +
                                ". لا يمكن التراجع.",
                        )
                    },
                    confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("تراجع") } },
                )
            }
            if (viewingImage) {
                invoiceImage?.let { bytes ->
                    Dialog(onDismissRequest = { viewingImage = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        val full = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                        Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewingImage = false }, contentAlignment = Alignment.Center) {
                            if (full != null) Image(full, contentDescription = "صورة فاتورة المورد", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            TextButton(onClick = { viewingImage = false }, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { Text("إغلاق", color = Color.White) }
                        }
                    }
                }
            }
            if (paying && fx == null) MoneyDialog("سداد للمورد", SUPPLIER_METHODS, po.balanceDue, "سداد", { paying = false }) { amount, method, ref -> vm.pay(po, amount, method, ref) }
        }
    }
}
