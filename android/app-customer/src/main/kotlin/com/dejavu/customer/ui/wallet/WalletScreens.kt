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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.glass
import com.dejavu.customer.ui.components.GlassBottomPanel
import com.dejavu.customer.ui.components.goldChipColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.dejavu.core.data.StoreExtrasRepository
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
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.customer.ui.components.SectionTitle
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.media.ImagePicker
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import com.dejavu.customer.ui.appContainer
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

class WalletViewModel(private val repo: WalletRepository, private val extras: StoreExtrasRepository? = null) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<WalletData>>(Load.Loading)
    val state = _state.asStateFlow()

    private val _redeeming = MutableStateFlow(false)
    val redeeming = _redeeming.asStateFlow()

    /** Adds a gift card's value (code GC-XXXX-XXXX) to the wallet; [done] closes the dialog. */
    fun redeemGiftCard(code: String, done: () -> Unit) {
        val store = extras ?: return
        if (_redeeming.value) return
        _redeeming.value = true
        viewModelScope.launch {
            store.redeemGiftCard(code)
                .onSuccess { r ->
                    say("تمت إضافة ${Money.plain(Money.parse(r.amount), Currency.of(r.currency))} إلى رصيدك 🎁")
                    done()
                    load()
                }
                .onFailure { say(it.userMessage()) }
            _redeeming.value = false
        }
    }

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

    // Editing / withdrawing a request still under review.

    private val _editing = MutableStateFlow<TopUpEdit?>(null)
    val editing = _editing.asStateFlow()

    /** A new deposit slip for the request being edited, as compressed JPEG bytes (null = keep the current one). */
    private val _editReceipt = MutableStateFlow<ByteArray?>(null)
    val editReceipt = _editReceipt.asStateFlow()

    /** Active bank accounts, for moving a request to another account that takes the same currency. */
    private val _accounts = MutableStateFlow<List<StoreBankAccount>>(emptyList())
    val accounts = _accounts.asStateFlow()

    private val _deleting = MutableStateFlow(false)
    val deleting = _deleting.asStateFlow()

    fun startEdit(t: TopUp) {
        _editReceipt.value = null
        _editing.value = TopUpEdit(
            topUp = t,
            amount = Money.parse(t.amount).stripTrailingZeros().toPlainString(),
            reference = t.reference.orEmpty(),
            note = t.note.orEmpty(),
            bankAccountId = t.bankAccount?.id,
        )
        if (_accounts.value.isEmpty()) {
            viewModelScope.launch {
                repo.bankAccounts().onSuccess { list -> _accounts.value = list.filter { it.isActive }.sortedBy { it.sortOrder } }
            }
        }
    }

    fun updateEdit(change: TopUpEdit.() -> TopUpEdit) {
        _editing.value = _editing.value?.change()
    }

    fun setEditReceipt(bytes: ByteArray?) {
        _editReceipt.value = bytes
    }

    fun cancelEdit() {
        if (_editing.value?.saving == true) return
        _editing.value = null
        _editReceipt.value = null
    }

    fun saveEdit() {
        val e = _editing.value ?: return
        if (e.saving) return
        val amount = parseAmount(e.amount)
        if (topUpAmountError(e.amount, e.topUp.currency) != null || amount == null) {
            _editing.value = e.copy(showErrors = true)
            return
        }
        _editing.value = e.copy(saving = true)
        viewModelScope.launch {
            repo.updateTopUp(
                id = e.topUp.id,
                currency = null,
                amount = amount.toPlainString(),
                receipt = _editReceipt.value,
                mimeType = "image/jpeg",
                bankAccountId = e.bankAccountId,
                reference = e.reference.trim(),
                note = e.note.trim(),
            )
                .onSuccess {
                    _editing.value = null
                    _editReceipt.value = null
                    say("تم تعديل طلب الشحن")
                    load()
                }
                .onFailure {
                    _editing.value = _editing.value?.copy(saving = false)
                    say(it.userMessage())
                }
        }
    }

    fun delete(t: TopUp, done: () -> Unit) {
        if (_deleting.value) return
        _deleting.value = true
        viewModelScope.launch {
            repo.deleteTopUp(t.id)
                .onSuccess {
                    say("تم حذف طلب الشحن")
                    load()
                }
                .onFailure { say(it.userMessage()) }
            _deleting.value = false
            done()
        }
    }
}

