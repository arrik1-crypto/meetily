package com.meetily.mobile

import com.meetily.mobile.data.ClearVoice
import com.meetily.mobile.whisper.EnhanceBlocks
import com.meetily.mobile.whisper.OnnxSanity
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the speech-enhancement model bundled in the APK (not a download, so
 * not one of the download pins): GTCRN's gtcrn_simple.onnx as the sherpa-onnx
 * project publishes it in its speech-enhancement-models release, byte for
 * byte the file that release's checksum.txt names.
 *
 * It also reads the metadata sherpa-onnx's GTCRN loader reads, so the rate
 * and frame sizes the clear-voice code assumes are the model's own. A model
 * without them would end the process when it loads.
 */
class SpeechEnhanceAssetTest {

    private fun asset(): File =
        listOf("src/main/assets", "app/src/main/assets", "android/app/src/main/assets")
            .map { File(it, ClearVoice.MODEL_ASSET) }
            .firstOrNull { it.isFile }
            ?: run {
                fail("${ClearVoice.MODEL_ASSET} not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun bundledModelIsTheVerifiedFile() {
        val bytes = asset().readBytes()
        assertEquals(ClearVoice.MODEL_BYTES, bytes.size.toLong())
        assertEquals(
            "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534",
            sha256(bytes)
        )
        // The digest the app checks its copy against is the same one.
        assertEquals(sha256(bytes), ClearVoice.MODEL_SHA256)
    }

    @Test
    fun metadataIsTheGtcrnTheCodeAssumes() {
        val report = OnnxSanity.inspect(OnnxSanity.ArraySource(asset().readBytes()))
        assertNull(report.problem)
        val meta = report.metadata
        // sherpa-onnx v2.0.3 offline-speech-denoiser-gtcrn-model.cc reads
        // each of these, and ends the process when one is missing.
        assertEquals("gtcrn", meta["model_type"])
        assertEquals(ClearVoice.SAMPLE_RATE.toString(), meta["sample_rate"])
        assertEquals("512", meta["n_fft"])
        assertEquals("256", meta["hop_length"])
        assertEquals("512", meta["window_length"])
        assertEquals("hann_sqrt", meta["window_type"])
        assertEquals("1", meta["version"])
        for (key in listOf("conv_cache_shape", "tra_cache_shape", "inter_cache_shape")) {
            assertTrue(key, !meta[key].isNullOrBlank())
        }
        // The blocks never hand the denoiser less than a frame, and run two
        // frames past what they keep.
        val nFft = meta.getValue("n_fft").toInt()
        assertTrue(EnhanceBlocks.MIN_RUN >= nFft)
        assertTrue(EnhanceBlocks.TAIL >= 2 * nFft)
    }
}
