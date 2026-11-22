package com.meetily.mobile

import com.meetily.mobile.whisper.SpeechGate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the speech-detection model bundled in the APK (not a download, so not
 * one of the download pins): Silero VAD v6.2.0 in whisper.cpp's ggml form,
 * byte for byte the file whisper.cpp b5907 ships as
 * models/for-tests-silero-v6.2.0-ggml.bin and Hugging Face's
 * ggml-org/whisper-vad publishes as ggml-silero-v6.2.0.bin.
 *
 * It also reads the header whisper_vad_init_with_params reads, so the
 * window size SpeechGate assumes is the one the model was made for.
 *
 * The self-test probe (SpeechGate.PROBE_ASSET) is pinned the same way: a
 * different clip would change what the self-test measures.
 */
class SileroAssetTest {

    private fun asset(name: String = SpeechGate.MODEL_ASSET): File =
        listOf("src/main/assets", "app/src/main/assets", "android/app/src/main/assets")
            .map { File(it, name) }
            .firstOrNull { it.isFile }
            ?: run {
                fail("$name not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun bundledModelIsTheVerifiedFile() {
        val bytes = asset().readBytes()
        assertEquals(SpeechGate.MODEL_BYTES, bytes.size.toLong())
        assertEquals(
            "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
            sha256(bytes)
        )
    }

    /**
     * 24,576 samples (48 whole windows) of whisper.cpp b5907's
     * samples/jfk.wav (sha256 59dfb9a4...7860e), from sample 92,800 (5.8 s),
     * copied out of the WAV's 16-bit data unchanged.
     */
    @Test
    fun bundledProbeIsTheVerifiedClip() {
        val bytes = asset(SpeechGate.PROBE_ASSET).readBytes()
        assertEquals(SpeechGate.PROBE_BYTES, bytes.size.toLong())
        assertEquals(
            "7dc957f57bedc623d8e80798c1e951bdee782227590a410d5de0ef79a65984f7",
            sha256(bytes)
        )
        val samples = SpeechGate.probeSamples(bytes)!!
        assertEquals(SpeechGate.PROBE_WINDOWS * SpeechGate.WINDOW, samples.size)
        // Speech at a normal level, well above the RMS pre-filter's floor.
        val rms = kotlin.math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        assertTrue("probe rms $rms", rms > 0.05)
    }

    @Test
    fun headerIsSilero16kWithTheGatesWindow() {
        val b = ByteBuffer.wrap(asset().readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("ggml magic", 0x67676d6c, b.int)
        val typeLength = b.int
        val type = ByteArray(typeLength).also { b.get(it) }
        assertEquals("silero-16k", String(type, Charsets.US_ASCII))
        assertEquals("6.2.0", "${b.int}.${b.int}.${b.int}")
        assertEquals("n_window", SpeechGate.WINDOW, b.int)
        assertEquals("n_context", 64, b.int)
    }
}
