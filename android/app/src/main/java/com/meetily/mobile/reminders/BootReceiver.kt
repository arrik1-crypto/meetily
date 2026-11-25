package com.meetily.mobile.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms all alarms after a reboot (alarms don't survive one), and when the
 * user allows exact alarms, so reminders armed inexact are armed exact.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            return
        }
        val result = goAsync()
        Thread {
            try {
                Reminders.rescheduleAll(context)
            } finally {
                result.finish()
            }
        }.start()
    }
}
