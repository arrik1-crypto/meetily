package com.meetily.mobile

import com.k2fsa.sherpa.onnx.QnnConfig
import com.meetily.mobile.whisper.SherpaConfigs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Recap asks sherpa-onnx for. The JNI library reads every field of
 * these config objects, so a re-vendored API whose new fields default to
 * something other than "off" would change decoding silently; this pins the
 * values that matter, including the fields added up to v2.0.3 (HLG graph,
 * QNN backend and its HTP performance mode).
 */
class SherpaConfigsTest {

    private val files = SherpaConfigs.TransducerFiles.inDir(File("/models/parakeet"))

    private fun assertCpuOnly(qnn: QnnConfig) {
        assertEquals("", qnn.backendLib)
        assertEquals("", qnn.contextBinary)
        assertEquals("", qnn.systemLib)
        assertFalse(qnn.htpPerfMode)
    }

    @Test
    fun transducerFilesAreTheInt8Set() {
        assertEquals(File("/models/parakeet/encoder.int8.onnx").absolutePath, files.encoder)
        assertEquals(File("/models/parakeet/decoder.int8.onnx").absolutePath, files.decoder)
        assertEquals(File("/models/parakeet/joiner.int8.onnx").absolutePath, files.joiner)
        assertEquals(File("/models/parakeet/tokens.txt").absolutePath, files.tokens)
    }

    @Test
    fun offlineTransducerIsGreedyNemoOnTheCpu() {
        val config = SherpaConfigs.offlineTransducer(files, nThreads = 4)
        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals("", config.hotwordsFile)
        assertEquals("", config.ruleFsts)
        assertEquals("", config.ruleFars)
        assertEquals(0f, config.blankPenalty)
        // v2.0.0: an HLG graph switches CTC decoding; none for a transducer.
        assertEquals("", config.ctcFstDecoderConfig.graph)

        val model = config.modelConfig
        assertEquals("nemo_transducer", model.modelType)
        assertEquals("cpu", model.provider)
        assertEquals(4, model.numThreads)
        assertFalse(model.debug)
        assertEquals(files.tokens, model.tokens)
        assertEquals(files.encoder, model.transducer.encoder)
        assertEquals(files.decoder, model.transducer.decoder)
        assertEquals(files.joiner, model.transducer.joiner)
        assertCpuOnly(model.transducer.qnnConfig)
        assertEquals("", config.hr.lexicon)
    }

    @Test
    fun onlineTransducerHasNoEndpointing() {
        val config = SherpaConfigs.onlineTransducer(files, nThreads = 2)
        // Chunks arrive VAD-cut; the recognizer must not cut them again.
        assertFalse(config.enableEndpoint)
        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals("", config.hotwordsFile)
        assertEquals("", config.ctcFstDecoderConfig.graph)
        assertEquals("", config.lmConfig.model)

        val model = config.modelConfig
        assertEquals("cpu", model.provider)
        assertEquals(2, model.numThreads)
        assertEquals("", model.modelType)
        assertEquals(files.tokens, model.tokens)
        assertEquals(files.encoder, model.transducer.encoder)
        assertCpuOnly(model.transducer.qnnConfig)
    }

    @Test
    fun speakerEmbeddingRunsOnTwoCpuThreads() {
        val config = SherpaConfigs.speakerEmbedding("/models/speaker.onnx")
        assertEquals("/models/speaker.onnx", config.model)
        assertEquals(2, config.numThreads)
        assertEquals("cpu", config.provider)
        assertFalse(config.debug)
    }

    @Test
    fun configsAreFreshEachTime() {
        // The data classes are mutable (var fields); a shared instance
        // changed by one engine would leak into the next.
        val a = SherpaConfigs.offlineTransducer(files, 1)
        a.modelConfig.numThreads = 8
        assertTrue(SherpaConfigs.offlineTransducer(files, 1).modelConfig.numThreads == 1)
    }
}
