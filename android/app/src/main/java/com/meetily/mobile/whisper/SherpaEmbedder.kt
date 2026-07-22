package com.meetily.mobile.whisper

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/**
 * Thin wrapper around sherpa-onnx's speaker-embedding extractor. Everything
 * is guarded: if the native library or model can't load, [create] returns
 * null and recording proceeds without diarization.
 */
class SherpaEmbedder private constructor(
    private val extractor: SpeakerEmbeddingExtractor
) {
    /** Computes a voice embedding for 16 kHz mono float PCM, or null. */
    fun embed(samples: FloatArray): FloatArray? {
        return try {
            val stream = extractor.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                stream.inputFinished()
                if (extractor.isReady(stream)) extractor.compute(stream) else null
            } finally {
                stream.release()
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        try {
            extractor.release()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        fun create(modelPath: String): SherpaEmbedder? = try {
            SherpaEmbedder(
                SpeakerEmbeddingExtractor(
                    config = SpeakerEmbeddingExtractorConfig(
                        model = modelPath,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )
                )
            )
        } catch (_: Throwable) {
            // Covers UnsatisfiedLinkError (jniLibs absent) and bad models.
            null
        }
    }
}
