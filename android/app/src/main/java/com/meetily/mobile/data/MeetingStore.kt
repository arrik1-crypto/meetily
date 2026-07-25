package com.meetily.mobile.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Simple file-backed store: one JSON file per meeting under filesDir/meetings.
 * Everything stays on-device, matching Meetily's privacy-first design.
 */
class MeetingStore(context: Context) {

    private val dir: File = File(context.filesDir, "meetings").apply { mkdirs() }

    fun list(): List<Meeting> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                Meeting.fromJson(JSONObject(file.readText()))
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAtMs }
    }

    fun load(id: String): Meeting? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return try {
            Meeting.fromJson(JSONObject(file.readText()))
        } catch (_: Exception) {
            null
        }
    }

    fun save(meeting: Meeting) {
        val file = File(dir, "${meeting.id}.json")
        val tmp = File(dir, "${meeting.id}.json.tmp")
        tmp.writeText(meeting.toJson().toString())
        if (!tmp.renameTo(file)) {
            file.writeText(meeting.toJson().toString())
            tmp.delete()
        }
    }

    /**
     * Saves [meeting] after folding in any transcript segments that reached
     * disk since it was loaded.
     *
     * Screens hold a Meeting for as long as they are open and write the whole
     * object back on every change, so a plain [save] from a screen can erase
     * a segment appended meanwhile by the recording service (see
     * RecordingService.appendLateSegment). Everything else on the screen's
     * copy still wins — it is the one carrying the user's edits.
     */
    fun saveMerging(meeting: Meeting) {
        val onDisk = load(meeting.id)
        if (onDisk != null) MeetingMerge.foldLateSegments(meeting, onDisk)
        save(meeting)
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    // --- Active-recording marker, for crash recovery ---------------------
    // The recording service writes the in-progress meeting id here and saves
    // the meeting incrementally. If the process dies mid-recording the marker
    // survives, so the next launch knows a meeting was interrupted (its data
    // is already on disk from the incremental saves).

    private val activeMarker = File(dir, ".active")

    fun markActive(id: String) {
        try {
            activeMarker.writeText(id)
        } catch (_: Exception) {
        }
    }

    fun clearActive() {
        activeMarker.delete()
    }

    fun activeId(): String? {
        if (!activeMarker.exists()) return null
        val id = try {
            activeMarker.readText().trim()
        } catch (_: Exception) {
            ""
        }
        return id.ifBlank { null }
    }
}
