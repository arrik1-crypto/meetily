package com.meetily.mobile.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.meetily.mobile.MeetingDetailActivity
import com.meetily.mobile.R
import com.meetily.mobile.RecordingActivity
import com.meetily.mobile.RecordingService
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.CalendarHelper
import com.meetily.mobile.data.MeetingStore

/** Fires action-item reminders and calendar "record this?" nudges. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminders.ACTION_ITEM_REMINDER -> fireActionItem(context, intent)
            Reminders.ACTION_CALENDAR_NUDGE -> fireNudge(context)
        }
    }

    private fun fireActionItem(context: Context, intent: Intent) {
        val meetingId = intent.getStringExtra(Reminders.EXTRA_MEETING_ID) ?: return
        val task = intent.getStringExtra(Reminders.EXTRA_TASK) ?: return
        // Validate against current state: the item may be done or deleted.
        val meeting = MeetingStore(context).load(meetingId) ?: return
        val item = meeting.actionItems.firstOrNull { it.task == task } ?: return
        if (item.done) return

        ensureChannel(
            context, CHANNEL_REMINDERS,
            context.getString(R.string.reminders_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        val open = PendingIntent.getActivity(
            context,
            (meetingId + "|" + task).hashCode(),
            Intent(context, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(task)
            .setContentText(
                context.getString(R.string.reminder_notif_from, meeting.title)
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(task))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(context, (meetingId + "|" + task).hashCode(), notification)
    }

    private fun fireNudge(context: Context) {
        try {
            if (!AppSettings(context).meetingNudges) return
            if (RecordingService.isRunning) return
            val now = System.currentTimeMillis()
            // The alarm was set for an event's start; confirm one is actually
            // starting around now (calendar may have changed since arming).
            val event = CalendarHelper.findCurrentEvents(context)
                .firstOrNull { kotlin.math.abs(it.beginMs - now) <= 3 * 60_000L }
                ?: return
            ensureChannel(
                context, CHANNEL_NUDGES,
                context.getString(R.string.nudges_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val record = PendingIntent.getActivity(
                context,
                9002,
                Intent(context, RecordingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_NUDGES)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(
                    context.getString(R.string.nudge_notif_title, event.title)
                )
                .setContentText(context.getString(R.string.nudge_notif_body))
                .setContentIntent(record)
                .setAutoCancel(true)
                .setTimeoutAfter(20 * 60_000L)
            // When this event matches a known recurring series, offer a
            // pre-meeting brief: last time's outcomes + open items.
            if (hasSeriesHistory(context, event.title)) {
                val brief = PendingIntent.getActivity(
                    context,
                    9003,
                    Intent(context, com.meetily.mobile.PreMeetingBriefActivity::class.java)
                        .putExtra(
                            com.meetily.mobile.PreMeetingBriefActivity.EXTRA_QUERY,
                            event.title
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(
                    R.drawable.ic_sparkle,
                    context.getString(R.string.nudge_prep_action),
                    brief
                )
            }
            notify(context, 9002, builder.build())
        } finally {
            // Keep the chain alive no matter what this firing decided.
            Reminders.scheduleNextCalendarNudge(context)
        }
    }

    /** True when the library holds at least one past meeting of this series. */
    private fun hasSeriesHistory(context: Context, eventTitle: String): Boolean {
        return try {
            val key = com.meetily.mobile.search.MeetingGroups.normalizeTitle(eventTitle)
            key.isNotBlank() && MeetingStore(context).list().any {
                com.meetily.mobile.search.MeetingGroups.normalizeTitle(it.title) == key
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureChannel(
        context: Context,
        id: String,
        name: String,
        importance: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL_REMINDERS = "reminders"
        private const val CHANNEL_NUDGES = "nudges"
    }
}
