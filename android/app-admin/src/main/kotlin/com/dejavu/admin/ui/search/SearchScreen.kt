package com.dejavu.admin.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.R
import com.dejavu.admin.ui.RecentItem
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.barcode.rememberBarcodeScanner
import com.dejavu.admin.ui.label
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.DashboardRepository
import com.dejavu.core.admin.HomeSearch
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.PurchaseOrderStatus
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.SupplierKind
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.ErrorBox
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadingBox
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.label
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Route that opens a recently viewed item, or null for a kind this app no longer knows. */
internal fun recentRoute(item: RecentItem): String? = when (item.kind) {
    "product" -> R.of(R.PRODUCT, item.id)
    "customer" -> R.of(R.CUSTOMER, item.id)
    "supplier" -> R.of(R.SUPPLIER, item.id)
    "order" -> R.of(R.ORDER, item.id)
    "purchase" -> R.of(R.PURCHASE, item.id)
    else -> null
}

internal fun recentIcon(kind: String): ImageVector = when (kind) {
    "product" -> Icons.Filled.Star
    "customer" -> Icons.Filled.Face
    "supplier" -> Icons.Filled.AccountBox
    "purchase" -> Icons.Filled.ShoppingCart
    else -> Icons.AutoMirrored.Filled.List
}

/** Whether [term] is long enough to send: two characters, or a single digit (SKU number). */
private fun searchable(term: String): Boolean = term.length >= 2 || (term.length == 1 && term[0].isDigit())

class SearchViewModel(private val repo: DashboardRepository) : BaseViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    /** Null while the box is empty (or too short): the screen shows recent items then. */
    private val _result = MutableStateFlow<Load<HomeSearch>?>(null)
    val result = _result.asStateFlow()

    /** A newer search is running while older results stay on screen. */
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()

    /** A scanned barcode that matched exactly one product: open it straight away. */
    private val _autoOpen = MutableSharedFlow<HomeSearch.P>(extraBufferCapacity = 1)
    val autoOpen = _autoOpen.asSharedFlow()

    private var job: Job? = null

    fun setQuery(q: String) {
        _query.value = q
        search(q, debounce = true, scanned = false)
    }

    fun scanned(code: String) {
        _query.value = code
        search(code, debounce = false, scanned = true)
    }

    fun retry() {
        search(_query.value, debounce = false, scanned = false)
    }

    private fun search(q: String, debounce: Boolean, scanned: Boolean) {
        job?.cancel()
        val term = q.trim()
        if (!searchable(term)) {
            _result.value = null
            _searching.value = false
            return
        }
        job = viewModelScope.launch {
            if (debounce) delay(300)
            if (_result.value is Load.Ready) _searching.value = true else _result.value = Load.Loading
            val r = repo.search(term).toLoad()
            _result.value = r
            _searching.value = false
            if (scanned && r is Load.Ready) {
                val s = r.value
                if (s.products.size == 1 && s.customers.isEmpty() && s.orders.isEmpty() && s.suppliers.isEmpty() && s.purchaseOrders.isEmpty()) {
                    _autoOpen.tryEmit(s.products.first())
                }
            }
        }
    }
}

/** Search everything from home: products, customers, invoices, suppliers, purchase orders. */
@Composable
fun SearchScreen(session: StaffSession.LoggedIn, onBack: () -> Unit, navigate: (String) -> Unit) {
    val container = adminContainer()
    val vm = adminViewModel { SearchViewModel(it.dashboard) }
    val query by vm.query.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val recents by container.uiPrefs.recents.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    CollectMessages(vm, snackbar)

    val open: (RecentItem?, String) -> Unit = { recent, route ->
        if (recent != null) container.uiPrefs.addRecent(recent)
        keyboard?.hide()
        navigate(route)
    }
    val openProduct: (HomeSearch.P) -> Unit = { p ->
        open(RecentItem("product", p.id, p.nameAr, p.skuNumber?.let { "رقم $it" }), R.of(R.PRODUCT, p.id))
    }
    val scan = rememberBarcodeScanner { vm.scanned(it) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(vm) { vm.autoOpen.collect { openProduct(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("البحث", onBack) {
                if (session.can(Perm.PRODUCTS_READ)) TextButton(onClick = scan) { Text("مسح باركود") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
                placeholder = { Text("منتج، عميل، رقم فاتورة، مورد…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "مسح") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            when (val r = result) {
                null -> Recents(recents, onOpen = { item -> recentRoute(item)?.let { open(item, it) } }, onClear = container.uiPrefs::clearRecents)
                Load.Loading -> LoadingBox()
                is Load.Failed -> ErrorBox(r.message, onRetry = vm::retry)
                is Load.Ready ->
                    if (r.value.isEmpty) {
                        EmptyState(Icons.Filled.Search, "لا توجد نتائج", "جرّب كلمة أخرى أو رقم الفاتورة أو الباركود")
                    } else {
                        Results(r.value, open, openProduct)
                    }
            }
        }
    }
}

@Composable
private fun Recents(recents: List<RecentItem>, onOpen: (RecentItem) -> Unit, onClear: () -> Unit) {
    if (recents.isEmpty()) {
        EmptyState(Icons.Filled.Search, "ابحث في كل شيء", "المنتجات بالاسم أو الرقم أو الباركود، العملاء بالاسم أو الجوال، الفواتير والطلبات بالرقم، الموردون وطلبات الشراء")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("آخر ما فتحته", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("مسح") }
                }
                recents.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = Lux.Hairline)
                    ResultRow(icon = recentIcon(item.kind), title = item.title, subtitle = item.subtitle, onClick = { onOpen(item) })
                }
            }
        }
    }
}

