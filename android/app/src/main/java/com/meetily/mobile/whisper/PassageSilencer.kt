package com.meetily.mobile.whisper

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.WorkerThread
import com.meetily.mobile.data.LoudnessHistogram
import com.meetily.mobile.data.PassageAudio
import com.meetily.mobile.data.PassageSwap
import com.meetily.mobile.data.Waveform
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder

/**
 * Writes a meeting's audio again with one passage turned to silence: the
 * recording is decoded as it is (its own rate and channels), the frames in
 * the span are zeroed with a short fade either side (PassageAudio), and the
 * result is encoded with the platform AAC-LC encoder, configured as the
 * live recording's (MeetingAudioWriter), at the recording's rate, channels
 * and a similar bitrate, into ADTS frames like a live recording.
 *
 * Nothing moves in time. The encoder and decoder together delay the sound
 * (the encoder's priming); that delay is measured first on this phone by
 * encoding and decoding a short probe sound, and the new file is written
 * that many frames early, so its decoded timeline lines up with the
 * recording's frame for frame and every line, word and mark keeps pointing
 * at the same sound, however often a passage is removed. Its length comes
 * out the same, give or take the encoder's padding of its last frame, which
 * [silence] checks by reading the file back with MediaExtractor.
 *
 * ADTS rather than MediaMuxer's MP4. The muxer would not change the timing:
 * it writes an edit list only for a track stamped before zero or starting
 * late, and the platform encoder stamps its first frame at its first
 * input's time, so an .m4a would carry the priming untrimmed too, and its
 * length is its frames' (the last one repeats the one before). ADTS is what
 * live recordings are, so every path that reads a recording already reads
 * it, and it has no edit-list field a decoder could honour where the
 * transcript's decoder did not, which would put every word out by the
 * priming.
 *
 * Blocking and single-threaded; call it from a worker. Internal, like the
 * loudness collector (Waveform) it fills for the Audio tab.
 */
internal object PassageSilencer {

    /**
     * How a run ended. NEEDS_CONSENT: the recording decodes to a format the
     * encoder cannot take and the user did not agree to the whole recording
     * being mixed down (PassageAudio.mayWrite); nothing was written.
     */
    enum class Outcome { DONE, CANCELLED, NO_SPACE, FAILED, NEEDS_CONSENT }

    class Result(
        val outcome: Outcome,
        /** What the new file was written as (DONE only). */
        val format: PassageAudio.Format? = null,
        /** The recording's length as decoded, in ms; 0 when not known. */
        val durationMs: Long = 0L,
        /** Loudness of the new audio for the Audio tab's waveform (DONE only). */
        val loudness: LoudnessHistogram? = null
    )

    /** What a decode found. */
    private class Source(
        val sampleRate: Int,
        val channels: Int,
        val frames: Long,
        val finished: Boolean
    )

    /** Below this much free space a failed write is taken for a full disk. */
    private const val LOW_SPACE_BYTES = 16L * 1024 * 1024

    /** The recording would be mixed down, which the user did not agree to. */
    private class ConvertNotAgreed : RuntimeException()

