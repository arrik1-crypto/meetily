package com.meetily.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Best-effort wake for deferred work when the phone goes on charge.
 *
 * ACTION_POWER_CONNECTED is NOT on the implicit-broadcast exemption list, so
 * with a target SDK of 26 or later this manifest receiver is skipped whenever
 * the app is not already active — which is exactly when deferred work is
 * waiting. The path that is actually delivered is [ChargingJobService]; this
 * stays only for the moments the app happens to be active when the cable
 * goes in. Both share [QueuedWorkNotice], so they cannot disagree.
 */
class PowerConnectedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_POWER_CONNECTED) return
        QueuedWorkNotice.onPowerConnected(context)
    }
}
