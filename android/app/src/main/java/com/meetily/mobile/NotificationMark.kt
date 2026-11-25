package com.meetily.mobile

import com.meetily.mobile.DeviceAudioLock.NotificationLine

/**
 * The "Mark moment" action on the recording notification.
 *
 * Audio-only capture is the default, so during a meeting the phone usually
 * sits locked on the table, and the star on the recording screen meant
 * unlocking it and opening the app. The notification action reaches the same
 * RecordingService.requestHighlight from the shade or the lock screen.
 *
 * Pure decisions only, so they can be unit tested. The notification and the
 * session state live in RecordingService.
 */
object NotificationMark {

    /** How long "Marked at 0:12:34" stands in for the notification's line. */
    const val CONFIRM_MS = 3_000L

    /**
     * Whether the notification offers the action. Not while paused, when no
     * moment is being recorded, and not once Android has ended device audio,
     * when nothing more can be. MIC_NO_SIGNAL is still a running session (a
     * very quiet room can look like a dead microphone), so it keeps the
     * action. Not after Finish either: the session can spend up to 15 s
     * draining its backlog, and a tap then would be refused by [accepts]
     * with nothing to show for it.
     */
    fun offered(line: NotificationLine, finishing: Boolean): Boolean = !finishing &&
        when (line) {
            NotificationLine.RECORDING,
            NotificationLine.DEVICE_AUDIO_LIVE,
            NotificationLine.MIC_NO_SIGNAL -> true
            NotificationLine.PAUSED,
            NotificationLine.DEVICE_AUDIO_ENDED,
            NotificationLine.CAPTURE_ENDED -> false
        }

    /**
     * Whether a tap that reaches the service does anything. The tap can land
     * after the state it was offered in has gone: a pause a moment earlier,
     * Finish already saving, or no session at all when the tap raced the end
     * of one and the service was created only to receive it.
     */
    fun accepts(
        active: Boolean,
        finishing: Boolean,
        paused: Boolean,
        deviceAudioEnded: Boolean
    ): Boolean = active && !finishing && !paused && !deviceAudioEnded

    /**
     * The latest line's star after a tap. The on-screen star toggles, so a
     * second tap takes it off again; a notification button labelled Mark is
     * pressed without seeing the line, and must never remove a star.
     */
    fun highlightAfterTap(current: Boolean, markOnly: Boolean): Boolean =
        if (markOnly) true else !current
}
