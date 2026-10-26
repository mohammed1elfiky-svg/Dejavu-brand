package com.dejavu.customer

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.dejavu.core.data.AddressRepository
import com.dejavu.core.data.CartRepository
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.CurrencyRepository
import com.dejavu.core.data.NotificationRepository
import com.dejavu.core.data.OrderRepository
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.data.StoreExtrasRepository
import com.dejavu.core.data.WalletRepository
import com.dejavu.core.model.Currency
import com.dejavu.core.network.ApiClient
import com.dejavu.core.network.apiCall
import com.dejavu.core.network.normalizeBaseUrl
import com.dejavu.core.ui.background.BackgroundChecks
import com.dejavu.core.ui.push.Push
import com.dejavu.core.ui.push.PushConfig
import com.dejavu.core.ui.storage.CredentialsStore
import com.dejavu.core.ui.storage.SecureTokenStore
import com.dejavu.core.ui.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** Hand-wired dependencies for the whole app (small enough not to need a DI framework). */
class AppContainer(private val context: Context) {
    val settings = SettingsStore(context, BuildConfig.API_BASE_URL)
    private val tokens = SecureTokenStore(context)

    /** "Remember me" on the login screen; kept across sign-outs. */
    val savedLogin = CredentialsStore(context, "customer")
    private lateinit var sessionRef: SessionRepository

