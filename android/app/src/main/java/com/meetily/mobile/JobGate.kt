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

    /**
     * How long a start is assumed to be on its way before giving up on it.
     * Only has to cover the binder round trip to ActivityManager and back
     * into onStartCommand; the generous margin is so a start that is refused
     * outright cannot wedge the gate.
     */
    private const val PENDING_START_WINDOW_MS = 10_000L

    /**
     * When a foreground start was issued but the service has not yet reported
     * itself running. See [canStartBatch].
     */
    @Volatile
    private var pendingStartAt = 0L

    private fun startPending(): Boolean =
        pendingStartAt != 0L &&
            android.os.SystemClock.elapsedRealtime() - pendingStartAt < PENDING_START_WINDOW_MS

    /** Called by a batch service the moment it marks itself running. */
    fun onBatchStarted() {
        pendingStartAt = 0L
    }

    /**
     * True when nothing heavy is running and a batch job may start now.
     *
     * The isRunning flags alone are not enough. startForegroundService is
     * ASYNCHRONOUS — the service's onStartCommand is delivered on a later
     * main-thread message — so two requests in one main-thread turn both see
     * "nothing running" and both start.
     *
     * That is not a corner case: RecordingService.finishSession clears its own
     * isRunning and then calls maybeStartAutoCheck and maybeStartAutoSummary
     * back to back, so with both auto-passes enabled EVERY finished recording
     * kicked off a full re-transcription and a multi-GB LLM summary at the same
     * time — the exact thrash rule 2 exists to prevent, on a phone that has
     * just spent the whole meeting running Whisper.
     */
    fun canStartBatch(): Boolean =
        !RecordingService.isRunning &&
            !ImportService.isRunning &&
            !SummaryService.isRunning &&
            !startPending()

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
        // The power state is re-read at every drain, not trusted from when
        // the job was queued. "When charging" has to mean charging NOW —
        // otherwise merely opening the app off-charger runs the work the
        // setting was meant to hold back.
        val next = JobQueue.nextRunnable(JobQueue.pending(app), Power.isCharging(app))
            ?: return
        // Taken off the queue first: a job that fails to start must not spin
        // forever on every drain trigger.
        if (next.kind == JobQueue.KIND_IMPORT) {
            JobQueue.dequeueStaged(app, next.stagedFile)
        } else {
            JobQueue.dequeue(app, next.kind, next.meetingId)
        }
        when (next.kind) {
            JobQueue.KIND_SUMMARY ->
                startSummary(app, next.meetingId, next.payload, next.chargingOnly)
            JobQueue.KIND_CHECK ->
                startCheck(app, next.meetingId, next.payload, next.chargingOnly)
            JobQueue.KIND_IMPORT -> startImport(app, next)
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
            queue(app, JobQueue.KIND_SUMMARY, meetingId, templateKey, whenCharging)
            return
        }
        if (!canStartBatch()) {
            queue(app, JobQueue.KIND_SUMMARY, meetingId, templateKey, whenCharging)
            return
        }
        startSummary(app, meetingId, templateKey, whenCharging)
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
            queue(app, JobQueue.KIND_CHECK, meetingId, modelKey, whenCharging)
            return
        }
        if (!canStartBatch()) {
            queue(app, JobQueue.KIND_CHECK, meetingId, modelKey, whenCharging)
            return
        }
        startCheck(app, meetingId, modelKey, whenCharging)
    }

    /**
     * Starts an import now, or copies the file aside and queues it. Either
     * way the user's selection is kept — the old behaviour dropped the file
     * on the floor with only a toast to show for it.
     */
    fun requestImport(
        context: Context,
        uri: android.net.Uri,
        sourceName: String,
        modelKey: String,
        onQueued: (ImportQueue.Result) -> Unit
    ): Boolean {
        if (canStartBatch()) return false
        ImportQueue.stageAndQueue(context, uri, sourceName, modelKey, onQueued)
        return true
    }

    private fun startImport(context: Context, job: JobQueue.Job) {
        try {
            val file = AudioStore.fileFor(context, job.stagedFile)
            if (!file.exists() || file.length() <= 0L) return
            pendingStartAt = android.os.SystemClock.elapsedRealtime()
            val intent = Intent(context, ImportService::class.java)
                .setAction(ImportService.ACTION_START)
                .setData(android.net.Uri.fromFile(file))
                .putExtra(ImportService.EXTRA_NAME, job.sourceName)
                .putExtra(ImportService.EXTRA_MODEL, job.payload)
                // The importer renames this into place rather than copying a
                // second time; the service deletes it if the run never gets
                // that far.
                .putExtra(ImportService.EXTRA_ADOPT_FILE, job.stagedFile)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Throwable) {
            pendingStartAt = 0L
            JobQueue.enqueue(context, job)
        }
    }

    private fun queue(
        context: Context,
        kind: String,
        meetingId: String,
        payload: String,
        chargingOnly: Boolean = false
    ) {
        JobQueue.enqueue(
            context,
            JobQueue.Job(
                kind, meetingId, payload, System.currentTimeMillis(),
                chargingOnly = chargingOnly
            )
        )
    }

    private fun startSummary(
        context: Context,
        meetingId: String,
        templateKey: String,
        chargingOnly: Boolean = false
    ) {
        try {
            pendingStartAt = android.os.SystemClock.elapsedRealtime()
            // The service is told it is running on borrowed power, so that
            // pulling the charger stops it — see PowerWatch. Without this the
            // "wait until charging" setting only ever governed the START, and
            // a summary begun on the charger ran to the end on battery.
            SummaryService.start(
                context, meetingId, templateKey,
                chargingOnly = chargingOnly
            )
        } catch (_: Throwable) {
            pendingStartAt = 0L
            // Background foreground-service start refused (Android 12+), or
            // the service died on the way up. Put it back — still marked as
            // waiting for power, or the retry would escape the constraint.
            queue(context, JobQueue.KIND_SUMMARY, meetingId, templateKey, chargingOnly)
        }
    }

    private fun startCheck(
        context: Context,
        meetingId: String,
        modelKey: String,
        chargingOnly: Boolean = false
    ) {
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
                // As in startSummary: unplugging stops a pass that was only
                // allowed to start because the phone was on power.
                .putExtra(ImportService.EXTRA_CHARGING_ONLY, chargingOnly)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pendingStartAt = android.os.SystemClock.elapsedRealtime()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Throwable) {
            pendingStartAt = 0L
            queue(context, JobQueue.KIND_CHECK, meetingId, modelKey, chargingOnly)
        }
    }
}
