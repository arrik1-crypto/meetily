package com.meetily.mobile.data

import org.json.JSONObject
import java.io.File

/**
 * Recently deleted: how long a deleted meeting is kept, when it is due to be
 * erased, and how the trash directory names its files. Pure apart from
 * [Folder]'s file reads, so it is unit-tested; MeetingTrash does the rest.
 *
 * A meeting the user deletes is moved whole out of the library directory
 * (MeetingStore) into the trash directory, where its file is named
 * `<deletedAtMs>-<id>.json`. One rename carries both facts, so there is no
 * moment at which a meeting is in the trash without its date. Its audio,
 * clear-voice copy, photos, attachments and staged drafts stay where they
 * are, under their own names, until the meeting is restored or erased.
 *
 * Android's MediaStore names its own trashed files the same way
 * (`.trashed-<time>-<name>`).
 */
object Trash {

    /** How long a deleted meeting can be restored. */
    const val KEEP_DAYS = 30

    const val DAY_MS = 24L * 60 * 60 * 1000

    const val KEEP_MS = KEEP_DAYS * DAY_MS

    /**
     * 1 January 2025, UTC. No copy of the app could delete a meeting before
     * then, so a deletion dated earlier was made under a clock that was
     * wrong (a phone that lost its time and had not synced it yet), and its
     * date says nothing about how long the meeting has been in the trash.
     */
    const val PLAUSIBLE_FROM_MS = 1_735_689_600_000L

    private const val SUFFIX = ".json"

    /** Longest decimal Long: anything longer is not a date this wrote. */
    private const val MAX_STAMP_DIGITS = 19

    /** One deleted meeting: its id and when it was deleted. */
    data class Entry(val id: String, val deletedAtMs: Long) {
        /** Its file in the trash directory; see [parse]. */
        val fileName: String get() = "$deletedAtMs-$id$SUFFIX"
    }

    /** [id] deleted at [deletedAtMs] as a trash file name, or null when either cannot be one. */
    fun fileName(id: String, deletedAtMs: Long): String? {
        if (deletedAtMs < 0L || !SafeFiles.isPlainName(id)) return null
        return Entry(id, deletedAtMs).fileName.takeIf { SafeFiles.isPlainName(it) }
    }

    /**
     * The entry a trash file name stands for, or null for anything else.
     * The date is digits only, so the first '-' ends it, and the id (a UUID,
     * itself full of dashes) is everything after it.
     */
    fun parse(fileName: String): Entry? {
        if (!fileName.endsWith(SUFFIX)) return null
        val dash = fileName.indexOf('-')
        if (dash <= 0 || dash > MAX_STAMP_DIGITS) return null
        val stamp = fileName.substring(0, dash)
        if (stamp.any { it !in '0'..'9' }) return null
        val deletedAtMs = stamp.toLongOrNull() ?: return null
        val id = fileName.substring(dash + 1, fileName.length - SUFFIX.length)
        if (!SafeFiles.isPlainName(id)) return null
        return Entry(id, deletedAtMs)
    }

    /**
     * When an entry's [KEEP_DAYS] count from, as seen at [nowMs].
     *
     * Erasing is the one thing here that cannot be taken back, so every
     * doubt about the clock makes the count start later, never earlier:
     *  - a deletion dated before [PLAUSIBLE_FROM_MS] is undated, and counts
     *    from now until [redated] writes a real date for it;
     *  - a meeting cannot have been deleted before its file was last
     *    written ([savedAtMs], which the move keeps), so a clock that ran
     *    behind when it was deleted counts from the later of the two;
     *  - a date still in the future (the clock was ahead and has been put
     *    back) counts from now, so the screen never shows more than
     *    [KEEP_DAYS] days left. Not written back: if it is now the clock
     *    that is behind, the true date must still be there once it is right.
     */
    fun countsFrom(deletedAtMs: Long, savedAtMs: Long, nowMs: Long): Long {
        if (deletedAtMs < PLAUSIBLE_FROM_MS) return nowMs
        val known = if (savedAtMs >= PLAUSIBLE_FROM_MS) maxOf(deletedAtMs, savedAtMs) else deletedAtMs
        return minOf(known, nowMs)
    }

