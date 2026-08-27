package com.meetily.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Ends a job that was only allowed to run because the phone was charging,
 * the moment the charger comes out.
 *
 * "Wait until I'm charging" has always been a promise about when heavy work
 * STARTS. It said nothing about the far more expensive case: a transcription
 * or a multi-GB summary that began on the charger and then keeps running for
 * another twenty minutes on the battery after the phone is picked up and
 * walked away with. That is the same drain the setting exists to avoid, just
 * arriving by a different route.
 *
 * Two things this deliberately does NOT do:
 *
 *  - It is armed only for jobs that were gated on charging. A summary the
 *    user tapped for themselves while plugged in is theirs; unplugging must
 *    not cancel something they asked for and are waiting on.
 *  - It does not fire on the broadcast alone. Cables wobble, wireless pads
 *    lose alignment, and a car dock drops power over every bump. A momentary
 *    break is not a decision to stop, so the state is re-read after a pause
 *    and the job only ends if the phone is really off power.
 *
 * The receiver is registered by the running service and unregistered when it
 * finishes, so nothing is listening once there is nothing to stop.
 */
class PowerWatch(private val onStop: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var confirm: Runnable? = null

    /** Registers the watch. A no-op unless [gatedOnCharging]. */
    fun arm(context: Context, gatedOnCharging: Boolean) {
        if (!gatedOnCharging || receiver != null) return
        val watcher = object : BroadcastReceiver() {
            override fun onReceive(unused: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_POWER_DISCONNECTED) return
                scheduleConfirm(context.applicationContext)
            }
        }
        try {
            ContextCompat.registerReceiver(
                context,
                watcher,
                IntentFilter(Intent.ACTION_POWER_DISCONNECTED),
                // A protected system broadcast, so nothing else can send it
                // and the receiver never needs to be visible to other apps.
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiver = watcher
        } catch (_: Exception) {
        }
    }

    /** Stops listening. Safe to call when never armed, and more than once. */
    fun disarm(context: Context) {
        confirm?.let { main.removeCallbacks(it) }
        confirm = null
        val watcher = receiver ?: return
        receiver = null
        try {
            context.unregisterReceiver(watcher)
        } catch (_: Exception) {
        }
    }

    private fun scheduleConfirm(app: Context) {
        confirm?.let { main.removeCallbacks(it) }
        val check = Runnable {
            confirm = null
            // Plugged back in during the grace period: nothing happened.
            if (Power.isCharging(app)) return@Runnable
            onStop()
        }
        confirm = check
        main.postDelayed(check, CONFIRM_MS)
    }

    private companion object {
        /**
         * Long enough to ride out a wobbly connector, short enough that a
         * real unplug does not cost meaningful battery. The job is stopped,
         * not discarded, so erring towards patience is cheap.
         */
        const val CONFIRM_MS = 6_000L
    }
}
