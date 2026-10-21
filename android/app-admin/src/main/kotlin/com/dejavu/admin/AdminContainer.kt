package com.dejavu.admin

import android.content.Context
import com.dejavu.core.admin.AdminApi
import com.dejavu.core.admin.AdminOrderRepository
import com.dejavu.core.admin.AdminReturnRepository
import com.dejavu.core.admin.DashboardRepository
import com.dejavu.core.admin.ExpenseRepository
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.StaffSessionRepository
import com.dejavu.core.network.ApiClient
import com.dejavu.core.ui.storage.SecureTokenStore
import com.dejavu.core.ui.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-wired dependencies of the admin app. */
class AdminContainer(context: Context) {
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        appScope.launch { session.restore() }
    }
}
