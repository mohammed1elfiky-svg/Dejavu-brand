package com.dejavu.customer

import android.app.PendingIntent
import android.content.Intent
import com.dejavu.core.ui.push.DejavuMessagingService
import com.dejavu.core.ui.push.Push
import kotlinx.coroutines.launch

/** Order updates from the backend. Taps open the order in [MainActivity]. */
class PushService : DejavuMessagingService() {
    private val container get() = (application as DejavuApp).container

    override fun onToken(token: String) = container.registerPush(token)

    override fun onPush(title: String, body: String, data: Map<String, String>) {
        container.appScope.launch { container.notifications.refreshUnread() }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ORDER, data["order"])
            .putExtra(MainActivity.EXTRA_TOP_UP, data["topUp"])
        val tap = PendingIntent.getActivity(
            this,
            (data["order"] ?: data["topUp"]).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        Push.show(this, AppContainer.CHANNEL_ORDERS, com.dejavu.core.ui.R.drawable.ic_stat_dejavu, title, body, tap)
    }
}
