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
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.admin.WalletAdminRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.StaffSessionRepository
import com.dejavu.core.network.ApiClient
import com.dejavu.core.ui.push.Push
import com.dejavu.core.ui.push.PushConfig
import com.dejavu.core.ui.storage.SecureTokenStore
import com.dejavu.core.ui.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether Firebase was configured at build time. */
    val pushEnabled = Push.init(
        context,
        PushConfig(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_SENDER_ID),
    )
    private var pushToken: String? = null

    private val _openOrderId = MutableStateFlow<String?>(null)

    /** Order to show after a "new order" notification tap. */
    val openOrderId = _openOrderId.asStateFlow()

    fun requestOrder(id: String?) {
        if (!id.isNullOrBlank()) _openOrderId.value = id
    }

    fun orderOpened() {
        _openOrderId.value = null
    }

    fun start() {
        if (pushEnabled) Push.ensureChannel(context, CHANNEL_STAFF, "طلبات جديدة")
        appScope.launch { session.restore() }
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
            if (session.state.value is StaffSession.LoggedIn) session.registerDevice(t, BuildConfig.VERSION_NAME)
        }
    }

    suspend fun logout() {
        pushToken?.let { session.unregisterDevice(it) }
        session.logout()
    }

    companion object {
        const val CHANNEL_STAFF = "staff"
    }
}