    /** A random id for this phone (no personal data): links app usage before and after sign-in. */
    val deviceId: String = context.getSharedPreferences("device", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit { putString("id", it) } }
    }

    private val apiAndClient = ApiClient.create(
        baseUrl = { settings.baseUrl },
        tokens = tokens,
        onSessionExpired = { sessionRef.onSessionExpired() },
        debugLogging = BuildConfig.DEBUG,
        configure = { addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-Device-Id", deviceId).build()) } },
    )
    private val api = apiAndClient.first

    /** Same client as [api]: sends the customer's token and refreshes it when it expires. */
    private val http = apiAndClient.second

    /** A private file of the signed-in customer (e.g. the shipping receipt photo of an order), as bytes. */
    suspend fun privateFile(id: String): Result<ByteArray> = apiCall {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(normalizeBaseUrl(settings.baseUrl) + "store/me/files/" + Uri.encode(id)).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.bytes() ?: throw IOException("Empty file")
            }
        }
    }

    val session = SessionRepository(api, tokens).also { sessionRef = it }
    val catalog = CatalogRepository(api)
    val cart = CartRepository(api)
    val addresses = AddressRepository(api)
    val orders = OrderRepository(api, cart)
    val notifications = NotificationRepository(api)
    private val currencyPrefs = context.getSharedPreferences("customer_currency", Context.MODE_PRIVATE).also { prefs ->
        // A currency saved by an earlier build was the shopper's own choice.
        if (!prefs.contains(CURRENCY_PICKED)) {
            val saved = context.getSharedPreferences("settings", Context.MODE_PRIVATE).contains("display_currency")
            prefs.edit { putBoolean(CURRENCY_PICKED, saved) }
        }
    }

    /** True while the app itself switches to the region's currency (not the shopper's own pick). */
    @Volatile
    private var applyingRegionCurrency = false

    val currencies = CurrencyRepository(api, preferred = { settings.currency }, save = {
        settings.currency = it
        if (!applyingRegionCurrency) currencyPrefs.edit { putBoolean(CURRENCY_PICKED, true) }
    })

    /**
     * Shows prices in the rial of the shopper's region ([code], e.g. YER / YEQ) when the store
     * offers it. Unless [force] (right after sign-up), only while the shopper never picked a
     * display currency themselves.
     */
    fun applyRegionCurrency(code: String?, force: Boolean = false) {
        val wanted = code?.trim()?.takeIf { it.isNotEmpty() }?.let { Currency.of(it) } ?: return
        if (!force && currencyPrefs.getBoolean(CURRENCY_PICKED, false)) return
        appScope.launch {
            if (currencies.rates.value == null) currencies.refresh()
            if (wanted !in currencies.available.value || currencies.selected.value == wanted) return@launch
            applyingRegionCurrency = true
            try {
                currencies.select(wanted)
            } finally {
                applyingRegionCurrency = false
            }
        }
    }
    val wallet = WalletRepository(api)

    /** App settings from the owner, wish list, suggestions, assistant, gift cards, analytics. */
    val extras = StoreExtrasRepository(api)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether Firebase was configured at build time; without it notifications live only in the app. */
    val pushEnabled = Push.init(
        context,
        PushConfig(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_SENDER_ID),
    )
    private var pushToken: String? = null
    private val inboxPrefs = context.getSharedPreferences("customer_inbox_mode", Context.MODE_PRIVATE)

    /**
     * Notifications come by polling the inbox unless Firebase push works end to end:
     * configured in this build AND sending from the server (the device registration says so).
     */
    @Volatile
    var inboxByPolling: Boolean = !pushEnabled || !inboxPrefs.getBoolean(SERVER_PUSH, false)
        private set

    private val _openOrder = MutableStateFlow<String?>(null)

    /** Order to show, requested from outside the UI (notification tap, payment page link). */
    val openOrder = _openOrder.asStateFlow()

    fun requestOrder(number: String?) {
        if (!number.isNullOrBlank()) _openOrder.value = number
    }

    fun orderOpened() {
        _openOrder.value = null
    }

    private val _openProduct = MutableStateFlow<String?>(null)

    /** Product to show, requested from outside the UI (a new-product notification tap). */
    val openProduct = _openProduct.asStateFlow()

    fun requestProduct(slug: String?) {
        if (!slug.isNullOrBlank()) _openProduct.value = slug
    }

    fun productOpened() {
        _openProduct.value = null
    }

    private val _openCart = MutableStateFlow(false)

    /** Cart to show, requested from outside the UI (a cart-reminder notification tap). */
    val openCart = _openCart.asStateFlow()

    fun requestCart() {
        _openCart.value = true
    }

    fun cartOpened() {
        _openCart.value = false
    }

    private val _openWallet = MutableStateFlow(false)

    /** Wallet to show, requested from outside the UI (a top-up notification tap). */
    val openWallet = _openWallet.asStateFlow()

    fun requestWallet() {
        _openWallet.value = true
    }

    fun walletOpened() {
        _openWallet.value = false
    }

    fun start() {
        // Channels are needed for push and for the background inbox checks alike.
        ensureChannels(context)
        // Without working push: poll the notification inbox instead (the checks skip themselves otherwise).
        BackgroundChecks.schedule(context, InboxWorker.WORK_NAME, InboxWorker::class.java)
        appScope.launch {
            while (true) {
                if (inboxByPolling) InboxChecker.check(context, this@AppContainer)
                delay(60_000)
            }
        }
        appScope.launch { session.restore() }
        appScope.launch { currencies.refresh() }
        // The owner's app settings (sign-in rule, ads, assistant).
        appScope.launch { extras.refreshConfig() }
        // A signed-in shopper who never chose a currency sees their region's rial.
        appScope.launch {
            session.state.collect { state ->
                if (state is SessionState.LoggedIn) applyRegionCurrency(state.customer?.preferredCurrency)
            }
        }
        // Keep the cart badge, bell badge and push registration in step with the session.
        appScope.launch {
            session.state.distinctUntilChangedBy { it is SessionState.LoggedIn }.collect { state ->
                if (state is SessionState.LoggedIn) {
                    cart.refresh()
                    notifications.refreshUnread()
                    registerPush()
                    extras.refreshWishlistIds()
                } else {
                    cart.clear()
                    notifications.clear()
                    extras.clearWishlist()
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
            if (session.isLoggedIn) {
                notifications.registerDevice(t, BuildConfig.VERSION_NAME).onSuccess { r ->
                    inboxByPolling = !r.pushEnabled
                    inboxPrefs.edit { putBoolean(SERVER_PUSH, r.pushEnabled) }
                }
            }
        }
    }

    /** Signs out after detaching this phone from the account, so it stops receiving its notifications. */
    suspend fun logout() {
        pushToken?.let { notifications.unregisterDevice(it) }
        session.logout()
    }

    companion object {
        const val CHANNEL_ORDERS = "orders"
        const val CHANNEL_OFFERS = "offers"
        private const val SERVER_PUSH = "server_push"
        private const val CURRENCY_PICKED = "picked"

        fun ensureChannels(context: Context) {
            Push.ensureChannel(context, CHANNEL_ORDERS, "حالة الطلبات")
            Push.ensureChannel(context, CHANNEL_OFFERS, "العروض والمنتجات الجديدة")
        }
    }
}
