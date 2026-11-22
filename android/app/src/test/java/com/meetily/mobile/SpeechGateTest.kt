package com.meetily.mobile

import com.meetily.mobile.whisper.SpeechDetector
import com.meetily.mobile.whisper.SpeechGate
import com.meetily.mobile.whisper.SpeechGate.PAD_SAMPLES
import com.meetily.mobile.whisper.SpeechGate.WINDOW
import com.meetily.mobile.whisper.SpeechGateRun
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speech gate's decisions: Silero window probabilities to padded speech
 * regions, which chunks are kept, how 0.1 s frames become 512-sample windows
 * across chunks, and failing open. The detector is faked; the bundled model
 * itself is pinned by SileroAssetTest.
 */
class SpeechGateTest {

    private val frame = SpeechGate.SAMPLE_RATE / 10

    private fun probs(vararg runs: Pair<Float, Int>): FloatArray =
        runs.flatMap { (p, n) -> List(n) { p } }.toFloatArray()

    // ---- regions -------------------------------------------------------

    @Test
    fun silenceHasNoRegions() {
        assertTrue(SpeechGate.regions(FloatArray(100), 0L).isEmpty())
    }

    @Test
    fun speechThenSilenceIsOneClosedRegion() {
        val r = SpeechGate.regions(probs(0f to 3, 0.9f to 10, 0f to 6), 0L)
        assertEquals(1, r.size)
        assertEquals(3L * WINDOW, r[0].start)
        assertEquals(13L * WINDOW, r[0].end)
        assertFalse(r[0].open)
    }

    @Test
    fun regionsAreOnTheAbsoluteTimeline() {
        val r = SpeechGate.regions(probs(0.9f to 10, 0f to 6), 160_000L)
        assertEquals(160_000L, r.single().start)
        assertEquals(160_000L + 10L * WINDOW, r.single().end)
    }

    @Test
    fun aClickShorterThanMinSpeechIsDropped() {
        // 6 windows = 192 ms, under 250 ms.
        assertTrue(SpeechGate.regions(probs(0.9f to 6, 0f to 8), 0L).isEmpty())
        // 8 windows = 256 ms is enough.
        assertEquals(1, SpeechGate.regions(probs(0.9f to 8, 0f to 8), 0L).size)
    }

    @Test
    fun hysteresisKeepsSpeechGoingBetweenTheTwoThresholds() {
        // 0.4 is under the start threshold but over the end one, so it does
        // not end speech; the region ends where the probability drops below
        // 0.35.
        val r = SpeechGate.regions(probs(0.9f to 8, 0.4f to 10, 0f to 6), 0L)
        assertEquals(1, r.size)
        assertEquals(18L * WINDOW, r[0].end)
    }

    @Test
    fun aDipShorterThanMinSilenceDoesNotSplitSpeech() {
        val r = SpeechGate.regions(probs(0.9f to 8, 0.1f to 2, 0.9f to 8, 0f to 6), 0L)
        assertEquals(1, r.size)
        assertEquals(0L, r[0].start)
        assertEquals(18L * WINDOW, r[0].end)
    }

    @Test
    fun speechStillGoingAtTheEndIsOpenWhateverItsLength() {
        val r = SpeechGate.regions(probs(0f to 5, 0.9f to 2), 0L)
        assertEquals(1, r.size)
        assertTrue(r[0].open)
        assertEquals(5L * WINDOW, r[0].start)
        assertEquals(7L * WINDOW, r[0].end)
    }

    @Test
    fun aTrackerFedInPiecesFindsWhatOneCallOverTheWholeFinds() {
        // Every split of the same probabilities gives the same finished
        // regions, and the same open one at the end, as no split at all:
        // the hysteresis, the start of a region and a silence that has
        // begun all carry over.
        val random = java.util.Random(7)
        val levels = floatArrayOf(0f, 0.2f, 0.36f, 0.4f, 0.49f, 0.5f, 0.9f)
        repeat(200) {
            val all = FloatArray(400) { levels[random.nextInt(levels.size)] }
            for (i in all.indices) {
                // Runs, not noise, so that regions of every kind turn up.
                if (i > 0 && random.nextInt(8) != 0) all[i] = all[i - 1]
            }
            val whole = SpeechGate.regions(all, 1_000L)
            val tracker = SpeechGate.Tracker()
            val pieces = ArrayList<SpeechGate.Region>()
            var at = 0
            while (at < all.size) {
                val n = minOf(all.size - at, 1 + random.nextInt(40))
                val found = tracker.feed(all.copyOfRange(at, at + n), 1_000L + at.toLong() * WINDOW)
                at += n
                pieces += found.filter { !it.open || at == all.size }
            }
            assertEquals(whole.map { Triple(it.start, it.end, it.open) },
                pieces.map { Triple(it.start, it.end, it.open) })
        }
    }

