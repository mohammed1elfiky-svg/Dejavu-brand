@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.excel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.finance.Period
import com.dejavu.admin.ui.finance.ReportRange
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.ManagementRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.ProductImportResult
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val MAX_IMPORT_BYTES = 10 * 1024 * 1024

/** One downloadable sheet. [ranged] ones take the from/to dates. */
private enum class ExportKind(val key: String, val title: String, val subtitle: String, val permission: String, val ranged: Boolean = false) {
    PRODUCTS("products", "المنتجات", "كل المنتجات بمقاساتها وأسعارها", Perm.PRODUCTS_READ),
    STOCK("stock", "المخزون", "الكميات المتوفرة في كل مستودع", Perm.STOCK_READ),
    CUSTOMERS("customers", "العملاء", "أسماء العملاء وأرقامهم وأرصدتهم", Perm.CUSTOMERS_READ),
    SALES("sales", "المبيعات", "فواتير البيع في الفترة المحددة", Perm.REPORTS_SALES, ranged = true),
    DEBTORS("debtors", "المدينون", "العملاء الذين عليهم مبالغ", Perm.CUSTOMERS_READ),
    PRODUCT_SALES("product-sales", "مبيعات وأرباح المنتجات", "الكمية والإيراد والربح لكل منتج في الفترة", Perm.REPORTS_SALES, ranged = true),
}

class ExcelViewModel(private val repo: ManagementRepository) : BaseViewModel() {
    /** The export or template being downloaded now, by key. */
    private val _downloading = MutableStateFlow<String?>(null)
    val downloading = _downloading.asStateFlow()
    private val _range = MutableStateFlow(Period.THIS_MONTH.range())
    val range = _range.asStateFlow()

    private var importBytes: ByteArray? = null
    private val _fileName = MutableStateFlow<String?>(null)
    val fileName = _fileName.asStateFlow()
    private val _check = MutableStateFlow<ProductImportResult?>(null)
    val check = _check.asStateFlow()
    private val _saved = MutableStateFlow<ProductImportResult?>(null)
    val saved = _saved.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    fun setRange(r: ReportRange) {
        _range.value = r
    }

    /** Downloads the sheet into cache/photos and hands back the file to share. */
    fun download(key: String, dir: File, fileName: String, ranged: Boolean, done: (File) -> Unit) {
        if (_downloading.value != null) return
        _downloading.value = key
        viewModelScope.launch {
            val r = _range.value
            val result = if (key == TEMPLATE) repo.importTemplate() else repo.export(key, if (ranged) r.fromIso else null, if (ranged) r.toIso else null)
            result.orSay()?.let { bytes ->
                val file = withContext(Dispatchers.IO) {
                    runCatching {
                        val folder = File(dir, "photos").apply { mkdirs() }
                        File(folder, fileName).also { it.writeBytes(bytes) }
                    }.getOrNull()
                }
                if (file == null) say("تعذّر حفظ الملف") else done(file)
            }
            _downloading.value = null
        }
    }

    /** Reads the picked sheet and checks it on the server (nothing is saved yet). */
    fun pick(context: Context, uri: Uri) {
        if (_importing.value) return
        _importing.value = true
        _check.value = null
        _saved.value = null
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            when {
                bytes == null || bytes.isEmpty() -> say("تعذّرت قراءة الملف")
                bytes.size > MAX_IMPORT_BYTES -> say("حجم الملف أكبر من 10 ميغابايت")
                else -> {
                    importBytes = bytes
                    _fileName.value = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف Excel"
                    repo.importProducts(bytes, dryRun = true).orSay()?.let { _check.value = it }
                }
            }
            _importing.value = false
        }
    }

    fun save() {
        val bytes = importBytes ?: return
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            repo.importProducts(bytes, dryRun = false).orSay()?.let { r ->
                if (r.errors.isNotEmpty()) {
                    _check.value = r
                } else {
                    _saved.value = r
                    _check.value = null
                    importBytes = null
                    say("تم حفظ المنتجات")
                }
            }
            _importing.value = false
        }
    }

    fun clearImport() {
        importBytes = null
        _fileName.value = null
        _check.value = null
        _saved.value = null
    }

    companion object {
        const val TEMPLATE = "template"
    }
}

