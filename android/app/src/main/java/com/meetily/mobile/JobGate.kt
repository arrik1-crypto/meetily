package com.meetily.mobile

import android.content.Context
import android.content.Intent
import android.os.Build
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.MeetingStore

/**
 * Admission control for heavy work, and the one place that starts it.
 *
 * Two rules, both learned the hard way:
 *
 *  1. Live recording always wins. It is realtime and its input is
 *     unrecoverable if it falls behind, whereas an import or a summary is
 *     only ever delayed.
 *  2. At most one batch job at a time. Whisper's model and a multi-GB local
 *     LLM do not fit in a phone's memory together; running both thrashes
 *     mmap'd pages and starves the UI thread into an ANR.
 *
 * Anything refused is queued rather than dropped, and drains from whichever
 * trigger fires first — see [drain].
 */
object JobGate {

    /** True when nothing heavy is running and a batch job may start now. */
    fun canStartBatch(): Boolean =
        !RecordingService.isRunning &&
            !ImportService.isRunning &&
            !SummaryService.isRunning

    /**
     * Runs the next deferred job if the system can take one.
     *
     * Called from every point where starting a foreground service is legal:
     * an activity resuming, a recording finishing, and a batch job finishing
     * (which is what actually serialises the queue). Android 12+ forbids
     * starting a foreground service from the background, so the charger
     * broadcast cannot call this directly — see [PowerConnectedReceiver].
     */
    fun drain(context: Context) {
        if (!canStartBatch()) return
        val app = context.applicationContext
        val next = JobQueue.pending(app).firstOrNull() ?: return
        // Taken off the queue first: a job that fails to start must not spin
        // forever on every drain trigger.
        JobQueue.dequeue(app, next.kind, next.meetingId)
        when (next.kind) {
            JobQueue.KIND_SUMMARY -> startSummary(app, next.meetingId, next.payload)
            JobQueue.KIND_CHECK -> startCheck(app, next.meetingId, next.payload)
        }
    }

    /**
     * Starts a summary now, or queues it. [whenCharging] holds the job back
     * until the device is on power even when nothing else is running.
     */
    fun requestSummary(
        context: Context,
        meetingId: String,
        templateKey: String,
        whenCharging: Boolean
    ) {
        val app = context.applicationContext
        if (whenCharging && !Power.isCharging(app)) {
            queue(app, JobQueue.KIND_SUMMARY, meetingId, templateKey)
            return
        }
        if (!canStartBatch()) {
            queue(app, JobQueue.KIND_SUMMARY, meetingId, templateKey)
            return
        }
        startSummary(app, meetingId, templateKey)
    }

    /** As [requestSummary], for a post-meeting transcript accuracy check. */
    fun requestCheck(
        context: Context,
        meetingId: String,
        modelKey: String,
        whenCharging: Boolean
    ) {
        val app = context.applicationContext
        if (whenCharging && !Power.isCharging(app)) {
            queue(app, JobQueue.KIND_CHECK, meetingId, modelKey)
            return
        }
        if (!canStartBatch()) {
            queue(app, JobQueue.KIND_CHECK, meetingId, modelKey)
            return
        }
        startCheck(app, meetingId, modelKey)
    }

    private fun queue(context: Context, kind: String, meetingId: String, payload: String) {
        JobQueue.enqueue(
            context,
            JobQueue.Job(kind, meetingId, payload, System.currentTimeMillis())
        )
    }

    private fun startSummary(context: Context, meetingId: String, templateKey: String) {
        try {
            SummaryService.start(context, meetingId, templateKey)
        } catch (_: Throwable) {
            // Background foreground-service start refused (Android 12+), or
            // the service died on the way up. Put it back.
            queue(context, JobQueue.KIND_SUMMARY, meetingId, templateKey)
        }
    }

    private fun startCheck(context: Context, meetingId: String, modelKey: String) {
        try {
            val meeting = MeetingStore(context).load(meetingId) ?: return
            val audio = meeting.audioFile ?: return
            if (!AudioStore.exists(context, audio)) return
            val intent = Intent(context, ImportService::class.java)
                .setAction(ImportService.ACTION_START)
                .setData(AudioStore.uriFor(context, AudioStore.fileFor(context, audio)))
                .putExtra(ImportService.EXTRA_NAME, meeting.title)
                .putExtra(ImportService.EXTRA_MODEL, modelKey)
                .putExtra(ImportService.EXTRA_RECHECK_MEETING_ID, meetingId)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Throwable) {
            queue(context, JobQueue.KIND_CHECK, meetingId, modelKey)
        }
    }
}
