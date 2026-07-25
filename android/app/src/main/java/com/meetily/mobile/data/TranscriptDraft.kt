package com.meetily.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The staging area for a second-pass transcript.
 *
 * The accuracy check writes here, never into the meeting, until the user has
 * seen the comparison and chosen. That is the whole point of staging: a check
 * that is cancelled, killed, or fails halfway must leave the stored transcript
 * exactly as it was. A partial transcript is the right outcome for a NEW
 * import and the wrong one for a replacement.
 */
object TranscriptDraft {

    data class Draft(
        val meetingId: String,
        val modelKey: String,
        /** False while the pass is still running or if it died mid-file. */
        val complete: Boolean,
        val segments: List<TranscriptSegment>
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "transcript-drafts").apply { mkdirs() }

    private fun fileFor(context: Context, meetingId: String): File =
        File(dir(context), "$meetingId.json")

    fun save(
        context: Context,
        meetingId: String,
        modelKey: String,
        segments: List<TranscriptSegment>,
        complete: Boolean
    ) {
        val obj = JSONObject()
        obj.put("meetingId", meetingId)
        obj.put("model", modelKey)
        obj.put("complete", complete)
        val arr = JSONArray()
        for (segment in segments) arr.put(Meeting.segmentToJson(segment))
        obj.put("segments", arr)
        val file = fileFor(context, meetingId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(obj.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun load(context: Context, meetingId: String): Draft? {
        val file = fileFor(context, meetingId)
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("segments") ?: JSONArray()
            val segments = mutableListOf<TranscriptSegment>()
            for (i in 0 until arr.length()) {
                segments.add(Meeting.segmentFromJson(arr.getJSONObject(i)))
            }
            Draft(
                meetingId = obj.optString("meetingId", meetingId),
                modelKey = obj.optString("model", ""),
                complete = obj.optBoolean("complete", false),
                segments = segments
            )
        } catch (_: Exception) {
            null
        }
    }

    /** A finished pass waiting to be reviewed. */
    fun pending(context: Context, meetingId: String): Draft? =
        load(context, meetingId)?.takeIf { it.complete && it.segments.isNotEmpty() }

    fun delete(context: Context, meetingId: String) {
        try {
            fileFor(context, meetingId).delete()
        } catch (_: Exception) {
        }
    }
}
