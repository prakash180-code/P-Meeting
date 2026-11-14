package com.prakash.pmeeting.transcription

/** A pretrained whisper.cpp model available for download. */
data class ModelConfig(
    /** Unique identifier for the model. */
    val id: String,
    /** Human-readable display name. */
    val name: String,
    /** Direct download URL (must be a pretrained model from a trustworthy source). */
    val downloadUrl: String,
    /** Approximate download size in bytes (updated from Content-Length at runtime). */
    var approxSizeBytes: Long = 0,
    /** Whether this is the currently selected default model. */
    val isDefault: Boolean = false
)

object WhisperModels {
    /** Multilingual base model (~142 MB). Supports many languages including English. */
    val BASE = ModelConfig(
        id = "base",
        name = "Whisper Base (multilingual)",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        approxSizeBytes = 142_000_000,
        isDefault = true
    )

    /** English-only tiny model (~39 MB). Faster but English only. */
    val TINY_EN = ModelConfig(
        id = "tiny.en",
        name = "Whisper Tiny.en (English)",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
        approxSizeBytes = 39_000_000,
        isDefault = false
    )

    val ALL = listOf(BASE, TINY_EN)

    fun byId(id: String): ModelConfig? = ALL.find { it.id == id }

    fun defaultModel(): ModelConfig = ALL.find { it.isDefault } ?: ALL[0]
}
