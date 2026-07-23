package com.meetily.mobile.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.CalendarHelper
import com.meetily.mobile.data.MeetingStore

/**
 * Alarm scheduling for the two notification features:
 *
 *  - Action-item reminders: "remind me" on a to-do fires a notification at
 *    the chosen time, deep-linking back to its meeting.
 *  - Calendar nudges: when enabled, an alarm at the next calendar event's
 *    start asks "record this?"; each firing schedules the next one.
 *
 * Alarms don't survive reboots, so [rescheduleAll] re-arms everything from
 * persisted state — called from app start and the boot receiver.
 */
object Reminders {

    const val ACTION_ITEM_REMINDER = "com.meetily.mobile.reminder.ACTION_ITEM"
    const val ACTION_CALENDAR_NUDGE = "com.meetily.mobile.reminder.CALENDAR_NUDGE"
    const val EXTRA_MEETING_ID = "meeting_id"
    const val EXTRA_TASK = "task"

    /** Fallback window when exact alarms aren't permitted (Android 14+ default). */
    private const val WINDOW_MS = 10 * 60_000L

    fun scheduleActionItem(context: Context, meetingId: String, task: String, atMs: Long) {
        if (atMs <= System.currentTimeMillis()) return
        scheduleAt(context, atMs, actionItemIntent(context, meetingId, task))
    }

    fun cancelActionItem(context: Context, meetingId: String, task: String) {
        alarmManager(context).cancel(actionItemIntent(context, meetingId, task))
    }

    /** Arms (or disarms) the alarm for the next upcoming calendar event. */
    fun scheduleNextCalendarNudge(context: Context) {
        val intent = nudgeIntent(context)
        val manager = alarmManager(context)
        if (!AppSettings(context).meetingNudges) {
            manager.cancel(intent)
            return
        }
        val next = try {
            CalendarHelper.nextUpcomingEvent(context)
        } catch (_: Exception) {
            null
        } ?: run {
            manager.cancel(intent)
            return
        }
        scheduleAt(context, next.beginMs, intent)
    }

    /** Re-arms every future, not-done action-item reminder + the nudge chain. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val store = MeetingStore(context)
        for (meeting in store.list()) {
            for (item in meeting.actionItems) {
                val at = item.remindAtMs ?: continue
                if (!item.done && at > now) {
                    scheduleActionItem(context, meeting.id, item.task, at)
                }
            }
        }
        scheduleNextCalendarNudge(context)
    }

    private fun scheduleAt(context: Context, atMs: Long, operation: PendingIntent) {
        val manager = alarmManager(context)
        val canExact = if (android.os.Build.VERSION.SDK_INT >= 31) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
        try {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
            } else {
                manager.setWindow(AlarmManager.RTC_WAKEUP, atMs, WINDOW_MS, operation)
            }
        } catch (_: SecurityException) {
            manager.setWindow(AlarmManager.RTC_WAKEUP, atMs, WINDOW_MS, operation)
        }
    }

    private fun actionItemIntent(
        context: Context,
        meetingId: String,
        task: String
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            // Stable per (meeting, task) so re-scheduling replaces, not stacks.
            (meetingId + "|" + task).hashCode(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_ITEM_REMINDER)
                .putExtra(EXTRA_MEETING_ID, meetingId)
                .putExtra(EXTRA_TASK, task),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun nudgeIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            9001,
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_CALENDAR_NUDGE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
