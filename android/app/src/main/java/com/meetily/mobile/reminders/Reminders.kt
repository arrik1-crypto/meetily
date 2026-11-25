package com.meetily.mobile.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.CalendarHelper
import com.meetily.mobile.data.MeetingStore
import org.json.JSONObject

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

    /** Carried only by the [rescheduleAllIfCleared] sentinel; nothing handles it. */
    private const val ACTION_SENTINEL = "com.meetily.mobile.reminder.SENTINEL"
    const val EXTRA_MEETING_ID = "meeting_id"
    const val EXTRA_TASK = "task"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_EVENT_BEGIN = "event_begin"

    /** The reminder's own time, carried by an action-item alarm; see [Approach]. */
    const val EXTRA_REMIND_AT = "remind_at"

    /** How many upcoming events get their own armed alarm. */
    const val NUDGE_SLOTS = 8

    private const val NUDGE_REQUEST_BASE = 9100
    private const val REFRESH_REQUEST = 9099
    private const val SENTINEL_REQUEST = 9098
    private const val APPROACH_REQUEST = 9097

    /**
     * Inexact alarms and how to land them on time anyway.
     *
     * Without exact alarms (denied by default on Android 14+) Recap arms
     * setAndAllowWhileIdle, which Android gives a window of three quarters
     * of how far ahead the alarm was armed, up to an hour, and an idle phone
     * is woken at the end of it. Armed an hour ahead, a nudge could land 45
     * minutes late, long after the receiver had stopped accepting it, and an
     * action-item reminder up to an hour late.
     *
     * So a time further away than [DIRECT_ARM_MS] is never left to one
     * alarm: a step alarm halfway there arms it again from closer. A step's
     * own window ends at seven eighths of the way, so it always fires first,
     * and after a few steps the time itself is armed within [DIRECT_ARM_MS],
     * which lands it within [INEXACT_WINDOW_MS]. With exact alarms allowed
     * the time is armed directly. Pure — unit-tested.
     */
    object Approach {
        /** Armed this close or closer, an inexact alarm is late by [INEXACT_WINDOW_MS] at most. */
        const val DIRECT_ARM_MS = 8 * 60_000L

        /** The latest an inexact alarm armed [DIRECT_ARM_MS] ahead lands after its time. */
        const val INEXACT_WINDOW_MS = DIRECT_ARM_MS * 3 / 4

        /** Android's heuristic window for a one-shot inexact alarm (API 31+). */
        private const val WINDOW_SHARE = 0.75
        private const val MAX_WINDOW_MS = 60 * 60_000L

        /** A firing this much before the time is a step, not the time itself. */
        const val STEP_SLACK_MS = 30_000L

        /** When to arm for [targetMs] at [nowMs]: the time itself, or a step towards it. */
        fun nextArm(targetMs: Long, nowMs: Long, canExact: Boolean): Long {
            val ahead = targetMs - nowMs
            return if (canExact || ahead <= DIRECT_ARM_MS) targetMs else nowMs + ahead / 2
        }

        /**
         * The step that keeps every nudge in [triggers] on time: halfway to
         * the nearest one armed too far ahead to land on time by itself, or
         * null when none is (or exact alarms are allowed).
         */
        fun nextStep(triggers: List<Long>, nowMs: Long, canExact: Boolean): Long? {
            if (canExact) return null
            val far = triggers.filter { it - nowMs > DIRECT_ARM_MS }.minOrNull() ?: return null
            return nextArm(far, nowMs, canExact = false)
        }

        /** True when an alarm for [targetMs] firing at [nowMs] is a step towards it. */
        fun isStep(targetMs: Long, nowMs: Long): Boolean = targetMs - nowMs > STEP_SLACK_MS

        /** The latest an inexact alarm for [triggerMs], armed at [armedAtMs], can fire. */
        fun latestFire(triggerMs: Long, armedAtMs: Long): Long =
            triggerMs + minOf(((triggerMs - armedAtMs) * WINDOW_SHARE).toLong(), MAX_WINDOW_MS)
    }

    /**
     * Nudge timing. Exact alarms are denied by default on Android 14+, so the
     * fallback path fires anywhere inside a window — which is why the alarm is
     * armed slightly BEFORE the meeting starts and why the receiver's
     * acceptance window has to be wider than the alarm window. Previously the
     * alarm could fire up to 10 minutes late while the receiver only accepted
     * events within ±3 minutes of now, so a late alarm silently did nothing.
     * The window is only that short because [Approach] re-arms a nudge from
     * close by; armed once from far ahead it could be most of an hour.
     * Pure — unit-tested.
     */
    object NudgeTiming {
        /** Arm this far before the meeting starts. */
        const val LEAD_MS = 2 * 60_000L

        /** Inexact-alarm window length; latest fire is start + (WINDOW - LEAD). */
        const val INEXACT_WINDOW_MS = Approach.INEXACT_WINDOW_MS

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

    /**
     * Arms the reminder, or a step towards it when exact alarms are not
     * allowed (see [Approach]); the step's firing comes back here with the
     * same time until the time itself is armed.
     */
    fun scheduleActionItem(context: Context, meetingId: String, task: String, atMs: Long) {
        val now = System.currentTimeMillis()
        if (atMs <= now) return
        scheduleAt(
            context,
            Approach.nextArm(atMs, now, canExact(alarmManager(context))),
            actionItemIntent(context, meetingId, task, atMs)
        )
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
            cancelApproach(context)
            return
        }
        val now = System.currentTimeMillis()
        val upcoming = try {
            CalendarHelper.upcomingEvents(context, limit = NUDGE_SLOTS)
        } catch (_: Exception) {
            emptyList()
        }
        val events = upcoming.filter { event ->
            // upcomingEvents keeps an event until it starts, but its alarm is
            // due two minutes before that. Re-arming one that already fired
            // hands AlarmManager a past time, which it clamps to "in a few
            // seconds" — and that firing re-arms it again, so the device woke
            // every ~5 s for the two minutes before every meeting. A past
            // trigger is armed only for an event not yet handled, such as one
            // added a minute before it starts. Filtered before slots are
            // assigned, so a spent event never takes a later meeting's slot.
            NudgeTiming.triggerFor(event.beginMs) > now ||
                !NudgeState.alreadyPosted(
                    context, NudgeTiming.nudgeKey(event.eventId, event.beginMs)
                )
        }
        events.forEachIndexed { slot, event ->
            scheduleAt(
                context,
                NudgeTiming.triggerFor(event.beginMs),
                nudgeIntent(context, slot, event.eventId, event.beginMs)
            )
        }
        NudgeState.save(context, events)
        // Without exact alarms, a nudge armed far ahead would land late; the
        // step re-runs this, which arms every nudge again from closer.
        armApproach(
            context,
            Approach.nextStep(
                events.map { NudgeTiming.triggerFor(it.beginMs) }, now, canExact(manager)
            )
        )
        // A work calendar gains and moves meetings all day; without a periodic
        // sweep the armed set only refreshes when the app happens to start.
        scheduleRefresh(context)
    }

    /** Arms the nudges' [Approach] step at [atMs], or clears it when null. */
    private fun armApproach(context: Context, atMs: Long?) {
        if (atMs == null) {
            cancelApproach(context)
            return
        }
        try {
            alarmManager(context).setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, atMs, approachIntent(context)
            )
        } catch (_: Exception) {
        }
    }

    private fun cancelApproach(context: Context) {
        try {
            alarmManager(context).cancel(approachIntent(context))
        } catch (_: Exception) {
        }
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

    /**
     * [rescheduleAll], but only when something has cleared this app's alarms.
     *
     * Called on every process start, and the process is cold-started all day
     * by background triggers — calendar syncs, the hourly sweep, charger
     * events, the widget. Alarms survive process death and app updates; a
     * reboot (BootReceiver re-arms) or a force-stop clears them, and both
     * also discard every PendingIntent the app holds. So a far-future
     * sentinel alarm that is still registered proves those did not happen.
     *
     * Revoking "Alarms & reminders" clears only the app's exact alarms, and
     * kills the process: the reminders went, and the sentinel, armed
     * inexact, stayed, so they were never re-armed. Whether the last full
     * re-arm had exact alarms is therefore kept too, and any change of it
     * re-arms everything (which also turns inexact reminders exact once the
     * user allows exact alarms). Otherwise the action-item re-arm — a pass
     * over every meeting file — can be skipped.
     */
    fun rescheduleAllIfCleared(context: Context) {
        pruneDeliveries(context)
        val armed = PendingIntent.getBroadcast(
            context,
            SENTINEL_REQUEST,
            sentinelIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (needsFullReArm(armed != null, armedExact(context), canExact(alarmManager(context)))) {
            rescheduleAll(context)
        } else {
            // Still re-read the calendar as every start did: one provider
            // query, and it keeps the nudge set current.
            scheduleNextCalendarNudge(context)
        }
    }

    /**
     * Re-arms every future, not-done action-item reminder + the nudge chain,
     * and delivers any reminder that fell due while its alarm was gone.
     *
     * Alarms vanish on reboot and force-stop, and a restored backup carries
     * reminder times no alarm was ever armed for. Skipping every past time
     * here meant a reminder due while the phone was off never appeared at
     * all, while the meeting still showed it as set — so an overdue one is
     * posted now, late but not lost.
     */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val exact = canExact(alarmManager(context))
        val store = MeetingStore(context)
        // Action items only: a full list() parsed every transcript and every
        // word timing in the library just to read a few remindAt stamps.
        for (meeting in store.listPartial(MeetingStore.ACTION_FIELDS)) {
            for (item in meeting.actionItems) {
                val at = item.remindAtMs ?: continue
                val delivered = at <= now && wasDelivered(context, meeting.id, item.task, at)
                when (reArmDecision(at, item.done, delivered, now)) {
                    ReArm.ARM -> scheduleActionItem(context, meeting.id, item.task, at)
                    ReArm.DELIVER_NOW -> try {
                        ReminderReceiver.postActionItem(context, meeting, item)
                    } catch (_: Exception) {
                    }
                    ReArm.SKIP -> Unit
                }
            }
        }
        scheduleNextCalendarNudge(context)
        armSentinel(context)
        prefs(context, ALARM_PREFS).edit().putBoolean(KEY_ARMED_EXACT, exact).apply()
    }

    /**
     * Whether [rescheduleAllIfCleared] has to re-arm every reminder: the
     * sentinel is gone, or exact alarms were allowed or taken away since the
     * last full re-arm ([armedExact] null: never recorded). Pure — unit-tested.
     */
    fun needsFullReArm(sentinelArmed: Boolean, armedExact: Boolean?, exactNow: Boolean): Boolean =
        !sentinelArmed || armedExact != exactNow

    private const val ALARM_PREFS = "reminder_alarms"
    private const val KEY_ARMED_EXACT = "armed_exact"

    private fun armedExact(context: Context): Boolean? {
        val prefs = prefs(context, ALARM_PREFS)
        return if (prefs.contains(KEY_ARMED_EXACT)) prefs.getBoolean(KEY_ARMED_EXACT, false) else null
    }

    private fun prefs(context: Context, name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * Arms the marker [rescheduleAllIfCleared] looks for: a non-waking alarm
     * a year out. If it ever fires, the receiver ignores it and the next
     * start simply re-arms everything once.
     */
    private fun armSentinel(context: Context) {
        try {
            alarmManager(context).set(
                AlarmManager.RTC,
                System.currentTimeMillis() + 365L * 24 * 60 * 60_000L,
                PendingIntent.getBroadcast(
                    context,
                    SENTINEL_REQUEST,
                    sentinelIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun sentinelIntent(context: Context): Intent =
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_SENTINEL)

    /** A reminder more overdue than this is stale news; it is not posted late. */
    const val OVERDUE_GRACE_MS = 24L * 60 * 60 * 1000

    enum class ReArm { ARM, DELIVER_NOW, SKIP }

    /**
     * What [rescheduleAll] does with one reminder. [delivered] must say
     * whether this exact reminder time was already notified: without it,
     * every app start would re-post every past reminder. Pure — unit-tested.
     */
    fun reArmDecision(remindAtMs: Long, done: Boolean, delivered: Boolean, nowMs: Long): ReArm =
        when {
            done -> ReArm.SKIP
            remindAtMs > nowMs -> ReArm.ARM
            delivered -> ReArm.SKIP
            nowMs - remindAtMs <= OVERDUE_GRACE_MS -> ReArm.DELIVER_NOW
            else -> ReArm.SKIP
        }

    /*
     * Delivery record for action-item reminders, keyed on (meeting, task,
     * reminder time) so choosing a new time is a new reminder. Kept out of
     * the meeting file on purpose: a screen holding an older copy of the
     * meeting writes the whole object back, which would erase a flag stored
     * there and re-post the reminder on the next start.
     *
     * The task is in the key as a digest, never as text: the record outlives
     * the meeting (nothing here is told when one is erased), and the text of
     * the last reminded tasks stayed on the phone after Delete permanently.
     * Keys written before carried the text; they are rewritten as digests,
     * and everything past the grace window dropped, on every start.
     */
    private const val DELIVERY_PREFS = "reminder_delivery"
    private const val KEY_DELIVERED = "delivered"
    private const val DIGEST_KEY_PREFIX = "d|"

    /** The record's key for one reminder; the task only as a digest. Pure — unit-tested. */
    fun deliveryKey(meetingId: String, task: String, atMs: Long): String =
        "$DIGEST_KEY_PREFIX$meetingId|$atMs|${taskDigest(task)}"

    /** First 16 hex digits of the task's SHA-256: enough to tell a meeting's tasks apart. */
    fun taskDigest(task: String): String {
        val hex = "0123456789abcdef"
        val out = StringBuilder(16)
        for (byte in java.security.MessageDigest.getInstance("SHA-256")
            .digest(task.toByteArray(Charsets.UTF_8)).take(8)
        ) {
            val b = byte.toInt() and 0xff
            out.append(hex[b shr 4]).append(hex[b and 0xf])
        }
        return out.toString()
    }

    /**
     * [entries] as they should be stored at [nowMs]: keys that still carry a
     * task's text rewritten as digests, and anything past twice the grace
     * window dropped — nothing will ever ask about it again. Pure — unit-tested.
     */
    fun tidyDeliveries(entries: Map<String, Long>, nowMs: Long): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((key, at) in entries) {
            if (nowMs - at > 2 * OVERDUE_GRACE_MS) continue
            val tidy = if (key.startsWith(DIGEST_KEY_PREFIX)) {
                key
            } else {
                // Written before: "meeting|time|task text".
                val parts = key.split('|', limit = 3)
                val legacyAt = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size != 3 || legacyAt == null) continue
                deliveryKey(parts[0], parts[2], legacyAt)
            }
            out[tidy] = at
        }
        return out
    }

    private fun storedDeliveries(context: Context): Map<String, Long> = try {
        val json = JSONObject(
            prefs(context, DELIVERY_PREFS).getString(KEY_DELIVERED, "{}") ?: "{}"
        )
        val out = LinkedHashMap<String, Long>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = json.optLong(key)
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }

    private fun storeDeliveries(context: Context, entries: Map<String, Long>) {
        val json = JSONObject()
        for ((key, at) in entries) json.put(key, at)
        prefs(context, DELIVERY_PREFS).edit()
            .putString(KEY_DELIVERED, json.toString())
            .apply()
    }

    private fun deliveredMap(context: Context): Map<String, Long> =
        tidyDeliveries(storedDeliveries(context), System.currentTimeMillis())

    /** Rewrites the delivery record tidied (see [tidyDeliveries]) when that changes it. */
    @Synchronized
    fun pruneDeliveries(context: Context) {
        val stored = storedDeliveries(context)
        val tidy = tidyDeliveries(stored, System.currentTimeMillis())
        if (tidy != stored) storeDeliveries(context, tidy)
    }

    @Synchronized
    fun wasDelivered(context: Context, meetingId: String, task: String, atMs: Long): Boolean =
        deliveredMap(context).containsKey(deliveryKey(meetingId, task, atMs))

    /**
     * Records this reminder as delivered and returns true, or returns false
     * when it already was. Atomic, because the alarm and the start-up sweep
     * can both reach the same overdue reminder at once.
     */
    @Synchronized
    fun claimDelivery(context: Context, meetingId: String, task: String, atMs: Long): Boolean {
        val key = deliveryKey(meetingId, task, atMs)
        // Tidied while rewriting: old text keys go, and so does whatever is
        // past the grace window.
        val map = deliveredMap(context)
        if (map.containsKey(key)) return false
        storeDeliveries(context, map + (key to atMs))
        return true
    }

    private fun canExact(manager: AlarmManager): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                manager.canScheduleExactAlarms()
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }

    private fun scheduleAt(context: Context, atMs: Long, operation: PendingIntent) {
        val manager = alarmManager(context)
        val canExact = canExact(manager)
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

    /** [atMs] is the reminder's time, 0 when only cancelling (extras do not match on cancel). */
    private fun actionItemIntent(
        context: Context,
        meetingId: String,
        task: String,
        atMs: Long = 0L
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            // Stable per (meeting, task) so re-scheduling replaces, not stacks.
            (meetingId + "|" + task).hashCode(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_ITEM_REMINDER)
                .putExtra(EXTRA_MEETING_ID, meetingId)
                .putExtra(EXTRA_TASK, task)
                .putExtra(EXTRA_REMIND_AT, atMs),
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

    /** The nudges' [Approach] step: a one-off refresh, apart from the hourly one. */
    private fun approachIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            APPROACH_REQUEST,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REFRESH),
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
