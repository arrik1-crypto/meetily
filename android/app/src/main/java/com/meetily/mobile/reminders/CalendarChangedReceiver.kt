package com.meetily.mobile.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The calendar provider's "something synced" broadcast, so a newly arrived
 * meeting gets its nudge armed without the app being opened.
 *
 * Exported because that broadcast comes from another UID, which also means
 * any app can send it. So this receiver does exactly one thing — re-read the
 * calendar and re-arm, which is idempotent — and ignores every other action.
 * The internal alarm actions live in the non-exported [ReminderReceiver].
 */
class CalendarChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PROVIDER_CHANGED) return
        // A calendar query plus a dozen AlarmManager calls: off the main thread.
        val result = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                Reminders.scheduleNextCalendarNudge(appContext)
            } catch (_: Exception) {
            } finally {
                result.finish()
            }
        }.start()
    }
}
