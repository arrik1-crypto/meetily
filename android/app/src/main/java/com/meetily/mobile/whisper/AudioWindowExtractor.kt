package com.meetily.mobile.whisper

import android.content.Context
import android.net.Uri
import com.meetily.mobile.data.TranscriptResume
import java.io.File

/**
 * Pulls one time window out of a saved meeting-audio file as 16 kHz mono
 * float PCM — used to compute a voiceprint for a transcript line the user
 * tagged after the fact. Seeks to just before the window, decodes from
 * there and stops as soon as the window is filled. An MP3 (an imported
 * file keeps its format) is decoded from the top instead: its seeks land
 * seconds away from where its timestamps say (TranscriptResume.seekable),
 * and the window would hold someone else's speech.
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
        val uri = Uri.fromFile(file)
        val seekUs = seekUs(start, AudioFileDecoder.audioMime(context, uri))
        try {
            AudioFileDecoder.decode(
                context,
                uri,
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
                cancelled = { filled >= window.size },
                // Seek rather than decode everything ahead of the window;
                // counting then starts from wherever the seek landed. From
                // the top, counting starts at 0 and the audio before the
                // window is skipped as it comes.
                startUs = seekUs,
                onStartUs = if (seekUs > 0L) {
                    { us: Long -> position = us * SAMPLE_RATE / 1_000_000 }
                } else {
                    null
                }
            )
        } catch (_: Throwable) {
            return null
        }
        if (filled < SAMPLE_RATE * 3 / 2) return null // < 1.5 s: too short to embed
        return if (filled == window.size) window else window.copyOf(filled)
    }

    /**
     * Where the decode of a window starting at [startMs] begins, in µs: the
     * window itself when a seek lands where it says, else 0 (the top of the
     * file) for a format whose seeks do not ([mime] MP3, or unknown).
     */
    internal fun seekUs(startMs: Long, mime: String?): Long =
        if (TranscriptResume.seekable(mime)) startMs.coerceAtLeast(0L) * 1000 else 0L
}
