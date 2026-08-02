package com.meetily.mobile.llm

import com.meetily.mobile.data.AppSettings

/**
 * Which engine serves a chat call, as a pure function of the two settings.
 *
 * Extracted so it can be tested. The routing decision otherwise lives inside
 * two SharedPreferences-backed `isSelected()` calls, which a JVM unit test
 * cannot reach — and a second runtime that is wired in but never selected,
 * or one that captures the endpoint path, would leave the entire suite
 * green. This is the one place where "who answers" is decided.
 */
object EngineRouting {

    enum class Target {
        /** llama.cpp, in this process. */
        LLAMA,

        /** LiteRT-LM, in the :litert sandbox process. */
        LITERT,

        /** An OpenAI-compatible URL over the network. */
        ENDPOINT
    }

    /**
     * [engine] is `AppSettings.llmEngine`, [runtime] is
     * `AppSettings.localLlmRuntime`.
     *
     * Unknown values route to the safest thing that still works rather than
     * throwing: a stored key from a newer build must never strand an
     * install, which is the rule TranscriptionModels and NemoModel.legacy
     * already follow.
     */
    fun resolve(engine: String, runtime: String): Target = when {
        engine != LOCAL -> Target.ENDPOINT
        runtime == AppSettings.RUNTIME_LITERT -> Target.LITERT
        else -> Target.LLAMA
    }

    /** True when nothing is sent off the device, whichever runtime serves. */
    fun staysOnDevice(engine: String): Boolean = engine == LOCAL

    private const val LOCAL = "local"
}
