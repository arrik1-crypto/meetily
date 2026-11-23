package com.meetily.mobile

import android.media.AudioManager
import android.view.KeyEvent
import com.meetily.mobile.data.AudioDeletions
import com.meetily.mobile.data.FocusClient
import com.meetily.mobile.data.PlaybackFocus
import com.meetily.mobile.data.PlaybackFocus.Action
import com.meetily.mobile.data.PlaybackKeys
import com.meetily.mobile.data.PlaybackLifecycle
import com.meetily.mobile.data.PlaybackLifecycle.Release
import com.meetily.mobile.data.PlaybackSessionText
import com.meetily.mobile.data.PlaybackSkip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // --- Headphones unplugged ----------------------------------------------------

    @Test
    fun unpluggingWhilePlayingPausesAsTheUserWould() {
        val d = PlaybackFocus.onUnplugged(ongoing = PlaybackLifecycle.ongoing(playing = true, resumeOnGain = false))
        assertEquals(Action.PAUSE, d.action)
        assertFalse(d.resumeOnGain)
        assertTrue(d.abandon)
    }

    @Test
    fun unpluggingDuringACallCancelsTheResumeWhenItEnds() {
        // Playing on headphones; a call pauses it; the headphones come out
        // to take the call on the handset; the call ends. The meeting must
        // not start again on the loudspeaker.
        val call = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = true, resumeOnGain = false)
        assertTrue(call.resumeOnGain)
        // Still going on, so the unplug is still listened for.
        val ongoing = PlaybackLifecycle.ongoing(playing = false, resumeOnGain = call.resumeOnGain)
        assertTrue(ongoing)
        val unplugged = PlaybackFocus.onUnplugged(ongoing)
        assertEquals(Action.PAUSE, unplugged.action)
        assertFalse(unplugged.resumeOnGain)
        assertTrue(unplugged.abandon)
        val callEnds = PlaybackFocus.onChange(
            AudioManager.AUDIOFOCUS_GAIN, playing = false, resumeOnGain = unplugged.resumeOnGain
        )
        assertEquals(Action.NONE, callEnds.action)
    }

    @Test
    fun unpluggingWhileReopeningOnTheOtherRecordingPausesIt() {
        val ongoing = PlaybackLifecycle.ongoing(playing = false, resumeOnGain = false, resumingAfterSwitch = true)
        assertEquals(Action.PAUSE, PlaybackFocus.onUnplugged(ongoing).action)
    }

    @Test
    fun unpluggingWhilePausedByTheUserDoesNothing() {
        val d = PlaybackFocus.onUnplugged(ongoing = PlaybackLifecycle.ongoing(playing = false, resumeOnGain = false))
        assertEquals(Action.NONE, d.action)
        assertFalse(d.abandon)
    }

    // --- Carrying out a resume -------------------------------------------------

    @Test
    fun aPreparedPlayerResumesAtOnce() {
        assertEquals(
            PlaybackFocus.Resume.START,
            PlaybackFocus.resume(prepared = true, reopening = false, recording = false)
        )
    }

    @Test
    fun aResumeDuringAClearVoiceReopenWaitsForThePlayer() {
        // A prompt starts and ends while a long recording is reopened on its
        // clear-voice copy: playback carries on once the copy is ready
        // rather than staying paused with focus and the service held.
        val lost = PlaybackFocus.onChange(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, playing = true, resumeOnGain = false
        )
        val back = PlaybackFocus.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false, resumeOnGain = lost.resumeOnGain)
        assertEquals(Action.RESUME, back.action)
        assertEquals(
            PlaybackFocus.Resume.AFTER_REOPEN,
            PlaybackFocus.resume(prepared = false, reopening = true, recording = false)
        )
    }

    @Test
    fun nothingResumesWhileARecordingRuns() {
        assertEquals(
            PlaybackFocus.Resume.STAY_PAUSED,
            PlaybackFocus.resume(prepared = true, reopening = false, recording = true)
        )
        assertEquals(
            PlaybackFocus.Resume.STAY_PAUSED,
            PlaybackFocus.resume(prepared = false, reopening = true, recording = true)
        )
    }

    @Test
    fun aPlayerStillPreparingForTheFirstTimeStaysPaused() {
        assertEquals(
            PlaybackFocus.Resume.STAY_PAUSED,
            PlaybackFocus.resume(prepared = false, reopening = false, recording = false)
        )
    }

    // --- One AudioManager for focus ---------------------------------------------

    /**
     * Android's focus stack, as far as it matters here: a request is named
     * after the manager it went through (its toString() and the listener's),
     * a new request sends a loss to every other one and replaces them, and
     * an abandon removes only the request of the manager it went through.
     */
    private class FocusStack {
        val entries = ArrayList<Any>()
        val lossesSent = ArrayList<Any>()

        fun request(manager: Any) {
            for (other in entries) if (other !== manager) lossesSent.add(other)
            entries.clear()
            entries.add(manager)
        }

        fun abandon(manager: Any) {
            entries.removeAll { it === manager }
        }
    }

    @Test
    fun aPauseOnOneSurfaceAndAPlayOnAnotherNeverPausesItself() {
        // The meeting screen, the playback service and the application each
        // have their own AudioManager; focus goes through the application's.
        val application = Any()
        val focus = FocusClient { application }
        val stack = FocusStack()
        // Play on the meeting screen.
        stack.request(focus.forRequest()!!)
        // Pause on the lock screen: the request is given up.
        stack.abandon(focus.forAbandon()!!)
        assertTrue(stack.entries.isEmpty())
        // Play on the lock screen: no stale request is left to be sent a
        // loss, which would pause the player as it starts.
        stack.request(focus.forRequest()!!)
        assertTrue(stack.lossesSent.isEmpty())
        stack.abandon(focus.forAbandon()!!)
        assertTrue(stack.entries.isEmpty())
    }

    @Test
    fun focusIsGivenUpThroughTheManagerItWasAskedThrough() {
        var current: Any? = Any()
        val focus = FocusClient { current }
        val asked = focus.forRequest()
        // Asked again while held (Play twice): the same manager.
        current = Any()
        assertSame(asked, focus.forRequest())
        // Whatever the supplier gives now, the abandon goes where the request went.
        assertSame(asked, focus.forAbandon())
        assertNull(focus.forAbandon())
        // A later request starts afresh from the supplier.
        assertSame(current, focus.forRequest())
    }

    @Test
    fun noManagerMeansNothingToGiveUp() {
        val focus = FocusClient<Any> { null }
        assertNull(focus.forRequest())
        assertNull(focus.forAbandon())
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
