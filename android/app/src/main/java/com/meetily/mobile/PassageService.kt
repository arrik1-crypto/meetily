package com.meetily.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import com.meetily.mobile.data.AtomicJson
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.PassageAudio
import com.meetily.mobile.data.PassagePlan
import com.meetily.mobile.data.PassageSwap
import com.meetily.mobile.data.SafeFiles
import com.meetily.mobile.data.Waveform
import com.meetily.mobile.whisper.ImportEta
import com.meetily.mobile.whisper.PassageSilencer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Removes one passage of one meeting (PassagePlan) as a foreground job: its
 * audio is written again with the span silent (PassageSilencer), then the
 * meeting is saved pointing at the new file with the span's lines removed,
 * and only then is the recording deleted (PassageSwap). Like a clear-voice
 * copy (ClearVoiceService), it is the processing of a recorded audio file,
 * so mediaProcessing on Android 15 and later and dataSync below
 * (ImportFgsType), sharing that type's six-hour daily allowance; onTimeout
 * ends a run that reaches it, keeping nothing.
 *
 * Started from the meeting screen after the user saw exactly what goes, with
 * progress there and in the notification, which can stop it. It does not
 * start while a recording runs (JobGate's first rule) or while anything
 * else works on the meeting: a recording, import or accuracy check, summary
 * or clear-voice copy of it, a queued or interrupted job for it, or a
 * finished accuracy check still to review (all made from the audio with the
 * passage in it). While it runs the meeting counts as having its content
 * written (AudioRemoval.isWritingContent): nothing else edits, removes,
 * cleans up or deletes it, and JobGate starts no queued work until it ends.
 *
 * The clear-voice copy is deleted with the recording rather than silenced
 * too: it holds the passage, and silencing it would be a second full decode
 * and encode with its own timing to keep, for a copy the user can make
 * again from the player in one tap.
 */
class PassageService : Service() {

    /** How a run ended, for the meeting screen and the notification. */
    enum class Result { REMOVED, CANCELLED, FAILED, NO_SPACE, TIMED_OUT, GONE, RECORDING, BUSY }

    /** The meeting screen's view of a run. Called on the main thread. */
    interface Listener {
        /** True when this listener shows [meetingId], so the outcome is seen there. */
        fun showsMeeting(meetingId: String): Boolean
        fun onPassageProgress(meetingId: String, percent: Int, minutesLeft: Long?)

        /** The run on [meetingId] ended; with REMOVED, [span] is gone from the stored meeting. */
        fun onPassageDone(meetingId: String, result: Result, span: PassagePlan.Span)
    }

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wakeLockAcquiredMs = 0L

    /** Set by onTimeout: the daily allowance for this service type ran out. */
    @Volatile private var timedOut = false

    /** The newest start this instance was handed; finishRun stops with it. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (isRunning) cancelRequested = true else stopSelfResult(startId)
            }
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_MEETING_ID)
                val audio = intent.getStringExtra(EXTRA_AUDIO)
                val span = PassagePlan.Span(
                    intent.getLongExtra(EXTRA_START_MS, -1L),
                    intent.getLongExtra(EXTRA_END_MS, -1L)
                )
                if (isRunning || timedOut || id.isNullOrBlank() || audio.isNullOrBlank() ||
                    span.startMs < 0L || span.isEmpty
                ) {
                    val running = isRunning
                    val result = if (timedOut) Result.TIMED_OUT else Result.FAILED
                    refuseStart(startId)
                    // A screen showing progress for this start hears that it never began.
                    if (!running && !id.isNullOrBlank()) {
                        listeners.forEach { it.onPassageDone(id, result, span) }
                    }
                    return START_NOT_STICKY
                }
                isRunning = true
                currentMeetingId = id
                currentSpan = span
                claimedId = null
                claimedAtMs = 0L
                cancelRequested = false
                percent = 0
                minutesLeft = null
                createChannel()
                startForegroundCompat(buildNotification(id))
                // The foreground service keeps the process, not the CPU.
                acquireWakeLock()
                run(id, audio, span)
            }
            else -> if (!isRunning) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Turns a start down without breaking the startForegroundService
     * contract, which needs startForeground even from a start that is
     * refused (see ImportService.refuseStart).
     */
    private fun refuseStart(startId: Int) {
        if (isRunning && !timedOut) {
            try {
                startForegroundCompat(buildNotification(currentMeetingId))
            } catch (_: Exception) {
            }
            return
        }
        // A start that never became a run: nothing counts as busy for it.
        claimedId = null
        claimedAtMs = 0L
        try {
            createChannel()
            startForegroundCompat(buildNotification(null))
        } catch (_: Exception) {
        }
        stopForegroundCompat()
        if (!isRunning) stopSelfResult(startId)
    }

    private fun run(meetingId: String, audio: String, span: PassagePlan.Span) {
        val app = applicationContext
        Thread {
            // A live recording started meanwhile wins the CPU.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {
            }
            val result = try {
                work(app, meetingId, audio, span)
            } catch (_: Throwable) {
                Result.FAILED
            }
            main.post { finishRun(meetingId, result, span) }
        }.apply {
            name = "passage-removal"
            start()
        }
    }

    @WorkerThread
    private fun work(app: Context, meetingId: String, audio: String, span: PassagePlan.Span): Result {
        // Checked again here: the start was asynchronous.
        if (RecordingService.isRunning) return Result.RECORDING
        if (othersBusy(app, meetingId)) return Result.BUSY
        // A run this process did not live to finish is settled first,
        // including one on this meeting: this run has written nothing yet.
        recoverLeftovers(app, includeRunning = true)
        val store = MeetingStore(app)
        val meeting = store.load(meetingId) ?: return Result.GONE
        if (meeting.audioFile != audio) return Result.GONE
        val source = AudioStore.fileFor(app, audio)
        if (source.length() <= 0L) return Result.FAILED
        val (totalMs, bitRate) = probe(source)
        // Room for the new file beside the recording until the swap.
        if (totalMs > 0L) {
            val estimate = PassageAudio.estimatedBytes(totalMs, bitRate)
            if (ModelSpaceCheck.measure(app).availableBytes < estimate + SPACE_MARGIN_BYTES) return Result.NO_SPACE
        }
        val dir = workDir(app)
        val part = File(dir, PassageSwap.partName(meetingId))
        val probeFile = File(dir, PassageSwap.probeName(meetingId))
        val startedMs = SystemClock.elapsedRealtime()
        val written = PassageSilencer.silence(
            source, part, probeFile, span.startMs, span.endMs, bitRate, totalMs,
            cancelled = { cancelRequested || timedOut }
        ) { p, doneMs ->
            renewWakeLockIfStale()
            val left = if (totalMs > 0L) {
                ImportEta.remainingMinutes(doneMs, totalMs, SystemClock.elapsedRealtime() - startedMs)
            } else {
                null
            }
            publish(meetingId, p, left)
        }
        when (written.outcome) {
            PassageSilencer.Outcome.DONE -> Unit
            PassageSilencer.Outcome.CANCELLED -> return if (timedOut) Result.TIMED_OUT else Result.CANCELLED
            PassageSilencer.Outcome.NO_SPACE -> return Result.NO_SPACE
            PassageSilencer.Outcome.FAILED -> return Result.FAILED
        }
        // A last chance to stop before anything is changed.
        if (cancelRequested || timedOut) {
            part.delete()
            return if (timedOut) Result.TIMED_OUT else Result.CANCELLED
        }
        publish(meetingId, 100, null)
        return swap(app, store, meetingId, audio, span, part, written)
    }

    /**
     * Puts the new file in place of [audio] (see PassageSwap): marker,
     * rename, the meeting saved with the plan applied, then the recording
     * and everything made from it deleted.
     */
    @WorkerThread
    private fun swap(
        app: Context,
        store: MeetingStore,
        meetingId: String,
        audio: String,
        span: PassagePlan.Span,
        part: File,
        written: PassageSilencer.Result
    ): Result {
        val newName = PassageSwap.newAudioName(audio)
        if (!SafeFiles.isPlainName(newName)) {
            part.delete()
            return Result.FAILED
        }
        val target = AudioStore.fileFor(app, newName)
        // Not this meeting's (it points at [audio]), and no other meeting's
        // (the name starts with this one's); a leftover of a run that died.
        if (target.exists()) AudioStore.delete(app, newName)
        val marker = PassageSwap.markerName(meetingId)
        val markerJson = JSONObject().put("old", audio).put("new", newName).toString()
        if (!AtomicJson.write(workDir(app), marker, markerJson) || !part.renameTo(target)) {
            part.delete()
            File(workDir(app), marker).delete()
            return Result.FAILED
        }
        var swapped = false
        var copy: String? = null
        val saved = store.mutateIf(meetingId) { stored ->
            if (!PassageSwap.maySwap(true, stored.audioFile, audio)) {
                false
            } else {
                // The span the user confirmed, silenced as it is: its edges
                // do not move over lines the transcript lost meanwhile.
                val plan = PassagePlan.plan(
                    stored.segments, span, written.durationMs.takeIf { it > 0L },
                    stored.highlightMarksMs, stored.chapters, stored.transcriptResumeMs,
                    snap = false
                )
                PassagePlan.applyTo(stored, plan)
                stored.transcriptResumeMs = PassagePlan.resumeAfter(plan, stored.transcriptResumeMs)
                copy = stored.clearVoiceFile
                // Made from the recording, with the passage in it.
                stored.clearVoiceFile = null
                stored.audioFile = newName
                swapped = true
                true
            }
        }
        if (!saved || !swapped) {
            // Nothing points at the new file: the recording stays as it was.
            AudioStore.delete(app, newName)
            File(workDir(app), marker).delete()
            return if (!store.exists(meetingId)) Result.GONE else if (!saved) Result.FAILED else Result.GONE
        }
        // The recording, its waveform, clips and clear-voice copy hold the
        // passage; nothing points at them now.
        AudioRemoval.deleteFiles(app, audio, copy)
        written.loudness?.let { Waveform.saveFrom(app, newName, it) }
        File(workDir(app), marker).delete()
        return Result.REMOVED
    }

    /** The recording's length and stated bitrate (0 and -1 when unknown). Slow on raw ADTS. */
    @WorkerThread
    private fun probe(file: File): Pair<Long, Int> {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val bitRate = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toIntOrNull() ?: -1
            duration to bitRate
        } catch (_: Throwable) {
            0L to -1
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun publish(meetingId: String, p: Int, left: Long?) {
        if (p == percent && left == minutesLeft) return
        percent = p
        minutesLeft = left
        main.post {
            if (!isRunning || currentMeetingId != meetingId) return@post
            listeners.forEach { it.onPassageProgress(meetingId, p, left) }
            updateNotification(meetingId)
        }
    }

    private fun finishRun(meetingId: String, result: Result, span: PassagePlan.Span) {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        val wasTimedOut = timedOut
        isRunning = false
        cancelRequested = false
        currentSpan = null
        val watching = listeners.any { it.showsMeeting(meetingId) }
        listeners.forEach { it.onPassageDone(meetingId, result, span) }
        if (wasTimedOut || (!watching && result != Result.CANCELLED)) {
            postDoneNotification(meetingId, if (wasTimedOut) Result.TIMED_OUT else result)
        }
        timedOut = false
        // Queued work held back while the meeting was being changed, handed
        // over while this service is still in the foreground, which is what
        // makes starting it legal on Android 12+ (see ImportService.finishRun).
        // Not after a timeout: the foreground is gone already, and the next
        // activity resume drains instead.
        if (!wasTimedOut) JobGate.drain(this)
        // Drain never starts this service, so this start is still the last.
        if (stopSelfResult(lastStartId)) stopForegroundCompat()
    }

    /** See ClearVoiceService.onTimeout: stop between chunks, leave the foreground now. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        cancelRequested = true
        stopForegroundCompat()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "meetily:passage"
            ).also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            lock.acquire(WAKE_LOCK_MS)
            wakeLockAcquiredMs = System.currentTimeMillis()
        } catch (_: Exception) {
        }
    }

    private fun renewWakeLockIfStale() {
        if (System.currentTimeMillis() - wakeLockAcquiredMs >= WAKE_LOCK_RENEW_MS) acquireWakeLock()
    }

    // --- Notifications ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.passage_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun openMeeting(meetingId: String?, requestCode: Int): PendingIntent {
        val intent = if (meetingId != null) {
            Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
        } else {
            Intent(this, MainActivity::class.java)
        }
        return PendingIntent.getActivity(
            this, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(meetingId: String?): Notification {
        val cancelIntent = PendingIntent.getService(
            this, REQUEST_CANCEL,
            Intent(this, PassageService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val left = minutesLeft
        val text = if (left != null) {
            getString(R.string.passage_progress_eta, percent, minutesLabel(left))
        } else {
            getString(R.string.passage_progress, percent)
        }
        // No meeting title or transcript text: the notification can show on
        // the lock screen, and the passage is what the user wants gone.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_delete)
            .setContentTitle(getString(R.string.passage_notif_title))
            .setContentText(text)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openMeeting(meetingId, REQUEST_OPEN))
            .addAction(0, getString(R.string.passage_stop), cancelIntent)
            .build()
    }

    private fun updateNotification(meetingId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIF_ID, buildNotification(meetingId))
        } catch (_: SecurityException) {
        }
    }

    private fun postDoneNotification(meetingId: String, result: Result) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val body = getString(messageFor(result))
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_delete)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentTitle(
                getString(
                    if (result == Result.REMOVED) R.string.passage_notif_done else R.string.passage_notif_not_done
                )
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openMeeting(meetingId, REQUEST_DONE))
        try {
            manager.notify(NOTIF_DONE_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun minutesLabel(minutes: Long): String =
        if (minutes >= 60) {
            getString(R.string.duration_h_min, (minutes / 60).toInt(), (minutes % 60).toInt())
        } else {
            getString(R.string.duration_min, minutes.toInt())
        }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ImportFgsType.forSdk(Build.VERSION.SDK_INT))
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.meetily.mobile.passage.START"
        const val ACTION_CANCEL = "com.meetily.mobile.passage.CANCEL"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_AUDIO = "audio_file"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_END_MS = "end_ms"

        private const val CHANNEL_ID = "passage_removal"

        /** Out of the range the other services' notification ids use. */
        private const val NOTIF_ID = 7_320
        private const val NOTIF_DONE_ID = 7_321

        /** PendingIntent request codes of their own (see ClearVoiceService). */
        private const val REQUEST_OPEN = 4851
        private const val REQUEST_CANCEL = 4852
        private const val REQUEST_DONE = 4853

        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L

        /** Kept free beyond the new file's own size. */
        private const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024

        /** How long a start from [start] counts as on its way. */
        private const val CLAIM_WINDOW_MS = 10_000L

        @Volatile var isRunning = false
            private set

        /** The meeting a passage is being removed from, while [isRunning]. */
        @Volatile var currentMeetingId: String? = null
            private set

        /** The span being removed, while [isRunning]. */
        @Volatile var currentSpan: PassagePlan.Span? = null
            private set

        /** The latest run's progress, for a screen that opens mid-run. */
        @Volatile var percent = 0
            private set

        @Volatile var minutesLeft: Long? = null
            private set

        @Volatile private var cancelRequested = false

        /** [start] was called and onStartCommand has not run yet. */
        @Volatile private var claimedId: String? = null
        @Volatile private var claimedAtMs = 0L

        private val listeners = CopyOnWriteArraySet<Listener>()

        /** Serialises leftover recovery against the start of a run. */
        private val recoveryLock = Any()

        private fun claimed(): String? =
            claimedId?.takeIf {
                claimedAtMs != 0L && SystemClock.elapsedRealtime() - claimedAtMs < CLAIM_WINDOW_MS
            }

        /** True while a passage of [meetingId] is being removed, or about to be. */
        fun isRemoving(meetingId: String): Boolean =
            (isRunning && currentMeetingId == meetingId) || (!isRunning && claimed() == meetingId)

        /** The meeting a passage is being removed from, if any. */
        fun removingId(): String? = if (isRunning) currentMeetingId else claimed()

        /** True while any run is going or about to: JobGate starts no queued work then. */
        fun isBusy(): Boolean = removingId() != null

        fun addListener(listener: Listener) {
            listeners.add(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        /** The work folder for part files and swap markers (PassageSwap.WORK_DIR). */
        fun workDir(context: Context): File =
            File(AudioStore.dir(context), PassageSwap.WORK_DIR).apply { mkdirs() }

        /**
         * Something other than a passage run works on [meetingId] or waits
         * for its audio: its recording, import or accuracy check, summary or
         * clear-voice copy, a queued or interrupted job for it, or a staged
         * accuracy check still to review.
         */
        private fun othersBusy(context: Context, meetingId: String): Boolean =
            (RecordingService.isRunning && MeetingStore(context).activeId() == meetingId) ||
                (ImportService.isRunning && ImportService.currentMeetingId == meetingId) ||
                (SummaryService.isRunning && SummaryService.currentMeetingId == meetingId) ||
                ClearVoiceService.isPreparing(meetingId) ||
                meetingId in AudioRemoval.waitingForAudio(context, listOf(meetingId))

        /**
         * Why a passage of [meetingId] cannot be removed now, as a message,
         * or null when it can. Reads the job queue: a worker, or a tap.
         */
        fun blockReason(context: Context, meetingId: String): Int? = when {
            RecordingService.isRunning -> R.string.passage_blocked_recording
            removingId() != null ->
                if (removingId() == meetingId) R.string.passage_blocked_running else R.string.passage_blocked_other
            ClearVoiceService.isPreparing(meetingId) -> R.string.clearvoice_busy
            com.meetily.mobile.data.TranscriptDraft.isPending(context, meetingId) -> R.string.passage_blocked_review
            othersBusy(context, meetingId) -> R.string.passage_blocked_busy
            else -> null
        }

        /**
         * Starts removing [span] from [meetingId], whose recording is
         * [audioFile]. Main thread, from a screen in the foreground, after
         * the player let go of the recording. Returns false when refused.
         */
        fun start(context: Context, meetingId: String, audioFile: String, span: PassagePlan.Span): Boolean {
            if (isRunning || claimed() != null) return false
            percent = 0
            minutesLeft = null
            claimedId = meetingId
            claimedAtMs = SystemClock.elapsedRealtime()
            val intent = Intent(context, PassageService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MEETING_ID, meetingId)
                .putExtra(EXTRA_AUDIO, audioFile)
                .putExtra(EXTRA_START_MS, span.startMs)
                .putExtra(EXTRA_END_MS, span.endMs)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (_: Throwable) {
                claimedId = null
                claimedAtMs = 0L
                false
            }
        }

        /** Stops the run between chunks; nothing it made is kept and nothing changes. */
        fun cancel() {
            if (isRunning) cancelRequested = true
        }

        /**
         * Removes [span]'s lines from [meetingId], a meeting with no audio
         * on disk (there is nothing to silence): the plan is applied to the
         * stored meeting under the store's lock. A meeting that still names
         * a recording which is missing or empty (a backup restored without
         * it) counts as having none, as on the meeting screen; its name is
         * left as it is. Returns REMOVED, GONE when the meeting is gone or
         * has audio again, BUSY when something works on it, FAILED when the
         * save failed. Worker threads only.
         */
        @WorkerThread
        fun removeLinesOnly(context: Context, meetingId: String, span: PassagePlan.Span): Result {
            if (isRemoving(meetingId) || othersBusy(context, meetingId)) return Result.BUSY
            val store = MeetingStore(context)
            var applied = false
            val saved = store.mutateIf(meetingId) { stored ->
                if (AudioStore.exists(context, stored.audioFile)) {
                    false
                } else {
                    // The span the user confirmed; see swap.
                    val plan = PassagePlan.plan(
                        stored.segments, span, null,
                        stored.highlightMarksMs, stored.chapters, stored.transcriptResumeMs,
                        snap = false
                    )
                    PassagePlan.applyTo(stored, plan)
                    stored.transcriptResumeMs = PassagePlan.resumeAfter(plan, stored.transcriptResumeMs)
                    applied = true
                    true
                }
            }
            return when {
                !saved && store.exists(meetingId) -> Result.FAILED
                !saved || !applied -> Result.GONE
                else -> Result.REMOVED
            }
        }

        /**
         * Settles every swap a process left unfinished (PassageSwap.leftover),
         * and deletes part files and probes nothing is writing. At app start
         * and before each run; never while a run is going.
         */
        @WorkerThread
        fun recoverLeftovers(context: Context, includeRunning: Boolean = false) {
            synchronized(recoveryLock) {
                val dir = File(AudioStore.dir(context), PassageSwap.WORK_DIR)
                val files = dir.listFiles() ?: return
                val store = MeetingStore(context)
                for (file in files) {
                    // Asked per file: a run may have started meanwhile.
                    val running = if (includeRunning) null else removingId()
                    val id = PassageSwap.meetingOfMarker(file.name)
                    if (id == null) {
                        // A part file or probe, named after its meeting.
                        if (running == null || !file.name.startsWith("$running.")) file.delete()
                        continue
                    }
                    if (id == running) continue
                    val marker = try {
                        JSONObject(file.readText())
                    } catch (_: Exception) {
                        file.delete()
                        continue
                    }
                    val old = marker.optString("old", "")
                    val new = marker.optString("new", "")
                    if (!SafeFiles.isPlainName(old) || !SafeFiles.isPlainName(new) || old == new) {
                        file.delete()
                        continue
                    }
                    val meeting = store.load(id)
                    when (PassageSwap.leftover(meeting != null, meeting?.audioFile, old, new)) {
                        PassageSwap.Leftover.DELETE_NEW -> AudioStore.delete(context, new)
                        PassageSwap.Leftover.DELETE_OLD -> AudioRemoval.deleteFiles(context, old)
                        PassageSwap.Leftover.DELETE_BOTH -> {
                            AudioStore.delete(context, new)
                            AudioRemoval.deleteFiles(context, old)
                        }
                        // Looked at again at every start until the meeting is back or gone for good.
                        PassageSwap.Leftover.KEEP_BOTH -> continue
                    }
                    file.delete()
                }
            }
        }

        /** The message for a finished run's [result]. */
        fun messageFor(result: Result): Int = when (result) {
            Result.REMOVED -> R.string.passage_removed
            Result.CANCELLED -> R.string.passage_cancelled
            Result.FAILED -> R.string.passage_failed
            Result.NO_SPACE -> R.string.passage_no_space
            Result.TIMED_OUT -> R.string.passage_timed_out
            Result.GONE -> R.string.passage_gone
            Result.RECORDING -> R.string.passage_blocked_recording
            Result.BUSY -> R.string.passage_blocked_busy
        }
    }
}

