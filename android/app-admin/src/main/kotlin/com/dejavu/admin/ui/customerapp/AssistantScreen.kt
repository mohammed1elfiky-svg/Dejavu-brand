@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.customerapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.ChatConversation
import com.dejavu.core.admin.ChatLine
import com.dejavu.core.admin.ChatSettings
import com.dejavu.core.admin.ChatSettingsRequest
import com.dejavu.core.admin.ChatTestResult
import com.dejavu.core.admin.CustomerAppRepository
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val DEFAULT_MODEL = "gemini-flash-latest"
private val MODEL_NAME = Regex("^[a-z0-9.-]{3,60}$")

class AssistantViewModel(private val repo: CustomerAppRepository) : BaseViewModel() {
    private val _settings = MutableStateFlow<Load<ChatSettings>>(Load.Loading)
    val settings = _settings.asStateFlow()
    private val _convos = MutableStateFlow<Load<List<ChatConversation>>>(Load.Loading)
    val convos = _convos.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    /** The conversation shown in the dialog. */
    private val _open = MutableStateFlow<ChatConversation?>(null)
    val open = _open.asStateFlow()
    private val _lines = MutableStateFlow<Load<List<ChatLine>>>(Load.Loading)
    val lines = _lines.asStateFlow()

    val refresher = Refresher()
    private var started = false

    private val _testing = MutableStateFlow(false)
    val testing = _testing.asStateFlow()

    /** What the last "اختبار الاتصال" found (null until it is run). */
    private val _test = MutableStateFlow<ChatTestResult?>(null)
    val test = _test.asStateFlow()

    fun testConnection() {
        if (_testing.value) return
        _testing.value = true
        _test.value = null
        viewModelScope.launch {
            repo.testChat().orSay()?.let { _test.value = it }
            _testing.value = false
        }
    }

    fun start() {
        if (started) return
        started = true
        loadSettings()
        loadConvos()
    }

    fun loadSettings() {
        _settings.value = Load.Loading
        viewModelScope.launch { _settings.value = repo.chatSettings().toLoad() }
    }

    fun loadConvos() {
        if (_convos.value !is Load.Ready) _convos.value = Load.Loading
        viewModelScope.launch { _convos.value = repo.conversations().toLoad() }
    }

    fun refresh() = refresher.run(viewModelScope) {
        if (_settings.value !is Load.Ready) _settings.value = repo.chatSettings().toLoad()
        repo.conversations().orSay()?.let { _convos.value = Load.Ready(it) }
    }

    fun save(request: ChatSettingsRequest) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            repo.setChatSettings(request).orSay()?.let {
                _settings.value = Load.Ready(it)
                say(if (it.enabled) "تم حفظ إعدادات المساعد وهو مفعّل" else "تم حفظ إعدادات المساعد (موقوف)")
            }
            _saving.value = false
        }
    }

    fun openConversation(c: ChatConversation) {
        _open.value = c
        loadLines()
    }

    fun loadLines() {
        val c = _open.value ?: return
        _lines.value = Load.Loading
        viewModelScope.launch {
            val res = repo.conversation(c.customer.id)
            if (_open.value?.customer?.id == c.customer.id) _lines.value = res.toLoad()
        }
    }

    fun closeConversation() {
        _open.value = null
    }
}

