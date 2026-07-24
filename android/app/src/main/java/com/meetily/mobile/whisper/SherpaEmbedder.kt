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
    private val lock = Any()

    @Volatile private var released = false

    /**
     * Computes a voice embedding for 16 kHz mono float PCM, or null.
     *
     * Serialised against [release]: the recording service frees this from
     * the MAIN thread during teardown, while queued transcription chunks may
     * still be labelling speakers on the transcriber thread. Using the freed
     * native handle is a SIGSEGV — a fatal signal, not a catchable throwable
     * — so the guard has to prevent the call, not catch it.
     */
    fun embed(samples: FloatArray): FloatArray? = synchronized(lock) {
        if (released) return null
        try {
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

    /** Idempotent; blocks on at most one in-flight [embed]. */
    fun release() = synchronized(lock) {
        if (released) return
        released = true
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
