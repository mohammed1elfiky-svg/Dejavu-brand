@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.home

import com.dejavu.core.ui.theme.GlassTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.model.Category
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.Load
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.appViewModel
import com.dejavu.customer.ui.components.CategoryAvatar
import com.dejavu.core.ui.LoadContent
import com.dejavu.customer.ui.components.ProductCard
import com.dejavu.core.ui.SectionTitle
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeData(val categories: List<Category>, val featured: List<StoreProduct>, val newest: List<StoreProduct>)

class HomeViewModel(private val catalog: CatalogRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<HomeData>>(Load.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch {
            val categories = async { catalog.categories() }
            val featured = async { catalog.products(featured = true, pageSize = 10) }
            val newest = async { catalog.products(pageSize = 10) }
            _state.value = runCatching {
                HomeData(categories.await().getOrThrow(), featured.await().getOrThrow().items, newest.await().getOrThrow().items)
            }.toLoad()
        }
    }
}

@Composable
fun HomeScreen(
    openProduct: (String) -> Unit,
    openProducts: (title: String, category: String?, query: String?, featured: Boolean) -> Unit,
    openNotifications: () -> Unit,
) {
    val vm = appViewModel { HomeViewModel(it.catalog) }
    val state by vm.state.collectAsStateWithLifecycle()
    val container = appContainer()
    val loggedIn by container.session.state.collectAsStateWithLifecycle()
    val unread by container.notifications.unread.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GlassTopBar(
                title = { Text("Dejavu", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    if (loggedIn is SessionState.LoggedIn) {
                        IconButton(onClick = openNotifications) {
                            BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                                Icon(Icons.Filled.Notifications, contentDescription = "الإشعارات")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { data ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("ابحث عن فستان، قميص، حقيبة…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) openProducts("نتائج \"$query\"", null, query, false) }),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(data.categories, key = { it.id }) { c ->
                            CategoryAvatar(c.nameAr, c.imageUrl) { openProducts(c.nameAr, c.slug, null, false) }
                        }
                    }
                }
                if (data.featured.isNotEmpty()) {
                    item { SectionTitle("مختارات Dejavu", "عرض الكل") { openProducts("مختارات Dejavu", null, null, true) } }
                    item { ProductRow(data.featured, openProduct) }
                }
                item { SectionTitle("وصل حديثاً", "عرض الكل") { openProducts("وصل حديثاً", null, null, false) } }
                item { ProductRow(data.newest, openProduct) }
            }
        }
    }
}

@Composable
private fun ProductRow(products: List<StoreProduct>, openProduct: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(products, key = { it.id }) { p ->
            Column(Modifier.width(160.dp)) { ProductCard(p, onClick = { openProduct(p.slug) }) }
        }
    }
}
