package com.meetily.mobile

import com.meetily.mobile.RecordingService.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a screen reader is told, and when: progress at milestones rather
 * than every tick, recording state changes rather than per-utterance
 * churn, and turn-to-turn jumps on the speaker strip.
 */
class A11yAnnounceTest {

    // --- ProgressAnnouncer -------------------------------------------------

    @Test
    fun progressSpeaksFirstUpdateThenEachQuarter() {
        val a = ProgressAnnouncer()
        val spoken = (0..100).filter { a.shouldAnnounce(it) }
        assertEquals(listOf(0, 25, 50, 75, 100), spoken)
    }

    @Test
    fun progressJoinedMidRunSpeaksOnceThenMilestones() {
        val a = ProgressAnnouncer()
        assertTrue(a.shouldAnnounce(41))
        assertFalse(a.shouldAnnounce(42))
        assertFalse(a.shouldAnnounce(49))
        assertTrue(a.shouldAnnounce(50))
    }

    @Test
    fun progressRepeatsAndTicksStayQuiet() {
        val a = ProgressAnnouncer()
        assertTrue(a.shouldAnnounce(10))
        repeat(5) { assertFalse(a.shouldAnnounce(10)) }
        assertFalse(a.shouldAnnounce(24))
    }

    @Test
    fun indeterminateStageIsSpokenOnEntryOnly() {
        val a = ProgressAnnouncer()
        assertTrue(a.shouldAnnounce(-1))
        assertFalse(a.shouldAnnounce(-1))
        // Into a measured stage, and back to an unmeasured one.
        assertTrue(a.shouldAnnounce(30))
        assertTrue(a.shouldAnnounce(-1))
        assertFalse(a.shouldAnnounce(-1))
    }

    @Test
    fun resetMakesTheNextRunsFirstUpdateSpoken() {
        val a = ProgressAnnouncer()
        assertTrue(a.shouldAnnounce(0))
        a.reset()
        assertTrue(a.shouldAnnounce(0))
    }

    @Test
    fun outOfRangePercentsAreClamped() {
        val a = ProgressAnnouncer()
        assertTrue(a.shouldAnnounce(150))
        assertFalse(a.shouldAnnounce(100))
    }

    @Test
    fun customStep() {
        val a = ProgressAnnouncer(stepPercent = 10)
        assertEquals(11, (0..100).count { a.shouldAnnounce(it) })
    }

    // --- RecordingStatusSpeech ---------------------------------------------

    @Test
    fun startingIsQuietAndGoingLiveIsSpoken() {
        val s = RecordingStatusSpeech()
        assertFalse(s.shouldAnnounce(Status.STARTING, "Starting…"))
        assertTrue(s.shouldAnnounce(Status.LISTENING, "Listening"))
    }

    @Test
    fun perUtteranceChurnIsQuiet() {
        val s = RecordingStatusSpeech()
        s.shouldAnnounce(Status.STARTING, "Starting…")
        s.shouldAnnounce(Status.LISTENING, "Listening")
        repeat(3) {
            assertFalse(s.shouldAnnounce(Status.HEARING, "Hearing speech"))
            assertFalse(s.shouldAnnounce(Status.PROCESSING, "Processing…"))
            assertFalse(s.shouldAnnounce(Status.LISTENING, "Listening"))
        }
    }

    @Test
    fun modelLoadIsSpokenThenListeningAfterIt() {
        val s = RecordingStatusSpeech()
        s.shouldAnnounce(Status.STARTING, "Starting…")
        assertTrue(s.shouldAnnounce(Status.PROCESSING, "Loading model…"))
        // Capture going live after the load is still news.
        assertTrue(s.shouldAnnounce(Status.LISTENING, "Listening"))
        // From here on Processing is the per-chunk kind.
        assertFalse(s.shouldAnnounce(Status.PROCESSING, "Processing…"))
        assertFalse(s.shouldAnnounce(Status.LISTENING, "Listening"))
    }

