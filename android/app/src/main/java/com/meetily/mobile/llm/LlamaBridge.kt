package com.meetily.mobile.llm

/**
 * Thin JNI bridge to llama.cpp. Access only after [load] succeeds — loading
 * can fail on unsupported ABIs. All calls are serialized by [LocalLlm].
 */
object LlamaBridge {

    /** Loads the native library, which also holds whisper.cpp; see NativeEngines. */
    fun load(): Boolean = com.meetily.mobile.data.NativeEngines.load()

    /**
     * Loads [modelPath] with a context of [nCtx] tokens, clamped to the
     * window the model was trained on; [contextSize] says what it got.
     *
     * [fullSwaCache] false gives a sliding-window model (Gemma) the small
     * cache for its sliding-window layers, sized to the window plus one
     * batch instead of [nCtx]. Those layers then hold a fraction of the
     * memory and answer the same, but prompt reuse between calls works only
     * while that cache still holds what the next prompt needs (see
     * generateBytes in llama_jni.c). Ignored by models without
     * sliding-window attention.
     */
    external fun initModel(modelPath: String, nCtx: Int, nThreads: Int, fullSwaCache: Boolean): Long

    /**
     * The context window, in tokens, [initModel] actually created: the
     * request after the clamp to the trained window and llama.cpp's own
     * rounding up to a multiple of 256. 0 for a null handle.
     */
    external fun contextSize(ptr: Long): Int

    /**
     * The context window the loaded model was trained on (llama.cpp's
     * n_ctx_train), or 0 when its file does not say or the handle is null.
     * The clamp [initModel] applies to it is DeviceFit.clampToTrainedContext.
     */
    external fun trainedContext(ptr: Long): Int

    external fun freeModel(ptr: Long)

    /**
     * Resizes the thread pool between generations. Safe only when no decode
     * is in flight — LocalLlm serialises every call, so the top of generate()
     * is the one place that holds.
     */
    external fun setThreads(ptr: Long, nThreads: Int)

    /**
     * [packedMessages]: per message, 0x1e + role + 0x1f + content, as
     * [PromptPacking.pack] builds it. Returns the assistant reply, or null
     * on failure.
     *
     * Consecutive calls on the same model reuse the KV cache for the longest
     * token prefix the prompts share, so a follow-up that repeats a large
     * system message only pays for what changed.
     */
    fun generate(ptr: Long, packedMessages: String, maxTokens: Int): String? =
        generateBytes(ptr, packedMessages, maxTokens)?.let { String(it, Charsets.UTF_8) }

    // Raw bytes rather than a jstring: NewStringUTF needs modified UTF-8,
    // which a model's emoji (4-byte UTF-8) never is.
    private external fun generateBytes(ptr: Long, packedMessages: String, maxTokens: Int): ByteArray?

    /**
     * Stops a [generate] that is already running, from another thread.
     *
     * [generate] does not return until the whole reply is written, so this
     * is the only way to end one early. It returns null with
     * [lastError] set once the flag is seen — within one token.
     *
     * The flag is sticky: it is not cleared by the generation it stops, so
     * clear it before starting the next one or that one dies at its first
     * decode.
     */
    external fun setAbort(on: Boolean)

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
