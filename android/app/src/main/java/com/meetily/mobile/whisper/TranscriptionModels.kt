package com.meetily.mobile.whisper

import android.content.Context

/**
 * Router over the two transcription-model families: whisper.cpp ggml files
 * and sherpa-onnx NeMo models (Parakeet/Nemotron). A model key belongs to
 * exactly one family; whisper remains the fallback for unknown keys, which
 * also keeps old persisted settings valid.
 */
object TranscriptionModels {

    fun isNemo(key: String): Boolean = NemoModels.byKeyOrNull(key) != null

    fun displayName(key: String): String =
        NemoModels.byKeyOrNull(key)?.displayName
            ?: WhisperModels.byKey(key).displayName

    fun sizeMb(key: String): Int =
        NemoModels.byKeyOrNull(key)?.totalMb ?: WhisperModels.byKey(key).sizeMb

    fun englishOnly(key: String): Boolean =
        NemoModels.byKeyOrNull(key)?.englishOnly
            ?: WhisperModels.byKey(key).englishOnly

    fun isDownloaded(context: Context, key: String): Boolean {
        val nemo = NemoModels.byKeyOrNull(key)
        return if (nemo != null) {
            NemoModels.isDownloaded(context, nemo)
        } else {
            WhisperModels.isDownloaded(context, WhisperModels.byKey(key))
        }
    }

    /** Runtime check for the family the key belongs to. */
    fun isRuntimeAvailable(key: String): Boolean =
        if (isNemo(key)) {
            NemoModels.isRuntimeAvailable()
        } else {
            WhisperModels.isRuntimeAvailable()
        }

    /** Ready to transcribe with [key] right now. */
    fun isReady(context: Context, key: String): Boolean =
        isRuntimeAvailable(key) && isDownloaded(context, key)

    /** All model keys for pickers, whisper family first. */
    fun allKeys(): List<String> =
        WhisperModels.ALL.map { it.key } + NemoModels.ALL.map { it.key }

    /** Keys of every model that is downloaded and runnable. */
    fun downloadedKeys(context: Context): List<String> =
        allKeys().filter { isReady(context, it) }
}