    @Test
    fun aRegionCrossingACutKeepsItsStart() {
        val tracker = SpeechGate.Tracker()
        val first = tracker.feed(probs(0f to 4, 0.9f to 5), 0L)
        assertTrue(tracker.speaking)
        assertEquals(4L * WINDOW, first.single().start)
        // 5 + 3 windows = 256 ms in all, enough to count once it ends,
        // though neither part alone is.
        val second = tracker.feed(probs(0.9f to 3, 0f to 6), 9L * WINDOW)
        assertFalse(tracker.speaking)
        assertEquals(4L * WINDOW, second.single().start)
        assertEquals(12L * WINDOW, second.single().end)
        assertFalse(second.single().open)
    }

    // ---- keep ----------------------------------------------------------

    @Test
    fun aChunkIsKeptWhenAPaddedRegionOverlapsIt() {
        val region = listOf(SpeechGate.Region(10_000L, 20_000L, open = false))
        // Overlapping.
        assertTrue(SpeechGate.keep(region, 15_000L, 40_000L))
        // Ended before the chunk, but within the padding.
        assertTrue(SpeechGate.keep(region, 20_000L + PAD_SAMPLES - 1, 60_000L))
        // Ended further back than the padding reaches.
        assertFalse(SpeechGate.keep(region, 20_000L + PAD_SAMPLES, 60_000L))
        // Starts after the chunk, within the padding.
        assertTrue(SpeechGate.keep(region, 0L, 10_000L - PAD_SAMPLES + 1))
        assertFalse(SpeechGate.keep(region, 0L, 10_000L - PAD_SAMPLES))
        assertFalse(SpeechGate.keep(emptyList(), 0L, 1_000_000L))
    }

    @Test
    fun defaultsAreTheConservativeOnes() {
        assertEquals(0.5f, SpeechGate.THRESHOLD, 0f)
        assertEquals(0.35f, SpeechGate.NEG_THRESHOLD, 1e-6f)
        assertEquals(4_000, SpeechGate.MIN_SPEECH_SAMPLES) // 250 ms
        assertEquals(1_600, SpeechGate.MIN_SILENCE_SAMPLES) // 100 ms
        assertEquals(4_800, PAD_SAMPLES) // 300 ms
        assertEquals(512, WINDOW)
    }

    // ---- usable --------------------------------------------------------

    @Test
    fun onlyAFullSetOfProbabilitiesIsUsable() {
        assertTrue(SpeechGate.usable(floatArrayOf(0f, 0.5f, 1f), 3))
        assertFalse(SpeechGate.usable(null, 3))
        assertFalse(SpeechGate.usable(floatArrayOf(0f, 0.5f), 3))
        assertFalse(SpeechGate.usable(floatArrayOf(0f, Float.NaN, 1f), 3))
        assertFalse(SpeechGate.usable(floatArrayOf(0f, 1.5f, 1f), 3))
        assertFalse(SpeechGate.usable(floatArrayOf(-0.1f, 0.5f, 1f), 3))
    }

    // ---- self-test -----------------------------------------------------

    private val probeWindows = SpeechGate.PROBE_WINDOWS
    private val quietWindows = SpeechGate.QUIET_WINDOWS

    @Test
    fun aDetectorThatHearsTheProbeAndNotSilencePasses() {
        // What the host build of the bundled model returns.
        assertTrue(
            SpeechGate.selfTestPassed(
                FloatArray(probeWindows) { 0.99f }, FloatArray(quietWindows) { 0.009f }
            )
        )
        // Half the probe is enough; the first windows of a fresh state are low.
        assertTrue(
            SpeechGate.selfTestPassed(
                probs(0.1f to probeWindows / 2, 0.9f to probeWindows / 2),
                FloatArray(quietWindows)
            )
        )
    }

