package com.dejavu.customer

import android.content.Context
import com.dejavu.core.data.AddressRepository
import com.dejavu.core.data.CartRepository
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.CurrencyRepository
import com.dejavu.core.data.NotificationRepository
import com.dejavu.core.data.OrderRepository
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.SessionState
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

/** Hand-wired dependencies for the whole app (small enough not to need a DI framework). */
class AppContainer(private val context: Context) {
    val settings = SettingsStore(context, BuildConfig.API_BASE_URL)
    private val tokens = SecureTokenStore(context)
    private lateinit var sessionRef: SessionRepository

    private val api = ApiClient.create(
        baseUrl = { settings.baseUrl },
        tokens = tokens,
        onSessionExpired = { sessionRef.onSessionExpired() },
        debugLogging = BuildConfig.DEBUG,
    ).first

    val session = SessionRepository(api, tokens).also { sessionRef = it }
    val catalog = CatalogRepository(api)
    val cart = CartRepository(api)
    val addresses = AddressRepository(api)
    val orders = OrderRepository(api, cart)
    val notifications = NotificationRepository(api)
    val currencies = CurrencyRepository(api, preferred = { settings.currency }, save = { settings.currency = it })

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether Firebase was configured at build time; without it notifications live only in the app. */
    val pushEnabled = Push.init(
        context,
        PushConfig(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_SENDER_ID),
    )
    private var pushToken: String? = null

    private val _openOrder = MutableStateFlow<String?>(null)

    /** Order to show, requested from outside the UI (notification tap, payment page link). */
    val openOrder = _openOrder.asStateFlow()

    fun requestOrder(number: String?) {
        if (!number.isNullOrBlank()) _openOrder.value = number
    }

    fun orderOpened() {
        _openOrder.value = null
    }

    fun start() {
        if (pushEnabled) Push.ensureChannel(context, CHANNEL_ORDERS, "حالة الطلبات")
        appScope.launch { session.restore() }
        appScope.launch { currencies.refresh() }
        // Keep the cart badge, bell badge and push registration in step with the session.
        appScope.launch {
            session.state.distinctUntilChangedBy { it is SessionState.LoggedIn }.collect { state ->
                if (state is SessionState.LoggedIn) {
                    cart.refresh()
                    notifications.refreshUnread()
                    registerPush()
                } else {
                    cart.clear()
                    notifications.clear()
                }
            }
        }
    }

    /** Called with a new FCM token, or at sign-in. */
    fun registerPush(token: String? = null) {
        if (!pushEnabled) return
        appScope.launch {
            val t = token ?: Push.token() ?: return@launch
            pushToken = t
            if (session.isLoggedIn) notifications.registerDevice(t, BuildConfig.VERSION_NAME)
        }
    }

    /** Signs out after detaching this phone from the account, so it stops receiving its notifications. */
    suspend fun logout() {
        pushToken?.let { notifications.unregisterDevice(it) }
        session.logout()
    }

    companion object {
        const val CHANNEL_ORDERS = "orders"
    }
}
