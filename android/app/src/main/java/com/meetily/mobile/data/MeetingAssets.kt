package com.meetily.mobile.data

import android.content.Context

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
        // Also removes the .peaks waveform sidecar.
        AudioStore.delete(context, meeting.audioFile)
        TranscriptDraft.delete(context, meeting.id)
        // Staged speaker names, left behind when the meeting is deleted
        // before its suggestions were reviewed.
        SpeakerSuggestions.delete(context, meeting.id)
    }
}
