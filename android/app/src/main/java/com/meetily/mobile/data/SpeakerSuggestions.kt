package com.meetily.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Speaker attributions proposed by the LLM, held aside until the user has
 * looked at them.
 *
 * The analysis now runs in a service the user can walk away from, so its
 * result has to outlive the screen that asked for it. Suggestions are never
 * applied on arrival: manual tags win over model output everywhere else in
 * the app, and a run that finished while the user was in another app is
 * exactly when a silent rewrite would be least welcome.
 *
 * Written whole at the end of a run, so the file existing means the run
 * finished.
 */
object SpeakerSuggestions {

    private fun dir(context: Context): File =
        File(context.filesDir, "speaker-suggestions").apply { mkdirs() }

    private fun fileFor(context: Context, meetingId: String): File =
        File(dir(context), "$meetingId.json")

    /** Cheap enough for the main thread: one file-exists test. */
    fun isPending(context: Context, meetingId: String): Boolean =
        fileFor(context, meetingId).exists()

    fun save(context: Context, meetingId: String, suggestions: List<Pair<Int, String>>) {
        if (suggestions.isEmpty()) {
            delete(context, meetingId)
            return
        }
        val arr = JSONArray()
        for ((line, name) in suggestions) {
            arr.put(JSONObject().put("line", line).put("speaker", name))
        }
        val obj = JSONObject().put("meetingId", meetingId).put("suggestions", arr)
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

    fun load(context: Context, meetingId: String): List<Pair<Int, String>> {
        val file = fileFor(context, meetingId)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("suggestions") ?: JSONArray()
            val out = mutableListOf<Pair<Int, String>>()
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val name = entry.optString("speaker", "")
                if (name.isNotBlank()) out.add(entry.optInt("line", -1) to name)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun delete(context: Context, meetingId: String) {
        try {
            fileFor(context, meetingId).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * The suggestions still worth showing: a line that is in range and has no
     * speaker yet. Re-checked against the meeting as it stands at review
     * time, because the user may have tagged some of those lines themselves
     * while the analysis was running — and their tag wins.
     */
    fun applicable(
        suggestions: List<Pair<Int, String>>,
        segments: List<TranscriptSegment>
    ): List<Pair<Int, String>> =
        suggestions
            .distinctBy { it.first }
            .filter { (index, _) ->
                index in segments.indices && segments[index].speaker.isNullOrBlank()
            }
}
