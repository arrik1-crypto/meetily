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
import com.meetily.mobile.llm.LocalLlm
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.security.AppLock
import com.meetily.mobile.reminders.Reminders
import com.meetily.mobile.security.BackupCrypto
import com.meetily.mobile.whisper.CaptureTuning
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
    private var pendingPassphrase: CharArray? = null

    private val exportBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { writeBackup(it, null) }
        }
    private val exportBackupEncrypted =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val pass = pendingPassphrase
            pendingPassphrase = null
            if (uri != null && pass != null) writeBackup(uri, pass)
        }
    private val importBackup =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { readBackup(it) }
        }

    // --- Model downloads (owned by ModelDownloadService) --------------------

    private var downloadService: ModelDownloadService? = null
    private var downloadBound = false

    private val downloadObserver = object : ModelDownloadService.Observer {
        override fun onDownloadProgress(kind: String, key: String, percent: Int) {
            if (isFinishing || isDestroyed) return
            downloading = true
            manageModelsButton.isEnabled = false
            manageDiarizeButton.isEnabled = false
            if (kind == ModelDownloadService.KIND_LLM) {
                val model = LocalLlmModels.byKey(key)
                val bar = findViewById<LinearProgressIndicator>(R.id.localLlmProgress)
                bar.visibility = View.VISIBLE
                bar.isIndeterminate = percent == 0
                bar.progress = percent
                findViewById<TextView>(R.id.localLlmStatus).text =
                    getString(R.string.model_downloading, model.displayName, percent)
            } else if (kind == ModelDownloadService.KIND_DIARIZE) {
                val model = DiarizationModels.byKey(key)
                diarizeProgress.visibility = View.VISIBLE
                diarizeProgress.isIndeterminate = percent == 0
                diarizeProgress.progress = percent
                diarizeModelStatus.text =
                    getString(R.string.model_downloading, model.displayName, percent)
            } else {
                val model = WhisperModels.byKey(key)
                modelProgress.visibility = View.VISIBLE
                modelProgress.isIndeterminate = percent == 0
                modelProgress.progress = percent
                whisperModelStatus.text =
                    getString(R.string.model_downloading, model.displayName, percent)
            }
        }

        override fun onDownloadDone(
            kind: String,
            key: String,
            cancelled: Boolean,
            error: String?
        ) {
            if (isFinishing || isDestroyed) return
            downloading = false
            manageModelsButton.isEnabled = true
            manageDiarizeButton.isEnabled = true
            modelProgress.visibility = View.GONE
            diarizeProgress.visibility = View.GONE
            findViewById<LinearProgressIndicator>(R.id.localLlmProgress).visibility =
                View.GONE
            updateWhisperSection()
            updateLocalLlmStatus()
            if (error != null) {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.model_download_failed, error),
                    Toast.LENGTH_LONG
                ).show()
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
        if (!ModelDownloadService.isRunning && downloading) {
            // The download finished while we were away.
            downloading = false
            manageModelsButton.isEnabled = true
            manageDiarizeButton.isEnabled = true
            modelProgress.visibility = View.GONE
            diarizeProgress.visibility = View.GONE
            findViewById<LinearProgressIndicator>(R.id.localLlmProgress).visibility =
                View.GONE
            updateWhisperSection()
            updateLocalLlmStatus()
        }
    }

    override fun onStop() {
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

        settings = AppSettings(this)
        if (!ModelDownloadService.isRunning) {
            // Never sweep .part files while the download service is mid-write.
            WhisperModels.cleanPartials(this)
            DiarizationModels.cleanPartials(this)
            LocalLlmModels.cleanPartials(this)
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
        diarizeSwitch.isChecked = settings.diarizationEnabled
        findViewById<MaterialSwitch>(R.id.translateSwitch).isChecked = settings.whisperTranslate
        findViewById<android.widget.EditText>(R.id.vocabInput).setText(settings.customVocab)
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
            if (checked && !WhisperModels.isRuntimeAvailable()) {
                Toast.makeText(this, R.string.whisper_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        manageModelsButton.setOnClickListener { showModelDialog() }
        diarizeSwitch.setOnCheckedChangeListener { _, _ -> updateDiarizeSection() }
        manageDiarizeButton.setOnClickListener { showDiarizeModelDialog() }
        captureStatus = findViewById(R.id.captureStatus)
        updateCaptureStatus()
        findViewById<View>(R.id.micTuningButton).setOnClickListener { showMicTuningDialog() }
        findViewById<View>(R.id.micDeviceButton).setOnClickListener { showMicDeviceDialog() }
        voicesStatus = findViewById(R.id.voicesStatus)
        updateVoicesStatus()
        findViewById<View>(R.id.manageVoicesButton).setOnClickListener { showVoicesDialog() }

        findViewById<View>(R.id.exportBackupButton).setOnClickListener { showExportChoice() }
        findViewById<View>(R.id.importBackupButton).setOnClickListener {
            try {
                // "*/*" so encrypted .recapbak backups show up alongside zips.
                importBackup.launch("*/*")
            } catch (e: Exception) {
                Toast.makeText(
                    this, getString(R.string.restore_failed, e.message ?: "no file picker"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        setUpSecuritySection()
        findViewById<View>(R.id.privacyLink).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        findViewById<View>(R.id.saveButton).setOnClickListener {
            persistAll()
            // Nudge/reminder alarms depend on the just-saved settings.
            Thread { Reminders.rescheduleAll(applicationContext) }.start()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
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
        settings.customVocab =
            findViewById<android.widget.EditText>(R.id.vocabInput).text.toString().trim()
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

    private fun updateLocalLlmStatus() {
        val model = LocalLlmModels.byKey(settings.localLlmModel)
        findViewById<TextView>(R.id.localLlmStatus).text =
            if (LocalLlmModels.isDownloaded(this, model)) {
                getString(R.string.model_status_downloaded, model.displayName)
            } else {
                getString(R.string.model_status_missing, model.displayName)
            }
    }

    private fun showLlmModelDialog() {
        if (ModelDownloadService.isRunning) {
            Toast.makeText(this, R.string.download_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = LocalLlmModels.ALL.map { model ->
            val state = if (LocalLlmModels.isDownloaded(this, model)) {
                getString(R.string.model_downloaded_label)
            } else {
                getString(R.string.model_tap_download)
            }
            "${model.displayName} · ${model.sizeMb} MB · $state"
        } + getString(R.string.model_delete_all)

        AlertDialog.Builder(this)
            .setTitle(R.string.manage_llm_models)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= LocalLlmModels.ALL.size) {
                    LocalLlm.release()
                    LocalLlmModels.deleteAll(this)
                    updateLocalLlmStatus()
                    return@setItems
                }
                val model = LocalLlmModels.ALL[which]
                if (settings.localLlmModel != model.key) {
                    settings.localLlmModel = model.key
                    // Next generation loads the newly selected model.
                    LocalLlm.release()
                }
                if (LocalLlmModels.isDownloaded(this, model)) {
                    updateLocalLlmStatus()
                } else {
                    startModelDownload(ModelDownloadService.KIND_LLM, model.key)
                    updateLocalLlmStatus()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        // reflect the toggle on this screen immediately.
        secureScreenSwitch.setOnCheckedChangeListener { _, checked ->
            settings.secureScreen = checked
            AppLock.applySecureFlag(this)
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
            .setPositiveButton(R.string.backup_encrypt_yes) { _, _ -> promptExportPassphrase() }
            .setNegativeButton(R.string.backup_plain) { _, _ ->
                try {
                    exportBackup.launch(getString(R.string.backup_file_name))
                } catch (e: Exception) {
                    Toast.makeText(
                        this, getString(R.string.backup_failed, e.message ?: "no file picker"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun promptExportPassphrase() {
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
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_encrypt_yes)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val p = pass.text.toString()
                when {
                    p.length < 6 -> Toast.makeText(
                        this, R.string.backup_passphrase_short, Toast.LENGTH_LONG
                    ).show()
                    p != repeat.text.toString() -> Toast.makeText(
                        this, R.string.backup_passphrase_mismatch, Toast.LENGTH_LONG
                    ).show()
                    else -> {
                        pendingPassphrase = p.toCharArray()
                        try {
                            exportBackupEncrypted.launch(getString(R.string.backup_enc_file_name))
                        } catch (e: Exception) {
                            pendingPassphrase = null
                            Toast.makeText(
                                this,
                                getString(R.string.backup_failed, e.message ?: "no file picker"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun writeBackup(uri: Uri, passphrase: CharArray?) {
        Thread {
            val error = try {
                contentResolver.openOutputStream(uri)?.use {
                    if (passphrase != null) {
                        BackupManager.exportEncrypted(this, it, passphrase)
                    } else {
                        BackupManager.export(this, it)
                    }
                } ?: throw RuntimeException("could not open destination")
                null
            } catch (e: Exception) {
                e.message ?: "unknown error"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error == null) {
                    Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this, getString(R.string.backup_failed, error), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun readBackup(uri: Uri) {
        val encrypted = try {
            contentResolver.openInputStream(uri)?.use { BackupManager.sniffEncrypted(it) }
                ?: false
        } catch (e: Exception) {
            false
        }
        if (encrypted) promptRestorePassphrase(uri) else doRestore(uri, null)
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

    private fun doRestore(uri: Uri, passphrase: CharArray?) {
        Thread {
            var count = 0
            val error = try {
                count = contentResolver.openInputStream(uri)?.use {
                    BackupManager.import(this, it, passphrase)
                } ?: throw RuntimeException("could not open file")
                null
            } catch (e: BackupCrypto.WrongPassphraseException) {
                getString(R.string.restore_wrong_passphrase)
            } catch (e: Exception) {
                e.message ?: "unknown error"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error == null) {
                    Toast.makeText(
                        this, getString(R.string.restore_done, count), Toast.LENGTH_LONG
                    ).show()
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
        val model = WhisperModels.byKey(settings.whisperModel)
        whisperModelStatus.text = if (WhisperModels.isDownloaded(this, model)) {
            getString(R.string.model_status_downloaded, model.displayName)
        } else {
            getString(R.string.model_status_missing, model.displayName)
        }
        updateDiarizeSection()
    }

    private fun updateDiarizeSection() {
        diarizeSection.visibility = if (diarizeSwitch.isChecked) View.VISIBLE else View.GONE
        val model = DiarizationModels.byKey(settings.diarizationModel)
        diarizeModelStatus.text = if (DiarizationModels.isDownloaded(this, model)) {
            getString(R.string.model_status_downloaded, model.displayName)
        } else {
            getString(R.string.model_status_missing, model.displayName)
        }
    }

    private fun showModelDialog() {
        if (downloading) return
        val labels = WhisperModels.ALL.map { model ->
            val state = if (WhisperModels.isDownloaded(this, model)) {
                getString(R.string.model_downloaded_label)
            } else {
                getString(R.string.model_tap_download)
            }
            "${model.displayName} · ${model.sizeMb} MB · $state"
        } + getString(R.string.model_delete_all)

        AlertDialog.Builder(this)
            .setTitle(R.string.manage_models)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= WhisperModels.ALL.size) {
                    WhisperModels.deleteAll(this)
                    updateWhisperSection()
                    return@setItems
                }
                val model = WhisperModels.ALL[which]
                settings.whisperModel = model.key
                if (WhisperModels.isDownloaded(this, model)) {
                    updateWhisperSection()
                } else {
                    startDownload(model.key)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            .show()
    }

    private fun confirmDeleteVoice(name: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.voice_delete_confirm, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                VoiceProfileStore.delete(this, name)
                updateVoicesStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    /** Records up to 15 s of speech and saves it as [name]'s voiceprint. */
    private fun recordEnrollment(name: String) {
        val dModel = DiarizationModels.byKey(settings.diarizationModel)
        val modelPath = DiarizationModels.fileFor(this, dModel).absolutePath
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        // A fixed read-aloud script beats improvised speech for voiceprints:
        // continuous, phonetically varied audio with no dead air.
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
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
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.enroll_title, name))
            .setView(content)
            .setCancelable(false)
            .setPositiveButton(R.string.enroll_stop) { _, _ -> stopped.set(true) }
            .show()

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
                    val audio = FloatArray(sampleRate * 15)
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
                    if (filled >= sampleRate * 3) { // at least 3 s of speech
                        val embedder = com.meetily.mobile.whisper.SherpaEmbedder
                            .create(modelPath)
                        if (embedder != null) {
                            try {
                                embedder.embed(audio.copyOf(filled))?.let { embedding ->
                                    saved = VoiceProfileStore
                                        .addSample(this, name, embedding)
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
                Toast.makeText(
                    this,
                    if (saved) getString(R.string.voice_saved, name)
                    else getString(R.string.enroll_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
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
        val checked = keys.indexOf(settings.micDevice).coerceAtLeast(0)
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
        if (downloading) return
        val labels = DiarizationModels.ALL.map { model ->
            val state = if (DiarizationModels.isDownloaded(this, model)) {
                getString(R.string.model_downloaded_label)
            } else {
                getString(R.string.model_tap_download)
            }
            "${model.displayName} · ${model.approxSizeMb} MB · $state"
        } + getString(R.string.model_delete_all)

        AlertDialog.Builder(this)
            .setTitle(R.string.diarize_manage)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= DiarizationModels.ALL.size) {
                    DiarizationModels.deleteAll(this)
                    updateDiarizeSection()
                    return@setItems
                }
                val model = DiarizationModels.ALL[which]
                settings.diarizationModel = model.key
                if (DiarizationModels.isDownloaded(this, model)) {
                    updateDiarizeSection()
                } else {
                    startDiarizeDownload(model.key)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startDiarizeDownload(modelKey: String) {
        startModelDownload(ModelDownloadService.KIND_DIARIZE, modelKey)
    }

    private fun startDownload(modelKey: String) {
        startModelDownload(ModelDownloadService.KIND_WHISPER, modelKey)
    }

    /**
     * Hands the download to ModelDownloadService (foreground + wakelock), so
     * it keeps going when this screen is left or the phone sleeps. Progress
     * comes back through the bound observer.
     */
    private fun startModelDownload(kind: String, key: String) {
        if (ModelDownloadService.isRunning) {
            Toast.makeText(this, R.string.download_busy, Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, ModelDownloadService::class.java)
                .setAction(ModelDownloadService.ACTION_START)
                .putExtra(ModelDownloadService.EXTRA_KIND, kind)
                .putExtra(ModelDownloadService.EXTRA_KEY, key)
        )
        // Immediate visual feedback; service callbacks take over from here.
        downloadObserver.onDownloadProgress(kind, key, 0)
    }
}
