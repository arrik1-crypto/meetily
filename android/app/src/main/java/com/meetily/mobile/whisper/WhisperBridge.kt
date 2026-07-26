package com.meetily.mobile.whisper

/**
 * Thin JNI bridge to whisper.cpp. Access only through [WhisperModels.isRuntimeAvailable]
 * guards — loading can fail on unsupported ABIs.
 */
object WhisperBridge {

    @Volatile
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("meetily_whisper")
            loaded = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun transcribe(
        ptr: Long,
        samples: FloatArray,
        language: String?,
        nThreads: Int,
        translate: Boolean,
        /** Custom-vocabulary glossary fed as whisper's initial prompt. */
        prompt: String?
    ): String?

    /**
     * Transcription with word-level timings. Wire format from the JNI side:
     * per word, 0x1e + start-ms + 0x1f + text. [parseWords] decodes it.
     */
    external fun transcribeWords(
        ptr: Long,
        samples: FloatArray,
        language: String?,
        nThreads: Int,
        translate: Boolean,
        prompt: String?
    ): String?

    /**
     * The language whisper settled on during the most recent call, or null.
     *
     * Passing "auto" costs a complete extra encoder pass every call — whisper
     * encodes the window once just to read a language logit, then again to do
     * the work. Detect once, then pass the answer for the rest of the file.
     */
    external fun lastLanguage(ptr: Long): String?

    /** Decodes [transcribeWords] output into (full text, word timings). */
    fun parseWords(
        raw: String?
    ): Pair<String, List<com.meetily.mobile.data.WordStamp>> {
        if (raw.isNullOrEmpty()) return "" to emptyList()
        val words = raw.split('\u001e').mapNotNull { entry ->
            if (entry.isEmpty()) return@mapNotNull null
            val sep = entry.indexOf('\u001f')
            if (sep <= 0) return@mapNotNull null
            val ms = entry.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
            val text = entry.substring(sep + 1).trim()
            if (text.isEmpty()) null else com.meetily.mobile.data.WordStamp(ms, text)
        }
        return words.joinToString(" ") { it.text } to words
    }
}
