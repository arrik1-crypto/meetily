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
 *
 * A partial draft survives only where a later pass can carry it on: one the
 * charger stopped (requeued), or one left by a pass that died with the
 * process (offered back). Never marked ready, it holds where it stopped
 * ([Draft.resumeMs]), and a pass of the same model, reading the same audio
 * ([Draft.clearVoice]), continues from there instead of starting over
 * (TranscriptResume).
 */
object TranscriptDraft {

    data class Draft(
        val meetingId: String,
        val modelKey: String,
        /** False while the pass is still running or if it died mid-file. */
        val complete: Boolean,
        val segments: List<TranscriptSegment>,
        /**
         * The language the pass ran in (detected or pinned), and whether it
         * translated; applied to the meeting with the words when the whole
         * draft is accepted. Null and false in drafts written before this.
         */
        val language: String? = null,
        val translated: Boolean = false,
        /**
         * Set while [complete] is false and the pass stopped part-way
         * (charger unplugged): the audio offset the next pass of the same
         * model continues from, keeping the lines before it. See
         * TranscriptResume.
         */
        val resumeMs: Long? = null,
        /**
         * Set while [complete] is false: the pass decoded the meeting's
         * clear-voice copy (ClearVoice), as the user chose, rather than its
         * recording. Only a pass on the same audio carries the words on;
         * one on the other starts over rather than join the two.
         */
        val clearVoice: Boolean = false
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "transcript-drafts").apply { mkdirs() }

    private fun fileFor(context: Context, meetingId: String): File =
        SafeFiles.child(dir(context), "$meetingId.json")

    /**
     * Sidecar written only once a pass finishes cleanly, so screens can ask
     * "is there a check waiting?" with a file-exists test instead of parsing
     * a whole second transcript on the main thread.
     */
    private fun readyFor(context: Context, meetingId: String): File =
        SafeFiles.child(dir(context), "$meetingId.ok")

    fun save(
        context: Context,
        meetingId: String,
        modelKey: String,
        segments: List<TranscriptSegment>,
        complete: Boolean,
        language: String? = null,
        translated: Boolean = false,
        /** Where an unfinished pass stopped; see [Draft.resumeMs]. */
        resumeMs: Long? = null,
        /** The pass reads the clear-voice copy; see [Draft.clearVoice]. */
        clearVoice: Boolean = false
    ) {
        // A check still running on a meeting the user has deleted must not
        // write its draft back: deleteAll just removed it, and a stray draft
        // is transcript text that would ride along in every later backup.
        if (MeetingStore.wasDeleted(meetingId)) return
        val obj = JSONObject()
        obj.put("meetingId", meetingId)
        obj.put("model", modelKey)
        obj.put("complete", complete)
        if (!language.isNullOrBlank()) obj.put("language", language)
        if (translated) obj.put("translated", true)
        if (!complete && resumeMs != null && resumeMs >= 0L) obj.put("resumeMs", resumeMs)
        if (!complete && clearVoice) obj.put("clearVoice", true)
        val arr = JSONArray()
        for (segment in segments) arr.put(Meeting.segmentToJson(segment))
        obj.put("segments", arr)
        val file = fileFor(context, meetingId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            // Synced before the rename, for the same reason as AtomicJson.
            val bytes = obj.toString().toByteArray(Charsets.UTF_8)
            AtomicJson.writeSynced(tmp, bytes)
            if (!tmp.renameTo(file)) {
                AtomicJson.writeSynced(file, bytes)
                tmp.delete()
            }
            // Only after the content is on disk, so the marker can never
            // promise a draft that is not there.
            val ready = readyFor(context, meetingId)
            if (complete && segments.isNotEmpty()) ready.writeText("1") else ready.delete()
        } catch (_: Exception) {
        }
    }

    /** Cheap check for a finished pass — no parsing, safe on the main thread. */
    fun isPending(context: Context, meetingId: String): Boolean =
        readyFor(context, meetingId).exists() && fileFor(context, meetingId).exists()

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
                segments = segments,
                language = MeetingLanguage.normalize(obj.optString("language", "")),
                translated = obj.optBoolean("translated", false),
                resumeMs = if (obj.has("resumeMs")) {
                    obj.optLong("resumeMs", -1L).takeIf { it >= 0L }
                } else {
                    null
                },
                // Absent in drafts written before this: the recording.
                clearVoice = obj.optBoolean("clearVoice", false)
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
            readyFor(context, meetingId).delete()
            fileFor(context, meetingId).delete()
        } catch (_: Exception) {
        }
    }
}
