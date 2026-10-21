package com.dejavu.admin

import android.app.PendingIntent
import android.content.Intent
import com.dejavu.core.ui.push.DejavuMessagingService
import com.dejavu.core.ui.push.Push

/** New-order alerts for staff. Taps open the order. */
class PushService : DejavuMessagingService() {
    private val container get() = (application as AdminApp).container

    override fun onToken(token: String) = container.registerPush(token)

    override fun onPush(title: String, body: String, data: Map<String, String>) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ORDER_ID, data["orderId"])
        val tap = PendingIntent.getActivity(
            this,
            data["orderId"].hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        Push.show(this, AdminContainer.CHANNEL_STAFF, com.dejavu.core.ui.R.drawable.ic_stat_dejavu, title, body, tap)
    }
}
