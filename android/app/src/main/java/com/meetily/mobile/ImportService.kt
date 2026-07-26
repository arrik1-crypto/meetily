package com.meetily.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.whisper.AudioFileImporter

/**
 * Foreground (dataSync) service that owns a file-import run, so long
 * transcriptions survive the screen turning off or the user leaving.
 * ImportActivity is a thin bound observer, mirroring the
 * RecordingService/RecordingActivity split. One import at a time.
 */
class ImportService : Service() {

    interface Observer {
        /**
         * [meetingId] is null until the run knows which meeting it is filling
         * — the home screen uses it to put progress inside that meeting's own
         * card instead of a floating banner. [detail] is a human-readable
         * "how far, how much longer" line, blank until it can be estimated.
         */
        fun onImportProgress(meetingId: String?, percent: Int, detail: String)

        /**
         * error != null means the import failed outright (nothing kept);
         * warning != null means it finished but covered only part of the
         * file (partial transcript kept).
         */
        fun onImportDone(
            meetingId: String?,
            wasCancelled: Boolean,
            error: String?,
            warning: String?
        )
    }

    inner class ImportBinder : Binder() {
        val service: ImportService get() = this@ImportService
    }

    private val observers = java.util.concurrent.CopyOnWriteArraySet<Observer>()

    /** Registers [observer]; late binders catch up immediately. */
    fun addObserver(observer: Observer) {
        observers.add(observer)
        if (done) {
            observer.onImportDone(resultMeetingId, cancelled, resultError, resultWarning)
        } else {
            observer.onImportProgress(currentMeetingId, percent, detail)
        }
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    var sourceName: String = ""
        private set

    /** Per-run model override (see ImportActivity's picker); null = default. */
    private var modelKey: String? = null

    /** Set when this run is a second pass over an existing meeting's audio. */
    private var recheckMeetingId: String? = null

    /** Staged copy this run adopts, when it came off the import queue. */
    private var adoptFile: String? = null

    /** When this run began, so its throughput can be recorded on finish. */
    @Volatile private var runStartedMs = 0L

    @Volatile private var cancelled = false
    @Volatile private var percent = 0
    @Volatile private var detail = ""
    /** When the wake lock was last taken, so a long run can renew it. */
    @Volatile private var wakeLockAcquiredMs = 0L
    private var done = false
    private var resultMeetingId: String? = null
    private var resultError: String? = null
    private var resultWarning: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder = ImportBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                if (!isRunning) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val uri = intent.data
                if (isRunning || uri == null) return START_NOT_STICKY
                isRunning = true
                // A bound client (the home screen) keeps this instance alive
                // past stopSelf(), so a second run can land on the same
                // object. Without this reset it would start already finished
                // — or already cancelled.
                done = false
                cancelled = false
                percent = 0
                detail = ""
                resultMeetingId = null
                resultError = null
                resultWarning = null
                recheckMeetingId = intent.getStringExtra(EXTRA_RECHECK_MEETING_ID)
                adoptFile = intent.getStringExtra(EXTRA_ADOPT_FILE)
                isRecheck = recheckMeetingId != null
                currentMeetingId = recheckMeetingId
                sourceName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                    .ifBlank { getString(R.string.import_title) }
                modelKey = intent.getStringExtra(EXTRA_MODEL)
                createChannel()
                startForegroundCompat(buildNotification(0))
                // A dataSync service keeps the process alive but NOT the CPU:
                // without this, a long import stalls or dies once the screen
                // has been off for a while.
                runStartedMs = System.currentTimeMillis()
                acquireWakeLock()
                if (recheckMeetingId != null) {
                    com.meetily.mobile.data.JobQueue.markRunning(
                        this, com.meetily.mobile.data.JobQueue.KIND_CHECK,
                        recheckMeetingId!!, modelKey.orEmpty()
                    )
                }
                runImport(uri)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Takes (or renews) the CPU wake lock.
     *
     * Every acquisition carries a timeout so a wedged thread can never pin
     * the CPU forever, but a single up-front timeout is a trap: a long import
     * on a heavy model outlives it, the lock quietly releases, the device
     * suspends with the screen off, and the run stalls indefinitely behind a
     * progress bar that still looks alive. Renewing from the progress
     * callback keeps the lock alive exactly as long as work is happening,
     * and no longer.
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "meetily:import"
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
        if (System.currentTimeMillis() - wakeLockAcquiredMs >= WAKE_LOCK_RENEW_MS) {
            acquireWakeLock()
        }
    }

    private fun runImport(uri: Uri) {
        val settings = AppSettings(this)
        Thread {
            var result: AudioFileImporter.Result? = null
            var error: String? = null
            try {
                result = AudioFileImporter(this, settings).import(
                    uri = uri,
                    title = sourceName.substringBeforeLast('.').ifBlank { sourceName },
                    sourceName = sourceName,
                    modelKey = modelKey,
                    recheckMeetingId = recheckMeetingId,
                    adoptFile = adoptFile,
                    onMeetingCreated = { id ->
                        currentMeetingId = id
                        main.post {
                            if (isRunning) {
                                observers.forEach {
                                    it.onImportProgress(id, percent, detail)
                                }
                            }
                        }
                    },
                    onProgress = { p, text ->
                        percent = p
                        detail = text
                        renewWakeLockIfStale()
                        main.post {
                            if (isRunning) {
                                val id = currentMeetingId
                                observers.forEach { it.onImportProgress(id, p, text) }
                                updateNotification(p, text)
                            }
                        }
                    },
                    recordingActive = { RecordingService.isRunning },
                    cancelled = { cancelled }
                )
            } catch (e: Exception) {
                error = e.message ?: "unknown error"
            }
            // What this model actually cost on THIS phone. Every run has
            // always measured it and thrown it away; keeping it is what lets
            // the app quote an honest estimate before the next one starts.
            result?.let { r ->
                com.meetily.mobile.whisper.ModelSpeed.record(
                    this,
                    modelKey ?: AppSettings(this).whisperModel,
                    r.coveredMs,
                    System.currentTimeMillis() - runStartedMs
                )
            }
            val warning = result?.takeIf { !cancelled && it.truncated }?.let { r ->
                if (r.totalMs > 0) {
                    getString(
                        R.string.import_truncated,
                        formatMinutes(r.coveredMs), formatMinutes(r.totalMs)
                    )
                } else {
                    getString(R.string.import_stopped_early, formatMinutes(r.coveredMs))
                }
            }
            main.post { finishRun(result?.meetingId, error, warning) }
        }.apply {
            name = "import-service"
            start()
        }
    }