/** Excel: export lists and reports, import products from a sheet. */
@Composable
fun ExcelScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel(key = "excel") { ExcelViewModel(it.management) }
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val fileName by vm.fileName.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    CollectMessages(vm, snackbar)

    val exports = ExportKind.entries.filter { session.can(it.permission) }
    val canImport = session.can(Perm.PRODUCTS_WRITE)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pick(context, uri)
    }

    fun download(kind: ExportKind) {
        val stamp = LocalDate.now().toString()
        vm.download(kind.key, context.cacheDir, "dejavu-${kind.key}-$stamp.xlsx", kind.ranged) { file -> shareSheet(context, file, kind.title) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("Excel", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (exports.isEmpty() && !canImport) {
            EmptyState(Icons.Filled.Lock, "لا توجد صلاحية", "لا يملك حسابك صلاحية تصدير أو استيراد الملفات", modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (exports.isNotEmpty()) {
                item { Text("تصدير إلى Excel", style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep) }
                if (exports.any { it.ranged }) {
                    item { RangePicker(range, vm::setRange) }
                }
                items(exports, key = { it.key }) { k ->
                    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp, onClick = { download(k) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(k.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (k.ranged) "${k.subtitle} · ${range.from.shown()} - ${range.to.shown()}" else k.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Lux.InkSoft,
                                )
                            }
                            Icon(Icons.Filled.Share, contentDescription = "تنزيل ومشاركة", tint = Lux.GoldDeep, modifier = Modifier.size(22.dp))
                        }
                        if (downloading == k.key) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
            if (canImport) {
                item { Text("استيراد منتجات من Excel", style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep, modifier = Modifier.padding(top = 8.dp)) }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            "نزّل النموذج واملأه بالمنتجات (صف لكل مقاس/لون)، ثم اختر الملف. يتم فحص الملف أولاً، ولا يُحفظ شيء إلا بعد التأكيد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    vm.download(ExcelViewModel.TEMPLATE, context.cacheDir, "dejavu-products-template.xlsx", false) { file ->
                                        shareSheet(context, file, "نموذج المنتجات")
                                    }
                                },
                                enabled = downloading == null,
                                modifier = Modifier.weight(1f),
                            ) { Text("تحميل نموذج Excel", maxLines = 1) }
                            Button(
                                onClick = { picker.launch(arrayOf(XLSX, "application/vnd.ms-excel", "application/octet-stream")) },
                                enabled = !importing,
                                modifier = Modifier.weight(1f),
                            ) { Text("اختيار ملف", maxLines = 1) }
                        }
                        if (downloading == ExcelViewModel.TEMPLATE || importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
                check?.let { r ->
                    item { CheckCard(fileName, r, importing, onSave = vm::save, onCancel = vm::clearImport) }
                    if (r.errors.isNotEmpty()) {
                        items(r.errors.take(200)) { e ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("صف ${e.row}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                Text(e.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                        }
                        if (r.errors.size > 200) item { Text("و${r.errors.size - 200} خطأ آخر", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
                    }
                }
                saved?.let { r ->
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("تم حفظ المنتجات ✓", style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                            Text("منتجات جديدة: ${r.created}", style = MaterialTheme.typography.bodyMedium)
                            Text("مقاسات أُضيفت: ${r.variantsAdded}", style = MaterialTheme.typography.bodyMedium)
                            Text("عدد الصفوف: ${r.rows}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            TextButton(onClick = vm::clearImport) { Text("إغلاق") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckCard(fileName: String?, r: ProductImportResult, busy: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("نتيجة فحص الملف", style = MaterialTheme.typography.titleSmall)
        fileName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
        Text("عدد الصفوف: ${r.rows}", style = MaterialTheme.typography.bodyMedium)
        Text("منتجات جديدة: ${r.newProducts}", style = MaterialTheme.typography.bodyMedium)
        Text("منتجات موجودة (تُضاف لها المقاسات): ${r.existingProducts}", style = MaterialTheme.typography.bodyMedium)
        if (r.errors.isEmpty()) {
            Text("لا توجد أخطاء", style = MaterialTheme.typography.bodyMedium, color = Lux.GoldDeep)
        } else {
            Text("أخطاء: ${r.errors.size} — صحّح الملف ثم اختره مرة أخرى", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onSave, enabled = r.errors.isEmpty() && r.rows > 0 && !busy, modifier = Modifier.weight(1f)) { Text("حفظ المنتجات") }
            TextButton(onClick = onCancel) { Text("إلغاء") }
        }
    }
}

private val excelDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ar"))

private fun LocalDate.shown(): String = format(excelDateFormat)

/** Quick ranges and custom from/to dates for the sales sheets (same as the reports screen). */
@Composable
private fun RangePicker(range: ReportRange, onChange: (ReportRange) -> Unit) {
    var pickingFrom by remember { mutableStateOf<Boolean?>(null) }
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp) {
        Text("فترة المبيعات", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p ->
                val r = p.range()
                FilterChip(selected = r == range, onClick = { onChange(r) }, label = { Text(p.label) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingFrom = true }, modifier = Modifier.weight(1f)) { Text("من ${range.from.shown()}", maxLines = 1) }
            OutlinedButton(onClick = { pickingFrom = false }, modifier = Modifier.weight(1f)) { Text("إلى ${range.to.shown()}", maxLines = 1) }
        }
    }
    pickingFrom?.let { from ->
        DayPicker(if (from) range.from else range.to, onDismiss = { pickingFrom = null }) { d ->
            pickingFrom = null
            onChange(
                if (from) ReportRange(d, if (d.isAfter(range.to)) d else range.to)
                else ReportRange(if (d.isBefore(range.from)) d else range.from, d),
            )
        }
    }
}

@Composable
private fun DayPicker(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("تم") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    ) { DatePicker(state = state) }
}

/** Opens the share sheet for the .xlsx through the app's photos FileProvider. */
private fun shareSheet(context: Context, file: File, subject: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(XLSX)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "مشاركة الملف").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
