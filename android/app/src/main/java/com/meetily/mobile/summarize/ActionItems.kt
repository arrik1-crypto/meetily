package com.meetily.mobile.summarize

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.TranscriptSegment
import org.json.JSONArray

/**
 * Structured action-item extraction. The LLM is asked to append a
 * machine-readable JSON tail to its summary; when that is missing or
 * malformed we fall back to pattern-matching transcript segments, using
 * speaker tags as owners.
 */
object ActionItems {

    const val MARKER = "ACTION_ITEMS_JSON:"

    val LLM_INSTRUCTIONS =
        " At the very end of your response, add a line containing exactly '$MARKER' " +
            "followed by a JSON array where each element is an object with keys " +
            "\"task\" (string) and \"owner\" (string or null, using attendee names). " +
            // Said explicitly because models do all three: asked for a marker
            // after several numbered markdown headings, they turn it into one
            // more heading ("4. Action Items JSON") and fence the array. The
            // parser copes with that now, but not producing it is better.
            "This line is a plain marker, not a section: do not number it, do " +
            "not make it a heading, and do not wrap the array in a code fence. " +
            "Output nothing after the JSON array."

    /**
     * The shared output rules ask the model to write "None identified" under
     * a heading it found nothing for. That belongs in the summary body — it
     * shows the heading was considered — but the model repeats it in the JSON
     * tail too, where it would become a checkable to-do. Exact matches only,
     * so a real task that merely starts with "no" survives.
     */
    private val PLACEHOLDER_TASKS = setOf(
        "none",
        "none identified",
        "none identified at this time",
        "none at this time",
        "none noted",
        "none found",
        "none listed",
        "none mentioned",
        "none specified",
        "none required",
        "none yet",
        "no action items",
        "no action items identified",
        "no actions",
        "no actions identified",
        "no action required",
        "no follow-up required",
        "no follow up required",
        "no follow-ups",
        "no follow ups",
        "nothing identified",
        "nothing noted",
        "nothing to report",
        "n/a",
        "na",
        "not applicable"
    )

    /** True when [text] is a not-found placeholder rather than a real task. */
    fun isPlaceholderTask(text: String): Boolean {
        val cleaned = text
            .trim()
            .trim('-', '•', '*', '·', '–', '—', '+', ' ', '\t')
            .trim()
            .trimEnd('.', '!', ',', ';', ':')
            .trim()
            .lowercase()
        return cleaned.isEmpty() || cleaned in PLACEHOLDER_TASKS
    }

    /**
     * The marker as models actually write it.
     *
     * We ask for a line containing exactly ACTION_ITEMS_JSON:, and a matching
     * exact-string search is what the split used to do. Models do not
     * cooperate that precisely: a local model asked for a structured tail
     * after a set of numbered markdown headings will happily turn the marker
     * into one more heading — "4. Action Items JSON", title-cased, spaces
     * instead of underscores, no colon, sometimes bolded or prefixed with #.
     * None of that matched, so the split silently did not happen and a raw
     * JSON array was rendered into the summary the user reads.
     *
     * Matching the words rather than the exact token costs nothing and covers
     * every one of those shapes.
     */
    private val MARKER_PATTERN =
        Regex("""ACTION[_ \-]?ITEMS[_ \-]?JSON[ \t]*:?""", RegexOption.IGNORE_CASE)

    /** A fenced block, capturing its body. */
    private val FENCED_BLOCK =
        Regex("""```[a-zA-Z]*[ \t]*\r?\n?([\s\S]*?)```""")

    /**
     * Splits an LLM response into the human summary and the parsed action
     * items. Returns null items when no valid JSON tail is present.
     *
     * Whatever happens to the parsing, the returned summary never contains a
     * JSON array of tasks: if one cannot be consumed it is removed anyway.
     * Failing to extract action items degrades to pattern-matching the
     * transcript, which is survivable; showing the user a code block in the
     * middle of their meeting summary is not.
     */
    fun splitLlmOutput(text: String): Pair<String, List<ActionItem>?> {
        val marker = MARKER_PATTERN.findAll(text).lastOrNull()
        if (marker != null) {
            // Cut from the START of the marker's line, so a "## " or "4. "
            // heading prefix goes with it instead of being left dangling.
            val lineStart = text.lastIndexOf('\n', marker.range.first).let {
                if (it < 0) 0 else it + 1
            }
            val body = tidy(text.substring(0, lineStart))
            val items = parseItems(unfence(text.substring(marker.range.last + 1)))
            return body to items
        }
        // No marker at all. The array may still be sitting in a fenced block
        // the model produced under a heading of its own invention.
        val fenced = FENCED_BLOCK.findAll(text).lastOrNull { looksLikeTaskArray(it.groupValues[1]) }
        if (fenced != null) {
            val items = parseItems(unfence(fenced.groupValues[1]))
            return tidy(stripTaskArrays(text)) to items
        }
        return tidy(stripTaskArrays(text)) to null
    }

