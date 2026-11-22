package com.meetily.mobile

import com.meetily.mobile.data.BulkActions
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.data.Retention
import com.meetily.mobile.data.TranscriptResume
import com.meetily.mobile.data.TranscriptResume.Action
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.whisper.SpeechDetector
import com.meetily.mobile.whisper.SpeechGate
import com.meetily.mobile.whisper.SpeechGateRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continuing an interrupted transcription from where it stopped: which cut
 * it continues from, where the continued lines and words land, what the
 * seam keeps and drops, when a pass carries on at all, speakers across the
 * seam, and the queued job and meeting fields that carry the point.
 */
class TranscriptResumeTest {

    private val rate = 16_000
    private val frame = 1_600L
    private val base = 1_700_000_000_000L

    private fun seg(
        audioMs: Long,
        text: String,
        words: List<WordStamp>? = null,
        cluster: Int? = null,
        speaker: String? = null
    ) = TranscriptSegment(
        timestampMs = base + audioMs,
        text = text,
        audioMs = audioMs,
        words = words,
        clusterId = cluster,
        speaker = speaker
    )

    // --- Where a stopped pass continues from -------------------------------

    @Test
    fun withNoCutAtSilenceThePassContinuesFromItsOwnStart() {
        val cuts = TranscriptResume.Cuts(0L)
        assertEquals(0L, cuts.pointFor(10 * rate.toLong()))
    }

    @Test
    fun theLatestCutAtSilenceThatEverythingBeforeIsDoneUpToIsChosen() {
        val cuts = TranscriptResume.Cuts(0L)
        cuts.quietCut(20 * frame)
        cuts.quietCut(50 * frame)
        cuts.quietCut(100 * frame)
        // A batch starting at frame 60 is still with Whisper: 50 is the last
        // cut with everything before it transcribed.
        assertEquals(50 * frame, cuts.pointFor(60 * frame))
        // A cut exactly at the frontier is safe: the chunk after it is the
        // unfinished one.
        assertEquals(100 * frame, cuts.pointFor(100 * frame))
    }

    @Test
    fun aCutBeyondWhatIsFinishedWaitsUntilTheWorkBeforeItIsDone() {
        val cuts = TranscriptResume.Cuts(0L)
        cuts.quietCut(30 * frame)
        assertEquals(0L, cuts.pointFor(25 * frame))
        assertEquals(30 * frame, cuts.pointFor(31 * frame))
        // Out-of-order and already-settled cuts are ignored.
        cuts.quietCut(10 * frame)
        cuts.quietCut(30 * frame)
        assertEquals(30 * frame, cuts.pointFor(200 * frame))
    }

    @Test
    fun aContinuationNeverPointsBeforeItsOwnSeam() {
        val seam = 40_000L * 16
        val cuts = TranscriptResume.Cuts(seam)
        cuts.quietCut(1_000L)
        assertEquals(seam, cuts.pointFor(seam))
        assertEquals(seam, cuts.pointFor(seam + 5 * frame))
    }

    @Test
    fun theFrontierIsTheEarliestChunkStillUnfinished() {
        assertEquals(
            64_000L,
            TranscriptResume.frontier(listOf(null, 96_000L, null, 64_000L), 200_000L)
        )
        assertEquals(200_000L, TranscriptResume.frontier(listOf(null, null), 200_000L))
        assertEquals(200_000L, TranscriptResume.frontier(emptyList(), 200_000L))
    }

    @Test
    fun aStopMidBatchContinuesFromTheCutBeforeTheBatchNotAfterIt() {
        // The importer's own sequence: two chunks cut at silence and
        // transcribed, a third batched, then a stop that abandons it.
        val cuts = TranscriptResume.Cuts(0L)
        cuts.quietCut(80 * frame) // chunk 0..80 done
        cuts.quietCut(150 * frame) // chunk 80..150 done
        cuts.quietCut(230 * frame) // chunk 150..230 batched, then abandoned
        val frontier = TranscriptResume.frontier(
            listOf(150 * frame, null, null, null, 150 * frame),
            260 * frame
        )
        assertEquals(150 * frame, cuts.pointFor(frontier))
    }

