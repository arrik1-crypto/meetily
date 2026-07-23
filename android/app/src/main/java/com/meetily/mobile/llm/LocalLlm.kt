package com.meetily.mobile.llm

import android.content.Context
import com.meetily.mobile.data.AppSettings
import org.json.JSONArray

/**
 * The embedded on-device chat engine. Keeps one model loaded (lazily, per
 * selected key) and serializes generations; [release] drops it on memory
 * pressure or model switch. LlmClient routes here instead of HTTP when the
 * AI engine setting is "local" — no socket is ever opened for local chat.
 */
object LocalLlm {

    /** Fits phone memory and keeps prompt decode times sane. */
    private const val N_CTX = 4096
    private const val MAX_REPLY_TOKENS = 700

    /**
     * Prompt budget in characters (~3.2 chars/token conservative for English
     * with punctuation), leaving room for the reply and template overhead.
     */
    const val CHAR_BUDGET = (N_CTX - MAX_REPLY_TOKENS - 120) * 3

    private var appContext: Context? = null
    private var ptr = 0L
    private var loadedKey: String? = null
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isSelected(): Boolean {
        val context = appContext ?: return false
        return AppSettings(context).llmEngine == "local"
    }

    /**
     * Runs one chat completion on-device. [messages] is the same
     * OpenAI-shaped array LlmClient builds: [{role, content}, …].
     */
    fun chat(messages: JSONArray): String {
        val context = appContext
            ?: throw IllegalStateException("On-device AI is not initialized")
        if (!LocalLlmModels.isRuntimeAvailable()) {
            throw IllegalStateException("On-device AI runtime unavailable on this device")
        }
        val settings = AppSettings(context)
        val model = LocalLlmModels.byKey(settings.localLlmModel)
        if (!LocalLlmModels.isDownloaded(context, model)) {
            throw IllegalStateException(
                "On-device model not downloaded — get ${model.displayName} in Settings"
            )
        }

        val budgeted = budgetMessages(toPairs(messages), CHAR_BUDGET)
        val packed = buildString {
            for ((role, content) in budgeted) {
                append('\u001e').append(role).append('\u001f').append(content)
            }
        }

        synchronized(lock) {
            ensureLoaded(context, model)
            val reply = LlamaBridge.generate(ptr, packed, MAX_REPLY_TOKENS)
                ?.trim()
                .orEmpty()
            if (reply.isBlank()) {
                throw IllegalStateException("On-device model returned an empty response")
            }
            return reply
        }
    }

    /** Frees the loaded model (memory pressure, model switch, shutdown). */
    fun release() {
        synchronized(lock) {
            if (ptr != 0L) {
                LlamaBridge.freeModel(ptr)
                ptr = 0L
                loadedKey = null
            }
        }
    }

    private fun ensureLoaded(context: Context, model: LocalLlmModel) {
        if (ptr != 0L && loadedKey == model.key) return
        if (ptr != 0L) {
            LlamaBridge.freeModel(ptr)
            ptr = 0L
            loadedKey = null
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val loaded = LlamaBridge.initModel(
            LocalLlmModels.fileFor(context, model).absolutePath, N_CTX, threads
        )
        if (loaded == 0L) {
            throw IllegalStateException("Could not load the on-device model")
        }
        ptr = loaded
        loadedKey = model.key
    }

    private fun toPairs(messages: JSONArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until messages.length()) {
            val obj = messages.optJSONObject(i) ?: continue
            val role = obj.optString("role", "user").ifBlank { "user" }
            val content = obj.optString("content", "")
            if (content.isNotBlank()) out.add(role to content)
        }
        return out
    }

    /**
     * Shrinks messages to fit [charBudget]: the longest content loses its
     * middle (keeping 60% head + 40% tail around an omission marker) until
     * everything fits. Pure; unit-tested.
     */
    fun budgetMessages(
        messages: List<Pair<String, String>>,
        charBudget: Int
    ): List<Pair<String, String>> {
        val out = messages.toMutableList()
        var guard = 0
        while (out.sumOf { it.second.length } > charBudget && guard++ < 20) {
            val overshoot = out.sumOf { it.second.length } - charBudget
            val index = out.indices.maxByOrNull { out[it].second.length } ?: break
            val (role, content) = out[index]
            val target = (content.length - overshoot).coerceAtLeast(600)
            if (target >= content.length) break
            val marker = "\n…[middle of this section omitted to fit the on-device model]…\n"
            val keep = (target - marker.length).coerceAtLeast(400)
            val head = (keep * 6) / 10
            val tail = keep - head
            out[index] = role to (
                content.take(head) + marker + content.takeLast(tail)
                )
        }
        return out
    }
}
