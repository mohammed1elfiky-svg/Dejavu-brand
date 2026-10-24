package com.dejavu.customer.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.NotificationRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.AppNotification
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.formatDateTime
import com.dejavu.customer.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel(private val repo: NotificationRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<AppNotification>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    val unread = repo.unread
    private var page = 1

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            repo.page(1).fold(
                { p ->
                    page = 1
                    _state.value = Load.Ready(p.items)
                    _hasMore.value = p.hasMore
                },
                { if (_state.value !is Load.Ready) _state.value = Load.Failed(it.userMessage()) else say(it.userMessage()) },
            )
        }
    }

    fun more() {
        viewModelScope.launch {
            repo.page(page + 1).orSay()?.let { p ->
                page += 1
                _state.value = Load.Ready((_state.value as? Load.Ready)?.value.orEmpty() + p.items)
                _hasMore.value = p.hasMore
            }
        }
    }

    /** Marks as read and reports what to open: the order it is about, the product (new or back in stock), the cart, or the wallet for top-up news. */
    fun open(n: AppNotification, openOrder: (String) -> Unit, openWallet: () -> Unit, openProduct: (String) -> Unit, openCart: () -> Unit) {
        if (!n.isRead) {
            markLocally(n.id)
            viewModelScope.launch { repo.markRead(n.id) }
        }
        val order = n.orderNumber?.takeIf { it.isNotBlank() } ?: n.data["orderNumber"]?.takeIf { it.isNotBlank() }
        val product = n.data["product"]?.takeIf { it.isNotBlank() } ?: n.data["productSlug"]?.takeIf { it.isNotBlank() }
        when {
            order != null -> openOrder(order)
            product != null -> openProduct(product)
            n.data["screen"] == "cart" -> openCart()
            n.type == "WALLET" || n.data["topUp"] != null -> openWallet()
        }
    }

    fun readAll() {
        viewModelScope.launch {
            repo.markAllRead().orSay() ?: return@launch
            val now = "read"
            _state.value = (_state.value as? Load.Ready)?.value?.map { if (it.isRead) it else it.copy(readAt = now) }
                ?.let { Load.Ready(it) } ?: _state.value
        }
    }

    private fun markLocally(id: String) {
        val list = (_state.value as? Load.Ready)?.value ?: return
        _state.value = Load.Ready(list.map { if (it.id == id) it.copy(readAt = "read") else it })
    }
}

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    openOrder: (String) -> Unit,
    openWallet: () -> Unit,
    openProduct: (String) -> Unit = {},
    openCart: () -> Unit = {},
) {
    val vm = appViewModel { NotificationsViewModel(it.notifications) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("الإشعارات", onBack) {
                if (unread > 0) TextButton(onClick = vm::readAll) { Text("تحديد الكل كمقروء", color = Liquid.GoldDeep) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { items ->
            if (items.isEmpty()) {
                EmptyState(Icons.Filled.Notifications, "لا توجد إشعارات بعد", "سنخبرك هنا بكل جديد عن طلباتك ورصيدك والعروض", modifier = Modifier.padding(padding))
                return@LoadContent
            }
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { n ->
                    NotificationRow(n) { vm.open(n, openOrder, openWallet, openProduct, openCart) }
                }
                if (hasMore) {
                    item { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("عرض المزيد") } }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: AppNotification, onClick: () -> Unit) {
    // Unread notifications glow faintly gold.
    GlassCard(
        Modifier.fillMaxWidth(),
        elevation = if (n.isRead) 6.dp else 12.dp,
        contentPadding = 0.dp,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().background(if (n.isRead) Color.Transparent else Liquid.GoldPale.copy(alpha = 0.30f)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (n.isRead) Liquid.GoldPale.copy(alpha = 0.6f) else Color.Transparent)
                    .then(if (n.isRead) Modifier else Modifier.background(Liquid.GoldBrush)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = null, tint = if (n.isRead) Liquid.GoldDeep else Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (n.isRead) FontWeight.Normal else FontWeight.Bold)
                Text(n.body, style = MaterialTheme.typography.bodyMedium)
                Text(formatDateTime(n.sentAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            n.data["image"]?.takeIf { it.isNotBlank() }?.let { url ->
                ProductImage(url, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)), contentDescription = null)
            }
        }
    }
}
