package com.nicholas.onote.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * Persisted user preferences. Holds Compose state so reading them in
 * composition triggers recomposition; changes written through the set* helpers
 * are persisted to SharedPreferences immediately.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var themeMode: ThemeMode by mutableStateOf(loadThemeMode())
    var palmRejection: Boolean by mutableStateOf(prefs.getBoolean(KEY_PALM, true))
    var hudEnabled: Boolean by mutableStateOf(prefs.getBoolean(KEY_HUD, true))

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun updatePalmRejection(enabled: Boolean) {
        palmRejection = enabled
        prefs.edit().putBoolean(KEY_PALM, enabled).apply()
    }

    fun updateHudEnabled(enabled: Boolean) {
        hudEnabled = enabled
        prefs.edit().putBoolean(KEY_HUD, enabled).apply()
    }

    private fun loadThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    companion object {
        private const val PREFS_FILE = "onote_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_PALM = "palm_rejection"
        private const val KEY_HUD = "hud_enabled"
    }
}
