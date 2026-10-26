package com.dejavu.admin.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.OffersRepository
import com.dejavu.core.admin.WeeklySummary
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

class WeeklySummaryViewModel(private val repo: OffersRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<WeeklySummary>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _regenerating = MutableStateFlow(false)
    val regenerating = _regenerating.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.weeklySummary(false).toLoad() }
    }

    /** Writes the summary again from today's figures. */
    fun regenerate() {
        if (_regenerating.value) return
        _regenerating.value = true
        viewModelScope.launch {
            try {
                repo.weeklySummary(true).orSay()?.let {
                    _state.value = Load.Ready(it)
                    say("تم تحديث الملخص")
                }
            } finally {
                _regenerating.value = false
            }
        }
    }
}

/** The weekly summary of the business with suggestions. */
@Composable
fun WeeklySummaryScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "weekly-summary") { WeeklySummaryViewModel(it.offers) }
    val state by vm.state.collectAsStateWithLifecycle()
    val regenerating by vm.regenerating.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("ملخص الأسبوع", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { s ->
            SummaryContent(s, regenerating, vm::regenerate, Modifier.padding(padding))
        }
    }
}

@Composable
private fun SummaryContent(s: WeeklySummary, regenerating: Boolean, onRegenerate: () -> Unit, modifier: Modifier) {
    val st = s.stats
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "الفترة: ${formatDate(st.from)} – ${formatDate(st.to)}",
                style = MaterialTheme.typography.bodySmall,
                color = Lux.InkSoft,
            )
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الملخص", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (s.byAi) {
                        GoldPill {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("كُتب بالذكاء الاصطناعي", color = Color.White, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(s.text.trim(), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.size(8.dp))
                Text("أُعدّ في ${formatDateTime(s.generatedAt)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
        }
        if (!s.byAi) {
            item {
                GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = Lux.GoldDeep)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "أضف مفتاح Gemini في الخادم ليكتب الذكاء الاصطناعي الملخص مع الاقتراحات",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onRegenerate, enabled = !regenerating, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (regenerating) "جارٍ التحديث…" else "تحديث الآن")
                }
                if (regenerating) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RevenueCard(st, Modifier.weight(1f))
                FigureCard(
                    title = "الطلبات",
                    value = "${st.orders}",
                    lines = listOf("الكاشير ${st.posOrders} · التطبيق ${st.appOrders}", "الأسبوع السابق ${st.previousOrders}"),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FigureCard("عملاء جدد", "${st.newCustomers}", emptyList(), Modifier.weight(1f))
                FigureCard("المرتجعات", "${st.returns}", emptyList(), Modifier.weight(1f))
            }
        }
        if (st.topProducts.isNotEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("الأكثر مبيعاً", style = MaterialTheme.typography.titleSmall)
                    st.topProducts.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep, modifier = Modifier.width(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.units} قطعة · متبقٍ ${t.available}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            }
                            Text(Money.formatBase(t.revenue), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        if (st.runningOut.isNotEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("قارب على النفاد", style = MaterialTheme.typography.titleSmall, color = Warning)
                    st.runningOut.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val variant = listOfNotNull(r.size?.let { "مقاس $it" }, r.color).joinToString(" · ")
                                Text(
                                    listOfNotNull(variant.ifBlank { null }, "بيع ${r.sold}").joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Lux.InkSoft,
                                )
                            }
                            Text(
                                "متبقٍ ${r.available}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (r.available <= 0) MaterialTheme.colorScheme.error else Warning,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RevenueCard(st: WeeklySummary.Stats, modifier: Modifier) {
    GlassCard(modifier, elevation = 6.dp, contentPadding = 12.dp) {
        Text("الإيرادات", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
        Text(Money.formatBase(st.revenue), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Lux.GoldDeep)
        val change = st.changePercent
        if (change != null) {
            val up = change >= 0
            val color = if (up) Success else MaterialTheme.colorScheme.error
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (up) "ارتفاع" else "انخفاض",
                    tint = color,
                    modifier = Modifier.size(18.dp),
                )
                Text("${"%.1f".format(java.util.Locale.US, abs(change))}%", style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
        Text("الأسبوع السابق ${Money.formatBase(st.previousRevenue)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
    }
}

@Composable
private fun FigureCard(title: String, value: String, lines: List<String>, modifier: Modifier) {
    GlassCard(modifier, elevation = 6.dp, contentPadding = 12.dp) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
    }
}
