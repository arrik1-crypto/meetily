package com.meetily.mobile

import com.meetily.mobile.DeviceAudioLock.NotificationLine
import com.meetily.mobile.data.ImportBatch

/**
 * Android 16 Live Updates (promoted ongoing notifications): which of this
 * app's notifications ask to be one, and what their chip and bar show.
 *
 * The rules, from developer.android.com ("Create live update
 * notifications") checked against the platform source at
 * android16-qpr1-release (Notification.hasPromotableCharacteristics,
 * NotificationManagerService.fixNotificationWithChannel):
 *
 *  - The notification asks (the "android.requestPromotedOngoing" extra that
 *    NotificationCompat.Builder.setRequestPromotedOngoing writes), is
 *    ongoing, has a content title, has no style or BigTextStyle, CallStyle
 *    or ProgressStyle (MetricStyle too, from an API level after 36), is not
 *    a group summary and not colorized, and its channel is above
 *    IMPORTANCE_MIN.
 *  - No custom RemoteViews, and the public version's count too: a redacted
 *    lock-screen stand-in drawn from a custom layout disqualifies the whole
 *    notification. That is why the recording's Live Update gives up the
 *    lock-screen Mark button (RecordingService.buildNotification).
 *  - The manifest declares android.permission.POST_PROMOTED_NOTIFICATIONS,
 *    a normal app-op permission: granted at install, never asked for. The
 *    user can turn Live Updates off for the app in Settings; then
 *    NotificationManager.canPostPromotedNotifications() is false (always
 *    false below API 36 through NotificationManagerCompat).
 *    hasPromotableCharacteristics() judges the notification alone, not that
 *    setting. The system sets FLAG_PROMOTED_ONGOING or quietly does not, and
 *    OEMs may add criteria of their own.
 *  - Android 16 as first released promoted only colorized notifications;
 *    the rules above came with a later platform release that SDK_INT cannot
 *    tell apart, so where it matters the built notification is asked
 *    (hasPromotableCharacteristics) rather than the API level.
 *  - The status-bar chip shows setShortCriticalText first (whole when under
 *    7 characters, cut or dropped when it cannot fit 96 dp), else a running
 *    chronometer, else only the small icon.
 *  - Below API 36 NotificationCompat 1.17 keeps the request and the short
 *    text as extras nothing reads; the bar there stays the plain one.
 *  - Policy: only for activities that are ongoing, user-initiated and
 *    time-sensitive. Work waiting for a charger, automatic clean-up and
 *    other background work are not promoted, and a Live Update the user
 *    dismissed is not posted as one again (setDeleteIntent).
 *
 * Pure decisions only, so they can be unit tested; the notifications are
 * built in the services, the bar in LiveUpdateBar.
 */
object LiveUpdates {

    /** Android 16, the first release with promoted notifications. */
    const val MIN_SDK = 36

    /** Bar units per file of a batch: one segment each. */
    const val SEGMENT_LENGTH = 100

    /** The app's long-running work, and whether it can be a Live Update at all. */
    enum class Job(val promotable: Boolean) {
        /** Started by the Record button and running until Finish. */
        RECORDING(true),

        /** A file the user picked: at once, or off the import queue as one of a batch. */
        IMPORT(true),

        /** A pass over a meeting's own audio: re-transcribe, first transcript, Continue. */
        TRANSCRIPTION(true),

        CLEAR_VOICE(true),
        MODEL_DOWNLOAD(true),

        /**
         * Not promoted: its bar is a few stage estimates rather than a
         * measure, and it usually ends within a minute or two.
         */
        SUMMARY(false),

        /** Automatic clean-up (RetentionRunner): background work by definition. */
        CLEAN_UP(false)
    }

    /**
     * Whether a notification for [job] asks to be a Live Update. [dismissed]:
     * the user swiped this run's Live Update away, so it stays an ordinary
     * notification from then on.
     */
    fun requestsPromotion(
        job: Job,
        userStarted: Boolean,
        sdkInt: Int,
        dismissed: Boolean = false
    ): Boolean = sdkInt >= MIN_SDK && job.promotable && userStarted && !dismissed

