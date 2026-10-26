package com.dejavu.customer

import android.app.PendingIntent
import android.content.Intent
import com.dejavu.core.ui.push.DejavuMessagingService
import com.dejavu.core.ui.push.Push
import kotlinx.coroutines.launch

/** Order updates, offers and new products from the backend. Taps open the order or product in [MainActivity]. */
class PushService : DejavuMessagingService() {
    private val container get() = (application as DejavuApp).container

    override fun onToken(token: String) = container.registerPush(token)

    override fun onPush(title: String, body: String, data: Map<String, String>) {
        container.appScope.launch { container.notifications.refreshUnread() }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ORDER, data["order"])
            .putExtra(MainActivity.EXTRA_TOP_UP, data["topUp"])
            .putExtra(MainActivity.EXTRA_PRODUCT, data["product"])
            .putExtra(MainActivity.EXTRA_ORDER_NUMBER, data["orderNumber"])
            .putExtra(MainActivity.EXTRA_PRODUCT_SLUG, data["productSlug"])
            .putExtra(MainActivity.EXTRA_SCREEN, data["screen"])
            .putExtra(MainActivity.EXTRA_CATEGORY, data["category"])
            .putExtra(MainActivity.EXTRA_URL, data["url"])
            .putExtra(MainActivity.EXTRA_REVIEW, data["review"])
        val tap = PendingIntent.getActivity(
            this,
            (
                data["order"] ?: data["orderNumber"] ?: data["topUp"] ?: data["product"] ?: data["productSlug"]
                    ?: data["category"] ?: data["url"]
                    ?: data["promotion"] ?: data["screen"] ?: data["notificationId"]
                ).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = if (data["type"] == "PROMOTION") AppContainer.CHANNEL_OFFERS else AppContainer.CHANNEL_ORDERS
        // onMessageReceived runs on a background thread, so the blocking download is fine here.
        val image = Push.loadImage(data["image"])
        Push.show(this, channel, com.dejavu.core.ui.R.drawable.ic_stat_dejavu, title, body, tap, image)
    }
}
