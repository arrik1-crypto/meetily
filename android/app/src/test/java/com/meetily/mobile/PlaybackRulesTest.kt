package com.meetily.mobile

import android.media.AudioManager
import android.view.KeyEvent
import com.meetily.mobile.data.AudioDeletions
import com.meetily.mobile.data.PlaybackFocus
import com.meetily.mobile.data.PlaybackFocus.Action
import com.meetily.mobile.data.PlaybackKeys
import com.meetily.mobile.data.PlaybackLifecycle
import com.meetily.mobile.data.PlaybackLifecycle.Release
import com.meetily.mobile.data.PlaybackSessionText
import com.meetily.mobile.data.PlaybackSkip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio focus, 15-second skips, lock-screen text and the service lifecycle for meeting playback. */
class PlaybackRulesTest {

    // --- Audio focus ---------------------------------------------------------

    @Test
    fun aPermanentLossPausesAndGivesUpFocus() {
        val d = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_LOSS, playing = true, resumeOnGain = false)
        assertEquals(Action.PAUSE, d.action)
        assertFalse(d.resumeOnGain)
        assertTrue(d.abandon)
    }

    @Test
    fun aPermanentLossAfterAShortOneNeverResumes() {
        // A call, then another app starting music: the call's regain must
        // not start the meeting over that app.
        val d = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_LOSS, playing = false, resumeOnGain = true)
        assertEquals(Action.NONE, d.action)
        assertFalse(d.resumeOnGain)
        assertTrue(d.abandon)
        val after = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false, resumeOnGain = d.resumeOnGain)
        assertEquals(Action.NONE, after.action)
    }

    @Test
    fun aShortLossPausesAndTheRegainResumes() {
        val lost = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = true, resumeOnGain = false)
        assertEquals(Action.PAUSE, lost.action)
        assertTrue(lost.resumeOnGain)
        assertFalse(lost.abandon)
        val back = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false, resumeOnGain = lost.resumeOnGain)
        assertEquals(Action.RESUME, back.action)
        assertFalse(back.resumeOnGain)
    }

    @Test
    fun speechPausesRatherThanDucks() {
        val d = PlaybackFocus.onChange(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, playing = true, resumeOnGain = false
        )
        assertEquals(Action.PAUSE, d.action)
        assertTrue(d.resumeOnGain)
    }

    @Test
    fun aShortLossWhilePausedByTheUserDoesNotResumeLater() {
        val lost = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = false, resumeOnGain = false)
        assertEquals(Action.NONE, lost.action)
        assertFalse(lost.resumeOnGain)
        val back = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false, resumeOnGain = lost.resumeOnGain)
        assertEquals(Action.NONE, back.action)
    }

    @Test
    fun aSecondShortLossKeepsTheFirstOnesResume() {
        // A notification sound during a call: the call's pause still resumes.
        val d = PlaybackFocus.onChange(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, playing = false, resumeOnGain = true
        )
        assertEquals(Action.NONE, d.action)
        assertTrue(d.resumeOnGain)
    }

    @Test
    fun aGainNobodyAskedForChangesNothing() {
        val d = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = true, resumeOnGain = false)
        assertEquals(Action.NONE, d.action)
        assertFalse(d.abandon)
    }

    // --- Skips -----------------------------------------------------------------

    @Test
    fun skipsMoveFifteenSeconds() {
        assertEquals(75_000L, PlaybackSkip.target(60_000L, PlaybackSkip.STEP_MS, 600_000L))
        assertEquals(45_000L, PlaybackSkip.target(60_000L, -PlaybackSkip.STEP_MS, 600_000L))
    }

    @Test
    fun aBackSkipNearTheStartStopsAtZero() {
        assertEquals(0L, PlaybackSkip.target(4_000L, -PlaybackSkip.STEP_MS, 600_000L))
        assertEquals(0L, PlaybackSkip.target(0L, -PlaybackSkip.STEP_MS, 600_000L))
    }

    @Test
    fun aForwardSkipNearTheEndStopsAtTheEnd() {
        assertEquals(600_000L, PlaybackSkip.target(590_000L, PlaybackSkip.STEP_MS, 600_000L))
        assertEquals(600_000L, PlaybackSkip.target(600_000L, PlaybackSkip.STEP_MS, 600_000L))
    }

    @Test
    fun anUnknownLengthOnlyClampsTheStart() {
        assertEquals(15_000L, PlaybackSkip.target(0L, PlaybackSkip.STEP_MS, 0L))
        assertEquals(0L, PlaybackSkip.target(5_000L, -PlaybackSkip.STEP_MS, -1L))
        assertEquals(Long.MAX_VALUE, PlaybackSkip.target(Long.MAX_VALUE - 1L, PlaybackSkip.STEP_MS, 0L))
    }

    @Test
    fun aPositionOutsideTheAudioIsBroughtBackFirst() {
        // A player can report a little past its length at the very end.
        assertEquals(585_000L, PlaybackSkip.target(601_000L, -PlaybackSkip.STEP_MS, 600_000L))
        assertEquals(15_000L, PlaybackSkip.target(-200L, PlaybackSkip.STEP_MS, 600_000L))
    }

    // --- Lock-screen text ------------------------------------------------------

    @Test
    fun theSessionShowsANeutralLabelAndTheAppName() {
        val text = PlaybackSessionText.forSession("Recap", "Meeting audio")
        assertEquals("Meeting audio", text.title)
        assertEquals("Recap", text.subtitle)
    }

    @Test
    fun aBlankLabelFallsBackToTheAppNameNotToAnythingFromTheMeeting() {
        val text = PlaybackSessionText.forSession("Recap", " ")
        assertEquals("Recap", text.title)
        assertEquals("Recap", text.subtitle)
    }

    @Test
    fun theSessionTextTakesNothingFromTheMeeting() {
        // The rule's only inputs are the app's own strings: a meeting title
        // or transcript line has no way in. Pinned so a later "show the
        // title" change has to change this signature, and this test, first.
        val method = PlaybackSessionText::class.java.declaredMethods.single { it.name == "forSession" }
        assertEquals(2, method.parameterTypes.size)
        assertTrue(method.parameterTypes.all { it == String::class.java })
    }

    // --- Service lifecycle -----------------------------------------------------

    @Test
    fun playbackIsOngoingWhilePlayingOrWaitingOnAShortLossOrASwitch() {
        assertTrue(PlaybackLifecycle.ongoing(playing = true, resumeOnGain = false))
        assertTrue(PlaybackLifecycle.ongoing(playing = false, resumeOnGain = true))
        assertTrue(PlaybackLifecycle.ongoing(playing = false, resumeOnGain = false, resumingAfterSwitch = true))
        assertFalse(PlaybackLifecycle.ongoing(playing = false, resumeOnGain = false))
    }

    @Test
    fun theServiceRunsOnlyForAStartedPlayer() {
        // Preparing the player for its length (the Audio tab) shows nothing.
        assertFalse(PlaybackLifecycle.serviceWanted(hasPlayer = true, active = false, ongoing = false))
        assertTrue(PlaybackLifecycle.serviceWanted(hasPlayer = true, active = true, ongoing = false))
        assertTrue(PlaybackLifecycle.serviceWanted(hasPlayer = true, active = false, ongoing = true))
        assertFalse(PlaybackLifecycle.serviceWanted(hasPlayer = false, active = true, ongoing = true))
    }

    @Test
    fun aRotationKeepsThePlayerWhateverItIsDoing() {
        assertEquals(Release.KEEP, PlaybackLifecycle.onScreenClosed(true, screensLeft = 0, ongoing = false))
    }

    @Test
    fun leavingTheScreenWhilePlayingKeepsPlayingInTheBackground() {
        assertEquals(Release.KEEP, PlaybackLifecycle.onScreenClosed(false, screensLeft = 0, ongoing = true))
    }

    @Test
    fun leavingTheScreenWhilePausedLetsThePlayerGo() {
        assertEquals(Release.RELEASE, PlaybackLifecycle.onScreenClosed(false, screensLeft = 0, ongoing = false))
        // Unless another screen still shows the same meeting.
        assertEquals(Release.KEEP, PlaybackLifecycle.onScreenClosed(false, screensLeft = 1, ongoing = false))
    }

    @Test
    fun aLongPauseEndsTheServiceAndKeepsThePlayerOnlyForAnOpenScreen() {
        assertEquals(Release.END_SERVICE, PlaybackLifecycle.onIdle(ongoing = false, screensOpen = 1))
        assertEquals(Release.RELEASE, PlaybackLifecycle.onIdle(ongoing = false, screensOpen = 0))
        // A call longer than the idle time still resumes when it ends.
        assertEquals(Release.KEEP, PlaybackLifecycle.onIdle(ongoing = true, screensOpen = 0))
    }

    @Test
    fun swipingTheAppAwayStopsOnlyPlaybackThatIsNotGoingOn() {
        assertTrue(PlaybackLifecycle.stopOnTaskRemoved(ongoing = false))
        assertFalse(PlaybackLifecycle.stopOnTaskRemoved(ongoing = true))
    }

    @Test
    fun theIdleStopMatchesMedia3sTenMinutes() {
        assertEquals(600_000L, PlaybackLifecycle.IDLE_STOP_MS)
    }

    // --- Media keys --------------------------------------------------------------

    @Test
    fun nextAndPreviousAreTheFifteenSecondSkips() {
        assertEquals(PlaybackSkip.STEP_MS, PlaybackKeys.skipFor(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(PlaybackSkip.STEP_MS, PlaybackKeys.skipFor(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD))
        assertEquals(-PlaybackSkip.STEP_MS, PlaybackKeys.skipFor(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(-PlaybackSkip.STEP_MS, PlaybackKeys.skipFor(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD))
    }

    @Test
    fun otherKeysAreLeftToTheSession() {
        assertEquals(0L, PlaybackKeys.skipFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(0L, PlaybackKeys.skipFor(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(0L, PlaybackKeys.skipFor(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(0L, PlaybackKeys.skipFor(KeyEvent.KEYCODE_VOLUME_UP))
    }

    // --- Deletion hook -------------------------------------------------------------

    @Test
    fun aDeletedFileReachesTheListener() {
        val heard = mutableListOf<String>()
        AudioDeletions.listen { heard.add(it) }
        try {
            AudioDeletions.deleted("abc.aac")
            assertEquals(listOf("abc.aac"), heard)
        } finally {
            AudioDeletions.listen { }
        }
    }
}
