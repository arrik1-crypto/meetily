package com.meetily.mobile

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.meetily.mobile.data.AppSettings

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    private lateinit var useLlmSwitch: MaterialSwitch
    private lateinit var offlineSwitch: MaterialSwitch
    private lateinit var muteSoundsSwitch: MaterialSwitch
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var llmSection: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        useLlmSwitch = findViewById(R.id.useLlmSwitch)
        offlineSwitch = findViewById(R.id.offlineSwitch)
        muteSoundsSwitch = findViewById(R.id.muteSoundsSwitch)
        urlInput = findViewById(R.id.llmUrlInput)
        keyInput = findViewById(R.id.llmKeyInput)
        modelInput = findViewById(R.id.llmModelInput)
        llmSection = findViewById(R.id.llmSection)

        useLlmSwitch.isChecked = settings.useLlm
        offlineSwitch.isChecked = settings.preferOfflineRecognition
        muteSoundsSwitch.isChecked = settings.muteRecognizerSounds
        urlInput.setText(settings.llmBaseUrl)
        keyInput.setText(settings.llmApiKey)
        modelInput.setText(settings.llmModel)

        updateLlmSectionVisibility()
        useLlmSwitch.setOnCheckedChangeListener { _, _ -> updateLlmSectionVisibility() }

        findViewById<View>(R.id.saveButton).setOnClickListener {
            settings.useLlm = useLlmSwitch.isChecked
            settings.preferOfflineRecognition = offlineSwitch.isChecked
            settings.muteRecognizerSounds = muteSoundsSwitch.isChecked
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
}
