package com.meetily.mobile.llm

/**
 * Everything about fitting a meeting into a small context window, with no
 * dependency on which runtime does the generating.
 *
 * This was all private to [LocalLlm] until a second on-device runtime
 * arrived. Copying it would have been the cheaper edit and the worse one:
 * two map-reduce loops drift, and the drift shows up as one engine
 * summarising the whole meeting while the other silently loses its middle.
 * The same hazard AutoSummary was written to close.
 *
 * The context budget is a parameter rather than a constant here, because it
 * is a property of the loaded model, not of the app. Hard-coding llama.cpp's
 * 4096 for a runtime with a different window either wastes it or overflows
 * it, and both fail quietly.
 */
object PromptShaping {

    /** Reply headroom and template slack, in tokens, reserved from a window. */
    private const val REPLY_TOKENS = 700
    private const val TEMPLATE_SLACK = 120

    /** Conservative chars-per-token for English prose with punctuation. */
    private const val CHARS_PER_TOKEN = 3

    const val MAP_CHUNK_CHARS = 8_000
    const val MAP_MAX_CHUNKS = 12
    const val MAP_REPLY_TOKENS = 220

    const val MAP_PROMPT =
        "You are condensing one section of a longer meeting transcript. " +
            "Write compact notes (up to 8 short bullets) capturing decisions, " +
            "action items with their owners, key facts and numbers, and what " +
            "was discussed. No preamble, no commentary."

    /**
     * Prompt budget in characters for a model with [contextTokens] of window.
     *
     * [replyTokens] is a parameter because a retry may deliberately trade
     * prompt room for a longer answer — a reply that ran out of budget
     * mid-thought needs more space, not the same amount again.
     */
    fun charBudget(contextTokens: Int, replyTokens: Int = REPLY_TOKENS): Int =
        (contextTokens - replyTokens - TEMPLATE_SLACK).coerceAtLeast(400) * CHARS_PER_TOKEN

    /** Above this, middle-trimming loses too much: condense per-section instead. */
    fun mapReduceThreshold(contextTokens: Int): Int = charBudget(contextTokens) * 3 / 2

    /**
     * A smaller character budget, given that [chars] tokenized to [counted]
     * against a ceiling of [limit].
     *
     * Scales by how far over the attempt was, then takes another 10% off:
     * without that margin a prompt that lands one token over converges by
     * roughly one token per pass and never gets under in a bounded number
     * of tries. Never returns a budget too small to hold a prompt at all.
     */
    fun shrinkBudget(chars: Int, counted: Int, limit: Int): Int {
        if (counted <= 0 || limit <= 0) return chars
        return (chars.toLong() * limit / counted * 9 / 10)
            .coerceIn(400L, chars.toLong())
            .toInt()
    }

    /**
     * Reasoning models (Qwen 3.5 and kin) may open with a `<think>` block via
     * their chat template; users should only ever see the answer. Also
     * handles a truncated block (budget ran out mid-thought).
     *
     * Applies to every runtime. It is a property of the MODEL's chat
     * template, not of the engine executing it — the same Qwen weights emit
     * the same block whichever runtime loads them. Leaving this on one path
     * would leak raw chain-of-thought into saved summaries on the other.
     */
    /**
     * True when [reply] is nothing but an unfinished `<think>` block.
     *
     * This is the shape a reasoning model produces when it deliberates for
     * its entire reply budget and never reaches an answer. [stripThinking]
     * correctly refuses to show it, which leaves an empty string — and an
     * empty string is indistinguishable from the model having failed
     * outright unless someone asks this question. The two want completely
     * different responses: one is worth retrying with more room, the other
     * is not.
     */
    fun thinkingRanOver(reply: String): Boolean {
        val trimmed = reply.trim()
        return trimmed.startsWith("<think>") && !trimmed.contains("</think>")
    }

    fun stripThinking(reply: String): String {
        val trimmed = reply.trim()
        if (!trimmed.startsWith("<think>")) {
            return trimmed.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        }
        val close = trimmed.indexOf("</think>")
        return if (close >= 0) {
            trimmed.substring(close + "</think>".length).trim()
        } else {
            "" // never surface raw chain-of-thought as the summary
        }
    }

    /**
     * Splits at line boundaries into near-even chunks of at most roughly
     * [maxChars] (growing evenly beyond it only when [maxChunks] forces it).
     * Concatenation of the result is exactly [text]. Pure; unit-tested.
     */
    fun splitIntoChunks(
        text: String,
        maxChars: Int = MAP_CHUNK_CHARS,
        maxChunks: Int = MAP_MAX_CHUNKS
    ): List<String> {
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

    /** The omission marker; public so tests can measure against it directly. */
    const val OMISSION_MARKER =
        "\n…[middle of this section omitted to fit the on-device model]…\n"

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
            val keep = (target - OMISSION_MARKER.length).coerceAtLeast(400)
            val head = (keep * 6) / 10
            val tail = keep - head
            out[index] = role to (
                content.take(head) + OMISSION_MARKER + content.takeLast(tail)
                )
        }
        return out
    }

    /**
     * Condenses [content] chunk-by-chunk into ordered section notes, using
     * whatever [generate] the calling runtime supplies. Returns null when
     * there is nothing to gain (single chunk) or every section pass failed —
     * the caller then falls back to head+tail trimming.
     *
     * [generate] takes (messages, maxReplyTokens) and returns the reply; it
     * may throw, and a thrown section is recorded as unavailable rather than
     * abandoning the whole transcript. [onSection] reports 1-based progress.
     */
    fun condense(
        content: String,
        charBudget: Int,
        generate: (List<Pair<String, String>>, Int) -> String,
        onSection: (Int, Int) -> Unit = { _, _ -> },
        /**
         * Abandons the remaining sections when it turns true — the caller no
         * longer wants the answer at all (the charger came out). Checked
         * here as well as inside [generate] because a stopped section
         * returns instantly, and without this the loop would race through
         * every remaining chunk writing "(section notes unavailable)".
         */
        shouldStop: () -> Boolean = { false }
    ): String? {
        val chunks = splitIntoChunks(content, MAP_CHUNK_CHARS, MAP_MAX_CHUNKS)
        if (chunks.size < 2) return null
        val notes = StringBuilder(
            "[Ordered notes condensed from the full transcript of a long meeting]\n"
        )
        var produced = 0
        for ((index, chunk) in chunks.withIndex()) {
            // Null, not the notes gathered so far: a half-covered transcript
            // summarised as if it were the whole meeting is worse than no
            // summary, and the caller is about to requeue the job.
            if (shouldStop()) return null
            onSection(index + 1, chunks.size)
            // The chunk cap can force chunks past the budget; trim those.
            val body = if (chunk.length > charBudget - 600) {
                budgetMessages(listOf("user" to chunk), charBudget - 600)[0].second
            } else {
                chunk
            }
            val part = try {
                // Strip here as well as at the end. Without this a reasoning
                // model's deliberation is pasted verbatim into the notes, and
                // the final pass then spends its context reading someone
                // else's working-out instead of the meeting.
                stripThinking(
                    generate(listOf("system" to MAP_PROMPT, "user" to body), MAP_REPLY_TOKENS)
                ).trim()
            } catch (_: Throwable) {
                // Throwable, not Exception: a runtime that allocates on the
                // JVM heap raises OutOfMemoryError where llama.cpp returned
                // null, and one bad section must not lose the other eleven.
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
}