    @Test
    fun plausibleButWrongAnswersFailTheSelfTest() {
        // All zeros: usable, but the gate would skip every chunk.
        assertFalse(
            SpeechGate.selfTestPassed(FloatArray(probeWindows), FloatArray(quietWindows))
        )
        // Fewer than half the probe's windows heard.
        assertFalse(
            SpeechGate.selfTestPassed(
                probs(0.1f to probeWindows / 2 + 1, 0.9f to probeWindows / 2 - 1),
                FloatArray(quietWindows)
            )
        )
        // Everything is speech, silence included.
        assertFalse(
            SpeechGate.selfTestPassed(
                FloatArray(probeWindows) { 0.6f }, FloatArray(quietWindows) { 0.6f }
            )
        )
        // A compute that stopped part-way leaves the probe's scores behind.
        assertFalse(
            SpeechGate.selfTestPassed(
                FloatArray(probeWindows) { 0.99f },
                probs(0.99f to 1, 0f to quietWindows - 1)
            )
        )
    }

    @Test
    fun unusableAnswersFailTheSelfTest() {
        val quiet = FloatArray(quietWindows)
        val speech = FloatArray(probeWindows) { 0.99f }
        assertFalse(SpeechGate.selfTestPassed(null, quiet))
        assertFalse(SpeechGate.selfTestPassed(speech, null))
        assertFalse(SpeechGate.selfTestPassed(speech.copyOf(probeWindows - 1), quiet))
        assertFalse(SpeechGate.selfTestPassed(speech, quiet.copyOf(quietWindows + 1)))
        assertFalse(
            SpeechGate.selfTestPassed(speech.copyOf().also { it[0] = Float.NaN }, quiet)
        )
    }

    @Test
    fun theProbeIsSigned16BitLittleEndian() {
        assertEquals(null, SpeechGate.probeSamples(ByteArray(4)))
        assertEquals(null, SpeechGate.probeSamples(ByteArray(SpeechGate.PROBE_BYTES.toInt() - 2)))
        val bytes = ByteArray(SpeechGate.PROBE_BYTES.toInt())
        bytes[0] = 0x00; bytes[1] = 0x80.toByte() // -32768
        bytes[2] = 0xff.toByte(); bytes[3] = 0x7f // 32767
        bytes[4] = 0x01; bytes[5] = 0x00 // 1
        bytes[6] = 0xff.toByte(); bytes[7] = 0xff.toByte() // -1
        val samples = SpeechGate.probeSamples(bytes)!!
        assertEquals(probeWindows * WINDOW, samples.size)
        assertEquals(-1f, samples[0], 0f)
        assertEquals(32767f / 32768f, samples[1], 0f)
        assertEquals(1f / 32768f, samples[2], 0f)
        assertEquals(-1f / 32768f, samples[3], 0f)
        assertEquals(0f, samples[4], 0f)
    }

    // ---- one run -------------------------------------------------------

    /**
     * Scores a window as speech when its samples are loud: the test audio is
     * 0.5 where someone speaks and 0 elsewhere.
     */
    private class FakeDetector(var answer: ((FloatArray) -> FloatArray?)? = null) : SpeechDetector {
        val sizes = ArrayList<Int>()
        val resets = ArrayList<Boolean>()

        override fun probabilities(windows: FloatArray, reset: Boolean): FloatArray? {
            sizes += windows.size
            resets += reset
            answer?.let { return it(windows) }
            return FloatArray(windows.size / WINDOW) { w ->
                var sum = 0f
                for (i in 0 until WINDOW) sum += kotlin.math.abs(windows[w * WINDOW + i])
                if (sum / WINDOW > 0.1f) 0.95f else 0.02f
            }
        }
    }

