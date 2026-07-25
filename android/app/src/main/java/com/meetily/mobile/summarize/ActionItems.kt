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
     * Splits an LLM response into the human summary and the parsed action
     * items. Returns null items when no valid JSON tail is present.
     */
    fun splitLlmOutput(text: String): Pair<String, List<ActionItem>?> {
        val idx = text.lastIndexOf(MARKER)
        if (idx < 0) return text.trim() to null
        val clean = text.substring(0, idx).trim()
        val jsonPart = text.substring(idx + MARKER.length)
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            val arr = JSONArray(jsonPart)
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
            clean to items
        } catch (_: Exception) {
            clean to null
        }
    }

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
