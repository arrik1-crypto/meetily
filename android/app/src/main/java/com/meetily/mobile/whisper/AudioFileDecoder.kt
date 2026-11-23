package com.meetily.mobile.whisper

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Streaming decoder: any audio the platform can read (m4a, mp3, wav, ogg,
 * flac, amr…) → 16 kHz mono float PCM, delivered in chunks so hour-long
 * files never sit in memory whole.
 */
object AudioFileDecoder {

    const val TARGET_RATE = 16_000

    class UnsupportedAudioException(message: String) : RuntimeException(message)

    /**
     * Decodes [uri], pushing 16 kHz mono float chunks into [onPcm]. Progress
     * (0..100, from the file position) goes to [onProgress]. Returns the
     * container-reported duration in ms, or -1 if unknown. Stops early when
     * [cancelled] returns true.
     *
     * [startUs] > 0 seeks first, to the sync frame before it (less a short
     * preroll, so the decoder's warm-up output falls before the wanted
     * window), rather than decoding everything ahead of it. Decoding from
     * the top to cut five seconds out of hour three was tens of seconds of
     * full-core work. The first chunk then does not start at 0: [onStartUs]
     * receives its media time before the first [onPcm]. It is called only
     * when a seek was asked for.
     *
     * One read of one file; a [Reader] reads several places of one file
     * from a single open.
     */
    fun decode(
        context: Context,
        uri: Uri,
        onPcm: (FloatArray) -> Unit,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
        startUs: Long = 0L,
        onStartUs: ((Long) -> Unit)? = null
    ): Long = Reader(context, uri).use { reader ->
        reader.decode(onPcm, onProgress, cancelled, startUs, onStartUs)
        if (reader.durationUs > 0) reader.durationUs / 1000 else -1L
    }

    /**
     * One open of [uri]'s first audio track for any number of reads
     * ([decode]) at different places: the extractor and the decoder are
     * made once, and every read after the first seeks and starts the
     * decoder afresh. Opening raw ADTS (every live recording) makes the
     * platform's AAC extractor walk every frame header of the file, which
     * takes seconds on a long meeting, so a job that reads many short
     * windows of one recording (AlignService) opens it once.
     *
     * The constructor throws as [AudioFileDecoder.decode] does (no audio
     * track, a file the platform cannot read), with nothing left open. A
     * read that throws may leave the decoder unusable: [close] the reader
     * then, and open another for further reads. One thread at a time;
     * always [close] it.
     */
    class Reader(context: Context, uri: Uri) : java.io.Closeable {
        private val extractor = MediaExtractor()
        private var codec: MediaCodec? = null
        private var format: MediaFormat? = null

        /** The track's length in µs as its format states it; -1 when it does not. */
        var durationUs = -1L
            private set

        private var sampleRate = 0
        private var channels = 1
        private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        /** A read has begun: the next one seeks and starts the decoder afresh. */
        private var used = false

        /**
         * The decoder has given output (or its format) since it was last
         * configured: it has taken the codec-specific data from the format,
         * and a flush keeps it. Before that a flush may lose it (MediaCodec,
         * "Codec-specific Data"), so the decoder is configured again instead.
         */
        private var hadOutput = false

