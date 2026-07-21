package com.meetily.mobile.data

import org.json.JSONArray
import org.json.JSONObject

data class TranscriptSegment(
    val timestampMs: Long,
    val text: String,
    val speaker: String? = null,
    val highlighted: Boolean = false
)

data class QaEntry(
    val question: String,
    val answer: String
)

data class Meeting(
    val id: String,
    var title: String,
    val createdAtMs: Long,
    val segments: MutableList<TranscriptSegment> = mutableListOf(),
    var notes: String = "",
    var summary: String = "",
    var attendees: MutableList<String> = mutableListOf(),
    val qa: MutableList<QaEntry> = mutableListOf()
) {
    /** Raw transcript text, no speaker labels (used for snippets, word counts, extractive summary). */
    fun transcriptText(): String =
        segments.joinToString("\n") { it.text }

    /** Transcript with speaker labels where assigned (used for sharing and LLM summaries). */
    fun transcriptTextWithSpeakers(): String =
        segments.joinToString("\n") { seg ->
            val speaker = seg.speaker
            if (speaker.isNullOrBlank()) seg.text else "$speaker: ${seg.text}"
        }

    fun highlightedTexts(): List<String> =
        segments.filter { it.highlighted }.map { seg ->
            val speaker = seg.speaker
            if (speaker.isNullOrBlank()) seg.text else "$speaker: ${seg.text}"
        }

    fun attendeesText(): String = attendees.joinToString(", ")

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("createdAtMs", createdAtMs)
        obj.put("notes", notes)
        obj.put("summary", summary)
        val attendeesArr = JSONArray()
        for (name in attendees) {
            attendeesArr.put(name)
        }
        obj.put("attendees", attendeesArr)
        val arr = JSONArray()
        for (seg in segments) {
            val s = JSONObject()
            s.put("t", seg.timestampMs)
            s.put("text", seg.text)
            if (!seg.speaker.isNullOrBlank()) {
                s.put("speaker", seg.speaker)
            }
            if (seg.highlighted) {
                s.put("highlighted", true)
            }
            arr.put(s)
        }
        obj.put("segments", arr)
        val qaArr = JSONArray()
        for (entry in qa) {
            val q = JSONObject()
            q.put("q", entry.question)
            q.put("a", entry.answer)
            qaArr.put(q)
        }
        obj.put("qa", qaArr)
        return obj
    }

    companion object {
        fun parseAttendees(input: String): MutableList<String> =
            input.split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toMutableList()

        fun fromJson(obj: JSONObject): Meeting {
            val meeting = Meeting(
                id = obj.getString("id"),
                title = obj.optString("title", "Untitled meeting"),
                createdAtMs = obj.optLong("createdAtMs", 0L),
                notes = obj.optString("notes", ""),
                summary = obj.optString("summary", "")
            )
            val attendeesArr = obj.optJSONArray("attendees") ?: JSONArray()
            for (i in 0 until attendeesArr.length()) {
                val name = attendeesArr.optString(i, "")
                if (name.isNotBlank()) meeting.attendees.add(name)
            }
            val arr = obj.optJSONArray("segments") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val speaker = s.optString("speaker", "")
                meeting.segments.add(
                    TranscriptSegment(
                        timestampMs = s.optLong("t", 0L),
                        text = s.optString("text", ""),
                        speaker = speaker.ifBlank { null },
                        highlighted = s.optBoolean("highlighted", false)
                    )
                )
            }
            val qaArr = obj.optJSONArray("qa") ?: JSONArray()
            for (i in 0 until qaArr.length()) {
                val q = qaArr.getJSONObject(i)
                val question = q.optString("q", "")
                val answer = q.optString("a", "")
                if (question.isNotBlank()) {
                    meeting.qa.add(QaEntry(question, answer))
                }
            }
            return meeting
        }
    }
}
