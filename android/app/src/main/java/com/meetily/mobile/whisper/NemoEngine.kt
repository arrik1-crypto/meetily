package com.meetily.mobile.whisper

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.meetily.mobile.data.WordStamp
import java.io.File

/**
 * NVIDIA NeMo transcription through sherpa-onnx: Parakeet TDT via the
 * offline recognizer, Nemotron streaming via the online recognizer. Both
 * plug into the existing VAD-cut chunk pipeline — a chunk of 16 kHz mono
 * float PCM in, (text, word timings) out — so recording, imports,
 * diarization, and tap-to-seek all work unchanged.
 */
class NemoEngine private constructor(
    private val offline: OfflineRecognizer?,
    private val online: OnlineRecognizer?,
    /** See [NemoModel.languageOption]; null for models with no language prompt. */
    private val languageOption: String? = null
) {

    /** Blocking; call from a worker thread. Null on any engine failure. */
    fun transcribe(samples: FloatArray): Pair<String, List<WordStamp>>? {
        return try {
            if (offline != null) {
                val stream = offline.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    offline.decode(stream)
                    val result = offline.getResult(stream)
                    NemoWords.assemble(result.tokens.toList(), result.timestamps)
                } finally {
                    stream.release()
                }
            } else if (online != null) {
                val stream = online.createStream()
                try {
                    // Must be set before any audio: the recognizer reads it
                    // when it builds the decoder's prompt for the first
                    // chunk. Only multilingual checkpoints carry language-tag
                    // tokens, so this is null for everything else.
                    languageOption?.let { stream.setOption("language", it) }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    // Tail padding pushes the last real frames through the
                    // streaming model's lookahead window.
                    stream.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
                    stream.inputFinished()
                    while (online.isReady(stream)) {
                        online.decode(stream)
                    }
                    val result = online.getResult(stream)
                    NemoWords.assemble(result.tokens.toList(), result.timestamps)
                } finally {
                    stream.release()
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        try {
            offline?.release()
        } catch (_: Throwable) {
        }
        try {
            online?.release()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        /**
         * Loads [model] from its downloaded directory. Null when the native
         * runtime is unavailable or the model files don't load — callers
         * treat that exactly like a missing whisper model.
         */
        fun create(context: Context, model: NemoModel, nThreads: Int): NemoEngine? {
            if (!NemoModels.isDownloaded(context, model)) return null
            val dir = NemoModels.dir(context, model)
            val transducerEncoder = File(dir, "encoder.int8.onnx").absolutePath
            val transducerDecoder = File(dir, "decoder.int8.onnx").absolutePath
            val transducerJoiner = File(dir, "joiner.int8.onnx").absolutePath
            val tokens = File(dir, "tokens.txt").absolutePath
            return try {
                if (model.streaming) {
                    val recognizer = OnlineRecognizer(
                        config = OnlineRecognizerConfig(
                            featConfig = FeatureConfig(
                                sampleRate = SAMPLE_RATE, featureDim = 80
                            ),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = transducerEncoder,
                                    decoder = transducerDecoder,
                                    joiner = transducerJoiner
                                ),
                                tokens = tokens,
                                numThreads = nThreads,
                                provider = "cpu"
                            ),
                            // Chunks are already VAD-cut upstream; the
                            // recognizer must not split them further.
                            enableEndpoint = false
                        )
                    )
                    NemoEngine(null, recognizer, model.languageOption)
                } else {
                    val recognizer = OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig = FeatureConfig(
                                sampleRate = SAMPLE_RATE, featureDim = 80
                            ),
                            modelConfig = OfflineModelConfig(
                                transducer = OfflineTransducerModelConfig(
                                    encoder = transducerEncoder,
                                    decoder = transducerDecoder,
                                    joiner = transducerJoiner
                                ),
                                tokens = tokens,
                                modelType = "nemo_transducer",
                                numThreads = nThreads,
                                provider = "cpu"
                            )
                        )
                    )
                    NemoEngine(recognizer, null)
                }
            } catch (_: Throwable) {
                // UnsatisfiedLinkError (jniLibs absent) or bad model files.
                null
            }
        }
    }
}
