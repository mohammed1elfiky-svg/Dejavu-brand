package com.dejavu.admin

import android.content.Context
import com.dejavu.core.admin.AdminApi
import com.dejavu.core.admin.AdminOrderRepository
import com.dejavu.core.admin.AdminReturnRepository
import com.dejavu.core.admin.DashboardRepository
import com.dejavu.core.admin.ExpenseRepository
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.PromotionRepository
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.SmsRepository
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.admin.WalletAdminRepository
import com.dejavu.core.admin.StaffRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.StaffSessionRepository
import com.dejavu.core.network.ApiClient
import com.dejavu.core.ui.push.Push
import com.dejavu.core.ui.push.PushConfig
import com.dejavu.core.ui.storage.SecureTokenStore
import com.dejavu.core.ui.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import com.dejavu.admin.print.PrinterStore
import com.dejavu.core.ui.storage.CredentialsStore
import com.dejavu.core.ui.background.BackgroundChecks
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** Hand-wired dependencies of the admin app. */
class AdminContainer(private val context: Context) {
    val settings = SettingsStore(context, BuildConfig.API_BASE_URL)
    private val tokens = SecureTokenStore(context, name = "staff_session")
    private lateinit var sessionRef: StaffSessionRepository

    private val api: AdminApi = ApiClient.retrofit(
        baseUrl = { settings.baseUrl },
        tokens = tokens,
        onSessionExpired = { sessionRef.onSessionExpired() },
        debugLogging = BuildConfig.DEBUG,
    ).first.create(AdminApi::class.java)

    val session = StaffSessionRepository(api, tokens).also { sessionRef = it }
    val dashboard = DashboardRepository(api)
    val orders = AdminOrderRepository(api)
    val returns = AdminReturnRepository(api)
    val inventory = InventoryRepository(api)
    val purchasing = PurchasingRepository(api)
    val expenses = ExpenseRepository(api)
    val finance = FinanceRepository(api)
    val pos = PosRepository(api)
    val treasury = TreasuryRepository(api)
    val wallet = WalletAdminRepository(api)
    val promotions = PromotionRepository(api)
    val staff = StaffRepository(api)
    val sms = SmsRepository(api)

    /** Receipt and barcode-label printers of this device. */
    val printers = PrinterStore(context)

    /** "Remember me" on the login screen (kept after signing out). */
    val savedLogin = CredentialsStore(context, "staff")

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether Firebase was configured at build time. */
    val pushEnabled = Push.init(
        context,
        PushConfig(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_SENDER_ID),
    )
    private var pushToken: String? = null
    private val alertPrefs = context.getSharedPreferences("staff_alerts_mode", Context.MODE_PRIVATE)

    /**
     * Alerts come by polling unless Firebase push works end to end: configured in this
     * build AND sending from the server (the device registration says so).
     */
    @Volatile
    var alertsByPolling: Boolean = !pushEnabled || !alertPrefs.getBoolean(SERVER_PUSH, false)
        private set

    private val _openOrderId = MutableStateFlow<String?>(null)

    /** Order to show after a "new order" notification tap. */
    val openOrderId = _openOrderId.asStateFlow()

    fun requestOrder(id: String?) {
        if (!id.isNullOrBlank()) _openOrderId.value = id
    }

    fun orderOpened() {
        _openOrderId.value = null
    }

    private val _openTopUpId = MutableStateFlow<String?>(null)

    /** Top-up request to show after an alert tap. */
    val openTopUpId = _openTopUpId.asStateFlow()

    fun requestTopUp(id: String?) {
        if (!id.isNullOrBlank()) _openTopUpId.value = id
    }

    fun topUpOpened() {
        _openTopUpId.value = null
    }

    /** Whether this device holds a staff session (also outside the UI, e.g. in workers). */
    val hasSession: Boolean get() = tokens.current() != null

    fun start() {
        Push.ensureChannel(context, CHANNEL_STAFF, "طلبات جديدة")
        appScope.launch { session.restore() }
        // Without working push: poll for new orders and top-up requests instead.
        BackgroundChecks.schedule(context, "staff-alerts", StaffAlertsWorker::class.java)
        appScope.launch {
            while (true) {
                if (alertsByPolling) StaffAlertsChecker.check(context, this@AdminContainer)
                delay(60_000)
            }
        }
        appScope.launch {
            session.state.distinctUntilChangedBy { it is StaffSession.LoggedIn }.collect {
                if (it is StaffSession.LoggedIn) registerPush()
            }
        }
    }

    fun registerPush(token: String? = null) {
        if (!pushEnabled) return
        appScope.launch {
            val t = token ?: Push.token() ?: return@launch
            pushToken = t
            if (session.state.value is StaffSession.LoggedIn) {
                session.registerDevice(t, BuildConfig.VERSION_NAME).onSuccess { r ->
                    alertsByPolling = !r.pushEnabled
                    alertPrefs.edit().putBoolean(SERVER_PUSH, r.pushEnabled).apply()
                }
            }
        }
    }

    suspend fun logout() {
        pushToken?.let { session.unregisterDevice(it) }
        session.logout()
        StaffAlertsChecker.reset(context)
    }

    companion object {
        const val CHANNEL_STAFF = "staff"
        private const val SERVER_PUSH = "server_push"
    }
}
