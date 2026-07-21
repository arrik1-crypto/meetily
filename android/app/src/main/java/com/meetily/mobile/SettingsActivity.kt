package com.meetily.mobile

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.whisper.WhisperModels

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    private lateinit var useLlmSwitch: MaterialSwitch
    private lateinit var offlineSwitch: MaterialSwitch
    private lateinit var muteSoundsSwitch: MaterialSwitch
    private lateinit var whisperSwitch: MaterialSwitch
    private lateinit var whisperSection: View
    private lateinit var whisperModelStatus: TextView
    private lateinit var modelProgress: LinearProgressIndicator
    private lateinit var manageModelsButton: MaterialButton
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var llmSection: View

    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        findViewById<MaterialToolbar>(R.id.settingsToolbar).setNavigationOnClickListener {
            finish()
        }

        useLlmSwitch = findViewById(R.id.useLlmSwitch)
        offlineSwitch = findViewById(R.id.offlineSwitch)
        muteSoundsSwitch = findViewById(R.id.muteSoundsSwitch)
        whisperSwitch = findViewById(R.id.whisperSwitch)
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
        urlInput.setText(settings.llmBaseUrl)
        keyInput.setText(settings.llmApiKey)
        modelInput.setText(settings.llmModel)

        updateLlmSectionVisibility()
        updateWhisperSection()
        useLlmSwitch.setOnCheckedChangeListener { _, _ -> updateLlmSectionVisibility() }
        whisperSwitch.setOnCheckedChangeListener { _, checked ->
            updateWhisperSection()
            if (checked && !WhisperModels.isRuntimeAvailable()) {
                Toast.makeText(this, R.string.whisper_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        manageModelsButton.setOnClickListener { showModelDialog() }

        findViewById<View>(R.id.saveButton).setOnClickListener {
            settings.useLlm = useLlmSwitch.isChecked
            settings.preferOfflineRecognition = offlineSwitch.isChecked
            settings.muteRecognizerSounds = muteSoundsSwitch.isChecked
            settings.transcriptionEngine =
                if (whisperSwitch.isChecked) "whisper" else "system"
            settings.llmBaseUrl = urlInput.text.toString().trim()
            settings.llmApiKey = keyInput.text.toString().trim()
            settings.llmModel = modelInput.text.toString().trim()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateLlmSectionVisibility() {
        llmSection.visibility = if (useLlmSwitch.isChecked) View.VISIBLE else View.GONE
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
