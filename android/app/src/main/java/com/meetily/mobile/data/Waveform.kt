package com.meetily.mobile.data

import android.content.Context
import com.meetily.mobile.whisper.AudioFileDecoder
import java.io.File

/**
 * Per-bar loudness for a meeting's audio, so the Audio tab can draw it.
 *
 * The recording is stored as AAC, so there is no amplitude to read without
 * decoding — and decoding an hour-long meeting is far too slow to do on
 * every visit. It is done once and the result cached beside the audio: a
 * few hundred bytes against a file of many megabytes.
 *
 * The bars are RMS, not peak. Peak amplitude on speech is dominated by
 * plosives and door slams and draws a picket fence; RMS tracks how loud a
 * moment actually sounded, which is what makes a waveform useful for
 * finding the part you want.
 */
object Waveform {

    /** Bar count. Matches the design's 54-bar card. */
    const val BARS = 54

    /**
     * Cached bars for [audioFile], or null when it has never been computed.
     * Cheap: reads a small text sidecar, no decoding.
     */
    fun cached(context: Context, audioFile: String): FloatArray? {
        val file = sidecar(context, audioFile)
        if (!file.exists()) return null
        return try {
            val parts = file.readText().trim().split(",")
            if (parts.size != BARS) return null
            FloatArray(BARS) { parts[it].toFloat() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes [audioFile] and returns its bars, caching them. BLOCKING and
     * slow (seconds for a long meeting) — call from a worker thread.
     *
     * Returns null when the audio cannot be decoded, which is not an error
     * worth surfacing: the tab simply falls back to a flat placeholder
     * rather than claiming the recording is broken.
     */
    fun compute(context: Context, audioFile: String): FloatArray? {
        val source = AudioStore.fileFor(context, audioFile)
        if (!source.exists() || source.length() <= 0L) return null

        // Which bar a sample belongs to depends on the total length, and the
        // length is only known after decoding. Decoding twice is far too
        // slow, so samples go into a histogram much finer than BARS and that
        // is folded down at the end — bounded memory, one decode.
        val histogram = LoudnessHistogram()

        val ok = try {
            AudioFileDecoder.decode(
                context,
                AudioStore.uriFor(context, source),
                onPcm = { pcm -> histogram.add(pcm) },
                onProgress = {},
                cancelled = { false }
            )
            true
        } catch (_: Throwable) {
            false
        }
        if (!ok || histogram.total <= 0L) return null

        val normalised = histogram.bars(BARS)
        save(context, audioFile, normalised)
        return normalised
    }

    private fun save(context: Context, audioFile: String, bars: FloatArray) {
        try {
            sidecar(context, audioFile).writeText(
                bars.joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }
            )
        } catch (_: Exception) {
            // A cache that cannot be written just means recomputing later.
        }
    }

    private fun sidecar(context: Context, audioFile: String): File =
        File(AudioStore.fileFor(context, audioFile).absolutePath + ".peaks")
}
