package com.meetily.mobile

import com.meetily.mobile.whisper.SpeechGate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
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
 */
class SileroAssetTest {

    private fun asset(): File =
        listOf("src/main/assets", "app/src/main/assets", "android/app/src/main/assets")
            .map { File(it, SpeechGate.MODEL_ASSET) }
            .firstOrNull { it.isFile }
            ?: run {
                fail("${SpeechGate.MODEL_ASSET} not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }

    @Test
    fun bundledModelIsTheVerifiedFile() {
        val bytes = asset().readBytes()
        assertEquals(SpeechGate.MODEL_BYTES, bytes.size.toLong())
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals("2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987", sha)
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
