@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.customerapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.BehaviorReport
import com.dejavu.core.admin.CustomerAppRepository
import com.dejavu.core.admin.durationLabel
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val PERIODS = listOf(7, 30, 90)

class BehaviorViewModel(private val repo: CustomerAppRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<BehaviorReport>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _days = MutableStateFlow(30)
    val days = _days.asStateFlow()
    val refresher = Refresher()
    private var started = false

    private fun from(days: Int): String = LocalDate.now().minusDays((days - 1).toLong()).toString()

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun select(days: Int) {
        if (days == _days.value) return
        _days.value = days
        _state.value = Load.Loading
        load()
    }

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        val wanted = _days.value
        viewModelScope.launch {
            val res = repo.behavior(from(wanted), null)
            if (wanted == _days.value) _state.value = res.toLoad()
        }
    }

    fun refresh() = refresher.run(viewModelScope) {
        val wanted = _days.value
        val res = repo.behavior(from(wanted), null)
        if (wanted == _days.value) res.orSay()?.let { _state.value = Load.Ready(it) }
    }
}

@Composable
internal fun BehaviorContent(onBack: () -> Unit, openCustomer: (String) -> Unit, openProduct: (String) -> Unit) {
    val vm = adminViewModel(key = "behavior") { BehaviorViewModel(it.customerApp) }
    val state by vm.state.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.start() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("سلوك العملاء", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PERIODS.forEach { n ->
                    FilterChip(selected = n == days, onClick = { vm.select(n) }, label = { Text(if (n <= 10) "آخر $n أيام" else "آخر $n يوماً") })
                }
            }
            LoadContent(state, onRetry = vm::load) { r ->
                RefreshList(refreshing, onRefresh = vm::refresh) {
                    val empty = r.totals.sessions == 0 && r.topViewed.isEmpty() && r.topSearches.isEmpty()
                    if (empty) {
                        EmptyList(Icons.Filled.Info, "لا توجد بيانات في هذه الفترة", "ستظهر البيانات بعد تحديث العملاء للتطبيق")
                    } else {
                        BehaviorList(r, days, openCustomer, openProduct)
                    }
                }
            }
        }
    }
}

