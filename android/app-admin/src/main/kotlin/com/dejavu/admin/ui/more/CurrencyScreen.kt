package com.dejavu.admin.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.core.admin.ExchangeRates
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

class CurrencyViewModel(private val repo: FinanceRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<ExchangeRates>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.exchangeRates().toLoad() }
    }

    fun save(rate: String, note: String, done: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.setRate(Currency.YER, rate, note).orSay()?.let {
                _state.value = Load.Ready(it)
                say("تم تحديث سعر الصرف. الطلبات الجديدة ستُحسب به")
                done()
            }
            _busy.value = false
        }
    }
}

/** Base currency (SAR) and the YER rate used to price new orders. */
@Composable
fun CurrencyScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel { CurrencyViewModel(it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val canEdit = session.can(Perm.SETTINGS_MANAGE)
    var rate by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("العملة وسعر الصرف", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { fx ->
            val current = fx.rate(Currency.YER)
            val newRate = rate.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    InfoCard {
                        SummaryRow("العملة الأساسية", "${Currency.SAR.nameAr} (${Currency.SAR.symbol})", strong = true)
                        Text(
                            "الأسعار والتكاليف والقيود المحاسبية والتقارير كلها بالريال السعودي.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    InfoCard {
                        Text("${Currency.YER.nameAr} (${Currency.YER.symbol})", style = MaterialTheme.typography.titleSmall)
                        if (current != null) {
                            SummaryRow("سعر الصرف الحالي", "1 ر.س = ${Money.plain(BigDecimal(current.rate), Currency.YER)}", strong = true)
                            current.updatedAt?.let { SummaryRow("آخر تحديث", formatDateTime(it)) }
                            SummaryRow("مثال: منتج بـ 100 ر.س", Money.format("100", Currency.YER, current.rate))
                        } else {
                            Text("لم يُحدَّد سعر صرف بعد؛ البيع بالريال اليمني متوقف.", color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            "يُثبَّت السعر في كل طلب لحظة إنشائه، فتغييره لا يؤثر على الطلبات السابقة أو فواتيرها.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (canEdit) {
                    item {
                        InfoCard {
                            Text("تحديث سعر الصرف", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = rate,
                                onValueChange = { rate = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("كم ريال يمني يساوي 1 ريال سعودي؟") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = note,
                                onValueChange = { note = it.take(255) },
                                label = { Text("ملاحظة (اختياري)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            newRate?.let {
                                Text(
                                    "100 ر.س = ${Money.format(BigDecimal(100), Currency.YER, it)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = { confirming = true }, enabled = newRate != null && !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("حفظ السعر الجديد")
                            }
                        }
                    }
                }
                if (fx.history.isNotEmpty()) {
                    item { Text("سجل الأسعار", style = MaterialTheme.typography.titleSmall) }
                    items(fx.history, key = { it.id }) { h ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Money.plain(BigDecimal(h.rate), Currency.YER), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(formatDateTime(h.effectiveAt), style = MaterialTheme.typography.bodySmall)
                            }
                            val by = listOfNotNull(h.createdBy?.name, h.note).joinToString(" · ")
                            if (by.isNotEmpty()) Text(by, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }

    if (confirming) {
        ConfirmDialog(
            "تأكيد سعر الصرف",
            "سيُستخدم 1 ر.س = $rate ر.ي لكل الطلبات الجديدة بالريال اليمني من الآن.",
            "حفظ",
            onDismiss = { confirming = false },
        ) { vm.save(rate, note) { rate = ""; note = "" } }
    }
}
