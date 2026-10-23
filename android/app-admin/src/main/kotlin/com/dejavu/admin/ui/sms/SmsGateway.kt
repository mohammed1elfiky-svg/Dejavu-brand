package com.dejavu.admin.ui.sms

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.AdminApp
import com.dejavu.admin.AdminContainer
import com.dejavu.admin.MainActivity
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.OutgoingSms
import com.dejavu.core.admin.SmsRepository
import com.dejavu.core.admin.SmsSettings
import com.dejavu.core.admin.SmsToSend
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ------------------------------------------------------------- switch ---

/**
 * The store phone as SMS gateway: while switched on (kept across restarts), [SmsGatewayService]
 * takes the texts the server queued and sends them with this phone's SIM.
 */
object SmsGateway {
    private const val PREFS = "sms_gateway"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(ENABLED, on) }
        if (on) start(context) else stop(context)
    }

    fun canSend(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasTelephony(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /** Runs the gateway on this device (no-op when the system refuses a start from the background). */
    fun start(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, SmsGatewayService::class.java)) }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, SmsGatewayService::class.java)) }
    }

    /** Starts the gateway when a staff session exists and it was switched on; stops it on sign-out. */
    fun watch(app: AdminApp) {
        val container = app.container
        container.appScope.launch {
            container.session.state.map { it is StaffSession.LoggedIn }.distinctUntilChanged().collect { loggedIn ->
                if (!loggedIn) stop(app) else if (isEnabled(app) && canSend(app)) start(app)
            }
        }
    }
}

// ------------------------------------------------------------ service ---

/** Foreground service that sends the server's queued texts from this phone's SIM. */
class SmsGatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var sentCount = 0
    private val nextKey = AtomicInteger(0)

    private class Pending(parts: Int) {
        val left = AtomicInteger(parts)

        /** "" = every part was sent, otherwise the error. */
        val done = CompletableDeferred<String>()
    }

    private val pending = ConcurrentHashMap<Int, Pending>()

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = pending[intent.getIntExtra(EXTRA_KEY, -1)] ?: return
            if (resultCode == Activity.RESULT_OK) {
                if (p.left.decrementAndGet() <= 0) p.done.complete("")
            } else {
                p.done.complete(errorText(resultCode))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, sentReceiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always go foreground first: a service started with startForegroundService must.
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val container = (application as AdminApp).container
        if (!SmsGateway.isEnabled(this) || !container.hasSession) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { pollLoop(container) }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(sentReceiver) }
        super.onDestroy()
    }

    private suspend fun pollLoop(container: AdminContainer) {
        while (currentCoroutineContext().isActive) {
            if (!SmsGateway.isEnabled(this) || !container.hasSession) {
                stopSelf()
                return
            }
            if (!SmsGateway.canSend(this)) {
                showStatus("لا يوجد إذن لإرسال الرسائل النصية")
                delay(ERROR_DELAY_MS)
                continue
            }
            val wait = container.sms.claim(BATCH).fold(
                { batch ->
                    batch.forEach { m ->
                        val error = send(m)
                        if (error == null) sentCount++
                        report(container, m.id, error)
                    }
                    if (batch.isNotEmpty()) showStatus("أُرسلت $sentCount رسالة منذ التشغيل")
                    // A full batch: more may be waiting.
                    if (batch.size >= BATCH) 0L else POLL_MS
                },
                {
                    showStatus("تعذر الاتصال بالخادم، إعادة المحاولة بعد قليل")
                    ERROR_DELAY_MS
                },
            )
            if (wait > 0) delay(wait)
        }
    }

    private suspend fun report(container: AdminContainer, id: String, error: String?) {
        repeat(3) { attempt ->
            if (container.sms.report(id, error == null, error).isSuccess) return
            delay(2_000L * (attempt + 1))
        }
    }

    /** Sends one text; null when it went out, else why not. */
    private suspend fun send(m: SmsToSend): String? {
        val manager = smsManager() ?: return "الهاتف لا يدعم إرسال الرسائل"
        val key = nextKey.incrementAndGet()
        return try {
            val parts = manager.divideMessage(m.body)
            if (parts.isEmpty()) return "رسالة فارغة"
            val p = Pending(parts.size)
            pending[key] = p
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { i -> sentIntents.add(sentIntent(key, i)) }
            if (parts.size == 1) {
                manager.sendTextMessage(m.phone, null, parts[0], sentIntents[0], null)
            } else {
                manager.sendMultipartTextMessage(m.phone, null, parts, sentIntents, null)
            }
            // No confirmation in time (some phones never send one): the call did not fail, count it as sent.
            withTimeoutOrNull(SENT_TIMEOUT_MS) { p.done.await() }?.ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotBlank() } ?: "تعذر إرسال الرسالة"
        } finally {
            pending.remove(key)
        }
    }

    private fun sentIntent(key: Int, part: Int): PendingIntent {
        val intent = Intent(ACTION_SENT).setPackage(packageName).putExtra(EXTRA_KEY, key)
        return PendingIntent.getBroadcast(this, key * 100 + part, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("بانتظار الرسائل"), type)
        true
    } catch (e: Exception) {
        false
    }

    private fun showStatus(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "بوابة الرسائل", NotificationManager.IMPORTANCE_LOW))
        }
        val tap = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.dejavu.core.ui.R.drawable.ic_stat_dejavu)
            .setContentTitle("بوابة الرسائل تعمل")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap)
            .build()
    }

    private companion object {
        const val CHANNEL = "sms_gateway"
        const val NOTIFICATION_ID = 4107
        const val ACTION_SENT = "com.dejavu.admin.SMS_SENT"
        const val EXTRA_KEY = "key"
        const val BATCH = 10
        const val POLL_MS = 10_000L
        const val ERROR_DELAY_MS = 60_000L
        const val SENT_TIMEOUT_MS = 20_000L

        fun errorText(code: Int): String = when (code) {
            SmsManager.RESULT_ERROR_RADIO_OFF -> "الشبكة مغلقة على الهاتف (وضع الطيران)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "لا توجد تغطية شبكة"
            SmsManager.RESULT_ERROR_NULL_PDU -> "تعذر تجهيز الرسالة"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "رفضت الشبكة الرسالة (تحقق من الرصيد والرقم)"
            else -> "فشل الإرسال (رمز $code)"
        }
    }
}

