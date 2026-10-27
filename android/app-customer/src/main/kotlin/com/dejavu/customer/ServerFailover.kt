package com.dejavu.customer

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.dejavu.core.model.ServerList
import com.dejavu.core.network.ApiJson
import com.dejavu.core.network.normalizeBaseUrl
import com.dejavu.core.ui.storage.SettingsStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the app on a server that answers. The addresses to try, in order: the newest list
 * the server gave out, then the ones built into the app. When none answers, the list is read
 * from the "directory" file on GitHub, which the owner updates from the admin app; so a
 * blocked address can be replaced without a new app version.
 */
class ServerFailover(
    context: Context,
    private val settings: SettingsStore,
    builtIn: List<String>,
    private val directoryUrl: String,
) {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
    private val builtIn = builtIn.mapNotNull(::clean).distinct()
    private val lock = Any()

    @Volatile
    private var lastFailedSearch = 0L

    private val probe = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** All addresses the app may use, in the order to try them. */
    fun known(): List<String> = (cached() + builtIn).distinct()

    /** Keeps [servers] (from the server or the directory) for the next time. */
    fun remember(servers: List<String>) {
        val list = servers.mapNotNull(::clean).distinct().take(MAX)
        if (list.isNotEmpty()) prefs.edit { putString(KEY, list.joinToString("\n")) }
    }

    /** The known address [url] was sent to, if any. */
    fun baseOf(url: HttpUrl): String? {
        val s = url.toString()
        return (listOf(normalizeBaseUrl(settings.baseUrl)) + known()).firstOrNull { s.startsWith(it) }
    }

    /**
     * After a request to [failedBase] could not reach it: the address to use now, or null to
     * give up. Blocking (network), so only from OkHttp's threads.
     */
    fun recover(failedBase: String): String? {
        synchronized(lock) {
            return search(failedBase)
        }
    }

    private fun search(failedBase: String): String? {
        val current = normalizeBaseUrl(settings.baseUrl)
        // Another request already moved on.
        if (current != failedBase) return current
        // A server set by hand in a test build is left alone.
        if (current !in known()) return null
        if (SystemClock.elapsedRealtime() - lastFailedSearch < RETRY_AFTER_MS) return null
        if (healthy(current)) return null
        known().firstOrNull { it != current && healthy(it) }?.let { return switchTo(it) }
        readDirectory()?.let { fresh ->
            remember(fresh)
            fresh.mapNotNull(::clean).firstOrNull { it != current && healthy(it) }?.let { return switchTo(it) }
        }
        lastFailedSearch = SystemClock.elapsedRealtime()
        return null
    }

    private fun switchTo(url: String): String {
        settings.setBaseUrl(url)
        lastFailedSearch = 0L
        return url
    }

    private fun healthy(base: String): Boolean = runCatching {
        probe.newCall(Request.Builder().url(base + "health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun readDirectory(): List<String>? {
        if (directoryUrl.isBlank()) return null
        return runCatching {
            probe.newCall(Request.Builder().url(directoryUrl).header("Cache-Control", "no-cache").build()).execute().use { res ->
                if (res.isSuccessful) ApiJson.decodeFromString(ServerList.serializer(), res.body?.string().orEmpty()).servers else null
            }
        }.getOrNull()
    }

    private fun cached(): List<String> = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun clean(raw: String): String? = runCatching { normalizeBaseUrl(raw) }.getOrNull()?.takeIf { it.startsWith("https://") }

    private companion object {
        const val KEY = "list"
        const val MAX = 5

        /** After every address failed, wait this long before searching again. */
        const val RETRY_AFTER_MS = 30_000L
    }
}

/** Moves a request whose server cannot be reached to one that answers, and sends it again. */
class FailoverInterceptor(private val failover: ServerFailover) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            return chain.proceed(request)
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            val base = failover.baseOf(request.url) ?: throw e
            val next = failover.recover(base)?.takeIf { it != base } ?: throw e
            return chain.proceed(request.newBuilder().url(moveTo(request.url, base, next)).build())
        }
    }

    companion object {
        fun moveTo(url: HttpUrl, from: String, to: String): HttpUrl {
            val target = to.toHttpUrl()
            val rest = url.encodedPath.removePrefix(from.toHttpUrl().encodedPath)
            return url.newBuilder().scheme(target.scheme).host(target.host).port(target.port).encodedPath(target.encodedPath + rest).build()
        }
    }
}

/**
 * Pictures from the store's own server carry the address they were uploaded through; send
 * them to the server in use now, so they still load after a switch.
 */
class CurrentServerImages(private val settings: SettingsStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val path = url.encodedPath
        val at = path.indexOf("/api/v1/files/")
        if (at < 0) return chain.proceed(request)
        val base = normalizeBaseUrl(settings.baseUrl).toHttpUrl()
        if (url.host == base.host && url.port == base.port) return chain.proceed(request)
        val moved = url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(base.encodedPath.removeSuffix("/api/v1/") + path.substring(at))
            .build()
        return chain.proceed(request.newBuilder().url(moved).build())
    }
}
