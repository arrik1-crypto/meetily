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
import com.meetily.mobile.data.SpeakerSuggestions
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
        /** [percent] is -1 while progress is indeterminate. */
        fun onSummaryProgress(meetingId: String, percent: Int, stage: String)

        /**
         * The result is already saved when this fires. [failed] is true when
         * a notes-enhancement run changed nothing (summary runs always
         * produce at least the extractive fallback).
         */
        fun onSummaryDone(meetingId: String, failed: Boolean)
    }

    inner class SummaryBinder : Binder() {
        val service: SummaryService get() = this@SummaryService
    }

    private val observers = java.util.concurrent.CopyOnWriteArraySet<Observer>()

    /** Registers [observer]; catches it up with the current state. */
    fun addObserver(observer: Observer) {
        observers.add(observer)
        if (isRunning) observer.onSummaryProgress(currentMeetingId, percent, stage)
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    @Volatile private var stage = ""

    @Volatile var percent = -1
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder = SummaryBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val meetingId = intent.getStringExtra(EXTRA_MEETING_ID).orEmpty()
        val templateKey = intent.getStringExtra(EXTRA_TEMPLATE).orEmpty()
        if (isRunning || meetingId.isBlank()) return START_NOT_STICKY
        isRunning = true
        // Closes JobGate's "a start is on its way" window; see
        // JobGate.canStartBatch.
        JobGate.onBatchStarted()
        currentMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SUMMARY
        currentMeetingId = meetingId
        currentTitle = MeetingStore(this).load(meetingId)?.title.orEmpty()
        percent = -1
        stage = getString(
            when (currentMode) {
                MODE_NOTES -> R.string.enhancing_notes
                MODE_SPEAKERS -> R.string.suggest_analyzing
                else -> R.string.summarizing
            }
        )
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
        // Only a summary run is worth resuming after a process death: notes
        // enhancement and speaker suggestions are quick, and re-running them
        // unasked would be more surprising than useful.
        if (currentMode == MODE_SUMMARY) {
            com.meetily.mobile.data.JobQueue.markRunning(
                this, com.meetily.mobile.data.JobQueue.KIND_SUMMARY, meetingId, templateKey
            )
        }
        when (currentMode) {
            MODE_NOTES -> runNotesEnhance(meetingId)
            MODE_SPEAKERS -> runSpeakerSuggest(meetingId)
            else -> runGeneration(meetingId, templateKey)
        }
        return START_NOT_STICKY
    }

    /**
     * Notes-enhancement run (same lifecycle as a summary run): expands the
     * user's rough notes with transcript context via the configured LLM,
     * keeping the pre-enhancement notes on the meeting for revert.
     */
    private fun runNotesEnhance(meetingId: String) {
        val store = MeetingStore(this)
        val settings = AppSettings(this)
        Thread {
            val meeting = store.load(meetingId)
            if (meeting == null || meeting.notes.isBlank()) {
                main.post { finishRun(meetingId, failed = meeting != null) }
                return@Thread
            }
            LocalLlm.stageListener = { section, total ->
                if (total > 0) {
                    setProgress(
                        progressPercent(section, total),
                        getString(R.string.summary_stage_condense, section, total)
                    )
                } else {
                    setProgress(88, getString(R.string.notes_stage_writing))
                }
            }
            var failed = false
            try {
                val enhanced = LlmClient.enhanceNotes(
                    settings.llmBaseUrl, settings.llmApiKey, settings.llmModel,
                    settings.localOnlyLlm,
                    meeting.notes, meeting.transcriptTextWithSpeakers()
                )
                if (enhanced.isBlank()) throw RuntimeException("empty result")
                // Fresh copy: the user may have edited elsewhere meanwhile.
                val target = store.load(meetingId) ?: meeting
                // Keep the OLDEST original across repeat enhancements, so
                // Revert always restores the user's own notes.
                if (target.notesOriginal.isBlank()) {
                    target.notesOriginal = target.notes
                }
                target.notes = enhanced.trim()
                store.save(target)
            } catch (_: Exception) {
                failed = true // original notes stay untouched
            } finally {
                LocalLlm.stageListener = null
            }
            main.post { finishRun(meetingId, failed) }
        }.apply {
            name = "notes-enhance-service"
            start()
        }
    }

    /**
     * Speaker attribution across the whole transcript. Nothing is written to
     * the meeting: the proposals are staged for the user to accept or reject
     * when they next open it, which is what lets this run happen in the
     * background at all.
     */
    private fun runSpeakerSuggest(meetingId: String) {
        val store = MeetingStore(this)
        val settings = AppSettings(this)
        Thread {
            val meeting = store.load(meetingId)
            if (meeting == null || meeting.segments.isEmpty()) {
                main.post { finishRun(meetingId, failed = meeting != null) }
                return@Thread
            }
            val lines = meeting.segments.map { it.text to it.speaker }
            val attendees = meeting.attendees.toList()
            var failed = false
            try {
                val suggestions = LlmClient.suggestSpeakersWindowed(
                    settings.llmBaseUrl, settings.llmApiKey, settings.llmModel,
                    settings.localOnlyLlm, lines, attendees
                ) { window, total ->
                    setProgress(
                        progressPercent(window, total),
                        getString(R.string.speakers_stage_window, window, total)
                    )
                }
                // Filtered again at review time against the meeting as it
                // then stands; this pass only avoids staging obvious noise.
                SpeakerSuggestions.save(
                    this,
                    meetingId,
                    SpeakerSuggestions.applicable(suggestions, meeting.segments)
                )
            } catch (_: Exception) {
                failed = true
            }
            main.post { finishRun(meetingId, failed) }
        }.apply {
            name = "speaker-suggest-service"
            start()
        }
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
            // Effective template = instructions + the user's depth setting +
            // shared output rules (custom templates get the rules too).
            val template = SummaryTemplates.personalised(
                SummaryTemplates.effective(
                    SummaryTemplates.byKey(this, templateKey), settings.summaryDepth
                ),
                settings.userName
            )
            val useLlm = settings.useLlm && settings.llmConfigured
            val rawTranscript = meeting.transcriptText()
            val speakerTranscript = meeting.transcriptTextWithSpeakers()
            val notes = meeting.notes + photoTextBlock(meeting)
            val highlights = meeting.highlightedTexts()

            // Map-reduce progress from the local engine surfaces as stages:
            // sections map onto 0-85%, the final write sits near the end,
            // and everything else stays indeterminate.
            LocalLlm.stageListener = { section, total ->
                if (total > 0) {
                    setProgress(
                        progressPercent(section, total),
                        getString(R.string.summary_stage_condense, section, total)
                    )
                } else {
                    setProgress(88, getString(R.string.summary_stage_writing))
                }
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
            // Written from the transcript as it stands now, so any "this
            // summary is out of date" flag is settled.
            target.summaryStale = false
            store.save(target)

            main.post { finishRun(meetingId) }
        }.apply {
            name = "summary-service"
            start()
        }
    }

    private fun setProgress(newPercent: Int, text: String) {
        percent = newPercent
        stage = text
        main.post {
            if (isRunning) {
                observers.forEach {
                    it.onSummaryProgress(currentMeetingId, newPercent, text)
                }
                updateNotification(text)
            }
        }
    }

    /**
     * Android 15 caps a dataSync foreground service at six hours per day for
     * apps targeting SDK 35 and kills an app that does not stop when told.
     * A local LLM summarising a long meeting is squarely in that budget once
     * an import has already spent part of it, and nothing overrode this — so
     * the outcome was a crash rather than a stop. See ImportService.onTimeout.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Reported as a failure, because that is what it is from the user's
        // side: no summary, and re-running it is the way to get one.
        postDoneNotification(currentMeetingId, true)
        isRunning = false
        currentMeetingId = ""
        currentTitle = ""
        percent = -1
        stopForegroundCompat()
        stopSelf()
    }

    private fun finishRun(meetingId: String, failed: Boolean = false) {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        if (currentMode == MODE_SUMMARY) {
            com.meetily.mobile.data.JobQueue.finished(
                this, com.meetily.mobile.data.JobQueue.KIND_SUMMARY, meetingId
            )
        }
        observers.forEach { it.onSummaryDone(meetingId, failed) }
        // Always announce completion — on-device runs take minutes, and the
        // user asked to see the finish from anywhere.
        postDoneNotification(meetingId, failed)
        isRunning = false
        currentMeetingId = ""
        currentTitle = ""
        currentMode = MODE_SUMMARY
        percent = -1
        // Start the next queued job while this service is still foreground —
        // that is what makes the start legal on Android 12+, and it is what
        // keeps two heavy engines from ever overlapping.
        JobGate.drain(this)
        stopForegroundCompat()
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
            .setContentTitle(
                when {
                    currentMode == MODE_NOTES && currentTitle.isNotBlank() ->
                        getString(R.string.notes_notif_title_named, currentTitle)
                    currentMode == MODE_NOTES ->
                        getString(R.string.enhancing_notes)
                    currentMode == MODE_SPEAKERS && currentTitle.isNotBlank() ->
                        getString(R.string.speakers_notif_title_named, currentTitle)
                    currentMode == MODE_SPEAKERS ->
                        getString(R.string.suggest_analyzing)
                    currentTitle.isNotBlank() ->
                        getString(R.string.summary_notif_title_named, currentTitle)
                    else -> getString(R.string.summary_notif_title)
                }
            )
            .setContentText(text)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
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

    private fun postDoneNotification(meetingId: String, failed: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = when {
            currentMode == MODE_NOTES && failed -> getString(R.string.notes_failed_notif)
            currentMode == MODE_NOTES -> getString(R.string.notes_done_notif)
            currentMode == MODE_SPEAKERS && failed ->
                getString(R.string.speakers_failed_notif)
            currentMode == MODE_SPEAKERS ->
                if (SpeakerSuggestions.isPending(this, meetingId)) {
                    getString(R.string.speakers_done_notif)
                } else {
                    getString(R.string.suggest_none)
                }
            else -> getString(R.string.summary_done_notif)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(title)
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

        @Volatile var currentTitle = ""
            private set

        /** What the current run produces: a summary or enhanced notes. */
        @Volatile var currentMode = MODE_SUMMARY
            private set

        /** Condensation covers 0-85%; section N reports as it starts. */
        fun progressPercent(section: Int, total: Int): Int =
            if (total <= 0) -1 else ((section - 1) * 85 / total).coerceIn(0, 85)

        const val ACTION_START = "com.meetily.mobile.summary.START"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_TEMPLATE = "template"
        const val EXTRA_MODE = "mode"
        const val MODE_SUMMARY = "summary"
        const val MODE_NOTES = "notes"
        const val MODE_SPEAKERS = "speakers"
        private const val CHANNEL_ID = "summary"
        private const val NOTIF_ID = 50
        private const val NOTIF_DONE_ID = 51

        fun start(
            context: Context,
            meetingId: String,
            templateKey: String,
            mode: String = MODE_SUMMARY
        ) {
            val intent = Intent(context, SummaryService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MEETING_ID, meetingId)
                .putExtra(EXTRA_TEMPLATE, templateKey)
                .putExtra(EXTRA_MODE, mode)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
