package com.meetily.mobile

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.ImageView
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

    private val exportBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { writeBackup(it) }
        }
    private val importBackup =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { readBackup(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)
        WhisperModels.cleanPartials(this)
        DiarizationModels.cleanPartials(this)

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
        findViewById<MaterialSwitch>(R.id.saveAudioSwitch).isChecked = settings.saveAudio
        calendarSwitch.isChecked = settings.calendarPrefill
        urlInput.setText(settings.llmBaseUrl)
        keyInput.setText(settings.llmApiKey)
        modelInput.setText(settings.llmModel)

        updateLlmSectionVisibility()
        updateWhisperSection()
        buildAccentRow()
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

        findViewById<View>(R.id.exportBackupButton).setOnClickListener {
            try {
                exportBackup.launch(getString(R.string.backup_file_name))
            } catch (e: Exception) {
                Toast.makeText(
                    this, getString(R.string.backup_failed, e.message ?: "no file picker"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        findViewById<View>(R.id.importBackupButton).setOnClickListener {
            try {
                importBackup.launch("application/zip")
            } catch (e: Exception) {
                Toast.makeText(
                    this, getString(R.string.restore_failed, e.message ?: "no file picker"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        findViewById<View>(R.id.privacyLink).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        findViewById<View>(R.id.saveButton).setOnClickListener {
            persistAll()
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
        settings.diarizationEnabled = diarizeSwitch.isChecked
        settings.saveAudio = findViewById<MaterialSwitch>(R.id.saveAudioSwitch).isChecked
        settings.llmBaseUrl = urlInput.text.toString().trim()
        settings.llmApiKey = keyInput.text.toString().trim()
        settings.llmModel = modelInput.text.toString().trim()
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
            if (downloading) {
                // A night-mode change recreates this screen, which would
                // orphan the running model download — same guard as accents.
                Toast.makeText(this, R.string.theme_wait_download, Toast.LENGTH_SHORT).show()
                suppressThemeListener = true
                group.check(
                    when (settings.themeMode) {
                        "light" -> R.id.themeLight
                        "dark" -> R.id.themeDark
                        else -> R.id.themeSystem
                    }
                )
                suppressThemeListener = false
                return@addOnButtonCheckedListener
            }
            settings.themeMode = mode
            ThemeManager.applyNightMode(mode)
        }
    }

    private fun buildAccentRow() {
        val row = findViewById<LinearLayout>(R.id.accentRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (40 * density).toInt()
        val margin = (8 * density).toInt()
        val strokeWidth = (2.5f * density).toInt()
        val ringColor = TypedValue().let {
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, it, true)
            it.data
        }
        val rippleRes = TypedValue().let {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            it.resourceId
        }
        for (accent in ThemeManager.ACCENTS) {
            val selected = accent.key == settings.accentColor
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@SettingsActivity, accent.swatchColorRes))
                if (selected) setStroke(strokeWidth, ringColor)
            }
            val swatch = ImageView(this).apply {
                background = circle
                if (rippleRes != 0) {
                    foreground = ContextCompat.getDrawable(this@SettingsActivity, rippleRes)
                }
                if (selected) {
                    setImageResource(R.drawable.ic_check)
                    val pad = (9 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = margin
                }
                contentDescription = accent.key
                setOnClickListener {
                    if (downloading) {
                        // recreate() would orphan the running model download.
                        Toast.makeText(
                            this@SettingsActivity, R.string.accent_wait_download,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    if (accent.key != settings.accentColor) {
                        // Only the accent is persisted; pending toggles/text
                        // survive recreate() via view state restoration.
                        settings.accentColor = accent.key
                        recreate()
                    }
                }
            }
            row.addView(swatch)
        }
    }

    private fun updateLlmSectionVisibility() {
        llmSection.visibility = if (useLlmSwitch.isChecked) View.VISIBLE else View.GONE
    }

    private fun writeBackup(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { BackupManager.export(this, it) }
                ?: throw RuntimeException("could not open destination")
            Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.backup_failed, e.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun readBackup(uri: Uri) {
        try {
            val count = contentResolver.openInputStream(uri)?.use {
                BackupManager.import(this, it)
            } ?: throw RuntimeException("could not open file")
            Toast.makeText(
                this, getString(R.string.restore_done, count), Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.restore_failed, e.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
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
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.enroll_title, name))
            .setMessage(R.string.enroll_instructions)
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
        val model = DiarizationModels.byKey(modelKey)
        downloading = true
        manageModelsButton.isEnabled = false
        manageDiarizeButton.isEnabled = false
        diarizeProgress.visibility = View.VISIBLE
        diarizeProgress.isIndeterminate = true
        diarizeModelStatus.text = getString(R.string.model_downloading, model.displayName, 0)

        Thread {
            try {
                DiarizationModels.download(this, model) { percent ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        diarizeProgress.isIndeterminate = false
                        diarizeProgress.progress = percent
                        diarizeModelStatus.text =
                            getString(R.string.model_downloading, model.displayName, percent)
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    downloading = false
                    manageModelsButton.isEnabled = true
                    manageDiarizeButton.isEnabled = true
                    diarizeProgress.visibility = View.GONE
                    updateDiarizeSection()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    downloading = false
                    manageModelsButton.isEnabled = true
                    manageDiarizeButton.isEnabled = true
                    diarizeProgress.visibility = View.GONE
                    updateDiarizeSection()
                    Toast.makeText(
                        this,
                        getString(R.string.model_download_failed, e.message ?: "network error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun startDownload(modelKey: String) {
        val model = WhisperModels.byKey(modelKey)
        downloading = true
        manageModelsButton.isEnabled = false
        manageDiarizeButton.isEnabled = false
        modelProgress.visibility = View.VISIBLE
        modelProgress.isIndeterminate = true
        whisperModelStatus.text = getString(R.string.model_downloading, model.displayName, 0)

        Thread {
            try {
                WhisperModels.download(this, model) { percent ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        modelProgress.isIndeterminate = false
                        modelProgress.progress = percent
                        whisperModelStatus.text =
                            getString(R.string.model_downloading, model.displayName, percent)
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    downloading = false
                    manageModelsButton.isEnabled = true
                    manageDiarizeButton.isEnabled = true
                    modelProgress.visibility = View.GONE
                    updateWhisperSection()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    downloading = false
                    manageModelsButton.isEnabled = true
                    manageDiarizeButton.isEnabled = true
                    modelProgress.visibility = View.GONE
                    updateWhisperSection()
                    Toast.makeText(
                        this,
                        getString(R.string.model_download_failed, e.message ?: "network error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}
