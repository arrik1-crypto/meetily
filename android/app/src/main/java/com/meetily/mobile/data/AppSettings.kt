package com.meetily.mobile.data

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meetily_settings", Context.MODE_PRIVATE)

    var useLlm: Boolean
        get() = prefs.getBoolean("use_llm", false)
        set(value) = prefs.edit().putBoolean("use_llm", value).apply()

    /** AI engine: "endpoint" (OpenAI-compatible URL) or "local" (embedded llama.cpp). */
    var llmEngine: String
        get() = prefs.getString("llm_engine", "endpoint") ?: "endpoint"
        set(value) {
            // Consent to unattended off-device summaries was given for the
            // engine in force at the time. Changing engine re-asks rather
            // than quietly inheriting the answer.
            val edit = prefs.edit().putString("llm_engine", value)
            if (value != prefs.getString("llm_engine", "endpoint")) {
                edit.putBoolean("auto_summary_endpoint_ok", false)
            }
            edit.apply()
        }

    /** Selected on-device GGUF model key (see LocalLlmModels). */
    var localLlmModel: String
        get() = prefs.getString("local_llm_model", "qwen2.5-1.5b") ?: "qwen2.5-1.5b"
        set(value) = prefs.edit().putString("local_llm_model", value).apply()

    /** True when the chosen AI engine has enough config to be called at all. */
    val llmConfigured: Boolean
        get() = llmEngine == "local" || llmBaseUrl.isNotBlank()

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

    /** Transcript body text size: "small", "medium" (default) or "large". */
    var transcriptTextSize: String
        get() = prefs.getString("transcript_text_size", "medium") ?: "medium"
        set(value) = prefs.edit().putString("transcript_text_size", value).apply()

    /**
     * The user's own name, used to personalise the Note to Self summary so it
     * can tell their commitments from everyone else's. Blank = unknown.
     */
    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value.trim()).apply()

    /** Summary depth knob: "brief", "standard", or "detailed". */
    var summaryDepth: String
        get() = prefs.getString("summary_depth", "standard") ?: "standard"
        set(value) = prefs.edit().putString("summary_depth", value).apply()

    /**
     * Per-series template memory (normalized title → template key), so a
     * standup series defaults to the standup template while a sales series
     * defaults to the sales report — independent of the global last-used.
     */
    fun seriesTemplate(seriesKey: String): String? {
        if (seriesKey.isBlank()) return null
        return try {
            org.json.JSONObject(prefs.getString("series_templates", "{}") ?: "{}")
                .optString(seriesKey).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun setSeriesTemplate(seriesKey: String, templateKey: String) {
        if (seriesKey.isBlank()) return
        try {
            val map = org.json.JSONObject(
                prefs.getString("series_templates", "{}") ?: "{}"
            )
            map.put(seriesKey, templateKey)
            prefs.edit().putString("series_templates", map.toString()).apply()
        } catch (_: Exception) {
        }
    }

    /** "system" (Android speech recognizer) or "whisper" (on-device whisper.cpp). */
    var transcriptionEngine: String
        get() = prefs.getString("transcription_engine", "system") ?: "system"
        set(value) = prefs.edit().putString("transcription_engine", value).apply()

    var whisperModel: String
        get() = prefs.getString("whisper_model", "base.en") ?: "base.en"
        set(value) = prefs.edit().putString("whisper_model", value).apply()

    /**
     * After a recording ends, run the saved audio through the heaviest
     * downloaded model and stage a comparison. Nothing is replaced without
     * the user reviewing it, and the pass is skipped on low battery.
     */
    var autoCheckTranscript: Boolean
        get() = prefs.getBoolean("auto_check_transcript", false)
        set(value) = prefs.edit().putBoolean("auto_check_transcript", value).apply()

    /**
     * Transcribe as the meeting happens, rather than from the recording
     * afterwards. Off by default, and worth leaving off.
     *
     * Running an ASR model continuously for the length of a meeting costs
     * roughly 40% of a phone battery per hour — measured, not estimated: a
     * 38-minute meeting took a device from 75% to just under 50%. The
     * after-the-fact pass over the saved audio produces a BETTER transcript
     * for less energy, because it works on whole sentences with real context
     * instead of chunks the silence detector happened to cut, batches the
     * encoder properly, clusters speakers across the entire recording, and
     * runs with the screen off (and optionally only on a charger).
     *
     * What turning this on buys is watching the words arrive. That is worth
     * something for a short note to self, so the option stays — but it is not
     * how the app should record a meeting.
     */
    var liveTranscription: Boolean
        get() = prefs.getBoolean("live_transcription", false)
        set(value) = prefs.edit().putBoolean("live_transcription", value).apply()

    /**
     * Hold the post-meeting accuracy pass back until the phone is on power.
     * The pass is DEFERRED, not skipped: a meeting that ends off-charger
     * queues and runs when you next plug in.
     */
    var autoCheckWhileChargingOnly: Boolean
        get() = prefs.getBoolean("auto_check_charging_only", false)
        set(value) = prefs.edit().putBoolean("auto_check_charging_only", value).apply()

    /**
     * After a recording ends, generate the AI summary without being asked.
     * Runs only on the local engine unless [autoSummaryEndpointOk] is set —
     * an endpoint engine would ship every meeting off-device with the user
     * never pressing anything.
     */
    var autoSummarize: Boolean
        get() = prefs.getBoolean("auto_summarize", false)
        set(value) = prefs.edit().putBoolean("auto_summarize", value).apply()

    /**
     * Replace a meeting's date-and-time title with one derived from its
     * transcript, the first time it is opened.
     *
     * Off by default. A recording is named for when it happened unless the
     * user asserted otherwise (by starting it from a calendar reminder, or
     * tapping "From calendar"); guessing a name from the words afterwards is
     * the app overriding that, so it has to be asked for.
     */
    var autoTitleFromTranscript: Boolean
        get() = prefs.getBoolean("auto_title_from_transcript", false)
        set(value) = prefs.edit().putBoolean("auto_title_from_transcript", value).apply()

    /** "end" (as soon as the meeting stops) or "charging" (defer to power). */
    var autoSummaryWhen: String
        get() = prefs.getString("auto_summary_when", "end") ?: "end"
        set(value) = prefs.edit().putString("auto_summary_when", value).apply()

    /**
     * Style for automatic summaries. Deliberately NOT [summaryTemplate],
     * which is last-used and gets rewritten every time the user picks a
     * template by hand — an automatic run must not drift with it.
     */
    var autoSummaryTemplate: String
        get() = prefs.getString("auto_summary_template", "general") ?: "general"
        set(value) = prefs.edit().putString("auto_summary_template", value).apply()

    /**
     * Explicit consent to run automatic summaries against a remote endpoint.
     * Reset whenever the engine changes, so switching from local to endpoint
     * re-asks instead of quietly inheriting the answer.
     */
    var autoSummaryEndpointOk: Boolean
        get() = prefs.getBoolean("auto_summary_endpoint_ok", false)
        set(value) = prefs.edit().putBoolean("auto_summary_endpoint_ok", value).apply()

    /**
     * True when an automatic summary may run unprompted right now: the
     * engine is configured, and either it is fully on-device or the user has
     * explicitly accepted that meetings leave the device.
     */
    val autoSummaryAllowed: Boolean
        get() = autoSummarize && useLlm && llmConfigured &&
            (llmEngine == "local" || autoSummaryEndpointOk)

    /**
     * Highlight and scroll the transcript along with playback. On by default:
     * it is the point of opening a meeting with the audio.
     */
    var followPlayback: Boolean
        get() = prefs.getBoolean("follow_playback", true)
        set(value) = prefs.edit().putBoolean("follow_playback", value).apply()

    /** Playback gain above the system ceiling, in dB (0 = off). */
    var playbackBoostDb: Int
        get() = prefs.getInt("playback_boost_db", 0)
        set(value) = prefs.edit().putInt("playback_boost_db", value).apply()

    /** Whisper translate task: non-English speech comes out as English text
     *  (multilingual models only; English-only models ignore this). */
    var whisperTranslate: Boolean
        get() = prefs.getBoolean("whisper_translate", false)
        set(value) = prefs.edit().putBoolean("whisper_translate", value).apply()

    /** Acoustic speaker detection (Whisper engine only, experimental). */
    var diarizationEnabled: Boolean
        get() = prefs.getBoolean("diarization_enabled", false)
        set(value) = prefs.edit().putBoolean("diarization_enabled", value).apply()

    var diarizationModel: String
        get() = prefs.getString("diarization_model", "resnet34-en") ?: "resnet34-en"
        set(value) = prefs.edit().putString("diarization_model", value).apply()

    /** Keep meeting audio on-device (Whisper engine only). */
    var saveAudio: Boolean
        get() = prefs.getBoolean("save_audio", true)
        set(value) = prefs.edit().putBoolean("save_audio", value).apply()

    /** Whisper capture: "recognition" (default), "camcorder", or "unprocessed". */
    var micSource: String
        get() = prefs.getString("mic_source", "recognition") ?: "recognition"
        set(value) = prefs.edit().putString("mic_source", value).apply()

    /** Whisper capture input device key ("auto" = system routing). */
    var micDevice: String
        get() = prefs.getString("mic_device", "auto") ?: "auto"
        set(value) = prefs.edit().putString("mic_device", value).apply()

    /** Custom vocabulary (names/jargon), comma or newline separated; biases
     *  Whisper transcription via its initial prompt (see Vocab.promptFor). */
    var customVocab: String
        get() = prefs.getString("custom_vocab", "") ?: ""
        set(value) = prefs.edit().putString("custom_vocab", value).apply()

    /** Meeting playback speed multiplier (0.5-3.0). */
    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1.0f)
        set(value) = prefs.edit().putFloat("playback_speed", value).apply()

    /** Skip non-speech stretches during meeting playback. */
    var skipSilence: Boolean
        get() = prefs.getBoolean("skip_silence", false)
        set(value) = prefs.edit().putBoolean("skip_silence", value).apply()

    /** Notify "meeting is starting — record?" at calendar event starts. */
    var meetingNudges: Boolean
        get() = prefs.getBoolean("meeting_nudges", false)
        set(value) = prefs.edit().putBoolean("meeting_nudges", value).apply()

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

    /** Only allow LLM endpoints on loopback / private networks. */
    var localOnlyLlm: Boolean
        get() = prefs.getBoolean("local_only_llm", true)
        set(value) = prefs.edit().putBoolean("local_only_llm", value).apply()

    /** Require biometric / device credential to open the app. */
    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(value) = prefs.edit().putBoolean("app_lock", value).apply()

    /** FLAG_SECURE: block screenshots and the recents-switcher preview. */
    var secureScreen: Boolean
        get() = prefs.getBoolean("secure_screen", false)
        set(value) = prefs.edit().putBoolean("secure_screen", value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit().putBoolean("onboarding_done", value).apply()

    var recordingConsent: Boolean
        get() = prefs.getBoolean("recording_consent", false)
        set(value) = prefs.edit().putBoolean("recording_consent", value).apply()
}
