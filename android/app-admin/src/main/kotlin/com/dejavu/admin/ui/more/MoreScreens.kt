@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.R
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.HomeBadges
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Numerals
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.GlassTopBar
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import kotlinx.coroutines.launch

/** One screen reachable from "المزيد": shown when the employee holds any of [permissions]. */
private data class MenuEntry(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val permissions: List<String>,
    val section: String,
    /** Waiting work shown as a red counter on the tile. */
    val badge: ((HomeBadges) -> Int)? = null,
)

/** A tile as drawn: a [MenuEntry] or one of the always-there device entries. */
private data class MoreTile(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val section: String,
    val caption: String? = null,
    val danger: Boolean = false,
    val badge: Int = 0,
    val onClick: () -> Unit,
)

private const val SALES = "المبيعات والكاشير"
private const val ORDERS = "الطلبات والعملاء"
private const val STOCK = "المخزون والمنتجات"
private const val PURCHASES = "المشتريات والموردون"
private const val FINANCE = "المالية والخزينة"
private const val STORE = "المتجر والتطبيق"
private const val SYSTEM = "النظام والإعدادات"
private val sections = listOf(SALES, ORDERS, STOCK, PURCHASES, FINANCE, STORE, SYSTEM)

private val menu = listOf(
    MenuEntry(R.POS_RETURNS, "مرتجعات واستبدال الكاشير", "إرجاع صنف مباع أو استبداله بمقاس/لون آخر مع تسوية الفارق", Icons.Filled.Refresh, listOf(Perm.POS_SELL), SALES),
    MenuEntry(R.SHIFT, "الوردية", "فتح وإقفال الوردية وعدّ النقد في الدرج", Icons.Filled.DateRange, listOf(Perm.POS_SELL), SALES, badge = { it.openShifts }),
    MenuEntry(R.PROMOTIONS, "العروض والخصومات", "خصم جماعي بنسبة مئوية على منتجات مختارة", Icons.Filled.Favorite, listOf(Perm.PRODUCTS_READ), SALES),
    MenuEntry(R.COUPONS, "أكواد الخصم", "أكواد خصم للعملاء وللكاشير", Icons.Filled.Favorite, listOf("coupons.manage"), SALES),
    MenuEntry(R.DEALS, "عروض الكمية والأطقم", "قطعة وقطعة مجاناً، قطعتين والثالثة مجاناً، طقم بسعر واحد", Icons.Filled.Favorite, listOf(Perm.PRODUCTS_READ), SALES),
    MenuEntry(R.PRE_ORDERS, "الحجز المسبق من الشحنات", "عملاء حجزوا قطعاً من طلبيات في الطريق", Icons.Filled.DateRange, listOf("layaways.manage"), SALES),
    MenuEntry(R.LAYAWAYS, "الحجز بعربون", "حجز قطع لعميل بعربون وإكمال البيع عند السداد", Icons.Filled.DateRange, listOf("layaways.manage"), SALES),
    MenuEntry(R.GIFT_CARDS, "بطاقات الهدايا", "بيع بطاقات الهدايا واستخدامها في حساب العميل", Icons.Filled.Favorite, listOf("giftcards.manage"), SALES),
    MenuEntry(R.BULK_PRICE, "تعديل الأسعار جماعياً", "رفع أو خفض أسعار منتجات أو أقسام كاملة وطباعة الملصقات", Icons.Filled.Edit, listOf(Perm.PRODUCTS_WRITE), SALES),
    MenuEntry(R.OFFERS, "المنتجات المعروضة بخصم", "كل المنتجات التي عليها عرض أو سعر مشطوب", Icons.Filled.Star, listOf(Perm.PRODUCTS_READ), SALES),
    MenuEntry(R.RETURNS, "المرتجعات", "طلبات الإرجاع والاسترداد", Icons.Filled.Refresh, listOf(Perm.RETURNS), ORDERS, badge = { it.returns }),
    MenuEntry(R.CUSTOMERS, "العملاء", "بيانات العملاء وأرصدتهم", Icons.Filled.Face, listOf(Perm.CUSTOMERS_READ), ORDERS),
    MenuEntry(R.DEBTORS, "العملاء المدينون", "الديون المستحقة على العملاء وتذكيرهم برسالة", Icons.Filled.Face, listOf(Perm.CUSTOMERS_READ), ORDERS),
    MenuEntry(R.REVIEWS, "آراء العملاء", "مراجعة تقييمات المنتجات ونشرها أو إخفاؤها", Icons.Filled.Star, listOf(Perm.PRODUCTS_WRITE), ORDERS, badge = { it.reviews }),
    MenuEntry(R.TOP_UPS, "طلبات شحن حسابات العملاء", "سندات الإيداع والموافقة أو الرفض", Icons.Filled.Email, listOf(Perm.WALLET_MANAGE), ORDERS, badge = { it.topUps }),
    MenuEntry(R.PRODUCTS, "المنتجات", "الأسعار والنشر والمقاسات", Icons.Filled.Star, listOf(Perm.PRODUCTS_READ), STOCK),
    MenuEntry(R.BRANDS, "الماركات", "إضافة الماركات وشعاراتها وتصفح منتجاتها", Icons.Filled.AccountBox, listOf(Perm.PRODUCTS_READ), STOCK),
    MenuEntry(R.CATEGORIES, "الأقسام", "تعديل أسماء الأقسام وإظهارها أو إخفاؤها في التطبيق", Icons.AutoMirrored.Filled.List, listOf(Perm.CATEGORIES_WRITE), STOCK),
    MenuEntry(R.STOCK_COUNTS, "جرد المخزون", "عدّ القطع على الرفوف وترحيل فروقات الجرد", Icons.Filled.CheckCircle, listOf("stock.count"), STOCK),
    MenuEntry(R.DAMAGES, "البضاعة التالفة", "إخراج القطع التالفة من المخزون مع صورة وسبب", Icons.Filled.Warning, listOf("stock.adjust"), STOCK),
    MenuEntry(R.EXCEL, "Excel: تصدير واستيراد", "تصدير القوائم والتقارير وإدخال المنتجات من ملف", Icons.AutoMirrored.Filled.List, listOf(Perm.PRODUCTS_READ, Perm.CUSTOMERS_READ, Perm.REPORTS_SALES), STOCK),
    MenuEntry(R.PURCHASING, "المشتريات", "أوامر الشراء والموردين", Icons.Filled.ShoppingCart, listOf(Perm.PURCHASES_READ), PURCHASES),
    MenuEntry(R.SUPPLIERS, "الموردون والاستيراد", "عمولة الموردين، البضائع المعلقة، طلبيات الاستيراد والـ CBM", Icons.Filled.Person, listOf(Perm.PURCHASES_READ), PURCHASES),
    MenuEntry(R.EXPENSES, "المصروفات", "تسجيل واعتماد وسداد المصروفات", Icons.AutoMirrored.Filled.List, listOf(Perm.EXPENSES_READ), FINANCE),
    MenuEntry(R.SETTLEMENTS, "توريدات الشحن", "تحصيل الدفع عند الاستلام من شركات الشحن", Icons.Filled.DateRange, listOf(Perm.PAYMENTS_READ), FINANCE),
    MenuEntry(R.REPORTS, "التقارير", "الأرباح والخسائر والمركز المالي والمبيعات والمخزون", Icons.Filled.Info, listOf(Perm.REPORTS_FINANCIAL, Perm.REPORTS_SALES, "reports.inventory"), FINANCE),
    MenuEntry(R.CASH_BOXES, "الصناديق والخزائن", "صناديق الكاشير، الخزائن، البنوك والتحويل بينها", Icons.Filled.Lock, listOf(Perm.POS_SELL, Perm.CASHBOXES_MANAGE), FINANCE),
    MenuEntry(R.EXCHANGE, "صرف العملات", "شراء وبيع العملات بين الصناديق وأرباح فروق العملة", Icons.Filled.Refresh, listOf(Perm.CASHBOXES_MANAGE), FINANCE),
    MenuEntry(R.CURRENCY, "العملات وأسعار الصرف", "إضافة العملات وتعديل أسعارها وإظهارها للعملاء", Icons.Filled.Info, listOf(Perm.ORDERS_READ, Perm.SETTINGS_MANAGE), FINANCE),
    MenuEntry(R.STORE_SETUP, "إعدادات تطبيق العملاء", "طرق الدفع والمحافظات والدفع عند الاستلام", Icons.Filled.Settings, listOf(Perm.SETTINGS_MANAGE), STORE),
    MenuEntry(R.CUSTOMER_APP, "صفحة المتجر", "عن المتجر وتسجيل الدخول قبل التصفح", Icons.Filled.Home, listOf(Perm.SETTINGS_MANAGE), STORE),
    MenuEntry(R.ASSISTANT, "مساعد الدردشة", "تعليمات البوت ورسالة الترحيب ومحادثات العملاء", Icons.Filled.Email, listOf("chat.manage"), STORE),
    MenuEntry(R.CAMPAIGNS, "إرسال إشعار أو رسالة للعملاء", "إشعار بصورة ورابط لمنتج أو قسم، لكل العملاء أو فئة منهم", Icons.Filled.Notifications, listOf("notifications.send"), STORE),
    MenuEntry(R.WEEKLY, "ملخص الأسبوع الذكي", "ماذا حدث في المبيعات ولماذا، مع اقتراحات", Icons.Filled.Info, listOf("dashboard.view"), STORE),
    MenuEntry(R.BEHAVIOR, "سلوك العملاء", "الوقت في التطبيق، أكثر المنتجات مشاهدة وما يبحثون عنه", Icons.Filled.Search, listOf(Perm.REPORTS_SALES), STORE),
    MenuEntry(R.BANK_ACCOUNTS, "الحسابات البنكية للمتجر", "تظهر للعملاء عند شحن الرصيد", Icons.Filled.Home, listOf(Perm.SETTINGS_MANAGE), STORE),
    MenuEntry(R.SMS, "الرسائل النصية (SMS)", "إرسال الرسائل من شريحة هذا الهاتف ورمز التحقق للعملاء", Icons.Filled.Phone, listOf(Perm.SETTINGS_MANAGE), STORE, badge = { it.smsWaiting }),
    MenuEntry(R.DAILY_SUMMARY, "الملخص اليومي", "رسالة مسائية بأرقام اليوم إلى هاتف المالك", Icons.Filled.Email, listOf(Perm.SETTINGS_MANAGE), STORE),
    MenuEntry(R.STAFF, "الموظفون والصلاحيات", "إضافة الموظفين، كلمات المرور، الصلاحيات والصناديق", Icons.Filled.Person, listOf(Perm.USERS_MANAGE), SYSTEM),
    MenuEntry(R.AUDIT, "سجل العمليات", "من أضاف أو عدّل أو حذف ماذا ومتى", Icons.AutoMirrored.Filled.List, listOf("audit.read"), SYSTEM),
    MenuEntry(R.OWNER_ALERTS, "تنبيهات المالك", "إشعار فوري عند حذف فاتورة أو عجز في الصندوق أو تعديل سعر", Icons.Filled.Notifications, listOf(Perm.SETTINGS_MANAGE), SYSTEM),
    MenuEntry(R.BACKUP, "النسخ الاحتياطي", "تنزيل نسخة من كل البيانات، نسخ يومي تلقائي واستعادة", Icons.Filled.Lock, listOf("system.backup"), SYSTEM),
)

