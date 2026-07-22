package com.meetily.mobile.whisper

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Encodes the live 16 kHz mono float capture into an ADTS AAC file on its
 * own thread. ADTS (rather than an .m4a container) is deliberate: every
 * frame is self-describing, so a crash mid-meeting still leaves a playable
 * file — matching the transcript's crash-safe incremental saves.
 *
 * Feed [write] from the audio thread (it never blocks; overflow drops the
 * frame rather than stalling capture) and call [finish] once at the end.
 */
class MeetingAudioWriter(private val outFile: File) {

    private val queue = LinkedBlockingQueue<FloatArray>(QUEUE_CAP)
    @Volatile private var running = true
    private var failed = false

    private val thread = Thread {
        try {
            encodeLoop()
        } catch (_: Throwable) {
            failed = true
        }
    }.apply {
        name = "meeting-audio-writer"
        start()
    }

    /** Non-blocking; drops the frame if the encoder can't keep up. */
    fun write(frame: FloatArray) {
        if (!running) return
        queue.offer(frame)
    }

    /**
     * Flushes and closes the file. Blocking (bounded); call off the audio
     * thread. Returns true if the file was written successfully.
     */
    fun finish(): Boolean {
        running = false
        try {
            thread.join(20_000)
        } catch (_: InterruptedException) {
        }
        return !failed && outFile.length() > 0
    }

    private fun encodeLoop() {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var presentationUs = 0L
        var pendingPcm: ShortArray = ShortArray(0)
        var pendingOffset = 0
        var inputDone = false

        BufferedOutputStream(FileOutputStream(outFile), 1 shl 16).use { out ->
            while (true) {
                if (!inputDone) {
                    if (pendingOffset >= pendingPcm.size) {
                        val next = queue.poll(50, TimeUnit.MILLISECONDS)
                        if (next != null) {
                            pendingPcm = toPcm16(next)
                            pendingOffset = 0
                        } else if (!running && queue.isEmpty()) {
                            val inIndex = codec.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, presentationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            }
                        }
                    }
                    if (pendingOffset < pendingPcm.size) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)
                            if (buffer != null) {
                                buffer.order(java.nio.ByteOrder.nativeOrder())
                                val maxShorts = buffer.remaining() / 2
                                val count =
                                    minOf(maxShorts, pendingPcm.size - pendingOffset)
                                for (i in 0 until count) {
                                    buffer.putShort(pendingPcm[pendingOffset + i])
                                }
                                codec.queueInputBuffer(
                                    inIndex, 0, count * 2, presentationUs, 0
                                )
                                pendingOffset += count
                                presentationUs +=
                                    count * 1_000_000L / SAMPLE_RATE
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val isConfig =
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !isConfig) {
                        val encoded = codec.getOutputBuffer(outIndex)
                        if (encoded != null) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            val payload = ByteArray(info.size)
                            encoded.get(payload)
                            out.write(adtsHeader(payload.size))
                            out.write(payload)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (eos) break
                }
            }
            out.flush()
        }
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        codec.release()
    }

    private fun toPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
        }
        return out
    }

    /** 7-byte ADTS header for one AAC-LC frame (16 kHz mono). */
    private fun adtsHeader(payloadLength: Int): ByteArray {
        val frameLength = payloadLength + 7
        val profile = 2 // AAC-LC
        val freqIndex = 8 // 16 kHz
        val channels = 1
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, no CRC
            (((profile - 1) shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or ((frameLength shr 11) and 0x3)).toByte(),
            ((frameLength shr 3) and 0xFF).toByte(),
            (((frameLength and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BIT_RATE = 48_000
        // ~100 ms frames; 300 entries ≈ 30 s of headroom before drops.
        private const val QUEUE_CAP = 300
    }
}
