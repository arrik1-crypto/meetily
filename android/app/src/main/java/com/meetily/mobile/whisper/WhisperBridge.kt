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
        translate: Boolean
    ): String?
}
