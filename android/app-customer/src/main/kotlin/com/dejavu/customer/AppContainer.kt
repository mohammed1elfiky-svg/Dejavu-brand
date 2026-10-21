package com.dejavu.customer

import android.content.Context
import com.dejavu.core.data.AddressRepository
import com.dejavu.core.data.CartRepository
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.OrderRepository
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.network.ApiClient
import com.dejavu.customer.data.SecureTokenStore
import com.dejavu.customer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** Hand-wired dependencies for the whole app (small enough not to need a DI framework). */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        appScope.launch { session.restore() }
        // Keep the cart badge in step with the session.
        appScope.launch {
            session.state.distinctUntilChangedBy { it is SessionState.LoggedIn }.collect { state ->
                if (state is SessionState.LoggedIn) cart.refresh() else cart.clear()
            }
        }
    }
}
