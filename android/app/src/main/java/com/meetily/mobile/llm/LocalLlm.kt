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
    const val N_CTX = 4096
    private const val MAX_REPLY_TOKENS = 700

    /**
     * Reply budget for the one retry after a model talks itself out of an
     * answer. Deliberately lopsided: the prompt shrinks to pay for it,
     * because a second identical attempt would fail identically.
     */
    private const val RETRY_REPLY_TOKENS = 1_400

    /** Added only on that retry; harmless to models that never deliberate. */
    private const val NO_DELIBERATION =
        "Answer directly. Do not think step by step, do not explain your " +
            "reasoning, and do not emit a think block — write only the " +
            "finished summary."


    /**
     * Prompt budget in characters, leaving room for the reply and template
     * overhead. Derived from [N_CTX] via the shared shaping rules rather than
     * computed here, so a second runtime with a different window cannot end
     * up using llama.cpp's number.
     */
    val CHAR_BUDGET = PromptShaping.charBudget(N_CTX)

    /** Above this, middle-trimming loses too much: condense per-section instead. */
    val MAP_REDUCE_THRESHOLD = PromptShaping.mapReduceThreshold(N_CTX)

    /**
     * Progress during long generations: (section, totalSections) while
     * condensing a long transcript, then (0, 0) once as "writing the final
     * summary". Not invoked at all for short single-pass generations.
     */
    @Volatile
    var stageListener: ((Int, Int) -> Unit)? = null

    private var appContext: Context? = null
    private var ptr = 0L
    private var loadedKey: String? = null

    /**
     * Guards the native handle. A ReentrantLock rather than a monitor so
     * [release] can DECLINE to wait: it is called from the main thread on
     * memory pressure (RecapApp.onTrimMemory) and from Settings, and
     * inference holds this lock for minutes. Blocking there froze the whole
     * app and risked an ANR.
     */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** Set when a release arrived mid-inference; honoured when it finishes. */
    @Volatile
    private var releasePending = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * True when this runtime should serve chat rather than a network
     * endpoint. Routed through [EngineRouting] so the predicate that decides
     * it has JVM test coverage; this method itself needs a Context and so
     * cannot have any.
     */
    fun isSelected(): Boolean {
        val context = appContext ?: return false
        return EngineRouting.staysOnDevice(AppSettings(context).llmEngine)
    }

    /** @see PromptShaping.stripThinking — kept here so callers and tests don't move. */
    fun stripThinking(reply: String): String = PromptShaping.stripThinking(reply)

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

        lock.lock()
        try {
            releasePending = false
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
                    stageListener?.invoke(0, 0)
                }
            }

            applyThreadBudget()
            val raw = LlamaBridge.generate(
                ptr, pack(fitToContext(pairs, MAX_REPLY_TOKENS)), MAX_REPLY_TOKENS
            )?.trim().orEmpty()
            val reply = stripThinking(raw)
            if (reply.isNotBlank()) return reply

            // Empty has three causes and they are not the same problem.
            // Reporting all of them as "returned an empty response" told the
            // user nothing and discarded the one case that is recoverable.
            if (raw.isBlank()) {
                throw IllegalStateException(
                    "The on-device model produced no output. Try a smaller model, " +
                        "or a shorter meeting."
                )
            }
            if (!PromptShaping.thinkingRanOver(raw)) {
                throw IllegalStateException("On-device model returned an empty response")
            }

            // It reasoned for the whole budget and never reached an answer.
            // Retry once with the trade reversed — more room to reply, less
            // to read — and ask it plainly not to deliberate.
            applyThreadBudget()
            val retried = stripThinking(
                LlamaBridge.generate(
                    ptr,
                    pack(
                        fitToContext(
                            pairs + Pair("system", NO_DELIBERATION),
                            RETRY_REPLY_TOKENS
                        )
                    ),
                    RETRY_REPLY_TOKENS
                )?.trim().orEmpty()
            )
            if (retried.isNotBlank()) return retried
            throw IllegalStateException(
                "The on-device model spent its whole reply thinking and never " +
                    "answered. A smaller or non-reasoning model will do better here."
            )
        } finally {
            // A release that arrived mid-inference was deferred rather than
            // blocking its caller; honour it now, off the main thread.
            if (releasePending) freeLocked()
            lock.unlock()
        }
    }

    /**
     * Shapes [pairs] to a prompt that provably fits the context window,
     * leaving room for [replyTokens].
     *
     * [PromptShaping.charBudget] is a chars-per-token ESTIMATE, calibrated
     * on prose. A meeting transcript is close to the worst case for it —
     * timestamps, speaker labels and proper nouns all tokenize far worse
     * than three characters each — so the estimate ran optimistic and the
     * prompt overflowed the window. llama.cpp then clamped it, and the
     * clamp is invisible to this side: the summary simply came back empty.
     *
     * So measure instead of guessing, and shrink until it fits. Each pass
     * scales the character budget by how far over the last one was, with a
     * 10% margin so a near-miss converges rather than oscillating.
     *
     * Falls back to the estimate alone if the count is unavailable — an
     * older native library without the export, or a tokenizer failure. That
     * is the behaviour this replaces, so the fallback is never worse.
     */
    private fun fitToContext(
        pairs: List<Pair<String, String>>,
        replyTokens: Int
    ): List<Pair<String, String>> {
        // Matches the native clamp: n_ctx - max_tokens - 8, with a little
        // more held back so we land under it rather than exactly on it.
        val limit = N_CTX - replyTokens - 64
        var chars = PromptShaping.charBudget(N_CTX, replyTokens)
        var shaped = budgetMessages(pairs, chars)
        repeat(4) {
            val counted = try {
                LlamaBridge.countTokens(ptr, pack(shaped))
            } catch (_: Throwable) {
                -1
            }
            if (counted <= 0 || counted <= limit) return shaped
            val next = PromptShaping.shrinkBudget(chars, counted, limit)
            // budgetMessages has floors of its own, so a budget it cannot
            // honour would loop without shrinking anything. Stop and let the
            // native middle-clamp catch what is left.
            if (next >= chars) return shaped
            chars = next
            shaped = budgetMessages(pairs, chars)
        }
        return shaped
    }

    fun needsMapReduce(length: Int): Boolean = length > MAP_REDUCE_THRESHOLD

    /**
     * Condenses [content] chunk-by-chunk into ordered section notes. Must be
     * called with [lock] held and the model loaded; the thread budget is
     * re-applied per section because a recording can start mid-summary.
     */
    private fun condense(content: String): String? = PromptShaping.condense(
        content = content,
        charBudget = CHAR_BUDGET,
        generate = { messages, replyTokens ->
            applyThreadBudget()
            // Measured, like the final pass. A section chunk is 8k characters
            // against the same estimate, so it overflows for the same reason
            // — and an overflowing section came back as "(section notes
            // unavailable)", which reads like the model failing rather than
            // the prompt never having fitted.
            LlamaBridge.generate(
                ptr, pack(fitToContext(messages, replyTokens)), replyTokens
            )?.trim().orEmpty()
        },
        onSection = { index, total -> stageListener?.invoke(index, total) }
    )

    /** @see PromptShaping.splitIntoChunks — kept here so callers and tests don't move. */
    fun splitIntoChunks(text: String, maxChars: Int, maxChunks: Int): List<String> =
        PromptShaping.splitIntoChunks(text, maxChars, maxChunks)

    private fun pack(messages: List<Pair<String, String>>): String = buildString {
        for ((role, content) in messages) {
            append('\u001e').append(role).append('\u001f').append(content)
        }
    }

    /**
     * Frees the loaded model (memory pressure, model switch, shutdown).
     *
     * NEVER BLOCKS. Callers include the main thread (onTrimMemory, deleting a
     * model in Settings) and inference can hold the lock for minutes, so when
     * the model is busy this records the request and returns; the generation
     * frees it on the way out.
     */
    fun release() {
        if (!lock.tryLock()) {
            releasePending = true
            return
        }
        try {
            freeLocked()
        } finally {
            lock.unlock()
        }
    }

    /** Caller must hold [lock]. */
    private fun freeLocked() {
        if (ptr != 0L) {
            LlamaBridge.freeModel(ptr)
            ptr = 0L
            loadedKey = null
        }
        releasePending = false
    }

    private fun ensureLoaded(context: Context, model: LocalLlmModel) {
        if (ptr != 0L && loadedKey == model.key) return
        if (ptr != 0L) {
            LlamaBridge.freeModel(ptr)
            ptr = 0L
            loadedKey = null
        }
        // Shared budget: a summary must not out-thread a live recording,
        // and must leave the UI thread a core to run on.
        val threads = currentThreadBudget()
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

    /** @see PromptShaping.budgetMessages — kept here so callers and tests don't move. */
    fun budgetMessages(
        messages: List<Pair<String, String>>,
        charBudget: Int
    ): List<Pair<String, String>> = PromptShaping.budgetMessages(messages, charBudget)

    /**
     * Threads this run may take right now.
     *
     * Read fresh rather than fixed at load: a recording can start long after
     * a summary does, and its capture threads land on top of a pool sized for
     * an idle phone. That surplus is what starves the UI thread.
     */
    private fun currentThreadBudget(): Int =
        com.meetily.mobile.data.HeavyWork.batchThreads(
            com.meetily.mobile.RecordingService.isRunning
        )

    /** Resizes the live context; safe only between generations. */
    private fun applyThreadBudget() {
        val handle = ptr
        if (handle == 0L) return
        try {
            LlamaBridge.setThreads(handle, currentThreadBudget())
        } catch (_: Throwable) {
            // Older native lib without the export: keep the load-time pool.
        }
    }

}
