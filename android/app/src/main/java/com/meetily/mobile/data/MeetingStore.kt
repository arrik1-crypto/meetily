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
        // Serialize once, outside the lock: Meeting is mutable and shared, so
        // building the JSON twice (as the fallback path used to) could put two
        // different snapshots into the two branches.
        AtomicJson.write(dir, "${meeting.id}.json", meeting.toJson().toString())
    }

    /**
     * Saves [meeting] after folding back any transcript segment that reached
     * disk stamped later than [segmentsSeenThroughMs] — the newest timestamp
     * the caller had when it last read from disk.
     *
     * Screens hold a Meeting for as long as they are open and write the whole
     * object back on every change, so a plain [save] from a screen can erase
     * a segment appended meanwhile by the recording service (see
     * RecordingService.appendLateSegment). Everything else on the screen's
     * copy still wins — it is the one carrying the user's edits, including
     * the lines it deliberately removed.
     */
    fun saveMerging(meeting: Meeting, segmentsSeenThroughMs: Long) {
        // Read-modify-write, so it has to exclude other writers for the whole
        // cycle — otherwise a segment that lands between the load and the save
        // is folded into nothing and then overwritten, which is the exact loss
        // this method exists to prevent.
        AtomicJson.exclusive {
            val onDisk = load(meeting.id)
            if (onDisk != null) {
                MeetingMerge.foldLateSegments(meeting, onDisk, segmentsSeenThroughMs)
            }
            save(meeting)
        }
    }

    /**
     * Saves only what a transcription pass owns — the segments, the audio
     * file and the model that produced them — onto whatever is on disk.
     *
     * An import holds one Meeting object for its whole run and writes the
     * entire thing back every batch, which for an hour of audio is many
     * minutes. The meeting is in the library from the start (its card shows
     * the import progress), so the user can open it and rename it, tag it,
     * star it or take notes while it runs — and the next batch write put all
     * of that back the way it was at construction, with no error and nothing
     * to undo.
     *
     * The write lock does NOT solve this on its own: a lost update is not a
     * torn file. The fix has to be about which side owns which field.
     */
    fun saveTranscription(meeting: Meeting) {
        AtomicJson.exclusive {
            val onDisk = load(meeting.id)
            if (onDisk == null) {
                save(meeting)
                return@exclusive
            }
            onDisk.segments.clear()
            onDisk.segments.addAll(meeting.segments)
            onDisk.audioFile = meeting.audioFile
            onDisk.transcriptModel = meeting.transcriptModel
            save(onDisk)
        }
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
