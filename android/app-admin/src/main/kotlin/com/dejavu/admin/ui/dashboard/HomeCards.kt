@file:OptIn(ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dejavu.admin.ui.RecentItem
import com.dejavu.admin.ui.search.recentIcon
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.DailySales
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.model.Money
import com.dejavu.core.model.Numerals
import com.dejavu.core.ui.theme.Liquid
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate

// ------------------------------------------------------------- the cards ---

/** Ids of the home cards; stored in [com.dejavu.admin.ui.UiPrefs.homeCards], so never rename one. */
internal object HomeCard {
    const val SEARCH = "search"
    const val BADGES = "badges"
    const val QUICK = "quick"
    const val RECENTS = "recents"
    const val KPI = "stats"
    const val CHART = "chart"
    const val ALERTS = "alerts"
    const val RATES = "rates"
    const val CASH = "cash"
    const val RECEIVABLES = "receivables"
    const val MONTH = "month"
    const val STATUSES = "statuses"

    val titles: Map<String, String> = linkedMapOf(
        SEARCH to "البحث",
        BADGES to "ما يحتاج انتباهك",
        QUICK to "إجراءات سريعة",
        RECENTS to "آخر ما فتحته",
        KPI to "مبيعات اليوم وربح الشهر",
        CHART to "مبيعات آخر 7 أيام",
        ALERTS to "تنبيهات تحتاج إجراء",
        RATES to "أسعار الصرف",
        CASH to "النقدية والبنوك",
        RECEIVABLES to "المستحقات والالتزامات",
        MONTH to "هذا الشهر",
        STATUSES to "الطلبات حسب الحالة",
    )

    /** Cards fed by the dashboard figures (need [Perm.DASHBOARD]). */
    val needDashboard = setOf(KPI, CHART, ALERTS, RATES, CASH, RECEIVABLES, MONTH, STATUSES)
}

/** Who is looking at home decides the default card order. */
internal enum class HomeRole { MANAGER, SALES, STOCK }

internal fun homeRole(s: StaffSession.LoggedIn): HomeRole = when {
    s.can(Perm.DASHBOARD) && (s.can(Perm.REPORTS_FINANCIAL) || s.can(Perm.JOURNAL_READ)) -> HomeRole.MANAGER
    s.can(Perm.POS_SELL) || s.can(Perm.ORDERS_READ) -> HomeRole.SALES
    s.can(Perm.STOCK_READ) || s.can(STOCK_COUNT) || s.can(Perm.PRODUCTS_WRITE) -> HomeRole.STOCK
    else -> HomeRole.MANAGER
}

internal fun defaultCards(role: HomeRole): List<String> = with(HomeCard) {
    when (role) {
        HomeRole.MANAGER -> listOf(SEARCH, BADGES, QUICK, KPI, CHART, ALERTS, RATES, CASH, RECEIVABLES, MONTH, STATUSES, RECENTS)
        HomeRole.SALES -> listOf(SEARCH, KPI, BADGES, QUICK, RECENTS, CHART, ALERTS, STATUSES, RATES, CASH, RECEIVABLES, MONTH)
        HomeRole.STOCK -> listOf(SEARCH, BADGES, QUICK, ALERTS, RECENTS, KPI, CHART, STATUSES, RATES, CASH, RECEIVABLES, MONTH)
    }
}

/** The saved order (cards unknown to it appended in their default place at the end), or the default. */
internal fun cardOrder(saved: List<String>, default: List<String>): List<String> =
    if (saved.isEmpty()) default else saved.filter { it in default }.distinct() + default.filter { it !in saved }

internal const val STOCK_COUNT = "stock.count"

// ------------------------------------------------------------ search pill ---

@Composable
internal fun SearchPill(onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), contentPadding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Lux.GoldDeep)
            Text(
                "ابحث عن منتج، عميل، فاتورة، مورد…",
                style = MaterialTheme.typography.bodyMedium,
                color = Lux.InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ----------------------------------------------------------------- badges ---

internal data class BadgeItem(val label: String, val count: Int, val color: Color, val onClick: () -> Unit)

@Composable
internal fun BadgesCard(items: List<BadgeItem>, loaded: Boolean) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(HomeCard.titles.getValue(HomeCard.BADGES), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text(
                if (loaded) "لا شيء بانتظارك الآن" else "جارٍ التحميل…",
                style = MaterialTheme.typography.bodySmall,
                color = Lux.InkSoft,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { b ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(b.color.copy(alpha = 0.12f))
                            .clickable(onClick = b.onClick)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(Numerals.of("${b.count}"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = b.color)
                        Text(b.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------- quick actions ---

internal data class QuickAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
internal fun QuickActionsCard(actions: List<QuickAction>) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(HomeCard.titles.getValue(HomeCard.QUICK), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            actions.forEach { a ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = a.onClick).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(Lux.GoldBrush), contentAlignment = Alignment.Center) {
                        Icon(a.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
                    }
                    Text(
                        a.label,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- recents ---

@Composable
internal fun RecentsCard(recents: List<RecentItem>, onOpen: (RecentItem) -> Unit, onClear: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(HomeCard.titles.getValue(HomeCard.RECENTS), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("مسح") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recents.forEach { item ->
                AssistChip(
                    onClick = { onOpen(item) },
                    label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(recentIcon(item.kind), contentDescription = null, modifier = Modifier.size(18.dp), tint = Lux.GoldDeep) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ chart ---

private fun dayName(date: String, last: Boolean): String {
    if (last) return "اليوم"
    return when (runCatching { LocalDate.parse(date.take(10)).dayOfWeek }.getOrNull()) {
        DayOfWeek.SATURDAY -> "سبت"
        DayOfWeek.SUNDAY -> "أحد"
        DayOfWeek.MONDAY -> "إثنين"
        DayOfWeek.TUESDAY -> "ثلاثاء"
        DayOfWeek.WEDNESDAY -> "أربعاء"
        DayOfWeek.THURSDAY -> "خميس"
        DayOfWeek.FRIDAY -> "جمعة"
        null -> ""
    }
}

/** Revenue per day of the last week as bars, with the week's total against the week before. */
@Composable
internal fun SalesChartCard(sales: DailySales?, failed: Boolean, onRetry: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        if (sales == null) {
            ChartPlaceholder(failed, onRetry)
        } else {
            SalesChart(sales)
        }
    }
}

@Composable
private fun ChartPlaceholder(failed: Boolean, onRetry: () -> Unit) {
    Column {
        Text(HomeCard.titles.getValue(HomeCard.CHART), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("تعذّر تحميل المبيعات", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        }
    }
}

@Composable
private fun SalesChart(sales: DailySales) {
    Column {
        val current = Money.parse(sales.total)
        val previous = Money.parse(sales.previousTotal)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(HomeCard.titles.getValue(HomeCard.CHART), style = MaterialTheme.typography.titleSmall)
                Text(Money.format(sales.total), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Text("الأسبوع السابق ${Money.format(sales.previousTotal)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
            ChangeTag(current, previous)
        }
        Spacer(Modifier.height(12.dp))
        val values = sales.days.map { Money.parse(it.revenue).toFloat().coerceAtLeast(0f) }
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val today = Liquid.Gold
        val other = Liquid.GoldSoft.copy(alpha = 0.45f)
        val track = Lux.Hairline
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val n = values.size
            if (n == 0) return@Canvas
            val slot = size.width / n
            val barWidth = slot * 0.56f
            val radius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            values.forEachIndexed { i, v ->
                // Oldest first; in right-to-left the first day sits on the right, like the labels below.
                val position = if (rtl) n - 1 - i else i
                val h = ((v / max) * (size.height - 6.dp.toPx())).coerceAtLeast(2.dp.toPx())
                val left = position * slot + (slot - barWidth) / 2f
                drawRoundRect(
                    color = if (i == n - 1) today else other,
                    topLeft = Offset(left, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = radius,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            sales.days.forEachIndexed { i, d ->
                Text(
                    dayName(d.date, last = i == sales.days.lastIndex),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == sales.days.lastIndex) Lux.GoldDeep else Lux.InkSoft,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** Percent change from [previous] to [current]: green arrow up, red arrow down. */
@Composable
private fun ChangeTag(current: BigDecimal, previous: BigDecimal) {
    if (previous.signum() == 0) {
        if (current.signum() > 0) Text("جديد", style = MaterialTheme.typography.labelMedium, color = Liquid.Success)
        return
    }
    val pct = current.subtract(previous).multiply(BigDecimal(100)).divide(previous.abs(), 1, RoundingMode.HALF_UP)
    val up = pct.signum() >= 0
    val color = if (up) Liquid.Success else Liquid.Danger
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (up) "ارتفاع" else "انخفاض",
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Text(Numerals.of("${pct.abs().stripTrailingZeros().toPlainString()}%"), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

// -------------------------------------------------------------- customize ---

/** Show / hide each card and move it up or down; [onSave] gets the full order and the hidden ones. */
@Composable
internal fun CustomizeHomeDialog(
    order: List<String>,
    hidden: Set<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>, Set<String>) -> Unit,
    onReset: () -> Unit,
) {
    var draft by remember { mutableStateOf(order) }
    var off by remember { mutableStateOf(hidden) }
    fun move(i: Int, by: Int) {
        val j = i + by
        if (j < 0 || j > draft.lastIndex) return
        val list = draft.toMutableList()
        val x = list[i]
        list[i] = list[j]
        list[j] = x
        draft = list
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تخصيص الرئيسية") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                draft.forEachIndexed { i, id ->
                    if (i > 0) HorizontalDivider(color = Lux.Hairline)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = id !in off, onCheckedChange = { on -> off = if (on) off - id else off + id })
                        Text(
                            HomeCard.titles[id] ?: id,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            color = if (id in off) Lux.InkSoft else MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "تحريك للأعلى") }
                        IconButton(onClick = { move(i, 1) }, enabled = i < draft.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "تحريك للأسفل") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onReset) { Text("استعادة الافتراضي") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft, off) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