    private fun audio(seconds: Double, speech: Boolean): FloatArray =
        FloatArray(Math.round(seconds * SpeechGate.SAMPLE_RATE).toInt()) { if (speech) 0.5f else 0f }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    @Test
    fun framesBecomeWholeWindowsAndTheStateCarriesOn() {
        val detector = FakeDetector()
        val gate = SpeechGateRun(detector)
        var start = 0L
        // Chunks of whole 0.1 s frames, like the importer cuts: 1600 samples
        // a frame never divides into 512-sample windows.
        for (frames in listOf(17, 23, 280, 16)) {
            val chunk = audio(frames * 0.1, speech = true)
            gate.keep(chunk, start)
            start += chunk.size
        }
        assertTrue(detector.sizes.all { it % WINDOW == 0 })
        assertEquals(start / WINDOW, detector.sizes.sum().toLong() / WINDOW)
        assertEquals(listOf(true, false, false, false), detector.resets)
    }

    @Test
    fun theModelStateCarriesOnOnlyThroughSpeech() {
        // Silero's state remembers loud audio and then misses a quiet
        // speaker, so it starts fresh after every chunk that ended quiet, and
        // carries on only across a cut in mid-speech.
        val detector = FakeDetector()
        val gate = SpeechGateRun(detector)
        var start = 0L
        val chunks = listOf(
            concat(audio(1.0, false), audio(1.0, true)), // cut at the length limit
            concat(audio(1.0, true), audio(1.0, false)), // cut in silence
            concat(audio(1.0, true), audio(1.0, false))
        )
        for (chunk in chunks) {
            assertTrue(gate.keep(chunk, start))
            start += chunk.size
        }
        assertEquals(listOf(true, false, true), detector.resets)
    }

    @Test
    fun aGapStartsAFreshGridAndState() {
        val detector = FakeDetector()
        val gate = SpeechGateRun(detector)
        gate.keep(audio(1.7, true), 0L)
        // The RMS check dropped 1.6 s in between.
        gate.keep(audio(2.0, true), (3.3 * SpeechGate.SAMPLE_RATE).toLong())
        assertEquals(listOf(true, true), detector.resets)
        // No carry from the first chunk went into the second call.
        assertEquals(2 * SpeechGate.SAMPLE_RATE / WINDOW * WINDOW, detector.sizes[1])
    }

    @Test
    fun speechIsKeptAndNoiseOnlyChunksAreSkipped() {
        val gate = SpeechGateRun(FakeDetector())
        val speech = concat(audio(0.5, false), audio(2.0, true), audio(1.0, false))
        val silence = audio(4.0, false)
        assertTrue(gate.keep(speech, 0L))
        assertFalse(gate.keep(silence, speech.size.toLong()))
        assertTrue(gate.keep(speech, (speech.size + silence.size).toLong()))
    }

    @Test
    fun paddingFromSpeechAtTheEndOfOneChunkKeepsTheNext() {
        val gate = SpeechGateRun(FakeDetector())
        // The first chunk ends 0.2 s after its speech; the second chunk holds
        // nothing the detector calls speech. It is within the 300 ms padding.
        val first = concat(audio(1.0, false), audio(1.0, true), audio(0.2, false))
        assertTrue(gate.keep(first, 0L))
        assertTrue(gate.keep(audio(3.0, false), first.size.toLong()))
        // A third quiet chunk is past the padding.
        assertFalse(gate.keep(audio(3.0, false), first.size + 3L * SpeechGate.SAMPLE_RATE))
    }

    @Test
    fun aClickAloneIsSkipped() {
        val gate = SpeechGateRun(FakeDetector())
        val click = concat(audio(1.0, false), audio(0.1, true), audio(1.5, false))
        assertFalse(gate.keep(click, 0L))
    }

    @Test
    fun aChunkCutInMidSpeechIsKeptAndSoIsTheRest() {
        val gate = SpeechGateRun(FakeDetector())
        // A forced cut at the length limit, 50 ms after speech starts.
        val first = concat(audio(27.95, false), audio(0.05, true))
        assertTrue(gate.keep(first, 0L))
        assertTrue(gate.keep(audio(3.0, true), first.size.toLong()))
    }

    /** Answers by window position in the stream, whatever the audio. */
    private fun scripted(score: (Int) -> Float): FakeDetector {
        var next = 0
        return FakeDetector { windows -> FloatArray(windows.size / WINDOW) { score(next++) } }
    }

    @Test
    fun quietSpeechBetweenTheThresholdsCarriesOnPastACut() {
        // A speaker found at 0.9, then trailing off to 0.4 for longer than
        // a whole chunk: under the start threshold, over the end one, so
        // still speech, as it would be on the uncut stream.
        val gate = SpeechGateRun(scripted { if (it < 10) 0.9f else 0.4f })
        val first = audio(28.0, false)
        assertTrue(gate.keep(first, 0L))
        assertTrue(gate.keep(audio(3.0, false), first.size.toLong()))
    }