    /** True once an entry has been kept its [KEEP_DAYS] and may be erased. */
    fun isDue(deletedAtMs: Long, savedAtMs: Long, nowMs: Long): Boolean =
        nowMs - countsFrom(deletedAtMs, savedAtMs, nowMs) >= KEEP_MS

    /**
     * Whole days left before an entry is erased, from [KEEP_DAYS] down to 0,
     * rounded up: one deleted a minute ago has 30 days left, one due within
     * the day has 1, one already due has 0.
     */
    fun daysLeft(deletedAtMs: Long, savedAtMs: Long, nowMs: Long): Int {
        val left = countsFrom(deletedAtMs, savedAtMs, nowMs) + KEEP_MS - nowMs
        if (left <= 0L) return 0
        return ((left + DAY_MS - 1) / DAY_MS).toInt().coerceAtMost(KEEP_DAYS)
    }

    /**
     * The date to write back for an undated entry (see [countsFrom]) once
     * the clock is plausible: now. Null when the entry needs none. Only
     * ever moves a date later, which can only keep a meeting longer.
     */
    fun redated(deletedAtMs: Long, nowMs: Long): Long? =
        if (deletedAtMs < PLAUSIBLE_FROM_MS && nowMs >= PLAUSIBLE_FROM_MS) nowMs else null

    /**
     * [entries] with one per meeting, most recently deleted first, and the
     * rest. A meeting is only ever in the library or in the trash once, so
     * the rest is normally empty; should a crash ever leave two copies, the
     * newer is the one shown, and the older is only a file to drop: its
     * audio and photos are the newer one's too.
     */
    fun newestPerId(entries: List<Entry>): Pair<List<Entry>, List<Entry>> {
        val sorted = entries.sortedWith(
            compareByDescending<Entry> { it.deletedAtMs }.thenBy { it.id }
        )
        val seen = HashSet<String>()
        val (kept, stale) = sorted.partition { seen.add(it.id) }
        return kept to stale
    }

    /**
     * True when the automatic clean-up [rule] would take this meeting again
     * after a restore: it deletes old meetings, this one is old enough and
     * not starred. A restore gives a week's grace (Retention.RECENT_MS);
     * the restore says so, and that starring it keeps it.
     */
    fun cleanUpWouldRetake(rule: Retention.Rule, createdAtMs: Long, starred: Boolean, nowMs: Long): Boolean =
        rule.enabled && rule.mode == Retention.Mode.MEETINGS && !starred &&
            createdAtMs in 1 until nowMs - rule.days * Retention.DAY_MS

    /** Files of deleted meetings, by the directory each lives in, that backups leave out. */
    data class Held(
        val audio: Set<String> = emptySet(),
        val photos: Set<String> = emptySet(),
        val attachments: Set<String> = emptySet()
    ) {
        operator fun plus(other: Held): Held =
            Held(audio + other.audio, photos + other.photos, attachments + other.attachments)
    }

    /**
     * The files [meeting] names: its recording with the waveform cache and
     * clear-voice copy named after it, any other copy it names, its photos
     * and its attachments. Names that are not plain names are left out:
     * nothing here may reach outside a store's directory.
     */
    fun heldBy(meeting: Meeting): Held {
        val audio = HashSet<String>()
        meeting.audioFile?.takeIf { SafeFiles.isPlainName(it) }?.let { name ->
            audio.add(name)
            audio.add("$name.peaks")
            if (!ClearVoice.isCopyName(name)) audio.add(ClearVoice.copyNameFor(name))
        }
        meeting.clearVoiceFile?.takeIf { SafeFiles.isPlainName(it) }?.let { audio.add(it) }
        return Held(
            audio = audio,
            photos = meeting.photos.filterTo(HashSet()) { SafeFiles.isPlainName(it) },
            attachments = meeting.attachmentsList.map { it.file }.filterTo(HashSet()) { SafeFiles.isPlainName(it) }
        )
    }

