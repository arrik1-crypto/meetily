package com.meetily.mobile

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Battery state checks shared by the recording service and the job queue. */
object Power {

    /**
     * Whether the phone is on external power — which is what every "wait
     * until charging" decision in the app actually means.
     *
     * Not BatteryManager.isCharging. That reports whether the battery level
     * is going UP, and below 90% the platform only flips it a fixed delay
     * (fifteen minutes) after the level has risen a step; on a phone holding
     * a charge limit it never flips at all. Read here, a quick replug still
     * stopped the job, deferred work ignored the charger it was waiting for,
     * and a meeting ending on the desk charger queued its work anyway.
     *
     * The sticky battery broadcast carries the plug state directly.
     * EXTRA_PLUGGED rather than EXTRA_STATUS: a charge-limited phone reports
     * NOT_CHARGING while plugged in, and the user still counts that as "on
     * the charger".
     */
    fun isCharging(context: Context): Boolean {
        val battery = try {
            // A null receiver only reads the sticky value; nothing is registered.
            context.applicationContext.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        } catch (_: Exception) {
            null
        }
        if (battery != null) {
            return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        return try {
            val manager =
                context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            manager.isCharging
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Whether a heavy, entirely optional pass is worth the battery. Being on
     * power always qualifies; otherwise there has to be real headroom left.
     */
    fun allowsHeavyWork(context: Context): Boolean = try {
        val manager =
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        isCharging(context) ||
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) >= 40
    } catch (_: Exception) {
        true
    }
}
