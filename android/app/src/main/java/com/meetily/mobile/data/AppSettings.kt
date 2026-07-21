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
}
