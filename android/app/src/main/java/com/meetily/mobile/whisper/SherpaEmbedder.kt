package com.meetily.mobile.whisper

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor

/**
 * Thin wrapper around sherpa-onnx's speaker-embedding extractor. Everything
 * is guarded: if the native library or model can't load, [create] returns
 * null and recording proceeds without diarization.
 */
class SherpaEmbedder private constructor(
    private val extractor: SpeakerEmbeddingExtractor,
    /** NativeWork tag for the loaded model; ended in [release]. */
    private val workToken: Int = 0
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
        com.meetily.mobile.diag.NativeWork.end(workToken)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        private const val TAG = "SherpaEmbedder"

        /**
         * [modelKey] is the catalogue key (DiarizationModel.key), only for
         * the NativeWork tag that says which model a native crash or memory
         * kill happened in; never a path.
         */
        fun create(modelPath: String, modelKey: String = ""): SherpaEmbedder? {
            // The native constructor cannot fail cleanly: a file onnxruntime
            // cannot parse aborts the process, and one without the metadata
            // the extractor needs leaves a handle whose first use crashes
            // (see OnnxSanity). Only a structurally sound model gets that far.
            // Past it, the vendored class throws when the native handle is 0.
            // The reason is logged, by catalogue key and never by path: the
            // file still counts as downloaded, so without it a corrupt model
            // is speaker detection silently doing nothing on every run.
            OnnxSanity.speakerModelProblem(java.io.File(modelPath))?.let {
                android.util.Log.w(TAG, "speaker model $modelKey rejected: $it")
                return null
            }
            val work = com.meetily.mobile.diag.NativeWork.begin(
                com.meetily.mobile.diag.NativeJob.DIARISE, modelKey
            )
            return try {
                SherpaEmbedder(
                    SpeakerEmbeddingExtractor(config = SherpaConfigs.speakerEmbedding(modelPath)),
                    work
                )
            } catch (_: Throwable) {
                // Covers UnsatisfiedLinkError (jniLibs absent) and the
                // IllegalArgumentException of a native handle of 0.
                com.meetily.mobile.diag.NativeWork.end(work)
                null
            }
        }
    }
}