    /**
     * The array out of whatever the model wrapped it in.
     *
     * Rather than peeling off known decorations one at a time — fences, bold
     * markers, a stray colon, a language tag — take everything between the
     * first '[' and the last ']'. There is only ever one array in this tail,
     * and anything outside it is punctuation by definition.
     */
    private fun unfence(raw: String): String {
        val open = raw.indexOf('[')
        val close = raw.lastIndexOf(']')
        if (open < 0 || close <= open) return raw.trim()
        return raw.substring(open, close + 1)
    }

    private fun parseItems(json: String): List<ActionItem>? = try {
        val arr = JSONArray(json)
        val items = mutableListOf<ActionItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val task = obj.optString("task", "").trim()
            if (task.isBlank() || isPlaceholderTask(task)) continue
            val owner = obj.optString("owner", "").trim()
            val cleanOwner = owner.takeIf {
                it.isNotBlank() && !it.equals("null", true) && !it.equals("unknown", true)
            }
            items.add(ActionItem(task, cleanOwner))
        }
        items
    } catch (_: Exception) {
        null
    }

    /** True when a fenced block's body is the task array rather than real content. */
    private fun looksLikeTaskArray(body: String): Boolean {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) return false
        return try {
            val arr = JSONArray(trimmed)
            // An empty array is still ours to remove; a non-empty one has to
            // look like tasks, so a summary that legitimately quotes some
            // other JSON survives.
            (0 until arr.length()).all { arr.optJSONObject(it)?.has("task") == true }
        } catch (_: Exception) {
            false
        }
    }

    /** Removes any fenced task array, and the heading immediately above it. */
    private fun stripTaskArrays(text: String): String {
        var out = text
        for (match in FENCED_BLOCK.findAll(text).toList().asReversed()) {
            if (!looksLikeTaskArray(match.groupValues[1])) continue
            val lineStart = out.lastIndexOf('\n', match.range.first).let {
                if (it < 0) 0 else it + 1
            }
            // Drop a lone heading line directly above the block — otherwise
            // removing the JSON leaves "Action Items JSON" pointing at nothing.
            val headingStart = headingLineStartAbove(out, lineStart)
            out = out.removeRange(headingStart, minOf(match.range.last + 1, out.length))
        }
        return out
    }

    private fun headingLineStartAbove(text: String, blockStart: Int): Int {
        var cursor = blockStart
        // Step back over blank lines, then over one heading-ish line.
        while (cursor > 0) {
            val prevEnd = cursor - 1
            val prevStart = text.lastIndexOf('\n', prevEnd - 1).let {
                if (it < 0) 0 else it + 1
            }
            val line = text.substring(prevStart, prevEnd).trim()
            if (line.isEmpty()) {
                cursor = prevStart
                continue
            }
            val bare = line.trim('#', '*', ' ', '\t', ':').trim()
            val isJsonHeading = MARKER_PATTERN.containsMatchIn(bare) ||
                bare.replace(Regex("""^\d+[.)]\s*"""), "")
                    .equals("action items json", ignoreCase = true)
            return if (isJsonHeading) prevStart else cursor
        }
        return cursor
    }

    /**
     * Trailing markdown left behind once the tail is removed — a lone "**" or
     * a stray fence, which render as literal characters at the end of the
     * summary. Seen in the wild directly under a stripped JSON block.
     */
    private fun tidy(body: String): String = body
        .trimEnd()
        .lines()
        .dropLastWhile { line ->
            val t = line.trim()
            // Emphasis characters only. Backticks are deliberately NOT in this
            // set: a lone trailing fence means a code block the summary still
            // needs, and dropping it would corrupt content rather than tidy it.
            t.isEmpty() || (t.isNotEmpty() && t.all { it == '*' || it == '_' })
        }
        .joinToString("\n")
        .trim()

    /** Offline fallback: action-flavored segments become items, speaker tag = owner. */
    fun fromMeetingContent(segments: List<TranscriptSegment>, notes: String): List<ActionItem> {
        val items = mutableListOf<ActionItem>()
        for (segment in segments) {
            val text = segment.text.trim()
            if (ExtractiveSummarizer.isActionSentence(text) && !isPlaceholderTask(text)) {
                items.add(ActionItem(text, segment.speaker))
            }
        }
        for (line in notes.split('\n')) {
            val trimmed = line.trim().trimStart('-', '•', '*', ' ')
            if (trimmed.length > 8 &&
                ExtractiveSummarizer.isActionSentence(trimmed) &&
                !isPlaceholderTask(trimmed)
            ) {
                items.add(ActionItem(trimmed))
            }
        }
        return items.distinctBy { it.task.lowercase() }.take(30)
    }
}
