package com.dejavu.customer

import com.dejavu.core.model.AnalyticsBatch
import com.dejavu.core.model.AnalyticsSession
import com.dejavu.core.model.AnalyticsView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Time spent in the app and on each product page, sent to the server in small batches
 * (for the owner's "سلوك العملاء" report). Nothing personal is sent besides the random
 * device id and, when signed in, the customer's own account.
 */
object Analytics {
    private var container: AppContainer? = null
    private var sessionId: String? = null
    private var sessionStart: Instant? = null
    private var activeSince: Long = 0
    private var seconds = 0L
    private var ticker: Job? = null
    private val views = mutableListOf<AnalyticsView>()
    private val openViews = mutableMapOf<String, Long>()

    fun init(container: AppContainer) {
        this.container = container
    }

    /** The app came to the foreground. A pause of less than a minute continues the same session. */
    @Synchronized
    fun onStart() {
        val now = System.currentTimeMillis()
        if (sessionId == null || now - lastStop > 60_000) {
            sessionId = UUID.randomUUID().toString()
            sessionStart = Instant.ofEpochMilli(now)
            seconds = 0
        }
        activeSince = now
        val c = container ?: return
        ticker?.cancel()
        // Long sessions are sent every few minutes, so closing the app suddenly loses little.
        ticker = c.appScope.launch {
            while (isActive) {
                delay(3 * 60_000L)
                flush()
            }
        }
    }

    private var lastStop = 0L

    /** The app went to the background: count the time and send what was collected. */
    @Synchronized
    fun onStop() {
        val now = System.currentTimeMillis()
        if (activeSince > 0) seconds += (now - activeSince) / 1000
        activeSince = 0
        lastStop = now
        ticker?.cancel()
        flush()
    }

    /** A product page opened. */
    @Synchronized
    fun productOpened(productId: String) {
        openViews[productId] = System.currentTimeMillis()
    }

    /** The product page closed (or the app left it). */
    @Synchronized
    fun productClosed(productId: String) {
        val started = openViews.remove(productId) ?: return
        val secs = ((System.currentTimeMillis() - started) / 1000).toInt()
        views += AnalyticsView(productId, secs, Instant.ofEpochMilli(started).toString())
        if (views.size >= 20) flush()
    }

    @Synchronized
    private fun flush() {
        val c = container ?: return
        val id = sessionId ?: return
        val start = sessionStart ?: return
        val running = if (activeSince > 0) (System.currentTimeMillis() - activeSince) / 1000 else 0
        val batch = AnalyticsBatch(
            deviceId = c.deviceId,
            sessions = listOf(AnalyticsSession(id, start.toString(), (seconds + running).toInt())),
            views = views.toList(),
        )
        views.clear()
        c.appScope.launch { c.extras.sendAnalytics(batch) }
    }
}
