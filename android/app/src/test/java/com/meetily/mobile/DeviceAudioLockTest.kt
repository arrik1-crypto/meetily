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
    fun theLockIsBlamedOnlyWhereLockingEndsTheCapture() {
        // Below Android 15 the projection survives a locked screen, so
        // naming it as the cause would point the user at the wrong thing.
        assertEquals(R.string.devaudio_status_ended_pre35, DeviceAudioLock.endedStatusRes(29))
        assertEquals(R.string.devaudio_status_ended_pre35, DeviceAudioLock.endedStatusRes(34))
        assertEquals(R.string.status_device_audio_ended, DeviceAudioLock.endedStatusRes(35))
        assertEquals(
            R.string.devaudio_notif_ended_detail_pre35, DeviceAudioLock.endedDetailRes(34)
        )
        assertEquals(R.string.devaudio_notif_ended_detail, DeviceAudioLock.endedDetailRes(35))
    }

    @Test
    fun aUserPauseOnDeviceAudioIsStillAPause() {
        // Until Android ends it, a paused device-audio session can resume.
        assertEquals(NotificationLine.PAUSED, line(paused = true, deviceAudio = true))
    }

    @Test
    fun aMicrophoneCaptureThatStoppedIsNeverReportedAsRecording() {
        // The capture ending holds the session paused; the line says it
        // ended, not "Paused" with a Resume that would record nothing, and
        // never "Recording in progress" over a dead microphone.
        fun ended(paused: Boolean = true, micWarned: Boolean = false) =
            DeviceAudioLock.notificationLine(
                paused, micWarned, deviceAudio = false, deviceAudioEnded = false, sdkInt = 35,
                captureEnded = true
            )
        assertEquals(NotificationLine.CAPTURE_ENDED, ended())
        assertEquals(NotificationLine.CAPTURE_ENDED, ended(paused = false, micWarned = true))
        assertTrue(NotificationLine.CAPTURE_ENDED.ended)
        assertTrue(NotificationLine.DEVICE_AUDIO_ENDED.ended)
        for (line in NotificationLine.entries) {
            if (line != NotificationLine.CAPTURE_ENDED && line != NotificationLine.DEVICE_AUDIO_ENDED) {
                assertFalse("$line", line.ended)
            }
        }
        // Device audio ending says why in its own words.
        assertEquals(
            NotificationLine.DEVICE_AUDIO_ENDED,
            DeviceAudioLock.notificationLine(
                paused = true, micWarned = false, deviceAudio = true, deviceAudioEnded = true,
                sdkInt = 35, captureEnded = true
            )
        )
    }
}