/** The edit form of a top-up request under review (its currency stays the same). */
data class TopUpEdit(
    val topUp: TopUp,
    val amount: String,
    val reference: String,
    val note: String,
    val bankAccountId: String?,
    val saving: Boolean = false,
    val showErrors: Boolean = false,
)

/** Balances per currency, top-up requests and recent movements. */
@Composable
fun WalletScreen(onBack: () -> Unit, topUp: () -> Unit) {
    val vm = appViewModel { WalletViewModel(it.wallet, it.extras) }
    val redeeming by vm.redeeming.collectAsStateWithLifecycle()
    var giftCard by rememberSaveable { mutableStateOf(false) }
    var giving by rememberSaveable { mutableStateOf(false) }
    val state by vm.state.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val editReceipt by vm.editReceipt.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<TopUp?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberImagePicker { bytes -> vm.setEditReceipt(bytes) }
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
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { giftCard = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Star, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("استخدام بطاقة هدية")
                        }
                        if (balances.any { Money.parse(it.balance).signum() > 0 }) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { giving = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Favorite, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("إهداء رصيد لعميل آخر")
                            }
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
                    items(data.topUps, key = { "t" + it.id }) { t ->
                        TopUpRow(t, onEdit = { vm.startEdit(t) }, onDelete = { confirmDelete = t })
                    }
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
                    items(data.wallet.entries, key = { "e" + it.id }) { e -> EntryRow(e) }
                }
            }
        }
    }

    editing?.let { e ->
        EditTopUpDialog(
            edit = e,
            receipt = editReceipt,
            accounts = accounts.filter { it.takes(e.topUp.currency) },
            picker = picker,
            onChange = vm::updateEdit,
            onRemoveReceipt = { vm.setEditReceipt(null) },
            onSave = vm::saveEdit,
            onDismiss = vm::cancelEdit,
        )
    }

    if (giving) {
        val balances = (state as? com.dejavu.core.ui.Load.Ready)?.value?.wallet?.balances.orEmpty()
        GiftBalanceDialog(
            balances = balances,
            snackbar = snackbar,
            onSent = {
                giving = false
                vm.load()
            },
            onDismiss = { giving = false },
        )
    }

    if (giftCard) {
        GiftCardDialog(
            busy = redeeming,
            onRedeem = { code -> vm.redeemGiftCard(code) { giftCard = false } },
            onDismiss = { if (!redeeming) giftCard = false },
        )
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = null },
            title = { Text("حذف طلب الشحن") },
            text = { Text("هل تريد حذف طلب شحن ${Money.plain(Money.parse(t.amount), t.currency)} (${t.number})؟ لا يمكن التراجع عن ذلك.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(t) { confirmDelete = null } }, enabled = !deleting) {
                    if (deleting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("حذف", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }, enabled = !deleting) { Text("إلغاء") } },
        )
    }
}

/** Typing a gift card's code (GC-XXXX-XXXX): its value goes to the wallet. */
@Composable
private fun GiftCardDialog(busy: Boolean, onRedeem: (String) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val normalized = code.trim().uppercase().replace(" ", "")
    val valid = Regex("^GC-[A-Z0-9]{4}-[A-Z0-9]{4}$").matches(normalized)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("استخدام بطاقة هدية") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "اكتب الكود المطبوع على البطاقة، وتُضاف قيمتها إلى رصيدك فوراً.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(14) },
                    label = { Text("كود البطاقة") },
                    placeholder = { Text("GC-XXXX-XXXX") },
                    singleLine = true,
                    enabled = !busy,
                    isError = normalized.length >= 12 && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRedeem(normalized) }, enabled = valid && !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("استخدام", color = Liquid.GoldDeep)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("إلغاء") } },
    )
}

