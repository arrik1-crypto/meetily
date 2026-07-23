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

    /** Above this, middle-trimming loses too much: condense per-section instead. */
    const val MAP_REDUCE_THRESHOLD = CHAR_BUDGET * 3 / 2

    private const val MAP_CHUNK_CHARS = 8_000
    private const val MAP_MAX_CHUNKS = 12
    private const val MAP_REPLY_TOKENS = 220
    private const val MAP_PROMPT =
        "You are condensing one section of a longer meeting transcript. " +
            "Write compact notes (up to 8 short bullets) capturing decisions, " +
            "action items with their owners, key facts and numbers, and what " +
            "was discussed. No preamble, no commentary."

    /** (sectionsDone, sectionsTotal) during a map-reduce condense pass. */
    @Volatile
    var stageListener: ((Int, Int) -> Unit)? = null

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
    fun chat(messages: JSONArray, allowMapReduce: Boolean = true): String {
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

        var pairs = toPairs(messages)

        synchronized(lock) {
            ensureLoaded(context, model)

            // Map-reduce: when one message (in practice, the transcript) far
            // exceeds the context, condense it section-by-section with the
            // same model, then answer over the ordered notes — full coverage
            // instead of a missing middle.
            val longest = pairs.indices.maxByOrNull { pairs[it].second.length }
            if (allowMapReduce && longest != null &&
                needsMapReduce(pairs[longest].second.length)
            ) {
                condense(pairs[longest].second)?.let { condensed ->
                    pairs = pairs.toMutableList().also {
                        it[longest] = it[longest].first to condensed
                    }
                }
            }

            val budgeted = budgetMessages(pairs, CHAR_BUDGET)
            val reply = LlamaBridge.generate(ptr, pack(budgeted), MAX_REPLY_TOKENS)
                ?.trim()
                .orEmpty()
            if (reply.isBlank()) {
                throw IllegalStateException("On-device model returned an empty response")
            }
            return reply
        }
    }

    fun needsMapReduce(length: Int): Boolean = length > MAP_REDUCE_THRESHOLD

    /**
     * Condenses [content] chunk-by-chunk into ordered section notes. Returns
     * null when there's nothing to gain (single chunk) or every section pass
     * failed — the caller then falls back to head+tail trimming. Must be
     * called with [lock] held and the model loaded.
     */
    private fun condense(content: String): String? {
        val chunks = splitIntoChunks(content, MAP_CHUNK_CHARS, MAP_MAX_CHUNKS)
        if (chunks.size < 2) return null
        val notes = StringBuilder(
            "[Ordered notes condensed from the full transcript of a long meeting]\n"
        )
        var produced = 0
        for ((index, chunk) in chunks.withIndex()) {
            stageListener?.invoke(index + 1, chunks.size)
            // The chunk cap can force chunks past the budget; trim those.
            val body = if (chunk.length > CHAR_BUDGET - 600) {
                budgetMessages(listOf("user" to chunk), CHAR_BUDGET - 600)[0].second
            } else {
                chunk
            }
            val part = try {
                LlamaBridge.generate(
                    ptr,
                    pack(listOf("system" to MAP_PROMPT, "user" to body)),
                    MAP_REPLY_TOKENS
                )?.trim().orEmpty()
            } catch (_: Throwable) {
                ""
            }
            notes.append("\n--- Section ").append(index + 1).append(" ---\n")
            if (part.isBlank()) {
                notes.append("(section notes unavailable)\n")
            } else {
                notes.append(part).append('\n')
                produced++
            }
        }
        return if (produced == 0) null else notes.toString()
    }

    /**
     * Splits at line boundaries into near-even chunks of at most roughly
     * [maxChars] (growing evenly beyond it only when [maxChunks] forces it).
     * Concatenation of the result is exactly [text]. Pure; unit-tested.
     */
    fun splitIntoChunks(text: String, maxChars: Int, maxChunks: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val count = ((text.length + maxChars - 1) / maxChars).coerceAtMost(maxChunks)
        val target = (text.length + count - 1) / count
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length && chunks.size < count - 1) {
            var end = (start + target).coerceAtMost(text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline > start + target * 85 / 100) end = newline + 1
            }
            chunks.add(text.substring(start, end))
            start = end
        }
        if (start < text.length) chunks.add(text.substring(start))
        return chunks
    }

    private fun pack(messages: List<Pair<String, String>>): String = buildString {
        for ((role, content) in messages) {
            append('\u001e').append(role).append('\u001f').append(content)
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
