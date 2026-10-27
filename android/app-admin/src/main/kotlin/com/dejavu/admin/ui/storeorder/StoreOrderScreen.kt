package com.dejavu.admin.ui.storeorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.StoreOrderItem
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The products placed by hand at the top of the customer app, and a search to add more. */
class StoreOrderViewModel(private val finance: FinanceRepository, private val inventory: InventoryRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<StoreOrderItem>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _dirty = MutableStateFlow(false)
    val dirty = _dirty.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _results = MutableStateFlow<List<StoreOrderItem>>(emptyList())
    val results = _results.asStateFlow()
    private var searchJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = finance.storeOrder().toLoad()
            _dirty.value = false
        }
    }

    private fun edit(change: (MutableList<StoreOrderItem>) -> Unit) {
        val list = (_state.value as? Load.Ready)?.value?.toMutableList() ?: return
        change(list)
        _state.value = Load.Ready(list)
        _dirty.value = true
    }

    fun move(index: Int, by: Int) = edit { l ->
        val to = index + by
        if (to in l.indices) l.add(to, l.removeAt(index))
    }

    fun remove(id: String) = edit { l -> l.removeAll { it.id == id } }

    fun add(item: StoreOrderItem) = edit { l -> if (l.none { it.id == item.id }) l.add(item) }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            inventory.products(query, pageSize = 15).onSuccess { page ->
                _results.value = page.items.map { StoreOrderItem(it.id, it.nameAr, it.slug, it.status.name, it.images.firstOrNull()?.url) }
            }
        }
    }

    fun save() {
        val list = (_state.value as? Load.Ready)?.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            finance.setStoreOrder(list.map { it.id }).fold(
                {
                    _state.value = Load.Ready(it)
                    _dirty.value = false
                    say("تم حفظ الترتيب، سيظهر للعملاء بهذا الترتيب")
                },
                { e -> say(e.userMessage()) },
            )
            _busy.value = false
        }
    }
}

/** Screen under "المزيد ← ترتيب المنتجات في التطبيق". */
@Composable
fun StoreOrderScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "store-order") { StoreOrderViewModel(it.finance, it.inventory) }
    val state by vm.state.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { vm.search(query) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("ترتيب المنتجات في التطبيق", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (dirty) {
                Button(onClick = vm::save, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("حفظ الترتيب") }
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { placed ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "المنتجات هنا تظهر أولاً في تطبيق العملاء بنفس الترتيب، وبعدها باقي المنتجات من الأحدث للأقدم.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (placed.isEmpty()) {
                    item { Text("لم ترتّب أي منتج بعد. ابحث بالأسفل وأضف المنتجات التي تريدها في الأعلى.", style = MaterialTheme.typography.bodyMedium) }
                }
                itemsIndexed(placed, key = { _, p -> p.id }) { i, p ->
                    OrderRow(p, position = i + 1) {
                        IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "أعلى") }
                        IconButton(onClick = { vm.move(i, 1) }, enabled = i < placed.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "أسفل") }
                        IconButton(onClick = { vm.remove(p.id) }) { Icon(Icons.Filled.Close, "إزالة") }
                    }
                }
                item {
                    Text("إضافة منتج", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("ابحث باسم المنتج أو رقمه") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val ids = placed.map { it.id }.toSet()
                items(results.filter { it.id !in ids }, key = { "r-" + it.id }) { p ->
                    OrderRow(p, position = null) {
                        IconButton(onClick = { vm.add(p) }) { Icon(Icons.Filled.Add, "إضافة") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(p: StoreOrderItem, position: Int?, actions: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (position != null) Text("$position", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
            ProductImage(p.imageUrl, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f)) {
                Text(p.nameAr, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p.status != "ACTIVE") Text("غير منشور", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            actions()
        }
    }
}
