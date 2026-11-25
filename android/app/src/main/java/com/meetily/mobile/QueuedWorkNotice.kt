package com.meetily.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.meetily.mobile.data.JobQueue

/**
 * What happens when the phone goes on power with work waiting for it.
 * Shared by [ChargingJobService], the path that is actually delivered, and
 * [PowerConnectedReceiver], kept as a best-effort extra.
 *
 * Neither caller may start a foreground service on Android 12+: nothing
 * about going on charge exempts the app from the background-start ban, and
 * the attempt throws. So the direct start is tried only where it is legal,
 * and otherwise the user gets a notification whose tap opens the app —
 * MainActivity drains the queue on resume, from the foreground, where
 * starting a service is always allowed. A job never runs without somewhere
 * visible saying so.
 */
object QueuedWorkNotice {

    private const val CHANNEL_ID = "queued"
    private const val NOTIF_ID = 60
    private const val NEEDS_MODEL_NOTIF_ID = 61

    /**
     * The staged files the waiting-for-a-model notice was last posted for,
     * in this process; a new process posts it once more. Main thread.
     */
    private var needsModelPosted = ""

    fun onPowerConnected(context: Context) {
        val app = context.applicationContext
        var pending = JobQueue.pending(app)
        if (pending.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && JobGate.canStartBatch()) {
            JobGate.drain(app)
            pending = JobQueue.pending(app)
            if (pending.isEmpty()) return
        }
        notifyWaiting(app, pending.size)
    }

    /**
     * Files queued for import with no model on the phone to transcribe them
     * (JobGate.drain passes them over and keeps them): one quiet notice
     * whose tap opens Settings, posted again only when the files waiting
     * change, and taken down once none do. Called on the main thread at
     * every drain that started nothing.
     */
    fun onImportsWaitingForModel(context: Context, waiting: List<JobQueue.Job>) {
        val key = waiting.map { it.stagedFile }.sorted().joinToString("\n")
        if (key == needsModelPosted) return
        needsModelPosted = key
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (waiting.isEmpty()) {
            manager.cancel(NEEDS_MODEL_NOTIF_ID)
            return
        }
        ensureChannel(context, manager)
        val open = PendingIntent.getActivity(
            context, 10,
            Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = context.getString(R.string.import_waiting_model_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(
                context.resources.getQuantityString(
                    R.plurals.import_waiting_model_title, waiting.size, waiting.size
                )
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        try {
            manager.notify(NEEDS_MODEL_NOTIF_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.queued_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun notifyWaiting(context: Context, count: Int) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager)
        val open = PendingIntent.getActivity(
            context, 9,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(
                context.resources.getQuantityString(
                    R.plurals.queued_ready_title, count, count
                )
            )
            .setContentText(context.getString(R.string.queued_ready_body))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        try {
            manager.notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
