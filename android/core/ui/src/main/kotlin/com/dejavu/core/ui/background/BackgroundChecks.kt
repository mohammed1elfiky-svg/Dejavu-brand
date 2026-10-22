package com.dejavu.core.ui.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Periodic checks for news (new orders, top-ups, notifications) that show a local
 * notification. Used when Firebase push is not configured, so alerts still arrive
 * while the app is closed (Android runs them about every 15 minutes).
 */
object BackgroundChecks {
    fun schedule(context: Context, name: String, worker: Class<out ListenableWorker>) {
        val request = PeriodicWorkRequest.Builder(worker, 15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, name: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name)
    }
}