    /**
     * Whether an ImportService run is work the user started now. A file the
     * user picked always is: started at once, or off the import queue once
     * the job ahead of it ends (a batch runs that way), and never held for a
     * charger. A pass over a meeting's audio is only when the meeting screen
     * started it there and then ([fromScreen]): one off the job queue is the
     * automatic check after a recording, the transcript of an audio-only
     * recording, or work that waited for a charger or for other work to end.
     */
    fun transcriptionUserStarted(
        passOverMeeting: Boolean,
        fromScreen: Boolean,
        chargingOnly: Boolean
    ): Boolean = !chargingOnly && (!passOverMeeting || fromScreen)

    /** The import's job kind, for [requestsPromotion]. */
    fun transcriptionJob(passOverMeeting: Boolean): Job =
        if (passOverMeeting) Job.TRANSCRIPTION else Job.IMPORT

    /**
     * A ProgressStyle bar: [value] of [max], one [segments] entry per file of
     * a batch (empty for one file, which leaves the platform's default of 100).
     */
    data class Progress(
        val value: Int,
        val max: Int,
        val indeterminate: Boolean,
        val segments: List<Int>
    ) {
        /** Whole percent of the whole bar, for the chip; null while indeterminate. */
        val percent: Int?
            get() = if (indeterminate || max <= 0) null else (value.toLong() * 100 / max).toInt()
    }

    /**
     * The bar for a job at [percent] of its current file. One of a batch
     * (ImportBatch.showsPosition, the rule its "File 2 of 5" line follows)
     * spans the whole batch, file [batchIndex] of [batchSize], so the bar
     * moves on from file to file instead of starting over. Indeterminate
     * only before anything is done: 0 percent of the first or only file,
     * the plain bar's rule.
     */
    fun progress(percent: Int, batchIndex: Int = 0, batchSize: Int = 0): Progress {
        val p = percent.coerceIn(0, 100)
        if (!ImportBatch.showsPosition(batchIndex, batchSize)) {
            return Progress(p, 100, p == 0, emptyList())
        }
        val value = (batchIndex - 1) * SEGMENT_LENGTH + p
        return Progress(
            value = value,
            max = batchSize * SEGMENT_LENGTH,
            indeterminate = value == 0,
            segments = List(batchSize) { SEGMENT_LENGTH }
        )
    }

    /** What the recording's chip says in place of its running time. */
    enum class RecordingChip {
        /** No short text: the chip runs the notification's chronometer. */
        ELAPSED,
        PAUSED,

        /** The microphone is carrying no signal at all (MIC_NO_SIGNAL). */
        NO_MIC,

        /** Android ended the device-audio capture; only Finish is left. */
        ENDED,

        /** Finish is under way, draining the last of the transcript. */
        SAVING
    }

    /**
     * The recording's chip. The running time, as the screen's timer shows
     * it, while anything is being recorded; a word when the clock stops or
     * something needs a look.
     */
    fun recordingChip(line: NotificationLine, finishing: Boolean): RecordingChip = when {
        finishing -> RecordingChip.SAVING
        else -> when (line) {
            NotificationLine.RECORDING,
            NotificationLine.DEVICE_AUDIO_LIVE -> RecordingChip.ELAPSED
            NotificationLine.MIC_NO_SIGNAL -> RecordingChip.NO_MIC
            NotificationLine.PAUSED -> RecordingChip.PAUSED
            NotificationLine.DEVICE_AUDIO_ENDED -> RecordingChip.ENDED
        }
    }

    /**
     * Whether the recording keeps its classic notification, with the Mark
     * button drawn into its lock-screen stand-in. Only when a Live Update is
     * out of reach: not asked for, Live Updates off for the app, or the
     * built notification not promotable on this device. Losing the button
     * for nothing would be all cost.
     */
    fun recordingKeepsLockScreenMark(
        requested: Boolean,
        canPostPromoted: Boolean,
        promotable: Boolean
    ): Boolean = !(requested && canPostPromoted && promotable)
}
