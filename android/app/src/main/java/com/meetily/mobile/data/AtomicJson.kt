package com.meetily.mobile.data

import java.io.File

/**
 * Write-a-JSON-file-or-leave-the-old-one-alone, for the meeting store.
 *
 * Split out of MeetingStore so it can be hammered by a real unit test: the
 * store itself needs a Context for filesDir, this needs a directory.
 *
 * The store used to write `<id>.json.tmp` and rename it, which is atomic
 * against READERS but not against other writers. The temp path is derived
 * from the meeting id, so every thread saving the SAME meeting used the same
 * temp file, and writeText truncates. Two overlapping saves could therefore
 * rename a half-written blob over a good meeting, and a meeting that no
 * longer parses is dropped by MeetingStore.list() — it disappears from the
 * library with no error anywhere.
 *
 * That is not hypothetical. During a recording, RecordingService's
 * "meeting-save" executor writes the whole meeting every 2.5 seconds while
 * appendLateSegment writes the same meeting from its own raw thread, and
 * those two collide exactly when a recording ends with chunks still
 * transcribing. SummaryService and the meeting screen add two more writers.
 */
internal object AtomicJson {

    /**
     * One lock for every writer in the process. It has to be static: each
     * caller builds its own MeetingStore over the same directory, so an
     * instance field would guard nothing.
     *
     * Coarser than a per-file lock, and deliberately so — these are small
     * JSON files written a few times a second at worst, and the contended
     * window is one write plus one rename. A per-id lock map would need its
     * own eviction and buys nothing measurable here.
     */
    private val lock = Any()

    /**
     * Replaces [name] in [dir] with [json]. Returns false if the content
     * could not be written at all; the previous file is then untouched.
     */
    fun write(dir: File, name: String, json: String): Boolean = synchronized(lock) {
        val file = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.writeText(json)
        } catch (_: Exception) {
            tmp.delete()
            return false
        }
        if (tmp.renameTo(file)) return true
        // Rename failed (it does on some devices when the target exists).
        // Writing in place is not atomic, so a crash mid-write loses the
        // meeting — but refusing to save loses it for certain.
        return try {
            file.writeText(json)
            true
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    /**
     * Runs [block] with the write lock held, so a read-modify-write cycle
     * cannot interleave with another one. See MeetingStore.saveMerging.
     */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }
}
