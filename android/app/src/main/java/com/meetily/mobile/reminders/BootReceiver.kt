package com.meetily.mobile.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms all alarms after a reboot (alarms don't survive one). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
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
