package com.dejavu.customer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dejavu.core.model.AppNotification
import com.dejavu.core.ui.push.Push
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.OffsetDateTime

/** Background half of [InboxChecker]: runs about every 15 minutes, also while the app is closed. */
class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as DejavuApp).container
        if (container.inboxByPolling) InboxChecker.check(applicationContext, container)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "customer-inbox"
    }
}

/**
 * When push does not work end to end (no Firebase in this build, or the server sends
 * none), checks the notification inbox and shows every new unread item as a local
 * notification, so top-up results and order updates still arrive. Checked every minute
 * while the app runs and by [InboxWorker] in the background.
 */
object InboxChecker {
    private const val PREFS = "inbox_checks"
    private const val KEY_LAST_SENT = "last_sent_at"
    private const val FIRST_RUN_WINDOW_MS = 30 * 60 * 1000L
    private val lock = Mutex()

    suspend fun check(context: Context, container: AppContainer) = lock.withLock {
        if (!container.session.isLoggedIn) return@withLock
        val page = container.notifications.page().getOrNull() ?: return@withLock

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_LAST_SENT, -1L)
        // First check on this phone: only very recent items, not the whole history.
        val after = if (stored >= 0) stored else System.currentTimeMillis() - FIRST_RUN_WINDOW_MS

        val fresh = page.items
            .mapNotNull { n -> sentMillis(n.sentAt)?.let { n to it } }
            .filter { (n, sent) -> !n.isRead && sent > after }
            .sortedBy { it.second }
        if (fresh.isNotEmpty()) AppContainer.ensureChannels(context)
        fresh.forEach { (n, _) -> show(context, n) }

        val newest = page.items.mapNotNull { sentMillis(it.sentAt) }.maxOrNull()
        val marker = maxOf(after, newest ?: after)
        if (marker != stored) prefs.edit { putLong(KEY_LAST_SENT, marker) }
    }

    /** Same tap behaviour as [PushService]: [MainActivity] opens the order, wallet or product. */
    private fun show(context: Context, n: AppNotification) {
        val data = n.data
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ORDER, data["order"])
            .putExtra(MainActivity.EXTRA_TOP_UP, data["topUp"])
            .putExtra(MainActivity.EXTRA_PRODUCT, data["product"])
        val tap = PendingIntent.getActivity(
            context,
            n.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val promotion = n.type == "PROMOTION" || data["type"] == "PROMOTION"
        val channel = if (promotion) AppContainer.CHANNEL_OFFERS else AppContainer.CHANNEL_ORDERS
        Push.show(context, channel, com.dejavu.core.ui.R.drawable.ic_stat_dejavu, n.title, n.body, tap)
    }

    private fun sentMillis(iso: String): Long? =
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}
