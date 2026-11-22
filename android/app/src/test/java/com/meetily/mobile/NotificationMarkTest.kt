package com.meetily.mobile

import com.meetily.mobile.DeviceAudioLock.NotificationLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording notification's Mark moment: when it is offered, when a tap
 * that reaches the service does anything, and what it does to a star.
 */
class NotificationMarkTest {

    private fun accepts(
        active: Boolean = true,
        finishing: Boolean = false,
        paused: Boolean = false,
        ended: Boolean = false
    ) = NotificationMark.accepts(active, finishing, paused, ended)

    private fun offered(line: NotificationLine, finishing: Boolean = false) =
        NotificationMark.offered(line, finishing)

    @Test
    fun offeredWhileTheSessionIsRecording() {
        assertTrue(offered(NotificationLine.RECORDING))
        assertTrue(offered(NotificationLine.DEVICE_AUDIO_LIVE))
        // A quiet room can look like a dead microphone; the session runs on.
        assertTrue(offered(NotificationLine.MIC_NO_SIGNAL))
    }

    @Test
    fun notOfferedWhenNothingIsBeingRecorded() {
        assertFalse(offered(NotificationLine.PAUSED))
        assertFalse(offered(NotificationLine.DEVICE_AUDIO_ENDED))
    }

    @Test
    fun notOfferedOnceFinishIsSaving() {
        // The backlog can take up to 15 s to drain after Finish, and a tap
        // then is refused; the button must be gone rather than do nothing.
        for (line in NotificationLine.entries) {
            assertFalse("$line while finishing", offered(line, finishing = true))
        }
    }

    @Test
    fun theActionFitsBesidePauseAndFinish() {
        // Android shows three actions at most. Pause and Finish are both
        // there whenever Mark is, so it must never be offered as a fourth.
        for (line in NotificationLine.entries) {
            val pause = line != NotificationLine.DEVICE_AUDIO_ENDED
            val count = 1 + (if (pause) 1 else 0) + (if (offered(line)) 1 else 0)
            assertTrue("$line has $count actions", count <= 3)
        }
    }

    @Test
    fun aTapDuringARunningSessionMarks() {
        assertTrue(accepts())
    }

    @Test
    fun aTapWithNoSessionDoesNothing() {
        // The tap can be what created the service, after Finish had ended
        // the session it was offered in.
        assertFalse(accepts(active = false))
    }

    @Test
    fun aTapThatRacedAStateChangeDoesNothing() {
        assertFalse(accepts(finishing = true))
        assertFalse(accepts(paused = true))
        assertFalse(accepts(ended = true))
        assertFalse(accepts(paused = true, ended = true))
    }

    @Test
    fun theNotificationNeverRemovesAStar() {
        assertTrue(NotificationMark.highlightAfterTap(current = false, markOnly = true))
        assertTrue(NotificationMark.highlightAfterTap(current = true, markOnly = true))
    }

    @Test
    fun theOnScreenStarStillToggles() {
        assertTrue(NotificationMark.highlightAfterTap(current = false, markOnly = false))
        assertFalse(NotificationMark.highlightAfterTap(current = true, markOnly = false))
    }

    @Test
    fun theConfirmationIsBrief() {
        assertEquals(3_000L, NotificationMark.CONFIRM_MS)
    }
}
