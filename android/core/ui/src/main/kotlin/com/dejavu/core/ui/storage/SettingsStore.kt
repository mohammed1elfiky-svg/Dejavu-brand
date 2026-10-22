package com.dejavu.core.ui.storage

import android.content.Context
import androidx.core.content.edit
import com.dejavu.core.model.Currency
import com.dejavu.core.network.normalizeBaseUrl

/**
 * Which server the app talks to: the built-in cloud server unless changed at runtime
 * (e.g. to a Codespaces URL) from the Server screen.
 */
class SettingsStore(context: Context, val defaultBaseUrl: String) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile
    var baseUrl: String = prefs.getString(KEY, null)?.takeUnless(::isDevServer) ?: defaultBaseUrl
        private set

    fun setBaseUrl(raw: String) {
        baseUrl = if (raw.isBlank()) defaultBaseUrl else normalizeBaseUrl(raw)
        prefs.edit { putString(KEY, baseUrl) }
    }

    /** Currency the shopper sees prices in. */
    var currency: Currency
        get() = prefs.getString(CURRENCY, null)?.takeIf { it.isNotBlank() }?.let { Currency.of(it) } ?: Currency.SAR
        set(value) = prefs.edit { putString(CURRENCY, value.code) }

    /** Emulator/local addresses saved by older builds; they never work on a real phone. */
    private fun isDevServer(url: String) = listOf("10.0.2.2", "localhost", "127.0.0.1").any { "//$it" in url }

    private companion object {
        const val KEY = "api_base_url"
        const val CURRENCY = "display_currency"
    }
}
