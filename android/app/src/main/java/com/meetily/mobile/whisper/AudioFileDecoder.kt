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
     */
    fun decode(
        context: Context,
        uri: Uri,
        onPcm: (FloatArray) -> Unit,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw UnsupportedAudioException("no audio track found")
            }
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val durationUs = try {
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    -1L
                }
            } catch (_: Exception) {
                -1L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInt(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInt(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = Resampler(sampleRate)

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
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIndex)
                        if (out != null) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            val mono = toMonoFloat(out, channels, pcmEncoding)
                            val resampled = resampler.process(mono)
                            if (resampled.isNotEmpty()) onPcm(resampled)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    val newRate = try {
                        newFormat.getInt(MediaFormat.KEY_SAMPLE_RATE)
                    } catch (_: Exception) {
                        sampleRate
                    }
                    channels = try {
                        newFormat.getInt(MediaFormat.KEY_CHANNEL_COUNT)
                    } catch (_: Exception) {
                        channels
                    }
                    pcmEncoding = try {
                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            newFormat.getInt(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    } catch (_: Exception) {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    if (newRate != sampleRate) {
                        sampleRate = newRate
                        resampler = Resampler(sampleRate)
                    }
                }
            }
            return if (durationUs > 0) durationUs / 1000 else -1L
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
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

    /**
     * Stateful linear-interpolation resampler; keeps fractional position and
     * the previous buffer's last sample so chunk boundaries stay continuous.
     */
    private class Resampler(private val srcRate: Int, private val dstRate: Int = TARGET_RATE) {
        private var frac = 0.0 // position within [last, src[0])
        private var last = 0f
        private var hasLast = false

        fun process(input: FloatArray): FloatArray {
            if (input.isEmpty()) return FloatArray(0)
            if (srcRate == dstRate) return input
            val src: FloatArray
            if (hasLast) {
                src = FloatArray(input.size + 1)
                src[0] = last
                System.arraycopy(input, 0, src, 1, input.size)
            } else {
                src = input
            }
            val step = srcRate.toDouble() / dstRate
            val maxOut = (((src.size - 1) - frac) / step).toInt() + 2
            val out = FloatArray(maxOut.coerceAtLeast(0))
            var t = frac
            var count = 0
            while (t <= src.size - 1) {
                val i = t.toInt()
                val f = (t - i).toFloat()
                out[count++] =
                    if (i + 1 < src.size) src[i] * (1 - f) + src[i + 1] * f
                    else src[i]
                t += step
            }
            frac = t - (src.size - 1)
            last = src[src.size - 1]
            hasLast = true
            return out.copyOf(count)
        }
    }
}
