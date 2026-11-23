package com.meetily.mobile

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * The progress of a long job's notification: a ProgressStyle bar with its
 * chip text when the job is a Live Update (LiveUpdates.requestsPromotion),
 * and the plain bar it always had otherwise, on every Android version.
 */
object LiveUpdateBar {

    /**
     * Puts the bar on [builder]. [live]: the ProgressStyle bar of
     * [progress], with its percentage as the chip text (none while
     * indeterminate, leaving the chip its icon). Otherwise the plain bar at
     * [plainPercent], 0 showing as indeterminate, as before.
     */
    fun setBar(
        context: Context,
        builder: NotificationCompat.Builder,
        live: Boolean,
        progress: LiveUpdates.Progress,
        plainPercent: Int
    ) {
        if (!live) {
            builder.setProgress(100, plainPercent, plainPercent == 0)
            return
        }
        val style = NotificationCompat.ProgressStyle()
            .setProgressIndeterminate(progress.indeterminate)
            .setProgress(progress.value)
        for (length in progress.segments) {
            style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(length))
        }
        builder.setStyle(style)
            .setShortCriticalText(
                progress.percent?.let { context.getString(R.string.liveupdates_chip_percent, it) }
            )
    }

    /**
     * Asks for the Live Update itself, on the main notification only (never
     * its public version). [onDismissed] tells the service the user swiped
     * it away, so the rest of the run is not posted as one again.
     */
    fun requestPromotion(builder: NotificationCompat.Builder, onDismissed: PendingIntent) {
        builder.setRequestPromotedOngoing(true)
            .setDeleteIntent(onDismissed)
    }
}
