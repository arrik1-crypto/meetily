package com.meetily.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.WhisperModels

/**
 * Foreground (dataSync) service that owns model downloads, so a 466 MB
 * Whisper model keeps downloading when the user leaves Settings or the
 * screen turns off. SettingsActivity binds as a thin observer, mirroring
 * the ImportService/ImportActivity split.
 *
 * Downloads run one at a time (parallel downloads share the same pipe and
 * finish no sooner overall), but further requests QUEUE: the user can tap a
 * whisper model, a speaker model, and an LLM back-to-back and they download
 * consecutively under one notification. Cancel stops the current download
 * and drops the queue.
 */
class ModelDownloadService : Service() {

    interface Observer {
        fun onDownloadProgress(kind: String, key: String, percent: Int)

        /** error == null means the model finished downloading (or was cancelled). */
        fun onDownloadDone(kind: String, key: String, cancelled: Boolean, error: String?)

        /** A request arrived while another download runs; it waits in line. */
        fun onDownloadQueued(kind: String, key: String) {}
    }

    inner class DownloadBinder : Binder() {
        val service: ModelDownloadService get() = this@ModelDownloadService
    }

    var observer: Observer? = null
        set(value) {
            field = value
            // Late binders catch up immediately — current item and queue.
            if (value != null && isRunning) {
                value.onDownloadProgress(currentKind, currentKey, percent)
                for ((kind, key) in queue) value.onDownloadQueued(kind, key)
            }
        }

    @Volatile private var cancelled = false
    @Volatile var percent = 0
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    /** Waiting (kind, key) pairs; main-thread confined. */
    private val queue = ArrayDeque<Pair<String, String>>()

    /** (display name, error-or-null) per finished item, for the summary. */
    private val finished = mutableListOf<Pair<String, String?>>()

