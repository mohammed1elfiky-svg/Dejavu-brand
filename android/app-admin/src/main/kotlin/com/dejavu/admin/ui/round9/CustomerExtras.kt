package com.dejavu.admin.ui.round9

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.TextDialog
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.CustomerLoyalty
import com.dejavu.core.admin.Perm
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import kotlinx.coroutines.launch

/**
 * Round 9 on the customer page: their loyalty points (on / off for them, add or remove points),
 * blocking the customer and their number, and the full statement.
 */
@Composable
fun CustomerExtrasCard(customerId: String, blocked: Boolean, openStatement: () -> Unit, onChanged: (String) -> Unit) {
    val container = adminContainer()
    val repo = container.round9
    val canEdit = container.session.can(Perm.CUSTOMERS_WRITE)
    val scope = rememberCoroutineScope()
    var loyalty by remember { mutableStateOf<CustomerLoyalty?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(customerId) { loyalty = repo.customerLoyalty(customerId).getOrNull() }
    fun run(done: String, block: suspend () -> Result<*>) {
        scope.launch {
            block().onSuccess { onChanged(done) }.onFailure { onChanged(it.userMessage()) }
            loyalty = repo.customerLoyalty(customerId).getOrNull()
        }
    }
    InfoCard {
        Text("نقاط الولاء والحساب", style = MaterialTheme.typography.titleSmall)
        loyalty?.let { l ->
            Text(
                "${l.points} نقطة (تساوي ${Money.plain(Money.parse(l.value), Currency.SAR)}) · " +
                    when (l.customerSetting) {
                        true -> "مفعّلة لهذا العميل"
                        false -> "موقوفة لهذا العميل"
                        null -> if (l.earning) "حسب إعداد المتجر (يربح)" else "حسب إعداد المتجر (لا يربح)"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = Lux.InkSoft,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = openStatement, modifier = Modifier.weight(1f)) { Text("كشف الحساب") }
            if (canEdit) OutlinedButton(onClick = { dialog = "points" }, modifier = Modifier.weight(1f)) { Text("النقاط") }
        }
        if (canEdit) {
            TextButton(onClick = { dialog = if (blocked) "unblock" else "block" }) {
                Text(if (blocked) "إلغاء حظر العميل" else "حظر العميل ورقمه", color = if (blocked) Lux.GoldDeep else MaterialTheme.colorScheme.error)
            }
        }
    }
    when (dialog) {
        "block" -> TextDialog("حظر العميل", "السبب", "حظر", onDismiss = { dialog = null }, minLength = 0) { reason ->
            run("حُظر العميل، ولا يستطيع رقمه التسجيل مرة أخرى") { repo.block(customerId, reason) }
        }
        "unblock" -> ConfirmDialog("إلغاء الحظر؟", "يعود الحساب نشطاً ويستطيع الدخول.", "إلغاء الحظر", onDismiss = { dialog = null }) {
            run("أُلغي الحظر") { repo.unblock(customerId) }
        }
        "points" -> PointsDialog(loyalty, onDismiss = { dialog = null }, onMode = { mode ->
            dialog = null
            run("تم") { repo.setCustomerLoyalty(customerId, mode) }
        }) { points, note ->
            dialog = null
            run("تم تعديل النقاط") { repo.adjustPoints(customerId, points, note) }
        }
    }
}

@Composable
private fun PointsDialog(loyalty: CustomerLoyalty?, onDismiss: () -> Unit, onMode: (Boolean?) -> Unit, onAdjust: (Int, String) -> Unit) {
    var points by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var add by rememberSaveable { mutableStateOf(true) }
    val n = points.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("نقاط العميل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("النقاط لهذا العميل:", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = loyalty?.customerSetting == null, onClick = { onMode(null) }, label = { Text("حسب المتجر") })
                    FilterChip(selected = loyalty?.customerSetting == true, onClick = { onMode(true) }, label = { Text("مفعّلة") })
                    FilterChip(selected = loyalty?.customerSetting == false, onClick = { onMode(false) }, label = { Text("موقوفة") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = add, onClick = { add = true }, label = { Text("إضافة") })
                    FilterChip(selected = !add, onClick = { add = false }, label = { Text("خصم") })
                }
                OutlinedTextField(value = points, onValueChange = { points = it.filter(Char::isDigit).take(7) }, label = { Text("عدد النقاط") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text("ملاحظة (اختياري)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onAdjust(if (add) n!! else -n!!, note) }, enabled = n != null && n > 0) { Text("حفظ النقاط") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}
