@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.expenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.AccountPickerDialog
import com.dejavu.admin.ui.ChipFilter
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.JournalEntriesCard
import com.dejavu.admin.ui.TextDialog
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.label
import com.dejavu.admin.ui.tint
import com.dejavu.core.admin.ExpenseCategory
import com.dejavu.core.admin.ExpenseDetail
import com.dejavu.core.admin.ExpensePage
import com.dejavu.core.admin.ExpenseRepository
import com.dejavu.core.admin.ExpenseRequest
import com.dejavu.core.admin.ExpenseStatus
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.LedgerAccount
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

// ------------------------------------------------------------------- list ---

class ExpensesViewModel(private val repo: ExpenseRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<ExpensePage>>(Load.Loading)
    val state = _state.asStateFlow()
    var status: ExpenseStatus? = null
        private set

    fun filter(s: ExpenseStatus?) {
        status = s
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        if (reset || _state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.list(status).toLoad() }
    }
}

@Composable
fun ExpensesScreen(session: StaffSession.LoggedIn, onBack: () -> Unit, openExpense: (String) -> Unit, newExpense: () -> Unit) {
    val vm = adminViewModel { ExpensesViewModel(it.expenses) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("المصروفات", onBack) },
        floatingActionButton = {
            if (session.can(Perm.EXPENSES_WRITE)) FloatingActionButton(onClick = newExpense) { Icon(Icons.Filled.Add, contentDescription = "مصروف جديد") }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ChipFilter(listOf(null) + ExpenseStatus.entries, vm.status, { it?.label ?: "الكل" }, vm::filter)
            LoadContent(state, onRetry = { vm.load() }) { page ->
                if (page.items.isEmpty()) {
                    EmptyState(Icons.AutoMirrored.Filled.List, "لا توجد مصروفات")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { SummaryRow("الإجمالي (${page.total})", Money.format(page.totals.amount), strong = true) }
                        items(page.items, key = { it.id }) { e ->
                            Card(onClick = { openExpense(e.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(e.description, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        StatusChip(e.status.label, e.status.tint())
                                    }
                                    Text("${e.number} · ${e.category.name} · ${formatDate(e.expenseDate)}", style = MaterialTheme.typography.bodySmall)
                                    Text(Money.format(Money.parse(e.amount).add(Money.parse(e.taxAmount))), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- detail ---

class ExpenseDetailViewModel(private val id: String, private val repo: ExpenseRepository, private val finance: FinanceRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<ExpenseDetail>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _accounts = MutableStateFlow<List<LedgerAccount>>(emptyList())
    val accounts = _accounts.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.get(id).toLoad() }
        viewModelScope.launch { _accounts.value = finance.cashAccounts() }
    }

    fun approve(paidFrom: String?) = act(if (paidFrom == null) "تم الاعتماد كمصروف مستحق" else "تم الاعتماد والسداد") { repo.approve(id, paidFrom) }
    fun pay(paidFrom: String) = act("تم السداد") { repo.pay(id, paidFrom) }
    fun cancel(reason: String) = act("تم الإلغاء") { repo.cancel(id, reason) }

    private fun act(done: String, block: suspend () -> Result<ExpenseDetail>) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block().orSay()?.let {
                _state.value = Load.Ready(it)
                say(done)
            }
            _busy.value = false
        }
    }
}

private enum class ExpenseAction { APPROVE_PAID, PAY, CANCEL }

@Composable
fun ExpenseDetailScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel(key = "expense:$id") { ExpenseDetailViewModel(id, it.expenses, it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var action by remember { mutableStateOf<ExpenseAction?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("مصروف", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { e ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.description, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            StatusChip(e.status.label, e.status.tint())
                        }
                        Text("${e.number} · ${e.category.name} · ${formatDate(e.expenseDate)}", style = MaterialTheme.typography.bodySmall)
                        e.payee?.let { Text("المستفيد: $it") }
                        SummaryRow("المبلغ", Money.format(e.amount))
                        SummaryRow("ضريبة قابلة للاسترداد", Money.format(e.taxAmount))
                        SummaryRow("الإجمالي", Money.format(e.total), strong = true)
                        e.createdBy?.let { Text("أنشأه: ${it.name}", style = MaterialTheme.typography.bodySmall) }
                        e.approvedBy?.let { Text("اعتمده: ${it.name}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (e.status == ExpenseStatus.DRAFT && session.can(Perm.EXPENSES_APPROVE)) {
                            Button(onClick = { action = ExpenseAction.APPROVE_PAID }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("اعتماد — دُفع الآن") }
                            OutlinedButton(onClick = { vm.approve(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("اعتماد — يُدفع لاحقاً") }
                        }
                        if (e.status == ExpenseStatus.APPROVED && session.can(Perm.EXPENSES_WRITE) && session.can(Perm.PAYMENTS_RECORD)) {
                            Button(onClick = { action = ExpenseAction.PAY }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("سداد") }
                        }
                        if ((e.status == ExpenseStatus.DRAFT || e.status == ExpenseStatus.APPROVED) && session.can(Perm.EXPENSES_APPROVE)) {
                            OutlinedButton(onClick = { action = ExpenseAction.CANCEL }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("إلغاء", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                item { JournalEntriesCard(e.journalEntries) }
            }
        }
    }

    when (action) {
        ExpenseAction.APPROVE_PAID -> AccountPickerDialog("دُفع من", accounts, { action = null }) { vm.approve(it.id) }
        ExpenseAction.PAY -> AccountPickerDialog("السداد من", accounts, { action = null }) { vm.pay(it.id) }
        ExpenseAction.CANCEL -> TextDialog("إلغاء المصروف", "السبب", "إلغاء", { action = null }) { vm.cancel(it) }
        null -> {}
    }
}

// -------------------------------------------------------------------- new ---

class NewExpenseViewModel(private val repo: ExpenseRepository) : BaseViewModel() {
    private val _categories = MutableStateFlow<List<ExpenseCategory>>(emptyList())
    val categories = _categories.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created = _created.asSharedFlow()

    init {
        viewModelScope.launch { repo.categories().orSay()?.let { _categories.value = it.filter { c -> c.isActive } } }
    }

    fun save(request: ExpenseRequest) {
        _saving.value = true
        viewModelScope.launch {
            repo.create(request).orSay()?.let { _created.tryEmit(it.id) }
            _saving.value = false
        }
    }
}

@Composable
fun NewExpenseScreen(onBack: () -> Unit, created: (String) -> Unit) {
    val vm = adminViewModel { NewExpenseViewModel(it.expenses) }
    val categories by vm.categories.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var tax by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var payee by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Aden")).toString()) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.created.collect { created(it) } }

    val validAmount = amount.toBigDecimalOrNull()?.let { it.signum() > 0 && it.scale() <= 2 } == true
    val validTax = tax.isBlank() || tax.toBigDecimalOrNull()?.let { it.signum() >= 0 && it.scale() <= 2 } == true
    val validDate = runCatching { LocalDate.parse(date) }.isSuccess
    val canSave = categoryId != null && validAmount && validTax && validDate && description.trim().length >= 3 && !saving

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("مصروف جديد", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("التصنيف", style = MaterialTheme.typography.titleSmall)
            categories.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable { categoryId = c.id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = c.id == categoryId, onClick = { categoryId = c.id })
                    Text(c.name)
                }
            }
            OutlinedTextField(value = description, onValueChange = { description = it.take(500) }, label = { Text("الوصف") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("المبلغ قبل الضريبة") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tax, onValueChange = { tax = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("ضريبة القيمة المضافة (اختياري)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = payee, onValueChange = { payee = it.take(150) }, label = { Text("المستفيد (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = date, onValueChange = { date = it.take(10) }, label = { Text("التاريخ (YYYY-MM-DD)") }, singleLine = true,
                isError = !validDate, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, onClick = { date = LocalDate.now(ZoneId.of("Asia/Aden")).toString() }, label = { Text("اليوم") })
                FilterChip(selected = false, onClick = { date = LocalDate.now(ZoneId.of("Asia/Aden")).minusDays(1).toString() }, label = { Text("أمس") })
            }
            Button(
                onClick = {
                    vm.save(ExpenseRequest(categoryId!!, amount, date, description.trim(), tax.ifBlank { null }, payee.trim().ifBlank { null }))
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("حفظ كمسودة") }
            Text("يُعتمد المصروف من صفحته بعد الحفظ، وعندها يُسجَّل القيد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
