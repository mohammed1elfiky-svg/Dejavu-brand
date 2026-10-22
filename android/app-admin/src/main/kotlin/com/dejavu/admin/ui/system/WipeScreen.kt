package com.dejavu.admin.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.WIPE_PHRASE
import com.dejavu.core.admin.WipeSummary
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WipeViewModel(private val repo: InventoryRepository) : BaseViewModel() {
    private val _preview = MutableStateFlow<Load<WipeSummary>>(Load.Loading)

    /** What a wipe would delete now (dry run). */
    val preview = _preview.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _done = MutableStateFlow<WipeSummary?>(null)
    val done = _done.asStateFlow()

    fun load() {
        viewModelScope.launch { _preview.value = repo.wipeTestData(dryRun = true).toLoad() }
    }

    fun wipe() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.wipeTestData(dryRun = false).orSay()?.let { _done.value = it }
            _busy.value = false
        }
    }
}

private val LABELS = linkedMapOf(
    "products" to "المنتجات",
    "variants" to "المتغيرات والباركودات",
    "categories" to "الأقسام",
    "brands" to "الماركات",
    "orders" to "الطلبات والفواتير",
    "payments" to "المدفوعات",
    "journalEntries" to "القيود المحاسبية",
    "purchaseOrders" to "أوامر الشراء",
    "suppliers" to "الموردون",
    "expenses" to "المصروفات",
    "walletTopUps" to "طلبات شحن الرصيد",
    "promotions" to "العروض",
    "files" to "الصور والملفات",
    "customers" to "العملاء",
    "users" to "الموظفون (عدا المدير)",
    "cashierDrawers" to "صناديق الكاشير",
)

/** Deletes all test data before going live, after a preview and the typed confirmation. */
@Composable
fun WipeDataScreen(onBack: () -> Unit, done: () -> Unit) {
    val vm = adminViewModel { WipeViewModel(it.inventory) }
    val preview by vm.preview.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.done.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var typed by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("تصفير البيانات التجريبية", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(preview, onRetry = vm::load, modifier = Modifier.padding(padding)) { p ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("سيُحذف نهائياً", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        (result ?: p).deleted.let { counts ->
                            LABELS.forEach { (key, label) ->
                                Text("$label: ${counts[key] ?: 0}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        Text(
                            "يبقى: حساب المدير (${p.keptUsers.joinToString()})، العملات وأسعارها، دليل الحسابات والسنوات المالية، الأدوار والصلاحيات، المخازن والخزائن والبنوك، الحسابات البنكية للمتجر وإعدادات النظام. يبقى العميل النقدي للكاشير.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                if (result != null) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("تم تصفير البيانات ✓ قاعدة البيانات جاهزة للتشغيل الفعلي.", style = MaterialTheme.typography.titleSmall)
                            Button(onClick = done, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("تم") }
                        }
                    }
                } else {
                    item {
                        Text("لا يمكن التراجع عن هذه العملية. للتأكيد اكتب: $WIPE_PHRASE", style = MaterialTheme.typography.bodyMedium)
                    }
                    item {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("عبارة التأكيد") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Button(
                            onClick = { confirm = true },
                            enabled = !busy && typed.trim() == WIPE_PHRASE,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("حذف كل البيانات التجريبية")
                        }
                    }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog("تأكيد نهائي", "هل أنت متأكد؟ ستُحذف كل البيانات المذكورة نهائياً.", "حذف نهائي", onDismiss = { confirm = false }) {
            confirm = false
            vm.wipe()
        }
    }
}
