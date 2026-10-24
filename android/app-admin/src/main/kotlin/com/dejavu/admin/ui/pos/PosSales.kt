@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.pos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.PosReceipt
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.PosSaleSummary
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.launch

/** Today's till sales: view / print, delete (void) and correct. */
class PosSalesViewModel(private val pos: PosRepository) : BaseViewModel() {
    var sales by mutableStateOf<Load<List<PosSaleSummary>>>(Load.Loading)
        private set
    var showAll by mutableStateOf(false)
        private set
    /** Sale being loaded or deleted. */
    var busyId by mutableStateOf<String?>(null)
        private set

    fun load() {
        if (sales !is Load.Ready) sales = Load.Loading
        viewModelScope.launch {
            val result = pos.sales(all = showAll)
            if (result.isFailure && sales is Load.Ready) {
                result.orSay()
            } else {
                sales = result.toLoad()
            }
        }
    }

    fun toggleAll(all: Boolean) {
        if (showAll == all) return
        showAll = all
        sales = Load.Loading
        load()
    }

    /** Loads the full receipt of [id] and hands it to [then]. */
    fun open(id: String, then: (PosReceipt) -> Unit) {
        if (busyId != null) return
        busyId = id
        viewModelScope.launch {
            pos.receipt(id).orSay()?.let(then)
            busyId = null
        }
    }

    fun void(id: String, reason: String, done: () -> Unit) {
        if (busyId != null) return
        busyId = id
        viewModelScope.launch {
            pos.voidSale(id, reason).fold(
                {
                    say("تم حذف الفاتورة ${it.number}")
                    done()
                    load()
                },
                { e -> say(e.userMessage()) },
            )
            busyId = null
        }
    }
}

/** WhatsApp's brand green, for the "send on WhatsApp" actions. */
internal val WhatsAppGreen = androidx.compose.ui.graphics.Color(0xFF1FA855)

private val PosSaleSummary.deleted: Boolean get() = isVoided || cancelledAt != null

/**
 * Sheet with today's invoices. [canAll]: managers may list every cashier's sales;
 * [canEdit]: delete / correct a sale. [currencyImage] gives a currency's uploaded picture.
 */
@Composable
fun PosSalesSheet(
    canAll: Boolean,
    canEdit: Boolean,
    currencyImage: (String) -> String?,
    onDismiss: () -> Unit,
    onView: (PosReceipt) -> Unit,
    onEdit: (PosReceipt) -> Unit,
    onWhatsApp: ((PosReceipt) -> Unit)? = null,
) {
    val vm = adminViewModel { PosSalesViewModel(it.pos) }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LaunchedEffect(Unit) { vm.load() }
    var deleting by remember { mutableStateOf<PosSaleSummary?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Lux.Pearl) {
        Box {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("فواتير اليوم", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "إغلاق") }
                }
                if (canAll) {
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !vm.showAll, onClick = { vm.toggleAll(false) }, label = { Text("فواتيري") })
                        FilterChip(selected = vm.showAll, onClick = { vm.toggleAll(true) }, label = { Text("كل الكاشيرات") })
                    }
                }
                if (vm.busyId != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                when (val s = vm.sales) {
                    Load.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is Load.Failed -> Column(Modifier.padding(16.dp)) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::load) { Text("إعادة المحاولة") }
                    }
                    is Load.Ready -> if (s.value.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(200.dp)) { EmptyState(Icons.Filled.DateRange, "لا توجد فواتير اليوم") }
                    } else {
                        val live = s.value.filter { !it.deleted }
                        Text(
                            "${live.size} فاتورة" + if (s.value.size > live.size) " · ${s.value.size - live.size} محذوفة" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = Lux.InkSoft,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 640.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(s.value, key = { it.id }) { sale ->
                                SaleRow(
                                    sale,
                                    showCashier = vm.showAll,
                                    canEdit = canEdit,
                                    busy = vm.busyId != null,
                                    currencyImage = currencyImage,
                                    onView = { vm.open(sale.id, onView) },
                                    onDelete = { deleting = sale },
                                    onEdit = { vm.open(sale.id, onEdit) },
                                    onWhatsApp = onWhatsApp?.let { send -> { vm.open(sale.id, send) } },
                                )
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    deleting?.let { sale ->
        VoidSaleDialog(sale, busy = vm.busyId != null, onDismiss = { deleting = null }) { reason ->
            vm.void(sale.id, reason) { deleting = null }
        }
    }
}

@Composable
private fun SaleRow(
    sale: PosSaleSummary,
    showCashier: Boolean,
    canEdit: Boolean,
    busy: Boolean,
    currencyImage: (String) -> String?,
    onView: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onWhatsApp: (() -> Unit)? = null,
) {
    val deleted = sale.deleted
    val strike = if (deleted) TextDecoration.LineThrough else null
    GlassCard(Modifier.fillMaxWidth().alpha(if (deleted) 0.6f else 1f), elevation = 4.dp, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sale.number, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textDecoration = strike)
                Text(
                    listOfNotNull(
                        formatDateTime(sale.placedAt).ifBlank { null },
                        sale.customer?.name,
                        sale.cashier?.name?.takeIf { showCashier }?.let { "الكاشير: $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            CurrencyArt(sale.currency.code, currencyImage(sale.currency.code), height = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                Money.plain(Money.parse(sale.grandTotalInCurrency), sale.currency),
                style = MaterialTheme.typography.titleSmall,
                color = Lux.GoldDeep,
                textDecoration = strike,
            )
        }
        if (deleted) {
            Text(
                "محذوفة" + (sale.cancelReason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onView, enabled = !busy) { Text("عرض/طباعة") }
                if (onWhatsApp != null) TextButton(onClick = onWhatsApp, enabled = !busy) { Text("واتساب", color = WhatsAppGreen) }
                if (canEdit) {
                    TextButton(onClick = onEdit, enabled = !busy) { Text("تعديل") }
                    TextButton(onClick = onDelete, enabled = !busy) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun VoidSaleDialog(sale: PosSaleSummary, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حذف الفاتورة ${sale.number}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "سيُعاد المبلغ والمخزون وحساب العميل كما كانت قبل الفاتورة.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(200) },
                    label = { Text("السبب (اختياري)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim()) }, enabled = !busy) {
                Text(if (busy) "جارٍ الحذف…" else "حذف", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}
