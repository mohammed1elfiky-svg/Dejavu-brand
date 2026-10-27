@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.product

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dejavu.core.data.StoreExtrasRepository
import com.dejavu.core.model.Money
import com.dejavu.core.model.RelatedProducts
import com.dejavu.core.network.normalizeBaseUrl
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.customer.Analytics
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.components.ProductStrip
import com.dejavu.customer.ui.components.PromoCountdown
import com.dejavu.customer.ui.components.fewLeftText
import com.dejavu.customer.ui.components.rememberWishlistToggle
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.delay
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
import com.dejavu.core.model.PreOrder
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
import com.dejavu.core.ui.theme.GlassIconButton
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
    private val extras: StoreExtrasRepository,
) : BaseViewModel() {
    /** "يناسبه أيضاً" and "اشترت العميلات أيضاً". */
    private val _related = MutableStateFlow(RelatedProducts())
    val related = _related.asStateFlow()

    /** The other products of this product's offers (sets and "buy X get Y"). */
    private val _offerProducts = MutableStateFlow<List<StoreProduct>>(emptyList())
    val offerProducts = _offerProducts.asStateFlow()

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
                extras.related(slug).onSuccess { _related.value = it }
                val others = p.deals.flatMap { it.productIds }.filter { it != p.id }.distinct()
                if (others.isNotEmpty()) extras.productsByIds(others).onSuccess { _offerProducts.value = it }
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

    /** Reserving pieces of the next shipment, and the pre-order just made (for the success dialog). */
    private val _preOrdering = MutableStateFlow(false)
    val preOrdering = _preOrdering.asStateFlow()
    private val _preOrdered = MutableStateFlow<PreOrder?>(null)
    val preOrdered = _preOrdered.asStateFlow()

    /** Signed out: asks to sign in and returns false. */
    fun canPreOrder(): Boolean {
        if (session.isLoggedIn) return true
        _events.tryEmit(Event.LoginRequired)
        return false
    }

    fun preOrder(variantId: String, quantity: Int, done: () -> Unit) {
        if (!canPreOrder() || _preOrdering.value) return
        _preOrdering.value = true
        viewModelScope.launch {
            extras.preOrder(variantId, quantity).orSay()?.let {
                _preOrdered.value = it
                done()
                // The open quantity of the shipment went down.
                catalog.product(slug).onSuccess { p -> _product.value = Load.Ready(p) }
            }
            _preOrdering.value = false
        }
    }

    fun clearPreOrdered() {
        _preOrdered.value = null
    }

    /** One piece of each product of a set offer. */
    fun addSet(variantIds: List<String>) {
        if (!session.isLoggedIn) {
            _events.tryEmit(Event.LoginRequired)
            return
        }
        if (_selection.value.adding) return
        _selection.value = _selection.value.copy(adding = true)
        viewModelScope.launch {
            var ok = true
            for (id in variantIds) {
                if (cart.add(id, 1).orSay() == null) {
                    ok = false
                    break
                }
            }
            if (ok) _events.tryEmit(Event.Added)
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
    val vm = appViewModel(key = "product:$slug") { ProductViewModel(slug, it.catalog, it.cart, it.session, it.extras) }
    val reviewsVm = appViewModel(key = "product-reviews:$slug") { ReviewsViewModel(slug, it.catalog, it.session) }
    val state by vm.product.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val watching by vm.watching.collectAsStateWithLifecycle()
    val related by vm.related.collectAsStateWithLifecycle()
    val offerProducts by vm.offerProducts.collectAsStateWithLifecycle()
    val preOrdering by vm.preOrdering.collectAsStateWithLifecycle()
    val preOrdered by vm.preOrdered.collectAsStateWithLifecycle()
    var preOrderFor by remember { mutableStateOf<StoreVariant?>(null) }
    val container = appContainer()
    val config by container.extras.config.collectAsStateWithLifecycle()
    val wishlistIds by container.extras.wishlistIds.collectAsStateWithLifecycle()
    val toggleWishlist = rememberWishlistToggle(onLoginRequired = requireLogin)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    CollectMessages(reviewsVm, snackbar)

    // Add-to-cart flow: the picture flies to the floating cart, the cart bumps, a card confirms.
    val cart by container.cart.cart.collectAsStateWithLifecycle()
    var flight by remember { mutableStateOf(0) }
    var bump by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var pictureBounds by remember { mutableStateOf<Rect?>(null) }
    var cartBounds by remember { mutableStateOf<Rect?>(null) }
    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ProductViewModel.Event.LoginRequired -> requireLogin()
                ProductViewModel.Event.Added -> {
                    confirm = false
                    flight += 1
                }
            }
        }
    }
    LaunchedEffect(confirm) {
        if (confirm) {
            delay(4000)
            confirm = false
        }
    }

    // Time on the page (for the owner's report) and the full-screen ad counter, once loaded.
    val loadedId = (state as? Load.Ready)?.value?.id
    if (loadedId != null) {
        DisposableEffect(loadedId) {
            Analytics.productOpened(loadedId)
            onDispose { Analytics.productClosed(loadedId) }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            val loaded = (state as? Load.Ready)?.value
            BackTopBar(loaded?.nameAr ?: "", onBack) {
                if (loaded != null) {
                    GlassIconButton(onClick = {
                        val price = vm.selectedVariant()?.price ?: loaded.basePrice
                        shareProduct(context, loaded, price, container.settings.baseUrl)
                    }) { Icon(Icons.Filled.Share, contentDescription = "مشاركة") }
                    val saved = loaded.id in wishlistIds
                    GlassIconButton(onClick = { toggleWishlist(loaded) }) {
                        Icon(
                            if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (saved) "إزالة من المفضلة" else "أضف إلى المفضلة",
                            tint = if (saved) Liquid.Danger else Liquid.GoldDeep,
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val p = (state as? Load.Ready)?.value
            if (p != null) Column {
                val variant = vm.selectedVariant()
                FloatingOptionsSheet(
                    product = p,
                    selectedColor = selection.color,
                    selectedSize = selection.size,
                    variant = variant,
                    onColor = vm::pickColor,
                    onSize = vm::pickSize,
                    quantity = { if (variant != null) QuantityStepper(selection.quantity, vm::setQuantity, max = minOf(variant.availableQty, 20)) },
                    availability = { Availability(variant) },
                )
                GlassBottomPanel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (p.merchantPrice) Text("سعر التاجر", style = MaterialTheme.typography.labelSmall, color = Liquid.GoldDeep)
                            PriceText(variant?.price ?: p.basePrice, variant?.compareAtPrice ?: p.compareAtPrice)
                        }
                        if (variant != null && variant.preOrderable()) {
                            // Sold out, but more is on its way: reserve from the next shipment.
                            Button(onClick = { if (vm.canPreOrder()) preOrderFor = variant }, enabled = !preOrdering) {
                                Text("احجزي من الشحنة القادمة")
                            }
                        } else if (variant != null && !variant.inStock) {
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
        Box(Modifier.fillMaxSize().onGloballyPositioned { layerOrigin = it.positionInRoot() }) {
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { p ->
            val variant = vm.selectedVariant()
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                Gallery(p, selection.color, container.settings.baseUrl, Modifier.onGloballyPositioned { pictureBounds = it.boundsInRoot() })
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
                            if (p.inStock) PromoCountdown(p.promotion?.endsAt)
                            fewLeftText(p.fewLeft)?.let { Tag(it, Liquid.Danger) }
                            if (config.chat.enabled) {
                                OutlinedButton(onClick = openChat) {
                                    Icon(Icons.Filled.Face, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("اسأل المساعد")
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
                    ProductDeals(
                        product = p,
                        selected = variant,
                        known = (offerProducts + related.similar + related.alsoBought).distinctBy { it.id },
                        openProduct = openProduct,
                        onAddSet = vm::addSet,
                        adding = selection.adding,
                    )
                    ReviewsSection(reviewsVm, openAll = openReviews)
                }
                ProductStrip("يناسبه أيضاً", related.similar, openProduct)
                ProductStrip("اشترت العميلات أيضاً", related.alsoBought, openProduct)
                Spacer(Modifier.height(16.dp))
            }
        }
        val loaded = (state as? Load.Ready)?.value
        if (loaded != null) {
            FloatingCartButton(
                count = cart?.itemCount ?: 0,
                bump = bump,
                onClick = openCart,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp).onGloballyPositioned { cartBounds = it.boundsInRoot() },
            )
            FlyToCart(
                flight = flight,
                imageUrl = productMedia(loaded, selection.color, container.settings.baseUrl).filterIsInstance<MediaItem.Image>().firstOrNull()?.url,
                from = pictureBounds,
                to = cartBounds,
                origin = layerOrigin,
                onLanded = {
                    bump += 1
                    confirm = true
                },
            )
            AddedCard(
                visible = confirm,
                productName = loaded.nameAr,
                onViewCart = {
                    confirm = false
                    openCart()
                },
                onContinue = { confirm = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
        }
    }

    preOrderFor?.let { v ->
        PreOrderDialog(
            variant = v,
            busy = preOrdering,
            onConfirm = { qty -> vm.preOrder(v.id, qty) { preOrderFor = null } },
            onDismiss = { preOrderFor = null },
        )
    }
    preOrdered?.let { done -> PreOrderDoneDialog(done, onDismiss = vm::clearPreOrdered) }
}

/** Photos of the chosen [color] first (then the video); a new color starts again from its first photo. */
@Composable
private fun Gallery(p: StoreProduct, color: String?, baseUrl: String, modifier: Modifier = Modifier) {
    Morph(color, modifier) { c -> ColorGallery(p, c, baseUrl) }
}

@Composable
private fun ColorGallery(p: StoreProduct, color: String?, baseUrl: String) {
    val media = remember(p, color, baseUrl) { productMedia(p, color, baseUrl) }
    val pager = rememberPagerState { media.size }
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).glass(Liquid.CardShape, elevation = 14.dp)) {
        HorizontalPager(pager, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 5f)) { page ->
            val open = Modifier.fillMaxSize().clickable { viewerAt = page }
            when (val item = media[page]) {
                is MediaItem.Image -> ProductImage(item.url, open, contentDescription = p.nameAr)
                is MediaItem.Video -> VideoPoster(item.poster, open)
            }
        }
        if (media.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(media.size) { i ->
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) Liquid.Gold else Color.White.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
    viewerAt?.let { start -> MediaViewer(media, start, onDismiss = { viewerAt = null }) }
}

/** Shares the product as text: name, price and the link to its page on the server. */
private fun shareProduct(context: Context, p: StoreProduct, price: String, baseUrl: String) {
    val link = normalizeBaseUrl(baseUrl) + "share/p/" + Uri.encode(p.slug)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, p.nameAr)
        putExtra(Intent.EXTRA_TEXT, "${p.nameAr}\n${Money.format(price)}\n$link")
    }
    runCatching { context.startActivity(Intent.createChooser(send, "مشاركة المنتج")) }
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
    if (variant != null && variant.preOrderable()) {
        IncomingInfo(variant)
        return
    }
    val (text, color) = when {
        variant == null -> "اختر اللون والمقاس" to MaterialTheme.colorScheme.onSurfaceVariant
        !variant.inStock -> "نفدت الكمية" to MaterialTheme.colorScheme.error
        variant.availableQty <= 3 -> com.dejavu.core.model.Numerals.of("باقي ${variant.availableQty}") to Warning
        else -> "متوفر" to Success
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}