// ------------------------------------------------------------- screen ---

class SmsGatewayViewModel(private val repo: SmsRepository) : BaseViewModel() {
    private val _settings = MutableStateFlow<Load<SmsSettings>>(Load.Loading)
    val settings = _settings.asStateFlow()
    private val _messages = MutableStateFlow<Load<List<OutgoingSms>>>(Load.Loading)
    val messages = _messages.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)

    /** Status filter of the list (null = all). */
    val status = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** Reloads settings and messages; a failed refresh keeps what is already shown. */
    fun refresh() {
        viewModelScope.launch {
            repo.settings().fold(
                { _settings.value = Load.Ready(it) },
                { if (_settings.value !is Load.Ready) _settings.value = Load.Failed(it.userMessage()) },
            )
        }
        loadMessages()
    }

    private fun loadMessages() {
        val status = _status.value
        viewModelScope.launch {
            repo.recent(status).fold(
                { if (status == _status.value) _messages.value = Load.Ready(it) },
                { if (status == _status.value && _messages.value !is Load.Ready) _messages.value = Load.Failed(it.userMessage()) },
            )
        }
    }

    fun filter(status: String?) {
        if (status == _status.value) return
        _status.value = status
        _messages.value = Load.Loading
        loadMessages()
    }

    fun setRequireOtp(on: Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.setRequireOtp(on).orSay()?.let {
                _settings.value = Load.Ready(it)
                say(if (on) "سيُطلب رمز تحقق برسالة نصية عند تسجيل العملاء" else "أُلغي طلب رمز التحقق عند التسجيل")
            }
            _busy.value = false
        }
    }

    fun show(message: String) = say(message)
}

private val STATUSES = listOf("PENDING", "SENDING", "SENT", "FAILED")

private fun statusLabel(status: String?): String = when (status) {
    null -> "الكل"
    "PENDING" -> "قيد الانتظار"
    "SENDING" -> "جارٍ الإرسال"
    "SENT" -> "أُرسلت"
    "FAILED" -> "فشلت"
    else -> status
}

@Composable
private fun statusTint(status: String): Color = when (status) {
    "PENDING" -> Warning
    "SENDING" -> MaterialTheme.colorScheme.primary
    "SENT" -> Success
    else -> MaterialTheme.colorScheme.error
}

private fun purposeLabel(purpose: String): String = when (purpose) {
    "OTP" -> "رمز تحقق"
    "RECEIPT" -> "سند قبض"
    "EXCHANGE" -> "مصارفة"
    else -> "رسالة"
}

