package com.meetily.mobile.data

import android.content.Context
import java.io.File

/**
 * Everything a meeting leaves on disk outside its own JSON file.
 *
 * Delete is the one affordance a user has for "make this go away", and it has
 * to mean it: a deleted meeting's photos, audio, waveform cache, attachments
 * and staged accuracy-check draft are all still user content, and the backup
 * writer zips whole directories rather than following references — so anything
 * missed here does not merely leak space, it is re-exported into every future
 * backup of a meeting the user believes is gone.
 *
 * Kept as one function so a future asset kind can only be added in one place.
 */
object MeetingAssets {

    fun deleteAll(context: Context, meeting: Meeting) {
        for (photo in meeting.photos) {
            PhotoStore.delete(context, photo)
        }
        for (attachment in meeting.attachmentsList) {
            AttachmentStore.delete(context, attachment.file)
        }
        // Shared clips are cut from the audio file and named after it, so
        // they go first, while the name is still in hand.
        com.meetily.mobile.export.ClipExporter.deleteFor(context, meeting.audioFile)
        // Also removes the .peaks waveform sidecar and the clear-voice copy
        // named after the recording.
        AudioStore.delete(context, meeting.audioFile)
        deleteStaleClearVoice(context, meeting.audioFile, meeting.clearVoiceFile)
        TranscriptDraft.delete(context, meeting.id)
        // Staged speaker names, left behind when the meeting is deleted
        // before its suggestions were reviewed.
        SpeakerSuggestions.delete(context, meeting.id)
        deletePassageLeftovers(context, meeting.id)
        // A long meeting shared as a file left the whole of it in the cache.
        com.meetily.mobile.export.MeetingShareFile.deleteFor(context, meeting.id)
    }

    /**
     * What a passage removal (PassageService) left of meeting [meetingId]
     * when the process died part-way through its swap: the marker naming
     * the recording and the file that was to replace it, in
     * PassageSwap.WORK_DIR. One of the two holds the passage. A meeting
     * that was not in the library when the leftovers were next looked at
     * keeps both (PassageSwap.Leftover.KEEP_BOTH), so deleting it for good
     * takes both, with their clips and clear-voice copies, and the marker.
     */
    fun deletePassageLeftovers(context: Context, meetingId: String) {
        val markerName = PassageSwap.markerName(meetingId)
        if (!SafeFiles.isPlainName(markerName)) return
        val marker = File(File(AudioStore.dir(context), PassageSwap.WORK_DIR), markerName)
        if (!marker.isFile) return
        val names = try {
            PassageSwap.markerNames(marker.readText())
        } catch (_: Exception) {
            null
        }
        if (names != null) {
            for (name in listOf(names.first, names.second)) {
                com.meetily.mobile.export.ClipExporter.deleteFor(context, name)
                AudioStore.delete(context, name)
            }
        }
        marker.delete()
    }

    /**
     * Deletes [clearVoiceFile] when it is a clear-voice copy that was not
     * made from [audioFile] (one AudioStore.delete would not find by name),
     * such as a copy left from an earlier recording. Only a copy's name is
     * ever taken, so stored data cannot point this at any other file.
     */
    fun deleteStaleClearVoice(context: Context, audioFile: String?, clearVoiceFile: String?) {
        if (clearVoiceFile.isNullOrBlank() || !ClearVoice.isCopyName(clearVoiceFile)) return
        if (ClearVoice.copyOf(audioFile, clearVoiceFile) != null) return
        AudioStore.fileFor(context, clearVoiceFile).delete()
    }
}
