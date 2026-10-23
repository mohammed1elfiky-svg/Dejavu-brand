@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.system

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dejavu.admin.AdminApp
import com.dejavu.admin.AdminContainer
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AuditEntry
import com.dejavu.core.admin.AuditType
import com.dejavu.core.admin.DailySummarySettings
import com.dejavu.core.admin.DashboardRepository
import com.dejavu.core.admin.SystemRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ================================================================= audit ===

class AuditViewModel(private val repo: SystemRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<AuditEntry>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private val _types = MutableStateFlow<List<AuditType>>(emptyList())
    val types = _types.asStateFlow()
    private val _type = MutableStateFlow<String?>(null)
    val type = _type.asStateFlow()
    private val _from = MutableStateFlow<LocalDate?>(null)
    val from = _from.asStateFlow()
    private val _to = MutableStateFlow<LocalDate?>(null)
    val to = _to.asStateFlow()
    private var page = 1

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { repo.auditTypes().getOrNull()?.let { _types.value = it } }
        viewModelScope.launch { fetch(reset = true) }
    }

    fun more() {
        viewModelScope.launch { fetch(reset = false) }
    }

    fun pickType(t: String?) {
        if (t == _type.value) return
        _type.value = t
        reload()
    }

    fun pickDates(from: LocalDate?, to: LocalDate?) {
        _from.value = from
        _to.value = to
        reload()
    }

    private fun reload() {
        _state.value = Load.Loading
        viewModelScope.launch { fetch(reset = true) }
    }

    private suspend fun fetch(reset: Boolean) {
        val next = if (reset) 1 else page + 1
        repo.audit(page = next, entityType = _type.value, from = _from.value?.toString(), to = _to.value?.toString()).fold(
            { p ->
                page = next
                val before = if (reset) emptyList() else (_state.value as? Load.Ready)?.value.orEmpty()
                _state.value = Load.Ready(before + p.items)
                _hasMore.value = p.hasMore
            },
            { e -> if (reset) _state.value = Load.Failed(e.userMessage()) else say(e.userMessage()) },
        )
    }
}

private fun auditActionLabel(action: String): String = when (action) {
    "CREATE" -> "إضافة"
    "UPDATE" -> "تعديل"
    "DELETE" -> "حذف"
    "CANCEL" -> "إلغاء/حذف"
    "LOGIN" -> "دخول"
    "POST" -> "ترحيل"
    "REVERSE" -> "عكس"
    "APPROVE" -> "اعتماد"
    else -> action
}

private fun auditTypeLabel(type: String): String = when (type) {
    "PosSale" -> "فاتورة كاشير"
    "SupplierVoucher" -> "سند مورد"
    "CashTransfer" -> "تحويل/مصارفة"
    "Product" -> "منتج"
    "ProductVariant" -> "متغير منتج"
    "Category" -> "قسم"
    "Brand" -> "ماركة"
    "User" -> "موظف"
    "Role" -> "دور وصلاحيات"
    "Customer" -> "عميل"
    "CustomerExchange" -> "مصارفة عميل"
    "CustomerReceipt" -> "سند قبض"
    "CashierShift" -> "وردية"
    "CashBox" -> "صندوق"
    "Coupon" -> "كود خصم"
    "Promotion" -> "عرض"
    "Backup" -> "نسخة احتياطية"
    "Order" -> "طلب"
    "Return" -> "مرتجع"
    "Expense" -> "مصروف"
    "PurchaseOrder" -> "أمر شراء"
    "Supplier" -> "مورد"
    "JournalEntry" -> "قيد محاسبي"
    "Currency" -> "عملة"
    "ExchangeRate" -> "سعر صرف"
    "StockAdjustment" -> "تسوية مخزون"
    "StockCount" -> "جرد مخزون"
    "WalletTopUp" -> "شحن رصيد"
    "Setting" -> "إعدادات"
    else -> type
}

private val prettyJson = Json { prettyPrint = true }

private fun pretty(e: JsonElement?): String = e?.let { runCatching { prettyJson.encodeToString(JsonElement.serializer(), it) }.getOrNull() } ?: "—"

