package com.prakash.pmeeting.ui.theme

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted light/dark override for the app theme.
 *
 * `null` means "no explicit choice yet — follow the system setting".
 */
class ThemePreference(context: Context) {

    private companion object {
        const val PREFS_NAME = "pmeeting_ui"
        const val KEY_DARK_THEME = "dark_theme"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _darkTheme = MutableStateFlow(
        if (prefs.contains(KEY_DARK_THEME)) prefs.getBoolean(KEY_DARK_THEME, false) else null
    )

    /** Observable override; `null` = follow the system. */
    val darkTheme: StateFlow<Boolean?> = _darkTheme.asStateFlow()

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DARK_THEME, enabled) }
        _darkTheme.value = enabled
    }
}
