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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings

/**
 * Thin observer over ImportService: starts an import for a shared or picked
 * audio file, shows progress, and opens the meeting when done. The service
 * keeps working if this screen (or the whole app UI) goes away.
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
                else -> {
                    Toast.makeText(
                        this@ImportActivity,
                        getString(R.string.import_failed, error ?: "unknown error"),
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
            finish() // import keeps running in the service
        }
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
                    // this file only.
                    abandoned = true
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

        val uri = incomingUri()
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
                // The old instance's copy thread carries on and reports.
                finish()
                return
            }
            resumePreStart(uri, savedInstanceState.getBoolean(STATE_AWAITING_PERMISSION, false))
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
        resumePreStart(uri, permissionRequested = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_STARTED, started && !queueing)
        outState.putBoolean(STATE_QUEUEING, queueing)
        outState.putBoolean(STATE_AWAITING_PERMISSION, awaitingPermission)
        outState.putString(STATE_LANGUAGE, importLanguage)
    }

    /** Copying into the queue; nothing was handed to the service. */
    private var queueing = false

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

    private val notificationPermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { _ ->
            // Granted or not, the import goes ahead — it just may run without
            // a visible notification, exactly as the record path behaves.
            val start = pendingStart
            pendingStart = null
            awaitingPermission = false
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
                    showModelPicker(uri, fileName, downloaded, durationMs)
                } else {
                    startImport(uri, fileName, downloaded.first())
                }
            }
        }.apply {
            name = "import-probe"
            start()
        }
    }

    private fun showModelPicker(
        uri: Uri,
        name: String,
        downloaded: List<String>,
        durationMs: Long
    ) {
        val settings = AppSettings(this)
        ModelPickerSheet.show(
            this,
            getString(R.string.import_choose_model),
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
            onPick = { key -> startImport(uri, name, key) },
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

    private fun incomingUri(): Uri? {
        intent?.data?.let { return it }
        if (intent?.action == Intent.ACTION_SEND) {
            return if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
        return null
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
    }

    override fun onDestroy() {
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