    // --- Offsets of continued lines and words ---------------------------------

    @Test
    fun samplesAndMillisecondsAgreeAtEveryFrameBoundary() {
        for (frames in listOf(0L, 1L, 37L, 25_331L, 1_000_000L)) {
            val sample = frames * frame
            assertEquals(sample, TranscriptResume.sampleAt(TranscriptResume.msAt(sample, rate), rate))
        }
        // 42:10 into the recording.
        assertEquals(40_480_000L, TranscriptResume.sampleAt(2_530_000L, rate))
        assertEquals(2_530_000L, TranscriptResume.msAt(40_480_000L, rate))
    }

    @Test
    fun decodedAudioBeforeTheSeamIsSkippedToTheSample() {
        assertEquals(200, TranscriptResume.skipBefore(1_000L, 512, 1_200L))
        assertEquals(0, TranscriptResume.skipBefore(1_000L, 512, 900L))
        assertEquals(512, TranscriptResume.skipBefore(1_000L, 512, 2_000L))
        assertEquals(512, TranscriptResume.skipBefore(1_000L, 512, 1_512L))
    }

    @Test
    fun aContinuedLineSitsAtItsPlaceInTheFileAndItsWordsMoveWithIt() {
        val seamMs = 2_530_000L
        // The importer counts samples from the seam, so a chunk three frames
        // after it starts at its absolute sample, and the line is stamped
        // from that exactly as a pass from the top would stamp it.
        val chunkStart = TranscriptResume.sampleAt(seamMs, rate) + 3 * frame
        val audioMs = TranscriptResume.msAt(chunkStart, rate)
        assertEquals(2_530_300L, audioMs)
        val words = listOf(WordStamp(0L, "hello"), WordStamp(420L, "again"))
        val line = seg(audioMs, "hello again", words)
        assertEquals(base + 2_530_300L, line.timestampMs)
        // Word timings stay relative to their line: unchanged by where the
        // pass began, and in the right place in the recording.
        assertEquals(2_530_300L, TranscriptResume.wordAt(line, words[0]))
        assertEquals(2_530_720L, TranscriptResume.wordAt(line, words[1]))
        assertTrue(TranscriptResume.after(line, seamMs, base))
        assertNull(TranscriptResume.wordAt(line.copy(audioMs = null), words[0]))
    }

    // --- The seam -------------------------------------------------------------

