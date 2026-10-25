package com.dejavu.admin.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.model.Numerals
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton

private data class TourPage(val icon: ImageVector, val title: String, val body: String)

/** First-run tour, shown once per device over the app; covers only what [session] may do. */
@Composable
fun OnboardingTour(session: StaffSession.LoggedIn) {
    val prefs = adminContainer().uiPrefs
    val seen by prefs.tourSeen.collectAsStateWithLifecycle()
    if (!seen) {
        val pages = remember(session) { tourPages(session) }
        TourOverlay(pages, onDone = { prefs.setTourSeen(true) })
    }
}

private fun tourPages(session: StaffSession.LoggedIn): List<TourPage> = buildList {
    val name = session.profile?.name?.trim()?.substringBefore(' ').orEmpty()
    add(
        TourPage(
            Icons.Filled.Star,
            if (name.isNotEmpty()) "أهلاً $name" else "أهلاً بك",
            "جولة سريعة على أقسام التطبيق المتاحة لك. يمكنك تخطيها الآن وإعادتها في أي وقت من «المزيد».",
        ),
    )
    if (session.can(Perm.DASHBOARD)) {
        add(
            TourPage(
                Icons.Filled.Home,
                "الرئيسية والبحث",
                "أرقام اليوم والمبيعات وما ينتظر المتابعة في مكان واحد. اضغط زر البحث للوصول بسرعة إلى أي منتج أو عميل أو طلب.",
            ),
        )
    }
    if (session.can(Perm.POS_SELL)) {
        add(
            TourPage(
                Icons.Filled.ShoppingCart,
                "الكاشير والورديات",
                "افتح الوردية أولاً، ثم امسح الباركود أو ابحث عن الصنف لإضافته إلى الفاتورة واختر طريقة الدفع. في آخر اليوم أقفل الوردية وعدّ النقد في الدرج.",
            ),
        )
    }
    if (session.can(Perm.ORDERS_READ)) {
        add(
            TourPage(
                Icons.AutoMirrored.Filled.List,
                "الطلبات",
                "طلبات تطبيق العملاء تصل هنا مع تنبيه. افتح الطلب لتأكيده وتجهيزه وشحنه، وتابع حالته حتى التسليم.",
            ),
        )
    }
    if (session.can(Perm.STOCK_READ)) {
        add(
            TourPage(
                Icons.Filled.CheckCircle,
                "المخزون والجرد",
                "كميات كل صنف بمقاساته وألوانه، والأصناف التي قاربت على النفاد. ومن «جرد المخزون» تعدّ القطع على الرفوف وترحّل الفروقات.",
            ),
        )
    }
    add(
        TourPage(
            Icons.Filled.Notifications,
            "التنبيهات",
            "الدائرة الحمراء على أي قسم تعني أعمالاً تنتظرك، مثل طلب جديد أو طلب شحن رصيد أو رأي عميل للمراجعة.",
        ),
    )
    add(
        TourPage(
            Icons.Filled.Menu,
            "المزيد والمظهر",
            "باقي الأقسام مجمّعة في «المزيد» مع خانة بحث. ومن «المظهر» تختار الوضع الداكن وحجم الخط وشكل الأرقام، ومن «جولة تعريفية» تعيد هذا الشرح.",
        ),
    )
}

@Composable
private fun TourOverlay(pages: List<TourPage>, onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val last = pages.lastIndex
    val current = page.coerceIn(0, last)

    BackHandler { if (current > 0) page = current - 1 else onDone() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // Swallow taps so the screen underneath stays untouched while the tour is open.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Numerals.of("${current + 1} / ${pages.size}"),
                        style = MaterialTheme.typography.labelMedium,
                        color = Lux.InkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    if (current < last) TextButton(onClick = onDone) { Text("تخطي") }
                }
                AnimatedContent(targetState = current, label = "tour") { p ->
                    val item = pages[p]
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 230.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.size(84.dp).clip(CircleShape).background(Lux.GoldBrush),
                            contentAlignment = Alignment.Center,
                        ) { Icon(item.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(42.dp)) }
                        Spacer(Modifier.height(18.dp))
                        Text(item.title, style = MaterialTheme.typography.titleLarge, color = Lux.Ink, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            item.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Lux.InkSoft,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    pages.indices.forEach { i ->
                        val on = i == current
                        Box(
                            Modifier
                                .height(8.dp)
                                .width(if (on) 22.dp else 8.dp)
                                .clip(CircleShape)
                                .background(if (on) Lux.GoldDeep else Lux.InkSoft.copy(alpha = 0.35f)),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (current > 0) {
                        OutlinedButton(onClick = { page = current - 1 }, modifier = Modifier.weight(1f)) { Text("السابق") }
                    }
                    Button(
                        onClick = { if (current < last) page = current + 1 else onDone() },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (current < last) "التالي" else "ابدأ") }
                }
            }
        }
    }
}