    private fun finishRun(meetingId: String?, error: String?, warning: String?) {
        done = true
        resultMeetingId = meetingId
        resultError = error
        resultWarning = warning
        val recheckId = recheckMeetingId
        if (recheckId != null && (cancelled || error != null || warning != null)) {
            // Nothing was written to the meeting, and a partial draft would
            // produce a comparison where the whole uncovered tail reads as
            // "the new model went silent here" — which, accepted, would cut
            // the transcript down to whatever the pass managed to reach.
            com.meetily.mobile.data.TranscriptDraft.delete(this, recheckId)
        }
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        // Renamed into the meeting on success; anything left here means the
        // run failed before that, and the staged copy would just leak.
        adoptFile?.let { com.meetily.mobile.data.AudioStore.delete(this, it) }
        adoptFile = null
        if (recheckId != null) {
            com.meetily.mobile.data.JobQueue.finished(
                this, com.meetily.mobile.data.JobQueue.KIND_CHECK, recheckId
            )
        }
        observers.forEach { it.onImportDone(meetingId, cancelled, error, warning) }
        if (!cancelled) postCompletionNotification(meetingId, error, warning)
        // isRecheck / currentMeetingId deliberately survive the run: an
        // observer that binds after the finish still needs to know what just
        // happened. Both are reset by the next ACTION_START.
        isRunning = false
        // Hand over to the next queued job while this service is still in the
        // foreground — that is what makes starting one legal at all on
        // Android 12+, and it is what serialises the queue.
        JobGate.drain(this)
        stopForegroundCompat()
        stopSelf()
    }

    private fun formatMinutes(ms: Long): String {
        val totalMin = (ms + 30_000) / 60_000
        return if (totalMin >= 60) {
            getString(R.string.duration_h_min, totalMin / 60, totalMin % 60)
        } else {
            getString(R.string.duration_min, totalMin)
        }
    }

    fun requestCancel() {
        cancelled = true
    }

    // --- Notifications ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.import_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(progress: Int, text: String = detail): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, ImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ImportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(
                if (recheckMeetingId != null) {
                    getString(R.string.check_notif_title, sourceName)
                } else {
                    getString(R.string.import_notif_title, sourceName)
                }
            )
            .setContentText(
                text.ifBlank { getString(R.string.import_status_running, progress) }
            )
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(android.R.string.cancel), cancelIntent)
            .build()
    }

    private fun updateNotification(progress: Int, text: String = detail) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIF_ID, buildNotification(progress, text))
        } catch (_: SecurityException) {
        }
    }

    private fun postCompletionNotification(
        meetingId: String?,
        error: String?,
        warning: String?
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setSilent(true)
        if (meetingId != null && recheckMeetingId != null && warning != null) {
            builder.setContentTitle(getString(R.string.check_incomplete_notif))
                .setContentText(getString(R.string.check_incomplete_body))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.check_incomplete_body))
                )
        } else if (meetingId != null && recheckMeetingId != null) {
            // The second pass is done but nothing has changed yet — the whole
            // point is that the user reviews it first.
            builder.setContentTitle(getString(R.string.check_ready_notif))
                .setContentText(getString(R.string.check_ready_notif_body))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 4,
                        Intent(this, TranscriptCheckActivity::class.java)
                            .putExtra(TranscriptCheckActivity.EXTRA_MEETING_ID, meetingId)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
        } else if (meetingId != null) {
            builder.setContentTitle(
                if (warning != null) getString(R.string.import_incomplete_notif)
                else getString(R.string.import_done_notif)
            )
                .setContentText(warning ?: sourceName)
                .setStyle(
                    warning?.let { NotificationCompat.BigTextStyle().bigText(it) }
                )
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 3,
                        Intent(this, MeetingDetailActivity::class.java)
                            .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
        } else {
            builder.setContentTitle(getString(R.string.import_failed_notif))
                .setContentText(error ?: "")
        }
        try {
            manager.notify(NOTIF_DONE_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
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
        @Volatile var isRunning = false
            private set

        /** The meeting this run is filling, once known. */
        @Volatile var currentMeetingId: String? = null
            private set

        /** True while the run is a second pass over an existing meeting. */
        @Volatile var isRecheck = false
            private set

        const val ACTION_START = "com.meetily.mobile.import.START"
        const val ACTION_CANCEL = "com.meetily.mobile.import.CANCEL"
        const val EXTRA_NAME = "source_name"
        const val EXTRA_MODEL = "model_key"
        const val EXTRA_RECHECK_MEETING_ID = "recheck_meeting_id"
        const val EXTRA_ADOPT_FILE = "adopt_file"
        private const val CHANNEL_ID = "import"
        private const val NOTIF_ID = 44
        private const val NOTIF_DONE_ID = 45

        /** Held in renewable slices rather than one long up-front bet. */
        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L
    }
}
