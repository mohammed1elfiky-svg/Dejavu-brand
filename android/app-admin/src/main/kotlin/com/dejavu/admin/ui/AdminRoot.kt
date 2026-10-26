@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui

import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import com.dejavu.core.ui.storage.SavedLogin
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Checkbox
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ShoppingCart
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.dejavu.core.ui.theme.FloatingGlassBar
import com.dejavu.core.ui.theme.GlassNavItem
import com.dejavu.core.ui.theme.LiquidTransitions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dejavu.admin.ui.dashboard.DashboardScreen
import com.dejavu.admin.ui.expenses.ExpenseDetailScreen
import com.dejavu.admin.ui.expenses.ExpensesScreen
import com.dejavu.admin.ui.expenses.NewExpenseScreen
import com.dejavu.admin.ui.finance.CustomerDetailScreen
import com.dejavu.admin.ui.finance.CustomersScreen
import com.dejavu.admin.ui.finance.ReportsScreen
import com.dejavu.admin.ui.finance.SettlementsScreen
import com.dejavu.admin.ui.inventory.ProductDetailScreen
import com.dejavu.admin.ui.inventory.ProductsScreen
import com.dejavu.admin.ui.brands.BrandsScreen
import com.dejavu.admin.ui.system.WipeDataScreen
import com.dejavu.admin.ui.printers.PrinterSettingsScreen
import com.dejavu.admin.ui.purchasing.EditImportScreen
import com.dejavu.admin.ui.purchasing.SupplierAccountScreen
import com.dejavu.admin.ui.sms.SmsGatewayScreen
import com.dejavu.admin.ui.categories.CategoriesScreen
import com.dejavu.admin.ui.coupons.CouponsScreen
import com.dejavu.admin.ui.pos.ShiftScreen
import com.dejavu.admin.ui.system.AuditScreen
import com.dejavu.admin.ui.system.BackupScreen
import com.dejavu.admin.ui.system.DailySummaryScreen
import com.dejavu.admin.ui.store.ReviewsScreen
import com.dejavu.admin.ui.store.StoreSetupScreen
import com.dejavu.admin.ui.search.SearchScreen
import com.dejavu.admin.ui.layaway.LayawayScreen
import com.dejavu.admin.ui.layaway.LayawaysScreen
import com.dejavu.admin.ui.layaway.NewLayawayScreen
import com.dejavu.admin.ui.giftcards.GiftCardsScreen
import com.dejavu.admin.ui.inventory.DamagesScreen
import com.dejavu.admin.ui.pricing.BulkPriceScreen
import com.dejavu.admin.ui.pricing.OffersScreen
import com.dejavu.admin.ui.system.OwnerAlertsScreen
import com.dejavu.admin.ui.excel.ExcelScreen
import com.dejavu.admin.ui.customerapp.AssistantScreen
import com.dejavu.admin.ui.customerapp.BehaviorScreen
import com.dejavu.admin.ui.customerapp.CampaignsScreen
import com.dejavu.admin.ui.customerapp.CustomerAppSettingsScreen
import com.dejavu.admin.ui.more.AppearanceScreen
import com.dejavu.admin.ui.more.OnboardingTour
import com.dejavu.admin.ui.finance.DebtorsScreen
import com.dejavu.admin.ui.inventory.StockCountsScreen
import com.dejavu.admin.ui.purchasing.ImportReceiveScreen
import com.dejavu.admin.ui.purchasing.NewImportScreen
import com.dejavu.admin.ui.purchasing.SupplierScreen
import com.dejavu.admin.ui.purchasing.SuppliersScreen
import com.dejavu.admin.ui.inventory.StockScreen
import com.dejavu.admin.ui.products.ProductFormScreen
import com.dejavu.admin.ui.more.CurrencyScreen
import com.dejavu.admin.ui.more.MoreScreen
import com.dejavu.admin.ui.more.ServerScreen
import com.dejavu.admin.ui.orders.OrderDetailScreen
import com.dejavu.admin.ui.orders.OrdersScreen
import com.dejavu.admin.ui.orders.ReturnDetailScreen
import com.dejavu.admin.ui.orders.ReturnsScreen
import com.dejavu.admin.ui.purchasing.PurchaseOrderScreen
import com.dejavu.admin.ui.purchasing.NewPurchaseScreen
import com.dejavu.admin.ui.purchasing.PurchasingScreen
import com.dejavu.admin.ui.pos.PosReturnScreen
import com.dejavu.admin.ui.pos.PosScreen
import com.dejavu.admin.ui.staff.StaffEditScreen
import com.dejavu.admin.ui.staff.StaffScreen
import com.dejavu.admin.ui.promotions.PromotionEditScreen
import com.dejavu.admin.ui.promotions.PromotionsScreen
import com.dejavu.admin.ui.treasury.CashBoxScreen
import com.dejavu.admin.ui.treasury.CashBoxesScreen
import com.dejavu.admin.ui.treasury.ExchangeScreen
import com.dejavu.admin.ui.wallet.BankAccountsScreen
import com.dejavu.admin.ui.wallet.TopUpScreen
import com.dejavu.admin.ui.wallet.TopUpsScreen
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.data.userMessage
import com.dejavu.core.network.ApiException
import kotlinx.coroutines.launch

