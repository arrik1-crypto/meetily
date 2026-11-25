package com.meetily.mobile.whisper

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Continuous on-device transcription with whisper.cpp.
 *
 * Captures 16 kHz mono float PCM from the microphone, cuts speech chunks at
 * trailing silences (energy-based), and transcribes each chunk on a single
 * background executor so audio capture never blocks. With a speech gate
 * ([openSpeechGate]) a chunk with no speech in it is dropped on that
 * executor before the recogniser sees it.
 */
class WhisperRecorder(
    private val modelPath: String,
    /**
     * Catalogue key of the model at [modelPath], only for the NativeWork tag
     * that says which model a native crash or memory kill happened in.
     */
    private val modelKey: String = "",
    private val languageHint: String?, // "auto" => detect on the first chunk
    /** Whisper translate task: any spoken language comes out as English text. */
    private val translate: Boolean = false,
    /** Custom-vocabulary glossary (see Vocab.promptFor); null = none. */
    private val vocabPrompt: String? = null,
    /**
     * Builds the NeMo engine (Parakeet/Nemotron); when set, whisper is not
     * loaded and chunks go through sherpa-onnx instead. Run on the
     * transcriber thread once capture has started, like the whisper
     * context (see [prepareLiveLater]); null from it means the model did not
     * load, which leaves the session capturing only.
     */
    private val nemoLoader: (() -> NemoEngine?)? = null,
    /**
     * Further set-up the live transcription needs before its first chunk
     * (the speaker embedder), run on the transcriber thread right after the
     * model has loaded. Never stops the recording if it fails.
     */
    private val prepare: (() -> Unit)? = null,
    /**
     * False: capture only — no model is loaded, no chunk is accumulated and
     * nothing is transcribed. Frames still reach [frameSink], so the meeting
     * audio is written exactly as before and the whole recording is
     * transcribed afterwards from that file.
     *
     * This is the default way the app records. Transcribing live cost about
     * 40% of a battery per hour, and the after-the-fact pass is more accurate
     * anyway: it sees whole sentences with real context instead of chunks the
     * silence detector happened to cut, and it clusters speakers across the
     * entire recording rather than incrementally.
     *
     * MEASURED, this path: a 44-minute meeting on a Pixel 10 Pro XL took the
     * battery from ~80% (charge-limited) to ~76% — three or four points, so
     * roughly 4-5% an hour. Read error is ±1 point at each end, so treat it
     * as a band of about 3-7%/hour rather than a figure; either way it is
     * close to an order of magnitude below the live path, which is what
     * justifies live transcription being off by default.
     */
    private val transcribe: Boolean = true,
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
    /**
     * Opens the speech gate (SileroVad.open), or null for none. Invoked once,
     * only when transcribing, on the transcriber thread as capture starts and
     * ahead of every chunk (see [openSpeechGateLater]). A null result, or a
     * gate that fails later, leaves every chunk that passes the RMS check
     * going to the recogniser, as without it.
     */
    private val openSpeechGate: (() -> SileroVad?)? = null,
    // Optional tee of every captured (non-paused) frame, e.g. into the
    // meeting-audio writer. Called on the audio thread; must not block.
    private val frameSink: ((FloatArray) -> Unit)? = null,
    /**
     * Per-frame RMS, ~10x a second, on the audio thread. The value is already
     * computed for silence detection, so this costs nothing — and with live
     * transcription off it is the only remaining signal that the microphone
     * is hearing anything, which a recording screen has to be able to show.
     */
    private val onLevel: ((Float) -> Unit)? = null,
    // (text, speaker, clusterId, audioMs, words) — audioMs is the chunk's
    // start offset within the captured (non-paused) audio timeline; words
    // carry word-start offsets within the chunk for tap-to-seek.
    private val onSegment: (
        String, String?, Int?, Long, List<com.meetily.mobile.data.WordStamp>
    ) -> Unit,
    private val onProcessingChange: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    /** The live model loaded; chunks are transcribed from here on. Transcriber thread. */
    private val onLiveReady: (() -> Unit)? = null,
    /**
     * The live model could not be loaded. Capture carries on without it:
     * frames still reach the frame sink, and nothing more is transcribed.
     * Transcriber thread.
     */
    private val onLiveUnavailable: (() -> Unit)? = null,
    /**
     * Capture stopped for good without being asked to: the source could not
     * be opened, a read failed, or it stopped delivering audio. Nothing more
     * reaches the frame sink. Audio thread.
     */
    private val onCaptureEnded: (() -> Unit)? = null
) {
    private val sampleRate = 16_000
    private val frameSize = sampleRate / 10 // 100 ms

    private val minChunkSec = 1.6f
    private val maxChunkSec = 28f
    private val endSilenceSec = 0.8f
    private val silenceRms = 0.008f
    private val minSpeechRms = 0.012f

    /**
     * A blocking read is meant to block, so a run of instant empty returns
     * means the source has gone away rather than gone quiet. Even in the worst
     * case this is a fraction of a second before giving up.
     */
    private val maxEmptyReads = 50

    @Volatile private var running = false
    @Volatile private var paused = false
    private var audioThread: Thread? = null
    private val transcriber = Executors.newSingleThreadExecutor()

    /** Ordering guard shared by finish()/destroy(); see [TeardownGate]. */
    private val gate = TeardownGate()
    @Volatile private var contextPtr = 0L

    /** NativeWork tag for [contextPtr]; published and taken back with it. */
    private var workToken = 0

    /** What [nemoLoader] built; published and taken back like [contextPtr]. */
    @Volatile private var nemoEngine: NemoEngine? = null

    /**
     * Set once the live model failed to load: from then on capture only
     * feeds the frame sink, as with [transcribe] off.
     */
    @Volatile private var liveFailed = false

    /** A frame sink attached after start; see [attachFrameSink]. */
    @Volatile private var lateSink: ((FloatArray) -> Unit)? = null

    /**
     * The speech gate's detector and its state for this recording. Published
     * and taken back under [nativeLock] like [contextPtr]; [speechRun] is
     * only used on the transcriber thread, which sees chunks in order.
     */
    private var speechVad: SileroVad? = null
    @Volatile private var speechRun: SpeechGateRun? = null

    /**
     * Publishing [contextPtr] after a load and taking it back in
     * [releaseNative] are one critical section. Without it a teardown that
     * ran while a model was still loading found nothing to free and marked
     * the gate released, and the pointer the load then stored — hundreds of
     * MB of native heap — was never freed by anyone.
     */
    private val nativeLock = Any()

    // Incremented on the audio thread and decremented on the transcriber, so
    // it has to be atomic: a lost increment left the count stuck above zero
    // and the status on "Processing" for the rest of the meeting.
    private val pendingJobs = AtomicInteger(0)

    /** Samples handed to the transcriber and not yet decoded. */
    private val queuedSamples = AtomicLong(0L)

    /**
     * Live transcription cannot be allowed to fall arbitrarily far behind:
     * every queued chunk is a FloatArray on the Java heap (64 KB a second),
     * and a model slower than realtime grows that for the whole meeting until
     * the process runs out of memory. When the audio is being kept, the words
     * are not lost by stopping — the saved file is transcribed afterwards —
     * so the cap is short. When it is not, dropping a chunk loses its words
     * for good, so the cap only has to keep the heap safe.
     */
    private val maxQueuedSamples =
        (if (frameSink != null) BACKLOG_CAP_KEPT_SEC else BACKLOG_CAP_UNKEPT_SEC) * sampleRate

    /**
     * Set once a chunk was dropped because the transcriber could not keep up.
     * Read by the service to decide whether the live transcript is complete.
     */
    @Volatile var fellBehind = false
        private set

    /**
     * True when live transcription has stopped for good because it fell
     * behind and the saved audio will be transcribed instead.
     */
    val liveSuspended: Boolean get() = fellBehind && frameSink != null

    /** Queued chunks are skipped rather than decoded; see [abandonBacklog]. */
    @Volatile private var abandoned = false
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

    /**
     * The spoken language once known: the pin (or "en" for an English-only
     * model) from the start, Whisper's detected one after its first chunk,
     * null while still on auto. NeMo reports no language, so on that path
     * it stays the pin or null.
     */
    val knownLanguage: String?
        get() = com.meetily.mobile.data.MeetingLanguage.whisperRecorded(language)

    /** True when this session runs Whisper's translate task (English text). */
    val translating: Boolean get() = translate && nemoLoader == null

    /**
     * Starts feeding [sink] every captured frame from now on, for a session
     * that had no frame sink and has to keep its audio after all (the live
     * model failed to load). Ignored when one was given at construction.
     */
    fun attachFrameSink(sink: (FloatArray) -> Unit) {
        lateSink = sink
    }

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start() {
        if (running) return
        running = true
        audioThread = Thread {
            // Every way this thread can end without having been asked to.
            fun captureFailed(message: String) {
                onError(message)
                running = false
                onCaptureEnded?.invoke()
            }

            // Capture opens first and the live model loads behind it, on the
            // transcriber (prepareLiveLater). Loading it here first kept the
            // microphone closed for the seconds a model takes to load while
            // the session's clock already ran, so the opening words were in
            // neither the transcript nor the saved audio; and a model that
            // failed to load returned before any capture, losing the meeting.
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            val record = try {
                if (recordFactory != null) {
                    recordFactory.invoke() ?: run {
                        captureFailed("Device-audio capture unavailable")
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
                captureFailed("Microphone unavailable: ${e.message}")
                return@Thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                captureFailed("Microphone unavailable")
                return@Thread
            }
            if (!running) {
                // Stopped while the source was being opened.
                record.release()
                return@Thread
            }
            if (preferredDevice != null && recordFactory == null) {
                // Best-effort: falls back to system routing if it fails.
                try {
                    record.preferredDevice = preferredDevice
                } catch (_: Throwable) {
                }
            }

            if (transcribe) {
                // The model first, then the gate: every chunk queues behind
                // both, so the words captured while they load are decoded
                // once they are ready rather than lost.
                prepareLiveLater()
                if (openSpeechGate != null) openSpeechGateLater(openSpeechGate)
            }
            record.startRecording()
            val frame = FloatArray(frameSize)
            // Allocated once and filled in place. Growing a fresh array per
            // 100 ms frame copied the whole chunk every time — quadratic in
            // chunk length, and a large-object allocation per frame.
            val chunk = FloatArray((maxChunkSec * sampleRate).toInt() + frameSize)
            var chunkLen = 0
            var silenceRun = 0f
            var chunkPeakRms = 0f
            var capturedSamples = 0L // non-paused samples fed downstream
            // Samples dropped while paused. Only the speech gate's timeline
            // counts them: a pause is a gap in the audio, so the chunk after
            // it must start a fresh window grid and model state rather than
            // carry on from the audio before it. Timestamps and audio offsets
            // stay on the non-paused timeline, as before.
            var pausedSamples = 0L

            fun cutChunk() {
                if (chunkLen == 0) return
                // A copy: the transcriber executor holds on to it while the
                // buffer is refilled.
                val audio = chunk.copyOf(chunkLen)
                val peak = chunkPeakRms
                val startSample = capturedSamples - chunkLen
                val startMs = startSample * 1000 / sampleRate
                chunkLen = 0
                silenceRun = 0f
                chunkPeakRms = 0f
                if (peak < minSpeechRms) return // never contained speech
                submitChunk(audio, speakerSupplier(), startMs, startSample + pausedSamples)
            }

            var emptyReads = 0
            while (running) {
                val n = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    // Negative codes are terminal for this AudioRecord, and
                    // treating them as "nothing to read" turned a dead source
                    // into a tight loop: 100% of a core, forever, while the UI
                    // still said Listening and the transcript quietly stopped
                    // growing. Device-audio capture hits this whenever the
                    // MediaProjection is revoked or handed to another app.
                    onError(readErrorMessage(n))
                    break
                }
                if (n == 0) {
                    // A blocking read yielding nothing means the source has
                    // stopped producing. Tolerate a short run of it rather
                    // than ending a live recording on one hiccup, but do not
                    // spin on it indefinitely.
                    if (++emptyReads > maxEmptyReads) {
                        onError("Audio capture stopped delivering audio")
                        break
                    }
                    continue
                }
                emptyReads = 0
                if (paused) {
                    // Flush what was already captured BEFORE dropping the
                    // rest. Those frames went to frameSink on earlier passes,
                    // so they are in the saved recording — discarding them
                    // here left the last sentence before a pause audible in
                    // playback and absent from the transcript, the summary
                    // and the action items, with nothing to indicate it.
                    // cutChunk() no-ops once the buffer is empty, so the
                    // remaining paused frames cost nothing.
                    cutChunk()
                    pausedSamples += n
                    silenceRun = 0f
                    chunkPeakRms = 0f
                    continue
                }
                (frameSink ?: lateSink)?.invoke(frame.copyOf(n))
                val rms = rmsOf(frame, n)
                onLevel?.invoke(rms)
                if (!transcribe || liveFailed) {
                    // Capture only. The frame is already in the recording; not
                    // accumulating it here is the whole saving — no growing
                    // buffer, no silence tracking, no chunk ever submitted, so
                    // the transcriber executor stays idle for the meeting.
                    // A live model that failed to load lands here too, and
                    // the chunk it was building has nothing to decode it.
                    chunkLen = 0
                    capturedSamples += n
                    continue
                }
                chunkPeakRms = max(chunkPeakRms, rms)
                silenceRun = if (rms < silenceRms) silenceRun + 0.1f else 0f

                System.arraycopy(frame, 0, chunk, chunkLen, n)
                chunkLen += n
                capturedSamples += n

                val chunkSec = chunkLen.toFloat() / sampleRate
                if ((chunkSec >= minChunkSec && silenceRun >= endSilenceSec) ||
                    chunkSec >= maxChunkSec
                ) {
                    cutChunk()
                }
            }

            record.stop()
            record.release()
            cutChunk() // flush whatever was in flight
            // Still running: the loop broke on a dead source, not a stop.
            if (running) onCaptureEnded?.invoke()
        }.apply { start() }
    }

    /**
     * Loads the live model on the transcriber thread, queued ahead of every
     * chunk, so capture is already running while it loads (a 622 MB NeMo
     * encoder takes seconds). Chunks cut meanwhile wait in the queue, under
     * the usual backlog cap, and are decoded once it is ready. A model that
     * does not load leaves the recorder capturing only.
     *
     * Teardown frees what is published here: every release is queued on
     * this executor behind this job (finish and destroy), so it cannot run
     * first. A discarded session skips the load altogether.
     */
    private fun prepareLiveLater() {
        try {
            transcriber.execute {
                if (abandoned) return@execute
                val ready = try {
                    val load = nemoLoader
                    if (load != null) loadNemo(load) else loadWhisper()
                } catch (_: Throwable) {
                    false
                }
                if (!ready) {
                    liveFailed = true
                    onLiveUnavailable?.invoke()
                    return@execute
                }
                try {
                    prepare?.invoke()
                } catch (_: Throwable) {
                }
                onLiveReady?.invoke()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Teardown won the race: there is nothing left to load for.
        }
    }

    private fun loadWhisper(): Boolean {
        if (!WhisperBridge.load()) return false
        // Tagged before the load, where a memory kill would land; released
        // with the context.
        val work = com.meetily.mobile.diag.NativeWork.begin(
            com.meetily.mobile.diag.NativeJob.TRANSCRIBE, modelKey
        )
        val ptr = WhisperBridge.initContext(modelPath)
        if (ptr == 0L) {
            com.meetily.mobile.diag.NativeWork.end(work)
            return false
        }
        val published = synchronized(nativeLock) {
            if (gate.isReleased) {
                false
            } else {
                contextPtr = ptr
                workToken = work
                true
            }
        }
        if (!published) {
            WhisperBridge.freeContext(ptr)
            com.meetily.mobile.diag.NativeWork.end(work)
        }
        return published
    }

    private fun loadNemo(load: () -> NemoEngine?): Boolean {
        val engine = load() ?: return false
        val published = synchronized(nativeLock) {
            if (gate.isReleased) {
                false
            } else {
                nemoEngine = engine
                true
            }
        }
        if (!published) engine.release()
        return published
    }

    /**
     * Opens the speech gate on the transcriber thread, queued before any
     * chunk, so capture starts at once. The open can take a while the first
     * time (loading the library, waiting on the process-state tag, copying
     * the model out of the APK, the self-test), and on the audio thread that
     * audio would be in neither the transcript nor the saved recording. The
     * executor runs jobs in order on one thread, so every chunk queued
     * behind this one sees the gate, or its absence if the open failed.
     */
    private fun openSpeechGateLater(open: () -> SileroVad?) {
        try {
            transcriber.execute {
                // Same hand-off as the whisper context: if teardown has
                // started, nobody would free a detector published now, so
                // this job frees it. A gate that is not there never stops
                // the recording.
                val opened = try {
                    open()
                } catch (_: Throwable) {
                    null
                }
                val published = opened != null && synchronized(nativeLock) {
                    if (gate.isClaimed || !running) {
                        false
                    } else {
                        speechVad = opened
                        speechRun = opened.newRun()
                        true
                    }
                }
                if (!published) opened?.release()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Teardown won the race: there is nothing left to gate.
        }
    }

    /**
     * [gateStart] is the chunk's start on the speech gate's timeline,
     * which unlike [audioMs] also advances through pauses.
     */
    private fun submitChunk(audio: FloatArray, speaker: String?, audioMs: Long, gateStart: Long) {
        // A chunk can reach here just as teardown shuts the executor down —
        // submitting then throws RejectedExecutionException on the audio
        // thread and takes the process with it. Drop it instead.
        if (gate.isClaimed || abandoned) return
        // Once live has been given up in favour of the saved audio, further
        // chunks would only be decoded to be replaced; and with no model
        // loaded there is nothing to decode them.
        if (liveSuspended || liveFailed) return
        if (queuedSamples.get() + audio.size > maxQueuedSamples) {
            fellBehind = true
            return
        }
        queuedSamples.addAndGet(audio.size.toLong())
        pendingJobs.incrementAndGet()
        onProcessingChange(true)
        try {
            transcriber.execute {
                try {
                    // Queued while the model was loading, which then failed.
                    if (abandoned || liveFailed) return@execute
                    // Scored here, not on the audio thread: a 28-second chunk
                    // is 875 windows, a few hundred ms or more of work that
                    // capture cannot wait for. A skipped chunk gets no
                    // speaker label and no segment.
                    if (speechRun?.keep(audio, gateStart) == false) return@execute
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
                    val decoded = if (nemoLoader != null) {
                        nemoEngine?.transcribe(padded)
                    } else {
                        val ptr = contextPtr
                        if (ptr != 0L) {
                            // Encode only as much of the 30 s window as this
                            // chunk needs: live chunks are a few seconds each
                            // and are never batched, so the full window made
                            // every one cost as much as 30 seconds of speech.
                            val raw = WhisperBridge.transcribeWords(
                                ptr, padded, language, nThreads, translate, vocabPrompt,
                                audioCtx = WhisperBridge.liveAudioCtx(padded.size)
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
                    queuedSamples.addAndGet(-audio.size.toLong())
                    if (pendingJobs.decrementAndGet() <= 0) onProcessingChange(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Teardown won the race; this chunk is not transcribed.
            queuedSamples.addAndGet(-audio.size.toLong())
            if (pendingJobs.decrementAndGet() <= 0) onProcessingChange(false)
        }
    }

    /**
     * Skips every chunk still waiting to be transcribed (the one decoding now
     * finishes). For when the saved audio is going to be transcribed anyway,
     * so decoding the backlog would only burn minutes of CPU — after the
     * meeting, without a foreground service — on words that get replaced.
     * The finish/destroy ordering is unchanged: the queued release still runs.
     */
    fun abandonBacklog() {
        abandoned = true
    }

    /** Plain-language cause for a negative AudioRecord.read return. */
    private fun readErrorMessage(code: Int): String = when (code) {
        AudioRecord.ERROR_DEAD_OBJECT ->
            "Audio capture ended — device audio was stopped or taken over"
        AudioRecord.ERROR_INVALID_OPERATION ->
            "Audio capture stopped unexpectedly"
        AudioRecord.ERROR_BAD_VALUE ->
            "Audio capture rejected the read buffer"
        else -> "Audio capture failed (code $code)"
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
        val (ptr, work) = synchronized(nativeLock) {
            val current = contextPtr to workToken
            contextPtr = 0L
            workToken = 0
            current
        }
        if (ptr != 0L) WhisperBridge.freeContext(ptr)
        com.meetily.mobile.diag.NativeWork.end(work)
        val vad = synchronized(nativeLock) {
            val current = speechVad
            speechVad = null
            speechRun = null
            current
        }
        try {
            vad?.release()
        } catch (_: Throwable) {
        }
        val nemo = synchronized(nativeLock) {
            val current = nemoEngine
            nemoEngine = null
            current
        }
        try {
            nemo?.release()
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

    private companion object {
        /** Live backlog cap, in seconds of audio, when the audio is kept. */
        const val BACKLOG_CAP_KEPT_SEC = 90L

        /** Heap safety cap (~38 MB of floats) when nothing else has the audio. */
        const val BACKLOG_CAP_UNKEPT_SEC = 600L
    }
}
