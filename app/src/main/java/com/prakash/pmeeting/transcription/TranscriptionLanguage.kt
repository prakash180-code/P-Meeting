package com.prakash.pmeeting.transcription

/**
 * The transcription language used by Whisper.
 *
 * [AUTO] lets Whisper automatically detect the spoken language (passes no
 * explicit language to the native engine). [US_ENGLISH] forces English ("en").
 */
enum class TranscriptionLanguage(
    val label: String,
    /** The language code passed to whisper.cpp, or null for auto-detection. */
    val langCode: String?
) {
    AUTO("Auto", null),
    US_ENGLISH("US English", "en")
}
