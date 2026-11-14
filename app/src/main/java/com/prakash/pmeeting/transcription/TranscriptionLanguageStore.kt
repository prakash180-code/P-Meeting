package com.prakash.pmeeting.transcription

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's transcription language preference in app-private
 * SharedPreferences so it survives process death and app restarts.
 *
 * The current value is also exposed as a [StateFlow] so UI observing it
 * recomposes as soon as the setting changes.
 */
class TranscriptionLanguageStore(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_LANGUAGE = "transcription_language"

        fun create(context: Context): TranscriptionLanguageStore {
            val prefs = context.getSharedPreferences("pmeeting_prefs", Context.MODE_PRIVATE)
            return TranscriptionLanguageStore(prefs)
        }
    }

    private val _language = MutableStateFlow(read())

    /** Observable current language. */
    val language: StateFlow<TranscriptionLanguage> = _language.asStateFlow()

    /** Returns the currently selected language (defaults to Auto). */
    fun get(): TranscriptionLanguage = _language.value

    /** Persists the selected language. */
    fun set(language: TranscriptionLanguage) {
        prefs.edit { putString(KEY_LANGUAGE, language.name) }
        _language.value = language
    }

    private fun read(): TranscriptionLanguage {
        val name = prefs.getString(KEY_LANGUAGE, null)
        return TranscriptionLanguage.values().firstOrNull { it.name == name }
            ?: TranscriptionLanguage.AUTO
    }
}
