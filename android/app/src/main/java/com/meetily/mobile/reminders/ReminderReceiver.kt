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
            Reminders.ACTION_CALENDAR_NUDGE -> fireNudge(context, intent)
            // Hourly sweep, and the calendar provider telling us it synced:
            // both just re-arm, which is how a newly arrived meeting gets a
            // nudge without the app being opened.
            Reminders.ACTION_REFRESH, Intent.ACTION_PROVIDER_CHANGED ->
                try {
                    Reminders.scheduleNextCalendarNudge(context)
                } catch (_: Exception) {
                }
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

    private fun fireNudge(context: Context, intent: Intent) {
        try {
            if (!AppSettings(context).meetingNudges) return
            val now = System.currentTimeMillis()
            // A recording started in the last few minutes is for THIS meeting,
            // so stay quiet. One still running from the previous meeting must
            // not silence the next one — the back-to-back work pattern.
            if (Reminders.NudgeTiming.suppressedByRecording(
                    RecordingService.runningSinceMs, now
                )
            ) {
                return
            }
            // Confirm an event really is starting around now (the calendar can
            // change between arming and firing). The acceptance window must be
            // wider than the alarm's own window: without the exact-alarm
            // permission — denied by default on Android 14+ — the alarm fires
            // anywhere inside a several-minute band, and a tighter check here
            // silently swallowed the notification.
            // Nudge EVERY event starting around now, not just one: two
            // meetings at the same time are a routine work-calendar shape.
            // Dedupe by occurrence keeps the widened window from repeating.
            val candidates = CalendarHelper.findCurrentEvents(context)
                .filter { Reminders.NudgeTiming.accepts(it.beginMs, it.endMs, now) }
                .take(3)
            if (candidates.isEmpty()) return
            ensureChannel(
                context, CHANNEL_NUDGES,
                context.getString(R.string.nudges_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            for (event in candidates) {
                val key = Reminders.NudgeTiming.nudgeKey(event.eventId, event.beginMs)
                if (NudgeState.alreadyPosted(context, key)) continue
                // Per-event codes/ids: two meetings starting at once must not
                // overwrite each other's notification or share stale extras.
                val eventCode = 9200 + (event.eventId % 400).toInt()
                val record = PendingIntent.getActivity(
                    context,
                    eventCode,
                    Intent(context, RecordingActivity::class.java)
                        // Naming the event is what separates "started from
                        // THIS meeting's nudge" from any other way of
                        // starting a recording.
                        .putExtra(RecordingActivity.EXTRA_NUDGE_TITLE, event.title)
                        .putExtra(RecordingActivity.EXTRA_NUDGE_EVENT_ID, event.eventId)
                        // Load-bearing, not decoration. eventCode is
                        // id % 400, so two events 400 apart share a request
                        // code, and PendingIntent.filterEquals() ignores
                        // extras but compares data — without a distinct URI,
                        // FLAG_UPDATE_CURRENT would rewrite the first
                        // event's extras and the notification would start a
                        // recording named after the wrong meeting.
                        .setData(nudgeUri(event.eventId, event.beginMs))
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
                    // Stay up for roughly the meeting instead of a flat 20 min,
                    // so a phone face-down through the meeting still shows it.
                    .setTimeoutAfter(
                        (event.endMs - now).coerceIn(20 * 60_000L, 3 * 60 * 60_000L)
                    )
                // When this event matches a known recurring series, offer a
                // pre-meeting brief: last time's outcomes + open items.
                if (hasSeriesHistory(context, event.title)) {
                    val brief = PendingIntent.getActivity(
                        context,
                        eventCode + 500,
                        Intent(context, com.meetily.mobile.PreMeetingBriefActivity::class.java)
                            .putExtra(
                                com.meetily.mobile.PreMeetingBriefActivity.EXTRA_QUERY,
                                event.title
                            )
                            // Same collision, same fix: the brief carries the
                            // event title as an extra too.
                            .setData(nudgeUri(event.eventId, event.beginMs))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    builder.addAction(
                        R.drawable.ic_sparkle,
                        context.getString(R.string.nudge_prep_action),
                        brief
                    )
                }
                notify(context, eventCode, builder.build())
                NudgeState.markPosted(context, key)
            }
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

    /**
     * A URI unique to one occurrence, so PendingIntents for two events whose
     * ids are congruent modulo 400 stay distinct. Nothing resolves it — it
     * exists purely so [PendingIntent.filterEquals] can tell them apart.
     */
    private fun nudgeUri(eventId: Long, beginMs: Long): android.net.Uri =
        android.net.Uri.parse("recap://nudge/$eventId/$beginMs")

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
