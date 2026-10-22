package com.dejavu.core.ui.storage

import android.content.Context
import androidx.core.content.edit
import com.dejavu.core.model.Currency
import com.dejavu.core.network.normalizeBaseUrl

/** Which server the app talks to. Changeable at runtime (e.g. to a Codespaces URL) from Account → Server. */
class SettingsStore(context: Context, val defaultBaseUrl: String) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile
    var baseUrl: String = prefs.getString(KEY, null) ?: defaultBaseUrl
        private set

    fun setBaseUrl(raw: String) {
        baseUrl = if (raw.isBlank()) defaultBaseUrl else normalizeBaseUrl(raw)
        prefs.edit { putString(KEY, baseUrl) }
    }

    /** Currency the shopper sees prices in. */
    var currency: Currency
        get() = prefs.getString(CURRENCY, null)?.takeIf { it.isNotBlank() }?.let { Currency.of(it) } ?: Currency.SAR
        set(value) = prefs.edit { putString(CURRENCY, value.code) }

    private companion object {
        const val KEY = "api_base_url"
        const val CURRENCY = "display_currency"
    }
}
