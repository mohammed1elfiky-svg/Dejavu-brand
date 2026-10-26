@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.preorders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.StoreExtrasRepository
import com.dejavu.core.model.Numerals
import com.dejavu.core.model.PreOrder
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.toLoad
import com.dejavu.customer.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PreOrdersViewModel(private val extras: StoreExtrasRepository, val session: SessionRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<PreOrder>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _cancelling = MutableStateFlow<String?>(null)
    val cancelling = _cancelling.asStateFlow()

    fun load() {
        if (!session.isLoggedIn) return
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = extras.preOrders().toLoad() }
    }

    fun refresh() {
        if (!session.isLoggedIn || _refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val result = extras.preOrders()
            // Keep the list on screen when a pull-to-refresh fails; just say why.
            if (result.isSuccess || _state.value !is Load.Ready) _state.value = result.toLoad() else result.orSay()
            _refreshing.value = false
        }
    }

    fun cancel(p: PreOrder) {
        if (_cancelling.value != null) return
        _cancelling.value = p.id
        viewModelScope.launch {
            extras.cancelPreOrder(p.id).orSay()?.let {
                // Cancelled pre-orders are not listed.
                val list = (_state.value as? Load.Ready)?.value
                if (list != null) _state.value = Load.Ready(list.filterNot { it.id == p.id })
                say("تم إلغاء الحجز")
            }
            _cancelling.value = null
        }
    }
}

private fun statusLabel(status: String): Pair<String, Color> = when (status) {
    "WAITING" -> "بانتظار الوصول" to Liquid.GoldDeep
    "NOTIFIED" -> "وصلت — اطلبيها الآن" to Liquid.Success
    "CONVERTED" -> "تم حجزها لك" to Liquid.Success
    "CANCELLED" -> "ملغاة" to Liquid.InkSoft
    else -> status to Liquid.InkSoft
}

/** "21 أكتوبر 2026" from a full ISO time or a plain "2026-10-21" date. */
private fun expected(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return formatDate(iso).ifBlank { formatDate(iso.take(10) + "T12:00:00Z") }
}

/** The customer's pre-orders on pieces still on their way. */
@Composable
fun PreOrdersScreen(onBack: () -> Unit, openProduct: (String) -> Unit) {
    val vm = appViewModel { PreOrdersViewModel(it.extras, it.session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val cancelling by vm.cancelling.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmCancel by remember { mutableStateOf<PreOrder?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("حجوزاتي المسبقة", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!vm.session.isLoggedIn) {
            EmptyState(
                Icons.Filled.DateRange,
                "سجّلي الدخول لعرض حجوزاتك",
                "احجزي المقاسات النافدة من الشحنة القادمة من صفحة المنتج",
                modifier = Modifier.padding(padding),
            )
        } else LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
                if (list.isEmpty()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            EmptyState(
                                Icons.Filled.DateRange,
                                "لا توجد حجوزات مسبقة",
                                "عندما ينفد مقاسك وتكون هناك شحنة قادمة، اضغطي «احجزي من الشحنة القادمة» في صفحة المنتج",
                                modifier = Modifier.fillParentMaxSize(),
                            )
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.id }) { p ->
                            PreOrderRow(
                                p,
                                cancelling = cancelling == p.id,
                                onOpen = { openProduct(p.slug) },
                                onCancel = { confirmCancel = p },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmCancel?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("إلغاء الحجز؟") },
            text = { Text("سيُلغى حجزك لـ ${p.name} وقد تُحجز القطعة لعميلة أخرى.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = null
                    vm.cancel(p)
                }) { Text("إلغاء الحجز", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("تراجع") } },
        )
    }
}

@Composable
private fun PreOrderRow(p: PreOrder, cancelling: Boolean, onOpen: () -> Unit, onCancel: () -> Unit) {
    val (label, color) = statusLabel(p.status)
    GlassCard(Modifier.fillMaxWidth(), elevation = 10.dp, contentPadding = 12.dp, onClick = onOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductImage(p.imageUrl, Modifier.size(width = 72.dp, height = 96.dp).clip(MaterialTheme.shapes.medium), contentDescription = p.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val options = listOfNotNull(p.color, p.size?.let { "مقاس $it" }).joinToString(" · ")
                Text(
                    Numerals.of(listOf(options, "الكمية ${p.quantity}").filter { it.isNotBlank() }.joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = Liquid.InkSoft,
                )
                StatusChip(label, color)
                if (p.status == "WAITING") {
                    val date = expected(p.expectedDate)
                    if (date.isNotEmpty()) Text("الوصول المتوقع: $date", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
                }
                Text(Numerals.of("رقم الحجز ${p.number} · ${formatDate(p.createdAt)}"), style = MaterialTheme.typography.labelSmall, color = Liquid.InkSoft)
                if (p.status == "WAITING" || p.status == "NOTIFIED") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.status == "NOTIFIED") {
                            Text("اطلبيها الآن", style = MaterialTheme.typography.labelLarge, color = Liquid.Success, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        } else {
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        }
                        OutlinedButton(onClick = onCancel, enabled = !cancelling) {
                            Text("إلغاء", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
