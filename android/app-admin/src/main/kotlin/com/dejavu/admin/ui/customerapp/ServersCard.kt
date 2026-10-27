package com.dejavu.admin.ui.customerapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AppServers
import com.dejavu.core.admin.CustomerAppRepository
import com.dejavu.core.network.normalizeBaseUrl
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Result of trying an address from this phone. */
enum class Reach { CHECKING, OK, FAILED }

class AppServersViewModel(private val repo: CustomerAppRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<AppServers>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _reach = MutableStateFlow<Map<String, Reach>>(emptyMap())
    val reach = _reach.asStateFlow()
    private var started = false

    private val probe = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun load() {
        viewModelScope.launch { _state.value = repo.appServers().toLoad() }
    }

    fun save(servers: List<String>) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            repo.setAppServers(servers).orSay()?.let { saved ->
                _state.value = Load.Ready(saved)
                say(
                    when {
                        !saved.directory.configured -> "تم الحفظ. نسخة GitHub غير مفعّلة بعد"
                        saved.directory.error != null -> "تم الحفظ، لكن تعذّر تحديث نسخة GitHub"
                        else -> "تم الحفظ وتحديث نسخة GitHub"
                    },
                )
            }
            _saving.value = false
        }
    }

    fun sync() {
        viewModelScope.launch {
            repo.syncAppServers().orSay()?.let { saved ->
                _state.value = Load.Ready(saved)
                say(if (saved.directory.error == null) "تم تحديث نسخة GitHub" else "تعذّر تحديث نسخة GitHub")
            }
        }
    }

    /** Tries [raw]/health from this phone. */
    fun test(raw: String) {
        val url = raw.trim().takeIf { it.isNotEmpty() } ?: return
        _reach.value = _reach.value + (url to Reach.CHECKING)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    probe.newCall(Request.Builder().url(normalizeBaseUrl(url) + "health").build()).execute().use { it.isSuccessful }
                }.getOrDefault(false)
            }
            _reach.value = _reach.value + (url to if (ok) Reach.OK else Reach.FAILED)
        }
    }
}

/**
 * The server addresses the customer app tries in order. When none answers, the app reads
 * the list from its copy on GitHub, so a new address set here reaches shoppers even after
 * the old one is blocked.
 */
@Composable
internal fun ServersCard() {
    val vm = adminViewModel(key = "app-servers") { AppServersViewModel(it.customerApp) }
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val reach by vm.reach.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.start() }
    val loaded = (state as? Load.Ready)?.value

    GlassCard(Modifier.fillMaxWidth()) {
        Text("عناوين الخادم لتطبيق العملاء", style = MaterialTheme.typography.titleMedium)
        Text(
            "يجرّب تطبيق العملاء العناوين بالترتيب. وإن لم يصل لأي منها يقرأ القائمة من نسخة على GitHub، " +
                "فإذا حُجب عنوان أضف عنواناً جديداً هنا وسينتقل إليه العملاء دون تحديث التطبيق.",
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        when (val s = state) {
            is Load.Failed -> TextButton(onClick = vm::load) { Text("تعذّر التحميل، أعد المحاولة") }
            is Load.Loading -> Text("…", color = Lux.InkSoft)
            is Load.Ready -> Editor(s.value, saving, reach, vm)
        }
        loaded?.directory?.let { d ->
            Text(
                when {
                    !d.configured -> "نسخة GitHub: غير مفعّلة (أضف DIRECTORY_GITHUB_TOKEN على الخادم)"
                    d.error != null -> "نسخة GitHub: فشل التحديث — ${d.error}"
                    d.syncedAt != null -> "نسخة GitHub: محدّثة ${formatDateTime(d.syncedAt!!)}"
                    else -> "نسخة GitHub: لم تُحدَّث بعد"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (d.configured && d.error == null) Success else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (d.configured) TextButton(onClick = vm::sync) { Text("تحديث نسخة GitHub الآن") }
        }
    }
}

@Composable
private fun Editor(saved: AppServers, saving: Boolean, reach: Map<String, Reach>, vm: AppServersViewModel) {
    val rows = remember(saved) { mutableStateListOf<String>().apply { addAll(saved.servers.ifEmpty { listOf("") }) } }
    rows.forEachIndexed { i, value ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            FormField(
                value,
                { rows[i] = it.trim().take(200) },
                "العنوان ${i + 1}" + if (i == 0) " (الأساسي)" else "",
                modifier = Modifier.weight(1f),
                hint = "https://dejavu.example.com",
                note = when (reach[value.trim()]) {
                    Reach.CHECKING -> "جارٍ الاختبار…"
                    Reach.OK -> "✓ يصل من جوالك"
                    Reach.FAILED -> "✗ لا يصل من جوالك"
                    null -> null
                },
                isError = reach[value.trim()] == Reach.FAILED,
                enabled = !saving,
            )
            Row {
                IconButton(enabled = i > 0 && !saving, onClick = { rows.add(i - 1, rows.removeAt(i)) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "أعلى")
                }
                IconButton(enabled = i < rows.lastIndex && !saving, onClick = { rows.add(i + 1, rows.removeAt(i)) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "أسفل")
                }
                IconButton(enabled = rows.size > 1 && !saving, onClick = { rows.removeAt(i) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "حذف")
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = rows.size < 5 && !saving, onClick = { rows.add("") }) { Text("+ إضافة عنوان") }
        TextButton(enabled = rows.any { it.isNotBlank() }, onClick = { rows.filter { it.isNotBlank() }.forEach(vm::test) }) { Text("اختبار العناوين") }
    }
    SaveButton(busy = saving, enabled = rows.any { it.isNotBlank() }) { vm.save(rows.filter { it.isNotBlank() }) }
}