    @Test
    fun theSeamKeepsWhatCameBeforeAndTakesTheRestFromTheNewPassOnce() {
        val resumeMs = 60_000L
        val existing = listOf(
            seg(0L, "a"),
            seg(30_000L, "b"),
            // Finished after the last safe cut by the stopped pass.
            seg(60_000L, "c first"),
            seg(85_000L, "d first")
        )
        val resumed = listOf(seg(60_000L, "c"), seg(85_000L, "d"), seg(120_000L, "e"))
        val merged = TranscriptResume.seam(existing, resumed, resumeMs, base)
        assertEquals(listOf("a", "b", "c", "d", "e"), merged.map { it.text })
        // Nothing twice, nothing missing, in order.
        val offsets = merged.map { it.audioMs!! }
        assertEquals(offsets.distinct(), offsets)
        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun anythingTheNewPassPlacesBeforeTheSeamIsDropped() {
        val merged = TranscriptResume.seam(
            listOf(seg(0L, "a")),
            listOf(seg(59_999L, "stray"), seg(60_000L, "b")),
            60_000L, base
        )
        assertEquals(listOf("a", "b"), merged.map { it.text })
    }

    @Test
    fun aLineWithNoAudioOffsetIsPlacedByItsTimestamp() {
        val typed = TranscriptSegment(timestampMs = base + 10_000L, text = "typed")
        val lateTyped = TranscriptSegment(timestampMs = base + 70_000L, text = "late")
        val kept = TranscriptResume.prefix(listOf(typed, lateTyped), 60_000L, base)
        assertEquals(listOf("typed"), kept.map { it.text })
    }

    @Test
    fun editsMadeBeforeTheSeamWhileWaitingAreKept() {
        val edited = seg(10_000L, "Edited by hand", cluster = 2, speaker = "Ana")
            .copy(highlighted = true)
        val kept = TranscriptResume.prefix(listOf(edited, seg(70_000L, "x")), 60_000L, base)
        assertEquals(listOf(edited), kept)
    }

    @Test
    fun aMarkPastWhereTheWordsStopWaitsForThePassThatReachesIt() {
        val marks = listOf(5_000L, 59_999L, 60_000L, 90_000L)
        assertEquals(listOf(5_000L, 59_999L), TranscriptResume.marksReached(marks, 60_000L))
        assertEquals(marks, TranscriptResume.marksReached(marks, null))
    }

    // --- Whether a pass carries on ----------------------------------------------

    @Test
    fun onlyTheModelThatMadeTheWordsCarriesThemOn() {
        assertEquals(60_000L, TranscriptResume.continueFrom(60_000L, "small", "small", "de", null))
        assertNull(TranscriptResume.continueFrom(60_000L, "small", "base", "de", null))
        // Nothing stored, or stopped before its first safe cut: from the top.
        assertNull(TranscriptResume.continueFrom(null, "small", "small", "de", null))
        assertNull(TranscriptResume.continueFrom(0L, "small", "small", "de", null))
        // A transcript that records no model carries on with any.
        assertEquals(60_000L, TranscriptResume.continueFrom(60_000L, null, "base", null, null))
    }

    @Test
    fun aLanguageAskedForMustBeTheOneTheWordsAreIn() {
        fun go(requested: String?, stored: String?) =
            TranscriptResume.continueFrom(60_000L, "small", "small", stored, requested)
        assertEquals(60_000L, go("auto", "de"))
        assertEquals(60_000L, go("", "de"))
        assertEquals(60_000L, go("de", "de"))
        assertEquals(60_000L, go("de-DE", "de"))
        assertNull(go("fr", "de"))
        // The stopped pass settled on nothing: the request decides.
        assertEquals(60_000L, go("fr", null))
    }

    @Test
    fun theContinuationRunsInTheLanguageTheWordsAreIn() {
        assertEquals("de", TranscriptResume.languageFor("de", "auto"))
        assertEquals("auto", TranscriptResume.languageFor(null, "auto"))
        assertNull(TranscriptResume.languageFor(null, null))
    }

    @Test
    fun aQueuedContinuationOfATranscriptFinishedSinceIsDropped() {
        assertFalse(TranscriptResume.stillWanted(true, 60_000L, null))
        assertTrue(TranscriptResume.stillWanted(true, 60_000L, 75_000L))
        // Jobs from before resuming existed, and checks, run as they did.
        assertTrue(TranscriptResume.stillWanted(true, 0L, null))
        assertTrue(TranscriptResume.stillWanted(false, 60_000L, null))
    }

    @Test
    fun continueUsesTheModelThatMadeTheWordsOrAsksToStartOver() {
        fun plan(stored: String?, storedReady: Boolean, current: String, currentReady: Boolean) =
            TranscriptResume.plan(stored, storedReady, current, currentReady)
        assertEquals(TranscriptResume.Plan(Action.CONTINUE, "small"), plan("small", true, "base", true))
        assertEquals(TranscriptResume.Plan(Action.START_OVER, "base"), plan("small", false, "base", true))
        assertEquals(Action.NO_MODEL, plan("small", false, "small", false).action)
        assertEquals(Action.NO_MODEL, plan("small", false, "base", false).action)
        assertEquals(TranscriptResume.Plan(Action.CONTINUE, "base"), plan(null, false, "base", true))
        assertEquals(Action.NO_MODEL, plan(null, false, "base", false).action)
    }

    // --- Speakers across the seam -------------------------------------------------

    @Test
    fun voiceWindowsAreInOrderNeverOverlapAndStopAtTheSeam() {
        val segments = listOf(
            seg(0L, "a", words = listOf(WordStamp(0L, "a"), WordStamp(2_000L, "b"))),
            seg(5_000L, "split one"),
            seg(5_000L, "split two"),
            seg(40_000L, "c", words = listOf(WordStamp(0L, "c"), WordStamp(1_000L, "d"))),
            seg(58_000L, "e"),
            seg(70_000L, "after the seam")
        )
        val windows = TranscriptResume.voiceWindows(segments, 60_000L)
        assertEquals(
            listOf(
                // Ends 1.5 s after its last word, before the next line.
                TranscriptResume.Window(0L, 3_500L, listOf(0)),
                // Two lines from one chunk share one window; no words, so
                // the longest chunk bounds it.
                TranscriptResume.Window(5_000L, 33_000L, listOf(1, 2)),
                TranscriptResume.Window(40_000L, 42_500L, listOf(3)),
                // The seam bounds the last one.
                TranscriptResume.Window(58_000L, 60_000L, listOf(4))
            ),
            windows
        )
        for (i in 1 until windows.size) {
            assertTrue(windows[i - 1].endMs <= windows[i].startMs)
        }
    }

    @Test
    fun aNameGivenBeforeTheSeamCarriesOverOnlyWhereItIsClear() {
        val resumeMs = 60_000L
        val segments = listOf(
            // Voice 1: two of three lines named Ana.
            seg(0L, "a1", cluster = 1, speaker = "Ana"),
            seg(5_000L, "a2", cluster = 1, speaker = "Ana"),
            seg(10_000L, "a3", cluster = 1),
            // Voice 2: one of three named.
            seg(15_000L, "b1", cluster = 2, speaker = "Ben"),
            seg(20_000L, "b2", cluster = 2),
            seg(25_000L, "b3", cluster = 2),
            // Voice 3: two different names.
            seg(30_000L, "c1", cluster = 3, speaker = "Cy"),
            seg(35_000L, "c2", cluster = 3, speaker = "Dee"),
            // After the seam.
            seg(60_000L, "a4", cluster = 1),
            seg(65_000L, "a5", cluster = 1, speaker = "Someone else"),
            seg(70_000L, "b4", cluster = 2),
            seg(75_000L, "c3", cluster = 3),
            seg(80_000L, "none")
        )
        val named = TranscriptResume.carryNames(segments, resumeMs, base)
        assertEquals("Ana", named.first { it.text == "a4" }.speaker)
        // A name already there is never replaced.
        assertEquals("Someone else", named.first { it.text == "a5" }.speaker)
        assertNull(named.first { it.text == "b4" }.speaker)
        assertNull(named.first { it.text == "c3" }.speaker)
        assertNull(named.first { it.text == "none" }.speaker)
        // Lines before the seam are left exactly as the user left them.
        assertEquals(segments.take(8), named.take(8))
    }

    // --- The speech gate at a cut ---------------------------------------------------

    /** Speech for the first [speechWindows] windows of the stream, then none. */
    private class Scripted(private val speechWindows: Int) : SpeechDetector {
        private var next = 0
        override fun probabilities(windows: FloatArray, reset: Boolean): FloatArray =
            FloatArray(windows.size / SpeechGate.WINDOW) { if (next++ < speechWindows) 0.9f else 0f }
    }

    @Test
    fun theGateRestsAtACutOnlyWhenNothingOfItsReachesPastIt() {
        val sec = SpeechGate.SAMPLE_RATE
        // About a second of speech, then quiet, in a 3 s chunk.
        val gate = SpeechGateRun(Scripted(speechWindows = 31))
        assertTrue(gate.keep(FloatArray(3 * sec), 0L))
        assertTrue(gate.restsAt(3L * sec))
        // The padding after the speech still reaches just after it.
        assertFalse(gate.restsAt(sec.toLong() + sec / 10))

        // Still speaking when the chunk ends: never a place to continue from.
        val speaking = SpeechGateRun(Scripted(speechWindows = 10_000))
        speaking.keep(FloatArray(3 * sec), 0L)
        assertFalse(speaking.restsAt(3L * sec))

        // A gate that has heard nothing holds nothing.
        assertTrue(SpeechGateRun(Scripted(0)).restsAt(0L))
    }

    @Test
    fun mp3IsDecodedFromTheTopRatherThanSought() {
        assertFalse(TranscriptResume.seekable("audio/mpeg"))
        assertFalse(TranscriptResume.seekable("AUDIO/MPEG"))
        assertFalse(TranscriptResume.seekable(null))
        assertTrue(TranscriptResume.seekable("audio/mp4a-latm"))
        assertTrue(TranscriptResume.seekable("audio/raw"))
        assertTrue(TranscriptResume.seekable("audio/opus"))
    }

    @Test
    fun whereATranscriptStoppedReadsLikeATimeInTheRecording() {
        assertEquals("42:10", TranscriptResume.clock(2_530_000L))
        assertEquals("1:05:00", TranscriptResume.clock(3_900_000L))
        assertEquals("0:00", TranscriptResume.clock(0L))
        assertEquals("0:00", TranscriptResume.clock(-5L))
        assertEquals("0:59", TranscriptResume.clock(59_999L))
    }

    // --- The queued job ---------------------------------------------------------------

    @Test
    fun aQueuedContinuationRoundTripsWithItsPoint() {
        val job = JobQueue.Job(
            JobQueue.KIND_CHECK, "m1", "small", queuedAtMs = 5L,
            chargingOnly = true, firstTranscript = true, required = true,
            language = "de", resumeFromMs = 2_530_000L
        )
        val plain = JobQueue.Job(JobQueue.KIND_SUMMARY, "m2", "default", queuedAtMs = 6L)
        val import = JobQueue.Job(
            JobQueue.KIND_IMPORT, "", "base", queuedAtMs = 7L,
            stagedFile = "staged.m4a", sourceName = "Call.m4a"
        )
        val jobs = listOf(job, plain, import)
        assertEquals(jobs, JobQueue.decode(JobQueue.encode(jobs)))
    }

    @Test
    fun aJobQueuedBeforeResumingExistedStartsFromTheBeginning() {
        // A row exactly as 3.18.0 wrote it.
        val raw = """[{"kind":"check","meetingId":"m1","payload":"small","queuedAtMs":5,
            "interrupted":false,"stagedFile":"","sourceName":"","chargingOnly":true,
            "firstTranscript":true,"required":true,"auto":false,"language":"de"}]"""
        val job = JobQueue.decode(raw).single()
        assertEquals(0L, job.resumeFromMs)
        assertEquals("small", job.payload)
        assertTrue(job.chargingOnly && job.firstTranscript && job.required)
        assertEquals("de", job.language)
    }

    @Test
    fun aJobWithoutAPointWritesNothingNewAndABadOneReadsAsZero() {
        val plain = JobQueue.Job(JobQueue.KIND_CHECK, "m1", "small", queuedAtMs = 1L)
        assertFalse(JobQueue.encode(listOf(plain)).contains("resumeFromMs"))
        val negative = """[{"kind":"check","meetingId":"m1","resumeFromMs":-40}]"""
        assertEquals(0L, JobQueue.decode(negative).single().resumeFromMs)
        assertTrue(JobQueue.decode("not json").isEmpty())
    }

    @Test
    fun aRequeuedContinuationReplacesTheRunMarkerAndKeepsItsPoint() {
        val marker = JobQueue.Job(
            JobQueue.KIND_CHECK, "m1", "small", queuedAtMs = 1L, interrupted = true,
            firstTranscript = true
        )
        val cleared = JobQueue.removeInterrupted(listOf(marker), JobQueue.KIND_CHECK, "m1")
        val requeued = JobQueue.add(
            cleared,
            JobQueue.Job(
                JobQueue.KIND_CHECK, "m1", "small", queuedAtMs = 2L, chargingOnly = true,
                firstTranscript = true, required = true, resumeFromMs = 2_530_000L
            )
        )
        assertEquals(2_530_000L, requeued.single().resumeFromMs)
        assertEquals(null, JobQueue.nextRunnable(requeued, charging = false))
        assertEquals(2_530_000L, JobQueue.nextRunnable(requeued, charging = true)?.resumeFromMs)
    }

    // --- The meeting field ---------------------------------------------------------------

    @Test
    fun theResumePointRoundTripsAndOldOrCompleteMeetingsHaveNone() {
        val m = Meeting(id = "m1", title = "Call", createdAtMs = base)
        m.transcriptResumeMs = 2_530_000L
        assertEquals(2_530_000L, Meeting.fromJson(m.toJson()).transcriptResumeMs)
        val complete = Meeting(id = "m2", title = "Done", createdAtMs = base).toJson()
        assertFalse(complete.has("transcriptResumeMs"))
        assertNull(Meeting.fromJson(complete).transcriptResumeMs)
        val negative = complete.put("transcriptResumeMs", -1L)
        assertNull(Meeting.fromJson(negative).transcriptResumeMs)
    }

    @Test
    fun aScreenSaveKeepsTheStoredPointUnlessTheScreenEndedIt() {
        val base0 = Meeting(id = "m1", title = "Call", createdAtMs = base).apply {
            transcriptResumeMs = 60_000L
        }
        val ours = MeetingMerge.snapshot(base0).apply { title = "Renamed" }
        // The continuing pass moved the point on meanwhile, and the stopped
        // recording's marks are still waiting for it.
        val disk = base0.copy(
            highlightMarksMs = mutableListOf(70_000L),
            transcriptResumeMs = 90_000L
        )
        val merged = MeetingMerge.mergeScreenEdits(base0, ours, disk, Long.MIN_VALUE)
        assertEquals("Renamed", merged.title)
        assertEquals(90_000L, merged.transcriptResumeMs)
        assertEquals(listOf(70_000L), merged.highlightMarksMs)
        // Removing the audio here ends it.
        val removed = MeetingMerge.snapshot(base0).apply {
            audioFile = null
            transcriptResumeMs = null
        }
        assertNull(MeetingMerge.mergeScreenEdits(base0, removed, disk, Long.MIN_VALUE).transcriptResumeMs)
    }

    // --- Removing the audio of an unfinished transcript ------------------------------------

    @Test
    fun automaticCleanUpAndMultiSelectLeaveTheAudioOfAnUnfinishedTranscript() {
        val day = Retention.DAY_MS
        val now = 1_800_000_000_000L
        fun candidate(id: String, unfinished: Boolean) = Retention.Candidate(
            id = id, createdAtMs = now - 400 * day, modifiedMs = now - 400 * day,
            starred = false, audioBytes = 1_000L, hasFutureReminder = false,
            hasTranscript = true, unfinished = unfinished
        )
        val both = listOf(candidate("done", false), candidate("half", true))
        assertEquals(
            listOf("done"),
            Retention.select(Retention.Rule(Retention.Mode.AUDIO, 90), both, emptySet(), now).map { it.id }
        )
        // Deleting whole old meetings is not about the audio: both go.
        assertEquals(
            setOf("done", "half"),
            Retention.select(Retention.Rule(Retention.Mode.MEETINGS, 90), both, emptySet(), now)
                .map { it.id }.toSet()
        )

        val half = BulkActions.Item(
            "half", hasAudio = true, recording = false, busy = false, queued = false,
            hasTranscript = true, unfinished = true
        )
        assertFalse(BulkActions.eligible(BulkActions.Action.REMOVE_AUDIO, half))
        assertTrue(BulkActions.eligible(BulkActions.Action.DELETE, half))
        assertTrue(BulkActions.eligible(BulkActions.Action.REMOVE_AUDIO, half.copy(unfinished = false)))
    }
}