/** SMS from the store phone: this phone as gateway, gateway status, sign-up codes and recent texts. */
@Composable
fun SmsGatewayScreen(onBack: () -> Unit) {
    val vm = adminViewModel { SmsGatewayViewModel(it.sms) }
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var gatewayOn by remember { mutableStateOf(SmsGateway.isEnabled(context)) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) {
        while (true) {
            vm.refresh()
            delay(15_000)
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (SmsGateway.canSend(context)) {
            SmsGateway.setEnabled(context, true)
            gatewayOn = true
            vm.show("تم تشغيل بوابة الرسائل على هذا الهاتف")
        } else {
            vm.show("يلزم السماح بإرسال الرسائل النصية لتشغيل البوابة")
        }
    }

    fun switchGateway(on: Boolean) {
        if (!on) {
            SmsGateway.setEnabled(context, false)
            gatewayOn = false
            return
        }
        if (!SmsGateway.hasTelephony(context)) return vm.show("هذا الجهاز لا يدعم إرسال الرسائل النصية")
        val needed = buildList {
            if (!SmsGateway.canSend(context)) add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) {
            SmsGateway.setEnabled(context, true)
            gatewayOn = true
        } else {
            permissions.launch(needed.toTypedArray())
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("الرسائل النصية (SMS)", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("استخدام هذا الهاتف لإرسال الرسائل", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(checked = gatewayOn, onCheckedChange = ::switchGateway)
                    }
                    Text(
                        if (gatewayOn) {
                            "يرسل هذا الهاتف الرسائل من شريحته كل بضع ثوانٍ ما دام متصلاً بالإنترنت (يظهر إشعار دائم). أبقِه مشحوناً ولا توقف التطبيق من الإعدادات."
                        } else {
                            "شغّل البوابة على هاتف المحل الذي فيه الشريحة: تُرسل منه رموز التحقق وإشعارات سندات القبض والمصارفة للعملاء."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("حالة البوابة", style = MaterialTheme.typography.titleSmall)
                    when (val s = settings) {
                        Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        is Load.Failed -> {
                            Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = vm::refresh) { Text("إعادة المحاولة") }
                        }
                        is Load.Ready -> {
                            val v = s.value
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("هاتف الإرسال", Modifier.weight(1f))
                                StatusChip(if (v.gatewayOnline) "متصل" else "غير متصل", if (v.gatewayOnline) Success else MaterialTheme.colorScheme.error)
                            }
                            Text(
                                v.gatewaySeenAt?.let { "آخر اتصال: ${formatDateTime(it)}" } ?: "لم يتصل أي هاتف بعد",
                                style = MaterialTheme.typography.bodySmall,
                                color = Lux.InkSoft,
                            )
                            Text("رسائل بانتظار الإرسال: ${v.pending}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("طلب رمز تحقق عند تسجيل العملاء")
                                    Text("يصل العميل رمز برسالة نصية لتأكيد رقمه", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                }
                                Switch(checked = v.requirePhoneOtp, onCheckedChange = vm::setRequireOtp, enabled = !busy)
                            }
                            if (v.requirePhoneOtp && !v.gatewayOnline) {
                                Text(
                                    "تنبيه: لا يوجد هاتف إرسال متصل، لن تصل رموز التحقق للعملاء.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            item { Text("آخر الرسائل", style = MaterialTheme.typography.titleSmall) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf<String?>(null) + STATUSES).forEach { s ->
                        FilterChip(selected = s == status, onClick = { vm.filter(s) }, label = { Text(statusLabel(s)) })
                    }
                }
            }
            when (val m = messages) {
                Load.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is Load.Failed -> item {
                    Column {
                        Text(m.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::refresh) { Text("إعادة المحاولة") }
                    }
                }
                is Load.Ready -> {
                    if (m.value.isEmpty()) {
                        item { Text("لا توجد رسائل", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(m.value, key = { it.id }) { sms -> SmsRow(sms) }
                }
            }
        }
    }
}

@Composable
private fun SmsRow(sms: OutgoingSms) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("‎${sms.phone}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${purposeLabel(sms.purpose)} · ${formatDateTime(sms.sentAt ?: sms.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            }
            StatusChip(statusLabel(sms.status), statusTint(sms.status))
        }
        Text(sms.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        sms.error?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