object R {
    const val DASHBOARD = "dashboard"
    const val ORDERS = "orders"
    const val STOCK = "stock"
    const val MORE = "more"
    const val ORDER = "order/{id}"
    const val RETURNS = "returns"
    const val RETURN = "return/{id}"
    const val PRODUCTS = "products"
    const val PRODUCT = "product/{id}"
    const val PRODUCT_NEW = "product-new"
    const val PRODUCT_EDIT = "product-edit/{id}"
    const val PURCHASING = "purchasing"
    const val PURCHASE = "purchase/{id}"
    const val EXPENSES = "expenses"
    const val EXPENSE = "expense/{id}"
    const val NEW_EXPENSE = "expense-new"
    const val SETTLEMENTS = "settlements"
    const val REPORTS = "reports"
    const val CUSTOMERS = "customers"
    const val CUSTOMER = "customer/{id}"
    const val SERVER = "server"
    const val CURRENCY = "currency"
    const val POS = "pos"
    const val CASH_BOXES = "cash-boxes"
    const val CASH_BOX = "cash-box/{id}"
    const val EXCHANGE = "exchange"
    const val PROMOTIONS = "promotions"
    /** id "new" for a new promotion. */
    const val PROMOTION = "promotion/{id}"
    const val TOP_UPS = "top-ups"
    const val TOP_UP = "top-up/{id}"
    const val BANK_ACCOUNTS = "bank-accounts"
    const val STAFF = "staff"
    const val POS_RETURNS = "pos-returns"
    const val PURCHASE_NEW = "purchase-new"
    const val BRANDS = "brands"
    const val BRAND_PRODUCTS = "brand-products/{id}"
    const val WIPE = "wipe-test-data"
    const val PRINTERS = "printers"
    const val SUPPLIERS = "suppliers"
    const val SUPPLIER = "supplier/{id}"
    const val IMPORT_NEW = "import-new/{id}"
    const val IMPORT_RECEIVE = "import-receive/{id}"
    const val IMPORT_EDIT = "import-edit/{id}"
    const val SUPPLIER_ACCOUNT = "supplier-account/{id}"
    const val SMS = "sms"
    const val CATEGORIES = "categories"
    const val COUPONS = "coupons"
    const val SHIFT = "shift"
    const val AUDIT = "audit"
    const val DEBTORS = "debtors"
    const val BACKUP = "backup"
    const val STOCK_COUNTS = "stock-counts"
    const val DAILY_SUMMARY = "daily-summary"
    const val STORE_SETUP = "store-setup"
    const val REVIEWS = "reviews"
    const val SEARCH = "search"
    const val APPEARANCE = "appearance"
    const val LAYAWAYS = "layaways"
    const val LAYAWAY = "layaway/{id}"
    const val LAYAWAY_NEW = "layaway-new"
    const val GIFT_CARDS = "gift-cards"
    const val DAMAGES = "damages"
    const val BULK_PRICE = "bulk-price"
    const val OFFERS = "offers"
    const val OWNER_ALERTS = "owner-alerts"
    const val EXCEL = "excel"
    const val CUSTOMER_APP = "customer-app"
    const val ASSISTANT = "assistant"
    const val CAMPAIGNS = "campaigns"
    const val BEHAVIOR = "behavior"
    /** id "new" for a new employee. */
    const val STAFF_EDIT = "staff/{id}"

    fun of(pattern: String, id: String) = pattern.replace("{id}", Uri.encode(id))
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val permission: String?)

