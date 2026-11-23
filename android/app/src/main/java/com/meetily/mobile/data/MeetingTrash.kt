package com.meetily.mobile.data

import android.content.Context
import androidx.annotation.WorkerThread
import java.io.File

/**
 * Recently deleted: a meeting the user deletes is kept here for
 * [Trash.KEEP_DAYS] days, then erased for good by the daily clean-up run
 * (RetentionRunner.runAutomatic) or the check at app start (RecapApp),
 * whichever comes first. It can be restored until then, or erased sooner.
 *
 * The meeting's own file is moved, unchanged, out of filesDir/meetings into
 * filesDir/[DIR] (MeetingStore.moveOut, one rename under the store's write
 * lock); the date travels in its name (Trash). Its audio, clear-voice copy,
 * photos, attachments and staged drafts stay where they are until it is
 * restored or erased. A move rather than a flag on the meeting, so that no
 * reader has to remember to leave deleted meetings out: MeetingStore's
 * list, listLight, listPartial, load, loadStaged and exists only ever look
 * in filesDir/meetings, and every screen, search, job, reminder, digest,
 * export and backup reads meetings through them (BackupManager zips that
 * directory's own files, and leaves out the files deleted meetings name:
 * [held]). A job still holding the meeting cannot write it back
 * (MeetingStore's tombstones, as for a delete), and work queued for it is
 * dropped as it moves here.
 *
 * Erasing goes through one function ([erase]) for the daily purge, "Delete
 * permanently" and "Empty", and that removes the same files as deleting a
 * meeting always has (MeetingAssets.deleteAll). Everything here touches
 * files, so worker threads only, apart from [hasAny] and [ids], which only
 * list a small directory.
 */
class MeetingTrash(private val context: Context) {

    private val folder = Trash.Folder(File(context.filesDir, DIR))

    /** A meeting just moved here, and the queued work dropped with it, which [undo] puts back. */
    class Moved(val entry: Trash.Entry, val droppedJobs: List<JobQueue.Job>)

    /** One deleted meeting as Recently deleted shows it. */
    data class Item(
        val entry: Trash.Entry,
        /** Blank when its file could not be read. */
        val title: String,
        val createdAtMs: Long,
        val starred: Boolean,
        /** Its file and every file it names: what erasing it frees. */
        val bytes: Long,
        val daysLeft: Int,
        /**
         * When it was deleted, as shown: the date its days count from
         * (Trash.shownDate), or null when the clock cannot say.
         */
        val deletedOnMs: Long?
    )

    enum class Restore { RESTORED, IN_LIBRARY, GONE, FAILED }

    /** Whether anything is waiting here; a directory listing. */
    fun hasAny(): Boolean = folder.hasAny()

    /** The ids of the meetings here; a directory listing. */
    fun ids(): Set<String> = folder.all().mapTo(HashSet()) { it.id }

    /**
     * Moves meeting [id] here, dated [nowMs] or, when the clock is behind
     * what the library has already seen, that (Trash.stampFor), and drops
     * the work queued for it (an accuracy check, a summary, an import's
     * marker): it would only wait for a meeting that is not there, and an
     * import's marker would offer back work for a meeting the user deleted
     * (MainActivity.offerInterruptedWork, which also leaves a deleted
     * meeting's audio alone should the marker outlive the move). Null when
     * there is no such meeting or the move failed. The caller checks first
     * that nothing is writing the meeting (AudioRemoval.isWriting).
     */
    @WorkerThread
    fun moveToTrash(id: String, nowMs: Long = System.currentTimeMillis()): Moved? {
        val entry = synchronized(LOCK) {
            val known = newestKnownMs()
            val stamp = Trash.stampFor(nowMs, known)
            val name = Trash.fileName(id, stamp) ?: return null
            folder.dir.mkdirs()
            val older = folder.entriesFor(id)
            if (!MeetingStore(context).moveOut(id, File(folder.dir, name))) return null
            // The next move made with this instance (a selection, a clean-up
            // run) comes after this one, without listing the library again.
            knownMs = maxOf(known, stamp)
            // Never expected: a meeting is in the library or here, not both.
            // An older copy left here is only a file; the files it names are
            // the ones the meeting just moved here names.
            for (copy in older) folder.fileOf(copy).delete()
            Trash.Entry(id, stamp)
        }
        return Moved(entry, dropJobs(id))
    }

    /**
     * Read once per instance; see [newestKnownMs]. One instance serves one
     * delete, selection or clean-up run.
     */
    private var knownMs: Long? = null

    /**
     * The newest time the library is known to have reached: the newest file
     * time of a meeting or of the library directory (MeetingStore), and the
     * newest date and file time here (Trash.Folder.newestTimeMs). A clock
     * that is behind cannot have written them, so Trash.stampFor dates a
     * deletion no earlier. Stats only; read once per instance, so deleting
     * a selection does not list the library once per meeting. Caller holds
     * [LOCK].
     */
    private fun newestKnownMs(): Long = knownMs ?: maxOf(
        MeetingStore(context).newestFileTimeMs(), folder.newestTimeMs()
    ).also { knownMs = it }

    private fun dropJobs(id: String): List<JobQueue.Job> {
        val dropped = JobQueue.load(context).filter { it.meetingId == id }
        for (kind in dropped.mapTo(LinkedHashSet()) { it.kind }) JobQueue.dequeue(context, kind, id)
        return dropped
    }

