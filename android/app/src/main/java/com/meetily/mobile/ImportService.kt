package com.meetily.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
 * Foreground service that owns a file-import run, so long transcriptions
 * survive the screen turning off or the user leaving. Recordings captured as
 * audio only get their transcript here too, and accuracy checks run here.
 * mediaProcessing on Android 15 and later, dataSync below: see ImportFgsType.
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

    /**
     * Per-run spoken language ("de", or "auto"); null uses Settings. See
     * [EXTRA_LANGUAGE].
     */
    private var language: String? = null

    /** Set when this run is a second pass over an existing meeting's audio. */
    private var recheckMeetingId: String? = null

    /** This run is giving an audio-only recording its first transcript. */
    private var firstTranscript = false

    /** This check transcribes the meeting's clear-voice copy; see [EXTRA_CLEAR_VOICE]. */
    private var clearVoice = false

    /**
     * Where this run was asked to continue an unfinished transcription from
     * (EXTRA_RESUME_FROM_MS), for its in-flight marker; the importer takes
     * the exact point from what is stored with the words.
     */
    private var resumeFromMs = 0L

    /**
     * Set by onTimeout. The instance is finished for good: the worker is
     * unwinding and must be allowed to land before anything else runs here.
     */
    private var timedOut = false

    /**
     * Staged copy this run adopts, when it came off the import queue, or the
     * sound remuxed out of a video picked straight away (runImport).
     */
    private var adoptFile: String? = null

    /**
     * Which file of a batch this run is ([EXTRA_BATCH_INDEX], from 1) and how
     * many came together; 0 for a file on its own. See JobQueue.Job.batchIndex.
     */
    var batchIndex = 0
        private set
    var batchSize = 0
        private set

    /** The batch this file came with ([EXTRA_BATCH_ID]); blank for none. */
    private var batchId = ""

    /**
     * The user chose "Cancel all": this file stops, and finishRun takes the
     * files of its batch still waiting off the queue before it drains.
     */
    @Volatile private var cancelAll = false

    /**
     * A file the user picked and started straight away (ImportActivity),
     * not one off the queue and not a pass over a meeting's own audio. It
     * has no queue entry of its own, so once its meeting exists it is
     * marked in flight as that meeting's first transcript: a run that dies
     * with the process is then offered back, from where its words stop,
     * like any other (MainActivity.offerInterruptedWork).
     */
    private var directImport = false

    /** When this run began, so its throughput can be recorded on finish. */
    @Volatile private var runStartedMs = 0L

    @Volatile private var cancelled = false

    /**
     * This run only got to start because the phone was on power — an accuracy
     * check or a first transcript the user asked to defer until charging.
     */
    private var chargingOnly = false

    /**
     * The user started this run just now (LiveUpdates.transcriptionUserStarted),
     * so on Android 16 its notification is a Live Update.
     */
    private var userStarted = false

    /**
     * The user swiped this run's Live Update away, or that of the batch it
     * is a later file of (LiveUpdates.startsDismissed); see
     * [ACTION_LIVE_DISMISSED].
     */
    private var liveUpdateDismissed = false

    /** The charger came out mid-run, so this pass is being put back. */
    @Volatile private var stoppedByUnplug = false

    /**
     * The importer has returned and its result is on the way to finishRun.
     * An unplug confirmed after this point has nothing left to save battery
     * on, and honouring it threw away a pass that had already finished.
     */
    @Volatile private var importerReturned = false

    /**
     * The newest start this instance has been handed, including cancels and
     * refused starts. finishRun stops with it rather than unconditionally: a
     * job drained into THIS service class arrives as a newer start, and a
     * bare stopSelf() tore the service down under it, leaving that job
     * running with no foreground service at all.
     */
    private var lastStartId = 0

    private val powerWatch = PowerWatch { stopForUnplug() }
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
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                if (!isRunning) stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_BATCH -> {
                if (isRunning) cancelBatch() else stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_LIVE_DISMISSED -> {
                // Posted as an ordinary notification from now on, never
                // promoted again in this run, nor in the rest of the batch
                // its bar spans (LiveUpdates).
                if (isRunning) {
                    liveUpdateDismissed = true
                    dismissedBatchFile = batchIndex to batchSize
                } else {
                    stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val uri = intent.data
                // timedOut: this instance hit the foreground-service cap and
                // its worker is still unwinding. A bound client keeps the
                // object alive, so a queued job could otherwise land on it and
                // race the run that is on its way out.
                if (isRunning || timedOut || uri == null) {
                    refuseStart(startId)
                    return START_NOT_STICKY
                }
                isRunning = true
                // Closes JobGate's "a start is on its way" window; see
                // JobGate.canStartBatch.
                JobGate.onBatchStarted()
                // A bound client (the home screen) keeps this instance alive
                // past stopSelf(), so a second run can land on the same
                // object. Without this reset it would start already finished
                // — or already cancelled.
                done = false
                cancelled = false
                cancelAll = false
                stoppedByUnplug = false
                importerReturned = false
                chargingOnly = intent.getBooleanExtra(EXTRA_CHARGING_ONLY, false)
                percent = 0
                detail = ""
                resultMeetingId = null
                resultError = null
                resultWarning = null
                recheckMeetingId = intent.getStringExtra(EXTRA_RECHECK_MEETING_ID)
                // Captured before the run, because the run is about to change
                // it: a meeting that arrives here with no words is a recording
                // captured as audio only, and this pass is its FIRST
                // transcript rather than a second opinion on an existing one.
                // That distinction decides whether the summary should follow.
                // The extra carries it across an unplug stop, after which the
                // meeting holds the stopped pass's partial words. Lines only,
                // never their word timings: this is the main thread, before
                // startForeground, and a full parse of a long meeting takes
                // seconds on a low-end phone.
                firstTranscript = recheckMeetingId?.let { id ->
                    intent.getBooleanExtra(EXTRA_FIRST_TRANSCRIPT, false) ||
                        com.meetily.mobile.data.MeetingStore(this)
                            .loadFields(id, setOf("segments"))
                            ?.segments?.isEmpty() == true
                } ?: false
                adoptFile = intent.getStringExtra(EXTRA_ADOPT_FILE)
                directImport = recheckMeetingId == null && adoptFile == null
                batchIndex = intent.getIntExtra(EXTRA_BATCH_INDEX, 0)
                batchSize = intent.getIntExtra(EXTRA_BATCH_SIZE, 0)
                batchId = intent.getStringExtra(EXTRA_BATCH_ID).orEmpty()
                resumeFromMs = intent.getLongExtra(EXTRA_RESUME_FROM_MS, 0L).coerceAtLeast(0L)
                isRecheck = recheckMeetingId != null
                isFirstTranscript = firstTranscript
                currentMeetingId = recheckMeetingId
                sourceName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                    .ifBlank { getString(R.string.import_title) }
                modelKey = intent.getStringExtra(EXTRA_MODEL)
                language = intent.getStringExtra(EXTRA_LANGUAGE)?.takeIf { it.isNotBlank() }
                clearVoice = recheckMeetingId != null && intent.getBooleanExtra(EXTRA_CLEAR_VOICE, false)
                userStarted = LiveUpdates.transcriptionUserStarted(
                    passOverMeeting = recheckMeetingId != null,
                    byHand = intent.getBooleanExtra(EXTRA_USER_STARTED, false),
                    chargingOnly = chargingOnly
                )
                // A later file of a batch whose Live Update the user swiped
                // away stays an ordinary notification, carried on to the
                // file after it; anything else asks afresh.
                liveUpdateDismissed =
                    LiveUpdates.startsDismissed(dismissedBatchFile, batchIndex, batchSize)
                dismissedBatchFile = if (liveUpdateDismissed) batchIndex to batchSize else null
                createChannel()
                startForegroundCompat(buildNotification(0))
                // Whisper or NeMo is about to load. An idle on-device LLM from
                // an earlier summary or Ask must not stay resident beside it —
                // the pairing JobGate exists to prevent. Never blocks; a
                // generation in flight frees it when it ends.
                com.meetily.mobile.llm.LocalLlm.release()
                // A foreground service (mediaProcessing on 35+, dataSync
                // below) keeps the process alive but NOT the CPU:
                // without this, a long import stalls or dies once the screen
                // has been off for a while.
                runStartedMs = System.currentTimeMillis()
                acquireWakeLock()
                // Armed only for the deferred re-transcription pass. A file
                // the user picked and is waiting on is never stopped by a
                // cable, and its staged copy is deleted on an unfinished run
                // — stopping one would lose the import outright.
                powerWatch.arm(this, chargingOnly && recheckMeetingId != null)
                if (recheckMeetingId != null) {
                    com.meetily.mobile.data.JobQueue.markRunning(
                        this, com.meetily.mobile.data.JobQueue.KIND_CHECK,
                        recheckMeetingId!!, modelKey.orEmpty(),
                        firstTranscript = firstTranscript,
                        language = language.orEmpty(),
                        resumeFromMs = resumeFromMs,
                        clearVoice = clearVoice
                    )
                }
                // A queued import left the queue when it was drained; until
                // its audio belongs to a saved meeting, this marker is the
                // only thing that knows it exists.
                adoptFile?.let {
                    com.meetily.mobile.data.JobQueue.markImportRunning(
                        this, it, sourceName, modelKey.orEmpty(), language.orEmpty()
                    )
                }
                runImport(uri)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Turns down a start without breaking the startForegroundService
     * contract. A start that lands on an instance that is not in the
     * foreground — refused because it timed out, or because it carried no
     * file — still has to call startForeground, or Android crashes the app
     * seconds later for not having done so.
     */
    private fun refuseStart(startId: Int) {
        if (isRunning && !timedOut) return // already foreground; the run stops itself
        try {
            createChannel()
            startForegroundCompat(buildNotification(0))
        } catch (_: Exception) {
            // Foreground time already exhausted; nothing more can be done.
        }
        stopForegroundCompat()
        // A timed-out run's own finish stops the service, with this start's id.
        if (!isRunning) stopSelfResult(startId)
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
                // A video keeps only its sound, remuxed before the importer
                // loads a model and then adopted like a queued file's staged
                // copy, so finishRun deletes it if the run never gets there.
                if (directImport) {
                    extractSound(uri)?.let {
                        adoptFile = it
                        // The model's speed is timed from here: the remux
                        // is no part of it.
                        runStartedMs = System.currentTimeMillis()
                    }
                }
                result = AudioFileImporter(this, settings).import(
                    uri = uri,
                    title = com.meetily.mobile.data.ImportBatch.titleFor(sourceName),
                    sourceName = sourceName,
                    modelKey = modelKey,
                    recheckMeetingId = recheckMeetingId,
                    // Already computed in onStartCommand from the meeting's
                    // segment count. Without it the importer treats this
                    // recording's only transcript as a second opinion and
                    // stages it in a draft the meeting never receives.
                    firstTranscript = firstTranscript,
                    adoptFile = adoptFile,
                    language = language,
                    // An unplugged check keeps its partial draft to carry on
                    // from when the charger is back (requeueForCharging).
                    keepPartialCheck = { stoppedByUnplug },
                    clearVoice = clearVoice,
                    onMeetingCreated = { id ->
                        currentMeetingId = id
                        adoptFile?.let {
                            com.meetily.mobile.data.JobQueue.importAdopted(this, it, id)
                        }
                        if (directImport) {
                            // With the model the importer runs, which it
                            // records on the meeting: the one a continuation
                            // has to use.
                            com.meetily.mobile.data.JobQueue.markRunning(
                                this, com.meetily.mobile.data.JobQueue.KIND_CHECK, id,
                                modelKey?.takeIf { it.isNotBlank() } ?: settings.whisperModel,
                                firstTranscript = true,
                                language = language.orEmpty()
                            )
                        }
                        main.post {
                            if (isRunning) {
                                observers.forEach {
                                    it.onImportProgress(id, percent, detail)
                                }
                            }
                        }
                    },
                    onProgress = { p, text -> reportProgress(p, text) },
                    recordingActive = { RecordingService.isRunning },
                    cancelled = { cancelled }
                )
            } catch (e: Exception) {
                error = e.message ?: getString(R.string.platform_error_unknown)
            }
            // What this model actually cost on THIS phone. Every run has
            // always measured it and thrown it away; keeping it is what lets
            // the app quote an honest estimate before the next one starts.
            result?.let { r ->
                com.meetily.mobile.whisper.ModelSpeed.record(
                    this,
                    modelKey ?: AppSettings(this).whisperModel,
                    // This run's own share: a continuation began part-way.
                    r.coveredMs - r.startedFromMs,
                    System.currentTimeMillis() - runStartedMs
                )
            }
            val warning = result?.takeIf { !cancelled && it.truncated }?.let { r ->
                // 0: it never reached a pause to carry on from, so the rest
                // can only be had by starting over; say how far it got.
                val stoppedAt = r.resumeFromMs?.takeIf { it > 0L }
                if (stoppedAt != null) {
                    // Its words are kept with where they stop, and the meeting
                    // offers to carry on from there (ContinueTranscription).
                    getString(
                        R.string.resume_stopped_early,
                        com.meetily.mobile.data.TranscriptResume.clock(stoppedAt)
                    )
                } else if (r.totalMs > 0) {
                    getString(
                        R.string.import_truncated,
                        formatMinutes(r.coveredMs), formatMinutes(r.totalMs)
                    )
                } else {
                    getString(R.string.import_stopped_early, formatMinutes(r.coveredMs))
                }
            }
            importerReturned = true
            val stoppedAt = result?.resumeFromMs
            val continued = (result?.startedFromMs ?: 0L) > 0L
            val hadWords = result?.hadWords == true
            main.post {
                finishRun(result?.meetingId, error, warning, stoppedAt, continued, hadWords)
            }
        }.apply {
            name = "import-service"
            start()
        }
    }

    /**
     * Progress from the worker, to the observers and the notification. One of
     * a batch says which file it is: "File 2 of 5 · 12/40 min". Before the
     * importer has a detail of its own, that is the percentage the screen and
     * the notification show for a single file ("Transcribing… 12%"); the
     * position line is never blank, so their fallback would not apply.
     */
    private fun reportProgress(p: Int, text: String) {
        val line = if (com.meetily.mobile.data.ImportBatch.showsPosition(batchIndex, batchSize)) {
            getString(
                R.string.import_batch_position_detail, batchIndex, batchSize,
                text.ifBlank { getString(R.string.import_status_running, p) }
            )
        } else {
            text
        }
        percent = p
        detail = line
        renewWakeLockIfStale()
        main.post {
            if (isRunning) {
                val id = currentMeetingId
                observers.forEach { it.onImportProgress(id, p, line) }
                updateNotification(p, line)
            }
        }
    }

    /**
     * For a video picked straight away: its sound remuxed into AudioStore
     * (VideoAudioExtractor), the name of that file; null when [uri] is not a
     * video, or its sound cannot be remuxed, and the importer copies the
     * file as before. Throws for a file with no sound to transcribe, and
     * when cancelled, before anything is loaded or saved.
     */
    private fun extractSound(uri: Uri): String? {
        val extractor = com.meetily.mobile.whisper.VideoAudioExtractor
        val keep = extractor.planFor(this, uri)
        if (keep == com.meetily.mobile.whisper.VideoAudio.Keep.NO_SOUND) {
            throw AudioFileImporter.ImportException(getString(R.string.import_video_no_sound))
        }
        if (!keep.remuxes) return null
        val line = getString(R.string.import_video_extracting)
        reportProgress(0, line)
        val out = extractor.newExtractFile(this, sourceName, keep)
        val kept = extractor.extract(this, uri, keep, out, { cancelled }) { p ->
            reportProgress(p, line)
        }
        if (cancelled) {
            out.delete()
            throw AudioFileImporter.ImportException(getString(R.string.import_cancelled_nothing_kept))
        }
        return if (kept) out.name else null
    }

    /**
     * [stoppedAtMs] is where the words now stop when the run did not finish
     * (AudioFileImporter.Result.resumeFromMs), for a later pass to continue.
     * [continued] is true when the run carried on an unfinished transcript,
     * [hadWords] when the meeting had lines before it (kept or replaced).
     */
    private fun finishRun(
        meetingId: String?,
        error: String?,
        warning: String?,
        stoppedAtMs: Long? = null,
        continued: Boolean = false,
        hadWords: Boolean = false
    ) {
        done = true
        // Captured because the field is cleared below, before the completion
        // notification is posted — and that notification has to know whether
        // this run produced a transcript or a second opinion on one.
        val wasFirstTranscript = firstTranscript
        resultMeetingId = meetingId
        resultError = error
        resultWarning = warning
        val recheckId = recheckMeetingId
        // A check the charger stopped keeps its partial draft, and carries on
        // from where it stopped once it is requeued below. One that never
        // reached a pause to carry on from would only start over: its draft
        // goes, as before.
        val keepsDraft = stoppedByUnplug && (stoppedAtMs ?: 0L) > 0L && !wasFirstTranscript
        // A first transcript (or a continuation of one) writes the meeting,
        // never a draft: a draft there is a finished accuracy check the user
        // has not reviewed yet, and is not this run's to throw away.
        if (recheckId != null && !wasFirstTranscript &&
            (cancelled || error != null || warning != null) && !keepsDraft
        ) {
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
        adoptFile?.let {
            com.meetily.mobile.data.AudioStore.delete(this, it)
            com.meetily.mobile.data.JobQueue.dequeueStaged(this, it)
        }
        adoptFile = null
        powerWatch.disarm(this)
        if (recheckId != null) {
            com.meetily.mobile.data.JobQueue.finished(
                this, com.meetily.mobile.data.JobQueue.KIND_CHECK, recheckId
            )
            // After finished(), so the marker and the requeued job are never
            // both in the queue for the same meeting.
            if (stoppedByUnplug) {
                requeueForCharging(recheckId, wasFirstTranscript, stoppedAtMs ?: 0L)
            }
        } else if (directImport) {
            // Its marker, by the meeting it was filling: the result has no
            // id when the import failed outright.
            currentMeetingId?.let {
                com.meetily.mobile.data.JobQueue.finished(
                    this, com.meetily.mobile.data.JobQueue.KIND_CHECK, it
                )
            }
        }
        directImport = false
        // The words exist now, so this is the moment the summary can start —
        // see AutoSummary. Only for a recording that had none before, and only
        // on a clean run: a cancelled or partial pass leaves the transcript
        // alone, so there is nothing new to summarise.
        if (firstTranscript && recheckId != null &&
            !cancelled && error == null && warning == null
        ) {
            // What the summary decision needs, without the word timings:
            // this is the main thread.
            val done = com.meetily.mobile.data.MeetingStore(this)
                .loadFields(recheckId, setOf("title", "segments", "summary"))
            // A continued transcript may already have a summary of the part
            // that was there, and one started over a summary of the lines it
            // replaced: either is marked out of date (AudioFileImporter), not
            // replaced, with its action items, ticks and reminders.
            if (done != null && !((continued || hadWords) && done.summary.isNotBlank())) {
                com.meetily.mobile.data.AutoSummary.maybeStart(
                    this, recheckId, done.title, done.segments.size
                )
            }
            // A recording kept only so it could be transcribed ("Keep
            // meeting audio" off) has its transcript now.
            if (done != null && done.segments.isNotEmpty()) {
                UnkeptAudio.afterFirstTranscript(this, recheckId)
            }
        }
        firstTranscript = false
        val stopped = stoppedByUnplug
        stoppedByUnplug = false
        chargingOnly = false
        observers.forEach { it.onImportDone(meetingId, cancelled, error, warning) }
        if (stopped) {
            // A cancel the user did not ask for has to say so. The job is
            // back on the queue waiting for power and carries on from where
            // it stopped, keeping the words before it (TranscriptResume);
            // without a point to continue from it starts over.
            postUnpluggedNotification(recheckId, stoppedAtMs)
        } else if (!cancelled) {
            postCompletionNotification(meetingId, error, warning, wasFirstTranscript)
        }
        // "Cancel all": the files of this batch still waiting go as well,
        // before the drain below would start the next of them. Their staged
        // copies are Recap's only ones, so the notice says how many.
        if (cancelAll) {
            val dropped = com.meetily.mobile.data.JobQueue.dequeueBatch(this, batchId)
            for (job in dropped) com.meetily.mobile.data.AudioStore.delete(this, job.stagedFile)
            if (dropped.isNotEmpty()) postBatchStoppedNotification(dropped.size)
        }
        cancelAll = false
        // isRecheck / currentMeetingId deliberately survive the run: an
        // observer that binds after the finish still needs to know what just
        // happened. Both are reset by the next ACTION_START.
        isRunning = false
        val wasTimedOut = timedOut
        timedOut = false
        // Hand over to the next queued job while this service is still in the
        // foreground — that is what makes starting one legal at all on
        // Android 12+, and it is what serialises the queue. Not after a
        // timeout: the service already left the foreground and its type's
        // budget is spent, so a start drained onto it could never satisfy
        // startForeground. The next activity resume drains instead.
        if (!wasTimedOut) JobGate.drain(this)
        // False when drain just started another job on this same class; its
        // pending start keeps the service, and the notification is replaced.
        if (stopSelfResult(lastStartId)) stopForegroundCompat()
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

    /**
     * Whether "Cancel all" applies to this run: a file of a batch with
     * files after it (ImportBatch.offersCancelAll).
     */
    fun hasLaterBatchFiles(): Boolean =
        isRunning && com.meetily.mobile.data.ImportBatch.offersCancelAll(batchId, batchIndex, batchSize)

    /**
     * Stops this file and the rest of its batch: the files still being
     * staged are not queued (ImportQueue.stopBatch), and finishRun takes
     * the queued ones off the queue. Plain Cancel stops this file only,
     * and the next one starts.
     */
    fun cancelBatch() {
        // Asked from a question left up past the run's end: nothing to stop.
        if (!isRunning) return
        if (batchId.isNotBlank()) {
            cancelAll = true
            ImportQueue.stopBatch(batchId)
        }
        cancelled = true
    }

    /**
     * Ends a charging-deferred pass because the phone came off power, and
     * requeues it still marked as waiting for a charger. The transcriber
     * polls [cancelled] between windows, so this lands within a second or
     * two rather than at the end of the file.
     */
    private fun stopForUnplug() {
        if (!isRunning || stoppedByUnplug || importerReturned) return
        stoppedByUnplug = true
        cancelled = true
    }

    /**
     * @see SummaryService.requeueForCharging. [firstTranscript] travels with
     * the job so the resumed pass still writes the meeting's transcript and
     * starts its summary, instead of staging an accuracy check. So does the
     * choice of the clear-voice copy, so the pass that carries a kept draft
     * on reads what made it.
     */
    private fun requeueForCharging(
        meetingId: String,
        firstTranscript: Boolean,
        /** Where the stopped pass's words end; see JobQueue.Job.resumeFromMs. */
        resumeFromMs: Long
    ) {
        com.meetily.mobile.data.JobQueue.enqueue(
            this,
            // Never trimmed when it carries a kept partial draft on; see there.
            com.meetily.mobile.data.JobQueue.chargingRequeue(
                meetingId, modelKey.orEmpty(), System.currentTimeMillis(),
                firstTranscript, language.orEmpty(), resumeFromMs,
                clearVoice = clearVoice
            )
        )
        ChargingJobService.schedule(this)
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
        val line = text.ifBlank { getString(R.string.import_status_running, progress) }
        // A Live Update on Android 16 when the user started this run now
        // (LiveUpdates); a start that is only being turned down is not one.
        val live = isRunning && !timedOut && LiveUpdates.requestsPromotion(
            LiveUpdates.transcriptionJob(passOverMeeting = recheckMeetingId != null),
            userStarted, Build.VERSION.SDK_INT, liveUpdateDismissed
        )
        val bar = LiveUpdates.progress(progress, batchIndex, batchSize)
        // What a locked phone shows when notification contents are hidden
        // there: how far it has got, never the meeting's or the file's name.
        // The line is minutes and file counts only.
        val publicVersion = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.liveupdates_public_transcribing))
            .setContentText(line)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        LiveUpdateBar.setBar(this, publicVersion, live, bar, progress)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(
                if (recheckMeetingId != null) {
                    getString(R.string.check_notif_title, sourceName)
                } else {
                    getString(R.string.import_notif_title, sourceName)
                }
            )
            .setContentText(line)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(android.R.string.cancel), cancelIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion.build())
        // Cancel stops this file and the next one starts; this stops the
        // files after it too.
        if (hasLaterBatchFiles() && !timedOut) {
            builder.addAction(
                0, getString(R.string.import_cancel_all),
                PendingIntent.getService(
                    this, 15,
                    Intent(this, ImportService::class.java).setAction(ACTION_CANCEL_BATCH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        LiveUpdateBar.setBar(this, builder, live, bar, progress)
        if (live) {
            LiveUpdateBar.requestPromotion(
                builder,
                PendingIntent.getService(
                    this, 14,
                    Intent(this, ImportService::class.java).setAction(ACTION_LIVE_DISMISSED),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        return builder.build()
    }

    private fun updateNotification(progress: Int, text: String = detail) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIF_ID, buildNotification(progress, text))
        } catch (_: SecurityException) {
        }
    }

    /**
     * Android 15 caps dataSync and mediaProcessing foreground services at six
     * hours per day each for apps targeting SDK 35, and an app that does not
     * stop when told is killed with ForegroundServiceDidNotStopInTimeException.
     * This runs as mediaProcessing there (ImportFgsType), with an allowance
     * of its own rather than the one downloads and summaries spend, but the
     * cap still applies and lands here, with [fgsType] saying which; the
     * handling is the same for both.
     *
     * That is not a limit this app comfortably sits under: CPU transcription
     * runs at two to three times realtime, so a pair of long meetings in one
     * day reaches it. Nothing here overrode onTimeout, so the outcome was a
     * crash with a partial transcript and no explanation.
     *
     * Stopping has to be immediate — the compliance window is seconds, far
     * less than a whisper batch — so the flag is set for the worker to unwind
     * against and the service gives up the foreground straight away. That is
     * enough for either type: leaving the foreground cancels the pending
     * crash (ServiceInfo documents stopForeground as working), and finishRun
     * stops the service once the worker has landed. Whatever has been
     * transcribed so far is already saved batch by batch, with where it
     * stops, so the meeting offers to continue from there
     * (ContinueTranscription).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled = true
        timedOut = true
        // The foreground-service budget ran out, not the charger: this run is
        // over for its own reason, and must not be reported as an unplug.
        stoppedByUnplug = false
        powerWatch.disarm(this)
        postTimeoutNotification(currentMeetingId)
        // isRunning deliberately NOT cleared here. The worker is still
        // unwinding — it has a batch to finish and a draft or transcript to
        // settle — and clearing the flag let a queued job start on this same
        // instance, tearing down the foreground state the first run was still
        // using and racing it on the same fields. finishRun clears it when
        // the run is genuinely over.
        stopForegroundCompat()
    }

    /**
     * Its own notification rather than postCompletionNotification, whose
     * success branches win whenever a meeting id is known — which after
     * hours of work it always is. The timeout read as "Import complete" or
     * "Transcript check ready", the latter opening a review with nothing in
     * it, and the explanation was never shown.
     */
    private fun postTimeoutNotification(meetingId: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // An accuracy check keeps nothing: finishRun deletes its draft, and
        // the next one starts from the beginning.
        val body = getString(
            if (recheckMeetingId != null && !firstTranscript) R.string.fgs_timeout_check
            else R.string.fgs_timeout_import
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentTitle(
                if (recheckMeetingId != null && !firstTranscript) {
                    getString(R.string.check_incomplete_notif)
                } else {
                    getString(R.string.import_incomplete_notif)
                }
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        // The meeting, never the check review: finishRun deletes the draft.
        if (meetingId != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 13,
                    Intent(this, MeetingDetailActivity::class.java)
                        .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        try {
            manager.notify(NOTIF_DONE_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun postUnpluggedNotification(meetingId: String?, stoppedAtMs: Long?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // The title only, from the head of the file: this is the main thread.
        val title = meetingId
            ?.let { com.meetily.mobile.data.MeetingStore(this).loadHeader(it)?.title }
            ?.takeIf { it.isNotBlank() }
            ?: sourceName
        // 0: no pause reached to carry on from, so it starts over.
        val body = if (stoppedAtMs != null && stoppedAtMs > 0L) {
            getString(
                R.string.resume_unplugged_continue, title,
                com.meetily.mobile.data.TranscriptResume.clock(stoppedAtMs)
            )
        } else {
            getString(R.string.unplugged_stopped_check, title)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentTitle(getString(R.string.unplugged_stopped_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        if (meetingId != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 12,
                    Intent(this, MeetingDetailActivity::class.java)
                        .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        try {
            manager.notify(NOTIF_DONE_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun postCompletionNotification(
        meetingId: String?,
        error: String?,
        warning: String?,
        wasFirstTranscript: Boolean = false
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setSilent(true)
        val awaitingReview = recheckMeetingId != null && !wasFirstTranscript
        if (meetingId != null && awaitingReview && warning != null) {
            builder.setContentTitle(getString(R.string.check_incomplete_notif))
                .setContentText(getString(R.string.check_incomplete_body))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.check_incomplete_body))
                )
        } else if (meetingId != null && awaitingReview) {
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
            // Named for what failed: not every run here is an import.
            builder.setContentTitle(
                getString(
                    when {
                        recheckMeetingId == null -> R.string.import_failed_notif
                        wasFirstTranscript -> R.string.transcription_failed_notif
                        else -> R.string.check_failed_notif
                    }
                )
            )
                .setContentText(error ?: "")
        }
        try {
            manager.notify(NOTIF_DONE_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * After "Cancel all": how many files of the batch were taken off the
     * queue, never imported. Their staged copies are gone, so the user has
     * to share or pick them again.
     */
    private fun postBatchStoppedNotification(count: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val body = resources.getQuantityString(R.plurals.import_batch_stopped_body, count, count)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentTitle(getString(R.string.import_batch_stopped_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        try {
            manager.notify(NOTIF_DONE_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ImportFgsType.forSdk(Build.VERSION.SDK_INT)
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

        /**
         * True when the run anchored to a meeting ([isRecheck]) is that
         * meeting's FIRST transcript: its words go straight into the meeting,
         * so observers treat it as an import, not a check with a draft to
         * review. Like [isRecheck] it outlives the run, for late binders.
         */
        @Volatile var isFirstTranscript = false
            private set

        /**
         * The batch file (index to size) whose Live Update the user swiped
         * away, moved on to each later file of that batch as it starts
         * (LiveUpdates.startsDismissed); null once a run that is not one
         * starts. Here rather than on the instance: when the next file is
         * still being copied the service stops between files, and that
         * file arrives on a new one. Main thread.
         */
        private var dismissedBatchFile: Pair<Int, Int>? = null

        const val ACTION_START = "com.meetily.mobile.import.START"
        const val ACTION_CANCEL = "com.meetily.mobile.import.CANCEL"

        /** "Cancel all": this file and the rest of its batch; see [cancelBatch]. */
        const val ACTION_CANCEL_BATCH = "com.meetily.mobile.import.CANCEL_BATCH"

        /** The user swiped the run's Live Update away (its delete intent). */
        const val ACTION_LIVE_DISMISSED = "com.meetily.mobile.import.LIVE_DISMISSED"
        const val EXTRA_NAME = "source_name"
        const val EXTRA_MODEL = "model_key"
        const val EXTRA_RECHECK_MEETING_ID = "recheck_meeting_id"
        const val EXTRA_ADOPT_FILE = "adopt_file"
        const val EXTRA_CHARGING_ONLY = "charging_only"
        /** This check is the meeting's first transcript; see JobQueue.Job. */
        const val EXTRA_FIRST_TRANSCRIPT = "first_transcript"
        /**
         * Spoken language for this run: a code from MeetingLanguage.CHOICES,
         * or "auto" to detect even when Settings pins one. Absent: Settings.
         */
        const val EXTRA_LANGUAGE = "spoken_language"
        /**
         * Long, ms: this run continues an unfinished transcription that
         * stopped there (JobQueue.Job.resumeFromMs). Absent or 0: from the
         * beginning, unless the meeting's own words say where they stop and
         * the same model is asked for (TranscriptResume.continueFrom).
         */
        const val EXTRA_RESUME_FROM_MS = "resume_from_ms"
        /**
         * Accuracy checks only: transcribe the meeting's clear-voice copy
         * rather than its recording. Only ever set by an explicit choice.
         */
        const val EXTRA_CLEAR_VOICE = "clear_voice"
        /**
         * Passes over a meeting's audio only: the user started this one, so
         * it is a Live Update on Android 16 (LiveUpdates.transcriptionUserStarted).
         * Set by the meeting screen at the user's tap, and by JobGate for a
         * pass the user asked for by hand that ran off the job queue
         * (LiveUpdates.queuedPassUserStarted). Absent on the automatic check
         * and an audio-only recording's transcript. A picked file needs no
         * flag; it is always the user's.
         */
        const val EXTRA_USER_STARTED = "user_started"
        /**
         * Ints: this file is number EXTRA_BATCH_INDEX (from 1) of the
         * EXTRA_BATCH_SIZE handed over together; see JobQueue.Job.batchIndex.
         */
        const val EXTRA_BATCH_INDEX = "batch_index"
        const val EXTRA_BATCH_SIZE = "batch_size"
        /** String: the batch this file came with; see JobQueue.Job.batchId. */
        const val EXTRA_BATCH_ID = "batch_id"
        private const val CHANNEL_ID = "import"
        private const val NOTIF_ID = 44
        private const val NOTIF_DONE_ID = 45

        /** Held in renewable slices rather than one long up-front bet. */
        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L
    }
}
