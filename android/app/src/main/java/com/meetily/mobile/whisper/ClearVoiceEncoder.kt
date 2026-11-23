package com.meetily.mobile.whisper

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.meetily.mobile.data.ClearVoice
import java.io.File
import java.nio.ByteOrder

/**
 * Encodes a clear-voice copy: 16 kHz mono float in, AAC-LC in an MP4 (.m4a)
 * out, with the platform encoder configured as MeetingAudioWriter
 * configures it for live recordings (same profile, rate and bitrate).
 *
 * Not MeetingAudioWriter itself, for two reasons. Its write drops a frame
 * when its queue is full, which is right for live capture and wrong for a
 * job that can simply wait. And it writes ADTS, which is what keeps a
 * recording playable after a crash; a copy is written to a part file and
 * renamed into place only when whole, so it can have an MP4 index instead,
 * which gives the player its length without walking every frame.
 *
 * Blocking and single-threaded: [write] feeds the encoder and drains it on
 * the caller's thread. Call [finish] once, then [release] (always).
 */
class ClearVoiceEncoder(out: File) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var samplesIn = 0L
    private var frames = 0
    private var released = false

    init {
        var opened: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, ClearVoice.SAMPLE_RATE, 1
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, ClearVoice.BIT_RATE)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            opened = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
        } catch (e: Throwable) {
            try {
                opened?.release()
            } catch (_: Throwable) {
            }
            codec.release()
            throw e
        }
        muxer = checkNotNull(opened)
    }

    /** Samples written so far. */
    val written: Long get() = samplesIn

    /** Encodes the first [count] samples of [samples]. */
    fun write(samples: FloatArray, count: Int) {
        var at = 0
        var idle = 0
        while (at < count) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                idle = 0
                val buffer = codec.getInputBuffer(index)
                if (buffer == null) {
                    codec.queueInputBuffer(index, 0, 0, presentationUs(), 0)
                } else {
                    buffer.clear()
                    buffer.order(ByteOrder.nativeOrder())
                    val n = minOf(buffer.remaining() / 2, count - at)
                    for (i in 0 until n) {
                        val v = (samples[at + i] * 32767f).toInt().coerceIn(-32768, 32767)
                        buffer.putShort(v.toShort())
                    }
                    codec.queueInputBuffer(index, 0, n * 2, presentationUs(), 0)
                    samplesIn += n
                    at += n
                }
            } else if (++idle > MAX_IDLE) {
                throw IllegalStateException("encoder stopped taking input")
            }
            drain(endOfStream = false)
        }
    }

    /**
     * Flushes the encoder and closes the MP4. Returns false when nothing
     * usable was written (the muxer refuses to close an empty file).
     */
    fun finish(): Boolean {
        var idle = 0
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index, 0, 0, presentationUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                break
            }
            if (++idle > MAX_IDLE) return false
            drain(endOfStream = false)
        }
        if (!drain(endOfStream = true)) return false
        if (!started || frames == 0) return false
        muxer.stop()
        return true
    }

    /** Frees the encoder and the muxer. Safe to call more than once. */
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
            muxer.release()
        } catch (_: Throwable) {
        }
    }

    private fun presentationUs(): Long = samplesIn * 1_000_000L / ClearVoice.SAMPLE_RATE

    /**
     * Moves encoded frames into the muxer. Without [endOfStream] it returns
     * as soon as the encoder has nothing ready; with it, it waits for the
     * end-of-stream frame and returns false if that never comes.
     */
    private fun drain(endOfStream: Boolean): Boolean {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return true
                    if (++idle > MAX_IDLE) return false
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                }
                index >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    // The codec-specific data travels in the track format.
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !config && started) {
                        val encoded = codec.getOutputBuffer(index)
                        if (encoded != null) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                            frames++
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return true
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L

        /** 2 s of an encoder that neither takes input nor gives output. */
        const val MAX_IDLE = 200
    }
}
