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
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.ClearVoice
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.whisper.ImportEta
import com.meetily.mobile.whisper.SpeechEnhancer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Makes one meeting's clear-voice copy (SpeechEnhancer) as a foreground job,
 * so it keeps going when the screen goes off or the user leaves the meeting.
 * mediaProcessing on Android 15 and later, dataSync below, as ImportService
 * (ImportFgsType): the copy is the processing of a recorded audio file. The
 * six-hour daily allowance is per type, so the two share it; onTimeout ends
 * a copy that reaches it, keeping nothing.
 *
 * User-started from the meeting screen's player, with progress there and in
 * the notification, rather than queued behind the job queue. The copy is a
 * listening aid the user asks for while looking at the player; a copy made
 * hours later on a charger would arrive unannounced and spend storage they
 * may no longer want. It is also unlike the work JobGate serialises: one
 * thread at background priority and a half-megabyte model, not a
 * multi-gigabyte one, so it neither delays a transcript or summary (it is
 * not counted by JobGate.canStartBatch) nor competes with one for memory.
 * It does not start while a recording runs (JobGate's first rule: the live
 * recording wins), and one that is already going yields the CPU to a
 * recording started later through its background priority. A long meeting
 * asks first, with its length and a battery note (ClearVoice.isLong).
 *
 * While it runs, the meeting counts as being written (AudioRemoval.isWriting),
 * so its audio is not removed and it is not deleted or cleaned up underneath.
 * Its transcript, notes and summary are not (AudioRemoval.isWritingContent),
 * so Find & replace stays open meanwhile. The copy is written to a part file
 * and only renamed into place, and its name stored on the meeting, once it
 * is whole; anything less is deleted.
 */
class ClearVoiceService : Service() {

    /** How a run ended, for the meeting screen and the notification. */
    enum class Result { READY, CANCELLED, FAILED, NO_SPACE, TIMED_OUT, GONE, RECORDING, BUSY }

    /** The meeting screen's view of a run. Called on the main thread. */
    interface Listener {
        /** True when this listener shows [meetingId], so the outcome is seen there. */
        fun showsMeeting(meetingId: String): Boolean
        fun onClearVoiceProgress(meetingId: String, percent: Int, minutesLeft: Long?)
        fun onClearVoiceDone(meetingId: String, result: Result)
    }

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wakeLockAcquiredMs = 0L

    /**
     * Set by onTimeout: Android's daily allowance for this service type ran
     * out, the service has left the foreground, and the worker is unwinding.
     */
    @Volatile private var timedOut = false

    /** The newest start this instance was handed; finishRun stops with it. */
    private var lastStartId = 0

