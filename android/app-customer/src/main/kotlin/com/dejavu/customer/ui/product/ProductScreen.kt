@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.product

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.dejavu.core.ui.theme.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.CartRepository
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.model.StoreVariant
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.Load
import com.dejavu.customer.ui.appViewModel
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.PriceText
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.QuantityStepper
import com.dejavu.core.ui.Tag
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Gold
import com.dejavu.customer.ui.components.BrandChip
import com.dejavu.customer.ui.components.GlassBottomPanel
import com.dejavu.customer.ui.components.goldChipColors
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.glass
import com.dejavu.customer.ui.components.discountBadge
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

data class Selection(val color: String? = null, val size: String? = null, val quantity: Int = 1, val adding: Boolean = false)

class ProductViewModel(
    private val slug: String,
    private val catalog: CatalogRepository,
    private val cart: CartRepository,
    private val session: SessionRepository,
) : BaseViewModel() {
    private val _product = MutableStateFlow<Load<StoreProduct>>(Load.Loading)
    val product = _product.asStateFlow()
    private val _selection = MutableStateFlow(Selection())
    val selection = _selection.asStateFlow()

    /** Fires when the user must sign in first, and after adding to the cart. */
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 2)
    val events = _events.asSharedFlow()

    enum class Event { LoginRequired, Added }

    /** Variants the shopper asked to be told about when they are back in stock. */
    private val _watched = MutableStateFlow<Set<String>>(emptySet())
    val watched = _watched.asStateFlow()
    private val _watching = MutableStateFlow(false)
    val watching = _watching.asStateFlow()

    init {
        load()
        // Also after signing in from this screen.
        viewModelScope.launch {
            session.state.distinctUntilChangedBy { it is SessionState.LoggedIn }.collect { state ->
                if (state is SessionState.LoggedIn) {
                    catalog.stockAlerts().onSuccess { list -> _watched.value = list.map { it.variantId }.toSet() }
                } else {
                    _watched.value = emptySet()
                }
            }
        }
    }

    /** "Tell me when it is back" for the selected out-of-stock variant. */
    fun watchStock() {
        val variant = selectedVariant() ?: return
        if (!session.isLoggedIn) {
            _events.tryEmit(Event.LoginRequired)
            return
        }
        if (_watching.value) return
        _watching.value = true
        viewModelScope.launch {
            catalog.watchStock(variant.id).orSay()?.let { list ->
                _watched.value = list.map { it.variantId }.toSet()
                say("سنُعلمك عند توفره")
            }
            _watching.value = false
        }
    }

    fun unwatchStock() {
        val variant = selectedVariant() ?: return
        if (_watching.value || !session.isLoggedIn) return
        _watching.value = true
        viewModelScope.launch {
            catalog.unwatchStock(variant.id).orSay()?.let { list ->
                _watched.value = list.map { it.variantId }.toSet()
                say("تم إلغاء التنبيه")
            }
            _watching.value = false
        }
    }

    fun load() {
        _product.value = Load.Loading
        viewModelScope.launch {
            val result = catalog.product(slug)
            _product.value = result.toLoad()
            result.getOrNull()?.let { p ->
                // Preselect the first available option so the price shown matches a real variant.
                val first = p.variants.firstOrNull { it.inStock } ?: p.variants.firstOrNull()
                _selection.value = Selection(color = first?.color, size = first?.size)
            }
        }
    }

    fun pickColor(color: String?) {
        val p = (product.value as? Load.Ready)?.value ?: return
        val sizes = p.variants.filter { it.color == color }
        val keepSize = sizes.any { it.size == _selection.value.size && it.inStock }
        _selection.value = _selection.value.copy(
            color = color,
            size = if (keepSize) _selection.value.size else (sizes.firstOrNull { it.inStock } ?: sizes.firstOrNull())?.size,
            quantity = 1,
        )
    }

    fun pickSize(size: String?) {
        _selection.value = _selection.value.copy(size = size, quantity = 1)
    }

    fun setQuantity(q: Int) {
        _selection.value = _selection.value.copy(quantity = q)
    }

    fun addToCart() {
        val variant = selectedVariant() ?: return
        if (!session.isLoggedIn) {
            _events.tryEmit(Event.LoginRequired)
            return
        }
        _selection.value = _selection.value.copy(adding = true)
        viewModelScope.launch {
            cart.add(variant.id, _selection.value.quantity).orSay()?.let { _events.tryEmit(Event.Added) }
            _selection.value = _selection.value.copy(adding = false)
        }
    }

    fun selectedVariant(): StoreVariant? {
        val p = (product.value as? Load.Ready)?.value ?: return null
        val s = _selection.value
        return p.variants.firstOrNull { it.color == s.color && it.size == s.size }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductScreen(
    slug: String,
    onBack: () -> Unit,
    openCart: () -> Unit,
    requireLogin: () -> Unit,
    openBrand: (name: String, slug: String) -> Unit = { _, _ -> },
    openReviews: () -> Unit = {},
    openProduct: (String) -> Unit = {},
    openChat: () -> Unit = {},
) {
    val vm = appViewModel(key = "product:$slug") { ProductViewModel(slug, it.catalog, it.cart, it.session) }
    val reviewsVm = appViewModel(key = "product-reviews:$slug") { ReviewsViewModel(slug, it.catalog, it.session) }
    val state by vm.product.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val watching by vm.watching.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    CollectMessages(reviewsVm, snackbar)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ProductViewModel.Event.LoginRequired -> requireLogin()
                ProductViewModel.Event.Added ->
                    if (snackbar.showSnackbar("تمت الإضافة إلى السلة", actionLabel = "عرض السلة") == SnackbarResult.ActionPerformed) openCart()
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar((state as? Load.Ready)?.value?.nameAr ?: "", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val p = (state as? Load.Ready)?.value
            if (p != null) {
                val variant = vm.selectedVariant()
                GlassBottomPanel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (p.merchantPrice) Text("سعر التاجر", style = MaterialTheme.typography.labelSmall, color = Liquid.GoldDeep)
                            PriceText(variant?.price ?: p.basePrice, variant?.compareAtPrice ?: p.compareAtPrice)
                        }
                        if (variant != null && !variant.inStock) {
                            // Out of stock: offer a back-in-stock notification instead.
                            if (variant.id in watched) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = Liquid.GoldDeep, modifier = Modifier.size(18.dp))
                                        Text("سنُعلمك عند توفره", style = MaterialTheme.typography.labelLarge, color = Liquid.GoldDeep)
                                    }
                                    TextButton(onClick = vm::unwatchStock, enabled = !watching) {
                                        Text("إلغاء التنبيه", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            } else {
                                Button(onClick = vm::watchStock, enabled = !watching) {
                                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("أعلمني عند التوفر")
                                }
                            }
                        } else {
                            Button(
                                onClick = vm::addToCart,
                                enabled = variant?.inStock == true && !selection.adding,
                            ) { Text("أضف إلى السلة") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { p ->
            val variant = vm.selectedVariant()
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                Gallery(p, selection.color)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(p.category.nameAr, style = MaterialTheme.typography.labelLarge, color = Liquid.GoldDeep)
                            Text(p.nameAr, style = MaterialTheme.typography.headlineSmall)
                            p.brand?.let { b -> BrandChip(b, onClick = { openBrand(b.name, b.slug) }) }
                            if (p.merchantPrice) Tag("سعر التاجر", Gold)
                            PriceText(variant?.price ?: p.basePrice, variant?.compareAtPrice ?: p.compareAtPrice)
                            if (p.ratingCount > 0) RatingSummary(p.ratingAvg, p.ratingCount)
                            Promotion(p)
                        }
                    }

                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val colors = p.variants.map { it.color to it.colorHex }.distinctBy { it.first }
                            if (colors.size > 1 || colors.firstOrNull()?.first != null) {
                                Text("اللون: ${selection.color ?: ""}", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    colors.forEach { (name, hex) -> ColorDot(hex, selected = name == selection.color) { vm.pickColor(name) } }
                                }
                            }

                            val sizes = p.variants.filter { it.color == selection.color }
                            if (sizes.any { it.size != null }) {
                                Text("المقاس", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    sizes.forEach { v ->
                                        FilterChip(
                                            selected = v.size == selection.size,
                                            onClick = { vm.pickSize(v.size) },
                                            label = {
                                                Text(
                                                    v.size ?: "-",
                                                    textDecoration = if (v.inStock) null else TextDecoration.LineThrough,
                                                    color = if (v.inStock) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            colors = goldChipColors(),
                                        )
                                    }
                                }
                            }

                            Availability(variant)
                            if (variant?.inStock == true) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("الكمية", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                                    QuantityStepper(selection.quantity, vm::setQuantity, max = minOf(variant.availableQty, 20))
                                }
                            }
                        }
                    }

                    if (!p.descriptionAr.isNullOrBlank()) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("الوصف", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                            Spacer(Modifier.height(8.dp))
                            Text(p.descriptionAr!!, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val specs = listOfNotNull(
                        p.bagSize?.trim()?.takeIf { it.isNotEmpty() }?.let { "مقاس الشنطة: $it سم" },
                        p.heelHeightCm?.trim()?.takeIf { it.isNotEmpty() }?.let { "ارتفاع الكعب: $it سم" },
                    )
                    if (specs.isNotEmpty()) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("المواصفات", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                            Spacer(Modifier.height(8.dp))
                            specs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    ReviewsSection(reviewsVm, openAll = openReviews)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Photos of the chosen [color] first; a new color starts again from its first photo. */
@Composable
private fun Gallery(p: StoreProduct, color: String?) {
    key(color) { ColorGallery(p, color) }
}

@Composable
private fun ColorGallery(p: StoreProduct, color: String?) {
    val images = remember(p, color) { p.imagesFor(color) }.ifEmpty { listOf(null) }
    val pager = rememberPagerState { images.size }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).glass(Liquid.CardShape, elevation = 14.dp)) {
        HorizontalPager(pager, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 5f)) { page ->
            ProductImage(images[page]?.url, Modifier.fillMaxSize(), contentDescription = p.nameAr)
        }
        if (images.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(images.size) { i ->
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) Liquid.Gold else Color.White.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}

/** Running promotion: badge, its name and when it ends. */
@Composable
private fun Promotion(p: StoreProduct) {
    val badge = p.discountBadge() ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tag(badge, Gold)
        p.promotion?.let { promo ->
            Text(promo.name, style = MaterialTheme.typography.labelLarge, color = Liquid.GoldDeep)
            promo.endsAt?.let { Text("حتى ${formatDate(it)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ColorDot(hex: String?, selected: Boolean, onClick: () -> Unit) {
    val color = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.surfaceVariant
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Liquid.Gold else MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(36.dp),
    ) {}
}

@Composable
private fun Availability(variant: StoreVariant?) {
    val (text, color) = when {
        variant == null -> "اختر اللون والمقاس" to MaterialTheme.colorScheme.onSurfaceVariant
        !variant.inStock -> "نفدت الكمية" to MaterialTheme.colorScheme.error
        variant.availableQty <= 3 -> "متبقي ${variant.availableQty} فقط" to Warning
        else -> "متوفر" to Success
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}