@Composable
private fun BehaviorList(r: BehaviorReport, period: Int, openCustomer: (String) -> Unit, openProduct: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "totals") { TotalsCard(r.totals) }
        if (r.perDay.isNotEmpty()) item(key = "per-day") { PerDayCard(r.perDay, period) }
        if (r.hours.any { it.sessions > 0 }) item(key = "hours") { HoursCard(r.hours) }

        if (r.topCustomers.isNotEmpty()) {
            item(key = "h-customers") { Header("أكثر العملاء استخداماً") }
            items(r.topCustomers, key = { "c:" + it.customer.id }) { c ->
                GlassCard(Modifier.fillMaxWidth(), contentPadding = 12.dp, onClick = { openCustomer(c.customer.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.customer.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    c.customer.phone.takeIf { it.isNotBlank() },
                                    c.lastSeen?.let { "آخر ظهور ${formatDateTime(it)}" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Lux.InkSoft,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(durationLabel(c.seconds), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                            Text("${c.sessions} مرة", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                        }
                    }
                }
            }
        }

        viewedSection("viewed", "أكثر المنتجات مشاهدة", r.topViewed, openProduct)
        viewedSection("not-bought", "شوهد كثيراً ولم يُبع", r.viewedNotBought, openProduct)
        viewedSection("out", "شوهد وهو نافد", r.viewedOutOfStock, openProduct, showStock = true)

        if (r.topSearches.isNotEmpty()) {
            item(key = "h-search") { Header("أكثر ما بحثوا عنه") }
            item(key = "search") { SearchCard(r.topSearches, noResults = false) }
        }
        if (r.noResultSearches.isNotEmpty()) {
            item(key = "h-noresult") { Header("بحثوا ولم يجدوا") }
            item(key = "noresult") { SearchCard(r.noResultSearches, noResults = true) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.viewedSection(
    key: String,
    title: String,
    list: List<BehaviorReport.Viewed>,
    openProduct: (String) -> Unit,
    showStock: Boolean = false,
) {
    if (list.isEmpty()) return
    item(key = "h-$key") { Header(title) }
    items(list, key = { "$key:" + it.productId }) { v -> ViewedCard(v, showStock) { openProduct(v.productId) } }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun TotalsCard(t: BehaviorReport.Totals) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("الوقت في التطبيق", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
        Text(durationLabel(t.seconds), style = MaterialTheme.typography.headlineSmall, color = Lux.GoldDeep)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Stat("الجلسات", "${t.sessions}")
            Stat("الأجهزة", "${t.devices}")
            Stat("العملاء المسجلون", "${t.customers}")
            Stat("متوسط الجلسة", durationLabel(t.avgSessionSeconds.toLong()))
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.widthIn(min = 72.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
    }
}

@Composable
private fun Bars(values: List<Float>, highlight: Int? = null) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val strong = Liquid.Gold
    val other = Liquid.GoldSoft.copy(alpha = 0.5f)
    val track = Lux.Hairline
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val slot = size.width / n
        val barWidth = (slot * 0.6f).coerceAtLeast(1f)
        val r = (barWidth / 2f).coerceAtMost(6.dp.toPx())
        drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        values.forEachIndexed { i, v ->
            if (v <= 0f) return@forEachIndexed
            val position = if (rtl) n - 1 - i else i
            val h = ((v / max) * (size.height - 4.dp.toPx())).coerceAtLeast(2.dp.toPx())
            val left = position * slot + (slot - barWidth) / 2f
            drawRoundRect(
                color = if (i == highlight) strong else other,
                topLeft = Offset(left, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(r, r),
            )
        }
    }
}

@Composable
private fun PerDayCard(perDay: List<BehaviorReport.Day>, period: Int) {
    val days = remember(perDay, period) { fillDays(perDay, period) }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("الوقت في التطبيق يومياً", style = MaterialTheme.typography.titleSmall)
        val peak = days.maxByOrNull { it.seconds }
        if (peak != null && peak.seconds > 0) {
            Text(
                "أعلى يوم ${shortDate(peak.date)}: ${durationLabel(peak.seconds)} · ${peak.users} مستخدم",
                style = MaterialTheme.typography.bodySmall,
                color = Lux.InkSoft,
            )
        }
        Bars(days.map { it.seconds.toFloat() }, highlight = peak?.let { days.indexOf(it) }?.takeIf { it >= 0 })
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shortDate(days.first().date), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            Text(shortDate(days.last().date), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
        }
    }
}

/** One entry per day of the period (days without use count as zero). */
private fun fillDays(perDay: List<BehaviorReport.Day>, period: Int): List<BehaviorReport.Day> {
    val byDate = perDay.associateBy { it.date }
    val start = LocalDate.now().minusDays((period - 1).toLong())
    val filled = (0 until period).map { i ->
        val d = start.plusDays(i.toLong()).toString()
        byDate[d] ?: BehaviorReport.Day(date = d, seconds = 0L, users = 0, sessions = 0)
    }
    // Keep days the server has outside this window (time-zone edges) too.
    val extra = perDay.filter { it.date !in filled.map { f -> f.date } }
    return (filled + extra).sortedBy { it.date }
}

/** "2026-10-20" (or a full ISO time) as a short date. */
private fun shortDate(date: String): String {
    val iso = if (date.length == 10) date + "T12:00:00Z" else date
    return formatDate(iso).ifBlank { date }
}

@Composable
private fun HoursCard(hours: List<BehaviorReport.Hour>) {
    val byHour = (0 until 24).map { h -> hours.firstOrNull { it.hour == h }?.sessions ?: 0 }
    val peak = byHour.indices.maxByOrNull { byHour[it] }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("أوقات الدخول خلال اليوم", style = MaterialTheme.typography.titleSmall)
        if (peak != null && byHour[peak] > 0) {
            Text("الذروة الساعة $peak:00", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        Bars(byHour.map { it.toFloat() }, highlight = peak)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf("0", "6", "12", "18", "23").forEachIndexed { i, label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = Lux.InkSoft,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        4 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                )
            }
        }
    }
}

@Composable
private fun ViewedCard(v: BehaviorReport.Viewed, showStock: Boolean, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 12.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductImage(v.imageUrl, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentDescription = v.name)
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${v.views} مشاهدة · ${v.viewers} شخص · متوسط ${durationLabel(v.avgSeconds.toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
                Text(
                    "بيع ${v.sold} · التحويل ${String.format(Locale.US, "%.1f", v.conversion)}%" +
                        if (showStock || v.available <= 0) " · المتوفر ${v.available}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (v.available <= 0) Warning else Lux.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun SearchCard(list: List<BehaviorReport.Search>, noResults: Boolean) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
        list.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Lux.Hairline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.query, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (noResults) "${s.count} مرة" else "${s.count} مرة · ${s.results} نتيجة",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (noResults) Warning else Lux.InkSoft,
                )
            }
        }
    }
}
