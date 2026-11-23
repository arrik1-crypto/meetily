package com.meetily.mobile

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.MeetingAssets
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.TranscriptDraft
import com.meetily.mobile.export.ClipExporter

/**
 * "Remove audio (keep transcript)": a meeting's recording goes, with its
 * waveform cache, its clear-voice copy and every clip cut from it, and
 * everything else stays — transcript, summary, notes, photos, attachments.
 *
 * Shared by the meeting's menu (MeetingDetailActivity, whose own copy also
 * drops the reference through its merge save), the Storage screen's largest
 * recordings, the home list's multi-select and the automatic clean-up rule,
 * so the safety checks and the confirmation's wording are the same
 * everywhere. A meeting with no transcript yet is not offered by
 * multi-select or the clean-up rule: its recording is all it holds.
 */
object AudioRemoval {

    /**
     * True when a recording, import, accuracy check or summary is writing
     * [meetingId]'s content right now: transcript, summary, notes or speaker
     * tags. An edit to them made meanwhile would be overwritten (recording
     * and import write the transcript back whole on every save). This is
     * Find & replace's rule (MeetingReplace).
     */
    fun isWritingContent(context: Context, meetingId: String): Boolean =
        (RecordingService.isRunning && MeetingStore(context).activeId() == meetingId) ||
            (ImportService.isRunning && ImportService.currentMeetingId == meetingId) ||
            (SummaryService.isRunning && SummaryService.currentMeetingId == meetingId)

    /**
     * [isWritingContent], or a clear-voice copy of [meetingId] being made.
     * Their next write would put the audio reference back (RecordingService
     * and ImportService write it on every save), and the copy reads the
     * recording and writes its own reference when it ends
     * (ClearVoiceService). The rule for removing the audio, deleting the
     * meeting and cleaning it up. The copy never touches the content, which
     * is why Find & replace asks [isWritingContent] instead.
     */
    fun isWriting(context: Context, meetingId: String): Boolean =
        isWritingContent(context, meetingId) || ClearVoiceService.isPreparing(meetingId)

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
     * True when the clear-voice copy of [meetingId] may not be deleted right
     * now: something is writing the meeting (an accuracy check running on
     * the copy among them), or a queued or interrupted check is to
     * transcribe the copy. Reads the job queue once.
     */
    fun copyInUse(context: Context, meetingId: String): Boolean =
        isWriting(context, meetingId) || JobQueue.load(context).any {
            it.kind == JobQueue.KIND_CHECK && it.meetingId == meetingId && it.clearVoice
        }

    /**
     * Asks before removing the audio of [count] meetings, freeing [bytes],
     * and says exactly what stops working. [skipped] selected meetings the
     * removal does not apply to are named as left out. [untranscribed] is
     * one meeting with no transcript yet, whose recording is all it holds:
     * the question then says so instead of promising a transcript is kept.
     * (Multi-select and the automatic clean-up leave such meetings out.)
     * [clearVoiceCopies] of the meetings have a clear-voice copy, which goes
     * with the recording and is counted in [bytes]; the question says so.
     * [onConfirm] runs on the main thread.
     */
    fun confirm(
        activity: Activity,
        count: Int,
        bytes: Long,
        skipped: Int = 0,
        untranscribed: Boolean = false,
        clearVoiceCopies: Int = 0,
        onConfirm: () -> Unit
    ) {
        val size = Formatter.formatShortFileSize(activity, bytes.coerceAtLeast(0L))
        val title = when {
            untranscribed && count == 1 -> activity.getString(R.string.storage_remove_audio_only_title)
            count == 1 && skipped == 0 -> activity.getString(R.string.storage_remove_audio_title)
            else -> activity.resources.getQuantityString(
                R.plurals.storage_remove_audio_title_many, count, count
            )
        }
        var message = activity.getString(
            if (untranscribed && count == 1) R.string.storage_remove_audio_only_message
            else R.string.storage_remove_audio_message,
            size
        )
        if (clearVoiceCopies > 0) {
            message += "\n\n" + activity.resources.getQuantityString(
                R.plurals.clearvoice_remove_audio_note, clearVoiceCopies, clearVoiceCopies
            )
        }
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
        var copy: String? = null
        // Written only when there is a reference to clear, so a meeting with
        // none keeps its file time (the clean-up's "changed recently").
        val saved = store.mutateIf(meetingId) { meeting ->
            val audio = meeting.audioFile
            if (audio.isNullOrBlank()) {
                false
            } else {
                name = audio
                copy = meeting.clearVoiceFile
                meeting.audioFile = null
                // The clear-voice copy is made from the recording and goes
                // with it (deleteFiles).
                meeting.clearVoiceFile = null
                true
            }
        }
        val removed = name
        if (!saved || removed.isNullOrBlank()) return -1L
        return deleteFiles(context, removed, copy)
    }

    /**
     * Deletes the audio file [name], its waveform sidecar, its clear-voice
     * copy and the clips cut from either. Returns the bytes the recording
     * and the copy held. [clearVoiceFile] is the copy the meeting named,
     * when it was not made from [name] (ClearVoice.copyOf): it goes too.
     * Call once nothing refers to [name] any more.
     */
    @WorkerThread
    fun deleteFiles(context: Context, name: String, clearVoiceFile: String? = null): Long {
        val bytes = AudioStore.bytesWithCopy(context, name)
        // Clips are named after the audio file, so they go while the name is
        // still in hand (see MeetingAssets.deleteAll).
        ClipExporter.deleteFor(context, name)
        // Also the copy named after it (AudioStore.delete).
        AudioStore.delete(context, name)
        MeetingAssets.deleteStaleClearVoice(context, name, clearVoiceFile)
        return bytes
    }
}
