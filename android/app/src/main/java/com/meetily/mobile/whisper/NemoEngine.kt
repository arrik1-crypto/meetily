package com.meetily.mobile.whisper

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.meetily.mobile.data.WordStamp

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
    private val languageOption: String? = null,
    /** NativeWork tag for the loaded sessions; ended in [release]. */
    private val workToken: Int = 0
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
        com.meetily.mobile.diag.NativeWork.end(workToken)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        /**
         * Loads [model] from its downloaded directory, told to decode
         * [language] where it takes a language prompt. Null when the native
         * runtime is unavailable or the model files don't load — callers
         * treat that exactly like a missing whisper model.
         */
        fun create(
            context: Context,
            model: NemoModel,
            nThreads: Int,
            /** Pinned spoken language, or null for auto; see NemoModel.languageOptionFor. */
            language: String? = null
        ): NemoEngine? {
            if (!NemoModels.isDownloaded(context, model)) return null
            val files = SherpaConfigs.TransducerFiles.inDir(NemoModels.dir(context, model))
            // Tagged before the sessions are built: the encoder alone is
            // 600+ MB of onnxruntime allocations, where a memory kill lands.
            val work = com.meetily.mobile.diag.NativeWork.begin(
                com.meetily.mobile.diag.NativeJob.TRANSCRIBE, model.key
            )
            return try {
                if (model.streaming) {
                    val recognizer = OnlineRecognizer(
                        config = SherpaConfigs.onlineTransducer(files, nThreads)
                    )
                    NemoEngine(null, recognizer, model.languageOptionFor(language), work)
                } else {
                    val recognizer = OfflineRecognizer(
                        config = SherpaConfigs.offlineTransducer(files, nThreads)
                    )
                    NemoEngine(recognizer, null, workToken = work)
                }
            } catch (_: Throwable) {
                // UnsatisfiedLinkError (jniLibs absent), or model files that
                // do not load: since sherpa-onnx 2.0.0 the native constructor
                // rethrows onnxruntime's error as a Java exception, and the
                // vendored wrapper throws for a native handle of 0.
                com.meetily.mobile.diag.NativeWork.end(work)
                null
            }
        }
    }
}
