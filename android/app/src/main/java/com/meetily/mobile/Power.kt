package com.meetily.mobile

import android.content.Context
import android.os.BatteryManager

/** Battery state checks shared by the recording service and the job queue. */
object Power {

    fun isCharging(context: Context): Boolean = try {
        val manager =
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        manager.isCharging
    } catch (_: Exception) {
        false
    }

    /**
     * Whether a heavy, entirely optional pass is worth the battery. Charging
     * always qualifies; otherwise there has to be real headroom left.
     */
    fun allowsHeavyWork(context: Context): Boolean = try {
        val manager =
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        manager.isCharging ||
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) >= 40
    } catch (_: Exception) {
        true
    }
}
