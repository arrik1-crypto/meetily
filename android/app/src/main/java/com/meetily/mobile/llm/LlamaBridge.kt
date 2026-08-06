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

    /**
     * Tokens [packedMessages] costs once the chat template is applied — the
     * same number [generate] will see. Negative if it could not be counted.
     *
     * The budget was a chars-per-token guess before this existed, and an
     * optimistic guess overflows the context window where nothing can
     * report it. Throws UnsatisfiedLinkError against an older native
     * library that lacks the export; callers fall back to the estimate.
     */
    external fun countTokens(ptr: Long, packedMessages: String): Int

    /**
     * Why the last [generate] or [countTokens] failed, or "" if it did not.
     *
     * [generate] returns null from six native paths, and a model that ends
     * its turn without writing returns an empty string. Without this they
     * are one indistinguishable blank, and the message shown to the user
     * named a cause — the model — that often was not involved.
     *
     * Only meaningful straight after a null or blank return. Throws
     * UnsatisfiedLinkError on an older native library; treat that as "no
     * detail available" rather than as a failure of its own.
     */
    external fun lastError(): String
}
