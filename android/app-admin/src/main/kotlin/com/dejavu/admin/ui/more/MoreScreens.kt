@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.more

import com.dejavu.core.ui.theme.GlassTopBar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
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
import com.dejavu.admin.ui.R
import com.dejavu.admin.ui.adminContainer
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BackTopBar
import kotlinx.coroutines.launch

private data class MenuEntry(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val permissions: List<String>,
    val section: String,
)

private const val SALES = "المبيعات"
private const val STOCK = "المخزون والمشتريات"
private const val FINANCE = "المالية"
private const val SETTINGS = "الإعدادات"
private val sections = listOf(SALES, STOCK, FINANCE, SETTINGS)

private val menu = listOf(
    MenuEntry(R.RETURNS, "المرتجعات", "طلبات الإرجاع والاسترداد", Icons.Filled.Refresh, listOf(Perm.RETURNS), SALES),
    MenuEntry(R.POS_RETURNS, "مرتجعات واستبدال الكاشير", "إرجاع صنف مباع أو استبداله بمقاس/لون آخر مع تسوية الفارق", Icons.Filled.Refresh, listOf(Perm.POS_SELL), SALES),
    MenuEntry(R.CUSTOMERS, "العملاء", "بيانات العملاء وأرصدتهم", Icons.Filled.Face, listOf(Perm.CUSTOMERS_READ), SALES),
    MenuEntry(R.PROMOTIONS, "العروض والخصومات", "خصم جماعي بنسبة مئوية على منتجات مختارة", Icons.Filled.Favorite, listOf(Perm.PRODUCTS_READ), SALES),
    MenuEntry(R.TOP_UPS, "طلبات شحن حسابات العملاء", "سندات الإيداع والموافقة أو الرفض", Icons.Filled.Email, listOf(Perm.WALLET_MANAGE), SALES),
    MenuEntry(R.PRODUCTS, "المنتجات", "الأسعار والنشر والمقاسات", Icons.Filled.Star, listOf(Perm.PRODUCTS_READ), STOCK),
    MenuEntry(R.BRANDS, "الماركات", "إضافة الماركات وشعاراتها وتصفح منتجاتها", Icons.Filled.AccountBox, listOf(Perm.PRODUCTS_READ), STOCK),
    MenuEntry(R.SUPPLIERS, "الموردون والاستيراد", "عمولة الموردين، البضائع المعلقة، طلبيات الاستيراد والـ CBM", Icons.Filled.Person, listOf(Perm.PURCHASES_READ), STOCK),
    MenuEntry(R.PURCHASING, "المشتريات", "أوامر الشراء والموردين", Icons.Filled.ShoppingCart, listOf(Perm.PURCHASES_READ), STOCK),
    MenuEntry(R.EXPENSES, "المصروفات", "تسجيل واعتماد وسداد المصروفات", Icons.AutoMirrored.Filled.List, listOf(Perm.EXPENSES_READ), FINANCE),
    MenuEntry(R.SETTLEMENTS, "توريدات الشحن", "تحصيل الدفع عند الاستلام من شركات الشحن", Icons.Filled.DateRange, listOf(Perm.PAYMENTS_READ), FINANCE),
    MenuEntry(R.REPORTS, "التقارير", "الأرباح والخسائر والمركز المالي والمبيعات والمخزون", Icons.Filled.Info, listOf(Perm.REPORTS_FINANCIAL, Perm.REPORTS_SALES, "reports.inventory"), FINANCE),
    MenuEntry(R.CASH_BOXES, "الصناديق والخزائن", "صناديق الكاشير، الخزائن، البنوك والتحويل بينها", Icons.Filled.Lock, listOf(Perm.POS_SELL, Perm.CASHBOXES_MANAGE), FINANCE),
    MenuEntry(R.EXCHANGE, "صرف العملات", "شراء وبيع العملات بين الصناديق وأرباح فروق العملة", Icons.Filled.Refresh, listOf(Perm.CASHBOXES_MANAGE), FINANCE),
    MenuEntry(R.CURRENCY, "العملات وأسعار الصرف", "إضافة العملات وتعديل أسعارها وإظهارها للعملاء", Icons.Filled.Info, listOf(Perm.ORDERS_READ, Perm.SETTINGS_MANAGE), FINANCE),
    MenuEntry(R.STAFF, "الموظفون والصلاحيات", "إضافة الموظفين، كلمات المرور، الصلاحيات والصناديق", Icons.Filled.Person, listOf(Perm.USERS_MANAGE), SETTINGS),
    MenuEntry(R.BANK_ACCOUNTS, "الحسابات البنكية للمتجر", "تظهر للعملاء عند شحن الرصيد", Icons.Filled.Home, listOf(Perm.SETTINGS_MANAGE), SETTINGS),
    MenuEntry(R.SMS, "الرسائل النصية (SMS)", "إرسال الرسائل من شريحة هذا الهاتف ورمز التحقق للعملاء", Icons.Filled.Phone, listOf(Perm.SETTINGS_MANAGE), SETTINGS),
)

