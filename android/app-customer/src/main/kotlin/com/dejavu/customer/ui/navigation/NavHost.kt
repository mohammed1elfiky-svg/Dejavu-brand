package com.dejavu.customer.ui.navigation

import android.Manifest
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dejavu.customer.ui.account.AccountScreen
import com.dejavu.customer.ui.account.AddressFormScreen
import com.dejavu.customer.ui.account.AddressesScreen
import com.dejavu.customer.ui.account.ReturnsScreen
import com.dejavu.customer.ui.account.ServerSettingsScreen
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
import com.dejavu.customer.ui.orders.ReturnRequestScreen
import com.dejavu.customer.ui.product.ProductScreen
import com.dejavu.customer.ui.wallet.TopUpScreen
import com.dejavu.customer.ui.wallet.WalletScreen

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
    const val RETURNS = "returns"
    const val SERVER = "server"
    const val NOTIFICATIONS = "notifications"
    const val WALLET = "wallet"
    const val TOP_UP = "wallet/top-up"

    const val PRODUCTS = "products?title={title}&category={category}&q={q}&featured={featured}"
    const val PRODUCT = "product/{slug}"
    const val ORDER = "order/{number}?placed={placed}"
    const val RETURN_REQUEST = "order/{number}/return"
    const val ADDRESS_FORM = "address?id={id}"

    fun products(title: String, category: String? = null, query: String? = null, featured: Boolean = false) =
        "products?title=${enc(title)}&category=${enc(category)}&q=${enc(query)}&featured=$featured"
    fun product(slug: String) = "product/${enc(slug)}"
    fun order(number: String, placed: Boolean = false) = "order/${enc(number)}?placed=$placed"
    fun returnRequest(number: String) = "order/${enc(number)}/return"
    fun addressForm(id: String? = null) = "address?id=${enc(id)}"

    private fun enc(v: String?) = v?.let { Uri.encode(it) } ?: ""
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "الرئيسية", Icons.Filled.Home),
    Tab(Routes.CATEGORIES, "الأقسام", Icons.Filled.Menu),
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

    // A notification tap or the payment page's "back to the app" link.
    LaunchedEffect(openOrder, session is SessionState.LoggedIn) {
        val number = openOrder ?: return@LaunchedEffect
        if (session !is SessionState.LoggedIn) return@LaunchedEffect
        nav.navigate(Routes.order(number)) { launchSingleTop = true }
        container.orderOpened()
    }
    // A top-up notification tap.
    LaunchedEffect(openWallet, session is SessionState.LoggedIn) {
        if (!openWallet || session !is SessionState.LoggedIn) return@LaunchedEffect
        nav.navigate(Routes.WALLET) { launchSingleTop = true }
        container.walletOpened()
    }
    if (session is SessionState.LoggedIn && container.pushEnabled) AskNotificationPermission()

    Scaffold(
        // Screens draw their own top bars; here only the bottom inset is handled.
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            label = { Text(tab.label) },
                            icon = {
                                val count = if (tab.route == Routes.CART) cart?.itemCount ?: 0 else 0
                                BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) { Icon(tab.icon, contentDescription = tab.label) }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    openProduct = { nav.navigate(Routes.product(it)) },
                    openProducts = { title, category, query, featured -> nav.navigate(Routes.products(title, category, query, featured)) },
                    openNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
                )
            }
            composable(Routes.CATEGORIES) {
                CategoriesScreen(openCategory = { title, slug -> nav.navigate(Routes.products(title, category = slug)) })
            }
            composable(
                Routes.PRODUCTS,
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType; nullable = true },
                    navArgument("category") { type = NavType.StringType; nullable = true },
                    navArgument("q") { type = NavType.StringType; nullable = true },
                    navArgument("featured") { type = NavType.BoolType; defaultValue = false },
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
                )
            }
            composable(Routes.PRODUCT) { entry ->
                ProductScreen(
                    slug = entry.arguments?.getString("slug").orEmpty(),
                    onBack = { nav.popBackStack() },
                    openCart = { nav.switchTab(Routes.CART) },
                    requireLogin = { nav.navigate(Routes.LOGIN) },
                )
            }
            composable(Routes.CART) {
                CartScreen(
                    openProduct = { nav.navigate(Routes.product(it)) },
                    checkout = { nav.navigate(Routes.CHECKOUT) },
                    login = { nav.navigate(Routes.LOGIN) },
                    shop = { nav.switchTab(Routes.HOME) },
                )
            }
            composable(Routes.CHECKOUT) {
                CheckoutScreen(
                    onBack = { nav.popBackStack() },
                    addAddress = { nav.navigate(Routes.addressForm()) },
                    topUp = { nav.navigate(Routes.TOP_UP) },
                    placed = { number ->
                        nav.navigate(Routes.order(number, placed = true)) { popUpTo(Routes.CART) }
                    },
                )
            }
            composable(Routes.ORDERS) {
                OrdersScreen(openOrder = { nav.navigate(Routes.order(it)) }, login = { nav.navigate(Routes.LOGIN) }, shop = { nav.switchTab(Routes.HOME) })
            }
            composable(Routes.ORDER, arguments = listOf(navArgument("placed") { type = NavType.BoolType; defaultValue = false })) { entry ->
                val number = entry.arguments?.getString("number").orEmpty()
                OrderDetailScreen(
                    number = number,
                    justPlaced = entry.arguments?.getBoolean("placed") ?: false,
                    onBack = { if (!nav.popBackStack()) nav.switchTab(Routes.ORDERS) },
                    requestReturn = { nav.navigate(Routes.returnRequest(number)) },
                    openProduct = { nav.navigate(Routes.product(it)) },
                )
            }
            composable(Routes.RETURN_REQUEST) { entry ->
                ReturnRequestScreen(
                    number = entry.arguments?.getString("number").orEmpty(),
                    onBack = { nav.popBackStack() },
                    submitted = { nav.navigate(Routes.RETURNS) { popUpTo(Routes.ORDERS) } },
                )
            }
            composable(Routes.ACCOUNT) {
                AccountScreen(
                    login = { nav.navigate(Routes.LOGIN) },
                    register = { nav.navigate(Routes.REGISTER) },
                    addresses = { nav.navigate(Routes.ADDRESSES) },
                    returns = { nav.navigate(Routes.RETURNS) },
                    orders = { nav.switchTab(Routes.ORDERS) },
                    notifications = { nav.navigate(Routes.NOTIFICATIONS) },
                    wallet = { nav.navigate(Routes.WALLET) },
                    server = { nav.navigate(Routes.SERVER) },
                )
            }
            composable(Routes.ADDRESSES) {
                AddressesScreen(onBack = { nav.popBackStack() }, edit = { nav.navigate(Routes.addressForm(it)) })
            }
            composable(Routes.ADDRESS_FORM, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true })) { entry ->
                AddressFormScreen(id = entry.arguments?.getString("id")?.ifBlank { null }, onDone = { nav.popBackStack() })
            }
            composable(Routes.RETURNS) {
                ReturnsScreen(onBack = { nav.popBackStack() }, openOrder = { nav.navigate(Routes.order(it)) })
            }
            composable(Routes.LOGIN) {
                LoginScreen(
                    onBack = { nav.popBackStack() },
                    loggedIn = { nav.popBackStack() },
                    register = { nav.navigate(Routes.REGISTER) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                )
            }
            composable(Routes.REGISTER) {
                RegisterScreen(
                    onBack = { nav.popBackStack() },
                    registered = { nav.popBackStack() },
                    login = { nav.navigate(Routes.LOGIN) { popUpTo(Routes.REGISTER) { inclusive = true } } },
                )
            }
            composable(Routes.SERVER) { ServerSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen(
                    onBack = { nav.popBackStack() },
                    openOrder = { nav.navigate(Routes.order(it)) },
                    openWallet = { nav.navigate(Routes.WALLET) { launchSingleTop = true } },
                )
            }
            composable(Routes.WALLET) {
                WalletScreen(onBack = { if (!nav.popBackStack()) nav.switchTab(Routes.ACCOUNT) }, topUp = { nav.navigate(Routes.TOP_UP) })
            }
            composable(Routes.TOP_UP) {
                TopUpScreen(
                    onBack = { nav.popBackStack() },
                    // Show the new request (under review) in the wallet.
                    submitted = {
                        if (!nav.popBackStack(Routes.WALLET, inclusive = false)) {
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

/** Bottom-tab navigation: one copy of each tab, state restored when coming back. */
private fun NavController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
