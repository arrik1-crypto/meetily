package com.meetily.mobile.export

import android.content.Context
import com.meetily.mobile.data.SafeFiles
import java.io.File

/**
 * The file a long meeting is shared as (MeetingDetailActivity.shareMeeting):
 * the whole meeting as text, title, attendees, summary, notes and the
 * transcript with speakers, in cache/shares, which the FileProvider serves
 * as its "shares" path.
 *
 * Only the share target needs it, and only while it reads the link, so it
 * does not stay: each share first deletes every earlier one ([write]),
 * Delete permanently and Remove a passage delete the meeting's
 * ([deleteFor], from MeetingAssets.deleteAll and PassageService), and the
 * app's start-up deletes any older than [MAX_AGE_MS] ([sweep]). Left alone,
 * Android trims the cache only when storage runs low, so a deleted
 * meeting's transcript stayed on the phone.
 *
 * Each share sits in a folder named after the meeting's id, so it can be
 * found again; the file keeps the title-based name the receiving app shows.
 */
object MeetingShareFile {

    /** Under the cache directory; file_paths.xml's cache-path "shares". */
    const val DIR = "shares"

    /** How long a share is left for its target to read at most. */
    const val MAX_AGE_MS = 60L * 60L * 1000L

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    /** Writes [text] for meeting [meetingId] titled [title]; earlier shares go first. */
    fun write(context: Context, meetingId: String, title: String, text: String): File =
        write(dir(context), meetingId, title, text)

    fun write(root: File, meetingId: String, title: String, text: String): File {
        clear(root)
        val folder = File(root, folderName(meetingId))
        folder.mkdirs()
        return File(folder, fileName(title)).apply { writeText(text) }
    }

    /** Deletes meeting [meetingId]'s share, if one is left. */
    fun deleteFor(context: Context, meetingId: String) = deleteFor(dir(context), meetingId)

    fun deleteFor(root: File, meetingId: String) {
        if (!SafeFiles.isPlainName(meetingId)) return
        val folder = File(root, meetingId)
        if (folder.exists()) folder.deleteRecursively()
    }

    /** Deletes every share older than [MAX_AGE_MS], those of earlier versions too. */
    fun sweep(context: Context, nowMs: Long = System.currentTimeMillis()) = sweep(dir(context), nowMs)

    fun sweep(root: File, nowMs: Long) {
        val entries = root.listFiles() ?: return
        for (entry in entries) {
            if (isStale(entry.lastModified(), nowMs)) entry.deleteRecursively()
        }
    }

    /**
     * True when a share written at [modifiedMs] is past its time at [nowMs].
     * An unknown time (0) is, and so is one far in the future, which only a
     * clock set back since can give.
     */
    fun isStale(modifiedMs: Long, nowMs: Long): Boolean =
        modifiedMs <= 0L || nowMs - modifiedMs >= MAX_AGE_MS || modifiedMs - nowMs >= MAX_AGE_MS

    /** The file's name, from the meeting's title, as the receiving app shows it. */
    fun fileName(title: String): String =
        title.take(60).replace(Regex("[^\\w\\-]+"), "_").ifBlank { "meeting" } + ".txt"

    private fun folderName(meetingId: String): String =
        if (SafeFiles.isPlainName(meetingId)) meetingId else "meeting"

    private fun clear(root: File) {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }
}
