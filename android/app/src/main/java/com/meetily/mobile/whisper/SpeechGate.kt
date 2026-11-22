package com.meetily.mobile.whisper

/**
 * The decisions of the speech gate, kept free of Android and native types so
 * they are unit-tested.
 *
 * The importer and the live recorder cut audio into chunks at quiet spots and
 * drop a chunk whose loudest 100 ms frame is below an RMS floor. That cheap
 * check stays first. A chunk that passes it is then scored by Silero VAD
 * (one speech probability per 512-sample window at 16 kHz, see [SileroVad])
 * and goes to the recogniser only if it holds speech. Steady noise and typing
 * are loud enough for the RMS check but are not speech, and each such chunk
 * would otherwise cost a recogniser pass, or for Whisper a share of a
 * 30-second encoder window, and could come back as invented text. Music is
 * not counted on: on a host build, synthetic tone chords scored as speech
 * and were kept.
 *
 * The defaults lean towards keeping:
 *  - a window is speech at a probability of [THRESHOLD] or more, and speech
 *    only ends after [MIN_SILENCE_SAMPLES] below [NEG_THRESHOLD], the same
 *    hysteresis whisper.cpp's own segmenter uses;
 *  - a finished region shorter than [MIN_SPEECH_SAMPLES] is a click, not a
 *    word, and does not count;
 *  - a region still open at the end of the audio scored so far counts
 *    whatever its length, since it may be a sentence that carries on;
 *  - every region is widened by [PAD_SAMPLES] each side, and a chunk is kept
 *    when any widened region overlaps it, including one that ended in the
 *    previous chunk, and in the importer one that starts in the next (see
 *    [SpeechGateRun.reachesBack]).
 *
 * The gate only ever removes whole chunks. A kept chunk is passed on
 * unchanged, at the same absolute start sample, so its timestamps, its
 * audio offset, its diarization window and its word timings are exactly
 * what they are with the gate off.
 */
object SpeechGate {

    /**
     * The bundled model in assets/: Silero VAD v6.2.0 in whisper.cpp's ggml
     * form. SileroAssetTest pins its size, hash and window to these.
     */
    const val MODEL_ASSET = "ggml-silero-v6.2.0.bin"
    const val MODEL_BYTES = 885_098L

    const val SAMPLE_RATE = 16_000

    /** Samples per Silero window at 16 kHz (n_window in the bundled model). */
    const val WINDOW = 512

    /**
     * The known-speech clip [selfTestPassed] scores: 16 kHz mono 16-bit
     * little-endian PCM, [PROBE_WINDOWS] whole windows (1.536 s) of
     * whisper.cpp b5907's samples/jfk.wav from 5.8 s. SileroAssetTest pins
     * its size and hash.
     */
    const val PROBE_ASSET = "vad_probe.pcm"
    const val PROBE_WINDOWS = 48
    const val PROBE_BYTES = PROBE_WINDOWS * WINDOW * 2L

    /** Windows of digital silence scored after the probe. */
    const val QUIET_WINDOWS = 16

    const val THRESHOLD = 0.5f

    /** Once in speech, a window counts as silence only below this. */
    const val NEG_THRESHOLD = THRESHOLD - 0.15f

    const val MIN_SPEECH_SAMPLES = SAMPLE_RATE * 250 / 1000
    const val MIN_SILENCE_SAMPLES = SAMPLE_RATE * 100 / 1000
    const val PAD_SAMPLES = SAMPLE_RATE * 300 / 1000

    /** Speech from [start] to [end], absolute samples; [open] when still going. */
    class Region(val start: Long, val end: Long, val open: Boolean)

    /**
     * The hysteresis state machine that turns probabilities into regions,
     * kept from one call to the next so that a run of chunks is read as one
     * timeline. Speech still going at the end of one call carries on into
     * the next with its own start and any silence that had begun, so a
     * quiet speaker who stays between [NEG_THRESHOLD] and [THRESHOLD] across
     * a cut is still in speech after it, as on an uncut stream.
     */
    class Tracker {
        private var inSpeech = false
        private var start = 0L
        private var silenceFrom = -1L

        /** True when speech was still going at the end of the last [feed]. */
        val speaking: Boolean
            get() = inSpeech

        /**
         * The regions that end in [probs] and are long enough to count,
         * then the one still open at its end, if any. One probability per
         * [WINDOW] samples, the first window starting at absolute sample
         * [firstSample], which follows on from the previous call's windows.
         */
        fun feed(probs: FloatArray, firstSample: Long): List<Region> {
            val out = ArrayList<Region>()
            for (i in probs.indices) {
                val p = probs[i]
                val at = firstSample + i.toLong() * WINDOW
                if (p >= THRESHOLD) {
                    if (!inSpeech) {
                        inSpeech = true
                        start = at
                    }
                    silenceFrom = -1L
                    continue
                }
                if (inSpeech && p < NEG_THRESHOLD) {
                    if (silenceFrom < 0) silenceFrom = at
                    if (at - silenceFrom >= MIN_SILENCE_SAMPLES) {
                        if (silenceFrom - start >= MIN_SPEECH_SAMPLES) {
                            out.add(Region(start, silenceFrom, open = false))
                        }
                        inSpeech = false
                        silenceFrom = -1L
                    }
                }
            }
            if (inSpeech) {
                out.add(Region(start, firstSample + probs.size.toLong() * WINDOW, open = true))
            }
            return out
        }
    }

