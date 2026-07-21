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
                if (task.isBlank()) continue
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
            if (ExtractiveSummarizer.isActionSentence(segment.text)) {
                items.add(ActionItem(segment.text.trim(), segment.speaker))
            }
        }
        for (line in notes.split('\n')) {
            val trimmed = line.trim().trimStart('-', '•', '*', ' ')
            if (trimmed.length > 8 && ExtractiveSummarizer.isActionSentence(trimmed)) {
                items.add(ActionItem(trimmed))
            }
        }
        return items.distinctBy { it.task.lowercase() }.take(30)
    }
}
