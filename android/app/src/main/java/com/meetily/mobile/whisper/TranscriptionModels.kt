package com.meetily.mobile.whisper

import android.content.Context

/**
 * Router over the two transcription-model families: whisper.cpp ggml files
 * and sherpa-onnx NeMo models (Parakeet/Nemotron). A model key belongs to
 * exactly one family; whisper remains the fallback for unknown keys, which
 * also keeps old persisted settings valid.
 */
object TranscriptionModels {

    fun isNemo(key: String): Boolean = NemoModels.byKeyOrNull(key) != null

    fun displayName(key: String): String =
        NemoModels.byKeyOrNull(key)?.displayName
            ?: WhisperModels.byKey(key).displayName

    fun sizeMb(key: String): Int =
        NemoModels.byKeyOrNull(key)?.totalMb ?: WhisperModels.byKey(key).sizeMb

    fun englishOnly(key: String): Boolean =
        NemoModels.byKeyOrNull(key)?.englishOnly
            ?: WhisperModels.byKey(key).englishOnly

    fun isDownloaded(context: Context, key: String): Boolean {
        val nemo = NemoModels.byKeyOrNull(key)
        return if (nemo != null) {
            NemoModels.isDownloaded(context, nemo)
        } else {
            WhisperModels.isDownloaded(context, WhisperModels.byKey(key))
        }
    }

    /** Runtime check for the family the key belongs to. */
    fun isRuntimeAvailable(key: String): Boolean =
        if (isNemo(key)) {
            NemoModels.isRuntimeAvailable()
        } else {
            WhisperModels.isRuntimeAvailable()
        }

    /** Ready to transcribe with [key] right now. */
    fun isReady(context: Context, key: String): Boolean =
        isRuntimeAvailable(key) && isDownloaded(context, key)

    /** "whisper" or "nemo" — see [rankedForCheck]. */
    fun family(key: String): String = if (isNemo(key)) "nemo" else "whisper"

    /**
     * Transcription quality, higher is better. Explicit, because the obvious
     * proxy is wrong: Parakeet's files total 632 MB against turbo Q's 547, so
     * ranking by BYTES puts the fast model above the slow one and, once the
     * "not the same model twice" rule removes it, hands the job to the
     * slowest thing installed. Size measures capacity, not accuracy, and
     * across these two families it is anti-correlated with speed.
     */
    fun qualityRank(key: String): Int = when (key) {
        "tiny.en", "tiny" -> 1
        "base.en", "base" -> 2
        "small.en-q5_1", "small-q5_1" -> 3
        "small.en", "small" -> 4
        // Same architecture and parameter count, so the same rank. Nemotron
        // 3.5 covers far more languages, but nobody has measured its ENGLISH
        // accuracy against the English-only sibling — and NVIDIA reports it
        // on FLEURS rather than the Open ASR Leaderboard, so the published
        // numbers do not compare. Ranking it higher would assert that.
        "nemotron-en", "nemotron-3.5" -> 5
        "parakeet-tdt-v2", "parakeet-tdt-v3" -> 6
        "large-v3-turbo-q5_0" -> 7
        else -> 3
    }

    /**
     * Candidates for a second-opinion pass over a transcript made by
     * [currentKey], best first.
     *
     * Excludes by FAMILY rather than by key. A second Whisper size, or
     * Parakeet v3 after v2, agrees with the first pass on most of what it
     * gets wrong — they share an architecture and much of their training
     * data. Whisper against Parakeet is the genuinely independent comparison,
     * and it is also the cheap one.
     *
     * [transcriptSample] (some of the current transcript's text) is the only
     * language signal there is: see [rankForCheck].
     */
    fun rankedForCheck(
        context: Context,
        currentKey: String?,
        transcriptSample: String? = null
    ): List<String> = rankForCheck(downloadedKeys(context), currentKey, transcriptSample)

    /**
     * Context-free core of [rankedForCheck], so the ranking is unit-testable.
     *
     * Quality alone ignored language. Ties kept list order, which puts every
     * English-only variant first, so a German transcript from a multilingual
     * Whisper was "checked" by English-only Parakeet v2: minutes of CPU for a
     * draft nobody can use. Now, when the source is multilingual (or its
     * text is not in a European script), English-only models drop out, and
     * so does Parakeet v3 (25 European languages) for Japanese, Chinese,
     * Korean and the like — each only while something better suited is
     * installed. Remaining ties go to the multilingual model.
     */
    internal fun rankForCheck(
        downloaded: List<String>,
        currentKey: String?,
        transcriptSample: String? = null
    ): List<String> {
        val currentFamily = currentKey?.let { family(it) }
        val crossFamily = downloaded.filter {
            it != currentKey && (currentFamily == null || family(it) != currentFamily)
        }
        // Fall back to a different model in the same family rather than
        // refusing outright when only one family is installed.
        val pool = crossFamily.ifEmpty { downloaded.filter { it != currentKey } }
        val european = transcriptSample?.let { europeanScript(it) }
        val englishSource = currentKey != null && englishOnly(currentKey)
        val multilingualSource =
            (currentKey != null && !englishSource) || european == false
        val suited = pool
            .filter { !multilingualSource || !englishOnly(it) }
            .filter { european != false || !europeanOnly(it) }
            .ifEmpty { pool }
        return suited.sortedWith(
            compareByDescending<String> { qualityRank(it) }
                .thenBy { if (englishSource) 0 else rankTieLanguage(it) }
        )
    }

