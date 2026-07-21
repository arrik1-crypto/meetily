package com.meetily.mobile

import android.content.Intent
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.BackupManager
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
        urlInput = findViewById(R.id.llmUrlInput)
        keyInput = findViewById(R.id.llmKeyInput)
        modelInput = findViewById(R.id.llmModelInput)
        llmSection = findViewById(R.id.llmSection)

        useLlmSwitch.isChecked = settings.useLlm
        offlineSwitch.isChecked = settings.preferOfflineRecognition
        muteSoundsSwitch.isChecked = settings.muteRecognizerSounds
        whisperSwitch.isChecked = settings.transcriptionEngine == "whisper"
        calendarSwitch.isChecked = settings.calendarPrefill
        urlInput.setText(settings.llmBaseUrl)
        keyInput.setText(settings.llmApiKey)
        modelInput.setText(settings.llmModel)

        updateLlmSectionVisibility()
        updateWhisperSection()
        buildAccentRow()
        useLlmSwitch.setOnCheckedChangeListener { _, _ -> updateLlmSectionVisibility() }
        whisperSwitch.setOnCheckedChangeListener { _, checked ->
            updateWhisperSection()
            if (checked && !WhisperModels.isRuntimeAvailable()) {
                Toast.makeText(this, R.string.whisper_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        manageModelsButton.setOnClickListener { showModelDialog() }

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
        settings.llmBaseUrl = urlInput.text.toString().trim()
        settings.llmApiKey = keyInput.text.toString().trim()
        settings.llmModel = modelInput.text.toString().trim()
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

    private fun startDownload(modelKey: String) {
        val model = WhisperModels.byKey(modelKey)
        downloading = true
        manageModelsButton.isEnabled = false
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
                    modelProgress.visibility = View.GONE
                    updateWhisperSection()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    downloading = false
                    manageModelsButton.isEnabled = true
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
