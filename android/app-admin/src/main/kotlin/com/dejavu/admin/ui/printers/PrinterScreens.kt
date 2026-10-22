@file:OptIn(ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.printers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.print.PairedPrinter
import com.dejavu.admin.print.Printers
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.print.LabelLanguage
import com.dejavu.core.admin.print.PrinterLink
import com.dejavu.core.admin.print.PrinterSettings
import com.dejavu.core.admin.print.PrinterTarget
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import kotlinx.coroutines.launch

/** Receipt printer and barcode-label printer: how to reach them, paper/label size and a test print. */
@Composable
fun PrinterSettingsScreen(onBack: () -> Unit) {
    val container = adminContainer()
    val store = container.printers
    val settings by store.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var testing by remember { mutableStateOf<String?>(null) }

    fun test(which: String, job: suspend () -> Result<Unit>) {
        if (testing != null) return
        testing = which
        scope.launch {
            val result = job()
            testing = null
            snackbar.showSnackbar(result.fold({ "تم إرسال الطباعة التجريبية ✓" }, { it.message ?: "تعذّرت الطباعة" }))
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("الطابعات", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Heading("طابعة الفواتير (ESC/POS)", "طابعة حرارية 58 أو 80 مم عبر البلوتوث أو الشبكة")
                    TargetEditor(settings.receipt.target) { t -> store.update { it.copy(receipt = it.receipt.copy(target = t)) } }
                    Text("عرض الورق", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(58, 80).forEach { mm ->
                            FilterChip(settings.receipt.paperMm == mm, { store.update { it.copy(receipt = it.receipt.copy(paperMm = mm)) } }, label = { Text("$mm مم") })
                        }
                    }
                    SwitchRow("طباعة الفاتورة تلقائياً بعد إتمام البيع", settings.receipt.autoPrint) { v ->
                        store.update { it.copy(receipt = it.receipt.copy(autoPrint = v)) }
                    }
                    SwitchRow("قص الورق بعد الطباعة", settings.receipt.cut) { v -> store.update { it.copy(receipt = it.receipt.copy(cut = v)) } }
                    TestButton("طباعة فاتورة تجريبية", testing == "receipt", enabled = settings.receipt.target.isSet) {
                        test("receipt") { Printers.testReceipt(context, settings) }
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Heading("طابعة الباركود (الملصقات)", "Xprinter / TSC / Zebra وغيرها عبر البلوتوث أو الشبكة")
                    TargetEditor(settings.label.target) { t -> store.update { it.copy(label = it.label.copy(target = t)) } }
                    Text("لغة الطابعة", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabelLanguage.entries.forEach { lang ->
                            FilterChip(settings.label.language == lang, { store.update { it.copy(label = it.label.copy(language = lang)) } }, label = { Text(lang.label) })
                        }
                    }
                    Text("مقاس الملصق (مم)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("العرض", settings.label.widthMm, 20..120, Modifier.weight(1f)) { v -> store.update { it.copy(label = it.label.copy(widthMm = v)) } }
                        NumberField("الارتفاع", settings.label.heightMm, 10..120, Modifier.weight(1f)) { v -> store.update { it.copy(label = it.label.copy(heightMm = v)) } }
                        NumberField("الفراغ", settings.label.gapMm, 0..10, Modifier.weight(1f)) { v -> store.update { it.copy(label = it.label.copy(gapMm = v)) } }
                    }
                    Text("دقة رأس الطباعة", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(203, 300).forEach { dpi ->
                            FilterChip(settings.label.dpi == dpi, { store.update { it.copy(label = it.label.copy(dpi = dpi)) } }, label = { Text("$dpi dpi") })
                        }
                    }
                    TestButton("طباعة ملصق تجريبي", testing == "label", enabled = settings.label.target.isSet) {
                        test("label") { Printers.testLabel(context, settings) }
                    }
                }
            }
            item {
                Text(
                    "طابعات البلوتوث يجب أن تُقرن أولاً من إعدادات البلوتوث في الهاتف. طابعات الشبكة تستخدم عادةً المنفذ 9100، واطبع صفحة الإعدادات من الطابعة لمعرفة عنوان IP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColumnScope.TestButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (enabled) text else "$text (اختر الطابعة أولاً)")
    }
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter(Char::isDigit).take(3)
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.toIntOrNull()?.let { it !in range } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Bluetooth (pick a paired printer) or network (IP and port). */
@Composable
private fun TargetEditor(target: PrinterTarget, onChange: (PrinterTarget) -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf<List<PairedPrinter>>(emptyList()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            paired = Printers.paired(context)
            picking = true
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrinterLink.entries.forEach { link ->
            FilterChip(target.link == link, { onChange(target.copy(link = link)) }, label = { Text(link.label) })
        }
    }
    when (target.link) {
        PrinterLink.BLUETOOTH -> OutlinedButton(
            onClick = {
                val needed = Printers.bluetoothPermission
                if (needed != null && !Printers.canUseBluetooth(context)) {
                    permission.launch(needed)
                } else {
                    paired = Printers.paired(context)
                    picking = true
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text(if (target.bluetoothAddress.isBlank()) "اختيار طابعة بلوتوث" else "الطابعة: ${target.title}") }
        PrinterLink.NETWORK -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            var host by remember(target.host) { mutableStateOf(target.host) }
            var port by remember(target.port) { mutableStateOf(target.port.toString()) }
            OutlinedTextField(
                value = host,
                onValueChange = { v -> host = v.trim(); onChange(target.copy(host = host)) },
                label = { Text("عنوان IP") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { v ->
                    port = v.filter(Char::isDigit).take(5)
                    port.toIntOrNull()?.takeIf { it in 1..65535 }?.let { onChange(target.copy(port = it)) }
                },
                label = { Text("المنفذ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("الطابعات المقترنة") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    if (paired.isEmpty()) {
                        Text("لا توجد أجهزة بلوتوث مقترنة. شغّل الطابعة واقرنها من إعدادات البلوتوث في الهاتف ثم عد إلى هنا.")
                    }
                    paired.forEach { p ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                picking = false
                                onChange(target.copy(bluetoothAddress = p.address, bluetoothName = p.name))
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(p.name, style = if (p.address == target.bluetoothAddress) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
                            Text(p.address, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("إغلاق") } },
        )
    }
}

/** Shown when nothing is set yet. */
fun PrinterSettings.receiptReady() = receipt.target.isSet
fun PrinterSettings.labelReady() = label.target.isSet