/** Who did what and when: edits, deletions, logins…, newest first. */
@Composable
fun AuditScreen(onBack: () -> Unit) {
    val vm = adminViewModel { AuditViewModel(it.system) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val from by vm.from.collectAsStateWithLifecycle()
    val to by vm.to.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<String?>(null) } // "from" / "to"
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("سجل العمليات", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = type == null, onClick = { vm.pickType(null) }, label = { Text("الكل") })
                types.forEach { t ->
                    FilterChip(
                        selected = type == t.entityType,
                        onClick = { vm.pickType(t.entityType) },
                        label = { Text("${auditTypeLabel(t.entityType)} (${t.count})") },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = "from" }, modifier = Modifier.weight(1f)) { Text("من: ${from?.toString() ?: "—"}") }
                OutlinedButton(onClick = { picking = "to" }, modifier = Modifier.weight(1f)) { Text("إلى: ${to?.toString() ?: "—"}") }
                if (from != null || to != null) TextButton(onClick = { vm.pickDates(null, null) }) { Text("مسح") }
            }
            LoadContent(state, onRetry = vm::load) { entries ->
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entries.isEmpty()) item { Text("لا توجد عمليات مطابقة", color = Lux.InkSoft) }
                    items(entries, key = { it.id }) { e -> AuditRow(e) }
                    if (hasMore) {
                        item { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text("عرض المزيد") } }
                    }
                }
            }
        }
    }

    picking?.let { which ->
        AuditDayPicker(
            initial = (if (which == "from") from else to) ?: LocalDate.now(),
            onDismiss = { picking = null },
        ) { day ->
            picking = null
            if (which == "from") vm.pickDates(day, to) else vm.pickDates(from, day)
        }
    }
}

@Composable
private fun AuditRow(e: AuditEntry) {
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp, onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${auditActionLabel(e.action)} · ${auditTypeLabel(e.entityType)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (open) "إخفاء" else "التفاصيل", style = MaterialTheme.typography.labelMedium, color = Lux.GoldDeep)
        }
        Text(
            listOfNotNull(e.user?.name ?: "النظام", formatDateTime(e.createdAt)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
        )
        if (open) {
            e.entityId?.let { Text("المعرّف: $it", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 4.dp)) }
            if (e.before != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("قبل", style = MaterialTheme.typography.labelLarge)
                JsonText(pretty(e.before))
            }
            if (e.after != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("بعد", style = MaterialTheme.typography.labelLarge)
                JsonText(pretty(e.after))
            }
            if (e.before == null && e.after == null) Text("لا توجد تفاصيل محفوظة", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
    }
}

@Composable
private fun JsonText(text: String) {
    // JSON reads left to right even in the Arabic UI.
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text("‎" + text.replace("\n", "\n‎"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AuditDayPicker(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The date picker works in UTC midnights.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
                enabled = state.selectedDateMillis != null,
            ) { Text("تم") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    ) { DatePicker(state = state) }
}

// ================================================================ backup ===

/** Daily automatic backup into this phone's app storage (the last [KEEP] files are kept). */
object AutoBackup {
    private const val PREFS = "auto_backup"
    private const val ENABLED = "enabled"
    private const val LAST_AT = "last_at"
    private const val WORK = "daily-backup"
    const val KEEP = 7

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    /** When the last automatic backup was saved (epoch millis, 0 = never). */
    fun lastAt(context: Context): Long = prefs(context).getLong(LAST_AT, 0L)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(ENABLED, on) }
        if (on) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** At app start: keeps the daily work scheduled while the switch is on. */
    fun restoreSchedule(context: Context) {
        if (isEnabled(context)) schedule(context)
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequest.Builder(DailyBackupWorker::class.java, 1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun dir(context: Context): File = context.getExternalFilesDir("backups") ?: File(context.filesDir, "backups")

    /** Saved backups, newest first. */
    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json.gz") }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Downloads a backup into [dir] now; false when signed out or the download failed. */
    suspend fun run(context: Context, container: AdminContainer): Boolean {
        if (!container.hasSession) return false
        val body = container.system.backup().getOrNull() ?: return false
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val dir = dir(context).apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
                val tmp = File(dir, "dejavu-backup-$stamp.json.gz.part")
                body.use { b -> tmp.outputStream().use { out -> b.byteStream().copyTo(out) } }
                val target = File(dir, "dejavu-backup-$stamp.json.gz")
                if (target.exists()) target.delete()
                check(tmp.renameTo(target)) { "rename failed" }
                files(context).drop(KEEP).forEach { it.delete() }
            }.isSuccess
        }
        if (ok) prefs(context).edit { putLong(LAST_AT, System.currentTimeMillis()) }
        return ok
    }
}

/** The daily automatic backup, run by WorkManager. */
class DailyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AdminApp).container
        if (!container.hasSession) return Result.success()
        return if (AutoBackup.run(applicationContext, container)) Result.success() else Result.retry()
    }
}

class BackupViewModel(private val container: AdminContainer) : BaseViewModel() {
    private val repo: SystemRepository = container.system
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _restored = MutableStateFlow(false)