    /**
     * The speech regions in [probs] on their own, one probability per
     * [WINDOW] samples, the first window starting at absolute sample
     * [firstSample].
     */
    fun regions(probs: FloatArray, firstSample: Long): List<Region> =
        Tracker().feed(probs, firstSample)

    /** True when a padded region overlaps the chunk [chunkStart], [chunkEnd). */
    fun keep(regions: List<Region>, chunkStart: Long, chunkEnd: Long): Boolean =
        regions.any { it.start - PAD_SAMPLES < chunkEnd && it.end + PAD_SAMPLES > chunkStart }

    /**
     * Whether a detector's answer can be trusted for [windows] windows: one
     * probability each, every one a number from 0 to 1. Anything else means
     * the native side went wrong, and the gate stops for the run.
     */
    fun usable(probs: FloatArray?, windows: Int): Boolean {
        if (probs == null || probs.size != windows) return false
        for (p in probs) {
            if (!(p >= 0f && p <= 1f)) return false
        }
        return true
    }

    /**
     * The probe asset as samples, or null when it is not [PROBE_BYTES] long.
     */
    fun probeSamples(bytes: ByteArray): FloatArray? {
        if (bytes.size.toLong() != PROBE_BYTES) return null
        return FloatArray(bytes.size / 2) { i ->
            val lo = bytes[2 * i].toInt() and 0xff
            val hi = bytes[2 * i + 1].toInt()
            ((hi shl 8) or lo) / 32768f
        }
    }

    /**
     * The positive control run once per process before the gate is trusted.
     * [usable] only catches answers that are not probabilities at all; a
     * detector that runs but scores wrongly, such as an arm64 kernel that
     * miscomputes the graph, or a compute that stops part-way, which at b5907
     * still reports success and leaves zero or stale probabilities, would
     * otherwise skip every chunk and empty the transcript. So the detector
     * must find speech in at least half of the known-speech probe's windows
     * ([speech], scored from a fresh state) and none in digital silence
     * ([quiet], scored after it from a fresh state). On a host build with
     * the bundled model the probe scores 48 of 48 windows at 0.5 or more and
     * silence stays below 0.01. Failing this, the gate is off and every
     * chunk is transcribed, as without it.
     */
    fun selfTestPassed(speech: FloatArray?, quiet: FloatArray?): Boolean {
        if (!usable(speech, PROBE_WINDOWS) || !usable(quiet, QUIET_WINDOWS)) return false
        val voiced = speech!!.count { it >= THRESHOLD }
        return voiced * 2 >= PROBE_WINDOWS && quiet!!.all { it < NEG_THRESHOLD }
    }
}

/**
 * Scores the windows it is given and returns one probability per window, or
 * null on failure. [reset] asks for a fresh model state; false carries on
 * from the state the previous call left (see [SpeechGateRun] for when).
 */
fun interface SpeechDetector {
    fun probabilities(windows: FloatArray, reset: Boolean): FloatArray?
}

/**
 * The speech gate for one run (one import, or one live recording), fed its
 * chunks in order on one thread.
 *
 * Chunks are made of 0.1 s frames, which do not divide into 512-sample
 * windows, so the samples left over at the end of a chunk are carried into
 * the next one and the window grid runs on across chunks that follow on
 * directly. A chunk that does not start where the last one ended (the RMS
 * check or a full backlog dropped the audio between) starts a fresh grid.
 *
 * The regions are tracked the same way: the [SpeechGate.Tracker] state
 * runs on across chunks that follow on directly, so speech that began
 * before a cut is measured from its real start and ends where it would on
 * the uncut stream, and the last region that ended still pads the chunks
 * after it. A gap starts a fresh tracker.
 *
 * The detector's model state carries into the next chunk only when speech
 * was still going at the end of the last one, as after a cut at the length
 * limit in mid-sentence; otherwise each chunk starts from a fresh state.
 * Silero's state remembers loud audio. Measured on a host build of
 * whisper.cpp b5907 with the bundled model: a quiet speaker (-30 dB) starting
 * 1.5 s after six seconds of fan-like noise or typing was missed for up to
 * six seconds with the state carried over, and found from the first word
 * with a fresh one. Chunks are cut after at least 0.8 s below the RMS floor,
 * so a fresh state starts in silence, where it costs nothing. Resetting in
 * mid-speech does cost: forced every 1.6 s, it lost over a third of a quiet
 * speaker's speech windows.
 *
 * Fails open: if the detector throws or returns anything [SpeechGate.usable]
 * rejects, or the gate itself fails (an OutOfMemoryError for a 28-second
 * chunk's windows, say), [onFailure] runs once and every chunk from then on
 * is kept, which is the behaviour without a gate.
 */