@Composable
private fun Results(s: HomeSearch, open: (RecentItem?, String) -> Unit, openProduct: (HomeSearch.P) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.products.isNotEmpty()) {
            item {
                Section("المنتجات", s.products.size) {
                    s.products.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(color = Lux.Hairline)
                        ResultRow(
                            icon = Icons.Filled.Star,
                            imageUrl = p.imageUrl,
                            title = p.nameAr,
                            subtitle = p.skuNumber?.let { "رقم $it" },
                            trailing = Money.format(p.basePrice),
                            onClick = { openProduct(p) },
                        )
                    }
                }
            }
        }
        if (s.customers.isNotEmpty()) {
            item {
                Section("العملاء", s.customers.size) {
                    s.customers.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(color = Lux.Hairline)
                        val sub = listOfNotNull(c.phone, c.code, if (c.isMerchant) "تاجر" else null).joinToString(" · ").ifBlank { null }
                        ResultRow(
                            icon = Icons.Filled.Face,
                            title = c.name,
                            subtitle = sub,
                            onClick = { open(RecentItem("customer", c.id, c.name, sub), R.of(R.CUSTOMER, c.id)) },
                        )
                    }
                }
            }
        }
        if (s.orders.isNotEmpty()) {
            item {
                Section("الفواتير والطلبات", s.orders.size) {
                    s.orders.forEachIndexed { i, o ->
                        if (i > 0) HorizontalDivider(color = Lux.Hairline)
                        val title = "${if (o.channel == "POS") "فاتورة" else "طلب"} ${o.number}"
                        val status = OrderStatus.entries.firstOrNull { it.name == o.status }?.label ?: o.status
                        val sub = listOfNotNull(o.customer?.name, status, formatDate(o.placedAt).ifBlank { null }).joinToString(" · ")
                        ResultRow(
                            icon = Icons.AutoMirrored.Filled.List,
                            title = title,
                            subtitle = sub,
                            trailing = Money.plain(Money.parse(o.grandTotalInCurrency), Currency.of(o.currency)),
                            onClick = { open(RecentItem("order", o.id, title, o.customer?.name), R.of(R.ORDER, o.id)) },
                        )
                    }
                }
            }
        }
        if (s.suppliers.isNotEmpty()) {
            item {
                Section("الموردون", s.suppliers.size) {
                    s.suppliers.forEachIndexed { i, x ->
                        if (i > 0) HorizontalDivider(color = Lux.Hairline)
                        val kind = if (x.kind == SupplierKind.SHIPPING_COMPANY) "شركة شحن" else "مورد"
                        val sub = listOfNotNull(x.code, kind).joinToString(" · ")
                        ResultRow(
                            icon = Icons.Filled.AccountBox,
                            title = x.name,
                            subtitle = sub,
                            onClick = { open(RecentItem("supplier", x.id, x.name, sub), R.of(R.SUPPLIER, x.id)) },
                        )
                    }
                }
            }
        }
        if (s.purchaseOrders.isNotEmpty()) {
            item {
                Section("طلبات الشراء", s.purchaseOrders.size) {
                    s.purchaseOrders.forEachIndexed { i, po ->
                        if (i > 0) HorizontalDivider(color = Lux.Hairline)
                        val status = PurchaseOrderStatus.entries.firstOrNull { it.name == po.status }?.label ?: po.status
                        val sub = listOfNotNull(po.supplier?.name, if (po.kind == "IMPORT") "استيراد" else null, status).joinToString(" · ")
                        ResultRow(
                            icon = Icons.Filled.ShoppingCart,
                            title = "طلب شراء ${po.number}",
                            subtitle = sub,
                            onClick = { open(null, R.of(R.PURCHASE, po.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, count: Int, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("$count", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
        }
        content()
    }
}

@Composable
private fun ResultRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    imageUrl: String? = null,
    trailing: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (imageUrl != null) {
            ProductImage(imageUrl, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Icon(icon, contentDescription = null, tint = Lux.GoldDeep, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleSmall)
    }
}
