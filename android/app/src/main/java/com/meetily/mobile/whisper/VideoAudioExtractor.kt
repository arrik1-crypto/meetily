package com.meetily.mobile.whisper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.meetily.mobile.data.AudioStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Keeps only the sound of an imported video. The audio track is read
 * straight from the content URI and written, sample by sample and without
 * re-encoding, into a small audio-only file in AudioStore (VideoAudio says
 * which format). Nothing of the picture is copied: a two-hour 1080p meeting
 * recording is gigabytes, its AAC sound about a hundred megabytes.
 *
 * When the sound cannot be remuxed (VideoAudio.Keep.WHOLE_FILE), or the
 * remux fails or reads back wrong, callers fall back to copying the file as
 * they always have, and AudioFileDecoder reads its sound from that copy.
 * Blocking: call from a worker thread.
 */
object VideoAudioExtractor {

    /** Names this process's remuxed files; see VideoAudio.isStale. */
    private val processToken: String =
        UUID.randomUUID().toString().replace("-", "").take(12)

    /** The MIME type of each track, or null when the file cannot be opened. */
    fun probe(context: Context, uri: Uri): List<String>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** What to keep of [uri]; see VideoAudio.plan. */
    fun planFor(context: Context, uri: Uri): VideoAudio.Keep =
        VideoAudio.plan(probe(context, uri), Build.VERSION.SDK_INT)

    /**
     * A new file in AudioStore for a remux that an import will adopt by
     * renaming (ImportService), named so one left behind by a process that
     * died is recognised and removed ([sweepStale]).
     */
    fun newExtractFile(context: Context, sourceName: String, keep: VideoAudio.Keep): File =
        AudioStore.newImportFile(
            context,
            VideoAudio.extractName(processToken, UUID.randomUUID().toString()),
            VideoAudio.storedName(sourceName, keep)
        )

    /**
     * Deletes remuxed files a previous process left behind (it died between
     * the remux and the import adopting it). Files this process is writing
     * carry its own token and are left alone.
     */
    fun sweepStale(context: Context) {
        AudioStore.dir(context).listFiles()?.forEach { file ->
            if (VideoAudio.isStale(file.name, processToken)) AudioStore.delete(context, file.name)
        }
    }

    /**
     * Remuxes the first audio track of [uri] into [out] as [keep] says.
     * Returns true when [out] holds that sound and reads back as audio;
     * otherwise [out] is deleted and false returned, also when [cancelled]
     * turned true. [onProgress] gets 0..100 from the position in the track.
     */
    fun extract(
        context: Context,
        uri: Uri,
        keep: VideoAudio.Keep,
        out: File,
        cancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Boolean {
        val format = when (keep) {
            VideoAudio.Keep.M4A -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            VideoAudio.Keep.THREE_GPP -> MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
            VideoAudio.Keep.WEBM -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            else -> return false
        }
        val ok = try {
            remux(context, uri, out, format, cancelled, onProgress) && !cancelled() &&
                readsBackAsAudio(out)
        } catch (_: Throwable) {
            // An odd file, a muxer that refuses the track, a full disk, a
            // provider that dropped the stream: the caller copies instead.
            false
        }
        if (!ok) out.delete()
        return ok
    }

    private fun remux(
        context: Context,
        uri: Uri,
        out: File,
        outputFormat: Int,
        cancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        // Read-write: the WebM muxer needs it, and the others accept it.
        val file = RandomAccessFile(out, "rw")
        try {
            file.setLength(0L)
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("audio/")
            } ?: return false
            val trackFormat = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                -1L
            }
            val capacity = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                0
            }.coerceIn(MIN_SAMPLE_BUFFER, MAX_SAMPLE_BUFFER)
            val m = MediaMuxer(file.fd, outputFormat)
            muxer = m
            val outTrack = m.addTrack(trackFormat)
            m.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(capacity)
            val info = MediaCodec.BufferInfo()
            // Timestamps are written from 0: the transcript is timed against
            // this file, and a muxer refuses times before the first sample.
            var firstUs = Long.MIN_VALUE
            var lastUs = -1L
            var samples = 0L
            var lastPercent = -1
            while (true) {
                if (cancelled()) return false
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                // Protected (DRM) content cannot be read this way.
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) return false
                val timeUs = extractor.sampleTime
                if (firstUs == Long.MIN_VALUE) firstUs = timeUs
                // Never backwards: an MPEG-4 track must not go back in time.
                val at = maxOf(timeUs - firstUs, lastUs)
                lastUs = at
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, at, flags)
                m.writeSampleData(outTrack, buffer, info)
                samples++
                if (durationUs > 0) {
                    val percent = ((timeUs.coerceAtLeast(0L) * 100) / durationUs).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
                extractor.advance()
            }
            if (samples == 0L) return false
            m.stop()
            started = false
            return true
        } finally {
            if (started) {
                try {
                    muxer?.stop()
                } catch (_: Throwable) {
                }
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
            try {
                file.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** The remuxed file opens and has a sound track: the import can read it. */
    private fun readsBackAsAudio(file: File): Boolean {
        if (file.length() <= 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("audio/")
            }
        } catch (_: Exception) {
            false
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** Big enough for any compressed audio frame. */
    private const val MIN_SAMPLE_BUFFER = 256 * 1024
    private const val MAX_SAMPLE_BUFFER = 8 * 1024 * 1024
}
