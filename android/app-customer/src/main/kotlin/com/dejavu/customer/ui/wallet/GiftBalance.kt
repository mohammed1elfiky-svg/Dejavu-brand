package com.dejavu.customer.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.WalletRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.WalletBalance
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.customer.ui.appViewModel
import com.dejavu.customer.ui.auth.Country
import com.dejavu.customer.ui.auth.PhoneField
import com.dejavu.customer.ui.components.goldChipColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Giving balance to another customer: who gets it (checked while typing), then the send. */
class GiftViewModel(private val repo: WalletRepository) : BaseViewModel() {
    /** The receiver's masked name, or why the number does not work; null while unknown. */
    private val _recipient = MutableStateFlow<Result<String>?>(null)
    val recipient = _recipient.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var lookup: Job? = null

    fun check(phone: String) {
        lookup?.cancel()
        _recipient.value = null
        lookup = viewModelScope.launch {
            delay(400)
            _recipient.value = repo.giftRecipient(phone).map { it.name }
        }
    }

    fun send(phone: String, currency: Currency, amount: String, password: String, note: String, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.sendGift(phone, currency, amount, password, note)
                .onSuccess { r ->
                    say("تم إهداء ${r.amount} إلى ${r.to} 🎁")
                    done()
                }
                .onFailure { say(it.userMessage()) }
            _busy.value = false
        }
    }
}

/** "إهداء رصيد": number, currency, amount, a note, and the password to confirm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GiftBalanceDialog(balances: List<WalletBalance>, snackbar: androidx.compose.material3.SnackbarHostState, onSent: () -> Unit, onDismiss: () -> Unit) {
    val vm = appViewModel { GiftViewModel(it.wallet) }
    val recipient by vm.recipient.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    com.dejavu.core.ui.CollectMessages(vm, snackbar)
    val spendable = balances.filter { (Money.parse(it.balance)).signum() > 0 }
    var country by rememberSaveable { mutableStateOf(Country.YE) }
    var number by rememberSaveable { mutableStateOf("") }
    var currencyCode by rememberSaveable { mutableStateOf(spendable.firstOrNull()?.currency?.code ?: "") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val phoneOk = country.isValid(number)
    val full = country.full(number)
    LaunchedEffect(full, phoneOk) { if (phoneOk) vm.check(full) }
    val chosen = spendable.firstOrNull { it.currency.code == currencyCode }
    val amountValue = amount.toBigDecimalOrNull()
    val amountOk = amountValue != null && amountValue.signum() > 0 && chosen != null && amountValue <= Money.parse(chosen.balance)
    val ready = phoneOk && recipient?.isSuccess == true && amountOk && password.isNotBlank() && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("إهداء رصيد") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (spendable.isEmpty()) {
                    Text("لا يوجد رصيد متاح للإهداء.")
                    return@Column
                }
                PhoneField(country, number, null, onCountry = { country = it }, onNumber = { number = it }, enabled = !busy)
                if (phoneOk) {
                    val r = recipient
                    Text(
                        when {
                            r == null -> "جارٍ التحقق من الرقم…"
                            r.isSuccess -> "سيصل الرصيد إلى: ${r.getOrNull()}"
                            else -> r.exceptionOrNull()?.userMessage() ?: "الرقم غير مسجّل"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r?.isSuccess == true) Liquid.Success else if (r == null) Liquid.InkSoft else MaterialTheme.colorScheme.error,
                    )
                }
                if (spendable.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        spendable.forEach { b ->
                            FilterChip(
                                selected = b.currency.code == currencyCode,
                                onClick = { currencyCode = b.currency.code },
                                label = { Text(b.currency.nameAr) },
                                colors = goldChipColors(),
                            )
                        }
                    }
                }
                chosen?.let { Text("رصيدك المتاح: ${Money.plain(Money.parse(it.balance), it.currency)}", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft) }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' }.take(12) },
                    label = { Text("المبلغ") },
                    singleLine = true,
                    isError = amount.isNotEmpty() && !amountOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = { Text("رسالة مع الهدية (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("كلمة المرور للتأكيد") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                val currency = chosen?.currency ?: return@TextButton
                vm.send(full, currency, amount, password, note) { onSent() }
            }) { Text(if (busy) "جارٍ الإرسال…" else "إهداء", color = Liquid.GoldDeep) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء") } },
    )
}
