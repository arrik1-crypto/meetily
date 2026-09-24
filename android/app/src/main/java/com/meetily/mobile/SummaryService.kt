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
    @Volatile private var wakeLockAcquiredMs = 0L
    private val main = Handler(Looper.getMainLooper())

    /** This run was deferred until the phone was charging; see [PowerWatch]. */
    private var chargingOnly = false

    /** Kept so a stopped run can be requeued with the template it was given. */
    private var runTemplateKey = ""

    /** Queued by AutoSummary, so its consent is re-checked; see runGeneration. */
    private var runAuto = false

    /**
     * Who decided this run's fate: still running, stopped by the unplug, or
     * committed to saving. One atomic value rather than a flag the main thread
     * sets and the worker reads, so a stop that lands after the worker has
     * committed is ignored instead of announcing — and requeueing — a summary
     * that was just saved.
     */
    private val unplugState = java.util.concurrent.atomic.AtomicInteger(RUN_ACTIVE)

    /** The charger came out mid-run, so this summary is not being written. */
    private val stoppedByUnplug: Boolean
        get() = unplugState.get() == RUN_STOPPED

    /**
     * Set by onTimeout. The worker is unwinding and the instance must refuse
     * new runs until finishRun lands, as in ImportService.
     */
    @Volatile private var timedOut = false

    /** The newest start this instance was handed; see ImportService.lastStartId. */
    private var lastStartId = 0

    private val powerWatch = PowerWatch { stopForUnplug() }

    override fun onBind(intent: Intent?): IBinder = SummaryBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val meetingId = intent.getStringExtra(EXTRA_MEETING_ID).orEmpty()
        val templateKey = intent.getStringExtra(EXTRA_TEMPLATE).orEmpty()
        if (isRunning || timedOut || meetingId.isBlank()) {
            refuseStart(startId)
            return START_NOT_STICKY
        }
        isRunning = true
        // Closes JobGate's "a start is on its way" window; see
        // JobGate.canStartBatch.
        JobGate.onBatchStarted()
        currentMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SUMMARY
        chargingOnly = intent.getBooleanExtra(EXTRA_CHARGING_ONLY, false)
        runTemplateKey = templateKey
        runAuto = intent.getBooleanExtra(EXTRA_AUTO, false)
        unplugState.set(RUN_ACTIVE)
        lastStopped = false
        // finishRun clears the abort flag when a stopped run ends; clearing it
        // here as well means nothing left over can kill this run's first
        // decode.
        LocalLlm.clearAbort()
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
        acquireWakeLock()
        // Only the deferred summary pass is watched. A summary the user
        // tapped for themselves while plugged in is theirs to wait for, and
        // notes/speaker runs are seconds rather than minutes. And only on
        // device: the stop cannot reach an HTTP request, so for an endpoint
        // it saved no battery and threw away a finished (and paid-for)
        // summary, only to upload the transcript again on the next charge.
        powerWatch.arm(
            this,
            chargingOnly && currentMode == MODE_SUMMARY && LocalLlm.isSelected()
        )
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
            val listener: (Int, Int) -> Unit = { section, total ->
                if (total > 0) {
                    setProgress(
                        progressPercent(section, total),
                        getString(R.string.summary_stage_condense, section, total)
                    )
                } else {
                    setProgress(88, getString(R.string.notes_stage_writing))
                }
            }
            LocalLlm.stageListener = listener
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
                // Only our own: a timed-out worker finishing late must not
                // wipe the listener of a newer run waiting on the model.
                if (LocalLlm.stageListener === listener) LocalLlm.stageListener = null
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
                // The segments are passed so the file records which
                // transcript these indices belong to; stillMatch() checks it
                // at review time.
                SpeakerSuggestions.save(
                    this,
                    meetingId,
                    SpeakerSuggestions.applicable(suggestions, meeting.segments),
                    meeting.segments
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
            // An automatic summary may have waited hours for a charger. If the
            // consent it was queued under has since been withdrawn — the
            // engine switched to an endpoint, or auto-summary turned off —
            // nothing is sent anywhere. JobGate checks this at drain; this
            // covers a settings change between then and now.
            if (runAuto && !settings.autoSummaryAllowed) {
                main.post { finishRun(meetingId, quiet = true) }
                return@Thread
            }
            val useLlm = settings.useLlm && settings.llmConfigured
            val rawTranscript = meeting.transcriptText()
            val speakerTranscript = meeting.transcriptTextWithSpeakers()
            val notes = meeting.notes + photoTextBlock(meeting)
            val highlights = meeting.highlightedTexts()

            // Map-reduce progress from the local engine surfaces as stages:
            // sections map onto 0-85%, the final write sits near the end,
            // and everything else stays indeterminate.
            val listener: (Int, Int) -> Unit = { section, total ->
                if (total > 0) {
                    setProgress(
                        progressPercent(section, total),
                        getString(R.string.summary_stage_condense, section, total)
                    )
                } else {
                    setProgress(88, getString(R.string.summary_stage_writing))
                }
            }
            LocalLlm.stageListener = listener
            var parsedItems: List<com.meetily.mobile.data.ActionItem>? = null
            var generationFailed = false
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
                generationFailed = true
                val fallback = ExtractiveSummarizer.summarize(
                    rawTranscript, notes, highlights, template.extractiveActionsOnly
                )
                getString(R.string.llm_failed_fallback, e.message ?: "unknown error") +
                    "\n\n" + fallback
            } finally {
                // Only our own: a timed-out worker finishing late must not
                // wipe the listener of a newer run waiting on the model.
                if (LocalLlm.stageListener === listener) LocalLlm.stageListener = null
            }
            // A generation that returned normally is a real summary, whatever
            // arrived meanwhile: keep it, and let a late unplug stop go.
            // Otherwise commit only if no stop got here first.
            val committed = if (generationFailed) {
                unplugState.compareAndSet(RUN_ACTIVE, RUN_COMMITTED)
            } else {
                unplugState.set(RUN_COMMITTED)
                true
            }
            if (!committed || (generationFailed && timedOut)) {
                // Nothing is written. The catch above has already turned the
                // stopped generation into the extractive fallback, and saving
                // THAT would quietly downgrade the meeting's summary as the
                // price of unplugging a cable — or of Android's time limit.
                main.post { finishRun(meetingId) }
                return@Thread
            }
            val finalItems = parsedItems
                ?: ActionItems.fromMeetingContent(meeting.segments.toList(), notes)

            // Save onto a FRESH copy: the user may have edited the meeting
            // (notes, tags, speaker names) while the model was thinking.
            val target = store.load(meetingId) ?: meeting
            target.summary = result
            /*
             * Carry the user's state across a regeneration.
             *
             * The list was replaced wholesale, which threw away every tick
             * the user had made and every reminder they had set — and worse,
             * left the alarms armed, so a notification still fired for an
             * item that no longer existed. Matching on normalised task text
             * is the same identity Reminders already uses as its key.
             */
            val previous = target.actionItems.associateBy { it.task.trim().lowercase() }
            val merged = finalItems.map { fresh ->
                val old = previous[fresh.task.trim().lowercase()]
                if (old == null) fresh
                else fresh.copy(done = old.done, remindAtMs = old.remindAtMs)
            }
            // Anything the new summary dropped takes its alarm with it.
            val keptKeys = merged.map { it.task.trim().lowercase() }.toSet()
            for (gone in target.actionItems) {
                if (gone.task.trim().lowercase() !in keptKeys && gone.remindAtMs != null) {
                    runCatching {
                        com.meetily.mobile.reminders.Reminders
                            .cancelActionItem(this, meetingId, gone.task)
                    }
                }
            }
            target.actionItems = merged.toMutableList()
            // Written from the transcript as it stands now, so any "this
            // summary is out of date" flag is settled.
            target.summaryStale = false
            val saved = store.save(target)
            if (!saved) {
                android.util.Log.e(
                    "SummaryService",
                    "summary for $meetingId could not be written to disk"
                )
            }

            main.post { finishRun(meetingId) }
        }.apply {
            name = "summary-service"
            start()
        }
    }

    /**
     * A renewable slice rather than one fixed grant.
     *
     * A local summary is a map-reduce over however many sections the
     * transcript has, with no wall-clock budget anywhere in LocalLlm, so a
     * long meeting outlives any single lock. When it expired the device
     * suspended with the screen off and the run stalled indefinitely behind a
     * progress bar that still looked alive — and because JobQueue still had
     * the job marked running, every queued import sat behind it. Renewing
     * from the progress callback keeps the lock alive exactly as long as work
     * is happening, and no longer. Same shape as ImportService.
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "meetily:summary"
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
        // Released by onTimeout; a worker still unwinding must not take it back.
        if (timedOut) return
        if (System.currentTimeMillis() - wakeLockAcquiredMs >= WAKE_LOCK_RENEW_MS) {
            acquireWakeLock()
        }
    }

    /**
     * Turns down a start without breaking the startForegroundService
     * contract; see ImportService.refuseStart.
     */
    private fun refuseStart(startId: Int) {
        if (isRunning && !timedOut) return // already foreground; the run stops itself
        try {
            createChannel()
            startForegroundCompat()
        } catch (_: Exception) {
            // Foreground time already exhausted; nothing more can be done.
        }
        stopForegroundCompat()
        // A timed-out run's own finish stops the service, with this start's id.
        if (!isRunning) stopSelfResult(startId)
    }

    private fun setProgress(newPercent: Int, text: String) {
        percent = newPercent
        stage = text
        renewWakeLockIfStale()
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
        if (!isRunning) {
            stopForegroundCompat()
            stopSelfResult(lastStartId)
            return
        }
        timedOut = true
        powerWatch.disarm(this)
        // The worker is one long native call; without this it went on
        // generating after the service stopped, next to whatever heavy job
        // the gate let in once isRunning was cleared.
        LocalLlm.requestAbort()
        // Reported as a failure, because that is what it is from the user's
        // side: no summary, and re-running it is the way to get one.
        postTimeoutNotification(currentMeetingId)
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        // isRunning deliberately NOT cleared: as in ImportService.onTimeout,
        // the worker is still unwinding and finishRun clears it when the run
        // is genuinely over, so no second heavy job starts beside it.
        stopForegroundCompat()
    }

    /**
     * Ends a charging-deferred run because the phone came off power, and puts
     * the job back on the queue still marked as waiting for a charger — so
     * plugging back in picks it up exactly as the original deferral would.
     */
    private fun stopForUnplug() {
        // Loses to a worker that has already committed its result.
        if (!isRunning || !unplugState.compareAndSet(RUN_ACTIVE, RUN_STOPPED)) return
        setProgress(percent, getString(R.string.unplugged_stopped_title))
        // The generation is one long native call; this is what ends it.
        LocalLlm.requestAbort()
    }

    /** [quiet]: nothing ran (withdrawn consent), so nothing is announced. */
    private fun finishRun(meetingId: String, failed: Boolean = false, quiet: Boolean = false) {
        // The worker has returned by now, so the stop an unplug or a timeout
        // raised has done its job. Left raised it outlived the run: every
        // other on-device feature — Ask, catch-up, titles, briefs — failed
        // with "generation was stopped" until the next summary started.
        LocalLlm.clearAbort()
        // And the model itself goes. The gate exists so a multi-GB LLM is
        // never resident beside Whisper, and the job drained next is often
        // exactly that. The generation has returned, so this frees at once.
        LocalLlm.release()
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        wakeLockAcquiredMs = 0L
        powerWatch.disarm(this)
        val wasTimedOut = timedOut
        if (currentMode == MODE_SUMMARY) {
            com.meetily.mobile.data.JobQueue.finished(
                this, com.meetily.mobile.data.JobQueue.KIND_SUMMARY, meetingId
            )
            // After finished(), so the marker and the requeued job are never
            // both in the queue for the same meeting.
            if (stoppedByUnplug) requeueForCharging(meetingId)
        }
        lastStopped = stoppedByUnplug
        observers.forEach { it.onSummaryDone(meetingId, failed) }
        if (stoppedByUnplug) {
            postUnpluggedNotification(currentTitle)
        } else if (!wasTimedOut && !quiet) {
            // Always announce completion — on-device runs take minutes, and
            // the user asked to see the finish from anywhere. A timeout has
            // already said what happened.
            postDoneNotification(meetingId, failed)
        }
        unplugState.set(RUN_ACTIVE)
        chargingOnly = false
        runAuto = false
        timedOut = false
        isRunning = false
        currentMeetingId = ""
        currentTitle = ""
        currentMode = MODE_SUMMARY
        percent = -1
        // Start the next queued job while this service is still foreground —
        // that is what makes the start legal on Android 12+, and it is what
        // keeps two heavy engines from ever overlapping. Not after a timeout:
        // the foreground is already gone and the dataSync budget spent.
        if (!wasTimedOut) JobGate.drain(this)
        // False when drain just started another job on this same class; its
        // pending start keeps the service, and the notification is replaced.
        if (stopSelfResult(lastStartId)) stopForegroundCompat()
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

    /**
     * Puts the stopped summary back on the queue, still marked as waiting for
     * power — and still marked automatic, so its consent is re-checked when
     * it runs. Plugging back in wakes it through exactly the path the
     * original deferral used — see ChargingJobService.
     */
    private fun requeueForCharging(meetingId: String) {
        com.meetily.mobile.data.JobQueue.enqueue(
            this,
            com.meetily.mobile.data.JobQueue.Job(
                com.meetily.mobile.data.JobQueue.KIND_SUMMARY,
                meetingId,
                runTemplateKey,
                System.currentTimeMillis(),
                chargingOnly = true,
                auto = runAuto
            )
        )
        ChargingJobService.schedule(this)
    }

    /**
     * Android's daily foreground-service limit ended the run. Not
     * postDoneNotification: for a summary that says "Summary ready", and
     * none was written.
     */
    private fun postTimeoutNotification(meetingId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = when (currentMode) {
            MODE_NOTES -> getString(R.string.notes_failed_notif)
            MODE_SPEAKERS -> getString(R.string.speakers_failed_notif)
            else -> getString(R.string.summary_timeout_notif)
        }
        val body = getString(R.string.fgs_timeout_summary)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openMeetingIntent(meetingId, 7))
            .build()
        try {
            manager.notify(NOTIF_DONE_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun postUnpluggedNotification(title: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(getString(R.string.unplugged_stopped_title))
            .setContentText(getString(R.string.unplugged_stopped_summary, title))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.unplugged_stopped_summary, title))
            )
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openMeetingIntent(currentMeetingId, 7))
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
        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L

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
        const val EXTRA_CHARGING_ONLY = "charging_only"
        /** Queued by AutoSummary; see JobQueue.Job.auto. */
        const val EXTRA_AUTO = "auto"
        const val MODE_SUMMARY = "summary"
        const val MODE_NOTES = "notes"
        const val MODE_SPEAKERS = "speakers"

        /**
         * Whether the run observers were just told about ended because the
         * charger came out, rather than finishing. Read alongside
         * currentMode/currentTitle, which are also still set at that point:
         * without it the home screen toasts "summary ready" for a summary
         * that was deliberately stopped and never written.
         */
        @Volatile
        var lastStopped = false
            internal set
        private const val CHANNEL_ID = "summary"
        private const val NOTIF_ID = 50
        private const val RUN_ACTIVE = 0
        private const val RUN_STOPPED = 1
        private const val RUN_COMMITTED = 2
        private const val NOTIF_DONE_ID = 51

        fun start(
            context: Context,
            meetingId: String,
            templateKey: String,
            mode: String = MODE_SUMMARY,
            /** This run only got to start because the phone is charging. */
            chargingOnly: Boolean = false,
            /** Queued by AutoSummary; consent is re-checked before it runs. */
            auto: Boolean = false
        ) {
            val intent = Intent(context, SummaryService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MEETING_ID, meetingId)
                .putExtra(EXTRA_TEMPLATE, templateKey)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_CHARGING_ONLY, chargingOnly)
                .putExtra(EXTRA_AUTO, auto)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
