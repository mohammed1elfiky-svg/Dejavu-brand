@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.customer.ui.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.CurrencyRepository
import com.dejavu.core.data.WalletRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.StoreBankAccount
import com.dejavu.core.model.TopUp
import com.dejavu.core.model.TopUpStatus
import com.dejavu.core.model.Wallet
import com.dejavu.core.model.WalletBalance
import com.dejavu.core.model.WalletEntry
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SectionTitle
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import com.dejavu.customer.ui.appViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

// ----------------------------------------------------------------- wallet ---

data class WalletData(val wallet: Wallet, val topUps: List<TopUp>)

class WalletViewModel(private val repo: WalletRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<WalletData>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            val wallet = async { repo.wallet() }
            val topUps = async { repo.topUps() }
            val w = wallet.await()
            val t = topUps.await()
            val result = w.map { WalletData(it, t.getOrDefault(emptyList())) }
            if (result.isFailure && _state.value is Load.Ready) {
                result.orSay()
            } else {
                _state.value = result.toLoad()
            }
        }
    }
}

/** Balances per currency, top-up requests and recent movements. */
@Composable
fun WalletScreen(onBack: () -> Unit, topUp: () -> Unit) {
    val vm = appViewModel { WalletViewModel(it.wallet) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    // Also refreshes after coming back from a top-up request.
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("رصيدي", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { data ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(vertical = 12.dp)) {
                val balances = data.wallet.balances
                if (balances.isEmpty()) {
                    item { BalanceCard(WalletBalance(Currency.SAR, "0")) }
                } else {
                    items(balances, key = { it.currency.code }) { BalanceCard(it) }
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Button(onClick = topUp, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("شحن الرصيد")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "حوّل المبلغ إلى أحد حساباتنا البنكية وأرفق صورة الإيصال، ويُضاف إلى رصيدك بعد المراجعة. يمكنك الدفع من رصيدك عند إتمام الطلب.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (data.topUps.isNotEmpty()) {
                    item { SectionTitle("طلبات الشحن") }
                    items(data.topUps, key = { "t" + it.id }) { TopUpRow(it) }
                }

                item { SectionTitle("آخر الحركات") }
                if (data.wallet.entries.isEmpty()) {
                    item {
                        Text(
                            "لا توجد حركات بعد",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    items(data.wallet.entries, key = { "e" + it.id }) { e ->
                        Column {
                            EntryRow(e)
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(b: WalletBalance) {
    val balance = Money.parse(b.balance)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.AccountBox, contentDescription = null)
                Text("الرصيد المتاح · ${b.currency.nameAr}", style = MaterialTheme.typography.labelLarge)
            }
            Text(Money.plain(balance.max(BigDecimal.ZERO), b.currency), style = MaterialTheme.typography.headlineMedium)
            val owed = Money.parse(b.owed)
            if (owed.signum() > 0) {
                Text("مستحق عليك: ${Money.plain(owed, b.currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TopUpRow(t: TopUp) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Money.plain(Money.parse(t.amount), t.currency), style = MaterialTheme.typography.titleMedium)
                    Text("${t.number} · ${formatDateTime(t.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(t.status.label, t.status.color())
            }
            t.bankAccount?.let { Text("إلى: ${it.bankName} (${it.accountNumber})", style = MaterialTheme.typography.bodySmall) }
            t.reference?.let { Text("رقم العملية: $it", style = MaterialTheme.typography.bodySmall) }
            if (t.status == TopUpStatus.REJECTED) {
                t.rejectReason?.let { Text("سبب الرفض: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun EntryRow(e: WalletEntry) {
    val amount = Money.parse(e.amount)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.note, style = MaterialTheme.typography.bodyMedium)
            Text(formatDateTime(e.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val sign = if (amount.signum() >= 0) "+" else "−"
        Text(
            "$sign${Money.plain(amount.abs(), e.currency)}",
            style = MaterialTheme.typography.titleSmall,
            color = if (amount.signum() >= 0) Success else MaterialTheme.colorScheme.error,
        )
    }
}

private val TopUpStatus.label: String
    get() = when (this) {
        TopUpStatus.PENDING -> "قيد المراجعة"
        TopUpStatus.APPROVED -> "مقبول"
        TopUpStatus.REJECTED -> "مرفوض"
    }

@Composable
private fun TopUpStatus.color() = when (this) {
    TopUpStatus.PENDING -> Warning
    TopUpStatus.APPROVED -> Success
    TopUpStatus.REJECTED -> MaterialTheme.colorScheme.error
}

// ----------------------------------------------------------------- top-up ---

data class TopUpForm(
    val currency: Currency? = null,
    val bankAccountId: String? = null,
    val amount: String = "",
    val reference: String = "",
    val note: String = "",
    val submitting: Boolean = false,
    val showErrors: Boolean = false,
)

class TopUpViewModel(private val repo: WalletRepository, currencies: CurrencyRepository) : BaseViewModel() {
    private val _accounts = MutableStateFlow<Load<List<StoreBankAccount>>>(Load.Loading)
    val accounts = _accounts.asStateFlow()
    private val _form = MutableStateFlow(TopUpForm())
    val form = _form.asStateFlow()

    /** The deposit slip, as compressed JPEG bytes. */
    private val _receipt = MutableStateFlow<ByteArray?>(null)
    val receipt = _receipt.asStateFlow()
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    /** Currencies the store sells in; bank accounts may add others. */
    val available = currencies.available

    init { load() }

    fun load() {
        _accounts.value = Load.Loading
        viewModelScope.launch {
            val result = repo.bankAccounts().map { list -> list.filter { it.isActive }.sortedBy { it.sortOrder } }
            _accounts.value = result.toLoad()
            result.getOrNull()?.let { list ->
                if (_form.value.currency == null) {
                    val first = list.firstOrNull()
                    _form.value = _form.value.copy(currency = first?.currency ?: available.value.firstOrNull(), bankAccountId = first?.id)
                }
            }
        }
    }

    fun currencyChoices(accounts: List<StoreBankAccount>): List<Currency> =
        (accounts.map { it.currency } + available.value).distinct()

    fun chooseCurrency(c: Currency, accounts: List<StoreBankAccount>) {
        val f = _form.value
        val keep = accounts.any { it.id == f.bankAccountId && it.currency == c }
        _form.value = f.copy(currency = c, bankAccountId = if (keep) f.bankAccountId else accounts.firstOrNull { it.currency == c }?.id)
    }

    fun chooseAccount(a: StoreBankAccount) {
        _form.value = _form.value.copy(bankAccountId = a.id, currency = a.currency)
    }

    fun update(change: TopUpForm.() -> TopUpForm) {
        _form.value = _form.value.change()
    }

    fun setReceipt(bytes: ByteArray?) {
        _receipt.value = bytes
    }

    fun amountError(f: TopUpForm = _form.value): String? {
        val currency = f.currency ?: return "اختر العملة"
        val amount = parseAmount(f.amount) ?: return "أدخل المبلغ المحوَّل"
        if (amount.signum() <= 0) return "المبلغ يجب أن يكون أكبر من صفر"
        if (amount.stripTrailingZeros().scale() > currency.decimals) {
            return if (currency.decimals == 0) "أدخل مبلغاً بدون كسور" else "أدخل المبلغ بحد أقصى ${currency.decimals} أرقام عشرية"
        }
        return null
    }

    fun submit() {
        val f = _form.value
        if (f.submitting) return
        val currency = f.currency
        val amount = parseAmount(f.amount)
        val receipt = _receipt.value
        if (amountError(f) != null || currency == null || amount == null || receipt == null) {
            _form.value = f.copy(showErrors = true)
            if (receipt == null) say("أرفق صورة إيصال الإيداع")
            return
        }
        _form.value = f.copy(submitting = true)
        viewModelScope.launch {
            repo.requestTopUp(currency, amount.toPlainString(), receipt, "image/jpeg", f.bankAccountId, f.reference.trim(), f.note.trim())
                .onSuccess { _done.tryEmit(Unit) }
                .onFailure { say(it.userMessage()) }
            _form.value = _form.value.copy(submitting = false)
        }
    }

    private companion object {
        /** Accepts Arabic-Indic digits and the Arabic decimal separator too. */
        fun parseAmount(raw: String): BigDecimal? {
            val latin = raw.trim().map { ch ->
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '٫' -> '.'
                    else -> ch
                }
            }.joinToString("").replace(",", "").replace("٬", "")
            return latin.toBigDecimalOrNull()
        }
    }
}

/** Bank transfer to one of the store's accounts, with the deposit slip, reviewed by staff. */
@Composable
fun TopUpScreen(onBack: () -> Unit, submitted: () -> Unit) {
    val vm = appViewModel { TopUpViewModel(it.wallet, it.currencies) }
    val accountsState by vm.accounts.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val receipt by vm.receipt.collectAsStateWithLifecycle()
    val available by vm.available.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberImagePicker { bytes -> vm.setReceipt(bytes) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.done.collect { submitted() } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("شحن الرصيد", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                Button(onClick = vm::submit, enabled = !form.submitting, modifier = Modifier.fillMaxWidth()) {
                    if (form.submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("إرسال طلب الشحن")
                }
            }
        },
    ) { padding ->
        LoadContent(accountsState, onRetry = vm::load, modifier = Modifier.padding(padding)) { accounts ->
            // Recompose when the currency list arrives.
            val choices = remember(accounts, available) { vm.currencyChoices(accounts) }
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("1. اختر العملة", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { c ->
                        FilterChip(
                            selected = c == form.currency,
                            onClick = { vm.chooseCurrency(c, accounts) },
                            label = { Text("${c.nameAr} (${c.symbol})") },
                        )
                    }
                }

                Text("2. حوّل المبلغ إلى أحد حساباتنا", style = MaterialTheme.typography.titleSmall)
                val forCurrency = accounts.filter { it.currency == form.currency }
                if (forCurrency.isEmpty()) {
                    Text(
                        if (accounts.isEmpty()) "لا توجد حسابات بنكية متاحة حالياً، تواصل معنا." else "لا يوجد حساب بنكي بهذه العملة، اختر عملة أخرى.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                forCurrency.forEach { a ->
                    BankAccountCard(a, selected = a.id == form.bankAccountId, onSelect = { vm.chooseAccount(a) }) { label, value ->
                        copyToClipboard(context, label, value)
                        scope.launch { snackbar.showSnackbar("تم نسخ $label") }
                    }
                }

                Text("3. بيانات التحويل", style = MaterialTheme.typography.titleSmall)
                val amountError = if (form.showErrors) vm.amountError(form) else null
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { v -> vm.update { copy(amount = v.take(16)) } },
                    label = { Text("المبلغ المحوَّل") },
                    suffix = form.currency?.let { { Text(it.symbol) } },
                    isError = amountError != null,
                    supportingText = amountError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.reference,
                    onValueChange = { v -> vm.update { copy(reference = v.take(100)) } },
                    label = { Text("رقم العملية / المرجع (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.note,
                    onValueChange = { v -> vm.update { copy(note = v.take(500)) } },
                    label = { Text("ملاحظة (اختياري)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("4. صورة إيصال الإيداع", style = MaterialTheme.typography.titleSmall)
                val slip = receipt
                if (slip != null) {
                    ReceiptPreview(slip, onRemove = { vm.setReceipt(null) })
                } else if (form.showErrors) {
                    Text("أرفق صورة الإيصال", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = picker.fromGallery, modifier = Modifier.weight(1f)) { Text("من المعرض") }
                    OutlinedButton(onClick = picker.fromCamera, modifier = Modifier.weight(1f)) { Text("التقاط صورة") }
                }
                Text(
                    "يُراجع فريقنا الطلب ويُضاف المبلغ إلى رصيدك بعد التأكد من وصول التحويل، وسنرسل لك إشعاراً بالنتيجة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BankAccountCard(a: StoreBankAccount, selected: Boolean, onSelect: () -> Unit, copy: (String, String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.bankName, style = MaterialTheme.typography.titleSmall)
                Text("باسم: ${a.accountName}", style = MaterialTheme.typography.bodySmall)
                CopyLine("رقم الحساب", a.accountNumber, copy)
                a.iban?.takeIf { it.isNotBlank() }?.let { CopyLine("الآيبان", it, copy) }
                Text("العملة: ${a.currency.nameAr}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                a.instructions?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CopyLine(label: String, value: String, copy: (String, String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { copy(label, value) }) { Text("نسخ") }
    }
}

@Composable
private fun ReceiptPreview(bytes: ByteArray, onRemove: () -> Unit) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "صورة الإيصال",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(Modifier.weight(1f)) {
            Text("تم إرفاق الإيصال", style = MaterialTheme.typography.bodyMedium, color = Success)
            Text("${bytes.size / 1024} كيلوبايت", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Clear, contentDescription = "إزالة الصورة") }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
