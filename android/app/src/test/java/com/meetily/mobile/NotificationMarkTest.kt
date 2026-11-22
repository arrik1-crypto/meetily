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

    @Test
    fun offeredWhileTheSessionIsRecording() {
        assertTrue(NotificationMark.offered(NotificationLine.RECORDING))
        assertTrue(NotificationMark.offered(NotificationLine.DEVICE_AUDIO_LIVE))
        // A quiet room can look like a dead microphone; the session runs on.
        assertTrue(NotificationMark.offered(NotificationLine.MIC_NO_SIGNAL))
    }

    @Test
    fun notOfferedWhenNothingIsBeingRecorded() {
        assertFalse(NotificationMark.offered(NotificationLine.PAUSED))
        assertFalse(NotificationMark.offered(NotificationLine.DEVICE_AUDIO_ENDED))
    }

    @Test
    fun theActionFitsBesidePauseAndFinish() {
        // Android shows three actions at most. Pause and Finish are both
        // there whenever Mark is, so it must never be offered as a fourth.
        for (line in NotificationLine.entries) {
            val pause = line != NotificationLine.DEVICE_AUDIO_ENDED
            val count = 1 + (if (pause) 1 else 0) + (if (NotificationMark.offered(line)) 1 else 0)
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