    override fun onBind(intent: Intent?): IBinder = DownloadBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // Cancel means "stop downloading models": current + queued.
                cancelled = true
                queue.clear()
                queuedCount = 0
                if (!isRunning) stopSelf()
            }
            ACTION_START -> {
                val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                if (kind.isBlank() || key.isBlank()) return START_NOT_STICKY
                if (isRunning) {
                    // Queue behind the current download unless it's already
                    // running or waiting.
                    val duplicate = (currentKind == kind && currentKey == key) ||
                        queue.any { it.first == kind && it.second == key }
                    if (!duplicate) {
                        queue.add(kind to key)
                        queuedCount = queue.size
                        observer?.onDownloadQueued(kind, key)
                        updateNotification(displayName(currentKind, currentKey), percent)
                    }
                    return START_NOT_STICKY
                }
                cancelled = false
                finished.clear()
                isRunning = true
                currentKind = kind
                currentKey = key
                percent = 0
                createChannel()
                startForegroundCompat(buildNotification(displayName(kind, key), 0))
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "meetily:model-download"
                    ).apply {
                        setReferenceCounted(false)
                        acquire(2 * 60 * 60 * 1000L)
                    }
                } catch (_: Exception) {
                }
                runDownload(kind, key)
            }
        }
        return START_NOT_STICKY
    }

    private fun displayName(kind: String, key: String): String = when (kind) {
        KIND_DIARIZE -> DiarizationModels.byKey(key).displayName
        KIND_LLM -> LocalLlmModels.byKey(key).displayName
        // "whisper" kind covers all transcription models; NeMo keys route
        // to their own registry.
        else -> com.meetily.mobile.whisper.TranscriptionModels.displayName(key)
    }

    private fun runDownload(kind: String, key: String) {
        val name = displayName(kind, key)
        Thread {
            var error: String? = null
            // Per-run throttle, local to this thread (onProgress is called
            // synchronously by the downloader on it). A fresh instance per
            // run is what lets the NEXT queued item report its own early
            // progress instead of inheriting this one's final percent.
            val throttle = ProgressThrottle(NOTIFY_MIN_MS)
            try {
                val onProgress: (Int) -> Unit = { p ->
                    // Published unthrottled: the observer setter replays this
                    // to late binders, so it must never read stale.
                    percent = p
                    if (throttle.shouldPost(p, SystemClock.uptimeMillis())) {
                        main.post {
                            if (isRunning) {
                                observer?.onDownloadProgress(kind, key, p)
                                updateNotification(name, p)
                            }
                        }
                    }
                }
                when (kind) {
                    KIND_DIARIZE -> DiarizationModels.download(
                        this, DiarizationModels.byKey(key), onProgress, { cancelled }
                    )
                    KIND_LLM -> LocalLlmModels.download(
                        this, LocalLlmModels.byKey(key), onProgress, { cancelled }
                    )
                    else -> {
                        val nemo = com.meetily.mobile.whisper.NemoModels.byKeyOrNull(key)
                        if (nemo != null) {
                            com.meetily.mobile.whisper.NemoModels.download(
                                this, nemo, onProgress, { cancelled }
                            )
                        } else {
                            WhisperModels.download(
                                this, WhisperModels.byKey(key), onProgress, { cancelled }
                            )
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // Cancelled: partial already deleted by the downloader.
            } catch (e: Exception) {
                error = e.message ?: "network error"
            }
            main.post { finishRun(kind, key, name, error) }
        }.apply {
            this.name = "model-download"
            start()
        }
    }

    private fun finishRun(kind: String, key: String, name: String, error: String?) {
        observer?.onDownloadDone(kind, key, cancelled, error)
        if (!cancelled) finished.add(name to error)

        // Chain into the next queued download (unless cancel dropped it all).
        val next = if (cancelled) null else queue.removeFirstOrNull()
        if (next != null) {
            queuedCount = queue.size
            currentKind = next.first
            currentKey = next.second
            percent = 0
            // Refresh the wakelock timeout for the new item.
            try {
                wakeLock?.acquire(2 * 60 * 60 * 1000L)
            } catch (_: Exception) {
            }
            updateNotification(displayName(next.first, next.second), 0)
            runDownload(next.first, next.second)
            return
        }

        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        queue.clear()
        queuedCount = 0
        stopForegroundCompat()
        if (!cancelled) postCompletionNotification()
        isRunning = false
        stopSelf()
    }

    fun requestCancel() {
        cancelled = true
        main.post {
            queue.clear()
            queuedCount = 0
        }
    }

    // --- Notifications ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(name: String, progress: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 4,
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 5,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.download_notif_title, name))
            .setContentText(
                if (queue.isEmpty()) {
                    getString(R.string.download_status_running, progress)
                } else {
                    getString(R.string.download_status_queued, progress, queue.size)
                }
            )
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(android.R.string.cancel), cancelIntent)
            .build()
    }

    private fun updateNotification(name: String, progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIF_ID, buildNotification(name, progress))
        } catch (_: SecurityException) {
        }
    }

    private fun postCompletionNotification() {
        if (finished.isEmpty()) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setSilent(true)
        if (finished.size == 1) {
            val (name, error) = finished.first()
            if (error == null) {
                builder.setContentTitle(getString(R.string.download_done_notif, name))
            } else {
                builder.setContentTitle(getString(R.string.download_failed_notif, name))
                    .setContentText(error)
            }
        } else {
            // Queued batch: one summary with a line per model.
            val lines = finished.map { (name, error) ->
                if (error == null) getString(R.string.download_line_ok, name)
                else getString(R.string.download_line_fail, name, error)
            }
            val failures = finished.count { it.second != null }
            builder.setContentTitle(
                if (failures == 0) getString(R.string.download_batch_done, finished.size)
                else getString(R.string.download_batch_partial, failures)
            )
                .setContentText(lines.joinToString("  ·  "))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n"))
                )
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

        @Volatile var currentKind = ""
            private set

        @Volatile var currentKey = ""
            private set

        /** Downloads waiting behind the current one. */
        @Volatile var queuedCount = 0
            private set

        const val ACTION_START = "com.meetily.mobile.download.START"
        const val ACTION_CANCEL = "com.meetily.mobile.download.CANCEL"
        const val EXTRA_KIND = "kind"
        const val EXTRA_KEY = "model_key"
        const val KIND_WHISPER = "whisper"
        const val KIND_DIARIZE = "diarize"
        const val KIND_LLM = "llm"
        /**
         * Floor between posted progress updates. 500 ms keeps the UI and the
         * notification comfortably under the system's per-package update
         * shedding threshold, so every notification we pay to build is one
         * the user actually sees.
         */
        private const val NOTIFY_MIN_MS = 500L
        private const val CHANNEL_ID = "model_download"
        private const val NOTIF_ID = 46
        private const val NOTIF_DONE_ID = 47
    }
}
