package com.dejavu.customer.ui.wishlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.StoreExtrasRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.toLoad
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.appViewModel
import com.dejavu.customer.ui.components.ProductCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WishlistViewModel(private val extras: StoreExtrasRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<StoreProduct>>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = extras.wishlist().toLoad() }
    }
}

/** The customer's wish list (المفضلة). */
@Composable
fun WishlistScreen(onBack: () -> Unit, openProduct: (String) -> Unit, login: () -> Unit) {
    val vm = appViewModel { WishlistViewModel(it.extras) }
    val container = appContainer()
    val session by container.session.state.collectAsStateWithLifecycle()
    val ids by container.extras.wishlistIds.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val loggedIn = session is SessionState.LoggedIn
    LaunchedEffect(loggedIn) { if (loggedIn) vm.load() }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("المفضلة", onBack) }) { padding ->
        if (!loggedIn) {
            EmptyState(
                Icons.Filled.FavoriteBorder,
                "سجّل الدخول لعرض المفضلة",
                "احفظ القطع التي تعجبك وارجعي إليها في أي وقت",
                "تسجيل الدخول",
                login,
                Modifier.padding(padding),
            )
        } else {
            LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { all ->
                // Hearts removed here disappear at once.
                val products = all.filter { it.id in ids }
                if (products.isEmpty()) {
                    EmptyState(
                        Icons.Filled.FavoriteBorder,
                        "المفضلة فارغة",
                        "اضغطي على القلب في أي منتج لحفظه هنا",
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    ) {
                        items(products, key = { it.id }) { p -> ProductCard(p, onClick = { openProduct(p.slug) }) }
                    }
                }
            }
        }
    }
}
