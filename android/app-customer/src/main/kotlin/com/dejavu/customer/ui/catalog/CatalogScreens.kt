@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.catalog

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.filter
import androidx.paging.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import com.dejavu.core.ui.theme.GlassTopBar
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.glass
import com.dejavu.customer.ui.components.GlassRow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.customer.ui.appContainer
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
import com.dejavu.core.ui.theme.OutlinedTextField
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

class CategoriesViewModel(private val catalog: CatalogRepository, private val cache: com.dejavu.customer.OfflineCache? = null) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<Category>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val listSerializer = kotlinx.serialization.builtins.ListSerializer(Category.serializer())

    init { load() }

    fun load() {
        viewModelScope.launch {
            // The last list shows at once; the fresh one replaces it.
            if (_state.value !is Load.Ready) _state.value = cache?.read("categories", listSerializer)?.let { Load.Ready(it) } ?: Load.Loading
            val fresh = catalog.categories(refresh = true)
            fresh.onSuccess { list -> cache?.write("categories", listSerializer, list) }
            if (fresh.isFailure && _state.value is Load.Ready) say(fresh.exceptionOrNull()!!.userMessage()) else _state.value = fresh.toLoad()
        }
    }
}

@Composable
fun CategoriesScreen(openCategory: (title: String, slug: String) -> Unit) {
    val vm = appViewModel { CategoriesViewModel(it.catalog, it.offline) }
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { GlassTopBar(title = { Text("الأقسام") }) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { categories ->
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                categories.forEach { parent ->
                    item(key = parent.id) { CategoryRow(parent, indent = false) { openCategory(parent.nameAr, parent.slug) } }
                    items(parent.children, key = { it.id }) { child -> CategoryRow(child, indent = true) { openCategory(child.nameAr, child.slug) } }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, indent: Boolean, onClick: () -> Unit) {
    GlassRow(
        title = category.nameAr,
        subtitle = category.nameEn,
        leading = {
            Box(Modifier.size(if (indent) 40.dp else 52.dp).glass(CircleShape, elevation = 4.dp).padding(2.dp)) {
                ProductImage(category.imageUrl, Modifier.fillMaxSize().clip(CircleShape))
            }
        },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Liquid.GoldDeep) },
        onClick = onClick,
        modifier = Modifier.padding(start = if (indent) 24.dp else 0.dp),
    )
}

// ------------------------------------------------------------- product list ---

data class ProductListState(
    val items: List<StoreProduct> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val sort: ProductSort = ProductSort.STORE,
)

/**
 * A product list (category, search, brand…) paged with Paging 3 over Room: saved pages show at
 * once and offline, fresh ones come from the server as the list scrolls.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPagingApi::class)
class ProductListViewModel(
    private val catalog: CatalogRepository,
    private val db: com.dejavu.customer.data.CatalogDb,
    private val category: String?,
    private val featured: Boolean,
    initialQuery: String?,
    private val brand: String? = null,
) : BaseViewModel() {
    private data class Params(val query: String?, val sort: ProductSort)

    private val params = MutableStateFlow(Params(initialQuery, ProductSort.STORE))
    private val _state = MutableStateFlow(ProductListState())
    val state = _state.asStateFlow()
    val query: String? get() = params.value.query

    val products: Flow<PagingData<StoreProduct>> = params.flatMapLatest<Params, PagingData<StoreProduct>> { p ->
        val key = listOf(category, featured, brand, p.query, p.sort).joinToString("|")
        Pager(
            config = PagingConfig(pageSize = 20, prefetchDistance = 8, enablePlaceholders = false),
            remoteMediator = com.dejavu.customer.data.ProductsMediator(key, db) { page ->
                catalog.products(p.query, category, featured.takeIf { it }, p.sort, page = page, brand = brand)
            },
            pagingSourceFactory = { db.dao().products(key) },
        ).flow.map { data: PagingData<com.dejavu.customer.data.CachedProduct> ->
            data.filter { it.product() != null }.map { cached: com.dejavu.customer.data.CachedProduct -> cached.product()!! }
        }
    }.cachedIn(viewModelScope)

    fun search(text: String) {
        params.value = params.value.copy(query = text.ifBlank { null })
    }

    fun sortBy(sort: ProductSort) {
        _state.value = _state.value.copy(sort = sort)
        params.value = params.value.copy(sort = sort)
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
    brand: String? = null,
) {
    val vm = appViewModel(key = "products:$category:$initialQuery:$featured:$brand") {
        ProductListViewModel(it.catalog, it.catalogDb, category, featured, initialQuery, brand)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val products = vm.products.collectAsLazyPagingItems()
    var query by rememberSaveable { mutableStateOf(initialQuery.orEmpty()) }
    var sortMenu by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val grid = rememberLazyGridState()
    val runSearch = { text: String ->
        RecentSearches.add(context, text)
        vm.search(text)
    }


    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar(title, onBack) {
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(sortLabel(state.sort), color = Liquid.GoldDeep) }
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
                    keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused },
                )
            }
            if (searchFocused && query.isBlank()) {
                item(span = { GridItemSpan(2) }) {
                    SearchSuggestions(onPick = { picked ->
                        query = picked
                        runSearch(picked)
                    })
                }
            }
            items(products.itemCount, key = { i -> "$i" }) { i ->
                products[i]?.let { p -> ProductCard(p, onClick = { openProduct(p.slug) }) }
            }
            item(span = { GridItemSpan(2) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    val refresh = products.loadState.refresh
                    val append = products.loadState.append
                    val error = (refresh as? LoadState.Error ?: append as? LoadState.Error)?.error
                    when {
                        refresh is LoadState.Loading && products.itemCount == 0 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            com.dejavu.core.ui.ShimmerCard(Modifier.weight(1f))
                            com.dejavu.core.ui.ShimmerCard(Modifier.weight(1f))
                        }
                        append is LoadState.Loading -> CircularProgressIndicator()
                        error != null && products.itemCount == 0 -> TextButton(onClick = products::retry) { Text("${error.userMessage()}\nإعادة المحاولة") }
                        error != null -> TextButton(onClick = products::retry) { Text("أنت تتصفح بدون اتصال · إعادة المحاولة") }
                        products.itemCount == 0 && refresh is LoadState.NotLoading -> EmptyState(Icons.Filled.Search, "لا توجد منتجات", "جرّب كلمة بحث أخرى")
                    }
                }
            }
        }
    }
}

private fun sortLabel(sort: ProductSort) = when (sort) {
    ProductSort.STORE -> "المقترح"
    ProductSort.NEWEST -> "الأحدث"
    ProductSort.PRICE_ASC -> "السعر: من الأقل"
    ProductSort.PRICE_DESC -> "السعر: من الأعلى"
}

// ------------------------------------------------------------------ search ---

/** The last searches on this phone (newest first, at most 10). */
object RecentSearches {
    private const val PREFS = "recent_searches"
    private const val KEY = "items"
    private const val MAX = 10

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun add(context: Context, query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return
        val list = (listOf(q) + load(context).filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/** What the store's shoppers search for most; fetched once per app run. */
private var popularSearchesCache: List<String>? = null

/** Shown while the search box is empty: "عمليات البحث الأخيرة" and "الأكثر بحثاً". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchSuggestions(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val extras = appContainer().extras
    val recent = remember { mutableStateListOf<String>().apply { addAll(RecentSearches.load(context)) } }
    var popular by remember { mutableStateOf(popularSearchesCache.orEmpty()) }
    LaunchedEffect(Unit) {
        if (popularSearchesCache == null) {
            extras.popularSearches().onSuccess {
                popularSearchesCache = it
                popular = it
            }
        }
    }
    if (recent.isEmpty() && popular.isEmpty()) return
    GlassCard(modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 14.dp) {
        Column {
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("عمليات البحث الأخيرة", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        RecentSearches.clear(context)
                        recent.clear()
                    }) { Text("مسح", color = Liquid.InkSoft) }
                }
                SuggestionChips(recent, onPick)
            }
            if (popular.isNotEmpty()) {
                Text(
                    "الأكثر بحثاً",
                    style = MaterialTheme.typography.titleSmall,
                    color = Liquid.GoldDeep,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = if (recent.isNotEmpty()) 10.dp else 0.dp, bottom = 4.dp),
                )
                SuggestionChips(popular, onPick)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChips(words: List<String>, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        words.forEach { word ->
            SuggestionChip(
                onClick = { onPick(word) },
                label = { Text(word, maxLines = 1) },
                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color.White.copy(alpha = 0.55f), labelColor = Liquid.Ink),
            )
        }
    }
}
