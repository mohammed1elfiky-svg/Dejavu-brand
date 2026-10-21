@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui

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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.dejavu.admin.ui.purchasing.PurchasingScreen
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

    fun of(pattern: String, id: String) = pattern.replace("{id}", Uri.encode(id))
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val permission: String?)

private val tabs = listOf(
    Tab(R.DASHBOARD, "الرئيسية", Icons.Filled.Home, Perm.DASHBOARD),
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
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
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
    val visibleTabs = tabs.filter { it.permission == null || session.can(it.permission) }
    val open = { pattern: String, id: String -> nav.navigate(R.of(pattern, id)) }
    val back: () -> Unit = { nav.popBackStack() }
    val container = adminContainer()
    val openOrderId by container.openOrderId.collectAsStateWithLifecycle()
    LaunchedEffect(openOrderId) {
        val id = openOrderId ?: return@LaunchedEffect
        if (session.can(Perm.ORDERS_READ)) nav.navigate(R.of(R.ORDER, id)) { launchSingleTop = true }
        container.orderOpened()
    }
    if (container.pushEnabled) AskNotificationPermission()

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            if (visibleTabs.any { it.route == route }) {
                NavigationBar {
                    visibleTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = visibleTabs.first().route, modifier = Modifier.padding(padding)) {
            composable(R.DASHBOARD) {
                DashboardScreen(
                    openOrders = { nav.switchTab(R.ORDERS) },
                    openReturns = { nav.navigate(R.RETURNS) },
                    openStock = { nav.switchTab(R.STOCK) },
                    openSettlements = { nav.navigate(R.SETTLEMENTS) },
                )
            }
            composable(R.ORDERS) { OrdersScreen(openOrder = { open(R.ORDER, it) }) }
            composable(R.ORDER) { e -> OrderDetailScreen(e.arguments?.getString("id").orEmpty(), session, back, openReturn = { open(R.RETURN, it) }) }
            composable(R.RETURNS) { ReturnsScreen(back, openReturn = { open(R.RETURN, it) }) }
            composable(R.RETURN) { e -> ReturnDetailScreen(e.arguments?.getString("id").orEmpty(), session, back) }
            composable(R.STOCK) { StockScreen(session, openProducts = { nav.navigate(R.PRODUCTS) }) }
            composable(R.PRODUCTS) {
                ProductsScreen(
                    back,
                    openProduct = { open(R.PRODUCT, it) },
                    newProduct = if (session.can(Perm.PRODUCTS_WRITE)) ({ nav.navigate(R.PRODUCT_NEW) }) else null,
                )
            }
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
            composable(R.PURCHASING) { PurchasingScreen(back, openOrder = { open(R.PURCHASE, it) }) }
            composable(R.PURCHASE) { e -> PurchaseOrderScreen(e.arguments?.getString("id").orEmpty(), session, back) }
            composable(R.EXPENSES) { ExpensesScreen(session, back, openExpense = { open(R.EXPENSE, it) }, newExpense = { nav.navigate(R.NEW_EXPENSE) }) }
            composable(R.EXPENSE) { e -> ExpenseDetailScreen(e.arguments?.getString("id").orEmpty(), session, back) }
            composable(R.NEW_EXPENSE) { NewExpenseScreen(back, created = { id -> nav.navigate(R.of(R.EXPENSE, id)) { popUpTo(R.EXPENSES) } }) }
            composable(R.SETTLEMENTS) { SettlementsScreen(session, back) }
            composable(R.REPORTS) { ReportsScreen(session, back) }
            composable(R.CUSTOMERS) { CustomersScreen(back, openCustomer = { open(R.CUSTOMER, it) }) }
            composable(R.CUSTOMER) { e -> CustomerDetailScreen(e.arguments?.getString("id").orEmpty(), back, openOrder = { open(R.ORDER, it) }) }
            composable(R.MORE) { MoreScreen(session, navigate = { nav.navigate(it) }) }
            composable(R.SERVER) { ServerScreen(back) }
            composable(R.CURRENCY) { CurrencyScreen(session, back) }
        }
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

// ----------------------------------------------------------------- login ---

@Composable
private fun LoginScreen() {
    val container = adminContainer()
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
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
            value = email, onValueChange = { email = it; error = null }, label = { Text("البريد الإلكتروني") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it; error = null }, label = { Text("كلمة المرور") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    container.session.login(email, password).onFailure {
                        error = if ((it as? ApiException)?.isUnauthorized == true) "البريد الإلكتروني أو كلمة المرور غير صحيحة" else it.userMessage()
                    }
                    busy = false
                }
            },
            enabled = !busy && email.contains('@') && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { if (busy) CircularProgressIndicator(strokeWidth = 2.dp) else Text("دخول") }
        TextButton(onClick = { showServer = true }) { Text("إعدادات الخادم: ${container.settings.baseUrl}") }
    }
}
