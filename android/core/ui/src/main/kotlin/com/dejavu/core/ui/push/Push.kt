package com.dejavu.core.ui.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.tasks.await
import java.net.HttpURLConnection
import java.net.URL

/**
 * Firebase project values for one app, injected at build time
 * (`-Pdejavu.firebase.*`). No google-services.json or Gradle plugin is needed,
 * and an empty config simply turns push off.
 */
data class PushConfig(val appId: String, val apiKey: String, val projectId: String, val senderId: String) {
    val isComplete: Boolean get() = listOf(appId, apiKey, projectId, senderId).all { it.isNotBlank() }
}

object Push {
    private const val TAG = "DejavuPush"

    /** Starts Firebase for this process. Returns whether push is available. */
    fun init(context: Context, config: PushConfig): Boolean {
        if (!config.isComplete) return false
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(config.appId)
                    .setApiKey(config.apiKey)
                    .setProjectId(config.projectId)
                    .setGcmSenderId(config.senderId)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed", e)
            false
        }
    }

    /** This install's FCM registration token, or null if unavailable (no Play services, offline…). */
    suspend fun token(): String? = try {
        FirebaseMessaging.getInstance().token.await()
    } catch (e: Exception) {
        Log.w(TAG, "No FCM token", e)
        null
    }

    fun ensureChannel(context: Context, id: String, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Shows a notification while the app is in the foreground (FCM shows background ones itself).
     * With an [image] it expands to the big picture (offers, new products).
     */
    fun show(
        context: Context,
        channel: String,
        smallIcon: Int,
        title: String,
        body: String,
        tap: PendingIntent,
        image: Bitmap? = null,
    ) {
        if (!canNotify(context)) return
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
        if (image != null) {
            builder.setLargeIcon(image).setStyle(NotificationCompat.BigPictureStyle().bigPicture(image))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        val n = builder
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked", e)
        }
    }

    /**
     * Downloads a notification photo (https only). Blocking: call it off the main thread,
     * e.g. from onMessageReceived. Any failure yields null and the notification shows without it.
     */
    fun loadImage(url: String?): Bitmap? {
        if (url.isNullOrBlank() || !url.startsWith("https://", ignoreCase = true)) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Notification image failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/** Receives FCM events and hands them to the app. Each app declares a subclass in its manifest. */
abstract class DejavuMessagingService : FirebaseMessagingService() {
    /** A new registration token: register it with the backend for the signed-in user. */
    abstract fun onToken(token: String)

    /** A message arrived while the app is running. */
    abstract fun onPush(title: String, body: String, data: Map<String, String>)

    final override fun onNewToken(token: String) = onToken(token)

    final override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        onPush(title, body, message.data)
    }
}
