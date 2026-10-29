package com.dejavu.customer.ui.navigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.ui.graphics.Color
import com.dejavu.core.model.TopUpStatus
import com.dejavu.core.ui.theme.FloatingGlassBar
import com.dejavu.core.ui.theme.GlassNavItem
import com.dejavu.core.ui.theme.IosTransitions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import com.dejavu.core.model.Category
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.dejavu.core.ui.theme.page
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dejavu.customer.ui.account.AccountScreen
import com.dejavu.customer.ui.account.AddressFormScreen
import com.dejavu.customer.ui.account.AddressesScreen
import com.dejavu.customer.ui.account.ServerSettingsScreen
import com.dejavu.customer.ui.account.StockAlertsScreen
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.auth.LoginScreen
import com.dejavu.customer.ui.auth.RegisterScreen
import com.dejavu.customer.ui.cart.CartScreen
import com.dejavu.customer.ui.catalog.CategoriesScreen
import com.dejavu.customer.ui.catalog.ProductListScreen
import com.dejavu.customer.ui.checkout.CheckoutScreen
import com.dejavu.core.data.SessionState
import com.dejavu.customer.ui.home.HomeScreen
import com.dejavu.customer.ui.notifications.NotificationsScreen
import com.dejavu.customer.ui.orders.OrderDetailScreen
import com.dejavu.customer.ui.orders.OrdersScreen
import com.dejavu.customer.ui.product.AllReviewsScreen
import com.dejavu.customer.ui.product.ProductScreen
import com.dejavu.customer.ui.wallet.TopUpScreen
import com.dejavu.customer.ui.wallet.WalletScreen
import com.dejavu.customer.ui.wishlist.WishlistScreen
import com.dejavu.customer.ui.chat.ChatScreen
import com.dejavu.customer.ui.about.AboutScreen
import com.dejavu.customer.ui.preorders.PreOrdersScreen

/** Route patterns and builders. Values are URL-encoded. */
object Routes {
    const val HOME = "home"
    const val CATEGORIES = "categories"
    const val CART = "cart"
    const val ORDERS = "orders"
    const val ACCOUNT = "account"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val CHECKOUT = "checkout"
    const val ADDRESSES = "addresses"
    const val SERVER = "server"
    const val NOTIFICATIONS = "notifications"
    const val WALLET = "wallet"
    const val TOP_UP = "wallet/top-up?amount={amount}&currency={currency}&forOrder={forOrder}"
    const val STOCK_ALERTS = "stock-alerts"
    const val CHAT = "chat"
    const val ABOUT = "about"
    const val WISHLIST = "wishlist"
    const val PRE_ORDERS = "pre-orders"
    const val FEED = "feed"
    const val FIND = "find-friends"
    const val USER = "user/{username}?tab={tab}"
    const val POST = "post/{id}"
    const val NEW_POST = "new-post"
    const val BIRTHDAYS = "birthdays"
    const val BIRTHDAY_CARD = "birthday-card"
    const val PROFILE = "profile"
    const val LOYALTY = "loyalty"
    const val GIFTS = "gifts"
    const val GROUP_GIFTS = "group-gifts"

    const val PRODUCTS = "products?title={title}&category={category}&q={q}&featured={featured}&brand={brand}"
    const val PRODUCT = "product/{slug}"
    const val PRODUCT_REVIEWS = "product/{slug}/reviews"
    const val ORDER = "order/{number}?placed={placed}"
    const val ADDRESS_FORM = "address?id={id}"

    fun products(title: String, category: String? = null, query: String? = null, featured: Boolean = false, brand: String? = null) =
        "products?title=${enc(title)}&category=${enc(category)}&q=${enc(query)}&featured=$featured&brand=${enc(brand)}"
    fun product(slug: String) = "product/${enc(slug)}"
    fun user(username: String, tab: Int = 0) = "user/${enc(username)}?tab=$tab"
    fun post(id: String) = "post/${enc(id)}"
    fun productReviews(slug: String) = "product/${enc(slug)}/reviews"
    fun order(number: String, placed: Boolean = false) = "order/${enc(number)}?placed=$placed"
    fun addressForm(id: String? = null) = "address?id=${enc(id)}"
    fun topUp(amount: String? = null, currency: String? = null, forOrder: Boolean = false) =
        "wallet/top-up?amount=${enc(amount)}&currency=${enc(currency)}&forOrder=$forOrder"