    /** The top-level members [heldBy] reads, with the header for showing an entry. */
    val ENTRY_FIELDS = setOf(
        "id", "title", "createdAtMs", "starred", "audioFile", "clearVoiceFile", "photos", "attachments"
    )

    /**
     * What a backup restore leaves alone so that Recently deleted is not
     * changed behind the user's back: a meeting in it is not restored over
     * or brought back (its id is skipped and counted), and neither are the
     * files it names, nor the ones the backup's own copy of it names, which
     * would otherwise land as files no meeting refers to. A backup writes
     * its meetings before their files, so a skipped copy's names are known
     * before its files come up. Pure; used by BackupManager.
     */
    class RestoreGuard(private val trashedIds: Set<String>, held: Held) {
        private val audio = HashSet(held.audio)
        private val photos = HashSet(held.photos)
        private val attachments = HashSet(held.attachments)

        /** Meetings of the backup left out because they are in Recently deleted. */
        var leftInTrash = 0
            private set

        /** True when the backup's `<id>.json` names a meeting in Recently deleted. */
        fun isTrashed(meetingFileName: String): Boolean =
            meetingFileName.endsWith(SUFFIX) && meetingFileName.removeSuffix(SUFFIX) in trashedIds

        /** Records a skipped meeting; [backupCopy] is the backup's copy when it could be read. */
        fun skipped(backupCopy: Meeting?) {
            leftInTrash++
            if (backupCopy == null) return
            val held = heldBy(backupCopy)
            audio.addAll(held.audio)
            photos.addAll(held.photos)
            attachments.addAll(held.attachments)
        }

        fun keepsAudio(name: String): Boolean = name in audio
        fun keepsPhoto(name: String): Boolean = name in photos
        fun keepsAttachment(name: String): Boolean = name in attachments
    }

    /**
     * The trash directory: listing it, and reading or renaming one entry's
     * file. Only MeetingTrash creates one, so this is the only code that
     * opens the directory; everything that reads the library goes through
     * MeetingStore, which never looks here.
     */
    class Folder(val dir: File) {

        /** Every readable entry name in the directory, in no order. */
        fun all(): List<Entry> = dir.list()?.mapNotNull { parse(it) } ?: emptyList()

        /** One entry per meeting, most recently deleted first; see [newestPerId]. */
        fun entries(): List<Entry> = newestPerId(all()).first

        /** Older copies of a meeting that has a newer entry; see [newestPerId]. */
        fun stale(): List<Entry> = newestPerId(all()).second

        fun entriesFor(id: String): List<Entry> = all().filter { it.id == id }

        fun hasAny(): Boolean = dir.list()?.any { parse(it) != null } == true

        fun fileOf(entry: Entry): File = SafeFiles.child(dir, entry.fileName)

        /** Renames [entry] to be dated [deletedAtMs]; the new entry, or null when that failed. */
        fun redate(entry: Entry, deletedAtMs: Long): Entry? {
            val next = Entry(entry.id, deletedAtMs)
            if (next == entry) return entry
            val target = fileOf(next)
            if (target.exists()) return null
            return if (fileOf(entry).renameTo(target)) next else null
        }

        /**
         * [entry]'s meeting with only [fields] read (all of them when null),
         * never its word timings; display-only. Null when it is gone or does
         * not parse.
         */
        fun read(entry: Entry, fields: Set<String>? = ENTRY_FIELDS): Meeting? = try {
            val text = fileOf(entry).readText()
            Meeting.fromJson(
                if (fields == null) JSONObject(text) else LightJson.parse(text, fields + "id", WORDS)
            ).also { it.loadedPartially = true }
        } catch (_: Exception) {
            null
        }

        private companion object {
            val WORDS = setOf("words")
        }
    }
}
