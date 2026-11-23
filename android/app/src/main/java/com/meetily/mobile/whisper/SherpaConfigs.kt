package com.meetily.mobile.whisper

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * The sherpa-onnx configs Recap builds, apart from the code that loads them.
 *
 * Every field the JNI library reads comes from these data classes, and a
 * re-vendored API can add fields whose defaults change behaviour (an HLG
 * graph, a QNN backend, hotwords). Building the configs here, without
 * touching native code, lets SherpaConfigsTest pin what Recap actually asks
 * for: greedy decoding on the CPU, 16 kHz 80-bin features, no endpointing.
 */
object SherpaConfigs {

    const val SAMPLE_RATE = 16_000

    /** The int8 transducer files every NeMo download installs; see NemoModels. */
    data class TransducerFiles(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String
    ) {
        companion object {
            fun inDir(dir: File) = TransducerFiles(
                encoder = File(dir, "encoder.int8.onnx").absolutePath,
                decoder = File(dir, "decoder.int8.onnx").absolutePath,
                joiner = File(dir, "joiner.int8.onnx").absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath
            )
        }
    }

    private fun features() = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)

    /** Parakeet TDT through the offline recognizer. */
    fun offlineTransducer(files: TransducerFiles, nThreads: Int) = OfflineRecognizerConfig(
        featConfig = features(),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = files.encoder,
                decoder = files.decoder,
                joiner = files.joiner
            ),
            tokens = files.tokens,
            modelType = "nemo_transducer",
            numThreads = nThreads,
            provider = "cpu"
        )
    )

    /** Nemotron streaming through the online recognizer. */
    fun onlineTransducer(files: TransducerFiles, nThreads: Int) = OnlineRecognizerConfig(
        featConfig = features(),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = files.encoder,
                decoder = files.decoder,
                joiner = files.joiner
            ),
            tokens = files.tokens,
            numThreads = nThreads,
            provider = "cpu"
        ),
        // Chunks are already VAD-cut upstream; the recognizer must not split
        // them further.
        enableEndpoint = false
    )

    /** A speaker-embedding model (WeSpeaker, 3D-Speaker or NeMo). */
    fun speakerEmbedding(modelPath: String) = SpeakerEmbeddingExtractorConfig(
        model = modelPath,
        numThreads = 2,
        debug = false,
        provider = "cpu"
    )
}
