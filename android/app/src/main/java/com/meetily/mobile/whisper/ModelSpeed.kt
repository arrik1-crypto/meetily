package com.meetily.mobile.whisper

import android.content.Context

/**
 * How long a model actually takes on THIS phone, and what that means for a
 * given recording.
 *
 * Every run already measures its own throughput — ImportEta divides elapsed
 * time by audio processed on every progress tick — and then throws the number
 * away. Keeping it is what lets the app say "about 5 h 35 m for this meeting"
 * before committing to it, instead of discovering that two minutes in.
 *
 * The seed values are only for the first run of a model on a device. They are
 * deliberately rough: a real measurement replaces them the moment one exists.
 */
object ModelSpeed {

    /**
     * Minutes of compute per minute of audio, before anything is measured.
     *
     * Anchored to figures observed on a mid-range phone: a Parakeet
     * transducer runs several times faster than real time, while Whisper
     * encodes a fixed 30-second window whatever it is given, so its cost is
     * set by the model rather than by the audio.
     */
    private val SEED = mapOf(
        "tiny.en" to 0.06f,
        "tiny" to 0.06f,
        "base.en" to 0.12f,
        "base" to 0.12f,
        "small.en-q5_1" to 0.35f,
        "small-q5_1" to 0.35f,
        "small.en" to 0.5f,
        "small" to 0.5f,
        "large-v3-turbo-q5_0" to 2.0f
    )

    private const val NEMO_SEED = 0.4f

    /** Nothing outside this is a plausible measurement; clamp rather than trust. */
    private const val MIN_RATE = 0.01f
    private const val MAX_RATE = 30f

    private fun prefs(context: Context) =
        context.getSharedPreferences("meetily_model_speed", Context.MODE_PRIVATE)

    /**
     * Records a completed run. [audioMs] is the audio actually processed and
     * [elapsedMs] the wall clock it took.
     *
     * Short runs are ignored: model loading is a fixed cost of tens of
     * seconds, and over a brief run it dominates and would poison the
     * estimate for every later one.
     */
    fun record(context: Context, modelKey: String, audioMs: Long, elapsedMs: Long) {
        if (modelKey.isBlank()) return
        if (audioMs < MIN_MEASURABLE_MS || elapsedMs <= 0L) return
        val rate = (elapsedMs.toFloat() / audioMs.toFloat()).coerceIn(MIN_RATE, MAX_RATE)
        // Blend with what is already known so one thermally throttled run, or
        // one run that shared the phone with something else, does not become
        // the number the app quotes forever.
        val blended = measuredRate(context, modelKey)?.let { it * 0.5f + rate * 0.5f } ?: rate
        prefs(context).edit().putFloat(modelKey, blended).apply()
    }

    /** The measured rate for [modelKey] on this device, or null if never run. */
    fun measuredRate(context: Context, modelKey: String): Float? {
        val stored = prefs(context).getFloat(modelKey, 0f)
        return if (stored > 0f) stored else null
    }

    /** Measured rate if there is one, otherwise the seed. Never null. */
    fun rate(context: Context, modelKey: String): Float =
        measuredRate(context, modelKey)
            ?: SEED[modelKey]
            ?: if (TranscriptionModels.isNemo(modelKey)) NEMO_SEED else 1f

    /** Estimated wall-clock milliseconds to transcribe [audioMs] with [modelKey]. */
    fun estimateMs(context: Context, modelKey: String, audioMs: Long): Long =
        (audioMs * rate(context, modelKey)).toLong()

    /**
     * "about 25 min" / "about 5 h 35 m". Rounded coarsely on purpose — this
     * is a projection from one number, and false precision would oversell it.
     */
    fun estimateLabel(context: Context, modelKey: String, audioMs: Long): String {
        val ms = estimateMs(context, modelKey, audioMs)
        val minutes = ((ms + 30_000L) / 60_000L).coerceAtLeast(1L)
        return if (minutes >= 60) {
            context.getString(
                com.meetily.mobile.R.string.estimate_h_min,
                minutes / 60, minutes % 60
            )
        } else {
            context.getString(com.meetily.mobile.R.string.estimate_min, minutes)
        }
    }

    /** True when a run would take long enough to be worth warning about. */
    fun isLongRun(context: Context, modelKey: String, audioMs: Long): Boolean =
        estimateMs(context, modelKey, audioMs) > LONG_RUN_MS

    /** Below this the fixed model-load cost dominates and skews the rate. */
    private const val MIN_MEASURABLE_MS = 120_000L

    /**
     * Above this, an unattended pass is committing the phone to something the
     * user would want a say in.
     */
    const val LONG_RUN_MS = 45L * 60_000L
}
