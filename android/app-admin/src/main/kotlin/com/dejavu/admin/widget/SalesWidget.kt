package com.dejavu.admin.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dejavu.admin.AdminApp
import com.dejavu.admin.MainActivity
import com.dejavu.admin.R
import com.dejavu.core.model.Money
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Home-screen widget: today's sales and new app orders; tapping it opens the app. */
class SalesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Show the last known figures at once, then fetch fresh ones in the background.
        SalesWidget.renderCached(context)
        SalesWidget.enqueue(context)
    }

    override fun onEnabled(context: Context) {
        SalesWidget.enqueue(context)
    }
}

/** Fetches the figures and redraws every placed widget. */
class SalesWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { SalesWidget.refresh(applicationContext) }
        return Result.success()
    }
}

object SalesWidget {
    private const val WORK = "sales-widget"
    private const val PREFS = "sales_widget"
    private const val KEY_SALES = "sales"
    private const val KEY_ORDERS = "orders"
    private const val KEY_UPDATED = "updated"
    private const val KEY_ATTEMPT = "attempt"

    /** Fewer requests than this apart are skipped (system broadcasts can ask for many updates in a row). */
    private const val MIN_GAP_MS = 60_000L

    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("ar"))

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, SalesWidgetProvider::class.java)).isNotEmpty()

    /** Asks for a refresh when online; call it e.g. after signing in. */
    fun enqueue(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_ATTEMPT, 0L) < MIN_GAP_MS) return
        p.edit().putLong(KEY_ATTEMPT, now).apply()
        val request = OneTimeWorkRequestBuilder<SalesWidgetWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun refresh(context: Context) {
        if (!hasWidgets(context)) return
        val container = (context.applicationContext as AdminApp).container
        if (!container.hasSession) {
            prefs(context).edit().clear().apply()
            render(context, signedIn = false, sales = null, orders = null, status = null)
            return
        }
        val dashboard = container.dashboard.dashboard().getOrNull()
        val badges = container.dashboard.badges().getOrNull()
        val p = prefs(context)
        if (dashboard != null || badges != null) {
            p.edit().apply {
                dashboard?.let { putString(KEY_SALES, it.today.sales) }
                badges?.let { putInt(KEY_ORDERS, it.newOrders) }
                putString(KEY_UPDATED, LocalTime.now().format(timeFormat))
            }.apply()
        }
        val sales = p.getString(KEY_SALES, null)
        val orders = if (p.contains(KEY_ORDERS)) p.getInt(KEY_ORDERS, 0) else null
        val updated = p.getString(KEY_UPDATED, null)
        val status = when {
            dashboard == null && badges == null -> if (updated != null) "تعذّر التحديث · آخر تحديث $updated" else "تعذّر التحديث"
            else -> "آخر تحديث $updated"
        }
        render(context, signedIn = true, sales = sales, orders = orders, status = status)
    }

    /** Draws the saved figures without fetching. */
    fun renderCached(context: Context) {
        val container = (context.applicationContext as? AdminApp)?.container
        if (container != null && !container.hasSession) {
            render(context, signedIn = false, sales = null, orders = null, status = null)
            return
        }
        val p = prefs(context)
        val updated = p.getString(KEY_UPDATED, null)
        render(
            context,
            signedIn = true,
            sales = p.getString(KEY_SALES, null),
            orders = if (p.contains(KEY_ORDERS)) p.getInt(KEY_ORDERS, 0) else null,
            status = updated?.let { "آخر تحديث $it" } ?: "جارٍ التحديث…",
        )
    }

    private fun render(context: Context, signedIn: Boolean, sales: String?, orders: Int?, status: String?) {
        val views = RemoteViews(context.packageName, R.layout.widget_sales)
        if (signedIn) {
            views.setTextViewText(R.id.widget_sales_value, sales?.let { Money.formatBase(it) } ?: "—")
            views.setViewVisibility(R.id.widget_orders, View.VISIBLE)
            views.setTextViewText(R.id.widget_orders, "طلبات التطبيق الجديدة: ${orders?.toString() ?: "—"}")
        } else {
            views.setTextViewText(R.id.widget_sales_value, "سجّل الدخول")
            views.setViewVisibility(R.id.widget_orders, View.GONE)
        }
        views.setViewVisibility(R.id.widget_updated, if (status != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_updated, status.orEmpty())
        val open = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        AppWidgetManager.getInstance(context).updateAppWidget(ComponentName(context, SalesWidgetProvider::class.java), views)
    }
}
