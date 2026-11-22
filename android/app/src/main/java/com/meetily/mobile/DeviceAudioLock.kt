package com.meetily.mobile

import androidx.annotation.StringRes

/**
 * Device-audio capture and the lock screen.
 *
 * Device audio is AudioPlaybackCapture riding a MediaProjection, and on
 * Android 15 QPR1 and later "screen projection automatically stops when the
 * device screen is locked" (developer.android.com, Media projection, "Status
 * bar chip and auto stop"). RecordingActivity keeps the screen awake, but
 * only while it is in front; a user watching a webinar in another app is on
 * that app's screen timeout, and the capture ends when it runs out.
 *
 * A quarterly platform release cannot be told apart from its base release
 * at runtime, so API 35 is treated as the line: a warning that turns out to
 * be early on an Android 15 phone without QPR1 costs nothing, while one that
 * is missing costs the rest of a meeting.
 *
 * Pure decisions only, so they can be unit tested. The dialog is
 * DeviceAudioLockDialog; the session state lives in RecordingService.
 */
object DeviceAudioLock {

    /** First API level on which locking the screen can end the projection. */
    const val STOPS_ON_LOCK_FROM_SDK = 35

    fun stopsOnLock(sdkInt: Int): Boolean = sdkInt >= STOPS_ON_LOCK_FROM_SDK

    /** Whether to explain the lock behaviour before asking for consent. */
    fun shouldWarnBeforeConsent(sdkInt: Int, dismissed: Boolean): Boolean =
        stopsOnLock(sdkInt) && !dismissed

    /**
     * The status line once Android has ended the capture. A locked screen is
     * named as a cause only where locking can end the projection; below
     * Android 15 the causes are the share being stopped or another app
     * starting one, and blaming the lock would send the user after the wrong
     * thing next time.
     */
    @StringRes
    fun endedStatusRes(sdkInt: Int): Int =
        if (stopsOnLock(sdkInt)) R.string.status_device_audio_ended
        else R.string.devaudio_status_ended_pre35

    /** The longer account of the same, for the notification and the screen. */
    @StringRes
    fun endedDetailRes(sdkInt: Int): Int =
        if (stopsOnLock(sdkInt)) R.string.devaudio_notif_ended_detail
        else R.string.devaudio_notif_ended_detail_pre35

    /** What the ongoing recording notification says, in one line. */
    enum class NotificationLine {
        RECORDING,
        DEVICE_AUDIO_LIVE,
        MIC_NO_SIGNAL,
        PAUSED,
        DEVICE_AUDIO_ENDED
    }

    /**
     * Ended wins over everything, paused included: a capture Android has
     * stopped cannot be resumed, so "Paused" with a Resume button would
     * promise something the session can no longer do.
     */
    fun notificationLine(
        paused: Boolean,
        micWarned: Boolean,
        deviceAudio: Boolean,
        deviceAudioEnded: Boolean,
        sdkInt: Int
    ): NotificationLine = when {
        deviceAudioEnded -> NotificationLine.DEVICE_AUDIO_ENDED
        paused -> NotificationLine.PAUSED
        micWarned -> NotificationLine.MIC_NO_SIGNAL
        deviceAudio && stopsOnLock(sdkInt) -> NotificationLine.DEVICE_AUDIO_LIVE
        else -> NotificationLine.RECORDING
    }
}