@Composable
private fun EditTopUpDialog(
    edit: TopUpEdit,
    receipt: ByteArray?,
    accounts: List<StoreBankAccount>,
    picker: ImagePicker,
    onChange: (TopUpEdit.() -> TopUpEdit) -> Unit,
    onRemoveReceipt: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currency = edit.topUp.currency
    val amountError = if (edit.showErrors) topUpAmountError(edit.amount, currency) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل طلب الشحن ${edit.topUp.number}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = edit.amount,
                    onValueChange = { v -> onChange { copy(amount = v.take(16)) } },
                    label = { Text("المبلغ المحوَّل") },
                    suffix = { Text(currency.symbol) },
                    isError = amountError != null,
                    supportingText = amountError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.reference,
                    onValueChange = { v -> onChange { copy(reference = v.take(100)) } },
                    label = { Text("رقم العملية / المرجع (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.note,
                    onValueChange = { v -> onChange { copy(note = v.take(500)) } },
                    label = { Text("ملاحظة (اختياري)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (accounts.size > 1) {
                    Text("الحساب البنكي", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                    accounts.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = a.id == edit.bankAccountId, onClick = { onChange { copy(bankAccountId = a.id) } }, role = Role.RadioButton)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = a.id == edit.bankAccountId,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Liquid.GoldDeep),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(a.bankName, style = MaterialTheme.typography.bodyMedium)
                                Text(a.accountNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text("صورة إيصال الإيداع", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                if (receipt != null) {
                    ReceiptPreview(receipt, onRemove = onRemoveReceipt)
                } else {
                    Text(
                        "يبقى الإيصال الحالي ما لم ترفق صورة جديدة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = picker.fromGallery, enabled = !edit.saving, modifier = Modifier.weight(1f)) { Text("من المعرض") }
                    OutlinedButton(onClick = picker.fromCamera, enabled = !edit.saving, modifier = Modifier.weight(1f)) { Text("التقاط صورة") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !edit.saving) {
                if (edit.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("حفظ", color = Liquid.GoldDeep)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !edit.saving) { Text("إلغاء") } },
    )
}

@Composable
private fun BalanceCard(b: WalletBalance) {
    val balance = Money.parse(b.balance)
    // Liquid-gold highlight card, like the admin app's headline tiles.
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .glass(Liquid.CardShape, elevation = 16.dp)
            .background(Liquid.GoldBrush)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.AccountBox, contentDescription = null, tint = Color.White)
            Text("الرصيد المتاح · ${b.currency.nameAr}", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
        Text(
            Money.plain(balance.max(BigDecimal.ZERO), b.currency),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        val owed = Money.parse(b.owed)
        if (owed.signum() > 0) {
            Text(
                "مستحق عليك: ${Money.plain(owed, b.currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TopUpRow(t: TopUp, onEdit: () -> Unit, onDelete: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), elevation = 10.dp, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Money.plain(Money.parse(t.amount), t.currency), style = MaterialTheme.typography.titleMedium, color = Liquid.GoldDeep)
                    Text("${t.number} · ${formatDateTime(t.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(t.status.label, t.status.color())
            }
            t.bankAccount?.let { Text("إلى: ${it.bankName} (${it.accountNumber})", style = MaterialTheme.typography.bodySmall) }
            t.reference?.takeIf { it.isNotBlank() }?.let { Text("رقم العملية: $it", style = MaterialTheme.typography.bodySmall) }
            if (t.status == TopUpStatus.REJECTED) {
                t.rejectReason?.let { Text("سبب الرفض: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            // Still under review: the customer may correct or withdraw it.
            if (t.status == TopUpStatus.PENDING) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = null, tint = Liquid.GoldDeep, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تعديل", color = Liquid.GoldDeep)
                    }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("حذف", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: WalletEntry) {
    val amount = Money.parse(e.amount)
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), elevation = 8.dp, contentPadding = 14.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
    private val _done = MutableSharedFlow<TopUp>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    /** The slip once uploaded and read: its file id, what was read off it, and whether it was used before. */
    private val _slip = MutableStateFlow<SlipRead?>(null)
    val slip = _slip.asStateFlow()
    private var prefilled = false

    /** "Pay now" from checkout: the amount and currency still to pay. */
    fun prefill(amount: String?, currencyCode: String?) {
        if (prefilled) return
        prefilled = true
        val c = currencyCode?.let { Currency.of(it) }
        _form.value = _form.value.copy(amount = amount ?: _form.value.amount, currency = c ?: _form.value.currency)
        pinnedCurrency = c
        // Accounts already loaded: pick one that takes this currency.
        val loaded = (_accounts.value as? Load.Ready)?.value
        if (c != null && loaded != null) {
            _form.value = _form.value.copy(bankAccountId = loaded.firstOrNull { it.takes(c) }?.id ?: _form.value.bankAccountId)
        }
    }
    private var pinnedCurrency: Currency? = null

    /** Currencies the store sells in; bank accounts may add others. */
    val available = currencies.available

    init { load() }

    fun load() {
        _accounts.value = Load.Loading
        viewModelScope.launch {
            val result = repo.bankAccounts().map { list -> list.filter { it.isActive }.sortedBy { it.sortOrder } }
            _accounts.value = result.toLoad()
            result.getOrNull()?.let { list ->
                val pinned = pinnedCurrency
                if (pinned != null && _form.value.bankAccountId == null) {
                    _form.value = _form.value.copy(bankAccountId = list.firstOrNull { it.takes(pinned) }?.id ?: list.firstOrNull()?.id)
                } else if (_form.value.currency == null) {
                    val first = list.firstOrNull()
                    _form.value = _form.value.copy(
                        currency = first?.acceptedCurrencies?.firstOrNull() ?: available.value.firstOrNull(),
                        bankAccountId = first?.id,
                    )
                }
            }
        }
    }

    /** The chosen bank account's currencies; without any account, the ones the store sells in. */
    fun currencyChoices(accounts: List<StoreBankAccount>, bankAccountId: String?): List<Currency> {
        val account = accounts.firstOrNull { it.id == bankAccountId }
        return account?.acceptedCurrencies ?: if (accounts.isEmpty()) available.value else accounts.flatMap { it.acceptedCurrencies }.distinct()
    }

    fun chooseCurrency(c: Currency, accounts: List<StoreBankAccount>) {
        val f = _form.value
        val keep = accounts.any { it.id == f.bankAccountId && it.takes(c) }
        _form.value = f.copy(currency = c, bankAccountId = if (keep) f.bankAccountId else accounts.firstOrNull { it.takes(c) }?.id)
    }

    /** Only the account's own currencies can be sent to it: keeps the chosen one if it takes it, else its first. */
    fun chooseAccount(a: StoreBankAccount) {
        val f = _form.value
        val currency = f.currency?.takeIf { a.takes(it) } ?: a.acceptedCurrencies.firstOrNull() ?: a.currency
        _form.value = f.copy(bankAccountId = a.id, currency = currency)
    }

    fun update(change: TopUpForm.() -> TopUpForm) {
        _form.value = _form.value.change()
    }

    fun setReceipt(bytes: ByteArray?) {
        _receipt.value = bytes
        _slip.value = null
        bytes ?: return
        _slip.value = SlipRead(reading = true)
        viewModelScope.launch {
            repo.uploadSlip(bytes)
                .onSuccess { (fileId, scan) ->
                    if (_receipt.value !== bytes) return@onSuccess
                    _slip.value = SlipRead(fileId = fileId, reference = scan.reference, amount = scan.amount, duplicate = scan.duplicate)
                    val f = _form.value
                    _form.value = f.copy(
                        reference = f.reference.ifBlank { scan.reference.orEmpty() },
                        amount = f.amount.ifBlank { scan.amount.orEmpty() },
                    )
                    scan.duplicate?.let { say(it) }
                }
                .onFailure { if (_receipt.value === bytes) _slip.value = null }
        }
    }

    fun amountError(f: TopUpForm = _form.value): String? = topUpAmountError(f.amount, f.currency)

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
            repo.requestTopUp(currency, amount.toPlainString(), receipt, "image/jpeg", f.bankAccountId, f.reference.trim(), f.note.trim(), _slip.value?.fileId)
                .onSuccess { _done.tryEmit(it) }
                .onFailure { say(it.userMessage()) }
            _form.value = _form.value.copy(submitting = false)
        }
    }
}

/** The uploaded slip: [reading] while it is being read off the photo. */
data class SlipRead(
    val reading: Boolean = false,
    val fileId: String? = null,
    val reference: String? = null,
    val amount: String? = null,
    val duplicate: String? = null,
)

/** Accepts Arabic-Indic digits and the Arabic decimal separator too. */
private fun parseAmount(raw: String): BigDecimal? {
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

private fun topUpAmountError(raw: String, currency: Currency?): String? {
    currency ?: return "اختر العملة"
    val amount = parseAmount(raw) ?: return "أدخل المبلغ المحوَّل"
    if (amount.signum() <= 0) return "المبلغ يجب أن يكون أكبر من صفر"
    if (amount.stripTrailingZeros().scale() > currency.decimals) {
        return if (currency.decimals == 0) "أدخل مبلغاً بدون كسور" else "أدخل المبلغ بحد أقصى ${currency.decimals} أرقام عشرية"
    }
    return null
}

/** Bank transfer to one of the store's accounts, with the deposit slip, reviewed by staff. */
@Composable
fun TopUpScreen(onBack: () -> Unit, submitted: (TopUp) -> Unit, amount: String? = null, currency: String? = null, forOrder: Boolean = false) {
    val vm = appViewModel { TopUpViewModel(it.wallet, it.currencies) }
    LaunchedEffect(vm) { vm.prefill(amount, currency) }
    val slip by vm.slip.collectAsStateWithLifecycle()
    val accountsState by vm.accounts.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val receipt by vm.receipt.collectAsStateWithLifecycle()
    val available by vm.available.collectAsStateWithLifecycle()
    val rates by appContainer().currencies.rates.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberImagePicker { bytes -> vm.setReceipt(bytes) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.done.collect { submitted(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(if (forOrder) "الدفع المباشر" else "شحن الرصيد", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            GlassBottomPanel {
                Button(onClick = vm::submit, enabled = !form.submitting, modifier = Modifier.fillMaxWidth()) {
                    if (form.submitting) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text(if (forOrder) "إرسال الحوالة وتأكيد الطلب" else "إرسال طلب الشحن")
                }
            }
        },
    ) { padding ->
        LoadContent(accountsState, onRetry = vm::load, modifier = Modifier.padding(padding)) { accounts ->
            // Recompose when the currency list arrives or another account is chosen.
            val choices = remember(accounts, available, form.bankAccountId) { vm.currencyChoices(accounts, form.bankAccountId) }
            val imageOf = { c: Currency -> rates?.rate(c)?.imageUrl }
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (forOrder) {
                    Text(
                        "حوّل قيمة الطلب بأي عملة يقبلها الحساب، ثم أرفق صورة الإشعار. يُضاف المبلغ إلى رصيدك ويُخصم منه سعر الطلب مباشرة.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text("1. حوّل المبلغ إلى أحد حساباتنا", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                if (accounts.isEmpty()) {
                    Text("لا توجد حسابات بنكية متاحة حالياً، تواصل معنا.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                accounts.forEach { a ->
                    BankAccountCard(a, selected = a.id == form.bankAccountId, imageOf = imageOf, onSelect = { vm.chooseAccount(a) }) { label, value ->
                        copyToClipboard(context, label, value)
                        scope.launch { snackbar.showSnackbar("تم نسخ $label") }
                    }
                }

                // Only the currencies the chosen account takes.
                Text("2. اختر عملة التحويل", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { c ->
                        FilterChip(
                            selected = c == form.currency,
                            onClick = { vm.chooseCurrency(c, accounts) },
                            label = { Text("${c.nameAr} (${c.symbol})") },
                            leadingIcon = { CurrencyArt(c.code, imageOf(c), height = 18.dp) },
                            colors = goldChipColors(),
                        )
                    }
                }

                Text("3. بيانات التحويل", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
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

                Text("4. صورة إيصال الإيداع", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                val photo = receipt
                if (photo != null) {
                    ReceiptPreview(photo, onRemove = { vm.setReceipt(null) })
                    val read = slip
                    when {
                        read == null -> {}
                        read.reading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Liquid.Gold, strokeWidth = 2.dp)
                            Text("نقرأ رقم الحوالة من الصورة…", style = MaterialTheme.typography.bodySmall)
                        }
                        read.duplicate != null -> Text(read.duplicate, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        read.reference != null -> Text("رقم الحوالة: ${read.reference}", color = Liquid.GoldDeep, style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (form.showErrors) {
                    Text("أرفق صورة الإيصال", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = picker.fromGallery, modifier = Modifier.weight(1f)) { Text("من المعرض") }
                    OutlinedButton(onClick = picker.fromCamera, modifier = Modifier.weight(1f)) { Text("التقاط صورة") }
                }
                Text(
                    "يُضاف المبلغ إلى رصيدك فور الإرسال ويتحقق فريقنا من وصول التحويل. لا تُقبل الحوالة نفسها مرتين.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BankAccountCard(
    a: StoreBankAccount,
    selected: Boolean,
    imageOf: (Currency) -> String?,
    onSelect: () -> Unit,
    copy: (String, String) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .glass(Liquid.CardShape, elevation = if (selected) 14.dp else 8.dp)
            .then(
                if (selected) {
                    Modifier.background(Liquid.GoldPale.copy(alpha = 0.35f)).border(1.5.dp, Liquid.Gold, Liquid.CardShape)
                } else {
                    Modifier
                },
            )
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Liquid.GoldDeep))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    a.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        ProductImage(url, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)), contentDescription = a.bankName)
                    }
                    Text(a.bankName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
                Text("باسم: ${a.accountName}", style = MaterialTheme.typography.bodySmall)
                CopyLine("رقم الحساب", a.accountNumber, copy)
                a.iban?.takeIf { it.isNotBlank() }?.let { CopyLine("الآيبان", it, copy) }
                Text("العملات المقبولة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.acceptedCurrencies.forEach { c -> CurrencyPill(c, imageOf(c)) }
                }
                a.instructions?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Small currency chip: its note / coin picture and name. */
@Composable
private fun CurrencyPill(c: Currency, imageUrl: String?) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Liquid.GoldPale.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CurrencyArt(c.code, imageUrl, height = 16.dp)
        Text(c.nameAr, style = MaterialTheme.typography.labelMedium)
    }
}

/** Every currency the account takes (the main one first). */
private val StoreBankAccount.acceptedCurrencies: List<Currency>
    get() = accepted.mapNotNull { code -> code.trim().uppercase().takeIf { it.isNotEmpty() }?.let { Currency.of(it) } }
        .ifEmpty { listOf(currency) }
        .distinct()

private fun StoreBankAccount.takes(c: Currency) = c in acceptedCurrencies

@Composable
private fun CopyLine(label: String, value: String, copy: (String, String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { copy(label, value) }) { Text("نسخ", color = Liquid.GoldDeep) }
    }
}

@Composable
private fun ReceiptPreview(bytes: ByteArray, onRemove: () -> Unit) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    Row(
        Modifier.fillMaxWidth().glass(Liquid.CardShape, elevation = 8.dp).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "صورة الإيصال",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)),
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
