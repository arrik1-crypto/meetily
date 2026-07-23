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
import androidx.core.app.NotificationCompat
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.WhisperModels

/**
 * Foreground (dataSync) service that owns a model download, so a 466 MB
 * Whisper model keeps downloading when the user leaves Settings or the
 * screen turns off. SettingsActivity binds as a thin observer, mirroring
 * the ImportService/ImportActivity split. One download at a time.
 */
class ModelDownloadService : Service() {

    interface Observer {
        fun onDownloadProgress(kind: String, key: String, percent: Int)

        /** error == null means the model finished downloading (or was cancelled). */
        fun onDownloadDone(kind: String, key: String, cancelled: Boolean, error: String?)
    }

    inner class DownloadBinder : Binder() {
        val service: ModelDownloadService get() = this@ModelDownloadService
    }

    var observer: Observer? = null
        set(value) {
            field = value
            // Late binders catch up immediately.
            if (value != null && isRunning) {
                value.onDownloadProgress(currentKind, currentKey, percent)
            }
        }

    @Volatile private var cancelled = false
    @Volatile var percent = 0
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder = DownloadBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                if (!isRunning) stopSelf()
            }
            ACTION_START -> {
                val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                if (isRunning || kind.isBlank() || key.isBlank()) return START_NOT_STICKY
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
        else -> WhisperModels.byKey(key).displayName
    }

    private fun runDownload(kind: String, key: String) {
        val name = displayName(kind, key)
        Thread {
            var error: String? = null
            try {
                val onProgress: (Int) -> Unit = { p ->
                    percent = p
                    main.post {
                        if (isRunning) {
                            observer?.onDownloadProgress(kind, key, p)
                            updateNotification(name, p)
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
                    else -> WhisperModels.download(
                        this, WhisperModels.byKey(key), onProgress, { cancelled }
                    )
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
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        observer?.onDownloadDone(kind, key, cancelled, error)
        stopForegroundCompat()
        if (!cancelled) postCompletionNotification(name, error)
        isRunning = false
        stopSelf()
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
            .setContentText(getString(R.string.download_status_running, progress))
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

    private fun postCompletionNotification(name: String, error: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setSilent(true)
        if (error == null) {
            builder.setContentTitle(getString(R.string.download_done_notif, name))
        } else {
            builder.setContentTitle(getString(R.string.download_failed_notif, name))
                .setContentText(error)
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

        const val ACTION_START = "com.meetily.mobile.download.START"
        const val ACTION_CANCEL = "com.meetily.mobile.download.CANCEL"
        const val EXTRA_KIND = "kind"
        const val EXTRA_KEY = "model_key"
        const val KIND_WHISPER = "whisper"
        const val KIND_DIARIZE = "diarize"
        const val KIND_LLM = "llm"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIF_ID = 46
        private const val NOTIF_DONE_ID = 47
    }
}