    private fun enc(v: String?) = v?.let { Uri.encode(it) } ?: ""
}

private const val DIRECT_PAID = "directPaid"

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "الرئيسية", Icons.Filled.Home),
    Tab(Routes.CATEGORIES, "الأقسام", Icons.Filled.Menu),
    Tab(Routes.FEED, "المجتمع", Icons.Filled.Face),
    Tab(Routes.CART, "السلة", Icons.Filled.ShoppingCart),
    Tab(Routes.ORDERS, "طلباتي", Icons.AutoMirrored.Filled.List),
    Tab(Routes.ACCOUNT, "حسابي", Icons.Filled.Person),
)

@Composable
fun DejavuNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val container = appContainer()
    val cart by container.cart.cart.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()
    val openOrder by container.openOrder.collectAsStateWithLifecycle()
    val openWallet by container.openWallet.collectAsStateWithLifecycle()
    val config by container.extras.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // The owner can ask shoppers to sign in before browsing: then only sign-in / sign-up show.
    val signInFirst = config.requireLogin && session is SessionState.LoggedOut
    val ready = backStack != null
    LaunchedEffect(signInFirst, route, ready) {
        if (!signInFirst || !ready) return@LaunchedEffect
        if (route != Routes.LOGIN && route != Routes.REGISTER && route != Routes.SERVER) {
            nav.navigate(Routes.LOGIN) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    /** After signing in: back where the shopper was, or into the store when sign-in came first. */
    val afterSignIn: () -> Unit = {
        if (config.requireLogin || !nav.popBackStack()) {
            nav.navigate(Routes.HOME) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // A notification tap or the payment page's "back to the app" link.
    LaunchedEffect(openOrder, session is SessionState.LoggedIn) {
        val number = openOrder ?: return@LaunchedEffect
        if (session !is SessionState.LoggedIn) return@LaunchedEffect
        nav.navigate(Routes.order(number)) { launchSingleTop = true }
        container.orderOpened()
    }
    // A new-product notification tap (no sign-in needed to browse).
    val openProduct by container.openProduct.collectAsStateWithLifecycle()
    LaunchedEffect(openProduct, signInFirst, ready) {
        val slug = openProduct ?: return@LaunchedEffect
        if (signInFirst || !ready) return@LaunchedEffect
        nav.navigate(Routes.product(slug)) { launchSingleTop = true }
        container.productOpened()
    }
    // A review prompt ("how was your order?"): the product's reviews, to rate it.
    val openReviews by container.openReviews.collectAsStateWithLifecycle()
    LaunchedEffect(openReviews, signInFirst, ready) {
        val slug = openReviews ?: return@LaunchedEffect
        if (signInFirst || !ready) return@LaunchedEffect
        nav.navigate(Routes.productReviews(slug)) { launchSingleTop = true }
        container.reviewsOpened()
    }
    // An offer notification leading to a category: its products.
    val openCategory by container.openCategory.collectAsStateWithLifecycle()
    LaunchedEffect(openCategory, signInFirst, ready) {
        val slug = openCategory ?: return@LaunchedEffect
        if (signInFirst || !ready) return@LaunchedEffect
        val title = container.catalog.categories().getOrNull()?.let { findCategory(it, slug) }?.nameAr ?: "المنتجات"
        nav.navigate(Routes.products(title, category = slug)) { launchSingleTop = true }
        container.categoryOpened()
    }
    // An offer notification leading to a web page: the browser.
    val openUrl by container.openUrl.collectAsStateWithLifecycle()
    LaunchedEffect(openUrl) {
        val url = openUrl ?: return@LaunchedEffect
        runCatching { uriHandler.openUri(url) }
        container.urlOpened()
    }
    // A cart-reminder notification tap.
    val openCart by container.openCart.collectAsStateWithLifecycle()
    LaunchedEffect(openCart) {
        if (!openCart) return@LaunchedEffect
        nav.switchTab(Routes.CART)
        container.cartOpened()
    }
    // A top-up notification tap.
    LaunchedEffect(openWallet, session is SessionState.LoggedIn) {
        if (!openWallet || session !is SessionState.LoggedIn) return@LaunchedEffect
        nav.navigate(Routes.WALLET) { launchSingleTop = true }
        container.walletOpened()
    }
    // Needed both for push and for the background inbox checks used without Firebase.
    if (session is SessionState.LoggedIn) {
        AskNotificationPermission()
        com.dejavu.customer.ui.members.GenderPrompt()
    }

    val shownTabs = tabs.filter { it.route != Routes.FEED || config.socialEnabled }
    val transitions = remember { IosTransitions(tabs.map { it.route }.toSet()) }
    Scaffold(
        // Screens draw their own top bars; here only the bottom inset is handled.
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!signInFirst && shownTabs.any { it.route == route }) {
                FloatingGlassBar {
                    shownTabs.forEach { tab ->
                        GlassNavItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = tab.icon,
                            label = tab.label,
                            badge = if (tab.route == Routes.CART) cart?.itemCount ?: 0 else 0,
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            enterTransition = transitions.enter,
            exitTransition = transitions.exit,
            popEnterTransition = transitions.popEnter,
            popExitTransition = transitions.popExit,
        ) {
            page(Routes.HOME) {
                HomeScreen(
                    openProduct = { nav.navigate(Routes.product(it)) },
                    openProducts = { title, category, query, featured -> nav.navigate(Routes.products(title, category, query, featured)) },
                    openNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
                    openBrand = { name, slug -> nav.navigate(Routes.products(name, brand = slug)) },
                    openChat = { nav.navigate(Routes.CHAT) },
                    openWishlist = { nav.navigate(Routes.WISHLIST) },
                )
            }
            page(Routes.CATEGORIES) {
                CategoriesScreen(openCategory = { title, slug -> nav.navigate(Routes.products(title, category = slug)) })
            }
            page(
                Routes.PRODUCTS,
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType; nullable = true },
                    navArgument("category") { type = NavType.StringType; nullable = true },
                    navArgument("q") { type = NavType.StringType; nullable = true },
                    navArgument("featured") { type = NavType.BoolType; defaultValue = false },
                    navArgument("brand") { type = NavType.StringType; nullable = true },
                ),
            ) { entry ->
                val args = entry.arguments
                ProductListScreen(
                    title = args?.getString("title").orEmpty().ifBlank { "المنتجات" },
                    category = args?.getString("category")?.ifBlank { null },
                    initialQuery = args?.getString("q")?.ifBlank { null },
                    featured = args?.getBoolean("featured") ?: false,
                    onBack = { nav.popBackStack() },
                    openProduct = { nav.navigate(Routes.product(it)) },
                    brand = args?.getString("brand")?.ifBlank { null },
                )
            }
            page(Routes.PRODUCT) { entry ->
                val slug = entry.arguments?.getString("slug").orEmpty()
                ProductScreen(
                    slug = slug,
                    onBack = { nav.popBackStack() },
                    openCart = { nav.switchTab(Routes.CART) },
                    requireLogin = { nav.navigate(Routes.LOGIN) },
                    openBrand = { name, brand -> nav.navigate(Routes.products(name, brand = brand)) },
                    openReviews = { nav.navigate(Routes.productReviews(slug)) },
                    openProduct = { nav.navigate(Routes.product(it)) },
                    openChat = { nav.navigate(Routes.CHAT) },
                )
            }
            page(Routes.PRODUCT_REVIEWS) { entry ->
                AllReviewsScreen(slug = entry.arguments?.getString("slug").orEmpty(), onBack = { nav.popBackStack() })
            }
            page(Routes.CART) {
                CartScreen(
                    openProduct = { nav.navigate(Routes.product(it)) },
                    checkout = { nav.navigate(Routes.CHECKOUT) },
                    login = { nav.navigate(Routes.LOGIN) },
                    shop = { nav.switchTab(Routes.HOME) },
                )
            }
            page(Routes.CHECKOUT) { entry ->
                // Set by the direct-payment screen once the transfer was credited.
                val paid by entry.savedStateHandle.getStateFlow(DIRECT_PAID, false).collectAsStateWithLifecycle()
                CheckoutScreen(
                    onBack = { nav.popBackStack() },
                    addAddress = { nav.navigate(Routes.addressForm()) },
                    topUp = { nav.navigate(Routes.topUp()) },
                    payDirect = { amount, currency -> nav.navigate(Routes.topUp(amount, currency, forOrder = true)) },
                    directPaid = paid,
                    consumeDirectPaid = { entry.savedStateHandle[DIRECT_PAID] = false },
                    placed = { number ->
                        nav.navigate(Routes.order(number, placed = true)) { popUpTo(Routes.CART) }
                    },
                )
            }
            page(Routes.ORDERS) {
                OrdersScreen(openOrder = { nav.navigate(Routes.order(it)) }, login = { nav.navigate(Routes.LOGIN) }, shop = { nav.switchTab(Routes.HOME) })
            }
            page(Routes.ORDER, arguments = listOf(navArgument("placed") { type = NavType.BoolType; defaultValue = false })) { entry ->
                val number = entry.arguments?.getString("number").orEmpty()
                OrderDetailScreen(
                    number = number,
                    justPlaced = entry.arguments?.getBoolean("placed") ?: false,
                    onBack = { if (!nav.popBackStack()) nav.switchTab(Routes.ORDERS) },
                    openProduct = { nav.navigate(Routes.product(it)) },
                )
            }
            page(Routes.ACCOUNT) {
                AccountScreen(
                    login = { nav.navigate(Routes.LOGIN) },
                    register = { nav.navigate(Routes.REGISTER) },
                    addresses = { nav.navigate(Routes.ADDRESSES) },
                    orders = { nav.switchTab(Routes.ORDERS) },
                    notifications = { nav.navigate(Routes.NOTIFICATIONS) },
                    wallet = { nav.navigate(Routes.WALLET) },
                    server = { nav.navigate(Routes.SERVER) },
                    stockAlerts = { nav.navigate(Routes.STOCK_ALERTS) },
                    wishlist = { nav.navigate(Routes.WISHLIST) },
                    about = { nav.navigate(Routes.ABOUT) },
                    chat = { nav.navigate(Routes.CHAT) },
                    preOrders = { nav.navigate(Routes.PRE_ORDERS) },
                    profile = { nav.navigate(Routes.PROFILE) },
                    loyalty = { nav.navigate(Routes.LOYALTY) },
                    gifts = { nav.navigate(Routes.GIFTS) },
                    groupGifts = { nav.navigate(Routes.GROUP_GIFTS) },
                    birthdays = { nav.navigate(Routes.BIRTHDAYS) },
                    birthdayCard = { nav.navigate(Routes.BIRTHDAY_CARD) },
                    findFriends = { nav.navigate(Routes.FIND) },
                )
            }
            page(Routes.FEED) {
                com.dejavu.customer.ui.social.FeedScreen(
                    openUser = { nav.navigate(Routes.user(it)) },
                    openProduct = { nav.navigate(Routes.product(it)) },
                    newPost = { nav.navigate(Routes.NEW_POST) },
                    birthdays = { nav.navigate(Routes.BIRTHDAYS) },
                    login = { nav.navigate(Routes.LOGIN) },
                    openCart = { nav.switchTab(Routes.CART) },
                )
            }
            page(
                Routes.USER,
                arguments = listOf(navArgument("username") { type = NavType.StringType }, navArgument("tab") { type = NavType.IntType; defaultValue = 0 }),
            ) { entry ->
                com.dejavu.customer.ui.social.UserScreen(
                    username = entry.arguments?.getString("username").orEmpty(),
                    initialTab = entry.arguments?.getInt("tab") ?: 0,
                    onBack = { nav.popBackStack() },
                    openUser = { nav.navigate(Routes.user(it)) },
                    openProduct = { nav.navigate(Routes.product(it)) },
                    login = { nav.navigate(Routes.LOGIN) },
                    openCart = { nav.switchTab(Routes.CART) },
                    editProfile = { nav.navigate(Routes.PROFILE) },
                    birthdayCard = { nav.navigate(Routes.BIRTHDAY_CARD) },
                )
            }
            page(Routes.POST) { entry ->
                com.dejavu.customer.ui.social.PostScreen(
                    id = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    openUser = { nav.navigate(Routes.user(it)) },
                    openProduct = { nav.navigate(Routes.product(it)) },
                    login = { nav.navigate(Routes.LOGIN) },
                    openCart = { nav.switchTab(Routes.CART) },
                )
            }
            page(Routes.NEW_POST) {
                com.dejavu.customer.ui.social.CreatePostScreen(
                    onBack = { nav.popBackStack() },
                    posted = { id -> nav.navigate(Routes.post(id)) { popUpTo(Routes.NEW_POST) { inclusive = true } } },
                    editProfile = { nav.navigate(Routes.PROFILE) },
                )
            }
            page(Routes.BIRTHDAYS) {
                com.dejavu.customer.ui.social.BirthdaysScreen(
                    onBack = { nav.popBackStack() },
                    openUser = { name, tab -> nav.navigate(Routes.user(name, tab)) },
                    login = { nav.navigate(Routes.LOGIN) },
                )
            }
            page(Routes.BIRTHDAY_CARD) {
                com.dejavu.customer.ui.social.BirthdayCardScreen(onBack = { nav.popBackStack() }, openMyPage = { nav.navigate(Routes.user(it, tab = 1)) })
            }
            page(Routes.FIND) {
                com.dejavu.customer.ui.social.FindFriendsScreen(onBack = { nav.popBackStack() }, openUser = { nav.navigate(Routes.user(it, tab = 1)) })
            }
            page(Routes.PROFILE) { com.dejavu.customer.ui.members.ProfileScreen(onBack = { nav.popBackStack() }) }
            page(Routes.LOYALTY) { com.dejavu.customer.ui.members.LoyaltyScreen(onBack = { nav.popBackStack() }) }
            page(Routes.GIFTS) { com.dejavu.customer.ui.members.GiftsScreen(onBack = { nav.popBackStack() }, addAddress = { nav.navigate(Routes.addressForm()) }) }
            page(Routes.GROUP_GIFTS) { com.dejavu.customer.ui.members.GroupGiftsScreen(onBack = { nav.popBackStack() }) }
            page(Routes.WISHLIST) {
                WishlistScreen(onBack = { nav.popBackStack() }, openProduct = { nav.navigate(Routes.product(it)) }, login = { nav.navigate(Routes.LOGIN) })
            }
            page(Routes.CHAT) {
                ChatScreen(onBack = { nav.popBackStack() }, openProduct = { nav.navigate(Routes.product(it)) }, login = { nav.navigate(Routes.LOGIN) })
            }
            page(Routes.PRE_ORDERS) { PreOrdersScreen(onBack = { nav.popBackStack() }, openProduct = { nav.navigate(Routes.product(it)) }) }
            page(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
            page(Routes.STOCK_ALERTS) {
                StockAlertsScreen(onBack = { nav.popBackStack() }, openProduct = { nav.navigate(Routes.product(it)) })
            }
            page(Routes.ADDRESSES) {
                AddressesScreen(onBack = { nav.popBackStack() }, edit = { nav.navigate(Routes.addressForm(it)) })
            }
            page(Routes.ADDRESS_FORM, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true })) { entry ->
                AddressFormScreen(id = entry.arguments?.getString("id")?.ifBlank { null }, onDone = { nav.popBackStack() })
            }
            page(Routes.LOGIN) {
                LoginScreen(
                    onBack = { if (signInFirst) context.findActivity()?.finish() else nav.popBackStack() },
                    loggedIn = afterSignIn,
                    register = { nav.navigate(Routes.REGISTER) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                    required = signInFirst,
                )
            }
            page(Routes.REGISTER) {
                RegisterScreen(
                    onBack = { if (signInFirst) context.findActivity()?.finish() else nav.popBackStack() },
                    registered = afterSignIn,
                    login = { nav.navigate(Routes.LOGIN) { popUpTo(Routes.REGISTER) { inclusive = true } } },
                    required = signInFirst,
                )
            }
            page(Routes.SERVER) { ServerSettingsScreen(onBack = { nav.popBackStack() }) }
            page(Routes.NOTIFICATIONS) {
                NotificationsScreen(
                    onBack = { nav.popBackStack() },
                    openOrder = { nav.navigate(Routes.order(it)) },
                    openWallet = { nav.navigate(Routes.WALLET) { launchSingleTop = true } },
                    openProduct = { nav.navigate(Routes.product(it)) },
                    openCart = { nav.switchTab(Routes.CART) },
                    openReviews = { nav.navigate(Routes.productReviews(it)) },
                    openCategory = { container.requestCategory(it) },
                    openScreen = { screen, data ->
                        val me = (session as? SessionState.LoggedIn)?.customer?.username
                        val target = when (screen) {
                            "gifts" -> Routes.GIFTS
                            "group-gift" -> Routes.GROUP_GIFTS
                            "loyalty" -> Routes.LOYALTY
                            "post" -> data["post"]?.let(Routes::post) ?: Routes.FEED
                            "user" -> data["username"]?.let { Routes.user(it) } ?: Routes.FEED
                            // My own birthday: the card to share. A friend's: their wishes, to gift.
                            "birthday" -> data["username"]?.takeIf { it != me }?.let { Routes.user(it, tab = 1) } ?: Routes.BIRTHDAY_CARD
                            else -> Routes.PROFILE
                        }
                        nav.navigate(target)
                    },
                )
            }
            page(Routes.WALLET) {
                WalletScreen(onBack = { if (!nav.popBackStack()) nav.switchTab(Routes.ACCOUNT) }, topUp = { nav.navigate(Routes.topUp()) })
            }
            page(
                Routes.TOP_UP,
                arguments = listOf(
                    navArgument("amount") { type = NavType.StringType; nullable = true },
                    navArgument("currency") { type = NavType.StringType; nullable = true },
                    navArgument("forOrder") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val args = entry.arguments
                val forOrder = args?.getBoolean("forOrder") ?: false
                TopUpScreen(
                    onBack = { nav.popBackStack() },
                    amount = args?.getString("amount")?.ifBlank { null },
                    currency = args?.getString("currency")?.ifBlank { null },
                    forOrder = forOrder,
                    submitted = { topUp ->
                        if (forOrder && topUp.status == TopUpStatus.APPROVED) {
                            // Credited: back to checkout, which places the order from the wallet.
                            nav.previousBackStackEntry?.savedStateHandle?.set(DIRECT_PAID, true)
                            nav.popBackStack()
                        } else if (!nav.popBackStack(Routes.WALLET, inclusive = false)) {
                            // Show the new request (under review) in the wallet.
                            nav.navigate(Routes.WALLET) { popUpTo(Routes.TOP_UP) { inclusive = true } }
                        }
                    },
                )
            }
        }
    }
}

/** Android 13+ asks before an app may show notifications; ask once after sign-in. */
@Composable
private fun AskNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** The category with [slug] anywhere in the tree. */
private fun findCategory(list: List<Category>, slug: String): Category? {
    for (c in list) {
        if (c.slug == slug) return c
        findCategory(c.children, slug)?.let { return it }
    }
    return null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Bottom-tab navigation: one copy of each tab, state restored when coming back. */
private fun NavController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