    /**
     * The user swiped this run's Live Update away: the rest of the run is an
     * ordinary notification (LiveUpdates). Main thread.
     */
    private var liveUpdateDismissed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (isRunning) cancelRequested = true else stopSelfResult(startId)
            }
            ACTION_LIVE_DISMISSED -> {
                if (isRunning) liveUpdateDismissed = true else stopSelfResult(startId)
            }
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_MEETING_ID)
                if (isRunning || timedOut || id.isNullOrBlank()) {
                    refuseStart(startId)
                    return START_NOT_STICKY
                }
                isRunning = true
                currentMeetingId = id
                claimedId = null
                claimedAtMs = 0L
                cancelRequested = false
                percent = 0
                minutesLeft = null
                liveUpdateDismissed = false
                createChannel()
                startForegroundCompat(buildNotification(id))
                // The foreground service keeps the process, not the CPU.
                acquireWakeLock()
                run(id)
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
            // Already in the foreground; answering again costs nothing.
            try {
                startForegroundCompat(buildNotification(currentMeetingId))
            } catch (_: Exception) {
            }
            return
        }
        try {
            createChannel()
            startForegroundCompat(buildNotification(null))
        } catch (_: Exception) {
            // The allowance is spent; nothing more can be done here.
        }
        stopForegroundCompat()
        if (!isRunning) stopSelfResult(startId)
    }

    private fun run(meetingId: String) {
        val app = applicationContext
        Thread {
            // ORT runs GTCRN on this thread (one intra-op thread), so this is
            // the priority the denoising runs at: a live recording wins.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {
            }
            val result = try {
                work(app, meetingId)
            } catch (_: Throwable) {
                Result.FAILED
            }
            main.post { finishRun(meetingId, result) }
        }.apply {
            name = "clear-voice"
            start()
        }
    }

    @WorkerThread
    private fun work(app: Context, meetingId: String): Result {
        // Checked again here: the start was asynchronous.
        if (RecordingService.isRunning) return Result.RECORDING
        if ((ImportService.isRunning && ImportService.currentMeetingId == meetingId) ||
            (SummaryService.isRunning && SummaryService.currentMeetingId == meetingId)
        ) {
            return Result.BUSY
        }
        val store = MeetingStore(app)
        val meeting = store.load(meetingId) ?: return Result.GONE
        val audio = meeting.audioFile ?: return Result.GONE
        val source = AudioStore.fileFor(app, audio)
        if (source.length() <= 0L) return Result.GONE
        val totalMs = durationMs(source)
        // Room for the copy, from its length; unknown length is not refused.
        if (totalMs > 0L) {
            val need = ClearVoice.estimatedBytes(totalMs) + SPACE_MARGIN_BYTES
            if (ModelSpaceCheck.measure(app).availableBytes < need) return Result.NO_SPACE
        }
        val part = AudioStore.fileFor(app, ClearVoice.partNameFor(audio))
        val enhancer = SpeechEnhancer.open(app) ?: return Result.FAILED
        val startedMs = SystemClock.elapsedRealtime()
        val outcome = try {
            enhancer.enhance(app, source, part, cancelled = { cancelRequested || timedOut }) { p, doneMs ->
                renewWakeLockIfStale()
                // From what is written when the length is known: the decoder
                // runs up to a block ahead of it. 100 comes once it is whole.
                val shown = if (totalMs > 0L && p < 100) {
                    (doneMs * 100 / totalMs).toInt().coerceIn(0, 99)
                } else {
                    p
                }
                val left = if (totalMs > 0L) {
                    ImportEta.remainingMinutes(doneMs, totalMs, SystemClock.elapsedRealtime() - startedMs)
                } else {
                    null
                }
                publish(meetingId, shown, left)
            }
        } finally {
            enhancer.release()
        }
        when (outcome) {
            SpeechEnhancer.Outcome.DONE -> Unit
            SpeechEnhancer.Outcome.CANCELLED -> return if (timedOut) Result.TIMED_OUT else Result.CANCELLED
            SpeechEnhancer.Outcome.NOTHING_DECODED, SpeechEnhancer.Outcome.FAILED -> return Result.FAILED
        }
        val target = AudioStore.fileFor(app, ClearVoice.copyNameFor(audio))
        target.delete()
        // Gone already when the meeting or its audio was deleted meanwhile
        // (AudioStore.delete takes the part file too).
        if (!part.renameTo(target)) {
            part.delete()
            return Result.GONE
        }
        // Stored only while the meeting still holds the recording the copy
        // was made from; otherwise nothing may point at it, and it goes.
        var belongs = false
        val saved = store.mutateIf(meetingId) { stored ->
            if (stored.audioFile != audio) {
                false
            } else {
                belongs = true
                if (stored.clearVoiceFile == target.name) {
                    false
                } else {
                    stored.clearVoiceFile = target.name
                    true
                }
            }
        }
        if (!saved || !belongs) {
            target.delete()
            return Result.GONE
        }
        return Result.READY
    }

    private fun publish(meetingId: String, p: Int, left: Long?) {
        if (p == percent && left == minutesLeft) return
        percent = p
        minutesLeft = left
        main.post {
            if (!isRunning || currentMeetingId != meetingId) return@post
            listeners.forEach { it.onClearVoiceProgress(meetingId, p, left) }
            updateNotification(meetingId)
        }
    }

    private fun finishRun(meetingId: String, result: Result) {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        val wasTimedOut = timedOut
        isRunning = false
        cancelRequested = false
        val watching = listeners.any { it.showsMeeting(meetingId) }
        listeners.forEach { it.onClearVoiceDone(meetingId, result) }
        // The meeting screen shows the outcome itself; the notification is
        // for a run that finished with nobody looking, or one Android cut.
        if (wasTimedOut || (!watching && result != Result.CANCELLED)) {
            postDoneNotification(meetingId, if (wasTimedOut) Result.TIMED_OUT else result)
        }
        timedOut = false
        if (stopSelfResult(lastStartId)) stopForegroundCompat()
    }

    /**
     * Android 15 caps mediaProcessing (and dataSync) foreground services at
     * six hours a day, and kills an app that does not stop when told. The
     * flag ends the run between decoder chunks, and leaving the foreground
     * right away is what Android asks for; finishRun stops the service once
     * the worker has landed. Nothing is kept from a run cut short.
     */
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
                PowerManager.PARTIAL_WAKE_LOCK, "meetily:clearvoice"
            ).also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            lock.acquire(WAKE_LOCK_MS)
            wakeLockAcquiredMs = System.currentTimeMillis()
        } catch (_: Exception) {
        }
    }

    /** Renewed from the progress callback, so the lock lasts as long as the work. */
    private fun renewWakeLockIfStale() {
        if (System.currentTimeMillis() - wakeLockAcquiredMs >= WAKE_LOCK_RENEW_MS) {
            acquireWakeLock()
        }
    }

    // --- Notifications ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.clearvoice_channel),
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
            Intent(this, ClearVoiceService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val left = minutesLeft
        val text = if (left != null) {
            getString(R.string.clearvoice_progress_eta, percent, minutesLabel(left))
        } else {
            getString(R.string.clearvoice_progress, percent)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(getString(R.string.clearvoice_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openMeeting(meetingId, REQUEST_OPEN))
            .addAction(0, getString(R.string.clearvoice_stop), cancelIntent)
        // Always the user's own tap on the player: a Live Update on Android
        // 16 (LiveUpdates), but not for a start that is only being refused.
        val live = isRunning && !timedOut && LiveUpdates.requestsPromotion(
            LiveUpdates.Job.CLEAR_VOICE, userStarted = true,
            sdkInt = Build.VERSION.SDK_INT, dismissed = liveUpdateDismissed
        )
        LiveUpdateBar.setBar(this, builder, live, LiveUpdates.progress(percent), percent)
        if (live) {
            LiveUpdateBar.requestPromotion(
                builder,
                PendingIntent.getService(
                    this, REQUEST_DISMISSED,
                    Intent(this, ClearVoiceService::class.java).setAction(ACTION_LIVE_DISMISSED),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        return builder.build()
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
        val ready = result == Result.READY
        val body = getString(messageFor(result))
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentTitle(
                getString(if (ready) R.string.clearvoice_notif_ready else R.string.clearvoice_notif_not_made)
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
        const val ACTION_START = "com.meetily.mobile.clearvoice.START"
        const val ACTION_CANCEL = "com.meetily.mobile.clearvoice.CANCEL"

        /** The user swiped the run's Live Update away (its delete intent). */
        const val ACTION_LIVE_DISMISSED = "com.meetily.mobile.clearvoice.LIVE_DISMISSED"
        const val EXTRA_MEETING_ID = "meeting_id"

        private const val CHANNEL_ID = "clear_voice"
        // 47 is ModelDownloadService's "downloaded" notice: sharing it would
        // let either service replace or remove the other's notification.
        private const val NOTIF_ID = 49
        private const val NOTIF_DONE_ID = 48

        /**
         * PendingIntent request codes of their own: an activity intent with
         * the same code as another service's (ImportService opens the meeting
         * screen with 3) is the same PendingIntent, and FLAG_UPDATE_CURRENT
         * would point that notification at this meeting.
         */
        private const val REQUEST_OPEN = 4701
        private const val REQUEST_CANCEL = 4702
        private const val REQUEST_DONE = 4703
        private const val REQUEST_DISMISSED = 4704

        /** Held in renewable slices rather than one long up-front bet. */
        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L

        /** Kept free beyond the copy's own size. */
        private const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024

        /** How long a start from [start] counts as on its way (see JobGate). */
        private const val CLAIM_WINDOW_MS = 10_000L

        @Volatile var isRunning = false
            private set

        /** The meeting being prepared, while [isRunning]. */
        @Volatile var currentMeetingId: String? = null
            private set

        /** The latest run's progress, for a screen that opens mid-run. */
        @Volatile var percent = 0
            private set

        @Volatile var minutesLeft: Long? = null
            private set

        @Volatile private var cancelRequested = false

        /**
         * [start] was called and onStartCommand has not run yet: the start
         * is asynchronous, and the meeting must count as busy meanwhile.
         */
        @Volatile private var claimedId: String? = null
        @Volatile private var claimedAtMs = 0L

        private val listeners = CopyOnWriteArraySet<Listener>()

        private fun claimed(): String? =
            claimedId?.takeIf {
                claimedAtMs != 0L && SystemClock.elapsedRealtime() - claimedAtMs < CLAIM_WINDOW_MS
            }

        /** True while a copy of [meetingId] is being made, or about to be. */
        fun isPreparing(meetingId: String): Boolean =
            (isRunning && currentMeetingId == meetingId) || (!isRunning && claimed() == meetingId)

        /** The meeting a copy is being made of, if any. */
        fun preparingId(): String? = if (isRunning) currentMeetingId else claimed()

        fun addListener(listener: Listener) {
            listeners.add(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        /**
         * Why a copy of [meetingId] cannot be started now, as a message, or
         * null when it can. The run checks the recording again itself.
         */
        fun blockReason(context: Context, meetingId: String): Int? = when {
            RecordingService.isRunning -> R.string.clearvoice_blocked_recording
            isRunning || claimed() != null ->
                if (preparingId() == meetingId) null else R.string.clearvoice_blocked_other
            AudioRemoval.isWritingContent(context, meetingId) -> R.string.clearvoice_blocked_busy
            else -> null
        }

        /**
         * Starts making the copy of [meetingId]. Main thread, from a screen
         * in the foreground. Returns false when the start was refused.
         */
        fun start(context: Context, meetingId: String): Boolean {
            if (isRunning || claimed() != null) return false
            // The screen shows this run's progress from here on, not the last one's.
            percent = 0
            minutesLeft = null
            claimedId = meetingId
            claimedAtMs = SystemClock.elapsedRealtime()
            val intent = Intent(context, ClearVoiceService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MEETING_ID, meetingId)
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

        /** Stops the run between decoder chunks; nothing it made is kept. */
        fun cancel() {
            if (isRunning) cancelRequested = true
        }

        /**
         * The recording's length from its container, or 0 when unknown.
         * A recording is raw ADTS, whose length is found by walking every
         * frame header, which is slow on a long meeting: a worker only.
         */
        @WorkerThread
        fun durationMs(file: java.io.File): Long {
            val retriever = android.media.MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } catch (_: Throwable) {
                0L
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        }

        /** The message for a finished run's [result]. */
        fun messageFor(result: Result): Int = when (result) {
            Result.READY -> R.string.clearvoice_ready
            Result.CANCELLED -> R.string.clearvoice_cancelled
            Result.FAILED -> R.string.clearvoice_failed
            Result.NO_SPACE -> R.string.clearvoice_no_space
            Result.TIMED_OUT -> R.string.clearvoice_timed_out
            Result.GONE -> R.string.clearvoice_gone
            Result.RECORDING -> R.string.clearvoice_blocked_recording
            Result.BUSY -> R.string.clearvoice_blocked_busy
        }
    }
}