@Composable
internal fun AssistantContent(onBack: () -> Unit, openCustomer: (String) -> Unit) {
    val vm = adminViewModel(key = "assistant") { AssistantViewModel(it.customerApp) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val convos by vm.convos.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val testResult by vm.test.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val open by vm.open.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.start() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("مساعد التسوق", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        RefreshList(refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val s = settings) {
                    Load.Loading -> item(key = "settings-loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    is Load.Failed -> item(key = "settings-failed") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("تعذّر تحميل إعدادات المساعد", style = MaterialTheme.typography.titleSmall)
                            Text(s.message, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            OutlinedButton(onClick = vm::loadSettings, modifier = Modifier.padding(top = 8.dp)) { Text("إعادة المحاولة") }
                        }
                    }
                    is Load.Ready -> {
                        if (!s.value.configured) {
                            item(key = "not-configured") { NotConfiguredCard() }
                        }
                        item(key = "settings") { AssistantSettingsCard(s.value, saving, vm::save) }
                        item(key = "test") { ConnectionTestCard(testing, testResult, vm::testConnection) }
                    }
                }
                item(key = "convos-head") {
                    Text("محادثات العملاء", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                when (val c = convos) {
                    Load.Loading -> item(key = "convos-loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    is Load.Failed -> item(key = "convos-failed") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text(c.message, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = vm::loadConvos, modifier = Modifier.padding(top = 8.dp)) { Text("إعادة المحاولة") }
                        }
                    }
                    is Load.Ready -> {
                        if (c.value.isEmpty()) {
                            item(key = "convos-empty") {
                                Text(
                                    "لم يتحدث أي عميل مع المساعد بعد.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Lux.InkSoft,
                                )
                            }
                        } else {
                            items(c.value, key = { "c:" + it.customer.id }) { conv ->
                                ConversationCard(conv) { vm.openConversation(conv) }
                            }
                        }
                    }
                }
            }
        }
    }

    open?.let { c ->
        ConversationDialog(
            c,
            lines,
            onRetry = vm::loadLines,
            onDismiss = vm::closeConversation,
            onOpenCustomer = {
                vm.closeConversation()
                openCustomer(c.customer.id)
            },
        )
    }
}

