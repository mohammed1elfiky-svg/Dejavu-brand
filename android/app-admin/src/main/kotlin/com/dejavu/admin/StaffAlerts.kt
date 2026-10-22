package com.dejavu.admin

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dejavu.core.admin.StaffAlert
import com.dejavu.core.ui.push.Push
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * New app orders and top-up requests as local notifications, for when Firebase push is
 * not set up: checked every minute while the app runs and about every 15 minutes in the
 * background ([StaffAlertsWorker]).
 */
object StaffAlertsChecker {
    private const val PREFS = "staff_alerts"
    private const val SINCE = "since"
    private val lock = Mutex()

    suspend fun check(context: Context, container: AdminContainer) = lock.withLock {
        if (!container.hasSession) return@withLock
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getString(SINCE, null)
        val res = container.pos.alerts(since).getOrNull() ?: return@withLock
        prefs.edit { putString(SINCE, res.now) }
        // First check on this device: start from now instead of replaying the last day.
        if (since == null) return@withLock
        Push.ensureChannel(context, AdminContainer.CHANNEL_STAFF, "طلبات جديدة")
        res.alerts.asReversed().forEach { show(context, it) }
    }

    /** Forget the position, e.g. after signing out (the next sign-in starts fresh). */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(SINCE) }
    }

    private fun show(context: Context, a: StaffAlert) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ORDER_ID, a.data["orderId"])
            .putExtra(MainActivity.EXTRA_TOP_UP_ID, a.data["topUpId"])
        val tap = PendingIntent.getActivity(context, a.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        Push.show(context, AdminContainer.CHANNEL_STAFF, com.dejavu.core.ui.R.drawable.ic_stat_dejavu, a.title, a.body, tap)
    }
}

/** Background check run by WorkManager. */
class StaffAlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AdminApp).container
        if (container.alertsByPolling) StaffAlertsChecker.check(applicationContext, container)
        return Result.success()
    }
}
