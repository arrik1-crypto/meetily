package com.meetily.mobile.data

import org.json.JSONArray
import org.json.JSONObject

data class TranscriptSegment(
    val timestampMs: Long,
    val text: String,
    val speaker: String? = null,
    val highlighted: Boolean = false,
    /** Acoustic diarization cluster ("Speaker N") when no name is known. */
    val clusterId: Int? = null,
    /** Offset into the meeting's audio file, for tap-to-play seek. */
    val audioMs: Long? = null
)

data class QaEntry(
    val question: String,
    val answer: String
)

data class ActionItem(
    val task: String,
    val owner: String? = null,
    val done: Boolean = false
)

data class Meeting(
    val id: String,
    var title: String,
    val createdAtMs: Long,
    val segments: MutableList<TranscriptSegment> = mutableListOf(),
    var notes: String = "",
    var summary: String = "",
    var attendees: MutableList<String> = mutableListOf(),
    val qa: MutableList<QaEntry> = mutableListOf(),
    var actionItems: MutableList<ActionItem> = mutableListOf(),
    var photos: MutableList<String> = mutableListOf(),
    /** Filename in AudioStore when the meeting's audio was kept. */
    var audioFile: String? = null
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
            if (seg.clusterId != null) {
                s.put("cluster", seg.clusterId)
            }
            if (seg.audioMs != null) {
                s.put("audioMs", seg.audioMs)
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
        val actionsArr = JSONArray()
        for (item in actionItems) {
            val a = JSONObject()
            a.put("task", item.task)
            if (!item.owner.isNullOrBlank()) a.put("owner", item.owner)
            a.put("done", item.done)
            actionsArr.put(a)
        }
        obj.put("actionItems", actionsArr)
        val photosArr = JSONArray()
        for (name in photos) {
            photosArr.put(name)
        }
        obj.put("photos", photosArr)
        if (!audioFile.isNullOrBlank()) {
            obj.put("audioFile", audioFile)
        }
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
                        highlighted = s.optBoolean("highlighted", false),
                        clusterId = if (s.has("cluster")) s.optInt("cluster") else null,
                        audioMs = if (s.has("audioMs")) s.optLong("audioMs") else null
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
            val actionsArr = obj.optJSONArray("actionItems") ?: JSONArray()
            for (i in 0 until actionsArr.length()) {
                val a = actionsArr.getJSONObject(i)
                val task = a.optString("task", "")
                if (task.isNotBlank()) {
                    val owner = a.optString("owner", "")
                    meeting.actionItems.add(
                        ActionItem(task, owner.ifBlank { null }, a.optBoolean("done", false))
                    )
                }
            }
            val photosArr = obj.optJSONArray("photos") ?: JSONArray()
            for (i in 0 until photosArr.length()) {
                val name = photosArr.optString(i, "")
                if (name.isNotBlank()) meeting.photos.add(name)
            }
            meeting.audioFile = obj.optString("audioFile", "").ifBlank { null }
            return meeting
        }
    }
}
