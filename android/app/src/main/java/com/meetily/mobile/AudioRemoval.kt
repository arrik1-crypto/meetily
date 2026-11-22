package com.meetily.mobile

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.TranscriptDraft
import com.meetily.mobile.export.ClipExporter

/**
 * "Remove audio (keep transcript)": a meeting's recording goes, with its
 * waveform cache and every clip cut from it, and everything else stays —
 * transcript, summary, notes, photos, attachments.
 *
 * Shared by the meeting's menu (MeetingDetailActivity, which saves through
 * its own merge path), the Storage screen's largest recordings, the home
 * list's multi-select and the automatic clean-up rule, so the safety checks
 * and the confirmation's wording are the same everywhere.
 */
object AudioRemoval {

    /**
     * True when a recording, import, accuracy check or summary is writing
     * [meetingId] right now. Their next write would put the audio reference
     * back (RecordingService and ImportService write it on every save).
     */
    fun isWriting(context: Context, meetingId: String): Boolean =
        (RecordingService.isRunning && MeetingStore(context).activeId() == meetingId) ||
            (ImportService.isRunning && ImportService.currentMeetingId == meetingId) ||
            (SummaryService.isRunning && SummaryService.currentMeetingId == meetingId)

    /**
     * Meetings whose audio something is still waiting for: a queued or
     * interrupted accuracy check or import (both read the audio), and a
     * staged accuracy check not yet reviewed (TranscriptCheckActivity plays
     * the audio beside the comparison). Reads the job queue once.
     */
    fun waitingForAudio(context: Context, meetingIds: Collection<String>): Set<String> {
        val jobs = JobQueue.load(context)
        val waiting = HashSet<String>()
        for (job in jobs) {
            if (job.meetingId.isNotBlank() &&
                (job.kind == JobQueue.KIND_CHECK || job.kind == JobQueue.KIND_IMPORT)
            ) {
                waiting.add(job.meetingId)
            }
        }
        for (id in meetingIds) {
            if (TranscriptDraft.isPending(context, id)) waiting.add(id)
        }
        return waiting.filterTo(HashSet()) { it in meetingIds }
    }

    /** True when the audio of [meetingId] may not be removed right now. */
    fun isBlocked(context: Context, meetingId: String): Boolean =
        isWriting(context, meetingId) || meetingId in waitingForAudio(context, listOf(meetingId))

    /**
     * Asks before removing the audio of [count] meetings, freeing [bytes],
     * and says exactly what stops working. [skipped] selected meetings the
     * removal does not apply to are named as left out. [onConfirm] runs on
     * the main thread.
     */
    fun confirm(
        activity: Activity,
        count: Int,
        bytes: Long,
        skipped: Int = 0,
        onConfirm: () -> Unit
    ) {
        val title = if (count == 1 && skipped == 0) {
            activity.getString(R.string.storage_remove_audio_title)
        } else {
            activity.resources.getQuantityString(
                R.plurals.storage_remove_audio_title_many, count, count
            )
        }
        var message = activity.getString(
            R.string.storage_remove_audio_message,
            Formatter.formatShortFileSize(activity, bytes.coerceAtLeast(0L))
        )
        if (skipped > 0) {
            message += "\n\n" + activity.resources.getQuantityString(
                R.plurals.storage_bulk_skipped, skipped, skipped
            )
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.storage_remove_audio_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    /**
     * Removes the audio of [meetingId] through the store's write lock, then
     * its files. Returns the bytes freed, or -1 when nothing was removed:
     * the meeting is gone, has no audio, or became busy since it was
     * offered. Worker threads only.
     *
     * The reference goes first: a meeting saved without it and files that
     * outlive it (a process death in between) only leaks space, while files
     * deleted under a reference that failed to save would leave a meeting
     * pointing at audio that is not there.
     */
    @WorkerThread
    fun remove(context: Context, store: MeetingStore, meetingId: String): Long {
        if (isBlocked(context, meetingId)) return -1L
        var name: String? = null
        val saved = store.mutate(meetingId) { meeting ->
            name = meeting.audioFile
            meeting.audioFile = null
        }
        val removed = name
        if (!saved || removed.isNullOrBlank()) return -1L
        return deleteFiles(context, removed)
    }

    /**
     * Deletes the audio file [name], its waveform sidecar and the clips cut
     * from it. Returns the bytes the audio file held. Call once nothing
     * refers to [name] any more.
     */
    @WorkerThread
    fun deleteFiles(context: Context, name: String): Long {
        val bytes = AudioStore.fileFor(context, name).length()
        // Clips are named after the audio file, so they go while the name is
        // still in hand (see MeetingAssets.deleteAll).
        ClipExporter.deleteFor(context, name)
        AudioStore.delete(context, name)
        return bytes
    }
}