private val tabs = listOf(
    // Everyone gets a home (its cards follow the role); till-only staff are filtered below.
    Tab(R.DASHBOARD, "الرئيسية", Icons.Filled.Home, null),
    Tab(R.POS, "الكاشير", Icons.Filled.AddCircle, Perm.POS_SELL),
    Tab(R.ORDERS, "الطلبات", Icons.AutoMirrored.Filled.List, Perm.ORDERS_READ),
    Tab(R.STOCK, "المخزون", Icons.Filled.ShoppingCart, Perm.STOCK_READ),
    Tab(R.MORE, "المزيد", Icons.Filled.Menu, null),
)

@Composable
fun AdminRoot() {
    val session by adminContainer().session.state.collectAsStateWithLifecycle()
    when (val s = session) {
        StaffSession.LoggedOut -> LoginScreen()
        is StaffSession.LoggedIn ->
            if (s.profile == null) {
                RestoringScreen()
            } else {
                AdminNavHost(s)
            }
    }
}

@Composable
private fun AdminNavHost(session: StaffSession.LoggedIn) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // Till staff (cashier / clerk without the dashboard) see only the till and the orders.
    val tillOnly = session.can(Perm.POS_SELL) && !session.can(Perm.DASHBOARD) && !session.can(Perm.USERS_MANAGE)
    val visibleTabs = tabs.filter { it.permission == null || session.can(it.permission) }
        .filter { !tillOnly || it.route == R.POS || it.route == R.ORDERS }
    val open = { pattern: String, id: String -> nav.navigate(R.of(pattern, id)) }
    val back: () -> Unit = { nav.popBackStack() }
    val container = adminContainer()
    val openOrderId by container.openOrderId.collectAsStateWithLifecycle()
    LaunchedEffect(openOrderId) {
        val id = openOrderId ?: return@LaunchedEffect
        if (session.can(Perm.ORDERS_READ)) nav.navigate(R.of(R.ORDER, id)) { launchSingleTop = true }
        container.orderOpened()
    }
    // A tapped "new top-up request" alert.
    val openTopUpId by container.openTopUpId.collectAsStateWithLifecycle()
    LaunchedEffect(openTopUpId) {
        val id = openTopUpId ?: return@LaunchedEffect
        if (session.can(Perm.WALLET_MANAGE)) nav.navigate(R.of(R.TOP_UP, id)) { launchSingleTop = true }
        container.topUpOpened()
    }
    // Alerts arrive by Firebase push or, without it, by polling; both need the permission.
    AskNotificationPermission()
    val badges by container.badges.collectAsStateWithLifecycle()
    // Counters change as staff work: refresh them on every screen change too.
    LaunchedEffect(route) { container.refreshBadges() }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            val onTab = visibleTabs.any { it.route == route }
            Column {
                ConnectionBar(aboveSystemBar = !onTab)
                if (onTab) {
                    FloatingGlassBar {
                        visibleTabs.forEach { tab ->
                            val badge = when (tab.route) {
                                R.ORDERS -> badges?.newOrders ?: 0
                                R.STOCK -> 0
                                R.MORE -> (badges?.topUps ?: 0) + (badges?.reviews ?: 0)
                                else -> 0
                            }
                            GlassNavItem(selected = route == tab.route, onClick = { nav.switchTab(tab.route) }, icon = tab.icon, label = tab.label, badge = badge)
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = visibleTabs.first().route,
            modifier = Modifier.padding(padding),
            enterTransition = LiquidTransitions.enter,
            exitTransition = LiquidTransitions.exit,
            popEnterTransition = LiquidTransitions.popEnter,
            popExitTransition = LiquidTransitions.popExit,
        ) {
            composable(R.DASHBOARD) {
                DashboardScreen(
                    session,
                    navigate = { nav.navigate(it) },
                    switchTab = { nav.switchTab(it) },
                    openOrders = { nav.switchTab(R.ORDERS) },
                    openReturns = { nav.navigate(R.RETURNS) },
                    openStock = { nav.switchTab(R.STOCK) },
                    openSettlements = { nav.navigate(R.SETTLEMENTS) },
                )
            }
            composable(R.ORDERS) {
                OrdersScreen(
                    openOrder = { open(R.ORDER, it) },
                    detailPane = { id, close -> OrderDetailScreen(id, session, close, openReturn = { open(R.RETURN, it) }) },
                )
            }
            composable(R.ORDER) { e -> OrderDetailScreen(e.arguments?.getString("id").orEmpty(), session, back, openReturn = { open(R.RETURN, it) }) }
            composable(R.RETURNS) { ReturnsScreen(back, openReturn = { open(R.RETURN, it) }) }
            composable(R.RETURN) { e -> ReturnDetailScreen(e.arguments?.getString("id").orEmpty(), session, back) }
            composable(R.STOCK) { StockScreen(session, openProducts = { nav.navigate(R.PRODUCTS) }, openProduct = { open(R.PRODUCT, it) }) }
            composable(R.PRODUCTS) {
                ProductsScreen(
                    back,
                    openProduct = { open(R.PRODUCT, it) },
                    newProduct = if (session.can(Perm.PRODUCTS_WRITE)) ({ nav.navigate(R.PRODUCT_NEW) }) else null,
                    detailPane = { id, close -> ProductDetailScreen(id, session, close, edit = { open(R.PRODUCT_EDIT, id) }) },
                )
            }
            composable(R.BRANDS) {
                BrandsScreen(
                    canEdit = session.can(Perm.CATEGORIES_WRITE),
                    onBack = back,
                    openProducts = { open(R.BRAND_PRODUCTS, it) },
                )
            }
            composable(R.BRAND_PRODUCTS) { e ->
                ProductsScreen(
                    back,
                    openProduct = { open(R.PRODUCT, it) },
                    newProduct = null,
                    initialBrandId = e.arguments?.getString("id"),
                )
            }
            composable(R.PRINTERS) { PrinterSettingsScreen(back) }
            composable(R.WIPE) { WipeDataScreen(back, done = { nav.switchTab(R.MORE) }) }
            composable(R.PRODUCT) { e ->
                val id = e.arguments?.getString("id").orEmpty()
                ProductDetailScreen(id, session, back, edit = { open(R.PRODUCT_EDIT, id) })
            }
            composable(R.PRODUCT_NEW) {
                ProductFormScreen(null, back, saved = { id -> nav.navigate(R.of(R.PRODUCT, id)) { popUpTo(R.PRODUCTS) } })
            }
            composable(R.PRODUCT_EDIT) { e ->
                ProductFormScreen(e.arguments?.getString("id").orEmpty(), back, saved = { nav.popBackStack() })
            }
            composable(R.PURCHASING) {
                PurchasingScreen(
                    back,
                    openOrder = { open(R.PURCHASE, it) },
                    newPurchase = { nav.navigate(R.PURCHASE_NEW) },
                    openSuppliers = { nav.navigate(R.SUPPLIERS) },
                    newImport = if (session.can(Perm.PRODUCTS_WRITE)) ({ open(R.IMPORT_NEW, "-") }) else null,
                )
            }
            composable(R.SUPPLIERS) { SuppliersScreen(session.can("suppliers.manage"), back, openSupplier = { open(R.SUPPLIER, it) }) }
            composable(R.SUPPLIER) { e ->
                SupplierScreen(
                    e.arguments?.getString("id").orEmpty(),
                    canManage = session.can("suppliers.manage"),
                    canOrder = session.can(Perm.PRODUCTS_WRITE) && session.can("purchases.write"),
                    onBack = back,
                    openOrder = { open(R.PURCHASE, it) },
                    newImport = { open(R.IMPORT_NEW, it) },
                    openAccount = { open(R.SUPPLIER_ACCOUNT, it) },
                )
            }
            composable(R.IMPORT_NEW) { e ->
                NewImportScreen(e.arguments?.getString("id")?.takeIf { it != "-" }, back, created = { id -> nav.navigate(R.of(R.PURCHASE, id)) { popUpTo(R.IMPORT_NEW) { inclusive = true } } })
            }
            composable(R.IMPORT_RECEIVE) { e -> ImportReceiveScreen(e.arguments?.getString("id").orEmpty(), back, done = back) }
            composable(R.IMPORT_EDIT) { e -> EditImportScreen(e.arguments?.getString("id").orEmpty(), back, saved = back) }
            composable(R.SUPPLIER_ACCOUNT) { e -> SupplierAccountScreen(e.arguments?.getString("id").orEmpty(), session, back) }
            composable(R.SMS) { SmsGatewayScreen(back) }
            composable(R.CATEGORIES) { CategoriesScreen(back) }
            composable(R.COUPONS) { CouponsScreen(back) }
            composable(R.PURCHASE_NEW) { NewPurchaseScreen(back, created = { id -> nav.navigate(R.of(R.PURCHASE, id)) { popUpTo(R.PURCHASING) } }) }
            composable(R.PURCHASE) { e -> PurchaseOrderScreen(e.arguments?.getString("id").orEmpty(), session, back, openImportReceive = { open(R.IMPORT_RECEIVE, it) }, editImport = { open(R.IMPORT_EDIT, it) }) }
            composable(R.EXPENSES) { ExpensesScreen(session, back, openExpense = { open(R.EXPENSE, it) }, newExpense = { nav.navigate(R.NEW_EXPENSE) }) }
            composable(R.EXPENSE) { e -> ExpenseDetailScreen(e.arguments?.getString("id").orEmpty(), session, back) }
            composable(R.NEW_EXPENSE) { NewExpenseScreen(back, created = { id -> nav.navigate(R.of(R.EXPENSE, id)) { popUpTo(R.EXPENSES) } }) }
            composable(R.SETTLEMENTS) { SettlementsScreen(session, back) }
            composable(R.REPORTS) { ReportsScreen(session, back) }
            composable(R.CUSTOMERS) {
                CustomersScreen(
                    back,
                    openCustomer = { open(R.CUSTOMER, it) },
                    detailPane = { id, close -> CustomerDetailScreen(id, close, openOrder = { open(R.ORDER, it) }) },
                )
            }
            composable(R.CUSTOMER) { e -> CustomerDetailScreen(e.arguments?.getString("id").orEmpty(), back, openOrder = { open(R.ORDER, it) }) }
            composable(R.MORE) { MoreScreen(session, navigate = { nav.navigate(it) }) }
            composable(R.SERVER) { ServerScreen(back) }
            composable(R.CURRENCY) { CurrencyScreen(session, back) }
            composable(R.POS) {
                PosScreen(
                    session,
                    openBoxes = { nav.navigate(R.CASH_BOXES) },
                    openReturns = { nav.navigate(R.POS_RETURNS) },
                    onLogout = if (tillOnly) ({ container.appScope.launch { container.logout() } }) else null,
                    openPrinters = { nav.navigate(R.PRINTERS) },
                    openShift = { nav.navigate(R.SHIFT) },
                )
            }
            composable(R.SHIFT) { ShiftScreen(session, back) }
            composable(R.AUDIT) { AuditScreen(back) }
            composable(R.DEBTORS) { DebtorsScreen(session, back, openCustomer = { open(R.CUSTOMER, it) }) }
            composable(R.BACKUP) { BackupScreen(back) }
            composable(R.STOCK_COUNTS) { StockCountsScreen(session, back) }
            composable(R.DAILY_SUMMARY) { DailySummaryScreen(back) }
            composable(R.STORE_SETUP) { StoreSetupScreen(back) }
            composable(R.REVIEWS) { ReviewsScreen(back) }
            composable(R.SEARCH) { SearchScreen(session, back, navigate = { nav.navigate(it) }) }
            composable(R.APPEARANCE) { AppearanceScreen(back) }
            composable(R.LAYAWAYS) { LayawaysScreen(session, back, open = { open(R.LAYAWAY, it) }, newLayaway = { nav.navigate(R.LAYAWAY_NEW) }) }
            composable(R.LAYAWAY) { e -> LayawayScreen(e.arguments?.getString("id").orEmpty(), session, back) }
            composable(R.LAYAWAY_NEW) { NewLayawayScreen(back, created = { id -> nav.navigate(R.of(R.LAYAWAY, id)) { popUpTo(R.LAYAWAYS) } }) }
            composable(R.GIFT_CARDS) { GiftCardsScreen(session, back) }
            composable(R.DAMAGES) { DamagesScreen(session, back) }
            composable(R.BULK_PRICE) { BulkPriceScreen(back) }
            composable(R.OFFERS) { OffersScreen(back, openProduct = { open(R.PRODUCT, it) }) }
            composable(R.OWNER_ALERTS) { OwnerAlertsScreen(back) }
            composable(R.EXCEL) { ExcelScreen(session, back) }
            composable(R.CUSTOMER_APP) { CustomerAppSettingsScreen(back) }
            composable(R.ASSISTANT) { AssistantScreen(back, openCustomer = { open(R.CUSTOMER, it) }) }
            composable(R.CAMPAIGNS) { CampaignsScreen(back) }
            composable(R.BEHAVIOR) { BehaviorScreen(back, openCustomer = { open(R.CUSTOMER, it) }, openProduct = { open(R.PRODUCT, it) }) }
            composable(R.POS_RETURNS) { PosReturnScreen(session, back) }
            composable(R.CASH_BOXES) {
                CashBoxesScreen(session, back, openBox = { open(R.CASH_BOX, it) }, openExchange = { nav.navigate(R.EXCHANGE) })
            }
            composable(R.CASH_BOX) { e -> CashBoxScreen(e.arguments?.getString("id").orEmpty(), session, back) }
            composable(R.EXCHANGE) { ExchangeScreen(session, back) }
            composable(R.PROMOTIONS) { PromotionsScreen(session, back, open = { open(R.PROMOTION, it) }) }
            composable(R.PROMOTION) { e -> PromotionEditScreen(e.arguments?.getString("id")?.takeIf { it != "new" }, back) }
            composable(R.TOP_UPS) { TopUpsScreen(back, open = { open(R.TOP_UP, it) }) }
            composable(R.TOP_UP) { e -> TopUpScreen(e.arguments?.getString("id").orEmpty(), session, back) }
            composable(R.BANK_ACCOUNTS) { BankAccountsScreen(session, back) }
            composable(R.STAFF) { StaffScreen(back, open = { open(R.STAFF_EDIT, it) }) }
            composable(R.STAFF_EDIT) { e -> StaffEditScreen(e.arguments?.getString("id")?.takeIf { it != "new" }, back) }
        }
    }
    // First sign-in on this device: a short tour of what this employee can do.
    OnboardingTour(session)
    }
}

