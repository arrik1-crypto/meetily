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
     * The newest file time in the library: each meeting's last save, and
     * the directory's own time, which a meeting written, moved out or
     * deleted sets. A stat per meeting, no reads. MeetingTrash dates a
     * deletion no earlier (Trash.stampFor).
     */
    internal fun newestFileTimeMs(): Long {
        var newest = dir.lastModified()
        for (file in dir.listFiles() ?: return newest) newest = maxOf(newest, file.lastModified())
        return newest
    }

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
     * [id] with only [fields] (plus "id") read and no word timings, for a
     * caller that needs a title, a path or a count and must not pay for the
     * transcript: one on the main thread, such as a service starting or a
     * receiver. Display-only, like [listPartial]: never save it. Null when
     * the file is missing or unreadable.
     */
    fun loadFields(id: String, fields: Set<String>): Meeting? {
        val file = fileFor(id) ?: return null
        if (!file.exists()) return null
        return try {
            fromFields(file.readText(), fields)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [HEADER_FIELDS] of [id], read from the start of the file only: its
     * title and date. Display-only; null when missing or unreadable.
     */
    fun loadHeader(id: String): Meeting? {
        val file = fileFor(id) ?: return null
        if (!file.exists()) return null
        return try {
            fromHeader(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [load] for a screen that shows the title and date first. [onHeader]
     * gets a display-only copy ([Meeting.loadedPartially]) with just
     * [HEADER_FIELDS], read from the start of the file before the full parse,
     * which a long meeting's word timings make the slow part. It is not
     * called when the file is missing or its start is unreadable.
     *
     * [cancelled] is asked before each stage, and a true answer ends the read
     * with null: a screen closed mid-read must not hold the worker every
     * other screen reads and saves on through a full parse nobody will see.
     * Blocking: call it on a worker thread.
     */
    fun loadStaged(
        id: String,
        onHeader: ((Meeting) -> Unit)?,
        cancelled: () -> Boolean = { false }
    ): Meeting? {
        val file = fileFor(id) ?: return null
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return null
        }
        if (cancelled()) return null
        if (onHeader != null) {
            val header = try {
                Meeting.fromJson(LightJson.parseLeading(text, HEADER_FIELDS))
                    .also { it.loadedPartially = true }
            } catch (_: Exception) {
                null
            }
            if (header != null) onHeader(header)
        }
        // The full parse is the slow part: seconds for a long meeting on a
        // low-end phone, split between the tokenizing and the building.
        if (cancelled()) return null
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        if (cancelled()) return null
        return try {
            Meeting.fromJson(json)
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
     * file, the model and language that produced them, and where an
     * unfinished pass stopped — onto whatever is on disk.
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
     *
     * A pass that continues an unfinished transcript passes its [seam]: the
     * lines before it are not the pass's to write back. They are shown and
     * editable for the whole run, so what is stored before the seam is
     * kept, with only the pass's speaker relabelling applied, and the
     * pass's own lines follow it (TranscriptResume.continued). Stored lines
     * past where an unfinished pass has got stay after them, without their
     * speaker numbers.
     */
    fun saveTranscription(meeting: Meeting, seam: TranscriptResume.Seam? = null): Boolean {
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
                HighlightMarks.apply(
                    meeting.segments,
                    TranscriptResume.marksReached(meeting.highlightMarksMs, meeting.transcriptResumeMs)
                )
                lastTranscribed = null
                return@exclusive save(meeting)
            }
            val segments = if (seam != null) {
                TranscriptResume.continued(
                    onDisk.segments, meeting.segments, seam.resumeMs, seam.baseMs, seam.relabel,
                    seam.reachedMs
                )
            } else {
                meeting.segments
            }
            // Read before the stored lines are replaced below.
            val marksSeamMs = TranscriptResume.placedBefore(onDisk.segments, seam?.resumeMs)
            onDisk.segments.clear()
            onDisk.segments.addAll(segments)
            onDisk.audioFile = meeting.audioFile
            onDisk.transcriptModel = meeting.transcriptModel
            // The pass's language travels with its words. Kept when this
            // pass has none (auto-detect never ran: no speech reached the
            // model), rather than wiping one an earlier pass recorded.
            meeting.language?.let { onDisk.language = it }
            onDisk.translated = meeting.translated
            // Where the words stop, while they do: the next pass carries on
            // from there (TranscriptResume). Null once the pass completes.
            onDisk.transcriptResumeMs = meeting.transcriptResumeMs
            // Highlights tapped while an audio-only recording had no lines to
            // star land on the lines this pass just produced; a mark past
            // where the words stop waits for the pass that reaches it. When a
            // timed line is kept before the seam, an earlier pass already
            // placed every mark before the seam on a kept line, and those are
            // the user's to edit, so a continuing pass leaves those marks
            // alone: a star the user removed there does not come back at
            // every batch. With no such line, the earlier pass starred
            // nothing that is kept, so this pass places them too
            // (TranscriptResume.placedBefore). A mark from the seam on that
            // comes before this pass's first line still resolves to the last
            // kept line, at every save of the run.
            HighlightMarks.apply(
                onDisk.segments,
                TranscriptResume.marksToPlace(
                    onDisk.highlightMarksMs, onDisk.transcriptResumeMs, marksSeamMs
                )
            )
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
            // Live transcription learns the language with its first chunk;
            // a capture-only session never does, and the pass that later
            // transcribes the audio records its own.
            if (snapshot.language != null) {
                onDisk.language = snapshot.language
                onDisk.translated = snapshot.translated
            }
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
     * [mutate] for a change that may already be in place: [block] returns
     * whether it changed anything, and the file is written only then. An
     * unchanged meeting keeps its file time, which the automatic clean-up
     * reads as "changed recently" (Retention.RECENT_MS).
     *
     * Returns false when the meeting is gone or the write failed, and true
     * otherwise, whether or not anything needed writing.
     */
    fun mutateIf(id: String, block: (Meeting) -> Boolean): Boolean = AtomicJson.exclusive {
        val target = load(id) ?: return@exclusive false
        if (!block(target)) return@exclusive true
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

    /**
     * Moves the meeting's file, whole and unchanged, to [target] in
     * Recently deleted (MeetingTrash), and remembers the id as [delete]
     * does, so a job still holding the meeting cannot write it back into
     * the library. False, with nothing changed, when there is no such
     * meeting or the move failed.
     */
    internal fun moveOut(id: String, target: File): Boolean {
        val file = fileFor(id) ?: return false
        return AtomicJson.exclusive {
            if (!file.isFile || !file.renameTo(target)) return@exclusive false
            tombstones.add(id)
            if (lastTranscribed?.id == id) lastTranscribed = null
            true
        }
    }

    /**
     * Puts [source], a file [moveOut] moved away, back as the meeting [id],
     * which may then be saved again. Its file time becomes now, which the
     * automatic clean-up reads as changed recently (Retention.RECENT_MS):
     * a meeting restored from Recently deleted is not taken again the next
     * day. False, with nothing changed, when a meeting with that id is in
     * the library already or the move failed.
     */
    internal fun moveIn(source: File, id: String): Boolean {
        val file = fileFor(id) ?: return false
        return AtomicJson.exclusive {
            if (file.exists() || !source.renameTo(file)) return@exclusive false
            tombstones.remove(id)
            if (lastTranscribed?.id == id) lastTranscribed = null
            file.setLastModified(System.currentTimeMillis())
            true
        }
    }

    // --- Active-recording marker, for crash recovery ---------------------
    // The recording service writes the in-progress meeting id here and saves
    // the meeting incrementally. If the process dies mid-recording the marker
    // survives, so the next launch knows a meeting was interrupted (its data
    // is already on disk from the incremental saves).

    private val activeMarker = File(dir, ".active")

    /** [liveShort]: see ActiveRecording. */
    fun markActive(id: String, liveShort: Boolean = false) {
        try {
            activeMarker.writeText(ActiveRecording(id, liveShort).format())
        } catch (_: Exception) {
        }
    }

    fun clearActive() {
        activeMarker.delete()
    }

    /**
     * [clearActive] while the marker still names [id] only: a session
     * started since has written its own.
     */
    fun clearActive(id: String) {
        if (activeId() == id) clearActive()
    }

    fun activeId(): String? = activeRecording()?.id

    fun activeRecording(): ActiveRecording? {
        if (!activeMarker.exists()) return null
        val text = try {
            activeMarker.readText()
        } catch (_: Exception) {
            ""
        }
        return ActiveRecording.parse(text)
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

        /** [loadFields] on a meeting file's [text]. Pure, so it is unit-tested. */
        internal fun fromFields(text: String, fields: Set<String>): Meeting =
            Meeting.fromJson(LightJson.parse(text, fields + "id", WORD_TIMINGS))
                .also { it.loadedPartially = true }

        /** [loadHeader] on a meeting file's [text]. Pure, so it is unit-tested. */
        internal fun fromHeader(text: String): Meeting =
            Meeting.fromJson(LightJson.parseLeading(text, HEADER_FIELDS))
                .also { it.loadedPartially = true }

        /**
         * What AiDisclaimers needs to decide the fixed notices of a summary
         * and its action items: the summary (for ones saved before the
         * template was recorded), the template and model that wrote it, and
         * those of the run that wrote the action items.
         */
        val NOTICE_FIELDS = setOf(
            "summary", "summaryTemplate", "summaryModel", "actionItemsTemplate", "actionItemsModel"
        )

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