    @Test
    fun pauseResumeErrorsAndSavingAreSpoken() {
        val s = RecordingStatusSpeech()
        s.shouldAnnounce(Status.LISTENING, "Listening")
        assertTrue(s.shouldAnnounce(Status.PAUSED, "Paused"))
        assertFalse(s.shouldAnnounce(Status.PAUSED, "Paused"))
        assertTrue(s.shouldAnnounce(Status.LISTENING, "Listening"))
        assertTrue(s.shouldAnnounce(Status.ERROR, "Error: code 7"))
        assertTrue(s.shouldAnnounce(Status.ERROR, "Error: code 9"))
        assertTrue(s.shouldAnnounce(Status.LISTENING, "Listening"))
        assertTrue(s.shouldAnnounce(Status.UNAVAILABLE, "Speech recognition unavailable"))
        assertTrue(s.shouldAnnounce(Status.FINISHING, "Saving…"))
        // The service confirming the same state the screen already showed.
        assertFalse(s.shouldAnnounce(Status.FINISHING, "Saving…"))
    }

    @Test
    fun deadMicWarningKeepsTheStatusButIsSpoken() {
        val s = RecordingStatusSpeech()
        s.shouldAnnounce(Status.LISTENING, "Listening")
        assertTrue(s.shouldAnnounce(Status.LISTENING, "No sound from the microphone"))
        // And the recovery.
        assertTrue(s.shouldAnnounce(Status.LISTENING, "Listening"))
    }

    @Test
    fun reattachingToALiveSessionSaysNothingNew() {
        val s = RecordingStatusSpeech()
        s.seed(Status.LISTENING, "Listening")
        assertFalse(s.shouldAnnounce(Status.LISTENING, "Listening"))
        assertFalse(s.shouldAnnounce(Status.HEARING, "Hearing speech"))
        assertFalse(s.shouldAnnounce(Status.PROCESSING, "Processing…"))
        assertTrue(s.shouldAnnounce(Status.PAUSED, "Paused"))
    }

    @Test
    fun seedingAStartingSessionStillSpeaksGoingLive() {
        val s = RecordingStatusSpeech()
        s.seed(Status.STARTING, "Starting…")
        assertTrue(s.shouldAnnounce(Status.LISTENING, "Listening"))
    }

    // --- SpeakerTurnNav ----------------------------------------------------

    private val starts = listOf(0L, 10_000L, 25_000L, 40_000L)

    @Test
    fun nextIsTheFollowingTurn() {
        assertEquals(10_000L, SpeakerTurnNav.next(starts, 0L))
        assertEquals(25_000L, SpeakerTurnNav.next(starts, 12_000L))
        assertNull(SpeakerTurnNav.next(starts, 41_000L))
    }

    @Test
    fun nextAfterLandingOnATurnDoesNotRepeatIt() {
        // Playback reports a few ms either side of where the jump landed.
        assertEquals(25_000L, SpeakerTurnNav.next(starts, 9_990L))
        assertEquals(25_000L, SpeakerTurnNav.next(starts, 10_040L))
    }

    @Test
    fun previousRestartsTheTurnOrStepsBackNearItsStart() {
        // Well into a turn: back to its start.
        assertEquals(10_000L, SpeakerTurnNav.previous(starts, 18_000L))
        // Just after a turn started: the one before.
        assertEquals(0L, SpeakerTurnNav.previous(starts, 10_500L))
        // Inside the first turn: its start, then nothing further.
        assertEquals(0L, SpeakerTurnNav.previous(starts, 1_000L))
        assertNull(SpeakerTurnNav.previous(starts, 0L))
    }

    @Test
    fun navigationIgnoresInputOrderAndEmptyStrips() {
        val shuffled = listOf(25_000L, 0L, 40_000L, 10_000L)
        assertEquals(25_000L, SpeakerTurnNav.next(shuffled, 12_000L))
        assertEquals(10_000L, SpeakerTurnNav.previous(shuffled, 18_000L))
        assertNull(SpeakerTurnNav.next(emptyList(), 0L))
        assertNull(SpeakerTurnNav.previous(emptyList(), 5_000L))
    }

    // --- pillInsetPx -------------------------------------------------------

    @Test
    fun pillInsetSplitsTheExtraHeight() {
        assertEquals(6, pillInsetPx(touchPx = 48, visualPx = 36))
        assertEquals(4, pillInsetPx(touchPx = 48, visualPx = 40))
        // Already big enough: no inset, never a negative one.
        assertEquals(0, pillInsetPx(touchPx = 48, visualPx = 48))
        assertEquals(0, pillInsetPx(touchPx = 48, visualPx = 60))
    }
}
