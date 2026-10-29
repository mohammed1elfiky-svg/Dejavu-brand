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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminGift
import com.dejavu.core.admin.PosGiftRequest
import com.dejavu.core.admin.Round9Repository
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

class GiftsViewModel(private val repo: Round9Repository) : LoadViewModel<List<AdminGift>>({ repo.gifts() }) {
    fun done(id: String, tracking: String?) = act("تم ✓ وأُبلغ صاحب الهدية") { repo.giftDone(id, tracking) }
}

private fun giftStatus(s: String) = when (s) {
    "WAITING" -> "بانتظار اختيار المستلم"
    "PICKUP" -> "تُستلم من المحل"
    "DELIVERY" -> "للتوصيل"
    "DONE" -> "سُلّمت"
    else -> "ملغاة"
}

/** Gifts bought at the till or collected by friends: prepare them once the receiver chooses. */
@Composable
fun GiftsScreen(onBack: () -> Unit, openOrder: (String) -> Unit) {
    val vm = adminViewModel(key = "gifts") { GiftsViewModel(it.round9) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf("OPEN") }
    var finishing by remember { mutableStateOf<AdminGift?>(null) }
    CollectMessages(vm, snackbar)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("الهدايا", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == "OPEN", onClick = { filter = "OPEN" }, label = { Text("للتجهيز") })
                FilterChip(selected = filter == "WAITING", onClick = { filter = "WAITING" }, label = { Text("بانتظار المستلم") })
                FilterChip(selected = filter == "DONE", onClick = { filter = "DONE" }, label = { Text("سُلّمت") })
            }
            LoadContent(state, onRetry = vm::load) { all ->
                val list = all.filter {
                    when (filter) {
                        "OPEN" -> it.status == "PICKUP" || it.status == "DELIVERY"
                        else -> it.status == filter
                    }
                }
                if (list.isEmpty()) {
                    EmptyList(Icons.Filled.Favorite, "لا توجد هدايا هنا", "سجّل فاتورة الكاشير كهدية من شاشة الفاتورة، ويختار المستلم التوصيل أو الاستلام من المحل")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.id }) { g ->
                            GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp, onClick = g.saleOrderId?.let { id -> { openOrder(id) } }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${g.number} · إلى ${g.recipient?.name ?: ""}", style = MaterialTheme.typography.titleSmall)
                                        Text("من ${g.giverName}${g.occasion?.let { " · بمناسبة $it" } ?: ""}${if (g.source == "GROUP") " · هدية جماعية" else ""}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                    }
                                    StatusChip(giftStatus(g.status), if (g.status == "DONE") Success else Lux.GoldDeep)
                                }
                                g.items.forEach { i -> Text("• ${i.name} ${listOfNotNull(i.size, i.color).joinToString(" ")} × ${i.quantity}", style = MaterialTheme.typography.bodySmall) }
                                deliveryText(g)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.Ink) }
                                g.recipient?.phone?.takeIf { it.isNotBlank() }?.let { Text("جوال المستلم: $it", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft) }
                                Text(formatDateTime(g.chosenAt ?: g.createdAt), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                if (g.status == "PICKUP" || g.status == "DELIVERY") {
                                    Button(onClick = { finishing = g }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (g.status == "PICKUP") "سُلّمت في المحل" else "تم الشحن")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    finishing?.let { g ->
        var tracking by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { finishing = null },
            title = { Text(if (g.status == "PICKUP") "تسليم ${g.number}" else "شحن ${g.number}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (g.status == "DELIVERY") {
                        OutlinedTextField(value = tracking, onValueChange = { tracking = it.take(80) }, label = { Text("رقم التتبع (اختياري)") }, singleLine = true)
                        Text("أجرة التوصيل يدفعها المستلم لشركة الشحن عند الاستلام.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    } else {
                        Text("سيصل المستلم إشعار بأنه استلم هديته.")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { finishing = null; vm.done(g.id, tracking) }) { Text("تأكيد") } },
            dismissButton = { TextButton(onClick = { finishing = null }) { Text("تراجع") } },
        )
    }
}

/** Where the receiver asked for it: an address, a carrier office or the store. */
private fun deliveryText(g: AdminGift): String? {
    val d = g.delivery ?: return null
    fun f(key: String) = (d[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return when (g.deliveryMethod) {
        "PICKUP" -> "استلام من المحل"
        "OFFICE" -> {
            val phones = runCatching { d["officePhones"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull().orEmpty()
            "مكتب ${listOfNotNull(f("carrier"), f("officeName")).joinToString(" - ")} · ${listOfNotNull(f("governorate"), f("city"), f("street")).joinToString("، ")}" +
                (if (phones.isNotEmpty()) " · ${phones.joinToString(" / ")}" else "")
        }
        "HOME" -> "توصيل إلى ${listOfNotNull(f("recipient"), f("phone")).joinToString(" · ")} — ${listOfNotNull(f("governorate"), f("city"), f("street"), f("details")).joinToString("، ")}"
        else -> null
    }
}

/**
 * After a till sale: "this is a gift for …". The receiver (by mobile or username) is told
 * «معك هدية من فلان بمناسبة …» and chooses delivery or pickup in their app.
 */
@Composable
fun MarkGiftDialog(saleOrderId: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val repo = adminContainer().round9
    val scope = rememberCoroutineScope()
    var to by rememberSaveable { mutableStateOf("") }
    var giver by rememberSaveable { mutableStateOf("") }
    var occasion by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("تسجيل الفاتورة كهدية 🎁") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = to, onValueChange = { to = it.take(40) }, label = { Text("جوال المستلم أو يوزره") }, singleLine = true)
                OutlinedTextField(value = giver, onValueChange = { giver = it.take(80) }, label = { Text("اسم المُهدي (يظهر للمستلم)") }, singleLine = true)
                OutlinedTextField(value = occasion, onValueChange = { occasion = it.take(80) }, label = { Text("المناسبة (اختياري)") }, singleLine = true)
                OutlinedTextField(value = message, onValueChange = { message = it.take(300) }, label = { Text("رسالة (اختياري)") })
                Text("يصل المستلم إشعار، ويختار التوصيل (يدفع أجرة الشحن عند الاستلام) أو الاستلام من المحل مجاناً.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        repo.markGift(PosGiftRequest(saleOrderId, to.trim(), giver.trim(), occasion.trim().takeIf { it.isNotEmpty() }, message.trim().takeIf { it.isNotEmpty() }))
                            .onSuccess { onDone("أُرسل إشعار الهدية إلى ${it.recipient?.name ?: "المستلم"}") }
                            .onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
                enabled = !busy && to.trim().length >= 2 && giver.isNotBlank(),
            ) { Text(if (busy) "جارٍ الإرسال…" else "إرسال") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("تراجع") } },
    )
}
