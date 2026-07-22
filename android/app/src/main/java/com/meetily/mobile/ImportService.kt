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
        fun onImportProgress(percent: Int)

        /** error != null means the import failed outright (nothing kept). */
        fun onImportDone(meetingId: String?, wasCancelled: Boolean, error: String?)
    }

    inner class ImportBinder : Binder() {
        val service: ImportService get() = this@ImportService
    }

    var observer: Observer? = null
        set(value) {
            field = value
            // Late binders catch up immediately.
            if (value != null) {
                if (done) {
                    value.onImportDone(resultMeetingId, cancelled, resultError)
                } else {
                    value.onImportProgress(percent)
                }
            }
        }

    var sourceName: String = ""
        private set

    @Volatile private var cancelled = false
    @Volatile private var percent = 0
    private var done = false
    private var resultMeetingId: String? = null
    private var resultError: String? = null
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
                sourceName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                    .ifBlank { getString(R.string.import_title) }
                createChannel()
                startForegroundCompat(buildNotification(0))
                runImport(uri)
            }
        }
        return START_NOT_STICKY
    }

    private fun runImport(uri: Uri) {
        val settings = AppSettings(this)
        Thread {
            var meetingId: String? = null
            var error: String? = null
            try {
                meetingId = AudioFileImporter(this, settings).import(
                    uri = uri,
                    title = sourceName.substringBeforeLast('.').ifBlank { sourceName },
                    sourceName = sourceName,
                    onProgress = { p ->
                        percent = p
                        main.post {
                            if (isRunning) {
                                observer?.onImportProgress(p)
                                updateNotification(p)
                            }
                        }
                    },
                    cancelled = { cancelled }
                )
            } catch (e: Exception) {
                error = e.message ?: "unknown error"
            }
            main.post { finishRun(meetingId, error) }
        }.apply {
            name = "import-service"
            start()
        }
    }

    private fun finishRun(meetingId: String?, error: String?) {
        done = true
        resultMeetingId = meetingId
        resultError = error
        observer?.onImportDone(meetingId, cancelled, error)
        stopForegroundCompat()
        postCompletionNotification(meetingId, error)
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
                    getString(R.string.import_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(progress: Int): Notification {
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
            .setContentTitle(getString(R.string.import_notif_title, sourceName))
            .setContentText(getString(R.string.import_status_running, progress))
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(android.R.string.cancel), cancelIntent)
            .build()
    }

    private fun updateNotification(progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIF_ID, buildNotification(progress))
        } catch (_: SecurityException) {
        }
    }

    private fun postCompletionNotification(meetingId: String?, error: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setSilent(true)
        if (meetingId != null) {
            builder.setContentTitle(getString(R.string.import_done_notif))
                .setContentText(sourceName)
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

        const val ACTION_START = "com.meetily.mobile.import.START"
        const val ACTION_CANCEL = "com.meetily.mobile.import.CANCEL"
        const val EXTRA_NAME = "source_name"
        private const val CHANNEL_ID = "import"
        private const val NOTIF_ID = 44
        private const val NOTIF_DONE_ID = 45
    }
}
