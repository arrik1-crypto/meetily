package com.meetily.mobile.data

import java.util.Locale

/**
 * A meeting's spoken language: which languages can be pinned, what each
 * engine is told, what is recorded on the meeting afterwards, and whether
 * the English-only heuristics may run on its transcript. Pure — unit-tested.
 *
 * Codes are Whisper's (ISO 639-1 where one exists), which is also what
 * whisper_full_lang_id reports for a detected language.
 *
 * Auto stays the default everywhere. A wrong pin does not degrade the
 * transcript, it replaces it with a phonetic rendering in the wrong
 * language, so pinning is only ever something the user chooses.
 */
object MeetingLanguage {

    /** Setting and override value: the engine detects the language itself. */
    const val AUTO = "auto"

    /**
     * Languages offered when pinning, with their English names.
     *
     * English names because they also go into prompts, which are written in
     * English; pickers show each language in the phone's own language
     * instead. Every code here is in whisper.cpp's language table.
     */
    private val NAMES: Map<String, String> = linkedMapOf(
        "en" to "English", "de" to "German", "fr" to "French", "es" to "Spanish",
        "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "sv" to "Swedish",
        "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish", "pl" to "Polish",
        "cs" to "Czech", "sk" to "Slovak", "hu" to "Hungarian", "ro" to "Romanian",
        "bg" to "Bulgarian", "hr" to "Croatian", "sl" to "Slovenian", "et" to "Estonian",
        "lv" to "Latvian", "lt" to "Lithuanian", "el" to "Greek", "ru" to "Russian",
        "uk" to "Ukrainian", "tr" to "Turkish", "ar" to "Arabic", "he" to "Hebrew",
        "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
        "vi" to "Vietnamese", "th" to "Thai", "id" to "Indonesian", "ms" to "Malay"
    )

    /** The pinnable languages, in the order above. */
    val CHOICES: List<String> = NAMES.keys.toList()

    /**
     * Nemotron 3.5's language prompts by Whisper code: its 40 locales,
     * reduced to the base languages sherpa-onnx accepts as aliases
     * ("de" for de-DE). A code it does not know falls back to its
     * auto-detect prompt inside sherpa-onnx, so leaving one out here only
     * means asking for auto honestly instead of by accident.
     *
     * Norwegian is the one rename: Whisper says "no", Nemotron has nb-NO
     * (Bokmål) and nn-NO; Bokmål is what most Norwegians write.
     */
    private val NEMOTRON: Map<String, String> = mapOf(
        "en" to "en", "es" to "es", "fr" to "fr", "it" to "it", "pt" to "pt",
        "nl" to "nl", "de" to "de", "tr" to "tr", "ru" to "ru", "ar" to "ar",
        "hi" to "hi", "ja" to "ja", "ko" to "ko", "vi" to "vi", "uk" to "uk",
        "pl" to "pl", "sv" to "sv", "cs" to "cs", "no" to "nb", "da" to "da",
        "bg" to "bg", "fi" to "fi", "hr" to "hr", "sk" to "sk", "zh" to "zh",
        "hu" to "hu", "ro" to "ro", "et" to "et", "el" to "el", "lt" to "lt",
        "lv" to "lv", "mt" to "mt", "sl" to "sl", "he" to "he", "th" to "th",
        "nn" to "nn"
    )

    /**
     * [raw] as a language code, or null for "not pinned".
     *
     * Lower-cased and cut to its base subtag ("de-DE" and "de_AT" are
     * "de"); "auto", blank and anything that is not two or three letters
     * are null. Older Java spellings of Hebrew and Indonesian ("iw", "in")
     * become the codes Whisper uses.
     */
    fun normalize(raw: String?): String? {
        val base = raw?.trim()?.lowercase(Locale.ROOT)
            ?.substringBefore('-')?.substringBefore('_')
            ?: return null
        if (base.isEmpty() || base == AUTO) return null
        if (base.length !in 2..3 || !base.all { it in 'a'..'z' }) return null
        return when (base) {
            "iw" -> "he"
            "in" -> "id"
            else -> base
        }
    }

    /** English name for [code], for prompts; null when it has none. */
    fun englishName(code: String?): String? {
        val c = normalize(code) ?: return null
        NAMES[c]?.let { return it }
        // A detected language outside the picker list. Java knows the
        // English names of most ISO codes; one it does not know comes back
        // as the code itself, which is no use in a sentence.
        val name = Locale.forLanguageTag(c).getDisplayLanguage(Locale.ENGLISH)
        return name.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) }
    }

    /**
     * The language a run should pin, or null for auto-detect.
     *
     * [override] comes with one import or re-transcription: null means "no
     * override, use [setting]", while [AUTO] is an explicit "detect it this
     * time" that wins over a pinned setting.
     */
    fun pinFor(override: String?, setting: String?): String? {
        if (override != null && override.isNotBlank()) return normalize(override)
        return normalize(setting)
    }

    /**
     * The language argument for whisper.cpp: "en" for an English-only
     * model (which can do nothing else), the pin, or "auto".
     */
    fun whisperArgument(pinned: String?, englishOnly: Boolean): String =
        if (englishOnly) "en" else pinned ?: AUTO

    /**
     * The value for a language-prompted NeMo model's per-stream "language"
     * option (Nemotron 3.5), or null for a model with no language prompt.
     */
    fun nemoOption(languagePrompt: Boolean, englishOnly: Boolean, pinned: String?): String? = when {
        !languagePrompt -> null
        englishOnly -> "en"
        else -> pinned?.let { NEMOTRON[it] } ?: AUTO
    }

    /** True when Nemotron 3.5 has its own prompt for [code]. */
    fun nemotronSupports(code: String?): Boolean = normalize(code) in NEMOTRON

    /**
     * What a NeMo run records as the meeting's language: "en" from an
     * English-only model, otherwise the pin, which is the user's own word
     * for what was spoken. NeMo reports no detected language, so an
     * unpinned run records nothing.
     *
     * Nothing either when Nemotron 3.5 ([languagePrompt]) was pinned to a
     * language it has no prompt for: [nemoOption] ran it on auto, so what
     * it decoded is not known, and recording the pin would switch summaries
     * and the English heuristics to a language the text may not be in.
     * Parakeet takes no hint at all, so for it the pin stays the user's word.
     */
    fun nemoRecorded(pinned: String?, englishOnly: Boolean, languagePrompt: Boolean): String? =
        when {
            englishOnly -> "en"
            languagePrompt && !nemotronSupports(pinned) -> null
            else -> pinned
        }

    /**
     * What a Whisper run records, given the language variable it ended with:
     * the detected or pinned code, or null when detection never ran (no
     * speech reached the model).
     */
    fun whisperRecorded(finalArgument: String?): String? = normalize(finalArgument)

    /**
     * The language the transcript TEXT is in. Whisper's translate task turns
     * any speech into English, so a translated meeting reads as English
     * whatever was spoken; otherwise it is the spoken language, if known.
     */
    fun transcriptLanguage(language: String?, translated: Boolean): String? =
        if (translated) "en" else normalize(language)

    /**
     * Whether the English word lists and patterns (the extractive summary's
     * stopwords and action phrases, the offline action-item fallback) may
     * run on a transcript in [transcriptLanguage].
     *
     * Unknown counts as English: that is every meeting recorded before the
     * language was stored, and every one where detection did not run, and
     * for those the heuristics behave exactly as they always have.
     */
    fun englishHeuristics(transcriptLanguage: String?): Boolean {
        val code = normalize(transcriptLanguage)
        return code == null || code == "en"
    }
}