        init {
            try {
                extractor.setDataSource(context, uri, null)
                var trackIndex = -1
                var found: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        found = f
                        break
                    }
                }
                if (trackIndex < 0 || found == null) {
                    throw UnsupportedAudioException("no audio track found")
                }
                extractor.selectTrack(trackIndex)
                format = found
                durationUs = try {
                    if (found.containsKey(MediaFormat.KEY_DURATION)) {
                        found.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        -1L
                    }
                } catch (_: Exception) {
                    -1L
                }
                val c = MediaCodec.createDecoderByType(found.getString(MediaFormat.KEY_MIME).orEmpty())
                codec = c
                startCodec(c, found)
            } catch (t: Throwable) {
                close()
                throw t
            }
        }

        /** Configures and starts [c] for [f]: its output is as [f] states until it says otherwise. */
        private fun startCodec(c: MediaCodec, f: MediaFormat) {
            c.configure(f, null, null, 0)
            c.start()
            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            hadOutput = false
        }

        /**
         * One read, as [AudioFileDecoder.decode] describes it: from the start
         * of the file, or from a seek to [startUs]. Every read after the
         * first seeks (to the start of the file when [startUs] is 0), so
         * [onStartUs] is called for it whatever [startUs] is.
         */
        fun decode(
            onPcm: (FloatArray) -> Unit,
            onProgress: (Int) -> Unit,
            cancelled: () -> Boolean,
            startUs: Long = 0L,
            onStartUs: ((Long) -> Unit)? = null
        ) {
            val codec = this.codec ?: throw IllegalStateException("reader closed")
            val format = this.format ?: throw IllegalStateException("reader closed")
            val again = used
            used = true
            if (again) {
                // Whatever the last read left queued, or its end of stream, goes.
                if (hadOutput) {
                    codec.flush()
                } else {
                    codec.stop()
                    startCodec(codec, format)
                }
            }
            val seek = startUs > 0 || again
            if (seek) {
                extractor.seekTo(
                    (startUs - SEEK_PREROLL_US).coerceAtLeast(0L),
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                )
            }
            var startReported = !seek
            var resampler = SincResampler(sampleRate, TARGET_RATE)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPercent = -1

            while (!outputDone) {
                if (cancelled()) break

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val read = if (buffer != null) {
                            extractor.readSampleData(buffer, 0)
                        } else {
                            -1
                        }
                        if (read < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, read, extractor.sampleTime, 0
                            )
                            if (durationUs > 0) {
                                val percent =
                                    ((extractor.sampleTime * 100) / durationUs)
                                        .toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    hadOutput = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIndex)
                        if (out != null) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            if (!startReported) {
                                startReported = true
                                onStartUs?.invoke(info.presentationTimeUs)
                            }
                            val mono = toMonoFloat(out, channels, pcmEncoding)
                            val resampled = resampler.process(mono)
                            if (resampled.isNotEmpty()) onPcm(resampled)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    hadOutput = true
                    val newFormat = codec.outputFormat
                    val newRate = try {
                        newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } catch (_: Exception) {
                        sampleRate
                    }
                    channels = try {
                        newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } catch (_: Exception) {
                        channels
                    }
                    pcmEncoding = try {
                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    } catch (_: Exception) {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    if (newRate != sampleRate) {
                        sampleRate = newRate
                        resampler = SincResampler(sampleRate, TARGET_RATE)
                    }
                }
            }
        }

        override fun close() {
            val c = codec
            codec = null
            format = null
            try {
                c?.stop()
            } catch (_: Throwable) {
            }
            try {
                c?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * The MIME type of the first audio track in [uri], the one [decode]
     * reads, or null when there is none or the file cannot be opened.
     */
    fun audioMime(context: Context, uri: Uri): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                .firstOrNull { it.startsWith("audio/") }
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** Interleaved PCM buffer → mono float [-1, 1]. */
    private fun toMonoFloat(
        buffer: java.nio.ByteBuffer,
        channels: Int,
        pcmEncoding: Int
    ): FloatArray {
        val ch = channels.coerceAtLeast(1)
        return if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buffer.asFloatBuffer()
            val frames = fb.remaining() / ch
            val mono = FloatArray(frames)
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) sum += fb.get(i * ch + c)
                mono[i] = sum / ch
            }
            mono
        } else {
            val sb = buffer.asShortBuffer()
            val frames = sb.remaining() / ch
            val mono = FloatArray(frames)
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until ch) sum += sb.get(i * ch + c).toInt()
                mono[i] = (sum.toFloat() / ch) / 32768f
            }
            mono
        }
    }

    /** About two AAC frames at 16 kHz: decoder warm-up lands before the window. */
    private const val SEEK_PREROLL_US = 150_000L
}
