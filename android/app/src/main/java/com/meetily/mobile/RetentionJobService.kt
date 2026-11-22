package com.meetily.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * The daily automatic clean-up (RetentionRunner.runAutomatic), armed only
 * while a rule is on.
 *
 * Scheduled the way ChargingJobService is: a JobScheduler job, persisted
 * across reboots (the app already holds RECEIVE_BOOT_COMPLETED). The app's
 * only other repeating trigger, the hourly calendar sweep, exists only
 * while meeting nudges are on, so it cannot carry this. The work itself is
 * file deletes on a worker thread, never the main one.
 */
class RetentionJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        Thread {
            try {
                RetentionRunner.runAutomatic(app)
            } catch (_: Throwable) {
                // A failed run deletes nothing more; tomorrow's tries again.
            } finally {
                params?.let { jobFinished(it, false) }
            }
        }.apply {
            name = "retention-run"
            start()
        }
        return true
    }

    /** A run cut short stops between meetings; the next daily run carries on. */
    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        /** Fixed, so scheduling again replaces rather than stacks (ChargingJobService is 7_301). */
        private const val JOB_ID = 7_302

        private const val PERIOD_MS = 24L * 60 * 60 * 1000

        /** Arms the daily run; a no-op when it is already armed. */
        fun schedule(context: Context) {
            try {
                val app = context.applicationContext
                val scheduler =
                    app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(
                    JOB_ID, ComponentName(app, RetentionJobService::class.java)
                )
                    .setPeriodic(PERIOD_MS)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(job)
            } catch (_: Exception) {
                // No scheduler: ensureScheduled tries again on the next start.
            }
        }

        fun cancel(context: Context) {
            try {
                val scheduler = context.applicationContext
                    .getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                scheduler.cancel(JOB_ID)
            } catch (_: Exception) {
            }
        }

        /**
         * Re-arms the run on app start when a rule is on. A force-stop
         * cancels every job the app had; nothing else would bring it back.
         */
        fun ensureScheduled(context: Context) {
            if (RetentionRunner.rule(context).enabled) schedule(context)
        }
    }
}
