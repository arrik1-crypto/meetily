package com.meetily.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Wakes work that is waiting for a charger.
 *
 * The app used to rely on a manifest receiver for ACTION_POWER_CONNECTED,
 * which apps targeting Android 8+ are not sent while in the background — so
 * the overnight "wait for charging" batch, and every job an unplug put back,
 * sat in the queue until the user happened to open the app on power. A
 * charging-constrained job is the delivery the platform offers instead.
 *
 * It only ever wakes the queue; see [QueuedWorkNotice] for why it cannot
 * simply run the work on Android 12+.
 */
class ChargingJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            // A batch job still running hands over to the queue itself when
            // it finishes, so telling the user to open the app would be noise.
            if (!ImportService.isRunning && !SummaryService.isRunning) {
                QueuedWorkNotice.onPowerConnected(this)
            }
        } catch (_: Throwable) {
        }
        // Nothing continues in the background; the job is done.
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        /** Fixed, so scheduling again replaces rather than stacks. */
        private const val JOB_ID = 7_301

        /**
         * Arms the wake for the next time the phone is on power. Called
         * whenever a charging-only job is queued; a no-op if one is already
         * pending.
         */
        fun schedule(context: Context) {
            try {
                val app = context.applicationContext
                val scheduler =
                    app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(
                    JOB_ID, ComponentName(app, ChargingJobService::class.java)
                )
                    .setRequiresCharging(true)
                    // Survives a reboot between queueing and plugging in; the
                    // app already holds RECEIVE_BOOT_COMPLETED for reminders.
                    .setPersisted(true)
                    .build()
                scheduler.schedule(job)
            } catch (_: Exception) {
                // No scheduler: the queue still drains on the next app open.
            }
        }
    }
}