    /** All data was replaced: the user must sign in again. */
    val restored = _restored.asStateFlow()
    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files = _files.asStateFlow()
    private val _lastAt = MutableStateFlow(0L)
    val lastAt = _lastAt.asStateFlow()

    fun refreshFiles(context: Context) {
        viewModelScope.launch {
            _files.value = withContext(Dispatchers.IO) { AutoBackup.files(context) }
            _lastAt.value = AutoBackup.lastAt(context)
        }
    }

    /** Streams a fresh backup into the file the user created. */
    fun download(resolver: ContentResolver, uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.backup().orSay()?.let { body ->
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        body.use { b ->
                            val out = resolver.openOutputStream(uri) ?: error("no stream")
                            out.use { b.byteStream().copyTo(it) }
                        }
                    }.isSuccess
                }
                say(if (ok) "تم حفظ النسخة الاحتياطية" else "تعذّر حفظ الملف")
            }
            _busy.value = false
        }
    }

    /** Saves an automatic-style backup into app storage now. */
    fun backupNow(context: Context) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val ok = AutoBackup.run(context, container)
            say(if (ok) "تم حفظ نسخة في ذاكرة التطبيق" else "تعذّر تنزيل النسخة الاحتياطية")
            refreshFiles(context)
            _busy.value = false
        }
    }

    fun restore(resolver: ContentResolver, uri: Uri, phrase: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (bytes == null || bytes.isEmpty()) {
                say("تعذّر قراءة الملف")
            } else {
                repo.restore(bytes, phrase).orSay()?.let { _restored.value = true }
            }
            _busy.value = false
        }
    }
}