    /**
     * What writing [file] again does to it beyond the passage, from its
     * first audio track's header, for the confirmation
     * (PassageAudio.reencodeOf); null when the header cannot be read.
     */
    @WorkerThread
    fun reencodeOf(file: File): PassageAudio.Reencode? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
                ?: return null
            PassageAudio.reencodeOf(
                format.getString(MediaFormat.KEY_MIME),
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Writes [source] with [startMs, endMs) silenced into [out], using
     * [probe] for the delay measurement. [onProgress] gets 0..99 and the
     * recording's ms decoded so far. [out] is deleted unless the outcome
     * is DONE, and [probe] always. A recording that decodes to a format
     * the encoder cannot take is mixed down to 16 kHz mono only when
     * [allowConvert] (the confirmation said so); otherwise the outcome is
     * NEEDS_CONSENT, decided on what the decoder gives, before anything is
     * written.
     */
    @WorkerThread
    fun silence(
        source: File,
        out: File,
        probe: File,
        startMs: Long,
        endMs: Long,
        sourceBitRate: Int,
        totalMs: Long,
        allowConvert: Boolean,
        cancelled: () -> Boolean,
        onProgress: (percent: Int, doneMs: Long) -> Unit
    ): Result {
        out.delete()
        var pipeline: Pipeline? = null
        val outcome = try {
            val decoded = decode(source, cancelled) { pcm, frames, rate, channels ->
                val p = pipeline ?: run {
                    // Asked of the decoder's format: the header PassageUi read may differ.
                    if (!PassageAudio.mayWrite(rate, channels, allowConvert)) throw ConvertNotAgreed()
                    setUp(rate, channels, sourceBitRate, startMs, endMs, out, probe, cancelled)
                        ?.also { pipeline = it }
                        ?: throw IllegalStateException("the codec's delay could not be measured")
                }
                if (rate != p.sourceRate || channels != p.sourceChannels) {
                    throw IllegalStateException("format changed mid-file")
                }
                p.push(pcm, frames)
                val doneMs = p.sourceFrames * 1000L / rate
                val percent = if (totalMs > 0L) (doneMs * 100 / totalMs).toInt().coerceIn(0, 99) else 0
                onProgress(percent, doneMs)
            }
            val p = pipeline
            when {
                decoded == null || p == null -> Outcome.FAILED
                !decoded.finished -> Outcome.CANCELLED
                !p.encoder.finish() -> Outcome.FAILED
                else -> {
                    p.encoder.release()
                    val expectedMs = p.sourceFrames * 1000L / p.sourceRate
                    val facts = readBack(out)
                    val verdict = PassageSwap.verify(facts, expectedMs, p.format.sampleRate, p.format.channels)
                    if (verdict == PassageSwap.Verdict.OK) Outcome.DONE else Outcome.FAILED
                }
            }
        } catch (_: ConvertNotAgreed) {
            Outcome.NEEDS_CONSENT
        } catch (_: IOException) {
            if (cancelled()) Outcome.CANCELLED else if (lowOnSpace(out)) Outcome.NO_SPACE else Outcome.FAILED
        } catch (_: Throwable) {
            if (cancelled()) Outcome.CANCELLED else Outcome.FAILED
        } finally {
            pipeline?.encoder?.release()
            probe.delete()
        }
        if (outcome != Outcome.DONE) {
            out.delete()
            return Result(outcome)
        }
        val p = checkNotNull(pipeline)
        return Result(Outcome.DONE, p.format, p.sourceFrames * 1000L / p.sourceRate, p.loudness)
    }

    private fun lowOnSpace(file: File): Boolean =
        (file.parentFile?.usableSpace ?: 0L) < LOW_SPACE_BYTES

    /**
     * Builds the pipeline for a recording that decodes to [rate] and
     * [channels]: the new file's format, the codec's delay measured on the
     * probe, and the encoder. Null when the delay cannot be measured (the
     * encoder gave the probe back unrecognisably, or not at all).
     */
    private fun setUp(
        rate: Int,
        channels: Int,
        sourceBitRate: Int,
        startMs: Long,
        endMs: Long,
        out: File,
        probe: File,
        cancelled: () -> Boolean
    ): Pipeline? {
        val format = PassageAudio.formatFor(rate, channels, sourceBitRate)
        val delay = measureDelay(format, probe, cancelled) ?: return null
        val encoder = AdtsEncoder(out, format)
        return Pipeline(
            format = format,
            sourceRate = rate,
            sourceChannels = channels,
            silence = PassageAudio.Silence.of(startMs, endMs, format.sampleRate),
            delayFrames = delay,
            encoder = encoder,
            loudness = Waveform.collector()
        )
    }

    /**
     * Encodes PassageAudio.probe as [format] into [file], decodes it back
     * and finds how far it moved. Null when that fails.
     */
    private fun measureDelay(format: PassageAudio.Format, file: File, cancelled: () -> Boolean): Int? {
        val sent = PassageAudio.probe(format.sampleRate)
        val ch = format.channels
        val interleaved = ShortArray(sent.size * ch)
        for (i in sent.indices) {
            val v = (sent[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            for (c in 0 until ch) interleaved[i * ch + c] = v
        }
        val encoder = AdtsEncoder(file, format)
        try {
            encoder.write(interleaved, 0, sent.size)
            if (!encoder.finish()) return null
        } finally {
            encoder.release()
        }
        var back = FloatArray(sent.size * 2)
        var filled = 0
        val decoded = decode(file, cancelled) { pcm, frames, rate, channels ->
            if (rate != format.sampleRate || channels != ch) throw IllegalStateException("probe format")
            if (filled + frames > back.size) back = back.copyOf(maxOf(back.size * 2, filled + frames))
            for (f in 0 until frames) back[filled + f] = pcm[f * channels] / 32768f
            filled += frames
        }
        file.delete()
        if (decoded == null || !decoded.finished) return null
        return PassageAudio.delayOf(sent, back.copyOf(filled))
    }

    /** The new file as MediaExtractor reads it, or null when it cannot. */
    private fun readBack(file: File): PassageSwap.Facts? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
                ?: return null
            PassageSwap.Facts(
                mime = format.getString(MediaFormat.KEY_MIME),
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) / 1000L
                } else {
                    -1L
                }
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * The new timeline: frames in the new file's format, silenced, counted
     * from the recording's first frame, then handed to the encoder less the
     * first [delayFrames], which the codec's delay puts back.
     */
    private class Pipeline(
        val format: PassageAudio.Format,
        val sourceRate: Int,
        val sourceChannels: Int,
        val silence: PassageAudio.Silence,
        val delayFrames: Int,
        val encoder: AdtsEncoder,
        val loudness: LoudnessHistogram
    ) {
        /** Mixed down and resampled when the encoder cannot take the recording's own format. */
        private val resampler = if (format.converts(sourceRate, sourceChannels)) {
            SincResampler(sourceRate, format.sampleRate)
        } else {
            null
        }

        /** Recording frames decoded so far. */
        var sourceFrames = 0L
            private set

        /** Frames of the new timeline so far, and how many of its first were held back. */
        private var frames = 0L
        private var skipped = 0L

        fun push(pcm: ShortArray, count: Int) {
            sourceFrames += count
            val resample = resampler
            val buffer: ShortArray
            val n: Int
            if (resample == null) {
                buffer = pcm
                n = count
            } else {
                val mono = FloatArray(count)
                val ch = sourceChannels.coerceAtLeast(1)
                for (f in 0 until count) {
                    var sum = 0
                    for (c in 0 until ch) sum += pcm[f * ch + c]
                    mono[f] = sum.toFloat() / ch / 32768f
                }
                val out = resample.process(mono)
                buffer = ShortArray(out.size) { (out[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                n = out.size
            }
            if (n == 0) return
            val ch = format.channels
            PassageAudio.apply(buffer, n, ch, frames, silence)
            val level = FloatArray(n)
            for (f in 0 until n) {
                var sum = 0
                for (c in 0 until ch) sum += buffer[f * ch + c]
                level[f] = sum.toFloat() / ch / 32768f
            }
            loudness.add(level)
            frames += n
            var from = 0
            if (skipped < delayFrames) {
                from = minOf(delayFrames - skipped, n.toLong()).toInt()
                skipped += from
            }
            if (from < n) encoder.write(buffer, from, n - from)
        }
    }

    /**
     * Decodes [file]'s first audio track as it is, handing [onPcm]
     * interleaved 16-bit frames with the rate and channel count they are at
     * (which the decoder may change from what the container said, for
     * HE-AAC say). Returns null when there is no audio track; a run that
     * [cancelled] stopped reports itself not finished.
     */
    private fun decode(
        file: File,
        cancelled: () -> Boolean,
        onPcm: (pcm: ShortArray, frames: Int, rate: Int, channels: Int) -> Unit
    ): Source? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var frames = 0L
            var pcm = ShortArray(0)
            while (true) {
                if (cancelled()) return Source(rate, channels, frames, finished = false)
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val read = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (read < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val out = decoder.getOutputBuffer(outIndex)
                        if (out != null) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            val ch = channels.coerceAtLeast(1)
                            val count: Int
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = out.asFloatBuffer()
                                count = fb.remaining() / ch
                                if (pcm.size < count * ch) pcm = ShortArray(count * ch)
                                for (i in 0 until count * ch) {
                                    pcm[i] = (fb.get(i) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                                }
                            } else {
                                val sb = out.asShortBuffer()
                                count = sb.remaining() / ch
                                if (pcm.size < count * ch) pcm = ShortArray(count * ch)
                                sb.get(pcm, 0, count * ch)
                            }
                            if (count > 0) {
                                onPcm(pcm, count, rate, ch)
                                frames += count
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (eos) return Source(rate, channels, frames, finished = true)
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val changed = decoder.outputFormat
                    rate = changed.intOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                    channels = changed.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    encoding = changed.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
            }
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

    private fun MediaFormat.intOr(key: String, fallback: Int): Int = try {
        if (containsKey(key)) getInteger(key) else fallback
    } catch (_: Exception) {
        fallback
    }

    /**
     * The platform AAC-LC encoder writing ADTS frames to a file: blocking,
     * fed and drained on the caller's thread (as ClearVoiceEncoder is), and
     * never dropping a frame. Call [finish] once, then [release] (always).
     */
    private class AdtsEncoder(out: File, private val format: PassageAudio.Format) {

        private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val file: FileOutputStream
        private val stream: BufferedOutputStream
        private val info = MediaCodec.BufferInfo()
        private var framesIn = 0L
        private var written = 0
        private var released = false

        init {
            var opened: FileOutputStream? = null
            try {
                val mediaFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, format.bitRate)
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
                codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                opened = FileOutputStream(out)
                codec.start()
            } catch (e: Throwable) {
                try {
                    opened?.close()
                } catch (_: Throwable) {
                }
                codec.release()
                throw e
            }
            file = checkNotNull(opened)
            stream = BufferedOutputStream(file, 1 shl 16)
        }

        /** Encodes [frames] interleaved frames of [pcm] from frame [from]. */
        fun write(pcm: ShortArray, from: Int, frames: Int) {
            val ch = format.channels
            var at = 0
            var idle = 0
            while (at < frames) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    idle = 0
                    val buffer = codec.getInputBuffer(index)
                    if (buffer == null) {
                        codec.queueInputBuffer(index, 0, 0, presentationUs(), 0)
                    } else {
                        buffer.clear()
                        buffer.order(ByteOrder.nativeOrder())
                        val n = minOf(buffer.remaining() / (2 * ch), frames - at)
                        if (n <= 0) throw IllegalStateException("encoder input buffer too small")
                        buffer.asShortBuffer().put(pcm, (from + at) * ch, n * ch)
                        codec.queueInputBuffer(index, 0, n * 2 * ch, presentationUs(), 0)
                        framesIn += n
                        at += n
                    }
                } else if (++idle > MAX_IDLE) {
                    throw IllegalStateException("encoder stopped taking input")
                }
                drain(endOfStream = false)
            }
        }

        /** Flushes the encoder and closes the file. False when nothing usable was written. */
        fun finish(): Boolean {
            var idle = 0
            while (true) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, presentationUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                if (++idle > MAX_IDLE) return false
                drain(endOfStream = false)
            }
            if (!drain(endOfStream = true)) return false
            stream.flush()
            // On the disk before the swap points the meeting at it.
            file.fd.sync()
            return written > 0
        }

        /** Frees the encoder and closes the file. Safe to call more than once. */
        fun release() {
            if (released) return
            released = true
            try {
                codec.stop()
            } catch (_: Throwable) {
            }
            try {
                codec.release()
            } catch (_: Throwable) {
            }
            try {
                stream.close()
            } catch (_: Throwable) {
            }
        }

        private fun presentationUs(): Long = framesIn * 1_000_000L / format.sampleRate

        private fun drain(endOfStream: Boolean): Boolean {
            var idle = 0
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return true
                        if (++idle > MAX_IDLE) return false
                    }
                    index >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        // The codec-specific data is what the ADTS header says.
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !config) {
                            val encoded = codec.getOutputBuffer(index)
                            if (encoded != null) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                val payload = ByteArray(info.size)
                                encoded.get(payload)
                                val header = PassageAudio.adtsHeader(payload.size, format.sampleRate, format.channels)
                                    ?: throw IllegalStateException("no ADTS header for ${format.sampleRate} Hz")
                                stream.write(header)
                                stream.write(payload)
                                written++
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (eos) return true
                    }
                }
            }
        }
    }

    private const val TIMEOUT_US = 10_000L

    /** 2 s of a codec that neither takes input nor gives output. */
    private const val MAX_IDLE = 200
}