private val LogoutBrush = Brush.linearGradient(listOf(Color(0xFFE57373), Color(0xFFC62828)))

@Composable
fun MoreScreen(session: StaffSession.LoggedIn, navigate: (String) -> Unit) {
    val container = adminContainer()
    val profile = session.profile
    val badges by container.badges.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    val tiles = buildList {
        menu.filter { e -> e.permissions.any(session::can) }.forEach { e ->
            val count = badges?.let { b -> e.badge?.invoke(b) } ?: 0
            add(MoreTile(e.route, e.title, e.subtitle, e.icon, e.section, badge = count) { navigate(e.route) })
        }
        add(MoreTile("appearance", "المظهر", "الوضع الداكن وحجم الخط وشكل الأرقام", Icons.Filled.Edit, SYSTEM) { navigate(R.APPEARANCE) })
        add(MoreTile("tour", "جولة تعريفية", "إعادة عرض شرح أقسام التطبيق", Icons.Filled.Info, SYSTEM) { container.uiPrefs.setTourSeen(false) })
        add(MoreTile("printers", "الطابعات", "طابعات الفواتير والباركود", Icons.Filled.Build, SYSTEM, caption = "الفواتير والباركود") { navigate(R.PRINTERS) })
        add(
            MoreTile("server", "الخادم", "عنوان الخادم واختبار الاتصال", Icons.Filled.Settings, SYSTEM, caption = container.settings.baseUrl.substringAfter("://")) {
                navigate(R.SERVER)
            },
        )
        if (session.can(Perm.SETTINGS_MANAGE) && profile?.roles?.any { it.name == "admin" } == true) {
            add(MoreTile("wipe", "تصفير البيانات التجريبية", "حذف بيانات التجربة قبل التشغيل الفعلي", Icons.Filled.Delete, SYSTEM, danger = true) { navigate(R.WIPE) })
        }
    }
    val q = query.trim()
    val shown = if (q.isEmpty()) {
        tiles
    } else {
        tiles.filter { t ->
            t.title.contains(q, ignoreCase = true) || t.section.contains(q, ignoreCase = true) || t.subtitle.contains(q, ignoreCase = true)
        }
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { GlassTopBar(title = { Text("المزيد") }) }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // Three tiles a row on phones, more as the screen widens.
            val columns = ((maxWidth - 32.dp) / 116.dp).toInt().coerceAtLeast(3)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (profile != null) {
                    item(key = "profile", span = { GridItemSpan(maxLineSpan) }) {
                        ProfileCard(profile.name, profile.phone ?: profile.email.orEmpty(), profile.roles.map { it.name })
                    }
                }
                item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("ابحث في القائمة…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "مسح") }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                sections.forEach { section ->
                    val inSection = shown.filter { it.section == section }
                    if (inSection.isNotEmpty()) {
                        item(key = "h:$section", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(section) }
                        items(inSection, key = { "t:${it.key}" }) { t ->
                            MenuTile(t.title, t.icon, caption = t.caption, danger = t.danger, badge = t.badge, onClick = t.onClick)
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "لا يوجد ما يطابق «$q»",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Lux.InkSoft,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
                item(key = "logout", span = { GridItemSpan(maxLineSpan) }) {
                    LogoutTile { confirmLogout = true }
                }
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

/** Red counter of waiting work ("9+" above nine). */
@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(22.dp).clip(CircleShape).background(Liquid.Danger),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 9) Numerals.of("9+") else Numerals.of(count.toString()),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Glass tile: gold circular icon over a two-line title, with an optional red counter. */
@Composable
private fun MenuTile(title: String, icon: ImageVector, caption: String? = null, danger: Boolean = false, badge: Int = 0, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxWidth()) {
        GlassCard(Modifier.fillMaxWidth().height(120.dp), shape = shape, elevation = 10.dp, contentPadding = 0.dp, onClick = onClick) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (danger) LogoutBrush else Lux.GoldBrush),
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
        if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

/** Full-width sign-out row at the bottom of the menu. */
@Composable
private fun LogoutTile(onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(20.dp), elevation = 10.dp, contentPadding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(LogoutBrush),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Text("تسجيل الخروج", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
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
