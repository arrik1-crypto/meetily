package com.meetily.mobile

import com.meetily.mobile.DeviceAudioLock.NotificationLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-audio capture and the lock screen: when the warning is shown, and
 * what the ongoing notification says once Android has ended the capture.
 */
class DeviceAudioLockTest {

    private fun line(
        paused: Boolean = false,
        micWarned: Boolean = false,
        deviceAudio: Boolean = false,
        ended: Boolean = false,
        sdk: Int = 35
    ) = DeviceAudioLock.notificationLine(paused, micWarned, deviceAudio, ended, sdk)

    @Test
    fun theWarningStartsAtAndroid15() {
        // QPR1 cannot be detected, so all of API 35 counts; below it a locked
        // screen leaves the projection running and there is nothing to say.
        assertFalse(DeviceAudioLock.shouldWarnBeforeConsent(29, dismissed = false))
        assertFalse(DeviceAudioLock.shouldWarnBeforeConsent(34, dismissed = false))
        assertTrue(DeviceAudioLock.shouldWarnBeforeConsent(35, dismissed = false))
        assertTrue(DeviceAudioLock.shouldWarnBeforeConsent(36, dismissed = false))
    }

    @Test
    fun dontShowAgainSilencesItForGood() {
        assertFalse(DeviceAudioLock.shouldWarnBeforeConsent(35, dismissed = true))
        assertFalse(DeviceAudioLock.shouldWarnBeforeConsent(36, dismissed = true))
    }

    @Test
    fun anEndedCaptureIsNeverReportedAsPausedOrRecording() {
        // Paused would come with a Resume button that can resume nothing.
        assertEquals(
            NotificationLine.DEVICE_AUDIO_ENDED,
            line(paused = true, deviceAudio = true, ended = true)
        )
        assertEquals(
            NotificationLine.DEVICE_AUDIO_ENDED,
            line(paused = false, deviceAudio = true, ended = true)
        )
    }

    @Test
    fun aLiveDeviceAudioSessionSaysToKeepTheScreenUnlocked() {
        assertEquals(NotificationLine.DEVICE_AUDIO_LIVE, line(deviceAudio = true, sdk = 35))
        assertEquals(NotificationLine.DEVICE_AUDIO_LIVE, line(deviceAudio = true, sdk = 36))
    }

    @Test
    fun belowAndroid15TheDeviceAudioLineIsTheOrdinaryOne() {
        assertEquals(NotificationLine.RECORDING, line(deviceAudio = true, sdk = 34))
    }

    @Test
    fun aMicrophoneSessionKeepsItsExistingLines() {
        assertEquals(NotificationLine.RECORDING, line())
        assertEquals(NotificationLine.PAUSED, line(paused = true))
        assertEquals(NotificationLine.MIC_NO_SIGNAL, line(micWarned = true))
        // A user pause still reads as a pause, warning or not.
        assertEquals(NotificationLine.PAUSED, line(paused = true, micWarned = true))
    }

    @Test
    fun aUserPauseOnDeviceAudioIsStillAPause() {
        // Until Android ends it, a paused device-audio session can resume.
        assertEquals(NotificationLine.PAUSED, line(paused = true, deviceAudio = true))
    }
}