/** Android 13+ asks before an app may show notifications; ask once per launch after sign-in. */
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

private fun NavController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * Loading the saved session at launch. If the server cannot be reached (e.g. a
 * stopped Codespace), say so and offer retry, server settings or sign-out
 * instead of spinning forever.
 */
@Composable
private fun RestoringScreen() {
    val container = adminContainer()
    val error by container.session.restoreError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showServer by rememberSaveable { mutableStateOf(false) }
    if (showServer) {
        ServerScreen(onBack = { showServer = false })
        return
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val message = error
        if (message == null) {
            CircularProgressIndicator()
            Text("جارٍ الاتصال بالخادم…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("تعذّر الاتصال بالخادم", style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(container.settings.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { scope.launch { container.session.restore() } }, modifier = Modifier.fillMaxWidth()) { Text("إعادة المحاولة") }
            OutlinedButton(onClick = { showServer = true }, modifier = Modifier.fillMaxWidth()) { Text("إعدادات الخادم") }
            TextButton(onClick = { container.session.onSessionExpired() }) { Text("تسجيل الخروج") }
        }
    }
}

// ----------------------------------------------------------------- login ---

@Composable
private fun LoginScreen() {
    val container = adminContainer()
    val scope = rememberCoroutineScope()
    val saved = remember { container.savedLogin.load() }
    var login by rememberSaveable { mutableStateOf(saved?.login.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(saved?.password.orEmpty()) }
    var remember by rememberSaveable { mutableStateOf(true) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showServer by rememberSaveable { mutableStateOf(false) }

    if (showServer) {
        ServerScreen(onBack = { showServer = false })
        return
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Dejavu", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text("لوحة الإدارة — دخول الموظفين", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = login, onValueChange = { login = it; error = null }, label = { Text("رقم الجوال") }, singleLine = true,
            supportingText = { Text("أو اسم المستخدم") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it; error = null }, label = { Text("كلمة المرور") }, singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "إخفاء" else "إظهار") } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { remember = !remember }) {
            Checkbox(checked = remember, onCheckedChange = { remember = it })
            Text("تذكرني (حفظ رقم الجوال وكلمة المرور على هذا الجهاز)")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    container.session.login(login, password)
                        .onSuccess { container.savedLogin.save(if (remember) SavedLogin(login.trim(), password) else null) }
                        .onFailure {
                            error = if ((it as? ApiException)?.isUnauthorized == true) "رقم الجوال أو كلمة المرور غير صحيحة" else it.userMessage()
                        }
                    busy = false
                }
            },
            enabled = !busy && login.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { if (busy) CircularProgressIndicator(strokeWidth = 2.dp) else Text("دخول") }
        TextButton(onClick = { showServer = true }) { Text("إعدادات الخادم: ${container.settings.baseUrl}") }
    }
}
