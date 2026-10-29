@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.round9

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.R
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.TodayTasks
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDateTime

class TodayViewModel(repo: com.dejavu.core.admin.Round9Repository) : LoadViewModel<TodayTasks>({ repo.today() })

/** One task line: what, who, when, and where tapping it goes. */
private data class Task(val key: String, val title: String, val subtitle: String, val route: String?)

/** "مهام اليوم": every waiting job in one list, oldest first in each group. */
@Composable
fun TodayScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val vm = adminViewModel(key = "today") { TodayViewModel(it.round9) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("مهام اليوم", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { t ->
                val groups = groupsOf(t)
                if (groups.all { it.second.isEmpty() }) {
                    EmptyList(Icons.Filled.CheckCircle, "لا توجد مهام الآن", "كل شيء منجز 👌")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.filter { it.second.isNotEmpty() }.forEach { (title, tasks) ->
                            item(key = "h:$title") {
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    StatusChip("${tasks.size}", Lux.GoldDeep)
                                }
                            }
                            items(tasks, key = { it.key }) { task ->
                                GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp, onClick = task.route?.let { r -> { navigate(r) } }) {
                                    Text(task.title, style = MaterialTheme.typography.titleSmall)
                                    Text(task.subtitle, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun money(amount: String, currency: String) = Money.plain(Money.parse(amount), Currency.of(currency))

private fun groupsOf(t: TodayTasks): List<Pair<String, List<Task>>> = listOf(
    "شحن أرصدة للتحقق" to t.topUps.map {
        Task("t:${it.id}", "${it.number} · ${it.customer.name}", "${money(it.amount, it.currency)} · ${if (it.status == "PENDING") "بانتظار الموافقة" else "قُبل تلقائياً، تحقّق من وصول المبلغ"} · ${formatDateTime(it.createdAt)}", R.of(R.TOP_UP, it.id))
    },
    "طلبات تجار تنتظر الموافقة" to t.approvals.map {
        Task("a:${it.id}", "${it.number} · ${it.customer.name}", "${money(it.grandTotal, it.currency)} · ${formatDateTime(it.placedAt)}", R.of(R.ORDER, it.id))
    },
    "طلبات للتجهيز والشحن" to t.toShip.map {
        val how = when (it.deliveryMethod) {
            "PICKUP" -> "استلام من المحل"
            "OFFICE" -> "إلى مكتب ${it.carrier?.name ?: "الشحن"}"
            else -> "توصيل للعنوان"
        }
        Task("s:${it.id}", "${it.number} · ${it.customer.name}", "$how · ${formatDateTime(it.placedAt)}", R.of(R.ORDER, it.id))
    },
    "حجوزات لم تُجهَّز قطعها" to t.layaways.map {
        Task("l:${it.id}", "${it.number} · ${it.customer.name}", formatDateTime(it.createdAt), R.of(R.LAYAWAY, it.id))
    },
    "هدايا للتجهيز" to t.gifts.map {
        Task("g:${it.id}", "${it.number} · إلى ${it.recipient.name}", "${if (it.status == "PICKUP") "تُستلم من المحل" else "للتوصيل"} · من ${it.giverName}", R.GIFTS)
    },
    "حجز مسبق وصل وينتظر العميل" to t.preOrders.map {
        Task("p:${it.id}", "${it.number} · ${it.customer.name}", "${it.itemName ?: ""} ${it.quantity ?: ""} قطعة · ${formatDateTime(it.notifiedAt)}".trim(), R.PRE_ORDERS)
    },
    "مرتجعات" to t.returns.map {
        Task("r:${it.id}", "${it.number} · ${it.customer.name}", "${if (it.status == "REQUESTED") "طلب جديد" else "موافَق، بانتظار الاستلام"} · ${formatDateTime(it.requestedAt)}", R.of(R.RETURN, it.id))
    },
    "توثيق يوزرات انستجرام" to t.instagram.map {
        Task("i:${it.id}", "@${it.username ?: ""} · ${it.name}", it.phone, R.INSTAGRAM)
    },
    "مخزون منخفض" to t.lowStock.map {
        Task("k:${it.variantId}", it.name, "${it.sku} · المتاح ${it.available} (الحد ${it.threshold})", R.STOCK)
    },
)
