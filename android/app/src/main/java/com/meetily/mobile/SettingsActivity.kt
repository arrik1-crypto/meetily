package com.meetily.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.BackupManager
import com.meetily.mobile.data.DeviceProbe
import com.meetily.mobile.licences.ModelTerms
import com.meetily.mobile.llm.LocalLlm
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.security.AppLock
import com.meetily.mobile.summarize.SummaryTemplates
import com.meetily.mobile.reminders.Reminders
import com.meetily.mobile.security.BackupCrypto
import com.meetily.mobile.whisper.CaptureTuning
import com.meetily.mobile.whisper.DiarizationModel
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.VoiceProfileStore
import com.meetily.mobile.whisper.WhisperModels

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    private lateinit var useLlmSwitch: MaterialSwitch
    private lateinit var offlineSwitch: MaterialSwitch
    private lateinit var muteSoundsSwitch: MaterialSwitch
    private lateinit var whisperSwitch: MaterialSwitch
    private lateinit var calendarSwitch: MaterialSwitch
    private lateinit var whisperSection: View
    private lateinit var whisperModelStatus: TextView
    private lateinit var modelProgress: LinearProgressIndicator
    private lateinit var manageModelsButton: MaterialButton
    private lateinit var diarizeSwitch: MaterialSwitch
    private lateinit var diarizeSection: View
    private lateinit var diarizeModelStatus: TextView
    private lateinit var diarizeProgress: LinearProgressIndicator
    private lateinit var manageDiarizeButton: MaterialButton
    private lateinit var captureStatus: TextView
    private lateinit var voicesStatus: TextView
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var llmSection: View

    private var downloading = false

    /**
     * The switch to Android's speech service was already confirmed on this
     * screen. Kept across recreation: the restored switch state runs the
     * change listener again, and Cancel on a second dialog would undo a
     * choice the user has already made.
     */
    private var systemEngineConfirmed = false

    /**
     * The file the picker made for an encrypted backup while its passphrase
     * is asked for. Kept across recreation, so a rotation (or the process
     * being stopped) asks again rather than leaving that file empty.
     */
    private var pendingEncryptedBackup: Uri? = null

    private val exportBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { writeBackup(it, null) }
        }
    /**
     * Encrypted export: pick the destination FIRST, then ask for the
     * passphrase.
     *
     * It used to run the other way round, holding the passphrase in a field
     * across the picker. The picker is another app, so this activity can be
     * destroyed and recreated behind it — and the field came back null. The
     * callback then quietly did nothing, having already created the
     * destination file: the user was left with a zero-byte file they believed
     * was an encrypted backup of everything.
     */
    private val exportBackupEncrypted =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            uri?.let { promptExportPassphrase(it) }
        }
    private val importBackup =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { readBackup(it) }
        }

    /**
     * "Export library as Markdown": the folder picked here goes straight to
     * LibraryExportRunner, while this screen still holds the picker's
     * grant. The runner holds the access for the length of that one export
     * (so leaving Settings does not cut it off) and gives it back after.
     */
    private val exportLibrary =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            tree?.let { startLibraryExport(it) }
        }

    /** Which library-export progress updates are read out; see ProgressAnnouncer. */
    private val libraryExportAnnouncer = ProgressAnnouncer()

    private val libraryExportListener = LibraryExportRunner.Listener { state ->
        if (!isFinishing && !isDestroyed) renderLibraryExport(state, live = true)
    }

    // --- Model downloads (owned by ModelDownloadService) --------------------

    private var downloadService: ModelDownloadService? = null
    private var downloadBound = false

    // Looked up once: these were re-resolved on every progress tick.
    private val llmProgress: LinearProgressIndicator by lazy {
        findViewById(R.id.localLlmProgress)
    }
    private val llmStatus: TextView by lazy { findViewById(R.id.localLlmStatus) }

    // Cancel under each section's progress bar: the notification's Cancel,
    // for a phone that does not show Recap's notifications.
    private val whisperCancel: View by lazy { findViewById(R.id.modelDownloadCancel) }
    private val diarizeCancel: View by lazy { findViewById(R.id.diarizeDownloadCancel) }
    private val llmCancel: View by lazy { findViewById(R.id.localLlmDownloadCancel) }

    /** The word re-timing model's row (AlignModelRow). */
    private val alignRow: AlignModelRow by lazy {
        AlignModelRow(
            this,
            findViewById(R.id.alignModelStatus),
            findViewById(R.id.alignModelProgress),
            findViewById(R.id.alignModelButton),
            findViewById(R.id.alignModelCancel)
        ) { cancelModelDownload(ModelDownloadService.KIND_ALIGN) }
    }

    // Last state actually rendered, so a repeated value (e.g. the rebind
    // catch-up replay landing on the same percent) costs no layout pass.
    private var lastRenderedKey = ""
    private var lastRenderedPercent = -1

    /** Which download-progress updates are read out; see ProgressAnnouncer. */
    private val downloadAnnouncer = ProgressAnnouncer()

    private val downloadObserver = object : ModelDownloadService.Observer {
        override fun onDownloadProgress(kind: String, key: String, percent: Int) {
            if (isFinishing || isDestroyed) return
            downloading = true
            if (key == lastRenderedKey && percent == lastRenderedPercent) return
            // A new item in the queue: its first update is spoken.
            if (key != lastRenderedKey) downloadAnnouncer.reset()
            lastRenderedKey = key
            lastRenderedPercent = percent
            val announce = downloadAnnouncer.shouldAnnounce(percent)
            if (kind == ModelDownloadService.KIND_ALIGN) {
                alignRow.onProgress(percent)
            } else if (kind == ModelDownloadService.KIND_LLM) {
                val model = LocalLlmModels.byKey(key)
                llmProgress.visibility = View.VISIBLE
                llmProgress.isIndeterminate = percent == 0
                llmProgress.progress = percent
                A11y.setStatusText(
                    llmStatus, downloadingText(kind, key, model.displayName, percent), announce
                )
                renderStopping(kind)
            } else if (kind == ModelDownloadService.KIND_DIARIZE) {
                val model = DiarizationModels.byKey(key)
                diarizeProgress.visibility = View.VISIBLE
                diarizeProgress.isIndeterminate = percent == 0
                diarizeProgress.progress = percent
                A11y.setStatusText(
                    diarizeModelStatus,
                    downloadingText(kind, key, model.displayName, percent),
                    announce
                )
                renderStopping(kind)
            } else {
                val name = com.meetily.mobile.whisper.TranscriptionModels.displayName(key)
                modelProgress.visibility = View.VISIBLE
                modelProgress.isIndeterminate = percent == 0
                modelProgress.progress = percent
                A11y.setStatusText(
                    whisperModelStatus, downloadingText(kind, key, name, percent), announce
                )
                renderStopping(kind)
            }
        }

        override fun onDownloadDone(
            kind: String,
            key: String,
            cancelled: Boolean,
            error: String?
        ) {
            if (isFinishing || isDestroyed) return
            // More items may be queued behind this one; the next item's
            // progress callback re-lights its own section.
            downloading = ModelDownloadService.queuedCount > 0
            lastRenderedKey = ""
            lastRenderedPercent = -1
            when (kind) {
                ModelDownloadService.KIND_LLM -> {
                    llmProgress.visibility = View.GONE
                    llmCancel.visibility = View.GONE
                }
                ModelDownloadService.KIND_DIARIZE -> {
                    diarizeProgress.visibility = View.GONE
                    diarizeCancel.visibility = View.GONE
                }
                ModelDownloadService.KIND_ALIGN -> alignRow.onDone(cancelled, error)
                else -> {
                    modelProgress.visibility = View.GONE
                    whisperCancel.visibility = View.GONE
                }
            }
            // A cancel, or Android's time limit, also drops a re-timing
            // download waiting behind this one; its row then shows what is
            // on the phone again.
            if (kind != ModelDownloadService.KIND_ALIGN) alignRow.refreshWhenSettled()
            // The status line is about to read "<model> downloaded", the
            // change that matters most. Set with no live region up, it was
            // heard only when it landed inside the 100% milestone's brief
            // announcement. A failure has its toast; a cancel was the user's
            // own doing.
            if (!cancelled && error == null && kind != ModelDownloadService.KIND_ALIGN) {
                A11y.announceNextChange(
                    when (kind) {
                        ModelDownloadService.KIND_LLM -> llmStatus
                        ModelDownloadService.KIND_DIARIZE -> diarizeModelStatus
                        else -> whisperModelStatus
                    }
                )
            }
            updateWhisperSection()
            updateLocalLlmStatus()
            if (kind == ModelDownloadService.KIND_LLM && OnDeviceAiSetup.takeJustEnabled()) {
                showOnDeviceAiEnabled()
            }
            if (error != null) {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.model_download_failed, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        override fun onDownloadQueued(kind: String, key: String) {
            if (isFinishing || isDestroyed) return
            when (kind) {
                ModelDownloadService.KIND_ALIGN -> alignRow.onQueued()
                ModelDownloadService.KIND_LLM ->
                    llmStatus.text = getString(
                        R.string.download_queued_status,
                        LocalLlmModels.byKey(key).displayName
                    )
                ModelDownloadService.KIND_DIARIZE ->
                    diarizeModelStatus.text = getString(
                        R.string.download_queued_status,
                        DiarizationModels.byKey(key).displayName
                    )
                else ->
                    whisperModelStatus.text = getString(
                        R.string.download_queued_status,
                        com.meetily.mobile.whisper.TranscriptionModels.displayName(key)
                    )
            }
        }
    }

    private val downloadConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? ModelDownloadService.DownloadBinder)?.service ?: return
            downloadService = svc
            svc.observer = downloadObserver
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind so a download started earlier (or from this screen) reports
        // progress here; downloads themselves live in the service and keep
        // going when this screen is left or the phone sleeps.
        bindService(
            Intent(this, ModelDownloadService::class.java),
            downloadConnection,
            Context.BIND_AUTO_CREATE
        )
        downloadBound = true
        // A library export runs on without this screen; pick its progress
        // (or its final count) up again, silently.
        LibraryExportRunner.listener = libraryExportListener
        renderLibraryExport(LibraryExportRunner.state(), live = false)
        if (!ModelDownloadService.isRunning && downloading) {
            // The download finished while we were away.
            downloading = false
            lastRenderedKey = ""
            lastRenderedPercent = -1
            modelProgress.visibility = View.GONE
            diarizeProgress.visibility = View.GONE
            llmProgress.visibility = View.GONE
            whisperCancel.visibility = View.GONE
            diarizeCancel.visibility = View.GONE
            llmCancel.visibility = View.GONE
            updateWhisperSection()
            updateLocalLlmStatus()
            alignRow.onDone(cancelled = false, error = null)
        }
    }

    override fun onResume() {
        super.onResume()
        // The first-run setup may have turned on-device AI on while this
        // screen was stopped, with no observer attached to hear it; Save
        // would then write the stale switches back over it. Checked here
        // rather than in onStart because a screen recreated while away
        // restores its switches' saved state after onStart.
        if (OnDeviceAiSetup.takeJustEnabled()) showOnDeviceAiEnabled()
        renderRetentionSummary()
    }

    override fun onStop() {
        if (LibraryExportRunner.listener === libraryExportListener) LibraryExportRunner.listener = null
        downloadService?.let { if (it.observer === downloadObserver) it.observer = null }
        if (downloadBound) {
            try {
                unbindService(downloadConnection)
            } catch (_: Exception) {
            }
            downloadBound = false
        }
        downloadService = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_settings)
        EdgeToEdge.setUp(this) {
            root(bottom = false)
            list(R.id.edgeScroll)
        }

        settings = AppSettings(this)
        if (!ModelDownloadService.isRunning) {
            // Never sweep .part files while the download service is mid-write.
            WhisperModels.cleanPartials(this)
            com.meetily.mobile.whisper.NemoModels.cleanPartials(this)
            DiarizationModels.cleanPartials(this)
            LocalLlmModels.cleanPartials(this)
            com.meetily.mobile.whisper.AlignerModels.cleanPartials(this)
            // A pick parked behind a download that died with the process
            // would otherwise claim "switching when ready" forever.
            PendingModelSelection.clearStale(this)
        }

        findViewById<MaterialToolbar>(R.id.settingsToolbar).setNavigationOnClickListener {
            finish()
        }

        useLlmSwitch = findViewById(R.id.useLlmSwitch)
        offlineSwitch = findViewById(R.id.offlineSwitch)
        muteSoundsSwitch = findViewById(R.id.muteSoundsSwitch)
        whisperSwitch = findViewById(R.id.whisperSwitch)
        calendarSwitch = findViewById(R.id.calendarSwitch)
        whisperSection = findViewById(R.id.whisperSection)
        whisperModelStatus = findViewById(R.id.whisperModelStatus)
        modelProgress = findViewById(R.id.modelProgress)
        manageModelsButton = findViewById(R.id.manageModelsButton)
        diarizeSwitch = findViewById(R.id.diarizeSwitch)
        diarizeSection = findViewById(R.id.diarizeSection)
        diarizeModelStatus = findViewById(R.id.diarizeModelStatus)
        diarizeProgress = findViewById(R.id.diarizeProgress)
        manageDiarizeButton = findViewById(R.id.manageDiarizeButton)
        urlInput = findViewById(R.id.llmUrlInput)
        keyInput = findViewById(R.id.llmKeyInput)
        modelInput = findViewById(R.id.llmModelInput)
        llmSection = findViewById(R.id.llmSection)

        useLlmSwitch.isChecked = settings.useLlm
        offlineSwitch.isChecked = settings.preferOfflineRecognition
        muteSoundsSwitch.isChecked = settings.muteRecognizerSounds
        whisperSwitch.isChecked = settings.transcriptionEngine == "whisper"
        systemEngineConfirmed =
            savedInstanceState?.getBoolean(KEY_SYSTEM_ENGINE_OK, false) ?: false
        diarizeSwitch.isChecked = settings.diarizationEnabled
        findViewById<MaterialSwitch>(R.id.translateSwitch).isChecked = settings.whisperTranslate
        findViewById<MaterialSwitch>(R.id.vadSwitch).isChecked = settings.vadSpeechGate
        findViewById<MaterialSwitch>(R.id.liveTranscriptionSwitch).isChecked =
            settings.liveTranscription
        findViewById<MaterialSwitch>(R.id.autoCheckSwitch).isChecked =
            settings.autoCheckTranscript
        setUpAutoWork()
        setUpLanguages()
        findViewById<android.widget.EditText>(R.id.vocabInput).setText(settings.customVocab)
        findViewById<android.widget.EditText>(R.id.userNameInput).setText(settings.userName)
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
            R.id.textSizeToggle
        ).apply {
            check(
                when (settings.transcriptTextSize) {
                    "small" -> R.id.textSizeSmall
                    "large" -> R.id.textSizeLarge
                    else -> R.id.textSizeMedium
                }
            )
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                settings.transcriptTextSize = when (checkedId) {
                    R.id.textSizeSmall -> "small"
                    R.id.textSizeLarge -> "large"
                    else -> "medium"
                }
            }
        }
        findViewById<View>(R.id.calendarCheckButton).setOnClickListener {
            startActivity(Intent(this, CalendarDiagnosticsActivity::class.java))
        }
        findViewById<View>(R.id.vocabMedicalButton).setOnClickListener {
            appendVocabPreset(com.meetily.mobile.whisper.Vocab.MEDICAL_PRESET)
        }
        findViewById<View>(R.id.vocabLegalButton).setOnClickListener {
            appendVocabPreset(com.meetily.mobile.whisper.Vocab.LEGAL_PRESET)
        }
        findViewById<MaterialSwitch>(R.id.saveAudioSwitch).isChecked = settings.saveAudio
        calendarSwitch.isChecked = settings.calendarPrefill
        setUpNudgeSwitch()
        urlInput.setText(settings.llmBaseUrl)
        keyInput.setText(settings.llmApiKey)
        modelInput.setText(settings.llmModel)

        updateLlmSectionVisibility()
        setUpLlmEngine()
        updateWhisperSection()

        setUpThemeToggle()
        useLlmSwitch.setOnCheckedChangeListener { _, _ -> updateLlmSectionVisibility() }
        whisperSwitch.setOnCheckedChangeListener { _, checked ->
            updateWhisperSection()
            if (checked) {
                systemEngineConfirmed = false
                // The first runtime check loads the native library (llama.cpp
                // as well as whisper.cpp), so it runs off the main thread.
                Thread {
                    if (!WhisperModels.isRuntimeAvailable()) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            Toast.makeText(this, R.string.whisper_unavailable, Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                }.start()
            } else if (!systemEngineConfirmed) {
                confirmSystemEngine()
            }
        }
        manageModelsButton.setOnClickListener { showModelDialog() }
        whisperCancel.setOnClickListener { cancelModelDownload(ModelDownloadService.KIND_WHISPER) }
        diarizeCancel.setOnClickListener { cancelModelDownload(ModelDownloadService.KIND_DIARIZE) }
        llmCancel.setOnClickListener { cancelModelDownload(ModelDownloadService.KIND_LLM) }
        diarizeSwitch.setOnCheckedChangeListener { _, _ -> updateDiarizeSection() }
        manageDiarizeButton.setOnClickListener { showDiarizeModelDialog() }
        captureStatus = findViewById(R.id.captureStatus)
        updateCaptureStatus()
        findViewById<View>(R.id.micTuningButton).setOnClickListener { showMicTuningDialog() }
        findViewById<View>(R.id.micDeviceButton).setOnClickListener { showMicDeviceDialog() }
        voicesStatus = findViewById(R.id.voicesStatus)
        updateVoicesStatus()
        findViewById<View>(R.id.manageVoicesButton).setOnClickListener { showVoicesDialog() }
        alignRow.bind()

        findViewById<View>(R.id.exportBackupButton).setOnClickListener { showExportChoice() }
        findViewById<View>(R.id.importBackupButton).setOnClickListener {
            try {
                // "*/*" so encrypted .recapbak backups show up alongside zips.
                importBackup.launch("*/*")
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(
                        R.string.restore_failed,
                        e.message ?: getString(R.string.platform_error_no_picker)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        setUpLibraryExport()

        setUpStorageSection()

        setUpSecuritySection()
        findViewById<View>(R.id.privacyLink).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
        findViewById<View>(R.id.licensesLink).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
        findViewById<View>(R.id.termsLink).setOnClickListener {
            startActivity(Intent(this, TermsActivity::class.java))
        }
        findViewById<View>(R.id.reportBugLink).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.bug_report_subject))
                putExtra(
                    Intent.EXTRA_TEXT,
                    com.meetily.mobile.diag.CrashLog.bugReportText(this@SettingsActivity)
                )
            }
            com.meetily.mobile.export.MeetingShareFile.clearAll(this)
            startActivity(Intent.createChooser(send, getString(R.string.report_bug)))
        }

        findViewById<View>(R.id.saveButton).setOnClickListener {
            persistAll()
            // Nudge/reminder alarms depend on the just-saved settings.
            Thread { Reminders.rescheduleAll(applicationContext) }.start()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            if (!askLocalNetworkAfterSave()) finish()
        }

        // An encrypted backup's passphrase was being asked for when this
        // screen was re-created: ask again for the file the picker made.
        savedInstanceState?.getString(KEY_PENDING_BACKUP)?.let { promptExportPassphrase(Uri.parse(it)) }
    }

    /** A local network question is under way; Save waits for it. */
    private var localNetworkAsking = false

    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            localNetworkAsking = false
            if (granted) {
                finish()
            } else {
                // Saved before the question, so a screen re-created meanwhile
                // names the same host.
                val host = LocalNetworkUi.hostOf(settings.llmBaseUrl).orEmpty()
                LocalNetworkUi.showPermissionDialog(
                    this, getString(R.string.platform_lan_denied, host)
                ) { finish() }
            }
        }

    /**
     * Android 17, with a build targeting it: an AI endpoint on the local
     * network is reachable only with the local network permission, so saving
     * one asks for it. True when that question took over closing the screen.
     * Never true while the build targets 36 (LocalNetworkUi.applies).
     */
    private fun askLocalNetworkAfterSave(): Boolean {
        if (localNetworkAsking) return true
        if (!LocalNetworkUi.applies(this) || LocalNetworkUi.granted(this)) return false
        if (!settings.useLlm || settings.llmEngine == "local") return false
        val url = settings.llmBaseUrl
        if (LocalNetworkUi.hostOf(url) == null) return false
        localNetworkAsking = true
        Thread {
            // A name is looked up, as EndpointGuard does before each request.
            val kind = LocalNetworkUi.endpointKind(url)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (com.meetily.mobile.security.LocalNetworkAccess.shouldRequest(
                        applies = true, granted = LocalNetworkUi.granted(this), kind = kind
                    )
                ) {
                    try {
                        localNetworkPermission.launch(
                            com.meetily.mobile.security.LocalNetworkAccess.PERMISSION
                        )
                        return@runOnUiThread
                    } catch (_: Exception) {
                    }
                }
                localNetworkAsking = false
                finish()
            }
        }.start()
        return true
    }

    /**
     * The Storage screen and the automatic clean-up rule. The rule is not a
     * Save-button setting: choosing one shows what its first run would
     * delete and is saved only on yes (RetentionUi).
     */
    private fun setUpStorageSection() {
        findViewById<View>(R.id.settingsStorageButton).setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }
        findViewById<View>(R.id.settingsTrashButton).setOnClickListener {
            startActivity(Intent(this, TrashActivity::class.java))
        }
        findViewById<View>(R.id.settingsRetentionButton).setOnClickListener {
            RetentionUi.choose(this) { if (!isFinishing && !isDestroyed) renderRetentionSummary() }
        }
    }

    /** Data: "Export library as Markdown" and its Stop button. */
    private fun setUpLibraryExport() {
        findViewById<View>(R.id.exportLibraryButton).setOnClickListener {
            try {
                exportLibrary.launch(null)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(
                        R.string.export_failed,
                        e.message ?: getString(R.string.platform_error_no_picker)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        findViewById<View>(R.id.exportLibraryCancel).setOnClickListener {
            // Stops between meetings; the files already written stay.
            LibraryExportRunner.cancel()
        }
    }

    private fun startLibraryExport(tree: Uri) {
        if (!LibraryExportRunner.start(this, tree)) {
            Toast.makeText(this, R.string.export_library_running, Toast.LENGTH_SHORT).show()
            return
        }
        libraryExportAnnouncer.reset()
        renderLibraryExport(LibraryExportRunner.state(), live = true)
    }

    /**
     * The export's progress, Stop button and final count. [live] is true for
     * a change the run just made, which may be spoken (the start, a step of
     * ProgressAnnouncer, stopping, the result); a re-render when the screen
     * comes back is silent.
     */
    private fun renderLibraryExport(state: LibraryExportRunner.State, live: Boolean) {
        val button = findViewById<View>(R.id.exportLibraryButton)
        val stop = findViewById<View>(R.id.exportLibraryCancel)
        val bar = findViewById<LinearProgressIndicator>(R.id.exportLibraryProgress)
        val status = findViewById<TextView>(R.id.exportLibraryStatus)
        button.isEnabled = !state.running
        stop.visibility = if (state.running && !state.cancelling) View.VISIBLE else View.GONE
        if (!state.running) {
            bar.visibility = View.GONE
            val result = state.result
            if (result == null) {
                status.visibility = View.GONE
            } else {
                status.visibility = View.VISIBLE
                A11y.setStatusText(status, LibraryExportRunner.message(this, result), live)
            }
            return
        }
        bar.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        val percent = if (state.total > 0) state.done * 100 / state.total else -1
        bar.isIndeterminate = percent < 0
        if (percent >= 0) bar.progress = percent
        val text = when {
            state.cancelling -> getString(R.string.export_library_stopping)
            state.total <= 0 -> getString(R.string.export_library_listing)
            // The meeting being written now, counting from 1.
            else -> getString(
                R.string.export_library_progress, minOf(state.done + 1, state.total), state.total
            )
        }
        val announce = live && if (state.cancelling) {
            status.text.toString() != text
        } else {
            libraryExportAnnouncer.shouldAnnounce(percent)
        }
        A11y.setStatusText(status, text, announce)
    }

    /** On every resume too: the Storage screen can change the rule. */
    private fun renderRetentionSummary() {
        // With a batch the daily run held back for review, which Storage
        // and clean-up opens: its notification may never have shown.
        val pending = RetentionUi.pendingNote(this)
        val summary = RetentionUi.summary(this, RetentionRunner.rule(this))
        findViewById<TextView>(R.id.settingsRetentionSummary).text =
            if (pending == null) summary else summary + "\n" + pending
    }

    /**
     * Spoken language (Transcription) and summary language (Summaries).
     * Saved the moment one is picked, like the text-size toggle: a choice
     * made in a dialog has no view state for persistAll to read back.
     */
    private fun setUpLanguages() {
        val spoken = findViewById<MaterialButton>(R.id.languageSpokenButton)
        val summary = findViewById<MaterialButton>(R.id.languageSummaryButton)
        fun render() {
            val spokenName = LanguagePicker.spokenLabel(this, settings.spokenLanguage)
            spoken.text = spokenName
            spoken.contentDescription = getString(R.string.language_spoken_button_desc, spokenName)
            val summaryName = LanguagePicker.summaryLabel(this, settings.summaryLanguage)
            summary.text = summaryName
            summary.contentDescription =
                getString(R.string.language_summary_button_desc, summaryName)
        }
        render()
        spoken.setOnClickListener {
            LanguagePicker.show(
                this,
                getString(R.string.language_spoken_title),
                getString(R.string.language_auto),
                com.meetily.mobile.data.MeetingLanguage.AUTO,
                settings.spokenLanguage
            ) { value ->
                settings.spokenLanguage = value
                render()
            }
        }
        A11y.labelClick(spoken, getString(R.string.language_change_spoken))
        summary.setOnClickListener {
            LanguagePicker.show(
                this,
                getString(R.string.language_summary_title),
                getString(R.string.language_same_as_meeting),
                com.meetily.mobile.summarize.OutputLanguage.SAME,
                settings.summaryLanguage
            ) { value ->
                settings.summaryLanguage = value
                render()
            }
        }
        A11y.labelClick(summary, getString(R.string.language_change_summary))
    }

    /**
     * Automatic post-meeting work: the charging qualifier for the accuracy
     * pass, and the automatic summary with its style and timing.
     */
    private fun setUpAutoWork() {
        val autoCheck = findViewById<MaterialSwitch>(R.id.autoCheckSwitch)
        val charging = findViewById<MaterialSwitch>(R.id.autoCheckChargingSwitch)
        charging.isChecked = settings.autoCheckWhileChargingOnly
        charging.isEnabled = autoCheck.isChecked
        autoCheck.setOnCheckedChangeListener { _, on -> charging.isEnabled = on }

        val autoTitle = findViewById<MaterialSwitch>(R.id.autoTitleSwitch)
        autoTitle.isChecked = settings.autoTitleFromTranscript
        // The offline title needs no consent, but the endpoint refinement
        // shares the auto-summary's gate and had no way to be granted from
        // here, so on the endpoint engine it silently never ran.
        autoTitle.setOnCheckedChangeListener { _, on ->
            if (on) confirmEndpointTitles()
        }

        val autoSummary = findViewById<MaterialSwitch>(R.id.autoSummarySwitch)
        val styleButton =
            findViewById<com.google.android.material.button.MaterialButton>(
                R.id.autoSummaryStyleButton
            )
        val whenButton =
            findViewById<com.google.android.material.button.MaterialButton>(
                R.id.autoSummaryWhenButton
            )

        fun renderAutoSummary() {
            val on = autoSummary.isChecked
            styleButton.isEnabled = on
            whenButton.isEnabled = on
            val template = SummaryTemplates.allWithCustom(this)
                .firstOrNull { it.key == settings.autoSummaryTemplate }
            styleButton.text = getString(
                R.string.setting_auto_summary_style,
                template?.label(this) ?: getString(R.string.template_general)
            )
            whenButton.text = getString(
                R.string.setting_auto_summary_when,
                getString(
                    if (settings.autoSummaryWhen == "charging") {
                        R.string.auto_summary_when_charging
                    } else {
                        R.string.auto_summary_when_end
                    }
                )
            )
        }

        autoSummary.isChecked = settings.autoSummarize
        renderAutoSummary()
        autoSummary.setOnCheckedChangeListener { _, on ->
            if (on && !confirmEndpointSummaries()) {
                autoSummary.isChecked = false
                return@setOnCheckedChangeListener
            }
            settings.autoSummarize = on
            renderAutoSummary()
        }
        styleButton.setOnClickListener {
            val templates = SummaryTemplates.allWithCustom(this)
            val current = templates
                .indexOfFirst { it.key == settings.autoSummaryTemplate }
                .coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_auto_summary_style_title)
                .setSingleChoiceItems(
                    templates.map { it.label(this) }.toTypedArray(), current
                ) { dialog, which ->
                    settings.autoSummaryTemplate = templates[which].key
                    renderAutoSummary()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        whenButton.setOnClickListener {
            val keys = arrayOf("end", "charging")
            val labels = arrayOf(
                getString(R.string.auto_summary_when_end),
                getString(R.string.auto_summary_when_charging)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_auto_summary_when_title)
                .setSingleChoiceItems(
                    labels, keys.indexOf(settings.autoSummaryWhen).coerceAtLeast(0)
                ) { dialog, which ->
                    settings.autoSummaryWhen = keys[which]
                    renderAutoSummary()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * An automatic summary against a remote endpoint would send the full text
     * of every meeting off-device with the user never pressing anything. That
     * needs saying out loud once, and re-saying if the engine changes.
     */
    private fun confirmEndpointSummaries(): Boolean {
        if (settings.llmEngine == "local") return true
        if (settings.autoSummaryEndpointOk) return true
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_summary_endpoint_title)
            .setMessage(R.string.auto_summary_endpoint_body)
            .setPositiveButton(R.string.auto_summary_endpoint_yes) { _, _ ->
                settings.autoSummaryEndpointOk = true
                settings.autoSummarize = true
                findViewById<MaterialSwitch>(R.id.autoSummarySwitch).isChecked = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return false
    }

    /**
     * The endpoint half of "name meetings from the transcript": it sends the
     * transcript off-device unprompted, so it asks like the auto-summary
     * does. Declining keeps the switch on — titles are then taken on the
     * device only.
     */
    private fun confirmEndpointTitles() {
        if (settings.llmEngine == "local") return
        if (settings.autoSummaryEndpointOk) return
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_title_endpoint_title)
            .setMessage(R.string.auto_title_endpoint_body)
            .setPositiveButton(R.string.auto_summary_endpoint_yes) { _, _ ->
                settings.autoSummaryEndpointOk = true
            }
            .setNegativeButton(R.string.auto_title_endpoint_no, null)
            .show()
    }

    /**
     * Turning the on-device engine off hands meetings to Android's speech
     * service, which on most phones is Google's and may send the audio to its
     * servers, and which leaves no recording to re-transcribe. Said once per
     * switch-off; Cancel or Back puts the switch back. The cloud half of the
     * warning is dropped only when the phone's offline recognizer is both
     * preferred and available, the same test RecordingService.createRecognizer
     * makes.
     */
    private fun confirmSystemEngine() {
        val offlineRecognizer = offlineSwitch.isChecked &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_system_engine_title)
            .setMessage(
                if (offlineRecognizer) R.string.privacy_system_engine_body_offline
                else R.string.privacy_system_engine_body
            )
            .setPositiveButton(R.string.privacy_system_engine_confirm) { _, _ ->
                systemEngineConfirmed = true
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                whisperSwitch.isChecked = true
            }
            .setOnCancelListener { whisperSwitch.isChecked = true }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SYSTEM_ENGINE_OK, systemEngineConfirmed)
        pendingEncryptedBackup?.let { outState.putString(KEY_PENDING_BACKUP, it.toString()) }
    }

    private fun persistAll() {
        settings.useLlm = useLlmSwitch.isChecked
        settings.preferOfflineRecognition = offlineSwitch.isChecked
        settings.muteRecognizerSounds = muteSoundsSwitch.isChecked
        settings.transcriptionEngine =
            if (whisperSwitch.isChecked) "whisper" else "system"
        settings.calendarPrefill = calendarSwitch.isChecked
        settings.meetingNudges = findViewById<MaterialSwitch>(R.id.nudgeSwitch).isChecked
        settings.diarizationEnabled = diarizeSwitch.isChecked
        settings.whisperTranslate =
            findViewById<MaterialSwitch>(R.id.translateSwitch).isChecked
        settings.vadSpeechGate = findViewById<MaterialSwitch>(R.id.vadSwitch).isChecked
        settings.liveTranscription =
            findViewById<MaterialSwitch>(R.id.liveTranscriptionSwitch).isChecked
        settings.autoCheckTranscript =
            findViewById<MaterialSwitch>(R.id.autoCheckSwitch).isChecked
        settings.autoCheckWhileChargingOnly =
            findViewById<MaterialSwitch>(R.id.autoCheckChargingSwitch).isChecked
        settings.autoTitleFromTranscript =
            findViewById<MaterialSwitch>(R.id.autoTitleSwitch).isChecked
        settings.customVocab =
            findViewById<android.widget.EditText>(R.id.vocabInput).text.toString().trim()
        settings.userName =
            findViewById<android.widget.EditText>(R.id.userNameInput).text.toString().trim()
        settings.saveAudio = findViewById<MaterialSwitch>(R.id.saveAudioSwitch).isChecked
        settings.llmBaseUrl = urlInput.text.toString().trim()
        settings.llmApiKey = keyInput.text.toString().trim()
        settings.llmModel = modelInput.text.toString().trim()
        settings.localOnlyLlm = findViewById<MaterialSwitch>(R.id.localOnlySwitch).isChecked
        settings.appLock = findViewById<MaterialSwitch>(R.id.appLockSwitch).isChecked
        settings.secureScreen = findViewById<MaterialSwitch>(R.id.secureScreenSwitch).isChecked
    }

    private val nudgePermissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (grants[android.Manifest.permission.READ_CALENDAR] != true) {
                findViewById<MaterialSwitch>(R.id.nudgeSwitch).isChecked = false
                Toast.makeText(this, R.string.nudges_need_calendar, Toast.LENGTH_LONG).show()
            }
        }

    private fun setUpNudgeSwitch() {
        val nudgeSwitch = findViewById<MaterialSwitch>(R.id.nudgeSwitch)
        nudgeSwitch.isChecked = settings.meetingNudges
        nudgeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) return@setOnCheckedChangeListener
            val wanted = mutableListOf(android.Manifest.permission.READ_CALENDAR)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                wanted.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = wanted.filter {
                ContextCompat.checkSelfPermission(this, it) !=
                    PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                try {
                    nudgePermissions.launch(missing.toTypedArray())
                } catch (_: Exception) {
                }
            }
        }
    }

    // --- AI engine (endpoint vs on-device) ----------------------------------

    private fun setUpLlmEngine() {
        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.llmEngineToggle)
        toggle.check(
            if (settings.llmEngine == "local") R.id.engineLocal else R.id.engineEndpoint
        )
        applyEngineVisibility()
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.llmEngine =
                if (checkedId == R.id.engineLocal) "local" else "endpoint"
            applyEngineVisibility()
            // Changing engine withdraws the endpoint consent (see
            // AppSettings.llmEngine), which left an auto-summary switch
            // showing ON while every automatic summary quietly stood down.
            // Make the switch tell the truth, then ask again.
            if (settings.llmEngine != "local" && !settings.autoSummaryEndpointOk) {
                val autoSummary = findViewById<MaterialSwitch>(R.id.autoSummarySwitch)
                if (autoSummary.isChecked) {
                    autoSummary.isChecked = false
                    confirmEndpointSummaries()
                } else if (findViewById<MaterialSwitch>(R.id.autoTitleSwitch).isChecked) {
                    confirmEndpointTitles()
                }
            }
        }
        findViewById<View>(R.id.manageLlmButton).setOnClickListener {
            showLlmModelDialog()
        }
        updateLocalLlmStatus()
    }

    private fun applyEngineVisibility() {
        val local = settings.llmEngine == "local"
        findViewById<View>(R.id.localLlmSection).visibility =
            if (local) View.VISIBLE else View.GONE
        findViewById<View>(R.id.endpointSection).visibility =
            if (local) View.GONE else View.VISIBLE
    }

    /**
     * The first-run setup just turned on-device AI on behind this screen
     * (OnDeviceAiSetup). Shown on the switches, or Save would write their
     * old state back over it.
     */
    private fun showOnDeviceAiEnabled() {
        if (settings.useLlm) useLlmSwitch.isChecked = true
        if (settings.llmEngine == "local") {
            findViewById<MaterialButtonToggleGroup>(R.id.llmEngineToggle).check(R.id.engineLocal)
        }
    }

    private fun updateLocalLlmStatus() {
        val model = LocalLlmModels.byKey(settings.localLlmModel)
        llmStatus.text = withPendingNote(
            ModelDownloadService.KIND_LLM,
            if (LocalLlmModels.isDownloaded(this, model)) {
                getString(R.string.model_status_downloaded, model.displayName)
            } else {
                getString(R.string.model_status_missing, model.displayName)
            }
        )
    }

    private fun showLlmModelDialog() {
        ModelPickerSheet.show(
            this,
            getString(R.string.manage_llm_models),
            entriesProvider = {
                LocalLlmModels.ALL.map { model ->
                    ModelPickerSheet.Entry(
                        key = model.key,
                        title = model.displayName,
                        meta = pendingMeta(
                            ModelDownloadService.KIND_LLM,
                            model.key,
                            getString(
                                R.string.model_card_meta,
                                model.sizeMb,
                                getString(R.string.model_lang_multi),
                                "GGUF"
                            )
                        ),
                        downloaded = LocalLlmModels.isDownloaded(this, model),
                        selected = settings.localLlmModel == model.key,
                        licence = ModelTerms.pickerLine(this, model.key),
                        fit = DeviceProbe.verdict(this, model.fitNeed)
                    )
                }
            },
            onPick = { key ->
                // Switching frees the loaded model (in select) so the next
                // generation loads the new one — but only once it is on disk.
                pickModel(ModelDownloadService.KIND_LLM, LocalLlmModels.byKey(key).key)
            },
            onDelete = { entry ->
                if (!deleteBlockedByDownload(ModelDownloadService.KIND_LLM, entry.key)) {
                    // Deleting the active model: drop the loaded instance first.
                    val wasSelected = settings.localLlmModel == entry.key
                    if (wasSelected) LocalLlm.release()
                    LocalLlmModels.delete(this, LocalLlmModels.byKey(entry.key))
                    if (wasSelected) {
                        reselectAfterDelete(
                            ModelDownloadService.KIND_LLM,
                            entry.key,
                            LocalLlmModels.ALL
                                .filter { LocalLlmModels.isDownloaded(this, it) }
                                .map { it.key }
                        )
                    }
                    updateLocalLlmStatus()
                }
            },
            onDeleteAll = {
                if (!deleteBlockedByDownload(ModelDownloadService.KIND_LLM)) {
                    LocalLlm.release()
                    LocalLlmModels.deleteAll(this)
                    updateLocalLlmStatus()
                }
            }
        )
    }

    // --- Security -----------------------------------------------------------

    private fun setUpSecuritySection() {
        val appLockSwitch = findViewById<MaterialSwitch>(R.id.appLockSwitch)
        val secureScreenSwitch = findViewById<MaterialSwitch>(R.id.secureScreenSwitch)
        val localOnlySwitch = findViewById<MaterialSwitch>(R.id.localOnlySwitch)
        appLockSwitch.isChecked = settings.appLock
        secureScreenSwitch.isChecked = settings.secureScreen
        localOnlySwitch.isChecked = settings.localOnlyLlm
        appLockSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !AppLock.canUseLock(this)) {
                appLockSwitch.isChecked = false
                Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        // Window flags are otherwise only decided at activity creation, so
        // reflect the toggle immediately — on every screen that is already
        // open, not just this one. Applying it here alone left the library
        // and any open meeting underneath still capturable for the rest of
        // their lives, while the UI said the protection was on.
        secureScreenSwitch.setOnCheckedChangeListener { _, checked ->
            settings.secureScreen = checked
            AppLock.applySecureFlagToAll()
        }
    }

    private var suppressThemeListener = false

    private fun setUpThemeToggle() {
        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.themeToggle)
        suppressThemeListener = true
        toggle.check(
            when (settings.themeMode) {
                "light" -> R.id.themeLight
                "dark" -> R.id.themeDark
                else -> R.id.themeSystem
            }
        )
        suppressThemeListener = false
        toggle.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked || suppressThemeListener) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLight -> "light"
                R.id.themeDark -> "dark"
                else -> "system"
            }
            if (mode == settings.themeMode) return@addOnButtonCheckedListener
            // Model downloads live in ModelDownloadService, so the recreate
            // this triggers no longer orphans them.
            settings.themeMode = mode
            // Posted: setDefaultNightMode recreates this activity, and doing
            // that from inside the toggle-group's checked-change dispatch
            // (especially with FLAG_SECURE windows) can flicker or wedge.
            group.post { ThemeManager.applyNightMode(mode) }
        }
    }

    private fun updateLlmSectionVisibility() {
        llmSection.visibility = if (useLlmSwitch.isChecked) View.VISIBLE else View.GONE
    }

    private fun showExportChoice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_encrypt_title)
            .setMessage(R.string.backup_encrypt_message)
            .setPositiveButton(R.string.backup_encrypt_yes) { _, _ ->
                try {
                    exportBackupEncrypted.launch(getString(R.string.backup_enc_file_name))
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.backup_failed,
                            e.message ?: getString(R.string.platform_error_no_picker)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(R.string.backup_plain) { _, _ ->
                try {
                    exportBackup.launch(getString(R.string.backup_file_name))
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.backup_failed,
                            e.message ?: getString(R.string.platform_error_no_picker)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun promptExportPassphrase(destination: Uri) {
        val pass = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.backup_passphrase_hint)
        }
        val repeat = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.backup_passphrase_repeat_hint)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(pass)
            addView(repeat)
        }
        pendingEncryptedBackup = destination
        // The destination file already exists by now: the picker made it.
        // Every way out of this question other than a backup written into
        // it removes that file (or says to), so an empty file is never left
        // looking like a backup, which is the failure this reordering
        // exists to remove.
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.backup_encrypt_yes)
            .setView(container)
            // Set again once shown, so a passphrase that is too short or
            // does not match keeps the question up rather than ending it.
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> abandonEncryptedBackup(destination) }
            // Back, or a tap outside: the same as Cancel.
            .setOnCancelListener { abandonEncryptedBackup(destination) }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val p = pass.text.toString()
            when {
                p.length < 6 -> pass.error = getString(R.string.backup_passphrase_short)
                p != repeat.text.toString() -> repeat.error = getString(R.string.backup_passphrase_mismatch)
                else -> {
                    pendingEncryptedBackup = null
                    dialog.dismiss()
                    writeBackup(destination, p.toCharArray())
                }
            }
        }
    }

    /**
     * The encrypted backup's passphrase was not given: the empty file the
     * picker made for it is deleted, or, when its provider refuses, the
     * user is told to delete it.
     */
    private fun abandonEncryptedBackup(destination: Uri) {
        pendingEncryptedBackup = null
        val app = applicationContext
        Thread {
            val removed = deleteDocument(app, destination)
            runOnUiThread {
                Toast.makeText(
                    app,
                    if (removed) R.string.backup_cancelled else R.string.backup_cancelled_empty,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /** Deletes the document [uri] names; false when its provider would not. */
    private fun deleteDocument(context: Context, uri: Uri): Boolean = try {
        android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (_: Exception) {
        false
    }

    private fun writeBackup(uri: Uri, passphrase: CharArray?) {
        // The application's context throughout: an export with audio runs
        // for minutes, and its result still has to show (and a failed one
        // still has to be cleared up) once this screen has closed.
        val app = applicationContext
        Thread {
            val error = try {
                app.contentResolver.openOutputStream(uri)?.use {
                    if (passphrase != null) {
                        BackupManager.exportEncrypted(app, it, passphrase)
                    } else {
                        BackupManager.export(app, it)
                    }
                } ?: throw RuntimeException(app.getString(R.string.platform_error_no_destination))
                null
            } catch (e: Exception) {
                e.message ?: app.getString(R.string.platform_error_unknown)
            }
            // What a failed export wrote is not a backup, yet a plain zip cut
            // short can still restore as if it were whole: it goes, or the
            // user is told it is incomplete.
            val removed = error != null && deleteDocument(app, uri)
            val message = when {
                error == null -> app.getString(R.string.backup_done)
                removed -> app.getString(R.string.backup_failed, error)
                else -> app.getString(R.string.backup_failed_partial, error)
            }
            runOnUiThread {
                Toast.makeText(
                    app, message, if (error == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun readBackup(uri: Uri) {
        // Off the main thread: for a cloud document (Drive and the like),
        // opening the stream blocks until the provider has downloaded the
        // file, and a backup carries every meeting's audio — long enough for
        // the app-not-responding dialog if it ran here.
        Toast.makeText(this, R.string.restore_reading, Toast.LENGTH_SHORT).show()
        Thread {
            var error: String? = null
            val encrypted = try {
                contentResolver.openInputStream(uri)?.use { BackupManager.sniffEncrypted(it) }
                    ?: false
            } catch (e: Exception) {
                // A file that cannot even be opened is a failure to report,
                // not a hint that it is unencrypted.
                error = e.message ?: getString(R.string.platform_error_unknown)
                false
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    error != null -> Toast.makeText(
                        this, getString(R.string.restore_failed, error), Toast.LENGTH_LONG
                    ).show()
                    encrypted -> promptRestorePassphrase(uri)
                    else -> doRestore(uri, null)
                }
            }
        }.start()
    }

    private fun promptRestorePassphrase(uri: Uri) {
        val pass = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.backup_passphrase_hint)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_passphrase_title)
            .setMessage(R.string.restore_passphrase_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                doRestore(uri, pass.text.toString().toCharArray())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Counts the backup's meetings that are on this phone too and, when
     * there are any, asks whether the backup's copies replace them before
     * anything is written: a replaced meeting loses every change made here
     * since the backup, with no undo. A wrong passphrase shows here, before
     * the question.
     */
    private fun doRestore(uri: Uri, passphrase: CharArray?) {
        Thread {
            var onPhone = 0
            val error = try {
                onPhone = contentResolver.openInputStream(uri)?.use {
                    BackupManager.countOnPhone(this, it, passphrase)
                } ?: throw RuntimeException(getString(R.string.platform_error_read_failed))
                null
            } catch (e: BackupCrypto.WrongPassphraseException) {
                getString(R.string.restore_wrong_passphrase)
            } catch (e: Exception) {
                e.message ?: getString(R.string.platform_error_unknown)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    error != null -> Toast.makeText(
                        this, getString(R.string.restore_failed, error), Toast.LENGTH_LONG
                    ).show()
                    onPhone == 0 -> runRestore(uri, passphrase, replaceExisting = false)
                    else -> AlertDialog.Builder(this)
                        .setTitle(R.string.restore_conflict_title)
                        .setMessage(
                            resources.getQuantityString(R.plurals.restore_conflict_message, onPhone, onPhone)
                        )
                        // Keeping is the answer that loses nothing.
                        .setPositiveButton(R.string.restore_keep_phone) { _, _ ->
                            runRestore(uri, passphrase, replaceExisting = false)
                        }
                        .setNegativeButton(R.string.restore_replace) { _, _ ->
                            runRestore(uri, passphrase, replaceExisting = true)
                        }
                        .setNeutralButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }.start()
    }

    private fun runRestore(uri: Uri, passphrase: CharArray?, replaceExisting: Boolean) {
        Thread {
            var result = BackupManager.Restored(0, 0)
            val error = try {
                result = contentResolver.openInputStream(uri)?.use {
                    BackupManager.import(this, it, passphrase, replaceExisting)
                } ?: throw RuntimeException(getString(R.string.platform_error_read_failed))
                // Restored reminders have no alarm armed yet, and any already
                // due would otherwise wait for the next cold start and then be
                // dropped as past. Best effort: the restore itself succeeded.
                try {
                    Reminders.rescheduleAll(applicationContext)
                } catch (_: Exception) {
                }
                null
            } catch (e: BackupCrypto.WrongPassphraseException) {
                getString(R.string.restore_wrong_passphrase)
            } catch (e: Exception) {
                e.message ?: getString(R.string.platform_error_unknown)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error == null) {
                    // A partly-restored backup has to say so. Reporting only
                    // the successes is how a user comes to trust an archive
                    // that is missing pieces.
                    var message = if (result.skipped > 0) {
                        getString(R.string.restore_done_skipped, result.meetings, result.skipped)
                    } else {
                        getString(R.string.restore_done, result.meetings)
                    }
                    if (result.keptOnPhone > 0) {
                        message += "\n" + resources.getQuantityString(
                            R.plurals.restore_kept_phone, result.keptOnPhone, result.keptOnPhone
                        )
                    }
                    if (result.leftInTrash > 0) {
                        // Deleted here since the backup was made, and left
                        // in Recently deleted (BackupManager): too much for
                        // a toast, and the place to restore them from.
                        AlertDialog.Builder(this)
                            .setMessage(
                                message + "\n\n" + resources.getQuantityString(
                                    R.plurals.trash_restore_left, result.leftInTrash, result.leftInTrash
                                )
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .setNeutralButton(R.string.trash_storage_open_desc) { _, _ ->
                                startActivity(Intent(this, TrashActivity::class.java))
                            }
                            .show()
                    } else {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(
                        this, getString(R.string.restore_failed, error), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun updateWhisperSection() {
        whisperSection.visibility = if (whisperSwitch.isChecked) View.VISIBLE else View.GONE
        val key = settings.whisperModel
        val name = com.meetily.mobile.whisper.TranscriptionModels.displayName(key)
        whisperModelStatus.text = withPendingNote(
            ModelDownloadService.KIND_WHISPER,
            if (com.meetily.mobile.whisper.TranscriptionModels.isDownloaded(this, key)) {
                getString(R.string.model_status_downloaded, name)
            } else {
                getString(R.string.model_status_missing, name)
            }
        )
        // Translate and custom vocabulary are Whisper features: RecordingService
        // passes translate=false and vocabPrompt=null whenever a NeMo model is
        // loaded, and AudioFileImporter does the same. Both controls stayed
        // fully interactive with no hint of that, so a Parakeet user could set
        // a translate toggle and a page of vocabulary and have every word of
        // it ignored, silently, for the life of the install.
        findViewById<View>(R.id.whisperOnlyNote).visibility =
            if (com.meetily.mobile.whisper.TranscriptionModels.isNemo(key)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        updateDiarizeSection()
    }

    private fun updateDiarizeSection() {
        diarizeSection.visibility = if (diarizeSwitch.isChecked) View.VISIBLE else View.GONE
        val model = DiarizationModels.byKey(settings.diarizationModel)
        diarizeModelStatus.text = withPendingNote(
            ModelDownloadService.KIND_DIARIZE,
            if (DiarizationModels.isDownloaded(this, model)) {
                getString(R.string.model_status_downloaded, model.displayName)
            } else {
                getString(R.string.model_status_missing, model.displayName)
            }
        )
    }

    private fun showModelDialog() {
        // Whisper family first, then the NVIDIA NeMo models (Parakeet /
        // Nemotron) served through sherpa-onnx — one sheet, one selection.
        ModelPickerSheet.show(
            this,
            getString(R.string.manage_models),
            entriesProvider = {
                com.meetily.mobile.whisper.TranscriptionModels.offeredKeys(this).map { key ->
                    ModelPickerSheet.Entry(
                        key = key,
                        title = com.meetily.mobile.whisper.TranscriptionModels
                            .displayName(key),
                        meta = pendingMeta(
                            ModelDownloadService.KIND_WHISPER,
                            key,
                            com.meetily.mobile.whisper.TranscriptionModels.metaLine(this, key)
                        ),
                        downloaded = com.meetily.mobile.whisper.TranscriptionModels
                            .isDownloaded(this, key),
                        selected = settings.whisperModel == key,
                        licence = ModelTerms.pickerLine(this, key),
                        fit = DeviceProbe.verdict(
                            this,
                            com.meetily.mobile.whisper.TranscriptionModels.fitNeedOrNull(key)
                        )
                    )
                }
            },
            onPick = { key ->
                // A model that still has to download becomes the live
                // selection only when it is on disk (ModelDownloadService
                // applies it); until then recordings keep the working one.
                pickModel(ModelDownloadService.KIND_WHISPER, key)
            },
            onDelete = { entry ->
                if (!deleteBlockedByDownload(ModelDownloadService.KIND_WHISPER, entry.key)) {
                    val nemo = com.meetily.mobile.whisper.NemoModels.byKeyOrNull(entry.key)
                    if (nemo != null) {
                        com.meetily.mobile.whisper.NemoModels.delete(this, nemo)
                    } else {
                        WhisperModels.delete(this, WhisperModels.byKey(entry.key))
                    }
                    if (settings.whisperModel == entry.key) {
                        reselectAfterDelete(
                            ModelDownloadService.KIND_WHISPER,
                            entry.key,
                            com.meetily.mobile.whisper.TranscriptionModels.offeredKeys(this)
                                .filter {
                                    com.meetily.mobile.whisper.TranscriptionModels
                                        .isReady(this, it)
                                }
                        )
                    }
                    updateWhisperSection()
                }
            },
            onDeleteAll = {
                if (!deleteBlockedByDownload(ModelDownloadService.KIND_WHISPER)) {
                    WhisperModels.deleteAll(this)
                    com.meetily.mobile.whisper.NemoModels.deleteAll(this)
                    updateWhisperSection()
                }
            }
        )
    }

    // --- Voice profiles -----------------------------------------------------

    private fun updateVoicesStatus() {
        val count = VoiceProfileStore.load(this).size
        voicesStatus.text = getString(R.string.voices_status, count)
    }

    private fun showVoicesDialog() {
        val profiles = VoiceProfileStore.load(this)
        val labels = mutableListOf(getString(R.string.enroll_voice))
        for (profile in profiles) {
            labels.add(getString(R.string.voice_row, profile.name, profile.samples))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_voices)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    startEnrollment()
                } else {
                    confirmDeleteVoice(profiles[which - 1].name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure() // lists people's names
    }

    private fun confirmDeleteVoice(name: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.voice_delete_confirm, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                VoiceProfileStore.delete(this, name)
                updateVoicesStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure() // names the person
    }

    private fun startEnrollment() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.enroll_needs_mic, Toast.LENGTH_LONG).show()
            return
        }
        val dModel = DiarizationModels.byKey(settings.diarizationModel)
        if (!DiarizationModels.isDownloaded(this, dModel)) {
            Toast.makeText(this, R.string.enroll_needs_model, Toast.LENGTH_LONG).show()
            return
        }
        if (RecordingService.isRunning) {
            Toast.makeText(this, R.string.enroll_wait_recording, Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = getString(R.string.voice_name_hint)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.enroll_voice)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) recordEnrollment(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Voiceprint capture with an explicit start: the dialog shows the
     * read-aloud script first, the mic goes hot only when "Start voiceprint
     * recording" is tapped, and a countdown bar drains over the 15-second
     * window so it's obvious when recording is live and how long remains.
     */
    private fun recordEnrollment(name: String) {
        val dModel = DiarizationModels.byKey(settings.diarizationModel)
        val modelPath = DiarizationModels.fileFor(this, dModel).absolutePath
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val discarded = java.util.concurrent.atomic.AtomicBoolean(false)
        // A fixed read-aloud script beats improvised speech for voiceprints:
        // continuous, phonetically varied audio with no dead air.
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val countdown = com.google.android.material.progressindicator
            .LinearProgressIndicator(this).apply {
                max = 100
                progress = 100
                trackCornerRadius = (2 * density).toInt()
                visibility = View.GONE
            }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(
                android.widget.TextView(this@SettingsActivity).apply {
                    text = getString(R.string.enroll_read_hint)
                    setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            this, com.google.android.material.R.attr.colorOnSurfaceVariant
                        )
                    )
                    textSize = 13f
                }
            )
            addView(
                android.widget.TextView(this@SettingsActivity).apply {
                    text = getString(R.string.enroll_script)
                    setBackgroundResource(R.drawable.bg_field)
                    val inner = (14 * density).toInt()
                    setPadding(inner, inner, inner, inner)
                    setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            this, com.google.android.material.R.attr.colorOnSurface
                        )
                    )
                    textSize = 16f
                    setLineSpacing(4 * density, 1f)
                }.also { script ->
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = (10 * density).toInt()
                    script.layoutParams = lp
                }
            )
            addView(
                countdown,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (14 * density).toInt() }
            )
        }
        // Null click listeners: the real handlers are attached after show()
        // so the buttons don't auto-dismiss the dialog.
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.enroll_title, name))
            .setView(content)
            .setCancelable(false)
            .setPositiveButton(R.string.enroll_start, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        val actionButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            discarded.set(true)
            stopped.set(true)
            // Not yet recording: nothing will dismiss the dialog for us.
            if (!started.get()) dialog.dismiss()
        }
        val ticker = android.os.Handler(mainLooper)
        actionButton.setOnClickListener {
            if (!started.compareAndSet(false, true)) {
                stopped.set(true) // second tap = "Done"
                return@setOnClickListener
            }
            actionButton.text = getString(R.string.enroll_stop)
            countdown.visibility = View.VISIBLE
            val startedAt = android.os.SystemClock.elapsedRealtime()
            ticker.post(object : Runnable {
                override fun run() {
                    if (stopped.get() || isFinishing || isDestroyed) return
                    val elapsed =
                        android.os.SystemClock.elapsedRealtime() - startedAt
                    countdown.progress =
                        (100 - elapsed * 100 / ENROLL_MS).toInt().coerceIn(0, 100)
                    if (elapsed < ENROLL_MS) ticker.postDelayed(this, 150)
                }
            })
            startEnrollmentCapture(name, dModel.key, modelPath, stopped, discarded, dialog)
        }
    }

    private fun startEnrollmentCapture(
        name: String,
        modelKey: String,
        modelPath: String,
        stopped: java.util.concurrent.atomic.AtomicBoolean,
        discarded: java.util.concurrent.atomic.AtomicBoolean,
        dialog: AlertDialog
    ) {
        Thread {
            var saved = false
            try {
                val sampleRate = 16_000
                val minBuffer = android.media.AudioRecord.getMinBufferSize(
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_FLOAT
                )
                val record = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_FLOAT,
                    maxOf(minBuffer, sampleRate * 4)
                )
                if (record.state == android.media.AudioRecord.STATE_INITIALIZED) {
                    record.startRecording()
                    val audio = FloatArray((sampleRate * ENROLL_MS / 1000L).toInt())
                    val frame = FloatArray(sampleRate / 10)
                    var filled = 0
                    while (!stopped.get() && filled < audio.size) {
                        val n = record.read(
                            frame, 0, frame.size,
                            android.media.AudioRecord.READ_BLOCKING
                        )
                        if (n <= 0) continue
                        val count = minOf(n, audio.size - filled)
                        System.arraycopy(frame, 0, audio, filled, count)
                        filled += count
                    }
                    record.stop()
                    record.release()
                    // Cancelled recordings are discarded, never embedded.
                    if (!discarded.get() && filled >= sampleRate * 3) {
                        val embedder = com.meetily.mobile.whisper.SherpaEmbedder
                            .create(modelPath, modelKey)
                        if (embedder != null) {
                            try {
                                val captured = audio.copyOf(filled)
                                embedder.embed(captured)?.let { embedding ->
                                    // Banking the audio lets the profile
                                    // roll over to future speaker models.
                                    saved = VoiceProfileStore.addSample(
                                        this, name, embedding, modelKey, captured
                                    )
                                }
                            } finally {
                                embedder.release()
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }
                updateVoicesStatus()
                if (!discarded.get()) {
                    Toast.makeText(
                        this,
                        if (saved) getString(R.string.voice_saved, name)
                        else getString(R.string.enroll_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.apply {
            this.name = "voice-enroll"
            start()
        }
    }

    // --- Whisper capture tuning -------------------------------------------

    private fun updateCaptureStatus() {
        val tuning = when (settings.micSource) {
            CaptureTuning.SOURCE_FARFIELD -> getString(R.string.tuning_farfield)
            CaptureTuning.SOURCE_RAW -> getString(R.string.tuning_raw)
            else -> getString(R.string.tuning_default)
        }
        val deviceKey = settings.micDevice
        val device = when {
            deviceKey == CaptureTuning.DEVICE_AUTO -> getString(R.string.device_auto)
            else -> {
                val connected = CaptureTuning.findPreferred(this, deviceKey)
                if (connected != null) {
                    CaptureTuning.deviceLabel(connected)
                } else {
                    getString(
                        R.string.device_not_connected,
                        CaptureTuning.nameFromKey(deviceKey)
                    )
                }
            }
        }
        captureStatus.text = getString(R.string.capture_status, tuning, device)
    }

    private fun showMicTuningDialog() {
        val rawSupported = CaptureTuning.unprocessedSupported(this)
        val labels = arrayOf(
            getString(R.string.tuning_default),
            getString(R.string.tuning_farfield),
            if (rawSupported) getString(R.string.tuning_raw)
            else getString(R.string.tuning_raw_unsupported)
        )
        val checked = CaptureTuning.SOURCE_KEYS.indexOf(settings.micSource)
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.mic_tuning_button)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.micSource = CaptureTuning.SOURCE_KEYS[which]
                updateCaptureStatus()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMicDeviceDialog() {
        val devices = CaptureTuning.inputDevices(this)
        val labels = mutableListOf(getString(R.string.device_auto))
        val keys = mutableListOf(CaptureTuning.DEVICE_AUTO)
        for (device in devices) {
            labels.add(CaptureTuning.deviceLabel(device))
            keys.add(CaptureTuning.deviceKey(device))
        }
        // Same matching as the recorder uses, so a key saved by an older
        // version (no address) still highlights its row.
        val checked = com.meetily.mobile.whisper.MicDeviceKeys
            .indexOf(keys, settings.micDevice).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.mic_device_button)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                settings.micDevice = keys[which]
                updateCaptureStatus()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDiarizeModelDialog() {
        ModelPickerSheet.show(
            this,
            getString(R.string.diarize_manage),
            entriesProvider = {
                DiarizationModels.offered(this).map { model ->
                    ModelPickerSheet.Entry(
                        key = model.key,
                        title = model.displayName,
                        meta = pendingMeta(
                            ModelDownloadService.KIND_DIARIZE,
                            model.key,
                            getString(
                                R.string.model_card_meta,
                                model.approxSizeMb,
                                // The language is in each model's name.
                                getString(R.string.platform_model_speaker_id),
                                "sherpa-onnx"
                            )
                        ),
                        downloaded = DiarizationModels.isDownloaded(this, model),
                        selected = settings.diarizationModel == model.key,
                        licence = ModelTerms.pickerLine(this, model.key),
                        fit = DeviceProbe.verdict(this, model.fitNeed)
                    )
                }
            },
            onPick = { key ->
                val model = DiarizationModels.byKey(key)
                val profiles =
                    if (model.key == settings.diarizationModel) emptyList()
                    else VoiceProfileStore.load(this)
                if (profiles.isEmpty()) {
                    applyDiarizeModelPick(model)
                } else {
                    // Voiceprints don't transfer between speaker models on
                    // their own — tell the user what will convert (banked
                    // audio) and what needs re-enrolling before switching.
                    val convertible = profiles.count {
                        VoiceProfileStore.hasStoredAudio(this, it.name)
                    }
                    val manual = profiles.size - convertible
                    val body = buildString {
                        append(getString(R.string.voice_switch_intro))
                        if (convertible > 0) {
                            append("\n\n")
                            append(
                                getString(
                                    R.string.voice_switch_auto,
                                    convertible, profiles.size
                                )
                            )
                        }
                        if (manual > 0) {
                            append("\n\n")
                            append(getString(R.string.voice_switch_reenroll, manual))
                        }
                    }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.voice_switch_title)
                        .setMessage(body)
                        .setPositiveButton(R.string.voice_switch_go) { _, _ ->
                            applyDiarizeModelPick(model)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            },
            onDelete = { entry ->
                if (!deleteBlockedByDownload(ModelDownloadService.KIND_DIARIZE, entry.key)) {
                    DiarizationModels.delete(this, DiarizationModels.byKey(entry.key))
                    if (settings.diarizationModel == entry.key) {
                        reselectAfterDelete(
                            ModelDownloadService.KIND_DIARIZE,
                            entry.key,
                            DiarizationModels.ALL
                                .filter { DiarizationModels.isDownloaded(this, it) }
                                .map { it.key }
                        )
                    }
                    updateDiarizeSection()
                }
            },
            onDeleteAll = {
                if (!deleteBlockedByDownload(ModelDownloadService.KIND_DIARIZE)) {
                    DiarizationModels.deleteAll(this)
                    updateDiarizeSection()
                }
            }
        )
    }

    /** Merges a starter glossary into the vocabulary field (deduped). */
    private fun appendVocabPreset(preset: List<String>) {
        val input = findViewById<android.widget.EditText>(R.id.vocabInput)
        val merged = com.meetily.mobile.whisper.Vocab
            .withPreset(input.text.toString(), preset)
        if (merged != input.text.toString().trim()) {
            input.setText(merged)
            input.setSelection(input.text.length)
            Toast.makeText(this, R.string.vocab_preset_added, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Cancel under [kind]'s progress bar: the same as the notification's
     * Cancel (the download running and those waiting behind it stop; what
     * was downloaded is kept for next time), which a phone with Recap's
     * notifications turned off never shows. The section says the download
     * is stopping until the service reports it has.
     */
    private fun cancelModelDownload(kind: String) {
        val svc = downloadService
        if (svc != null) {
            svc.requestCancel()
        } else {
            // Not bound yet: the notification's own route.
            try {
                startService(
                    Intent(this, ModelDownloadService::class.java)
                        .setAction(ModelDownloadService.ACTION_CANCEL)
                )
            } catch (_: Exception) {
            }
        }
        if (kind == ModelDownloadService.KIND_ALIGN) {
            alignRow.onStopping()
            return
        }
        downloadCancel(kind).visibility = View.GONE
        A11y.setStatusText(downloadStatus(kind), getString(R.string.devicefit_download_stopping), true)
    }

    /**
     * Shows Cancel under [kind]'s progress while its download runs, or says
     * it is stopping once it was cancelled (a late progress replay, after
     * Settings came back, must not offer Cancel again).
     */
    private fun renderStopping(kind: String) {
        val stopping = ModelDownloadService.currentStopping
        downloadCancel(kind).visibility = if (stopping) View.GONE else View.VISIBLE
        if (stopping) downloadStatus(kind).text = getString(R.string.devicefit_download_stopping)
    }

    private fun downloadCancel(kind: String): View = when (kind) {
        ModelDownloadService.KIND_LLM -> llmCancel
        ModelDownloadService.KIND_DIARIZE -> diarizeCancel
        else -> whisperCancel
    }

    private fun downloadStatus(kind: String): TextView = when (kind) {
        ModelDownloadService.KIND_LLM -> llmStatus
        ModelDownloadService.KIND_DIARIZE -> diarizeModelStatus
        else -> whisperModelStatus
    }

    /**
     * Model pickers stay usable while downloads run (that's how queueing
     * works), so deletes need one guard: the file currently being written.
     */
    private fun deleteBlockedByDownload(kind: String, key: String? = null): Boolean {
        val busy = ModelDownloadService.isRunning &&
            ModelDownloadService.currentKind == kind &&
            (key == null || ModelDownloadService.currentKey == key)
        if (busy) {
            Toast.makeText(this, R.string.download_busy, Toast.LENGTH_SHORT).show()
        }
        return busy
    }

    private fun applyDiarizeModelPick(model: DiarizationModel) {
        pickModel(ModelDownloadService.KIND_DIARIZE, model.key)
    }

    /**
     * A pick from one of the model sheets. A model already on disk (or a
     * re-pick of the live selection) takes effect now and cancels any
     * earlier pick still waiting on a download. A model that still has to
     * download is only PARKED: the live selection keeps pointing at the
     * model that works, so a recording started during a 550 MB download
     * still transcribes, and ModelDownloadService switches over when the
     * download succeeds (see PendingModelSelection). A failed or cancelled
     * download leaves the old model selected. When the live selection is
     * not on disk either there is nothing working to protect, so the pick
     * applies at once.
     */
    private fun pickModel(kind: String, key: String, spaceChecked: Boolean = false) {
        val downloaded = PendingModelSelection.isDownloaded(this, kind, key)
        // Every download starts here, so this is where a model whose licence
        // passes use restrictions on to the user waits for their one-time
        // acceptance. Nothing is parked or selected until they agree; then
        // the pick runs again and goes through.
        if (!downloaded && !ModelTerms.mayDownload(this, key)) {
            val name = when (kind) {
                ModelDownloadService.KIND_LLM -> LocalLlmModels.byKey(key).displayName
                ModelDownloadService.KIND_DIARIZE -> DiarizationModels.byKey(key).displayName
                else -> com.meetily.mobile.whisper.TranscriptionModels.displayName(key)
            }
            ModelTerms.confirm(this, key, name) {
                if (!isFinishing && !isDestroyed) pickModel(kind, key)
            }
            return
        }
        // And where free space is checked, first download or re-download
        // alike, before anything is parked: short of room, the user can free
        // some, go ahead anyway, or cancel and keep what they had.
        if (!downloaded && !spaceChecked) {
            ModelSpaceCheck.confirm(this, ModelSpaceCheck.sizesFor(kind, key)) {
                if (!isFinishing && !isDestroyed) pickModel(kind, key, spaceChecked = true)
            }
            return
        }
        if (PendingModelSelection.pick(this, kind, key)) {
            // Progress (and the "switches when ready" wording) comes from
            // the download callbacks from here on.
            startModelDownload(kind, key)
        } else {
            refreshModelStatus(kind)
        }
    }

    private fun refreshModelStatus(kind: String) {
        when (kind) {
            ModelDownloadService.KIND_LLM -> updateLocalLlmStatus()
            ModelDownloadService.KIND_DIARIZE -> updateDiarizeSection()
            else -> updateWhisperSection()
        }
    }

    /**
     * After deleting the live selection, fall back to another downloaded
     * model of the same kind if there is one. With none left the setting is
     * left alone; its status line then reads "not downloaded yet", and every
     * consumer checks the file before treating it as ready.
     */
    private fun reselectAfterDelete(kind: String, deletedKey: String, downloaded: List<String>) {
        val next = PendingModelSelection.replacementAfterDelete(deletedKey, downloaded) ?: return
        PendingModelSelection.select(this, kind, next)
        val name = when (kind) {
            ModelDownloadService.KIND_LLM -> LocalLlmModels.byKey(next).displayName
            ModelDownloadService.KIND_DIARIZE -> DiarizationModels.byKey(next).displayName
            else -> com.meetily.mobile.whisper.TranscriptionModels.displayName(next)
        }
        Toast.makeText(this, getString(R.string.model_reselected, name), Toast.LENGTH_SHORT)
            .show()
    }

    /** Progress line; says so when this download will switch the model over. */
    private fun downloadingText(kind: String, key: String, name: String, percent: Int): String =
        if (PendingModelSelection.get(this, kind) == key) {
            getString(R.string.model_downloading_pending, name, percent)
        } else {
            getString(R.string.model_downloading, name, percent)
        }

    /** Appends "switching to X when its download finishes" to a status line. */
    private fun withPendingNote(kind: String, status: String): String {
        val pending = PendingModelSelection.get(this, kind) ?: return status
        if (pending == PendingModelSelection.selected(this, kind)) return status
        val name = when (kind) {
            ModelDownloadService.KIND_LLM -> LocalLlmModels.byKey(pending).displayName
            ModelDownloadService.KIND_DIARIZE -> DiarizationModels.byKey(pending).displayName
            else -> com.meetily.mobile.whisper.TranscriptionModels.displayName(pending)
        }
        return status + "\n" + getString(R.string.model_pending_note, name)
    }

    /** Marks the picker card of a model that is parked behind its download. */
    private fun pendingMeta(kind: String, key: String, meta: String): String =
        if (PendingModelSelection.get(this, kind) == key) {
            getString(R.string.model_meta_pending, meta)
        } else {
            meta
        }

    /**
     * Hands the download to ModelDownloadService (foreground + wakelock), so
     * it keeps going when this screen is left or the phone sleeps. Progress
     * comes back through the bound observer. If a download is already
     * running, the service queues this one to run right after it — so a
     * whisper model, a speaker model, and an LLM can be tapped back-to-back
     * and download consecutively.
     */
    private fun startModelDownload(kind: String, key: String) {
        val wasRunning = ModelDownloadService.isRunning
        // A second tap on the model already downloading: the service ignores
        // it, so it is not "queued"; show where the download is instead.
        val alreadyDownloading = ModelDownloadService.isDownloading(kind, key)
        if (!ModelDownloadService.start(this, kind, key)) {
            // Android refused the service (see ModelDownloadService.start):
            // nothing will download, so nothing stays parked behind it.
            PendingModelSelection.clearIf(this, kind, key)
            refreshModelStatus(kind)
            Toast.makeText(this, R.string.devicefit_download_not_started, Toast.LENGTH_LONG).show()
            return
        }
        // Immediate visual feedback; service callbacks take over from here.
        if (alreadyDownloading) {
            lastRenderedPercent = -1
            downloadObserver.onDownloadProgress(kind, key, ModelDownloadService.currentPercent)
        } else if (wasRunning) {
            downloadObserver.onDownloadQueued(kind, key)
            Toast.makeText(this, R.string.download_queued_toast, Toast.LENGTH_SHORT).show()
        } else {
            downloadObserver.onDownloadProgress(kind, key, 0)
        }
    }

    companion object {
        /** Voiceprint capture window; the countdown bar drains over this. */
        private const val ENROLL_MS = 15_000L

        /** Saved-state key for [systemEngineConfirmed]. */
        private const val KEY_SYSTEM_ENGINE_OK = "sys_engine_ok"

        /** Saved-state key for [pendingEncryptedBackup]. */
        private const val KEY_PENDING_BACKUP = "pending_encrypted_backup"
    }
}
