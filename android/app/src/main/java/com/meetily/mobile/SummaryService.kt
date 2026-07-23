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
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.llm.LocalLlm
import com.meetily.mobile.summarize.ActionItems
import com.meetily.mobile.summarize.ExtractiveSummarizer
import com.meetily.mobile.summarize.LlmClient
import com.meetily.mobile.summarize.SummaryTemplates

/**
 * Owns a summary generation run, so a multi-minute on-device generation
 * survives rotation, navigation, and the screen turning off — the meeting
 * page is a thin observer (the ImportService pattern). The service loads a
 * fresh copy of the meeting, generates, saves the result itself, and only
 * then reports done: the summary can no longer be lost to a destroyed
 * activity.
 */
class SummaryService : Service() {

    interface Observer {
        fun onSummaryStage(stage: String)

        /** The summary (or its fallback text) is already saved when this fires. */
        fun onSummaryDone(meetingId: String)
    }

    inner class SummaryBinder : Binder() {
        val service: SummaryService get() = this@SummaryService
    }

    var observer: Observer? = null
        set(value) {
            field = value
            if (value != null && isRunning) value.onSummaryStage(stage)
        }

    @Volatile private var stage = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder = SummaryBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val meetingId = intent.getStringExtra(EXTRA_MEETING_ID).orEmpty()
        val templateKey = intent.getStringExtra(EXTRA_TEMPLATE).orEmpty()
        if (isRunning || meetingId.isBlank()) return START_NOT_STICKY
        isRunning = true
        currentMeetingId = meetingId
        stage = getString(R.string.summarizing)
        createChannel()
        startForegroundCompat()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "meetily:summary"
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
        runGeneration(meetingId, templateKey)
        return START_NOT_STICKY
    }

    private fun runGeneration(meetingId: String, templateKey: String) {
        val store = MeetingStore(this)
        val settings = AppSettings(this)
        Thread {
            val meeting = store.load(meetingId)
            if (meeting == null) {
                main.post { finishRun(meetingId) }
                return@Thread
            }
            val template = SummaryTemplates.byKey(this, templateKey)
            val useLlm = settings.useLlm && settings.llmConfigured
            val rawTranscript = meeting.transcriptText()
            val speakerTranscript = meeting.transcriptTextWithSpeakers()
            val notes = meeting.notes + photoTextBlock(meeting)
            val highlights = meeting.highlightedTexts()

            // Map-reduce progress from the local engine surfaces as stages.
            LocalLlm.stageListener = { done, total ->
                setStage(getString(R.string.summary_stage_condense, done, total))
            }
            var parsedItems: List<com.meetily.mobile.data.ActionItem>? = null
            val result = try {
                if (useLlm) {
                    val raw = LlmClient.summarize(
                        settings.llmBaseUrl, settings.llmApiKey, settings.llmModel,
                        settings.localOnlyLlm, speakerTranscript, notes,
                        meeting.attendees.toList(), highlights, template
                    )
                    val (clean, items) = ActionItems.splitLlmOutput(raw)
                    parsedItems = items
                    clean
                } else {
                    ExtractiveSummarizer.summarize(
                        rawTranscript, notes, highlights, template.extractiveActionsOnly
                    )
                }
            } catch (e: Exception) {
                val fallback = ExtractiveSummarizer.summarize(
                    rawTranscript, notes, highlights, template.extractiveActionsOnly
                )
                getString(R.string.llm_failed_fallback, e.message ?: "unknown error") +
                    "\n\n" + fallback
            } finally {
                LocalLlm.stageListener = null
            }
            val finalItems = parsedItems
                ?: ActionItems.fromMeetingContent(meeting.segments.toList(), notes)

            // Save onto a FRESH copy: the user may have edited the meeting
            // (notes, tags, speaker names) while the model was thinking.
            val target = store.load(meetingId) ?: meeting
            target.summary = result
            target.actionItems = finalItems.toMutableList()
            store.save(target)

            main.post { finishRun(meetingId) }
        }.apply {
            name = "summary-service"
            start()
        }
    }

    private fun setStage(text: String) {
        stage = text
        main.post {
            if (isRunning) {
                observer?.onSummaryStage(text)
                updateNotification(text)
            }
        }
    }

    private fun finishRun(meetingId: String) {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        val watched = observer != null
        observer?.onSummaryDone(meetingId)
        stopForegroundCompat()
        if (!watched) postDoneNotification(meetingId)
        isRunning = false
        currentMeetingId = ""
        stopSelf()
    }

    // --- Notifications ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.summary_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(getString(R.string.summary_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openMeetingIntent(currentMeetingId, 6))
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIF_ID, buildNotification(text))
        } catch (_: SecurityException) {
        }
    }

    private fun postDoneNotification(meetingId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(getString(R.string.summary_done_notif))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openMeetingIntent(meetingId, 7))
            .build()
        try {
            manager.notify(NOTIF_DONE_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun openMeetingIntent(meetingId: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            this, code,
            Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun startForegroundCompat() {
        val notification = buildNotification(stage)
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

    private fun photoTextBlock(meeting: com.meetily.mobile.data.Meeting): String {
        if (meeting.photoTexts.isEmpty()) return ""
        return "\n\n[Text captured from attached photos and whiteboards]\n" +
            meeting.photoTexts.values.joinToString("\n---\n").take(4_000)
    }

    companion object {
        @Volatile var isRunning = false
            private set

        @Volatile var currentMeetingId = ""
            private set

        const val ACTION_START = "com.meetily.mobile.summary.START"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_TEMPLATE = "template"
        private const val CHANNEL_ID = "summary"
        private const val NOTIF_ID = 50
        private const val NOTIF_DONE_ID = 51

        fun start(context: Context, meetingId: String, templateKey: String) {
            val intent = Intent(context, SummaryService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MEETING_ID, meetingId)
                .putExtra(EXTRA_TEMPLATE, templateKey)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
