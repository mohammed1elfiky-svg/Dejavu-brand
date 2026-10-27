package com.dejavu.admin.ui.finance

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.core.admin.AdminCustomerDetail
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Something to show the owner once: a new password or a code, with the text to pass on. */
data class Handover(val title: String, val secret: String, val phone: String, val message: String, val texted: Boolean)

class CustomerAccessViewModel(private val id: String, private val repo: FinanceRepository) : BaseViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _handover = MutableStateFlow<Handover?>(null)
    val handover = _handover.asStateFlow()

    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block()
            _busy.value = false
        }
    }

    fun approve(done: () -> Unit) = run {
        repo.approveCustomer(id).orSay()?.let {
            say("تم تفعيل الحساب، يمكن للعميل الدخول برقمه وكلمة المرور")
            done()
        }
    }

    fun sendPassword(done: () -> Unit) = run {
        repo.sendCustomerPassword(id).orSay()?.let { r ->
            _handover.value = Handover("كلمة مرور جديدة", r.password, r.phone, r.message, r.smsQueued)
            done()
        }
    }

    fun issueCode() = run {
        repo.customerCode(id).orSay()?.let { r -> _handover.value = Handover("رمز التحقق", r.code, r.phone, r.message, texted = false) }
    }

    fun closeHandover() {
        _handover.value = null
    }
}

/**
 * Letting the customer into the app: approve a sign-up that never entered its code, give a
 * code to send on WhatsApp, or a new random password (texted to Yemeni numbers).
 */
@Composable
fun CustomerAccessCard(c: AdminCustomerDetail, onChanged: () -> Unit) {
    val vm = adminViewModel(key = "access:${c.id}") { CustomerAccessViewModel(c.id, it.finance) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val handover by vm.handover.collectAsStateWithLifecycle()
    val pending = c.status == "PENDING"
    InfoCard {
        Text("الدخول إلى التطبيق", style = MaterialTheme.typography.titleSmall)
        if (pending) {
            Text(
                "سجّل العميل ولم يُدخل رمز التحقق بعد، فلا يستطيع الدخول.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { vm.approve(onChanged) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("تفعيل الحساب بدون رمز") }
            OutlinedButton(onClick = vm::issueCode, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("إنشاء رمز تحقق لإرساله عبر واتساب") }
        }
        OutlinedButton(onClick = { vm.sendPassword(onChanged) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (pending) "تفعيل وإرسال كلمة مرور جديدة" else "إرسال كلمة مرور جديدة للعميل")
        }
        if (!pending) {
            TextButton(onClick = vm::issueCode, enabled = !busy) { Text("رمز استعادة كلمة المرور (لإرساله عبر واتساب)") }
        }
    }
    handover?.let { h -> HandoverDialog(h, onClose = vm::closeHandover) }
}

@Composable
private fun HandoverDialog(h: Handover, onClose: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(h.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(h.secret, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (h.texted) "أُرسلت برسالة نصية إلى ${h.phone}. يمكنك إرسالها عبر واتساب أيضاً." else "أرسلها للعميل عبر واتساب، فهي لا تظهر مرة أخرى.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val digits = h.phone.filter { it.isDigit() }
                runCatching { uriHandler.openUri("https://wa.me/$digits?text=${Uri.encode(h.message)}") }
            }) { Text("إرسال عبر واتساب") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
    )
}