/** Download, automatic daily backup and restore of the whole database (admins). */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val container = adminContainer()
    val vm = adminViewModel { BackupViewModel(it) }
    val context = LocalContext.current
    val appContext = context.applicationContext
    val busy by vm.busy.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val lastAt by vm.lastAt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var autoOn by remember { mutableStateOf(AutoBackup.isEnabled(appContext)) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.refreshFiles(appContext)
        onPauseOrDispose {}
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri != null) vm.download(appContext.contentResolver, uri)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("النسخ الاحتياطي", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (restored) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("تمت استعادة النسخة الاحتياطية ✓", style = MaterialTheme.typography.titleMedium)
                        Text("استُبدلت كل البيانات. سجّل الدخول من جديد للمتابعة.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        Button(onClick = { container.appScope.launch { container.logout() } }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Text("تسجيل الدخول من جديد")
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("لماذا النسخ الاحتياطي؟", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "قاعدة البيانات المجانية على Render تُحذف بعد مدة، فتضيع كل البيانات. نزّل نسخة احتياطية بانتظام واحتفظ بها خارج الهاتف (Drive أو بريد).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(
                        onClick = { create.launch("dejavu-backup-${LocalDate.now()}.json.gz") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("تنزيل نسخة احتياطية الآن")
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("نسخ احتياطي يومي تلقائي", style = MaterialTheme.typography.titleSmall)
                            Text("مرة كل يوم عند الاتصال بالإنترنت، تُحفظ آخر ${AutoBackup.KEEP} نسخ على هذا الهاتف", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                        Switch(
                            checked = autoOn,
                            onCheckedChange = { on ->
                                AutoBackup.setEnabled(appContext, on)
                                autoOn = on
                            },
                        )
                    }
                    Text(
                        if (lastAt > 0) "آخر نسخة تلقائية: ${formatDateTime(Instant.ofEpochMilli(lastAt).toString())}" else "لم تُحفظ نسخة تلقائية بعد",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    OutlinedButton(onClick = { vm.backupNow(appContext) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("حفظ نسخة على الهاتف الآن")
                    }
                    if (files.isNotEmpty()) {
                        Text("المجلد: ${AutoBackup.dir(appContext).absolutePath}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 8.dp))
                        files.forEach { f ->
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text("${(f.length() + 1023) / 1024} KB", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                            }
                        }
                        Text(
                            "لمشاركة نسخة افتح المجلد أعلاه من تطبيق الملفات على الهاتف (Android/data).",
                            style = MaterialTheme.typography.labelSmall,
                            color = Lux.InkSoft,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("استعادة من نسخة", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "تحذير: ستُحذف كل البيانات الحالية (المنتجات، الفواتير، العملاء، الحسابات…) وتُستبدل ببيانات النسخة. لا يمكن التراجع.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedButton(
                        onClick = { pick.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(restoreUri?.let { "الملف: ${it.lastPathSegment ?: it}" } ?: "اختيار ملف النسخة") }
                    if (restoreUri != null) {
                        Text("للتأكيد اكتب: ${SystemRepository.RESTORE_PHRASE}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("عبارة التأكيد") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Button(
                            onClick = { confirm = true },
                            enabled = !busy && typed.trim() == SystemRepository.RESTORE_PHRASE,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("استعادة واستبدال كل البيانات")
                        }
                    }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog("تأكيد نهائي", "ستُستبدل كل البيانات الحالية ببيانات النسخة المختارة. متأكد؟", "استعادة", onDismiss = { confirm = false }) {
            confirm = false
            restoreUri?.let { vm.restore(appContext.contentResolver, it, typed.trim()) }
        }
    }
}

// ========================================================= daily summary ===

class DailySummaryViewModel(private val repo: DashboardRepository) : BaseViewModel() {
    private val _settings = MutableStateFlow<Load<DailySummarySettings>>(Load.Loading)
    val settings = _settings.asStateFlow()
    private val _preview = MutableStateFlow<Load<String>>(Load.Loading)
    val preview = _preview.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_settings.value !is Load.Ready) _settings.value = Load.Loading
        viewModelScope.launch { _settings.value = repo.dailySummarySettings().toLoad() }
        loadPreview()
    }

    fun loadPreview() {
        _preview.value = Load.Loading
        viewModelScope.launch { _preview.value = repo.dailySummary().map { it.text }.toLoad() }
    }

    fun save(s: DailySummarySettings) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.saveDailySummarySettings(s).orSay()?.let {
                _settings.value = Load.Ready(it)
                say("تم الحفظ")
            }
            _busy.value = false
        }
    }

    fun sendNow() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.sendDailySummary().orSay()?.let {
                _preview.value = Load.Ready(it.text)
                say("أُرسل الملخص إلى بوابة الرسائل")
            }
            _busy.value = false
        }
    }
}

/** The evening text message with the day's figures, sent to the owner's phone. */
@Composable
fun DailySummaryScreen(onBack: () -> Unit) {
    val vm = adminViewModel { DailySummaryViewModel(it.dashboard) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("الملخص اليومي", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(settings, onRetry = vm::load, modifier = Modifier.padding(padding)) { s ->
            var enabled by remember(s) { mutableStateOf(s.enabled) }
            var phone by remember(s) { mutableStateOf(s.phone.orEmpty()) }
            var hour by remember(s) { mutableIntStateOf(s.hour.coerceIn(0, 23)) }
            var hourMenu by remember { mutableStateOf(false) }
            val phoneOk = !enabled || phone.trim().length >= 7
            LazyColumn(Modifier.padding(padding).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("إرسال الملخص كل مساء", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "رسالة نصية بمبيعات اليوم والمصروفات والنقد. تُرسل من هاتف بوابة الرسائل، فيجب أن يكون مشغّلاً ومتصلاً.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Lux.InkSoft,
                                )
                            }
                            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                        }
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' }.take(20) },
                            label = { Text("رقم الهاتف المستلم") },
                            singleLine = true,
                            isError = !phoneOk,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Text("ساعة الإرسال", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 8.dp))
                        Box {
                            OutlinedButton(onClick = { hourMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(hourLabel(hour), modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(expanded = hourMenu, onDismissRequest = { hourMenu = false }) {
                                (0..23).forEach { h ->
                                    DropdownMenuItem(
                                        text = { Text(hourLabel(h)) },
                                        onClick = {
                                            hour = h
                                            hourMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { vm.save(DailySummarySettings(enabled = enabled, phone = phone.trim().ifBlank { null }, hour = hour)) },
                            enabled = !busy && phoneOk,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("حفظ") }
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("معاينة رسالة اليوم", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::loadPreview) { Text("تحديث") }
                        }
                        when (val p = preview) {
                            Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            is Load.Failed -> Text(p.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            is Load.Ready -> Text(p.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                        OutlinedButton(
                            onClick = vm::sendNow,
                            enabled = !busy && !s.phone.isNullOrBlank(),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        ) { Text("إرسال الآن") }
                        if (s.phone.isNullOrBlank()) {
                            Text("احفظ رقم الهاتف أولاً", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                        }
                    }
                }
            }
        }
    }
}

private fun hourLabel(h: Int): String {
    val suffix = when {
        h == 0 -> "منتصف الليل"
        h < 12 -> "صباحاً"
        h == 12 -> "ظهراً"
        h < 18 -> "عصراً"
        else -> "مساءً"
    }
    val twelve = if (h % 12 == 0) 12 else h % 12
    return "%02d:00 ($twelve $suffix)".format(Locale.US, h)
}
