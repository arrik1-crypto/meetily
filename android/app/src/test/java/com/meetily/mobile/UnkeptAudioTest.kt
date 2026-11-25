package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Keep meeting audio" off, in the default audio-only mode: the recording
 * has to be made so it can be transcribed, and was then kept for good. It
 * now goes once its transcript exists, and only then.
 */
class UnkeptAudioTest {

    private fun transcribed(
        words: Boolean = true,
        resumeMs: Long? = null,
        audio: String? = "rec.aac"
    ) = Meeting(id = "m", title = "Meeting", createdAtMs = 0L).apply {
        if (words) segments.add(TranscriptSegment(timestampMs = 0L, text = "hello", audioMs = 0L))
        transcriptResumeMs = resumeMs
        audioFile = audio
    }

    @Test
    fun aMarkedRecordingGoesOnceItsTranscriptIsComplete() {
        assertEquals(
            UnkeptAudio.Outcome.REMOVE,
            UnkeptAudio.decide(keepAudioNow = false, meeting = transcribed())
        )
    }

    @Test
    fun keepMeetingAudioTurnedBackOnBeforeTheTranscriptKeepsTheRecording() {
        // Marked when it was recorded with the switch off, transcribed hours
        // later (on the charger) after the user turned it back on: the
        // recording is kept for good and no longer marked, even though it
        // would otherwise go now.
        assertEquals(
            UnkeptAudio.Outcome.KEEP,
            UnkeptAudio.decide(keepAudioNow = true, meeting = transcribed())
        )
        // Decided from the switch alone, before the meeting is read.
        assertEquals(
            UnkeptAudio.Outcome.KEEP,
            UnkeptAudio.decide(keepAudioNow = true, meeting = null)
        )
    }

    @Test
    fun aRecordingWithoutWordsStays() {
        // A pass that heard nothing: the recording is all the meeting has.
        assertEquals(
            UnkeptAudio.Outcome.LEAVE,
            UnkeptAudio.decide(keepAudioNow = false, meeting = transcribed(words = false))
        )
    }

    @Test
    fun anUnfinishedTranscriptKeepsItsRecording() {
        // Without the audio the rest could never be transcribed.
        assertEquals(
            UnkeptAudio.Outcome.LEAVE,
            UnkeptAudio.decide(keepAudioNow = false, meeting = transcribed(resumeMs = 60_000L))
        )
    }

    @Test
    fun nothingToRemoveIsNotARemoval() {
        assertEquals(
            UnkeptAudio.Outcome.LEAVE,
            UnkeptAudio.decide(keepAudioNow = false, meeting = transcribed(audio = null))
        )
        // Deleted while its transcript ran.
        assertEquals(
            UnkeptAudio.Outcome.LEAVE,
            UnkeptAudio.decide(keepAudioNow = false, meeting = null)
        )
    }

    @Test
    fun aFirstTranscriptIsQueuedOnlyWithAudioAndAModel() {
        // At Finish, on swipe-away and for a recording an interrupted
        // session left behind: with no live words, or live words that stop
        // short of the meeting.
        assertTrue(RecordingService.wantsFirstTranscript(audioExists = true, modelReady = true))
        // No audio (or an empty file), or no model to run: nothing is queued.
        assertFalse(RecordingService.wantsFirstTranscript(audioExists = false, modelReady = true))
        assertFalse(RecordingService.wantsFirstTranscript(audioExists = true, modelReady = false))
    }
}