private val LogoutBrush = Brush.linearGradient(listOf(Color(0xFFE57373), Color(0xFFC62828)))

@Composable
fun MoreScreen(session: StaffSession.LoggedIn, navigate: (String) -> Unit) {
    val container = adminContainer()
    val profile = session.profile
    var confirmLogout by remember { mutableStateOf(false) }
    val entries = menu.filter { e -> e.permissions.any(session::can) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { GlassTopBar(title = { Text("المزيد") }) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (profile != null) {
                item(key = "profile", span = { GridItemSpan(maxLineSpan) }) { ProfileCard(profile.name, profile.phone ?: profile.email.orEmpty(), profile.roles.map { it.name }) }
            }
            sections.forEach { section ->
                val inSection = entries.filter { it.section == section }
                if (inSection.isNotEmpty()) {
                    item(key = "h:$section", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(section) }
                    items(inSection, key = { it.route }) { e -> MenuTile(e.title, e.icon) { navigate(e.route) } }
                }
            }
            if (entries.none { it.section == SETTINGS }) {
                item(key = "h:system", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(SETTINGS) }
            }
            if (session.can(Perm.SETTINGS_MANAGE) && profile?.roles?.any { it.name == "admin" } == true) {
                item(key = "wipe") { MenuTile("تصفير البيانات التجريبية", Icons.Filled.Delete, danger = true) { navigate(R.WIPE) } }
            }
            item(key = "printers") { MenuTile("الطابعات", Icons.Filled.Build, caption = "الفواتير والباركود") { navigate(R.PRINTERS) } }
            item(key = "server") { MenuTile("الخادم", Icons.Filled.Settings, caption = container.settings.baseUrl.substringAfter("://")) { navigate(R.SERVER) } }
            item(key = "logout") { MenuTile("تسجيل الخروج", Icons.AutoMirrored.Filled.ExitToApp, danger = true) { confirmLogout = true } }
        }
    }

    if (confirmLogout) {
        ConfirmDialog("تسجيل الخروج", "هل تريد الخروج من حسابك على هذا الجهاز؟", "خروج", onDismiss = { confirmLogout = false }) {
            container.appScope.launch { container.logout() }
        }
    }
}

@Composable
private fun ProfileCard(name: String, email: String, roles: List<String>) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Lux.GoldBrush),
                contentAlignment = Alignment.Center,
            ) {
                val initial = name.trim().take(1)
                if (initial.isNotEmpty()) {
                    Text(initial, color = Color.White, style = MaterialTheme.typography.titleLarge)
                } else {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = Color.White)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(email, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (roles.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        roles.forEach { r ->
                            GoldPill { Text(r, color = Color.White, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = Lux.GoldDeep,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )
}

/** Glass tile: gold circular icon over a two-line title. */
@Composable
private fun MenuTile(title: String, icon: ImageVector, caption: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    GlassCard(Modifier.fillMaxWidth().height(124.dp), shape = shape, elevation = 10.dp, contentPadding = 0.dp, onClick = onClick) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(if (danger) LogoutBrush else Lux.GoldBrush),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (danger) MaterialTheme.colorScheme.error else Lux.Ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            caption?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
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
