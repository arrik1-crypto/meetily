package com.meetily.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.ImportBatch

/**
 * Thin observer over ImportService: starts an import for a shared or picked
 * audio or video file, shows progress, and opens the meeting when done. The
 * service keeps working if this screen (or the whole app UI) goes away.
 * Several files at once are each queued as their own import, and caption
 * files (WebVTT, SRT) become meetings without audio; see [startBatch].
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var fileNameView: TextView

    private var service: ImportService? = null
    private var bound = false
    private var opened = false

    /**
     * Whether THIS screen started or bound to a run. Until it has, Cancel
     * must not message ImportService: the service may be busy with another
     * import or a check — the job this file is waiting behind.
     */
    private var startedRun = false

    /** Set by Cancel before any run started; the queue copy polls it. */
    @Volatile private var abandoned = false

    /**
     * Handed to the service or the queue. Saved, so a re-created screen
     * knows whether the pre-start steps (permission, probe, model picker)
     * still have to run — see onCreate.
     */
    private var started = false

    /** The notification-permission dialog is up; its result is still due. */
    private var awaitingPermission = false

    /**
     * Spoken language chosen for this file in the model sheet ("auto" or a
     * code); null leaves it to Settings. See ImportService.EXTRA_LANGUAGE.
     */
    private var importLanguage: String? = null

    /** Which progress updates are read out; see ProgressAnnouncer. */
    private val progressAnnouncer = ProgressAnnouncer()

    private val observer = object : ImportService.Observer {
        override fun onImportProgress(meetingId: String?, percent: Int, detail: String) {
            if (isFinishing || isDestroyed) return
            progress.isIndeterminate = percent == 0
            progress.progress = percent
            A11y.setStatusText(
                statusView,
                detail.ifBlank { getString(R.string.import_status_running, percent) },
                progressAnnouncer.shouldAnnounce(percent)
            )
        }

        override fun onImportDone(
            meetingId: String?,
            wasCancelled: Boolean,
            error: String?,
            warning: String?
        ) {
            if (isFinishing || isDestroyed) return
            when {
                // A first transcript is anchored to its meeting like a check
                // but writes straight into it: there is no draft to review,
                // and the meeting is what the user wants to see.
                meetingId != null && ImportService.isRecheck &&
                    !ImportService.isFirstTranscript -> {
                    // A cancelled or partial check left the stored transcript
                    // alone and threw its draft away — nothing to review.
                    if (wasCancelled || warning != null) {
                        Toast.makeText(
                            this@ImportActivity,
                            if (wasCancelled) getString(R.string.check_cancelled)
                            else getString(R.string.check_incomplete_body),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    } else {
                        openCheck(meetingId)
                    }
                }
                meetingId != null -> {
                    when {
                        wasCancelled -> Toast.makeText(
                            this@ImportActivity,
                            R.string.import_cancelled_partial,
                            Toast.LENGTH_LONG
                        ).show()
                        warning != null -> Toast.makeText(
                            this@ImportActivity, warning, Toast.LENGTH_LONG
                        ).show()
                    }
                    openDetail(meetingId)
                }
                // Cancelled before any meeting existed (a video's sound was
                // still being extracted): nothing failed, and nothing is kept.
                wasCancelled -> {
                    Toast.makeText(
                        this@ImportActivity, R.string.import_cancelled_nothing_kept,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
                else -> {
                    Toast.makeText(
                        this@ImportActivity,
                        getString(
                            R.string.import_failed, error ?: getString(R.string.platform_error_unknown)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? ImportService.ImportBinder)?.service ?: return
            service = svc
            if (svc.sourceName.isNotBlank()) {
                fileNameView.text = svc.sourceName
            }
            svc.addObserver(observer)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLanguage = savedInstanceState?.getString(STATE_LANGUAGE)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_import)
        EdgeToEdge.setUp(this) { root() }

        findViewById<MaterialToolbar>(R.id.importToolbar).setNavigationOnClickListener {
            if (stayWhileAdding.isEnabled) {
                stayWhileAdding.handleOnBackPressed()
            } else {
                finish() // import keeps running in the service
            }
        }
        onBackPressedDispatcher.addCallback(this, stayWhileAdding)
        statusView = findViewById(R.id.importStatus)
        progress = findViewById(R.id.importProgress)
        fileNameView = findViewById(R.id.importFileName)
        findViewById<android.view.View>(R.id.importCancelButton).setOnClickListener {
            val svc = service
            when {
                svc != null -> svc.requestCancel()
                // Started but not bound yet: the running job is ours.
                startedRun -> startService(
                    Intent(this, ImportService::class.java).setAction(ImportService.ACTION_CANCEL)
                )
                else -> {
                    // Still probing, choosing or copying to the queue: drop
                    // this file only, or the rest of this screen's batch
                    // (whose threads may have been started by an earlier
                    // instance of this screen; they poll the run, not it).
                    abandoned = true
                    batch?.cancelled = true
                    finish()
                    return@setOnClickListener
                }
            }
            A11y.setStatusText(statusView, getString(R.string.import_stopping), announce = true)
        }

        // The same progress screen backs an accuracy check; say which it is.
        if (ImportService.isRunning && ImportService.isRecheck &&
            !ImportService.isFirstTranscript
        ) {
            findViewById<MaterialToolbar>(R.id.importToolbar).title =
                getString(R.string.check_accuracy_title)
        }

        // A batch's summary was up: the batch is done, so show it again.
        savedInstanceState?.getString(STATE_SUMMARY)?.let {
            showSummary(it, savedInstanceState.getBoolean(STATE_SUMMARY_SETTINGS, false))
            return
        }
        // Several when shared or picked together: each becomes its own import.
        val uris = incomingUris()
        val uri = uris.firstOrNull()
        // A re-created Activity still carries its original intent, so reading
        // the uri again would start the SAME file a second time — JobGate
        // would find an import running, stage another copy and queue it, and
        // the user would end up with the meeting twice. Portrait is pinned on
        // phones, but from targetSdk 36 Android 16 ignores that on displays
        // 600dp and wider, so rotating a tablet or an unfolded foldable is a
        // trigger there. So are scheduled dark-theme switching at sunset,
        // font or display-size changes, locale changes and a process-death
        // restore during a long import.
        //
        // That holds only once the file was handed on. Re-created BEFORE
        // that — permission dialog, probe or model picker still pending —
        // the old instance's pending work died with it, and treating the
        // new one as an observer dropped the share with "couldn't read that
        // file as audio". Those phases simply run again instead.
        val handedOn = savedInstanceState?.getBoolean(STATE_STARTED, true) ?: false
        if (uri != null && savedInstanceState != null && !handedOn) {
            if (savedInstanceState.getBoolean(STATE_QUEUEING, false)) {
                // Only the batch this screen was adding: another screen's
                // batch, or a single file's queue copy, is not this one's.
                val run = savedInstanceState.getString(STATE_BATCH_ID)?.let { runs[it] }
                if (run != null) {
                    // A batch is still being added: this screen takes it over
                    // and stays, so the files not yet read keep their grant.
                    run.screen = this
                    batch = run
                    started = true
                    queueing = true
                    stayWhileAdding.isEnabled = true
                    A11y.setStatusText(
                        statusView, getString(R.string.import_batch_stay), announce = false
                    )
                    return
                }
                // The old instance's copy thread carries on and reports.
                finish()
                return
            }
            classifyAndStart(
                uris, savedInstanceState.getBoolean(STATE_AWAITING_PERMISSION, false)
            )
            return
        }
        if (uri == null || savedInstanceState != null) {
            // Reopened from the progress notification, or re-created: observe.
            if (ImportService.isRunning) {
                bindService(
                    Intent(this, ImportService::class.java), connection, Context.BIND_AUTO_CREATE
                )
                bound = true
                startedRun = true
                started = true
            } else if (savedInstanceState != null) {
                // Re-created after the run this screen was showing ended,
                // while no instance was observing. Nothing failed: its result
                // went to the completion notification and the meeting list,
                // so "couldn't read that file" would be wrong here.
                finish()
            } else {
                Toast.makeText(this, R.string.import_no_audio, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        classifyAndStart(uris, permissionRequested = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_STARTED, started && !queueing)
        outState.putBoolean(STATE_QUEUEING, queueing)
        outState.putBoolean(STATE_AWAITING_PERMISSION, awaitingPermission)
        outState.putString(STATE_LANGUAGE, importLanguage)
        batch?.let { outState.putString(STATE_BATCH_ID, it.id) }
        summary?.let {
            outState.putString(STATE_SUMMARY, it)
            outState.putBoolean(STATE_SUMMARY_SETTINGS, summaryOffersSettings)
        }
    }

    /** The batch summary while it is up, so a re-created screen shows it again. */
    private var summary: String? = null
    private var summaryOffersSettings = false

    /** Copying into the queue; nothing was handed to the service. */
    private var queueing = false

    /** One shared or picked file: its name and what it is (ImportBatch.kindOf). */
    private class Picked(val uri: Uri, val name: String, val kind: ImportBatch.Kind)

    /**
     * What each file is comes first, off the main thread: its name and type
     * are provider round trips. A single recording then goes the way one
     * always has (resumePreStart); caption files need no model and no
     * notification, and several files at once are a batch (startBatch).
     */
    private fun classifyAndStart(uris: List<Uri>, permissionRequested: Boolean) {
        // The sender's type: what a provider that reports none was shared as.
        val sharedType = intent?.type
        // Still due from the registry; kept across another re-creation.
        if (permissionRequested) awaitingPermission = true
        Thread {
            val files = uris.map { u ->
                val name = displayName(u)
                val type = try {
                    contentResolver.getType(u)
                } catch (_: Exception) {
                    null
                } ?: sharedType
                Picked(u, name, ImportBatch.kindOf(type, name))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val only = files.singleOrNull()
                if (only != null && only.kind == ImportBatch.Kind.MEDIA) {
                    resumePreStart(only.uri, permissionRequested)
                } else {
                    startBatch(files, permissionRequested)
                }
            }
        }.apply {
            name = "import-classify"
            start()
        }
    }

    /**
     * Everything before the file is handed on. [permissionRequested]: the
     * dialog was already launched by a previous instance, whose result the
     * activity-result registry delivers to this one — asking again would
     * show it twice.
     */
    private fun resumePreStart(uri: Uri, permissionRequested: Boolean) {
        val downloaded =
            com.meetily.mobile.whisper.TranscriptionModels.downloadedKeys(this)
        val whisperReady = downloaded.isNotEmpty()
        if (!whisperReady) {
            Toast.makeText(this, R.string.import_needs_whisper, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        // Ask before the service starts, not after.
        //
        // Sharing a file into Recap is an entry point in its own right — a new
        // user can reach it without ever having tapped Record, which is where
        // the only other request lives. On Android 13+ the permission is
        // denied by default, so ImportService's startForeground notification
        // was discarded: no progress, no cancel action (the screen's own back
        // button says the import keeps running), and no "done" notification. A
        // multi-minute transcription ran completely invisibly.
        //
        // Asked once and then continued either way, matching the record path:
        // the import itself does not need the permission, only its visibility
        // does.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Answered while this screen was still working out what the file
            // is (a re-created screen asks nothing again): carry on as asked.
            if (permissionAnswered) {
                beginImport(uri, downloaded)
                return
            }
            pendingStart = { beginImport(uri, downloaded) }
            awaitingPermission = true
            if (!permissionRequested) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        beginImport(uri, downloaded)
    }

    /** Set while the notification-permission dialog is up. */
    private var pendingStart: (() -> Unit)? = null

    /**
     * The dialog's answer arrived. A re-created screen gets it from the
     * activity-result registry, possibly before it has worked out what the
     * file is and set [pendingStart]; that step then goes on by itself.
     */
    private var permissionAnswered = false

    private val notificationPermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { _ ->
            // Granted or not, the import goes ahead — it just may run without
            // a visible notification, exactly as the record path behaves.
            val start = pendingStart
            pendingStart = null
            awaitingPermission = false
            permissionAnswered = true
            if (!isFinishing && !isDestroyed) start?.invoke()
        }

    private fun beginImport(uri: Uri, downloaded: List<String>) {
        // Ask which model should transcribe THIS file (heavier models suit
        // imports better than live capture, so the per-run choice matters
        // here). One downloaded model leaves no model to choose, but the sheet
        // still carries this file's spoken language when that model can be
        // told one; that one model, which is the usual setup, would otherwise
        // never show the language at all. Only a single model that takes no
        // language starts straight away.
        val ask = downloaded.size > 1 ||
            com.meetily.mobile.whisper.TranscriptionModels.acceptsLanguage(downloaded.first())

        // Both the display name and the duration are read off a content://
        // URI that may be backed by a cloud provider, where each probe is a
        // network fetch. They used to run on the main thread inside onCreate —
        // the query once, and setDataSource once PER DOWNLOADED MODEL, because
        // ModelPickerSheet calls entriesProvider() synchronously and re-calls
        // it after every delete. Four models meant four fetches before the
        // activity drew at all. Probe once, off the main thread, then show.
        Thread {
            val fileName = displayName(uri)
            val durationMs = if (ask) sourceDurationMs(uri) else 0L
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                fileNameView.text = fileName
                if (ask) {
                    showModelPicker(downloaded, durationMs) { key ->
                        startImport(uri, fileName, key)
                    }
                } else {
                    startImport(uri, fileName, downloaded.first())
                }
            }
        }.apply {
            name = "import-probe"
            start()
        }
    }

    /**
     * The model (and spoken language) sheet. [durationMs] is the file's
     * length for the time estimate, 0 for none (a batch).
     */
    private fun showModelPicker(
        downloaded: List<String>,
        durationMs: Long,
        title: String = getString(R.string.import_choose_model),
        onPick: (String) -> Unit
    ) {
        val settings = AppSettings(this)
        ModelPickerSheet.show(
            this,
            title,
            entriesProvider = {
                downloaded.map { key ->
                    ModelPickerSheet.Entry(
                        key = key,
                        title = com.meetily.mobile.whisper.TranscriptionModels
                            .displayName(key),
                        // This is the moment a heavy model gets chosen
                        // for a long file, so it is the moment to say what
                        // that costs on this phone.
                        meta = com.meetily.mobile.whisper.TranscriptionModels
                            .metaLineFor(this, key, durationMs),
                        downloaded = true,
                        selected = settings.whisperModel == key,
                        fit = com.meetily.mobile.data.DeviceProbe.verdict(
                            this,
                            com.meetily.mobile.whisper.TranscriptionModels.fitNeedOrNull(key)
                        )
                    )
                }
            },
            onPick = onPick,
            onDismissed = { finish() }, // no pick, nothing to import
            // Only when one of the models can be told the language at all.
            header = if (downloaded.any {
                    com.meetily.mobile.whisper.TranscriptionModels.acceptsLanguage(it)
                }
            ) {
                languageRow(settings)
            } else {
                null
            }
        )
    }

    /**
     * "Spoken language: Auto-detect" above the model cards: this file's
     * language, defaulting to the Settings value. Parakeet and English-only
     * models ignore it, as the Settings description says.
     */
    private fun languageRow(settings: AppSettings): View {
        // A throwaway parent so the row keeps its margin; the sheet adds it.
        val row = layoutInflater.inflate(
            R.layout.language_import_row, android.widget.LinearLayout(this), false
        ) as TextView
        fun render() {
            row.text = getString(
                R.string.language_import_row,
                LanguagePicker.spokenLabel(this, importLanguage ?: settings.spokenLanguage)
            )
        }
        render()
        row.setOnClickListener {
            LanguagePicker.show(
                this,
                getString(R.string.language_spoken_title),
                getString(R.string.language_auto),
                com.meetily.mobile.data.MeetingLanguage.AUTO,
                importLanguage ?: settings.spokenLanguage
            ) { value ->
                importLanguage = value
                render()
            }
        }
        A11y.labelClick(row, getString(R.string.language_change_spoken))
        return row
    }

    /**
     * Runs the file now, or copies it aside and queues it.
     *
     * Being busy used to end the story: a toast, and the file the user had
     * just picked went nowhere. Queueing keeps the choice — including the
     * model they chose for it.
     */
    private fun startImport(uri: Uri, name: String, modelKey: String) {
        if (abandoned) return
        started = true
        val queued = JobGate.requestImport(
            this, uri, name, modelKey, cancelled = { abandoned },
            language = importLanguage.orEmpty()
        ) { result ->
            queueing = false
            when (result) {
                ImportQueue.Result.QUEUED -> {
                    Toast.makeText(
                        this, getString(R.string.import_queued, name), Toast.LENGTH_LONG
                    ).show()
                    // The job it was waiting on may have finished during the
                    // copy, and that job's own drain found an empty queue.
                    // Nothing else would start this until Recap is next
                    // opened; the share returns the user to another app.
                    JobGate.drain(applicationContext)
                }
                ImportQueue.Result.TOO_MANY -> Toast.makeText(
                    this, R.string.import_queue_full, Toast.LENGTH_LONG
                ).show()
                ImportQueue.Result.FAILED -> Toast.makeText(
                    this, R.string.import_queue_failed, Toast.LENGTH_LONG
                ).show()
                ImportQueue.Result.NO_SOUND -> Toast.makeText(
                    this, R.string.import_video_no_sound, Toast.LENGTH_LONG
                ).show()
                // The user dropped it; nothing to say.
                ImportQueue.Result.CANCELLED -> Unit
            }
            finish()
        }
        if (queued) {
            queueing = true
            A11y.setStatusText(statusView, getString(R.string.import_queueing), announce = true)
            return
        }
        startedRun = true
        val start = Intent(this, ImportService::class.java)
            .setAction(ImportService.ACTION_START)
            .setData(uri)
            .putExtra(ImportService.EXTRA_NAME, name)
            .putExtra(ImportService.EXTRA_MODEL, modelKey)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        importLanguage?.let { start.putExtra(ImportService.EXTRA_LANGUAGE, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(start)
        } else {
            startService(start)
        }
        bindService(Intent(this, ImportService::class.java), connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    // --- Several files at once, and caption files ----------------------------

    /**
     * On while a batch is being added. The read grant for shared files
     * belongs to this screen, so leaving it would stop the files not yet
     * copied from being read; back says so instead, and Cancel stops adding.
     */
    private val stayWhileAdding = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            A11y.setStatusText(statusView, getString(R.string.import_batch_stay), announce = true)
        }
    }

    /**
     * One batch being added. Its worker threads hold it, and [runs] does
     * until it is done, so they always see Cancel: pressed on the screen that
     * started the batch or on one re-created meanwhile that took it over,
     * even once that screen is gone. Each batch has its own, so two batches
     * at once (shares from two apps) never report to each other's screen.
     */
    private class BatchRun(val id: String) {
        /** Cancel was pressed on a screen showing this batch. */
        @Volatile var cancelled = false

        /**
         * The screen showing this batch, null while none is (onDestroy).
         * Main thread only.
         */
        var screen: ImportActivity? = null
    }

    /** The batch this screen is adding or took over, while it is. */
    private var batch: BatchRun? = null

    /** What became of one file of a batch. */
    private sealed class Outcome {
        class Queued(val result: ImportQueue.Result) : Outcome()
        class Caption(val result: SubtitleFiles.Outcome) : Outcome()
    }

    /**
     * Files shared or picked together, or caption files, which need no
     * model. Each recording becomes its own queued import, staged now while
     * this screen holds the read grant, and they run one after another; each
     * caption file becomes a meeting straight away. The model, and the
     * spoken language, are asked for once for all of them.
     */
    private fun startBatch(files: List<Picked>, permissionRequested: Boolean) {
        fileNameView.text = files.singleOrNull()?.name
            ?: resources.getQuantityString(R.plurals.import_batch_files, files.size, files.size)
        val media = files.count { it.kind == ImportBatch.Kind.MEDIA }
        val downloaded = com.meetily.mobile.whisper.TranscriptionModels.downloadedKeys(this)
        // Captions only, or no model to transcribe with: the caption files
        // come in all the same, and the summary says what the rest need.
        if (media == 0 || downloaded.isEmpty()) {
            runBatch(files, null)
            return
        }
        // As for one file: asked before anything starts, then on either way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !permissionAnswered
        ) {
            pendingStart = { chooseBatchModel(files, downloaded) }
            awaitingPermission = true
            if (!permissionRequested) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        chooseBatchModel(files, downloaded)
    }

    private fun chooseBatchModel(files: List<Picked>, downloaded: List<String>) {
        val ask = downloaded.size > 1 ||
            com.meetily.mobile.whisper.TranscriptionModels.acceptsLanguage(downloaded.first())
        if (!ask) {
            runBatch(files, downloaded.first())
            return
        }
        val media = files.count { it.kind == ImportBatch.Kind.MEDIA }
        showModelPicker(
            downloaded, 0L,
            resources.getQuantityString(R.plurals.import_batch_choose_model, media, media)
        ) { key -> runBatch(files, key) }
    }

    /**
     * Reads the caption files, then stages the recordings into the queue
     * ([modelKey] null: there is no model, so none are). A worker thread
     * throughout; the summary comes at the end.
     */
    private fun runBatch(files: List<Picked>, modelKey: String?) {
        if (abandoned) return
        started = true
        queueing = true
        stayWhileAdding.isEnabled = true
        val run = BatchRun(java.util.UUID.randomUUID().toString())
        run.screen = this
        runs[run.id] = run
        batch = run
        val language = importLanguage.orEmpty()
        val app = applicationContext
        val outcomes = arrayOfNulls<Outcome>(files.size)
        progress.isIndeterminate = false
        progress.progress = 0
        Thread {
            // Caption files first: each takes a moment and needs nothing else.
            for ((i, file) in files.withIndex()) {
                if (file.kind != ImportBatch.Kind.SUBTITLE) continue
                if (run.cancelled) break
                runOnUiThread { run.screen?.showBatchStep(i, files) }
                outcomes[i] = Outcome.Caption(SubtitleFiles.import(app, file.uri, file.name))
            }
            runOnUiThread {
                (run.screen ?: this).queueBatchMedia(run, files, modelKey, language, outcomes)
            }
        }.apply {
            name = "import-captions"
            start()
        }
    }

    /**
     * Stages the batch's recordings, on whichever screen shows [run] now: the
     * one that started it, or one re-created meanwhile (onCreate). With none
     * left, the screen that started it (gone by then) still ends the batch.
     */
    private fun queueBatchMedia(
        run: BatchRun,
        files: List<Picked>,
        modelKey: String?,
        language: String,
        outcomes: Array<Outcome?>
    ) {
        val media = files.indices.filter { files[it].kind == ImportBatch.Kind.MEDIA }
        if (media.isEmpty() || modelKey == null || run.cancelled) {
            finishBatch(run, files, outcomes, needsModel = modelKey == null && media.isNotEmpty())
            return
        }
        // The queue's room, in the order the files came; the rest are left
        // out and listed, never dropped later without a word.
        val admission = ImportBatch.admit(
            media.size, com.meetily.mobile.data.JobQueue.importCount(
                com.meetily.mobile.data.JobQueue.load(this)
            )
        )
        for (k in admission.leftOut) {
            outcomes[media[k]] = Outcome.Queued(ImportQueue.Result.TOO_MANY)
        }
        val admitted = admission.admitted.map { media[it] }
        if (admitted.isEmpty()) {
            finishBatch(run, files, outcomes, needsModel = false)
            return
        }
        ImportQueue.stageBatch(
            this,
            admitted.map { ImportQueue.BatchFile(files[it].uri, files[it].name) },
            modelKey,
            language,
            cancelled = { run.cancelled },
            onFile = { k -> run.screen?.showBatchStep(admitted[k], files) },
            onResults = { results ->
                for ((k, result) in results.withIndex()) {
                    outcomes[admitted[k]] = Outcome.Queued(result)
                }
                (run.screen ?: this).finishBatch(run, files, outcomes, needsModel = false)
            }
        )
    }

    /** "Adding 2 of 5: Team sync.mp4", read out as each file starts. */
    private fun showBatchStep(index: Int, files: List<Picked>) {
        if (isFinishing || isDestroyed) return
        progress.isIndeterminate = false
        progress.progress = index * 100 / files.size.coerceAtLeast(1)
        A11y.setStatusText(
            statusView,
            getString(R.string.import_batch_step, index + 1, files.size, files[index].name),
            announce = true
        )
    }

    /**
     * Says what became of every file. One caption file on its own opens its
     * meeting, as an import does; one file that could not be used says why
     * in a toast; anything more is a summary that names the files left out
     * and why.
     */
    private fun finishBatch(
        run: BatchRun,
        files: List<Picked>,
        outcomes: Array<Outcome?>,
        needsModel: Boolean
    ) {
        runs.remove(run.id)
        run.screen = null
        // This run's screen only: another batch's screen keeps its own.
        if (batch === run) {
            batch = null
            queueing = false
            stayWhileAdding.isEnabled = false
        }
        val gone = isFinishing || isDestroyed
        val only = files.singleOrNull()
        if (only != null) {
            val message = when (val outcome = outcomes[0]) {
                is Outcome.Caption -> when (val r = outcome.result) {
                    is SubtitleFiles.Outcome.Imported -> {
                        if (r.skipped > 0) {
                            Toast.makeText(
                                applicationContext,
                                resources.getQuantityString(
                                    R.plurals.import_sub_skipped, r.skipped, r.title, r.skipped
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        if (!gone) {
                            openDetail(r.meetingId)
                            return
                        }
                        getString(R.string.import_sub_done, r.title)
                    }
                    is SubtitleFiles.Outcome.Failed ->
                        getString(R.string.import_sub_failed_one, only.name, r.reason)
                }
                // Cancelled before it was read: nothing to say.
                null -> if (only.kind == ImportBatch.Kind.UNSUPPORTED) {
                    getString(R.string.import_batch_unsupported_one)
                } else {
                    null
                }
                is Outcome.Queued -> null
            }
            message?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show() }
            if (!gone) finish()
            return
        }
        val lines = batchSummary(files, outcomes, needsModel)
        if (gone) {
            // This screen was closed or re-created meanwhile: no dialog to
            // show it in, so the first lines go in a toast.
            lines.firstOrNull()?.let {
                Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show()
            }
            return
        }
        showSummary(lines.joinToString("\n\n"), needsModel)
    }

    /** The batch summary; closing it closes this screen. */
    private fun showSummary(text: String, offerSettings: Boolean) {
        summary = text
        summaryOffersSettings = offerSettings
        A11y.setStatusText(statusView, getString(R.string.import_batch_summary_title), announce = false)
        progress.isIndeterminate = false
        progress.progress = 100
        AlertDialog.Builder(this)
            .setTitle(R.string.import_batch_summary_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .apply {
                if (offerSettings) {
                    setNeutralButton(R.string.import_batch_open_settings) { _, _ ->
                        startActivity(Intent(this@ImportActivity, SettingsActivity::class.java))
                    }
                }
            }
            .setOnDismissListener {
                // Not when the screen is only being re-created: it shows the
                // summary again (onCreate).
                if (!isChangingConfigurations) {
                    summary = null
                    finish()
                }
            }
            .showSecure()
            .also { summaryDialog = it }
    }

    private var summaryDialog: AlertDialog? = null

    /** The summary's paragraphs: what came in first, then what was left out and why. */
    private fun batchSummary(
        files: List<Picked>,
        outcomes: Array<Outcome?>,
        needsModel: Boolean
    ): List<String> {
        val queued = mutableListOf<String>()
        val transcripts = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val full = mutableListOf<String>()
        val noSound = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        val noModel = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        for ((i, file) in files.withIndex()) {
            when (val outcome = outcomes[i]) {
                is Outcome.Caption -> when (val r = outcome.result) {
                    is SubtitleFiles.Outcome.Imported -> {
                        transcripts.add(r.title)
                        if (r.skipped > 0) {
                            notes.add(
                                resources.getQuantityString(
                                    R.plurals.import_sub_skipped, r.skipped, r.title, r.skipped
                                )
                            )
                        }
                    }
                    is SubtitleFiles.Outcome.Failed ->
                        notes.add(getString(R.string.import_sub_failed_one, file.name, r.reason))
                }
                is Outcome.Queued -> when (outcome.result) {
                    ImportQueue.Result.QUEUED -> queued.add(file.name)
                    ImportQueue.Result.TOO_MANY -> full.add(file.name)
                    ImportQueue.Result.NO_SOUND -> noSound.add(file.name)
                    ImportQueue.Result.FAILED -> unreadable.add(file.name)
                    ImportQueue.Result.CANCELLED -> cancelled.add(file.name)
                }
                null -> when {
                    file.kind == ImportBatch.Kind.UNSUPPORTED -> unsupported.add(file.name)
                    file.kind == ImportBatch.Kind.MEDIA && needsModel -> noModel.add(file.name)
                    else -> cancelled.add(file.name)
                }
            }
        }
        fun names(list: List<String>): String {
            val (shown, more) = ImportBatch.nameList(list)
            return if (more > 0) getString(R.string.import_batch_names_more, shown, more) else shown
        }
        val lines = mutableListOf<String>()
        if (queued.isNotEmpty()) {
            lines.add(resources.getQuantityString(R.plurals.import_batch_queued, queued.size, queued.size))
        }
        if (transcripts.isNotEmpty()) {
            lines.add(
                resources.getQuantityString(
                    R.plurals.import_batch_transcripts, transcripts.size, transcripts.size
                )
            )
        }
        lines.addAll(notes)
        if (full.isNotEmpty()) {
            lines.add(
                getString(
                    R.string.import_batch_left_full,
                    com.meetily.mobile.data.JobQueue.MAX_IMPORTS, names(full)
                )
            )
        }
        if (noSound.isNotEmpty()) lines.add(getString(R.string.import_batch_left_no_sound, names(noSound)))
        if (unreadable.isNotEmpty()) {
            lines.add(getString(R.string.import_batch_left_unreadable, names(unreadable)))
        }
        if (unsupported.isNotEmpty()) {
            lines.add(getString(R.string.import_batch_left_unsupported, names(unsupported)))
        }
        if (noModel.isNotEmpty()) lines.add(getString(R.string.import_batch_left_needs_model, names(noModel)))
        if (cancelled.isNotEmpty()) {
            lines.add(getString(R.string.import_batch_left_cancelled, names(cancelled)))
        }
        return lines
    }

    /**
     * Container-reported length of the file being imported, for the model
     * picker's time estimate. Best-effort: a source that will not report a
     * duration simply gets no estimate rather than a wrong one.
     */
    private fun sourceDurationMs(uri: Uri): Long = try {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(this, uri)
            mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } finally {
            mmr.release()
        }
    } catch (_: Throwable) {
        0L
    }

    /**
     * Every file this screen was handed, in order and once each: an "Open
     * with" or a single pick (the data), a share (one stream), or a share
     * or pick of several (a list of streams).
     */
    private fun incomingUris(): List<Uri> {
        val incoming = intent ?: return emptyList()
        val found = LinkedHashSet<Uri>()
        incoming.data?.let { found.add(it) }
        when (incoming.action) {
            Intent.ACTION_SEND -> {
                val stream = if (Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                stream?.let { found.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = if (Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                streams?.let { list -> list.filterNotNull().forEach { found.add(it) } }
            }
        }
        return found.toList()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: getString(R.string.import_title)
    }

    private fun openDetail(meetingId: String) {
        if (opened) return
        opened = true
        startActivity(
            Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
        )
        finish()
    }

    private fun openCheck(meetingId: String) {
        if (opened) return
        opened = true
        startActivity(
            Intent(this, TranscriptCheckActivity::class.java)
                .putExtra(TranscriptCheckActivity.EXTRA_MEETING_ID, meetingId)
        )
        finish()
    }

    private companion object {
        const val STATE_STARTED = "import_started"
        const val STATE_QUEUEING = "import_queueing"
        const val STATE_AWAITING_PERMISSION = "import_awaiting_permission"
        const val STATE_LANGUAGE = "import_language"
        const val STATE_SUMMARY = "import_batch_summary"
        const val STATE_SUMMARY_SETTINGS = "import_batch_summary_settings"
        const val STATE_BATCH_ID = "import_batch_id"

        /**
         * The batches being added, by id, while they are: a screen re-created
         * meanwhile looks up the one whose id it saved and takes it over
         * instead of closing (onCreate). Removed when the batch is done.
         */
        val runs = java.util.concurrent.ConcurrentHashMap<String, BatchRun>()
    }

    override fun onDestroy() {
        // Re-created: the next instance takes the batch over (onCreate).
        // Until then, or for good when this screen was closed, the batch's
        // progress has no screen to go to; its summary ends up in a toast.
        batch?.let { if (it.screen === this) it.screen = null }
        // Re-created: the next instance shows the summary again.
        summaryDialog?.dismiss()
        summaryDialog = null
        service?.removeObserver(observer)
        if (bound) {
            try {
                unbindService(connection)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
