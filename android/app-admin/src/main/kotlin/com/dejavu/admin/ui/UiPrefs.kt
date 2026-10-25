package com.dejavu.admin.ui

import android.content.Context
import androidx.core.content.edit
import com.dejavu.core.model.Numerals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode(val label: String) { SYSTEM("حسب الجهاز"), LIGHT("فاتح"), DARK("داكن") }

/** Something opened recently, offered again on the home screen. [kind]: product, customer, supplier, order. */
data class RecentItem(val kind: String, val id: String, val title: String, val subtitle: String? = null)

/**
 * This device's look-and-feel choices: theme, text size, digits, the home cards (order and
 * which are hidden), recently opened items and whether the first-run tour was seen.
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString(THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _fontScale = MutableStateFlow(prefs.getFloat(FONT, 1f))

    /** Text size on top of the system one: 0.9, 1, 1.15 or 1.3. */
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    private val _arabicDigits = MutableStateFlow(prefs.getBoolean(DIGITS, false))

    /** Write numbers as ٠١٢٣ instead of 0123. Takes effect when the screen is recreated. */
    val arabicDigits: StateFlow<Boolean> = _arabicDigits.asStateFlow()

    private val _homeCards = MutableStateFlow(readStrings(HOME_ORDER))

    /** Home card ids in the user's order; empty until the user rearranges them. */
    val homeCards: StateFlow<List<String>> = _homeCards.asStateFlow()

    private val _hiddenCards = MutableStateFlow(readStrings(HOME_HIDDEN).toSet())
    val hiddenCards: StateFlow<Set<String>> = _hiddenCards.asStateFlow()

    private val _recents = MutableStateFlow(readRecents())

    /** Newest first, at most [MAX_RECENTS]. */
    val recents: StateFlow<List<RecentItem>> = _recents.asStateFlow()

    private val _tourSeen = MutableStateFlow(prefs.getBoolean(TOUR, false))
    val tourSeen: StateFlow<Boolean> = _tourSeen.asStateFlow()

    init {
        Numerals.arabic = _arabicDigits.value
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit { putString(THEME, mode.name) }
    }

    fun setFontScale(scale: Float) {
        _fontScale.value = scale
        prefs.edit { putFloat(FONT, scale) }
    }

    fun setArabicDigits(on: Boolean) {
        _arabicDigits.value = on
        Numerals.arabic = on
        prefs.edit { putBoolean(DIGITS, on) }
    }

    fun setHomeCards(order: List<String>, hidden: Set<String>) {
        _homeCards.value = order
        _hiddenCards.value = hidden
        prefs.edit {
            putString(HOME_ORDER, JSONArray(order).toString())
            putString(HOME_HIDDEN, JSONArray(hidden.toList()).toString())
        }
    }

    fun addRecent(item: RecentItem) {
        val list = (listOf(item) + _recents.value.filterNot { it.kind == item.kind && it.id == item.id }).take(MAX_RECENTS)
        _recents.value = list
        val json = JSONArray()
        list.forEach { json.put(JSONObject().put("kind", it.kind).put("id", it.id).put("title", it.title).put("subtitle", it.subtitle ?: "")) }
        prefs.edit { putString(RECENTS, json.toString()) }
    }

    fun clearRecents() {
        _recents.value = emptyList()
        prefs.edit { remove(RECENTS) }
    }

    fun setTourSeen(seen: Boolean) {
        _tourSeen.value = seen
        prefs.edit { putBoolean(TOUR, seen) }
    }

    private fun readStrings(key: String): List<String> = runCatching {
        val a = JSONArray(prefs.getString(key, null) ?: return emptyList())
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun readRecents(): List<RecentItem> = runCatching {
        val a = JSONArray(prefs.getString(RECENTS, null) ?: return emptyList())
        List(a.length()) {
            val o = a.getJSONObject(it)
            RecentItem(o.getString("kind"), o.getString("id"), o.getString("title"), o.optString("subtitle").takeIf { s -> s.isNotBlank() })
        }
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_RECENTS = 12
        private const val THEME = "theme"
        private const val FONT = "font_scale"
        private const val DIGITS = "arabic_digits"
        private const val HOME_ORDER = "home_cards"
        private const val HOME_HIDDEN = "home_hidden"
        private const val RECENTS = "recents"
        private const val TOUR = "tour_seen"
    }
}