    /**
     * Puts [entry]'s meeting back in the library, with its files as they
     * were. Its queued work stays dropped; [undo] is the exception.
     */
    @WorkerThread
    fun restore(entry: Trash.Entry): Restore {
        synchronized(LOCK) {
            val file = folder.fileOf(entry)
            if (!file.isFile) return Restore.GONE
            val store = MeetingStore(context)
            if (store.exists(entry.id)) return Restore.IN_LIBRARY
            return if (store.moveIn(file, entry.id)) Restore.RESTORED else Restore.FAILED
        }
    }

    /** Undoes [moved] straight after the delete: the meeting and its queued work come back. */
    @WorkerThread
    fun undo(moved: Moved): Restore {
        val result = restore(moved.entry)
        if (result == Restore.RESTORED) {
            for (job in moved.droppedJobs) JobQueue.enqueue(context, job)
        }
        return result
    }

    /** Erases [entries] for good; how many went. */
    @WorkerThread
    fun deleteForever(entries: Collection<Trash.Entry>): Int = synchronized(LOCK) {
        entries.count { erase(it) }
    }

    /**
     * Erases every meeting kept its [Trash.KEEP_DAYS] (Trash.isDue), and
     * dates any undated one (Trash.redated) as a deletion made now would be
     * (Trash.stampFor). Cheap when nothing is due: a directory listing and
     * a stat per meeting. Returns how many went.
     */
    @WorkerThread
    fun purgeDue(nowMs: Long = System.currentTimeMillis()): Int = synchronized(LOCK) {
        // Older copies of a meeting deleted twice (never expected): only the
        // file goes, since the files it names are the newer copy's too.
        for (copy in folder.stale()) folder.fileOf(copy).delete()
        var erased = 0
        for (entry in folder.entries()) {
            // The library is listed only when an entry needs a date.
            val date = if (entry.deletedAtMs < Trash.PLAUSIBLE_FROM_MS) {
                Trash.redated(entry.deletedAtMs, Trash.stampFor(nowMs, newestKnownMs()))
            } else {
                null
            }
            if (date != null) {
                folder.redate(entry, date)
                continue
            }
            val savedAt = folder.fileOf(entry).lastModified()
            if (Trash.isDue(entry.deletedAtMs, savedAt, nowMs) && erase(entry)) erased++
        }
        erased
    }

    /**
     * The one permanent delete. The meeting's files go exactly as deleting
     * it always removed them (MeetingAssets.deleteAll: photos, attachments,
     * shared clips, audio with its waveform cache and clear-voice copy, the
     * staged accuracy check, staged speaker names), then its own file. A
     * file that no longer parses names nothing that can be found, so only
     * it goes. Should the same meeting be back in the library (never
     * expected), those files are its files and stay. Caller holds [LOCK].
     */
    private fun erase(entry: Trash.Entry): Boolean {
        val file = folder.fileOf(entry)
        if (!file.isFile) return false
        val meeting = folder.read(entry)
        val store = MeetingStore(context)
        if (meeting != null && !store.exists(entry.id) && !store.exists(meeting.id)) {
            MeetingAssets.deleteAll(context, meeting)
        }
        return file.delete()
    }

    /**
     * Every meeting here, most recently deleted first, with its title, the
     * bytes erasing it frees and the days it has left. Reads each file
     * without its word timings.
     */
    @WorkerThread
    fun items(nowMs: Long = System.currentTimeMillis()): List<Item> {
        val store = MeetingStore(context)
        return folder.entries().map { entry ->
            val file = folder.fileOf(entry)
            val savedAt = file.lastModified()
            val meeting = folder.read(entry)
            val held = if (meeting != null && !store.exists(entry.id)) Trash.heldBy(meeting) else Trash.Held()
            Item(
                entry = entry,
                title = meeting?.title.orEmpty(),
                createdAtMs = meeting?.createdAtMs ?: 0L,
                starred = meeting?.starred == true,
                bytes = file.length() + bytesOf(held),
                daysLeft = Trash.daysLeft(entry.deletedAtMs, savedAt, nowMs),
                deletedOnMs = Trash.shownDate(entry.deletedAtMs, savedAt, nowMs)
            )
        }
    }

    private fun bytesOf(held: Trash.Held): Long =
        held.audio.sumOf { AudioStore.fileFor(context, it).length() } +
            held.photos.sumOf { PhotoStore.fileFor(context, it).length() } +
            held.attachments.sumOf { AttachmentStore.fileFor(context, it).length() }

    /**
     * The files the meetings here name, which a backup leaves out with
     * them. Not those of a meeting whose id is in the library too (never
     * expected): they are that meeting's files.
     */
    @WorkerThread
    fun held(): Trash.Held {
        val store = MeetingStore(context)
        var held = Trash.Held()
        for (entry in folder.all()) {
            if (store.exists(entry.id)) continue
            val meeting = folder.read(entry) ?: continue
            if (store.exists(meeting.id)) continue
            held += Trash.heldBy(meeting)
        }
        return held
    }

    /** What a backup restore must leave alone here; see Trash.RestoreGuard. */
    @WorkerThread
    fun restoreGuard(): Trash.RestoreGuard = synchronized(LOCK) { Trash.RestoreGuard(ids(), held()) }

    companion object {
        /** Under filesDir, beside "meetings". */
        const val DIR = "trash"

        /**
         * One move, restore or erase at a time, process-wide: a restore must
         * not find its files half erased by a purge running beside it.
         * Taken before the store's write lock, never inside it.
         */
        private val LOCK = Any()
    }
}
