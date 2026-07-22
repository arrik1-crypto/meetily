package com.meetily.mobile.whisper

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Pulls one time window out of a saved meeting-audio file as 16 kHz mono
 * float PCM — used to compute a voiceprint for a transcript line the user
 * tagged after the fact. Decodes from the file start (fast: decode runs
 * far above realtime) and stops as soon as the window is filled.
 */
object AudioWindowExtractor {

    private const val SAMPLE_RATE = AudioFileDecoder.TARGET_RATE
    private const val MAX_WINDOW_MS = 20_000L

    fun extract(context: Context, file: File, startMs: Long, endMs: Long): FloatArray? {
        val start = startMs.coerceAtLeast(0)
        val end = (if (endMs > start) endMs else start + 4_000L)
            .coerceAtMost(start + MAX_WINDOW_MS)
        val startSample = start * SAMPLE_RATE / 1000
        val endSample = end * SAMPLE_RATE / 1000
        val window = FloatArray((endSample - startSample).toInt())
        var position = 0L
        var filled = 0
        try {
            AudioFileDecoder.decode(
                context,
                Uri.fromFile(file),
                onPcm = { pcm ->
                    val chunkStart = position
                    position += pcm.size
                    if (position <= startSample) return@decode
                    var from = (startSample - chunkStart).toInt().coerceAtLeast(0)
                    while (from < pcm.size && filled < window.size) {
                        window[filled++] = pcm[from++]
                    }
                },
                onProgress = {},
                cancelled = { filled >= window.size }
            )
        } catch (_: Throwable) {
            return null
        }
        if (filled < SAMPLE_RATE * 3 / 2) return null // < 1.5 s: too short to embed
        return if (filled == window.size) window else window.copyOf(filled)
    }
}
