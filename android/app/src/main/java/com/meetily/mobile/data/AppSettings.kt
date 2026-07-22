package com.meetily.mobile.data

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meetily_settings", Context.MODE_PRIVATE)

    var useLlm: Boolean
        get() = prefs.getBoolean("use_llm", false)
        set(value) = prefs.edit().putBoolean("use_llm", value).apply()

    var llmBaseUrl: String
        get() = prefs.getString("llm_base_url", "http://192.168.1.100:11434/v1") ?: ""
        set(value) = prefs.edit().putString("llm_base_url", value).apply()

    var llmApiKey: String
        get() = prefs.getString("llm_api_key", "") ?: ""
        set(value) = prefs.edit().putString("llm_api_key", value).apply()

    var llmModel: String
        get() = prefs.getString("llm_model", "llama3.1") ?: ""
        set(value) = prefs.edit().putString("llm_model", value).apply()

    var preferOfflineRecognition: Boolean
        get() = prefs.getBoolean("prefer_offline_recognition", false)
        set(value) = prefs.edit().putBoolean("prefer_offline_recognition", value).apply()

    var muteRecognizerSounds: Boolean
        get() = prefs.getBoolean("mute_recognizer_sounds", true)
        set(value) = prefs.edit().putBoolean("mute_recognizer_sounds", value).apply()

    var summaryTemplate: String
        get() = prefs.getString("summary_template", "general") ?: "general"
        set(value) = prefs.edit().putString("summary_template", value).apply()

    /** "system" (Android speech recognizer) or "whisper" (on-device whisper.cpp). */
    var transcriptionEngine: String
        get() = prefs.getString("transcription_engine", "system") ?: "system"
        set(value) = prefs.edit().putString("transcription_engine", value).apply()

    var whisperModel: String
        get() = prefs.getString("whisper_model", "base.en") ?: "base.en"
        set(value) = prefs.edit().putString("whisper_model", value).apply()

    /** Acoustic speaker detection (Whisper engine only, experimental). */
    var diarizationEnabled: Boolean
        get() = prefs.getBoolean("diarization_enabled", false)
        set(value) = prefs.edit().putBoolean("diarization_enabled", value).apply()

    var diarizationModel: String
        get() = prefs.getString("diarization_model", "resnet34-en") ?: "resnet34-en"
        set(value) = prefs.edit().putString("diarization_model", value).apply()

    /** Whisper capture: "recognition" (default), "camcorder", or "unprocessed". */
    var micSource: String
        get() = prefs.getString("mic_source", "recognition") ?: "recognition"
        set(value) = prefs.edit().putString("mic_source", value).apply()

    /** Whisper capture input device key ("auto" = system routing). */
    var micDevice: String
        get() = prefs.getString("mic_device", "auto") ?: "auto"
        set(value) = prefs.edit().putString("mic_device", value).apply()

    var calendarPrefill: Boolean
        get() = prefs.getBoolean("calendar_prefill", true)
        set(value) = prefs.edit().putBoolean("calendar_prefill", value).apply()

    var accentColor: String
        get() = prefs.getString("accent_color", "indigo") ?: "indigo"
        set(value) = prefs.edit().putString("accent_color", value).apply()

    /** "system" (follow device), "light", or "dark". */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit().putBoolean("onboarding_done", value).apply()

    var recordingConsent: Boolean
        get() = prefs.getBoolean("recording_consent", false)
        set(value) = prefs.edit().putBoolean("recording_consent", value).apply()
}
