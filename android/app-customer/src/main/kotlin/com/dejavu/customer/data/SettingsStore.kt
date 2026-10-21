package com.dejavu.customer.data

import android.content.Context
import androidx.core.content.edit
import com.dejavu.core.network.normalizeBaseUrl
import com.dejavu.customer.BuildConfig

/** Which server the app talks to. Changeable at runtime (e.g. to a Codespaces URL) from Account → Server. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile
    var baseUrl: String = prefs.getString(KEY, null) ?: BuildConfig.API_BASE_URL
        private set

    val defaultBaseUrl: String get() = BuildConfig.API_BASE_URL

    fun setBaseUrl(raw: String) {
        baseUrl = if (raw.isBlank()) BuildConfig.API_BASE_URL else normalizeBaseUrl(raw)
        prefs.edit { putString(KEY, baseUrl) }
    }

    private companion object {
        const val KEY = "api_base_url"
    }
}
