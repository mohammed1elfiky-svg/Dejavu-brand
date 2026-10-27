package com.dejavu.customer

import android.content.Context
import com.dejavu.core.network.ApiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * The last home page and category list on this phone, so the app opens at once (also with a
 * slow or no connection) and then refreshes. Pictures are kept by the image loader's cache.
 */
class OfflineCache(context: Context) {
    private val dir = File(context.filesDir, "offline")

    suspend fun <T> read(name: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        runCatching { ApiJson.decodeFromString(serializer, File(dir, name).readText()) }.getOrNull()
    }

    suspend fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(ApiJson.encodeToString(serializer, value))
                tmp.renameTo(File(dir, name))
            }
        }
    }

    /** Prices can depend on who is signed in (merchants): forget them on sign-out. */
    fun clear() {
        runCatching { dir.deleteRecursively() }
    }
}
