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

    /**
     * Every meeting, newest first, WITHOUT word timings — which are most of a
     * transcribed meeting's bytes and nearly all of its parse cost. Enough for
     * anything that lists, counts, filters or searches the library.
     *
     * Reads the files themselves every time, so it can never be stale; it is
     * just cheaper. The copies are display-only ([Meeting.loadedPartially]):
     * load the meeting again before changing it.
     */
    fun listLight(): List<Meeting> = listParsed(keepTop = null)

    /**
     * Every meeting with only [fields] (plus "id") read, newest first when
     * "createdAtMs" is among them. For whole-library scans that need a few
     * fields — titles for series matching, action items for reminders — and
     * should not pay for transcripts at all. Display-only, like [listLight].
     */
    fun listPartial(fields: Set<String>): List<Meeting> = listParsed(keepTop = fields + "id")

    private fun listParsed(keepTop: Set<String>?): List<Meeting> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                Meeting.fromJson(LightJson.parse(file.readText(), keepTop, WORD_TIMINGS))
                    .also { it.loadedPartially = true }
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAtMs }
    }

    /** Whether [id] has a meeting file, without reading it. */
    fun exists(id: String): Boolean = fileFor(id)?.isFile == true

    /**
     * The file for [id], or null when the id is not a plain file name — which
     * only a crafted backup can produce, and which must never be joined onto
     * this directory (see SafeFiles).
     */
    private fun fileFor(id: String): File? =
        "$id.json".takeIf { SafeFiles.isPlainName(it) }?.let { File(dir, it) }

    fun load(id: String): Meeting? {
        val file = fileFor(id) ?: return null
        if (!file.exists()) return null
        return try {
            Meeting.fromJson(JSONObject(file.readText()))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes [meeting], returning false if nothing reached disk.
     *
     * The result used to be discarded. AtomicJson.write already reports
     * failure honestly; throwing that away meant a full disk or a revoked
     * directory looked identical to success, and callers went on to do the
     * irreversible half of their job — the accuracy-check apply deleted the
     * draft it had just failed to save, and the summary service announced a
     * summary that was never written.
     */
    fun save(meeting: Meeting): Boolean {
        // A light-listing copy is missing its word timings or more; writing it
        // back would erase them with no error. Callers load() before editing.
        if (meeting.loadedPartially) return false
        val file = fileFor(meeting.id) ?: return false
        // Serialize once, outside the lock: Meeting is mutable and shared, so
        // building the JSON twice (as the fallback path used to) could put two
        // different snapshots into the two branches.
        val json = meeting.toJson().toString()
        return AtomicJson.exclusive {
            if (meeting.id in tombstones) {
                // Deleted from the library while some job still held it. The
                // job's write would recreate the meeting with its audio and
                // photos already gone, so a missing file stays missing. A file
                // that is back (restored from a backup) is a live meeting again.
                if (!file.exists()) return@exclusive false
                tombstones.remove(meeting.id)
            }
            AtomicJson.write(dir, file.name, json)
        }
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
    fun saveMerging(meeting: Meeting, segmentsSeenThroughMs: Long): Boolean {
        // Read-modify-write, so it has to exclude other writers for the whole
        // cycle — otherwise a segment that lands between the load and the save
        // is folded into nothing and then overwritten, which is the exact loss
        // this method exists to prevent.
        return AtomicJson.exclusive {
            val onDisk = load(meeting.id)
            if (onDisk != null) {
                MeetingMerge.foldLateSegments(meeting, onDisk, segmentsSeenThroughMs)
            }
            save(meeting)
        }
    }

    /**
     * Writes a screen's edits onto the stored copy, and returns what is now
     * stored — see [MeetingMerge.mergeScreenEdits] for which side owns what.
     *
     * [base] is the copy the screen last received from here, [ours] its
     * current copy. Only the fields the screen changed since [base] are
     * written, so a screen that has been open for an hour cannot put back a
     * summary, an answer or an accepted transcript it never saw. With no
     * edits nothing is written, which makes this the screen's refresh too.
     *
     * Returns null when nothing usable came back: the meeting is gone and
     * the screen has nothing to write, or the write failed.
     */
    fun syncScreenCopy(base: Meeting, ours: Meeting, segmentsSeenThroughMs: Long): Meeting? {
        return AtomicJson.exclusive<Meeting?> {
            val onDisk = load(ours.id)
            if (onDisk == null) {
                // Unreadable or deleted underneath. Writing the screen's copy
                // is what saving from a screen has always done here.
                return@exclusive if (ours != base && save(ours)) ours else null
            }
            val merged = MeetingMerge.mergeScreenEdits(base, ours, onDisk, segmentsSeenThroughMs)
            if (merged != onDisk && !save(merged)) return@exclusive null
            merged
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
    fun saveTranscription(meeting: Meeting): Boolean {
        return AtomicJson.exclusive {
            val file = fileFor(meeting.id) ?: return@exclusive false
            // The reload is what protects the user's edits, but an import
            // persists after every chunk, and re-reading and re-parsing the
            // whole growing file each time made a long import's IO grow with
            // the square of its length. When nothing but this method has
            // written the file since its last call, the copy it wrote IS
            // what is on disk. The write generation is the authority (it sees
            // same-second writes that a timestamp cannot); mtime and length
            // additionally catch a restore, which replaces the file directly.
            val base = lastTranscribed?.takeIf {
                it.id == meeting.id &&
                    it.generation == AtomicJson.generation(file) &&
                    it.modifiedMs == file.lastModified() &&
                    it.length == file.length()
            }
            val onDisk = base?.meeting ?: load(meeting.id)
            if (onDisk == null) {
                HighlightMarks.apply(meeting.segments, meeting.highlightMarksMs)
                lastTranscribed = null
                return@exclusive save(meeting)
            }
            onDisk.segments.clear()
            onDisk.segments.addAll(meeting.segments)
            onDisk.audioFile = meeting.audioFile
            onDisk.transcriptModel = meeting.transcriptModel
            // Highlights tapped while an audio-only recording had no lines to
            // star land on the lines this pass just produced.
            HighlightMarks.apply(onDisk.segments, onDisk.highlightMarksMs)
            val saved = save(onDisk)
            lastTranscribed = if (saved) {
                TranscriptionBase(
                    meeting.id, AtomicJson.generation(file),
                    file.lastModified(), file.length(), onDisk
                )
            } else {
                null
            }
            saved
        }
    }

    /**
     * The live recording's periodic snapshot, merged onto the stored copy.
     *
     * The meeting is in the library while it records, and can be starred,
     * tagged, summarised or given a photo or attachment from there. A plain
     * [save] of the service's snapshot reset all of that every few seconds,
     * because the service only knows its own fields. So only what the
     * recording owns is written: the transcript, audio file, model and
     * highlight marks always; title, notes and attendees only when they were
     * changed through the recording screen since the last snapshot. Photos
     * are a union, so one added from the meeting screen keeps its reference.
     */
    fun saveRecordingSnapshot(
        snapshot: Meeting,
        withTitle: Boolean,
        withNotes: Boolean,
        withAttendees: Boolean
    ): Boolean {
        return AtomicJson.exclusive {
            val onDisk = load(snapshot.id) ?: return@exclusive save(snapshot)
            onDisk.segments.clear()
            onDisk.segments.addAll(snapshot.segments)
            onDisk.audioFile = snapshot.audioFile
            onDisk.transcriptModel = snapshot.transcriptModel
            onDisk.highlightMarksMs.clear()
            onDisk.highlightMarksMs.addAll(snapshot.highlightMarksMs)
            if (withTitle) onDisk.title = snapshot.title
            if (withNotes) onDisk.notes = snapshot.notes
            if (withAttendees) onDisk.attendees = snapshot.attendees.toMutableList()
            for (photo in snapshot.photos) {
                if (photo !in onDisk.photos) onDisk.photos.add(photo)
            }
            save(onDisk)
        }
    }

    /**
     * Read-modify-write against the stored copy, under the write lock.
     *
     * For background work that owns one field and must not care what a screen
     * did to the rest meanwhile: [block] receives the meeting as it is on
     * disk right now, mutates it, and the result is written before any other
     * writer gets in. Returns false when the meeting is gone.
     *
     * Doing this as load-then-save from the caller looks identical and is
     * not: two of those interleave into a lost update, which is exactly how
     * an answer generated over two minutes lands on top of a rename.
     */
    fun mutate(id: String, block: (Meeting) -> Unit): Boolean = AtomicJson.exclusive {
        val target = load(id) ?: return@exclusive false
        block(target)
        save(target)
    }

    /**
     * Removes the meeting file and remembers the id for the life of the
     * process, so a job still holding the meeting (a summary, an import
     * batch, a staged accuracy check) cannot write it back. Ids are random
     * UUIDs, so a remembered one is never legitimately reused.
     */
    fun delete(id: String) {
        val file = fileFor(id) ?: return
        AtomicJson.exclusive {
            tombstones.add(id)
            if (lastTranscribed?.id == id) lastTranscribed = null
            file.delete()
        }
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

    /** What saveTranscription last wrote, so the next batch can skip the reload. */
    private class TranscriptionBase(
        val id: String,
        val generation: Long,
        val modifiedMs: Long,
        val length: Long,
        val meeting: Meeting
    )

    companion object {
        /** Word timings: the member the light listings leave out. */
        private val WORD_TIMINGS = setOf("words")

        /** Enough to name and date a meeting (and to match it to a series). */
        val HEADER_FIELDS = setOf("id", "title", "createdAtMs")

        /** [HEADER_FIELDS] plus the action items, for follow-ups and reminders. */
        val ACTION_FIELDS = HEADER_FIELDS + "actionItems"

        // Process-wide, like the write lock that guards both: every caller
        // builds its own MeetingStore over the same directory.
        private val tombstones = HashSet<String>()
        private var lastTranscribed: TranscriptionBase? = null

        /** True when [id] was deleted from the library during this process. */
        fun wasDeleted(id: String): Boolean = AtomicJson.exclusive { id in tombstones }

        /**
         * Forgets a deletion because the meeting has come back on purpose —
         * a backup restore just put its file in place.
         */
        fun forgetDeleted(id: String) {
            AtomicJson.exclusive {
                tombstones.remove(id)
                if (lastTranscribed?.id == id) lastTranscribed = null
            }
        }
    }
}
