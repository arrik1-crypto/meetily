package com.meetily.mobile.llm.litert

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import java.io.File

/**
 * Direct binding to LiteRT-LM. Runs only inside the sandbox process.
 *
 * The spike bound this by reflection because the published API was unknown
 * and each wrong guess cost a CI round trip. That was a discovery device and
 * has no place here: the signatures below came out of `javap` on the
 * resolved AAR, and a compile error is now the correct way to learn that
 * they changed.
 */
internal class LiteRtBinding {

    private var engine: Engine? = null
    private var session: Session? = null
    private var loadedPath: String? = null
    private var loadedBackend: String? = null

    /** Context window the app plans prompts against. */
    val contextTokens: Int get() = MAX_TOKENS

    /**
     * Loads [modelPath] on [requested], falling back down the ladder, and
     * returns the backend that actually took it.
     *
     * The fallback is the point. The spike found that what a runtime selects
     * is not always what it was asked for, and a silent substitution would
     * leave the user believing the accelerator ran when it did not — which
     * is exactly the number they are trying to measure.
     */
    fun load(modelPath: String, requested: String, threadBudget: Int): String {
        if (engine != null && loadedPath == modelPath && loadedBackend == requested) {
            return loadedBackend ?: BACKEND_CPU
        }
        release()

        val file = File(modelPath)
        if (!file.isFile) {
            throw IllegalStateException("The LiteRT model file is missing. Import it again.")
        }

        var lastError: Throwable? = null
        for (candidate in ladder(requested)) {
            try {
                val built = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backendFor(candidate, threadBudget),
                        maxNumTokens = MAX_TOKENS,
                        cacheDir = file.parentFile?.absolutePath
                    )
                )
                built.initialize()
                engine = built
                session = built.createSession(SessionConfig())
                loadedPath = modelPath
                loadedBackend = candidate
                return candidate
            } catch (t: Throwable) {
                // Throwable: a backend that is absent surfaces as
                // UnsatisfiedLinkError or NoClassDefFoundError, not an
                // Exception, and those are exactly the ones worth falling
                // back from rather than dying on.
                Log.w(TAG, "LiteRT backend $candidate unavailable", t)
                lastError = t
                runCatching { engine?.close() }
                engine = null
                session = null
            }
        }
        throw IllegalStateException(
            "LiteRT could not start on this device: " +
                (lastError?.message ?: lastError?.javaClass?.simpleName ?: "unknown"),
        )
    }

    /**
     * Requested backend first, then progressively safer ones.
     *
     * GPU is never a fallback, only an explicit choice.
     * ACCELERATION_FINDINGS.md records that this device's PowerVR
     * DXT-48-1536 returns numerically incorrect k-quant matmuls under
     * ggml-vulkan and all-zero tensors under ExecuTorch — two engines, two
     * silent wrong-answer bugs, one part. Falling back INTO that would trade
     * a visible failure for confidently wrong meeting summaries, which is
     * the one failure mode this app cannot detect or apologise for.
     */
    private fun ladder(requested: String): List<String> = when (requested) {
        BACKEND_GPU -> listOf(BACKEND_GPU, BACKEND_CPU)
        BACKEND_TENSOR -> listOf(BACKEND_TENSOR, BACKEND_CPU)
        BACKEND_CPU -> listOf(BACKEND_CPU)
        else -> listOf(BACKEND_TENSOR, BACKEND_CPU) // "auto"
    }

    private fun backendFor(id: String, threadBudget: Int): Backend = when (id) {
        BACKEND_GPU -> Backend.GPU()
        BACKEND_TENSOR -> Backend.GOOGLE_TENSOR()
        // The count must come from the app's shared budget, not from
        // availableProcessors(): three engines each sizing themselves that
        // way is what HeavyWork was written to stop.
        else -> Backend.CPU(threadBudget, threadBudget)
    }

    fun generate(messages: List<Pair<String, String>>, maxReplyTokens: Int): String {
        val live = session ?: throw IllegalStateException("LiteRT session is not open")
        val prompt = flatten(messages)
        return live.generateContent(listOf(InputData.Text(prompt))).orEmpty()
    }

    /**
     * Flattens roles into one prompt.
     *
     * Deliberately not LocalLlm's packed 0x1E/0x1F wire format — that is a
     * private contract with llama_jni.c, carries a silent 32-message ceiling,
     * and corrupts if content ever contains those control bytes.
     */
    private fun flatten(messages: List<Pair<String, String>>): String = buildString {
        for ((role, content) in messages) {
            when (role) {
                "system" -> append(content).append("\n\n")
                "assistant" -> append("Assistant: ").append(content).append("\n\n")
                else -> append(content).append("\n\n")
            }
        }
    }.trim()

    fun release() {
        runCatching { session?.close() }
        runCatching { engine?.close() }
        session = null
        engine = null
        loadedPath = null
        loadedBackend = null
    }

    companion object {
        private const val TAG = "LiteRtBinding"

        /**
         * Larger than llama.cpp's 4096 because this runtime is not carrying
         * the same memory budget on the CPU path, but still modest: a bigger
         * window costs prefill, and prefill is what the map-reduce
         * summarizer is bound by.
         */
        private const val MAX_TOKENS = 4096

        const val BACKEND_CPU = "cpu"
        const val BACKEND_GPU = "gpu"
        const val BACKEND_TENSOR = "tensor"
    }
}
