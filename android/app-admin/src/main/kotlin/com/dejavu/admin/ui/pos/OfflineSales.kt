package com.dejavu.admin.ui.pos

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dejavu.admin.AdminApp
import com.dejavu.core.admin.PosProduct
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.PosSaleRequest
import com.dejavu.core.model.Currencies
import com.dejavu.core.network.ApiException
import com.dejavu.core.network.ApiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/** A till sale rung up without a connection, waiting to be sent; [error] = the server refused it last time. */
data class QueuedSale(val request: PosSaleRequest, val error: String? = null) {
    val clientRef: String get() = request.clientRef.orEmpty()
}

/**
 * Sales made while the till had no internet, kept as JSON in the app's files until they
 * reach the server (each carries its clientRef, so sending one twice books it once).
 * Also keeps the last product list and currencies so the grid still works offline.
 */
class OfflineSales private constructor(private val context: Context) {
    private val dir = File(context.filesDir, "pos-offline").apply { mkdirs() }
    private val queueFile = File(dir, "queue.json")
    private val productsFile = File(dir, "products.json")
    private val currenciesFile = File(dir, "currencies.json")
    private val lock = Mutex()

    private val _queue = MutableStateFlow(readQueue())

    /** Sales waiting to be sent, oldest first. */
    val queue: StateFlow<List<QueuedSale>> = _queue.asStateFlow()

    data class SyncResult(val sent: Int, val failed: Int, val offline: Boolean, val firstError: String?)

    suspend fun add(request: PosSaleRequest) = lock.withLock {
        val next = _queue.value.filter { it.clientRef != request.clientRef } + QueuedSale(request)
        write(next)
        schedule(context)
    }

    suspend fun remove(clientRef: String) = lock.withLock {
        write(_queue.value.filter { it.clientRef != clientRef })
    }

    /**
     * Sends the queued sales in order. Stops at the first connection failure; a sale the server
     * refuses keeps its error and is skipped (unless [includeFailed], e.g. "send now" by hand).
     */
    suspend fun sync(pos: PosRepository, includeFailed: Boolean = true): SyncResult = lock.withLock {
        var sent = 0
        var failed = 0
        var offline = false
        var firstError: String? = null
        for (item in _queue.value.toList()) {
            if (item.error != null && !includeFailed) continue
            val result = pos.sell(item.request)
            val error = result.exceptionOrNull()
            when {
                error == null -> {
                    sent++
                    write(_queue.value.filter { it.clientRef != item.clientRef })
                }
                isTransient(error) -> {
                    offline = true
                    break
                }
                else -> {
                    failed++
                    val message = error.message ?: "تعذّر إرسال الفاتورة"
                    if (firstError == null) firstError = message
                    write(_queue.value.map { if (it.clientRef == item.clientRef) it.copy(error = message) else it })
                }
            }
        }
        SyncResult(sent, failed, offline, firstError)
    }

    // ---- cache for the grid

    suspend fun saveProducts(list: List<PosProduct>) = withContext(Dispatchers.IO) {
        runCatching { productsFile.writeText(ApiJson.encodeToString(ListSerializer(PosProduct.serializer()), list)) }
        Unit
    }

    suspend fun products(): List<PosProduct>? = withContext(Dispatchers.IO) {
        runCatching { ApiJson.decodeFromString(ListSerializer(PosProduct.serializer()), productsFile.readText()) }.getOrNull()
    }

    suspend fun saveCurrencies(value: Currencies) = withContext(Dispatchers.IO) {
        runCatching { currenciesFile.writeText(ApiJson.encodeToString(Currencies.serializer(), value)) }
        Unit
    }

    suspend fun currencies(): Currencies? = withContext(Dispatchers.IO) {
        runCatching { ApiJson.decodeFromString(Currencies.serializer(), currenciesFile.readText()) }.getOrNull()
    }

    // ---- storage

    private fun readQueue(): List<QueuedSale> = runCatching {
        if (!queueFile.exists()) return@runCatching emptyList<QueuedSale>()
        (ApiJson.parseToJsonElement(queueFile.readText()) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val request = o["request"]?.let { ApiJson.decodeFromJsonElement(PosSaleRequest.serializer(), it) } ?: return@mapNotNull null
            QueuedSale(request, (o["error"] as? JsonPrimitive)?.contentOrNull)
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<QueuedSale>) {
        _queue.value = list
        runCatching {
            if (list.isEmpty()) {
                queueFile.delete()
            } else {
                val json = JsonArray(
                    list.map { q ->
                        buildJsonObject {
                            put("request", ApiJson.encodeToJsonElement(PosSaleRequest.serializer(), q.request))
                            q.error?.let { put("error", JsonPrimitive(it)) }
                        }
                    },
                )
                val tmp = File(dir, "queue.json.tmp")
                tmp.writeText(json.toString())
                if (!tmp.renameTo(queueFile)) {
                    queueFile.writeText(json.toString())
                    tmp.delete()
                }
            }
        }
    }

    companion object {
        private const val WORK = "pos-offline-sales"

        @Volatile
        private var instance: OfflineSales? = null

        fun get(context: Context): OfflineSales =
            instance ?: synchronized(this) { instance ?: OfflineSales(context.applicationContext).also { instance = it } }

        /** True when [error] means the server could not be reached (no internet, timeout…). */
        fun isOffline(error: Throwable): Boolean =
            (error as? ApiException)?.isNetwork == true || error is java.io.IOException

        /** Worth trying again later as is: offline, signed out, server down. A 4xx refusal is not. */
        fun isTransient(error: Throwable): Boolean {
            if (isOffline(error)) return true
            val status = (error as? ApiException)?.status ?: return false
            return status == 0 || status == 401 || status == 408 || status == 429 || status >= 500
        }

        /** Sends the queue in the background as soon as the device is online. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineSalesWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        /** On app start: schedules a send when sales are waiting. */
        fun scheduleIfPending(context: Context) {
            if (get(context).queue.value.isNotEmpty()) schedule(context)
        }
    }
}

/** Sends the offline queue once the network is back (retried with backoff while it fails). */
class OfflineSalesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AdminApp).container
        if (!container.hasSession) return Result.retry()
        val store = OfflineSales.get(applicationContext)
        if (store.queue.value.none { it.error == null }) return Result.success()
        val result = store.sync(container.pos, includeFailed = false)
        return if (result.offline || store.queue.value.any { it.error == null }) Result.retry() else Result.success()
    }
}
