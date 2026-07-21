package com.meetily.mobile.data

import org.json.JSONArray
import org.json.JSONObject

data class TranscriptSegment(
    val timestampMs: Long,
    val text: String
)

data class Meeting(
    val id: String,
    var title: String,
    val createdAtMs: Long,
    val segments: MutableList<TranscriptSegment> = mutableListOf(),
    var notes: String = "",
    var summary: String = ""
) {
    fun transcriptText(): String =
        segments.joinToString("\n") { it.text }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("createdAtMs", createdAtMs)
        obj.put("notes", notes)
        obj.put("summary", summary)
        val arr = JSONArray()
        for (seg in segments) {
            val s = JSONObject()
            s.put("t", seg.timestampMs)
            s.put("text", seg.text)
            arr.put(s)
        }
        obj.put("segments", arr)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): Meeting {
            val meeting = Meeting(
                id = obj.getString("id"),
                title = obj.optString("title", "Untitled meeting"),
                createdAtMs = obj.optLong("createdAtMs", 0L),
                notes = obj.optString("notes", ""),
                summary = obj.optString("summary", "")
            )
            val arr = obj.optJSONArray("segments") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                meeting.segments.add(
                    TranscriptSegment(s.optLong("t", 0L), s.optString("text", ""))
                )
            }
            return meeting
        }
    }
}