    /** Tie-break: a multilingual model before an English-only one. */
    private fun rankTieLanguage(key: String): Int = if (englishOnly(key)) 1 else 0

    /** Models whose languages are all written in European scripts. */
    private fun europeanOnly(key: String): Boolean = key == "parakeet-tdt-v3"

    /**
     * True when [text]'s letters are mostly Latin, Cyrillic or Greek; false
     * when mostly another script; null when there is too little to tell.
     */
    internal fun europeanScript(text: String): Boolean? {
        var european = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN,
                Character.UnicodeScript.CYRILLIC,
                Character.UnicodeScript.GREEK -> european++
                else -> other++
            }
        }
        if (european + other < 8) return null
        return european >= other
    }

    /** The "220 MB · English · Whisper" line every model picker shows. */
    fun metaLine(context: Context, key: String): String =
        context.getString(
            com.meetily.mobile.R.string.model_card_meta,
            sizeMb(key),
            context.getString(
                if (englishOnly(key)) {
                    com.meetily.mobile.R.string.model_lang_en
                } else {
                    com.meetily.mobile.R.string.model_lang_multi
                }
            ),
            if (isNemo(key)) "NVIDIA" else "Whisper"
        )

    /**
     * [metaLine] plus what this model would cost for [audioMs] of audio on
     * this phone. The only speed signal the pickers carried before was the
     * word "slow" inside a display name, sitting in the same parenthesis as
     * "best accuracy" — which is not a number anyone can act on.
     */
    fun metaLineFor(context: Context, key: String, audioMs: Long): String {
        val base = metaLine(context, key)
        if (audioMs <= 0L) return base
        return context.getString(
            com.meetily.mobile.R.string.model_card_meta_estimate,
            base,
            ModelSpeed.estimateLabel(context, key, audioMs)
        )
    }

    /**
     * Every downloaded model, for a check the user is choosing BY HAND:
     * the cross-family recommendations first, then everything else, then
     * the model that made the current transcript.
     *
     * [rankedForCheck] hides same-family models on purpose, because an
     * unattended pass is only worth running if it is independent. Applying
     * that to a manual choice was a mistake — it silently removed models the
     * user had downloaded, and when only one survived the picker did not
     * appear at all and the run just started. Refusing to offer an installed
     * model is not a safety feature; the person asking is the one who knows
     * what they want.
     */
    fun allForCheck(context: Context, currentKey: String?): List<String> =
        orderForCheck(downloadedKeys(context), currentKey)

    /** Context-free core of [allForCheck], so the ordering is unit-testable. */
    internal fun orderForCheck(downloaded: List<String>, currentKey: String?): List<String> {
        val currentFamily = currentKey?.let { family(it) }
        // Equal quality: the multilingual model first, unless the source was
        // English-only (see rankForCheck).
        val englishSource = currentKey != null && englishOnly(currentKey)
        val order = compareByDescending<String> { qualityRank(it) }
            .thenBy { if (englishSource) 0 else rankTieLanguage(it) }
        val cross = downloaded
            .filter { it != currentKey && currentFamily != null && family(it) != currentFamily }
            .sortedWith(order)
        val rest = downloaded.filter { it !in cross }.sortedWith(order)
        // The current model goes last: re-running it is legitimate (settings
        // may have changed since) but it is the least useful second opinion.
        val (current, others) = rest.partition { it == currentKey }
        return cross + others + current
    }

    /**
     * Every key the app can resolve, whisper family first — including
     * superseded models, so a persisted setting never stops resolving.
     * Pickers want [offeredKeys].
     */
    fun allKeys(): List<String> =
        WhisperModels.ALL.map { it.key } + NemoModels.ALL.map { it.key }

    /** Keys to show in a picker: see [NemoModels.offered]. */
    fun offeredKeys(context: Context): List<String> =
        WhisperModels.ALL.map { it.key } + NemoModels.offered(context).map { it.key }

    /** Keys of every model that is downloaded and runnable. */
    fun downloadedKeys(context: Context): List<String> =
        allKeys().filter { isReady(context, it) }
}
