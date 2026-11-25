package com.meetily.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Keep meeting audio" off, in the default audio-only mode: the recording
 * has to be made so it can be transcribed, and was then kept for good. It
 * now goes once its transcript exists, and only then.
 */
class UnkeptAudioTest {

    @Test
    fun aMarkedRecordingGoesOnceItsTranscriptIsComplete() {
        assertTrue(
            UnkeptAudio.removesNow(marked = true, keepAudio = false, hasWords = true, unfinished = false, hasAudio = true)
        )
    }

    @Test
    fun aRecordingTheUserKeepsStays() {
        assertFalse(
            UnkeptAudio.removesNow(marked = false, keepAudio = false, hasWords = true, unfinished = false, hasAudio = true)
        )
    }

    @Test
    fun keepMeetingAudioTurnedBackOnBeforeTheTranscriptKeepsTheRecording() {
        // Marked when it was recorded with the switch off, transcribed hours
        // later (on the charger) after the user turned it back on: the
        // recording is not deleted against the switch as it now stands.
        assertFalse(
            UnkeptAudio.removesNow(marked = true, keepAudio = true, hasWords = true, unfinished = false, hasAudio = true)
        )
    }

    @Test
    fun aRecordingWithoutWordsStays() {
        // A pass that heard nothing: the recording is all the meeting has.
        assertFalse(
            UnkeptAudio.removesNow(marked = true, keepAudio = false, hasWords = false, unfinished = false, hasAudio = true)
        )
    }

    @Test
    fun anUnfinishedTranscriptKeepsItsRecording() {
        // Without the audio the rest could never be transcribed.
        assertFalse(
            UnkeptAudio.removesNow(marked = true, keepAudio = false, hasWords = true, unfinished = true, hasAudio = true)
        )
    }

    @Test
    fun nothingToRemoveIsNotARemoval() {
        assertFalse(
            UnkeptAudio.removesNow(marked = true, keepAudio = false, hasWords = true, unfinished = false, hasAudio = false)
        )
    }

    @Test
    fun aRecordingWithNoWordsGetsItsFirstTranscriptQueued() {
        // At Finish, on swipe-away and for a recording an interrupted
        // session left behind.
        assertTrue(
            RecordingService.wantsFirstTranscript(hasWords = false, audioExists = true, modelReady = true)
        )
        // Words already there, no audio (or an empty file), or no model to
        // run: nothing is queued.
        assertFalse(
            RecordingService.wantsFirstTranscript(hasWords = true, audioExists = true, modelReady = true)
        )
        assertFalse(
            RecordingService.wantsFirstTranscript(hasWords = false, audioExists = false, modelReady = true)
        )
        assertFalse(
            RecordingService.wantsFirstTranscript(hasWords = false, audioExists = true, modelReady = false)
        )
    }
}