    @Test
    fun quietSpeechEndingAfterACutPadsOnlyTheChunkItEndsIn() {
        val sec = SpeechGate.SAMPLE_RATE
        // 0.9 at first, 0.4 to the end of the second chunk, then nothing.
        val endOfSpeech = (31L * sec / WINDOW).toInt()
        val gate = SpeechGateRun(scripted {
            when {
                it < 10 -> 0.9f
                it < endOfSpeech -> 0.4f
                else -> 0f
            }
        })
        assertTrue(gate.keep(audio(28.0, false), 0L))
        assertTrue(gate.keep(audio(3.0, false), 28L * sec))
        // Speech ended within the padding of this chunk's start.
        assertTrue(gate.keep(audio(3.0, false), 31L * sec))
        assertFalse(gate.keep(audio(3.0, false), 34L * sec))
    }

    @Test
    fun speechAtTheStartOfAChunkReachesBackIntoTheSkippedOneBefore() {
        val sec = SpeechGate.SAMPLE_RATE
        val chunkWindows = 28 * sec / WINDOW
        val gate = SpeechGateRun(scripted { if (it < chunkWindows) 0f else 0.9f })
        val first = audio(28.0, false)
        assertFalse(gate.keep(first, 0L))
        assertFalse(gate.reachesBack(0L, first.size.toLong()))
        assertTrue(gate.keep(audio(3.0, false), first.size.toLong()))
        // The 300 ms before the speech lies in the first chunk.
        assertTrue(gate.reachesBack(0L, first.size.toLong()))
    }

    @Test
    fun speechLaterInAChunkDoesNotReachBack() {
        val sec = SpeechGate.SAMPLE_RATE
        // Speech starts 0.5 s into the second chunk, past the padding.
        val speechFrom = (28L * sec + sec / 2) / WINDOW
        val gate = SpeechGateRun(scripted { if (it < speechFrom) 0f else 0.9f })
        val first = audio(28.0, false)
        assertFalse(gate.keep(first, 0L))
        assertTrue(gate.keep(audio(3.0, false), first.size.toLong()))
        assertFalse(gate.reachesBack(0L, first.size.toLong()))
    }

    @Test
    fun nothingReachesBackOnceTheGateHasFailed() {
        val sec = SpeechGate.SAMPLE_RATE
        val chunkWindows = 28 * sec / WINDOW
        var calls = 0
        val gate = SpeechGateRun(FakeDetector { _ ->
            if (calls++ == 0) FloatArray(chunkWindows) else null
        })
        val first = audio(28.0, false)
        assertFalse(gate.keep(first, 0L))
        assertTrue(gate.keep(audio(3.0, true), first.size.toLong()))
        assertTrue(gate.failed)
        assertFalse(gate.reachesBack(0L, first.size.toLong()))
    }

    @Test
    fun aHeldChunkIsSentWhenTheGateFailsOnTheNextOne() {
        // The importer holds a chunk cut at the length limit with no speech
        // in it. If the gate gives up on the next chunk, failing open must
        // send the held one too, as a run with the gate off would have.
        val sec = SpeechGate.SAMPLE_RATE
        val chunkWindows = 28 * sec / WINDOW
        var calls = 0
        val gate = SpeechGateRun(FakeDetector { _ ->
            if (calls++ == 0) FloatArray(chunkWindows) else null
        })
        val first = audio(28.0, false)
        assertFalse(gate.keep(first, 0L))
        assertFalse(gate.sendsHeld(0L, first.size.toLong()))
        assertTrue(gate.keep(audio(3.0, true), first.size.toLong()))
        assertTrue(gate.sendsHeld(0L, first.size.toLong()))
    }