class SpeechGateRun(
    private val detector: SpeechDetector,
    private val onFailure: (String) -> Unit = {}
) {
    /** True once the gate has given up for this run. */
    var failed = false
        private set

    private var nextStart = -1L
    private val carry = FloatArray(SpeechGate.WINDOW)
    private var carryLen = 0
    private var resetPending = true
    private var tracker = SpeechGate.Tracker()

    /** The last finished region that counted, whose padding can reach on. */
    private var lastClosed: SpeechGate.Region? = null

    /** The regions the last [keep] call found, for [reachesBack]. */
    private var found: List<SpeechGate.Region> = emptyList()

    /**
     * True when [audio], 16 kHz mono starting at absolute sample [start],
     * should go to the recogniser.
     */
    fun keep(audio: FloatArray, start: Long): Boolean {
        found = emptyList()
        if (failed || audio.isEmpty()) return true
        return try {
            decide(audio, start)
        } catch (_: Throwable) {
            failed = true
            found = emptyList()
            onFailure("gate failed")
            true
        }
    }

    /**
     * True when the padding of speech the last [keep] call found reaches
     * back into [chunkStart] to [chunkEnd], absolute samples: an earlier
     * chunk that was already skipped because its own windows held no
     * speech. A region found in one chunk is only widened back over the
     * chunk before it once that later chunk has been scored, so a caller
     * that can hold a skipped chunk until then (the importer) asks this,
     * through [sendsHeld], and sends it after all. The live recorder does
     * not hold chunks back, so there the padding before speech never
     * reaches into a chunk that was already decided.
     */
    fun reachesBack(chunkStart: Long, chunkEnd: Long): Boolean =
        !failed && SpeechGate.keep(found, chunkStart, chunkEnd)

    /**
     * True when a chunk the importer skipped and held, [chunkStart] to
     * [chunkEnd] in absolute samples, should go to the recogniser after
     * all, once the chunk after it has been through [keep]: when speech
     * found there [reachesBack] into it, or when the gate gave up while
     * scoring that chunk. With the gate off, or failed one chunk earlier,
     * the held chunk would have been transcribed, so failing open sends it
     * too.
     */
    fun sendsHeld(chunkStart: Long, chunkEnd: Long): Boolean =
        failed || reachesBack(chunkStart, chunkEnd)

    /**
     * True when nothing this run holds reaches [sample], absolute: speech
     * is not going on, and the padding of the last region that ended stops
     * short of it. A run started fresh at [sample] then decides every later
     * chunk as this one would, but for where its 512-sample window grid
     * falls, so the importer only offers such a point to continue a stopped
     * transcription from (TranscriptResume). Also true once the gate has
     * given up, when there is no state left to carry.
     */
    fun restsAt(sample: Long): Boolean {
        if (failed) return true
        if (tracker.speaking) return false
        val last = lastClosed ?: return true
        return last.end + SpeechGate.PAD_SAMPLES <= sample
    }

    private fun decide(audio: FloatArray, start: Long): Boolean {
        if (start != nextStart) {
            carryLen = 0
            tracker = SpeechGate.Tracker()
            lastClosed = null
            resetPending = true
        } else if (!tracker.speaking) {
            resetPending = true
        }
        nextStart = start + audio.size
        val dataStart = start - carryLen
        val total = carryLen + audio.size
        val windows = total / SpeechGate.WINDOW
        if (windows == 0) {
            // Too short to score: nothing to decide on, so keep it, and
            // carry it so the grid stays whole.
            System.arraycopy(audio, 0, carry, carryLen, audio.size)
            carryLen = total
            return true
        }
        val data = FloatArray(windows * SpeechGate.WINDOW)
        System.arraycopy(carry, 0, data, 0, carryLen)
        val used = data.size - carryLen
        System.arraycopy(audio, 0, data, carryLen, used)
        val probs = try {
            detector.probabilities(data, resetPending)
        } catch (_: Throwable) {
            null
        }
        if (!SpeechGate.usable(probs, windows)) {
            failed = true
            onFailure("detection failed")
            return true
        }
        resetPending = false
        carryLen = audio.size - used
        System.arraycopy(audio, used, carry, 0, carryLen)

        // The tracker carries on from the previous chunk's last window, so
        // a region that crosses the cut keeps its start and its hysteresis.
        val regions = tracker.feed(probs!!, dataStart)
        val keep = SpeechGate.keep(listOfNotNull(lastClosed) + regions, start, start + audio.size)
        regions.lastOrNull { !it.open }?.let { lastClosed = it }
        found = regions
        return keep
    }
}
