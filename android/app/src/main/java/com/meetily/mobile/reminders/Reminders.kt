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
    const val ACTION_REFRESH = "com.meetily.mobile.reminder.REFRESH"
    const val EXTRA_MEETING_ID = "meeting_id"
    const val EXTRA_TASK = "task"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_EVENT_BEGIN = "event_begin"

    /** How many upcoming events get their own armed alarm. */
    const val NUDGE_SLOTS = 8

    private const val NUDGE_REQUEST_BASE = 9100
    private const val REFRESH_REQUEST = 9099

    /**
     * Nudge timing. Exact alarms are denied by default on Android 14+, so the
     * fallback path fires anywhere inside a window — which is why the alarm is
     * armed slightly BEFORE the meeting starts and why the receiver's
     * acceptance window has to be wider than the alarm window. Previously the
     * alarm could fire up to 10 minutes late while the receiver only accepted
     * events within ±3 minutes of now, so a late alarm silently did nothing.
     * Pure — unit-tested.
     */
    object NudgeTiming {
        /** Arm this far before the meeting starts. */
        const val LEAD_MS = 2 * 60_000L

        /** Inexact-alarm window length; latest fire is start + (WINDOW - LEAD). */
        const val INEXACT_WINDOW_MS = 5 * 60_000L

        /** A nudge is still useful this long after the meeting started. */
        const val LATE_TOLERANCE_MS = 12 * 60_000L

        /** …and this far before it starts (early-firing inexact alarms). */
        const val EARLY_TOLERANCE_MS = 6 * 60_000L

        fun triggerFor(beginMs: Long): Long = beginMs - LEAD_MS

        /**
         * True when an event should nudge at [nowMs]: inside the widened
         * band around its start AND not already over (a short meeting can
         * otherwise slip through the widened late tolerance).
         */
        fun accepts(beginMs: Long, endMs: Long, nowMs: Long): Boolean =
            nowMs >= beginMs - EARLY_TOLERANCE_MS &&
                nowMs <= beginMs + LATE_TOLERANCE_MS &&
                endMs > nowMs

        /**
         * Dedupe identity for one occurrence. Keyed on the instance start, so
         * a recurring series nudges once per occurrence and a rescheduled
         * meeting is correctly treated as new.
         */
        fun nudgeKey(eventId: Long, beginMs: Long): String = "$eventId|$beginMs"

        /**
         * A recording already running for [sinceMs] suppresses the nudge only
         * if it started recently — i.e. it is for THIS meeting. Back-to-back
         * meetings (a recording still running from the previous one) must
         * still nudge, which is the common work-calendar pattern.
         */
        fun suppressedByRecording(sinceMs: Long?, nowMs: Long): Boolean =
            sinceMs != null && nowMs - sinceMs <= 5 * 60_000L

        /** Request code for the i-th armed slot (fixed set, so re-arming clears stale ones). */
        fun requestCode(slot: Int): Int = NUDGE_REQUEST_BASE + slot
    }

    fun scheduleActionItem(context: Context, meetingId: String, task: String, atMs: Long) {
        if (atMs <= System.currentTimeMillis()) return
        scheduleAt(context, atMs, actionItemIntent(context, meetingId, task))
    }

    fun cancelActionItem(context: Context, meetingId: String, task: String) {
        alarmManager(context).cancel(actionItemIntent(context, meetingId, task))
    }

    /**
     * Arms (or disarms) alarms for the next few calendar events.
     *
     * One alarm per event, in a fixed set of slots that are always cleared
     * first — so simultaneous meetings both nudge, back-to-back meetings do
     * not depend on the previous nudge firing to arm the next, and events
     * that were deleted or moved cannot leave a stale alarm behind.
     */
    fun scheduleNextCalendarNudge(context: Context) {
        val manager = alarmManager(context)
        // Always clear every slot first: re-arming is the only thing that
        // keeps the armed set in step with a calendar that changes all day.
        for (slot in 0 until NUDGE_SLOTS) {
            manager.cancel(nudgeIntent(context, slot, 0L, 0L))
        }
        if (!AppSettings(context).meetingNudges) {
            NudgeState.clear(context)
            cancelRefresh(context)
            return
        }
        val events = try {
            CalendarHelper.upcomingEvents(context, limit = NUDGE_SLOTS)
        } catch (_: Exception) {
            emptyList()
        }
        events.forEachIndexed { slot, event ->
            scheduleAt(
                context,
                NudgeTiming.triggerFor(event.beginMs),
                nudgeIntent(context, slot, event.eventId, event.beginMs)
            )
        }
        NudgeState.save(context, events)
        // A work calendar gains and moves meetings all day; without a periodic
        // sweep the armed set only refreshes when the app happens to start.
        scheduleRefresh(context)
    }

    /** Hourly re-arm so newly synced events get alarms without opening the app. */
    private fun scheduleRefresh(context: Context) {
        try {
            alarmManager(context).setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR,
                refreshIntent(context)
            )
        } catch (_: Exception) {
        }
    }

    private fun cancelRefresh(context: Context) {
        try {
            alarmManager(context).cancel(refreshIntent(context))
        } catch (_: Exception) {
        }
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
        // setAndAllowWhileIdle is the only INEXACT alarm API exempt from Doze;
        // the old setWindow fallback could be deferred for hours on a phone
        // sitting idle on a desk — exactly the situation during a workday.
        try {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
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

    private fun nudgeIntent(
        context: Context,
        slot: Int,
        eventId: Long,
        beginMs: Long
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            NudgeTiming.requestCode(slot),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_CALENDAR_NUDGE)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_EVENT_BEGIN, beginMs),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun refreshIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REFRESH_REQUEST,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
