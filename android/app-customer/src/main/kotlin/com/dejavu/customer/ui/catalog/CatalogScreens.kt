@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.ProductSort
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Category
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.Load
import com.dejavu.customer.ui.appViewModel
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.LoadContent
import com.dejavu.customer.ui.components.ProductCard
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// --------------------------------------------------------------- categories ---

class CategoriesViewModel(private val catalog: CatalogRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<Category>>>(Load.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = catalog.categories(refresh = true).toLoad() }
    }
}

@Composable
fun CategoriesScreen(openCategory: (title: String, slug: String) -> Unit) {
    val vm = appViewModel { CategoriesViewModel(it.catalog) }
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { CenterAlignedTopAppBar(title = { Text("الأقسام") }) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { categories ->
            LazyColumn(Modifier.padding(padding)) {
                categories.forEach { parent ->
                    item(key = parent.id) { CategoryRow(parent, indent = false) { openCategory(parent.nameAr, parent.slug) } }
                    items(parent.children, key = { it.id }) { child -> CategoryRow(child, indent = true) { openCategory(child.nameAr, child.slug) } }
                    item { HorizontalDivider() }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, indent: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(category.nameAr) },
        supportingContent = { Text(category.nameEn) },
        leadingContent = { ProductImage(category.imageUrl, Modifier.size(if (indent) 36.dp else 48.dp).clip(CircleShape)) },
        modifier = Modifier.clickable(onClick = onClick).padding(start = if (indent) 24.dp else 0.dp),
    )
}

// ------------------------------------------------------------- product list ---

data class ProductListState(
    val items: List<StoreProduct> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val sort: ProductSort = ProductSort.NEWEST,
)

class ProductListViewModel(
    private val catalog: CatalogRepository,
    private val category: String?,
    private val featured: Boolean,
    initialQuery: String?,
) : BaseViewModel() {
    private val _state = MutableStateFlow(ProductListState())
    val state = _state.asStateFlow()
    var query: String? = initialQuery
        private set

    init { reload() }

    fun search(text: String) {
        query = text.ifBlank { null }
        reload()
    }

    fun sortBy(sort: ProductSort) {
        _state.value = _state.value.copy(sort = sort)
        reload()
    }

    fun reload() {
        _state.value = ProductListState(sort = _state.value.sort)
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore) return
        _state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            catalog.products(query, category, featured.takeIf { it }, s.sort, page = s.page + 1)
                .onSuccess { page ->
                    _state.value = _state.value.copy(items = _state.value.items + page.items, page = page.page, hasMore = page.hasMore, loading = false)
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.userMessage()) }
        }
    }
}

@Composable
fun ProductListScreen(
    title: String,
    category: String?,
    initialQuery: String?,
    featured: Boolean,
    onBack: () -> Unit,
    openProduct: (String) -> Unit,
) {
    val vm = appViewModel(key = "products:$category:$initialQuery:$featured") { ProductListViewModel(it.catalog, category, featured, initialQuery) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(initialQuery.orEmpty()) }
    var sortMenu by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()

    // Load the next page when the last items scroll into view.
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) vm.loadMore() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar(title, onBack) {
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(sortLabel(state.sort)) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        ProductSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(sortLabel(s)) }, onClick = { sortMenu = false; vm.sortBy(s) })
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = grid,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(2) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("بحث") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(state.items, key = { it.id }) { p -> ProductCard(p, onClick = { openProduct(p.slug) }) }
            item(span = { GridItemSpan(2) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.loading -> CircularProgressIndicator()
                        state.error != null -> TextButton(onClick = vm::loadMore) { Text("${state.error}\nإعادة المحاولة") }
                        state.items.isEmpty() -> EmptyState(Icons.Filled.Search, "لا توجد منتجات", "جرّب كلمة بحث أخرى")
                    }
                }
            }
        }
    }
}

private fun sortLabel(sort: ProductSort) = when (sort) {
    ProductSort.NEWEST -> "الأحدث"
    ProductSort.PRICE_ASC -> "السعر: من الأقل"
    ProductSort.PRICE_DESC -> "السعر: من الأعلى"
}
