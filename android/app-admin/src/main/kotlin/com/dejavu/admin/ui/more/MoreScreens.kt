@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.R
import com.dejavu.admin.ui.adminContainer
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BackTopBar
import kotlinx.coroutines.launch

private data class MenuEntry(val route: String, val title: String, val subtitle: String, val icon: ImageVector, val permissions: List<String>)

private val menu = listOf(
    MenuEntry(R.RETURNS, "المرتجعات", "طلبات الإرجاع والاسترداد", Icons.Filled.Refresh, listOf(Perm.RETURNS)),
    MenuEntry(R.PRODUCTS, "المنتجات", "الأسعار والنشر والمقاسات", Icons.Filled.Star, listOf(Perm.PRODUCTS_READ)),
    MenuEntry(R.PURCHASING, "المشتريات", "أوامر الشراء والموردين", Icons.Filled.ShoppingCart, listOf(Perm.PURCHASES_READ)),
    MenuEntry(R.EXPENSES, "المصروفات", "تسجيل واعتماد وسداد المصروفات", Icons.AutoMirrored.Filled.List, listOf(Perm.EXPENSES_READ)),
    MenuEntry(R.SETTLEMENTS, "توريدات الشحن", "تحصيل الدفع عند الاستلام من شركات الشحن", Icons.Filled.DateRange, listOf(Perm.PAYMENTS_READ)),
    MenuEntry(R.REPORTS, "التقارير", "الأرباح والخسائر والمركز المالي والمبيعات", Icons.Filled.Info, listOf(Perm.REPORTS_FINANCIAL, Perm.REPORTS_SALES)),
    MenuEntry(R.CUSTOMERS, "العملاء", "بيانات العملاء وأرصدتهم", Icons.Filled.Face, listOf(Perm.CUSTOMERS_READ)),
    MenuEntry(R.CASH_BOXES, "الصناديق والخزائن", "صناديق الكاشير، الخزائن، البنوك والتحويل بينها", Icons.Filled.Lock, listOf(Perm.POS_SELL, Perm.CASHBOXES_MANAGE)),
    MenuEntry(R.EXCHANGE, "صرف العملات", "شراء وبيع العملات بين الصناديق وأرباح فروق العملة", Icons.Filled.Refresh, listOf(Perm.CASHBOXES_MANAGE)),
    MenuEntry(R.TOP_UPS, "طلبات شحن حسابات العملاء", "سندات الإيداع والموافقة أو الرفض", Icons.Filled.Email, listOf(Perm.WALLET_MANAGE)),
    MenuEntry(R.PROMOTIONS, "العروض والخصومات", "خصم جماعي بنسبة مئوية على منتجات مختارة", Icons.Filled.Favorite, listOf(Perm.PRODUCTS_READ)),
    MenuEntry(R.CURRENCY, "العملات وأسعار الصرف", "إضافة العملات وتعديل أسعارها وإظهارها للعملاء", Icons.Filled.Info, listOf(Perm.ORDERS_READ, Perm.SETTINGS_MANAGE)),
    MenuEntry(R.BANK_ACCOUNTS, "الحسابات البنكية للمتجر", "تظهر للعملاء عند شحن الرصيد", Icons.Filled.Home, listOf(Perm.SETTINGS_MANAGE)),
)

@Composable
fun MoreScreen(session: StaffSession.LoggedIn, navigate: (String) -> Unit) {
    val container = adminContainer()
    val profile = session.profile
    var confirmLogout by remember { mutableStateOf(false) }
    val entries = menu.filter { e -> e.permissions.any(session::can) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { CenterAlignedTopAppBar(title = { Text("المزيد") }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (profile != null) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        InfoCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column {
                                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                    Text(profile.email, style = MaterialTheme.typography.bodySmall)
                                    if (profile.roles.isNotEmpty()) {
                                        Text(profile.roles.joinToString("، ") { it.name }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            items(entries, key = { it.route }) { e -> MenuRow(e.title, e.subtitle, e.icon) { navigate(e.route) } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { MenuRow("الخادم", container.settings.baseUrl, Icons.Filled.Settings) { navigate(R.SERVER) } }
            item {
                ListItem(
                    headlineContent = { Text("تسجيل الخروج", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { confirmLogout = true },
                )
            }
        }
    }

    if (confirmLogout) {
        ConfirmDialog("تسجيل الخروج", "هل تريد الخروج من حسابك على هذا الجهاز؟", "خروج", onDismiss = { confirmLogout = false }) {
            container.appScope.launch { container.logout() }
        }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 1) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// ----------------------------------------------------------------- server ---

@Composable
fun ServerScreen(onBack: () -> Unit) {
    val container = adminContainer()
    val settings = container.settings
    var url by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("الخادم", onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("عنوان خادم Dejavu الذي يتصل به التطبيق.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("رابط الخادم") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "• المحاكي على نفس الجهاز: http://10.0.2.2:3000\n• GitHub Codespaces: رابط المنفذ 3000 بعد جعله Public",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    settings.setBaseUrl(url)
                    url = settings.baseUrl
                    status = "جارٍ الاختبار…"
                    scope.launch {
                        status = container.session.ping().fold({ "✓ متصل بنجاح" }, { "✗ ${it.userMessage()}" })
                        container.session.restore()
                    }
                }) { Text("حفظ واختبار") }
                OutlinedButton(onClick = { url = settings.defaultBaseUrl }) { Text("الافتراضي") }
            }
            status?.let { Text(it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("حسابات التجربة موضحة في docs/TRY_IT.md", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