@Composable
private fun NotConfiguredCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Warning)
            Text(
                "مفتاح Gemini غير مضاف في الخادم — أضف GEMINI_API_KEY في إعدادات الخادم",
                style = MaterialTheme.typography.bodyMedium,
                color = Warning,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Tries Gemini with a tiny question and says in Arabic what is wrong when it fails. */
@Composable
private fun ConnectionTestCard(testing: Boolean, result: ChatTestResult?, onTest: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("اختبار الاتصال بـ Gemini", style = MaterialTheme.typography.titleMedium)
        Text("يتأكد أن المفتاح والنموذج يعملان، وإن فشل يوضح السبب.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        OutlinedButton(onClick = onTest, enabled = !testing, modifier = Modifier.padding(top = 8.dp)) {
            if (testing) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp).height(16.dp).width(16.dp))
            Text(if (testing) "جارٍ الاختبار…" else "اختبار الاتصال")
        }
        if (result != null) {
            if (result.ok) {
                Text("✅ يعمل — النموذج ${result.model}", color = com.dejavu.core.ui.theme.Success, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            } else {
                Text("❌ لا يعمل", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(result.error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                if (result.available.isNotEmpty()) {
                    Text("النماذج المتاحة لمفتاحك: ${result.available.joinToString("، ")}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun AssistantSettingsCard(s: ChatSettings, saving: Boolean, onSave: (ChatSettingsRequest) -> Unit) {
    var enabled by rememberSaveable(s) { mutableStateOf(s.enabled) }
    var instructions by rememberSaveable(s) { mutableStateOf(s.instructions) }
    var welcome by rememberSaveable(s) { mutableStateOf(s.welcome) }
    var whatsapp by rememberSaveable(s) { mutableStateOf(s.whatsapp) }
    var dailyLimit by rememberSaveable(s) { mutableStateOf(s.dailyLimit.toString()) }
    var imageLimit by rememberSaveable(s) { mutableStateOf(s.imageLimit.toString()) }
    var model by rememberSaveable(s) { mutableStateOf(s.model.ifBlank { DEFAULT_MODEL }) }
    val daily = dailyLimit.toIntOrNull()
    val images = imageLimit.toIntOrNull()
    val modelOk = MODEL_NAME.matches(model.trim())
    val valid = daily != null && daily in 1..500 && images != null && images in 0..100 && modelOk

    GlassCard(Modifier.fillMaxWidth()) {
        Text("إعدادات المساعد", style = MaterialTheme.typography.titleMedium)
        Text(
            "مساعد ذكي داخل تطبيق العملاء يجيب عن أسئلتهم ويقترح المنتجات، ويحوّلهم إلى واتساب عند الحاجة.",
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        SwitchRow("تفعيل المساعد للعملاء", checked = enabled, enabled = !saving, onChange = { enabled = it })
        FormField(
            instructions,
            { instructions = it.take(8000) },
            "تعليمات المساعد",
            singleLine = false,
            minLines = 8,
            note = "اكتب أسلوب الرد، ما يقوله عن المتجر، وما يجب ألا يقوله (${instructions.length}/8000)",
            enabled = !saving,
        )
        FormField(welcome, { welcome = it.take(1000) }, "رسالة الترحيب", singleLine = false, minLines = 2, enabled = !saving)
        FormField(whatsapp, { whatsapp = it.trim().take(30) }, "رقم واتساب للتحويل", phone = true, note = "يحوّل المساعد العميل إليه عندما لا يستطيع المساعدة", enabled = !saving)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField(
                dailyLimit,
                { dailyLimit = it.filter(Char::isDigit).take(3) },
                "رسائل يومياً لكل عميل",
                modifier = Modifier.weight(1f),
                number = true,
                isError = daily == null || daily !in 1..500,
                enabled = !saving,
            )
            FormField(
                imageLimit,
                { imageLimit = it.filter(Char::isDigit).take(3) },
                "صور يومياً لكل عميل",
                modifier = Modifier.weight(1f),
                number = true,
                isError = images == null || images !in 0..100,
                enabled = !saving,
            )
        }
        FormField(
            model,
            { model = it.trim().lowercase().take(60) },
            "نموذج Gemini",
            hint = DEFAULT_MODEL,
            note = if (modelOk) "الافتراضي $DEFAULT_MODEL" else "اسم النموذج غير صحيح",
            isError = !modelOk,
            enabled = !saving,
        )
        SaveButton(busy = saving, enabled = valid) {
            onSave(
                ChatSettingsRequest(
                    enabled = enabled,
                    instructions = instructions.trim(),
                    welcome = welcome.trim(),
                    whatsapp = whatsapp.trim(),
                    dailyLimit = (daily ?: s.dailyLimit).coerceIn(1, 500),
                    imageLimit = (images ?: s.imageLimit).coerceIn(0, 100),
                    model = model.trim().ifBlank { DEFAULT_MODEL },
                ),
            )
        }
    }
}

@Composable
private fun ConversationCard(c: ChatConversation, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 12.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(c.customer.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.customer.phone.isNotBlank()) {
                    Text(c.customer.phone, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
            }
            Text(formatDateTime(c.lastAt), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
        }
        if (c.lastText.isNotBlank()) {
            Text(
                c.lastText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("${c.messages} رسالة", Lux.GoldDeep)
            if (c.images > 0) StatusChip("${c.images} صورة", Lux.GoldDeep)
            if (c.handovers > 0) StatusChip("${c.handovers} تحويل", Warning)
            if (c.reports > 0) StatusChip("${c.reports} إبلاغ", MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ConversationDialog(
    c: ChatConversation,
    lines: Load<List<ChatLine>>,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenCustomer: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "إغلاق") }
                    Column(Modifier.weight(1f)) {
                        Text(c.customer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c.customer.phone.isNotBlank()) {
                            Text(c.customer.phone, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LoadContent(lines, onRetry = onRetry) { list ->
                        if (list.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("لا توجد رسائل", color = Lux.InkSoft)
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(list, key = { it.id }) { line -> Bubble(line) }
                            }
                        }
                    }
                }
                Button(onClick = onOpenCustomer, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("فتح صفحة العميل") }
            }
        }
    }
}

@Composable
private fun Bubble(line: ChatLine) {
    val user = line.role == "USER"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.Start else Alignment.End) {
        Surface(
            color = if (user) Lux.GoldLight else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (line.imageFileId != null) StatusChip("📷 صورة", Lux.GoldDeep)
                if (line.text.isNotBlank()) Text(line.text, style = MaterialTheme.typography.bodyMedium)
                if (line.productIds.isNotEmpty()) {
                    Text("اقترح ${line.productIds.size} منتج", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                }
                if (line.handover) StatusChip("حوّل العميل إلى واتساب", Warning)
                if (line.reportedAt != null) StatusChip("أبلغ العميل عن هذا الرد" + (line.reportReason?.let { ": $it" } ?: ""), MaterialTheme.colorScheme.error)
            }
        }
        Text(
            (if (user) "العميل" else "المساعد") + " · " + formatDateTime(line.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
        )
    }
}

