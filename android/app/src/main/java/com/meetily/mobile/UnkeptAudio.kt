package com.meetily.mobile

import android.content.Context
import androidx.annotation.WorkerThread
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.MeetingStore

/**
 * Recordings whose audio the user did not ask to keep ("Keep meeting audio"
 * off), but which had to be recorded all the same because their transcript
 * is made from it after the meeting: live transcription off (the default),
 * or a live model that did not load. Without this the switch did nothing in
 * the default mode, and every such recording was kept for good.
 *
 * The recording stays until that transcript exists. A first pass that ends
 * cleanly with words removes it, as Remove audio (keep transcript) would; a
 * pass that fails, is cancelled or stops part-way keeps it, so the meeting
 * can still be transcribed or continued. The switch as it stands when that
 * transcript is made decides: turned back on by then, the recording is kept.
 *
 * Only meeting ids are kept here, never anything of a meeting's content.
 */
object UnkeptAudio {

    private const val PREFS = "unkept_audio"
    private const val KEY_IDS = "ids"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Notes that [meetingId]'s recording is kept only until it is transcribed. */
    @Synchronized
    fun mark(context: Context, meetingId: String) {
        if (meetingId.isBlank()) return
        val ids = prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty()
        if (meetingId in ids) return
        prefs(context).edit().putStringSet(KEY_IDS, ids + meetingId).apply()
    }

    @Synchronized
    fun isMarked(context: Context, meetingId: String): Boolean =
        meetingId in prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty()

    @Synchronized
    private fun unmark(context: Context, meetingId: String) {
        val ids = prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty()
        if (meetingId !in ids) return
        prefs(context).edit().putStringSet(KEY_IDS, ids - meetingId).apply()
    }

    /**
     * Whether a meeting's recording goes now. Only a marked one, only while
     * "Keep meeting audio" is still off ([keepAudio] is the switch as it is
     * now, not as it was at recording time: turned back on before the
     * transcript was made, it keeps the recording), only once it has words,
     * and only when its transcript is complete: one that stopped part-way
     * ([unfinished]) still needs the audio to be finished. Pure, so it is
     * unit-tested.
     */
    fun removesNow(
        marked: Boolean,
        keepAudio: Boolean,
        hasWords: Boolean,
        unfinished: Boolean,
        hasAudio: Boolean
    ): Boolean = marked && !keepAudio && hasWords && !unfinished && hasAudio

    /**
     * Called when a first transcript of [meetingId] has finished cleanly
     * (ImportService). Removes the recording off the calling thread if the
     * meeting is marked; anything else leaves it alone.
     */
    fun afterFirstTranscript(context: Context, meetingId: String) {
        val app = context.applicationContext
        if (!isMarked(app, meetingId)) return
        Thread({
            try {
                removeNow(app, meetingId)
            } catch (_: Throwable) {
                // The recording simply stays, as it always did.
            }
        }, "unkept-audio").start()
    }

    @WorkerThread
    private fun removeNow(context: Context, meetingId: String) {
        // The transcript can run hours or days after the meeting (waiting
        // for a charger or for a model), and the user may have turned "Keep
        // meeting audio" back on meanwhile. Deleting the recording for good
        // against the switch as it now stands is not what they asked for:
        // the recording is kept, and no longer marked.
        val keepAudio = AppSettings(context).saveAudio
        if (keepAudio) {
            unmark(context, meetingId)
            return
        }
        var name: String? = null
        var copy: String? = null
        MeetingStore(context).mutateIf(meetingId) { meeting ->
            val audio = meeting.audioFile
            if (!removesNow(
                    marked = true,
                    keepAudio = keepAudio,
                    hasWords = meeting.segments.isNotEmpty(),
                    unfinished = meeting.transcriptResumeMs != null,
                    hasAudio = !audio.isNullOrBlank()
                )
            ) {
                false
            } else {
                name = audio
                copy = meeting.clearVoiceFile
                meeting.audioFile = null
                meeting.clearVoiceFile = null
                true
            }
        }
        val removed = name ?: return
        unmark(context, meetingId)
        MeetingPlayback.stopMeeting(meetingId)
        AudioRemoval.deleteFiles(context, removed, copy)
    }
}