    @Test
    fun aHeldChunkGoesOnlyWhereSpeechReachesBackWhileTheGateWorks() {
        val sec = SpeechGate.SAMPLE_RATE
        val chunkWindows = 28 * sec / WINDOW
        val reaching = SpeechGateRun(scripted { if (it < chunkWindows) 0f else 0.9f })
        val first = audio(28.0, false)
        assertFalse(reaching.keep(first, 0L))
        assertTrue(reaching.keep(audio(3.0, false), first.size.toLong()))
        assertTrue(reaching.sendsHeld(0L, first.size.toLong()))

        // Speech 0.5 s into the next chunk, past the padding.
        val speechFrom = (28L * sec + sec / 2) / WINDOW
        val later = SpeechGateRun(scripted { if (it < speechFrom) 0f else 0.9f })
        assertFalse(later.keep(first, 0L))
        assertTrue(later.keep(audio(3.0, false), first.size.toLong()))
        assertFalse(later.failed)
        assertFalse(later.sendsHeld(0L, first.size.toLong()))
    }

    @Test
    fun aKeptChunkIsPassedOnUntouched() {
        val gate = SpeechGateRun(FakeDetector())
        val chunk = concat(audio(0.3, false), audio(1.4, true))
        val copy = chunk.copyOf()
        assertTrue(gate.keep(chunk, 48_000L))
        assertArrayEquals(copy, chunk, 0f)
    }

    @Test
    fun tooShortToScoreIsKeptAndCarried() {
        val detector = FakeDetector()
        val gate = SpeechGateRun(detector)
        assertTrue(gate.keep(audio(0.02, false), 0L)) // 320 samples
        assertTrue(detector.sizes.isEmpty())
        gate.keep(audio(0.1, false), 320L)
        // 320 carried + 1600 = 1920 samples: three whole windows.
        assertEquals(listOf(3 * WINDOW), detector.sizes)
        assertEquals(listOf(true), detector.resets)
    }

    @Test
    fun aFailedDetectionFailsOpenForTheRestOfTheRun() {
        var failures = 0
        val detector = FakeDetector { null }
        val gate = SpeechGateRun(detector) { failures++ }
        assertTrue(gate.keep(audio(3.0, false), 0L))
        assertTrue(gate.failed)
        detector.answer = null
        assertTrue(gate.keep(audio(3.0, false), 48_000L))
        assertEquals(1, detector.sizes.size) // never asked again
        assertEquals(1, failures)
    }

    @Test
    fun aThrowingOrMisshapenDetectorFailsOpen() {
        val throwing = SpeechGateRun(FakeDetector { throw UnsatisfiedLinkError("vadDetect") })
        assertTrue(throwing.keep(audio(3.0, false), 0L))
        assertTrue(throwing.failed)

        val short = SpeechGateRun(FakeDetector { FloatArray(it.size / WINDOW - 1) })
        assertTrue(short.keep(audio(3.0, false), 0L))
        assertTrue(short.failed)

        val nan = SpeechGateRun(FakeDetector { FloatArray(it.size / WINDOW) { Float.NaN } })
        assertTrue(nan.keep(audio(3.0, false), 0L))
        assertTrue(nan.failed)
    }

    @Test
    fun theImportersFramesAndCutsGiveTheSameDecisionsAsWholeChunks() {
        // Frames of 0.1 s grouped into chunks at arbitrary points: whatever the
        // cut points, the windows the detector sees cover the stream once, in
        // order, so a speech region lands where it is in the stream.
        val stream = concat(audio(4.0, false), audio(1.5, true), audio(6.0, false))
        val cuts = listOf(0, 9 * frame, 31 * frame, 59 * frame, stream.size)
        val gate = SpeechGateRun(FakeDetector())
        val kept = cuts.zipWithNext().map { (a, b) ->
            gate.keep(stream.copyOfRange(a, b), a.toLong())
        }
        // 0-0.9 s and 0.9-3.1 s are silence (speech starts at 4.0 s, beyond
        // the padding); 3.1-5.9 s holds it; 5.9 s onwards starts 0.4 s after
        // it ends, past the padding.
        assertEquals(listOf(false, false, true, false), kept)
        // A cut 0.2 s after the speech instead: the padding keeps the rest.
        val gate2 = SpeechGateRun(FakeDetector())
        val cuts2 = listOf(0, 31 * frame, 57 * frame, stream.size)
        val kept2 = cuts2.zipWithNext().map { (a, b) ->
            gate2.keep(stream.copyOfRange(a, b), a.toLong())
        }
        assertEquals(listOf(false, true, true), kept2)
    }
}
