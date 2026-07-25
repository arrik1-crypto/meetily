package com.meetily.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.meetily.mobile.data.JobQueue

/**
 * Wakes deferred work when the phone goes on charge.
 *
 * ACTION_POWER_CONNECTED is one of the few implicit broadcasts a
 * manifest-declared receiver still receives, so this does fire. What it may
 * NOT do on Android 12+ is start a foreground service: receiving this
 * broadcast grants no exemption from the background foreground-service-start
 * restriction, and the attempt throws.
 *
 * So the direct start is attempted only where it is legal, and otherwise the
 * user gets a notification whose tap opens the app — and MainActivity drains
 * the queue on resume, from the foreground, where starting a service is
 * always allowed. A job never runs without somewhere visible saying so.
 */
class PowerConnectedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_POWER_CONNECTED) return
        val app = context.applicationContext
        if (JobQueue.pending(app).isEmpty()) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && JobGate.canStartBatch()) {
            JobGate.drain(app)
            if (JobQueue.pending(app).isEmpty()) return
        }
        notifyWaiting(app, JobQueue.pending(app).size)
    }

    private fun notifyWaiting(context: Context, count: Int) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.queued_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
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

    companion object {
        private const val CHANNEL_ID = "queued"
        private const val NOTIF_ID = 60
    }
}
