package com.meetily.mobile.export

import android.content.Context
import android.net.Uri
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.whisper.AudioFileDecoder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Cuts a time window out of a meeting's audio into a small shareable WAV.
 * Decoding goes through [AudioFileDecoder] (16 kHz mono float), so any
 * container the meeting stores — live .aac or an imported file — clips the
 * same way. WAV output because every receiving app can play it.
 */
object ClipExporter {

    private const val SAMPLE_RATE = AudioFileDecoder.TARGET_RATE
    private const val MAX_CLIP_MS = 5 * 60_000L
    private const val KEEP_CLIPS = 4

    fun clipsDir(context: Context): File =
        File(AudioStore.dir(context), "clips").apply { mkdirs() }

    /**
     * Blocking (decode runs far above realtime, but call off the main
     * thread). Returns the written WAV, or null if nothing was decodable
     * inside the window.
     */
    fun export(context: Context, source: File, startMs: Long, endMs: Long): File? {
        val start = startMs.coerceAtLeast(0)
        val end = (if (endMs > start) endMs else start + 10_000L)
            .coerceAtMost(start + MAX_CLIP_MS)
        val startSample = start * SAMPLE_RATE / 1000
        val endSample = end * SAMPLE_RATE / 1000

        val pcm = ShortArray((endSample - startSample).toInt())
        var position = 0L
        var filled = 0
        try {
            AudioFileDecoder.decode(
                context,
                Uri.fromFile(source),
                onPcm = { chunk ->
                    val chunkStart = position
                    position += chunk.size
                    if (position <= startSample) return@decode
                    var from = (startSample - chunkStart).toInt().coerceAtLeast(0)
                    while (from < chunk.size && filled < pcm.size) {
                        val v = (chunk[from++] * 32767f).toInt().coerceIn(-32768, 32767)
                        pcm[filled++] = v.toShort()
                    }
                },
                onProgress = {},
                cancelled = { filled >= pcm.size }
            )
        } catch (_: Throwable) {
            return null
        }
        if (filled < SAMPLE_RATE / 2) return null // under half a second

        val dir = clipsDir(context)
        pruneOld(dir)
        val out = File(dir, "recap-clip-${System.currentTimeMillis()}.wav")
        return try {
            writeWav(out, pcm, filled)
            out
        } catch (_: Exception) {
            out.delete()
            null
        }
    }

    /** Clips are throwaways; keep only the most recent few around. */
    private fun pruneOld(dir: File) {
        val clips = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        for (stale in clips.drop(KEEP_CLIPS - 1)) {
            stale.delete()
        }
    }

    private fun writeWav(out: File, pcm: ShortArray, count: Int) {
        val dataBytes = count * 2
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
            stream.write(
                byteArrayOf(
                    'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()
                )
            )
            stream.write(le32(36 + dataBytes))
            stream.write(
                byteArrayOf(
                    'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
                    'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()
                )
            )
            stream.write(le32(16))          // fmt chunk size
            stream.write(le16(1))           // PCM
            stream.write(le16(1))           // mono
            stream.write(le32(SAMPLE_RATE))
            stream.write(le32(SAMPLE_RATE * 2)) // byte rate
            stream.write(le16(2))           // block align
            stream.write(le16(16))          // bits per sample
            stream.write(
                byteArrayOf(
                    'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()
                )
            )
            stream.write(le32(dataBytes))
            val buf = ByteArray(2)
            for (i in 0 until count) {
                val v = pcm[i].toInt()
                buf[0] = (v and 0xff).toByte()
                buf[1] = ((v shr 8) and 0xff).toByte()
                stream.write(buf)
            }
        }
    }

    private fun le16(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()
    )

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )
}
