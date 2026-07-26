package com.meetily.mobile.whisper

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Continuous on-device transcription with whisper.cpp.
 *
 * Captures 16 kHz mono float PCM from the microphone, cuts speech chunks at
 * trailing silences (energy-based), and transcribes each chunk on a single
 * background executor so audio capture never blocks.
 */
class WhisperRecorder(
    private val modelPath: String,
    private val languageHint: String?, // "auto" => detect on the first chunk
    /** Whisper translate task: any spoken language comes out as English text. */
    private val translate: Boolean = false,
    /** Custom-vocabulary glossary (see Vocab.promptFor); null = none. */
    private val vocabPrompt: String? = null,
    /** NeMo engine (Parakeet/Nemotron); when set, whisper is not loaded and
     *  chunks go through sherpa-onnx instead. */
    private val nemoEngine: NemoEngine? = null,
    // Capture tuning (see CaptureTuning): how firmware pre-processes the mic
    // signal, and which physical input to prefer (null = system routing).
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    private val preferredDevice: AudioDeviceInfo? = null,
    // Overrides mic capture entirely (e.g. AudioPlaybackCapture for device
    // audio). Invoked on the audio thread; null result = capture unavailable.
    private val recordFactory: (() -> AudioRecord?)? = null,
    // Sampled at chunk-cut time so speaker attribution reflects when the
    // words were SPOKEN, not when transcription finishes seconds later.
    private val speakerSupplier: () -> String? = { null },
    // Optional acoustic diarization: given the chunk's raw audio, returns a
    // speaker-cluster id. Invoked on the transcriber thread.
    private val chunkLabeler: ((FloatArray) -> Int?)? = null,
    // Optional tee of every captured (non-paused) frame, e.g. into the
    // meeting-audio writer. Called on the audio thread; must not block.
    private val frameSink: ((FloatArray) -> Unit)? = null,
    // (text, speaker, clusterId, audioMs, words) — audioMs is the chunk's
    // start offset within the captured (non-paused) audio timeline; words
    // carry word-start offsets within the chunk for tap-to-seek.
    private val onSegment: (
        String, String?, Int?, Long, List<com.meetily.mobile.data.WordStamp>
    ) -> Unit,
    private val onProcessingChange: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val sampleRate = 16_000
    private val frameSize = sampleRate / 10 // 100 ms

    private val minChunkSec = 1.6f
    private val maxChunkSec = 28f
    private val endSilenceSec = 0.8f
    private val silenceRms = 0.008f
    private val minSpeechRms = 0.012f

    @Volatile private var running = false
    @Volatile private var paused = false
    private var audioThread: Thread? = null
    private val transcriber = Executors.newSingleThreadExecutor()

    /** Ordering guard shared by finish()/destroy(); see [TeardownGate]. */
    private val gate = TeardownGate()
    @Volatile private var contextPtr = 0L
    @Volatile private var pendingJobs = 0
    // Live capture is realtime and its input is unrecoverable if it falls
    // behind, so it is sized first and never yields to batch work.
    private val nThreads = com.meetily.mobile.data.HeavyWork.recordingThreads()

    /**
     * The language passed to whisper. Starts as [languageHint] (null means
     * auto-detect) and is pinned to whatever whisper reports after the first
     * successful chunk — auto-detect costs a complete extra encoder pass on
     * every call, and live capture has no batching to spread it over.
     */
    @Volatile private var language: String? = languageHint

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start() {
        if (running) return
        running = true
        audioThread = Thread {
            if (nemoEngine == null) {
                if (!WhisperBridge.load()) {
                    onError("Whisper runtime unavailable on this device")
                    running = false
                    return@Thread
                }
                contextPtr = WhisperBridge.initContext(modelPath)
                if (contextPtr == 0L) {
                    onError("Could not load the Whisper model")
                    running = false
                    return@Thread
                }
            }

            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            val record = try {
                if (recordFactory != null) {
                    recordFactory.invoke() ?: run {
                        onError("Device-audio capture unavailable")
                        running = false
                        return@Thread
                    }
                } else {
                    AudioRecord(
                        audioSource,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT,
                        max(minBuffer, frameSize * 4 * 4)
                    )
                }
            } catch (e: Exception) {
                onError("Microphone unavailable: ${e.message}")
                running = false
                return@Thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onError("Microphone unavailable")
                record.release()
                running = false
                return@Thread
            }
            if (preferredDevice != null && recordFactory == null) {
                // Best-effort: falls back to system routing if it fails.
                try {
                    record.preferredDevice = preferredDevice
                } catch (_: Throwable) {
                }
            }

            record.startRecording()
            val frame = FloatArray(frameSize)
            var chunk = FloatArray(0)
            var silenceRun = 0f
            var chunkPeakRms = 0f
            var capturedSamples = 0L // non-paused samples fed downstream

            fun cutChunk() {
                if (chunk.isEmpty()) return
                val audio = chunk
                val peak = chunkPeakRms
                val startMs = (capturedSamples - chunk.size) * 1000 / sampleRate
                chunk = FloatArray(0)
                silenceRun = 0f
                chunkPeakRms = 0f
                if (peak < minSpeechRms) return // never contained speech
                submitChunk(audio, speakerSupplier(), startMs)
            }

            while (running) {
                val n = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                if (paused) {
                    // Keep draining the mic but throw the audio away.
                    chunk = FloatArray(0)
                    silenceRun = 0f
                    chunkPeakRms = 0f
                    continue
                }
                frameSink?.invoke(frame.copyOf(n))
                val rms = rmsOf(frame, n)
                chunkPeakRms = max(chunkPeakRms, rms)
                silenceRun = if (rms < silenceRms) silenceRun + 0.1f else 0f

                val grown = chunk.copyOf(chunk.size + n)
                System.arraycopy(frame, 0, grown, chunk.size, n)
                chunk = grown
                capturedSamples += n

                val chunkSec = chunk.size.toFloat() / sampleRate
                if ((chunkSec >= minChunkSec && silenceRun >= endSilenceSec) ||
                    chunkSec >= maxChunkSec
                ) {
                    cutChunk()
                }
            }

            record.stop()
            record.release()
            cutChunk() // flush whatever was in flight
        }.apply { start() }
    }

    private fun submitChunk(audio: FloatArray, speaker: String?, audioMs: Long) {
        // A chunk can reach here just as teardown shuts the executor down —
        // submitting then throws RejectedExecutionException on the audio
        // thread and takes the process with it. Drop it instead.
        if (gate.isClaimed) return
        pendingJobs++
        onProcessingChange(true)
        try {
            transcriber.execute {
                try {
                    val padded = if (audio.size < sampleRate * 12 / 10) {
                        audio.copyOf(sampleRate * 12 / 10)
                    } else {
                        audio
                    }
                    val clusterId = try {
                        chunkLabeler?.invoke(audio)
                    } catch (_: Throwable) {
                        null
                    }
                    val decoded = if (nemoEngine != null) {
                        nemoEngine.transcribe(padded)
                    } else {
                        val ptr = contextPtr
                        if (ptr != 0L) {
                            val raw = WhisperBridge.transcribeWords(
                                ptr, padded, language, nThreads, translate, vocabPrompt
                            )
                            // Auto-detect costs a complete extra encoder pass
                            // on EVERY call, and live capture has no batching
                            // to spread it over — so pay it once on the first
                            // chunk and pin the answer for the rest.
                            if (language == "auto") {
                                WhisperBridge.lastLanguage(ptr)
                                    ?.takeIf { it.isNotBlank() && it != "auto" }
                                    ?.let { language = it }
                            }
                            WhisperBridge.parseWords(raw)
                        } else {
                            null
                        }
                    }
                    if (decoded != null) {
                        val (text, words) = decoded
                        if (text.isNotBlank() && !isNoise(text)) {
                            onSegment(text.trim(), speaker, clusterId, audioMs, words)
                        }
                    }
                } catch (e: Throwable) {
                    onError("Transcription failed: ${e.message}")
                } finally {
                    pendingJobs--
                    if (pendingJobs <= 0) onProcessingChange(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Teardown won the race; this chunk is not transcribed.
            pendingJobs--
            if (pendingJobs <= 0) onProcessingChange(false)
        }
    }

    /** Whisper emits bracketed placeholders on silence, e.g. [BLANK_AUDIO]. */
    private fun isNoise(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("[") && t.endsWith("]")) ||
            (t.startsWith("(") && t.endsWith(")"))
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /**
     * Stops capture, flushes the final chunk, and runs [onComplete] on the
     * transcriber thread after every pending chunk is transcribed.
     */
    fun finish(onComplete: () -> Unit) {
        running = false
        Thread {
            try {
                audioThread?.join(4000)
            } catch (_: InterruptedException) {
            }
            // Whoever claims teardown owns the shutdown and the native free.
            if (!gate.claim()) {
                // destroy() already tore everything down; still tell the
                // caller we are done, or the finish flow hangs.
                onComplete()
                return@Thread
            }
            val submitted = try {
                transcriber.execute {
                    try {
                        onComplete()
                    } finally {
                        releaseNative()
                    }
                }
                true
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                false
            }
            transcriber.shutdown()
            if (!submitted) {
                onComplete()
                releaseNative()
            }
        }.start()
    }

    /**
     * Immediate teardown without waiting for pending transcriptions.
     *
     * Safe to call after [finish] — and it is, on every normal stop:
     * RecordingService tears the engines down once the finish callback
     * lands. Submitting to the already-shut-down executor threw
     * RejectedExecutionException on the main thread and crashed the app at
     * the end of a recording, so teardown is claimed exactly once and the
     * later caller becomes a no-op.
     */
    fun destroy() {
        running = false
        if (!gate.claim()) return
        try {
            transcriber.execute { releaseNative() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            releaseNative()
        }
        transcriber.shutdown()
    }

    /**
     * Frees the whisper context and the NeMo engine exactly once. Runs on
     * the transcriber thread whenever possible, so it cannot race a
     * transcription that is still using the context.
     */
    private fun releaseNative() = gate.releaseOnce {
        val ptr = contextPtr
        contextPtr = 0L
        if (ptr != 0L) WhisperBridge.freeContext(ptr)
        try {
            nemoEngine?.release()
        } catch (_: Throwable) {
        }
    }

    private fun rmsOf(buffer: FloatArray, n: Int): Float {
        var sum = 0.0
        val count = min(n, buffer.size)
        for (i in 0 until count) {
            sum += buffer[i] * buffer[i]
        }
        return sqrt(sum / max(1, count)).toFloat()
    }
}
