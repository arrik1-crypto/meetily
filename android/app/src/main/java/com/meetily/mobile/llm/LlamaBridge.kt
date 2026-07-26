package com.meetily.mobile.llm

/**
 * Thin JNI bridge to llama.cpp. Access only after [load] succeeds — loading
 * can fail on unsupported ABIs. All calls are serialized by [LocalLlm].
 */
object LlamaBridge {

    @Volatile
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("meetily_llama")
            loaded = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun initModel(modelPath: String, nCtx: Int, nThreads: Int): Long

    external fun freeModel(ptr: Long)

    /**
     * Resizes the thread pool between generations. Safe only when no decode
     * is in flight — LocalLlm serialises every call, so the top of generate()
     * is the one place that holds.
     */
    external fun setThreads(ptr: Long, nThreads: Int)

    /**
     * [packedMessages]: per message, 0x1e + role + 0x1f + content. Returns
     * the assistant reply, or null on failure.
     */
    external fun generate(ptr: Long, packedMessages: String, maxTokens: Int): String?
}
