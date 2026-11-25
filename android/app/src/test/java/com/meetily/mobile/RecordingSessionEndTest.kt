package com.meetily.mobile

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a recording session leaves behind as it ends, on a service that may
 * already be running the next one: its late transcript chunks
 * (LateChunks), a star tapped before its line existed (QueuedHighlight,
 * also as the snapshot saves it while it waits), and the decisions the end
 * of a recording rests on: how long Finish waits for a live model still
 * loading (RecordingService.finishWaitsForLoad) and for the audio file to
 * close (RecordingService.closeWaitMs).
 *
 * The service wiring around them (the close joined before the work is
 * issued, the watchdog re-armed while the model loads, the order in which
 * the session stops, and recovery from MainActivity) is private Service
 * code that a JVM test cannot run. Device checks cover it: PLAY_RELEASE.md
 * RF 14, 15, 16, 48 and 49, and DEVICE_TEST_PLAN.md P1-9c, P1-10, P1-10b,
 * P1-11a and P1-13.
 */
class RecordingSessionEndTest {

    private val words = listOf(WordStamp(0L, "hello"))

    @Test
    fun aLateChunkKeepsItsOwnSessionsSpeakerNames() {
        val names = mutableMapOf(0 to "Ana", 1 to "Ben")
        val late = LateChunks("first")
        late.seal(withAudio = true, clusterNames = names)
        // The next session on the same service starts its clusters at 0
        // again, with other people.
        names.clear()
        names[0] = "Chloe"
        val line = late.segment("hello", null, 0, 12_000L, words, nowMs = 5L)!!
        assertEquals("Ana", line.speaker)
        assertEquals(0, line.clusterId)
        assertEquals(5L, line.timestampMs)
        assertFalse(line.highlighted)
    }

    @Test
    fun aLateChunkKeepsItsAudioTimesByWhetherItsOwnSessionKeptAudio() {
        val kept = LateChunks("kept").apply { seal(withAudio = true, clusterNames = emptyMap()) }
        val line = kept.segment("hello", null, null, 12_000L, words, nowMs = 0L)!!
        assertEquals(12_000L, line.audioMs)
        assertEquals(words, line.words)
        // Sealed once: a next session that keeps its audio changes nothing.
        val notKept = LateChunks("not kept").apply {
            seal(withAudio = false, clusterNames = emptyMap())
            seal(withAudio = true, clusterNames = mapOf(0 to "Chloe"))
        }
        val bare = notKept.segment("hello", null, 0, 12_000L, words, nowMs = 0L)!!
        assertNull(bare.audioMs)
        assertNull(bare.words)
        assertNull(bare.speaker)
    }

    @Test
    fun aTaggedSpeakerWinsOverTheClusterName() {
        val late = LateChunks("s").apply { seal(withAudio = false, clusterNames = mapOf(0 to "Ana")) }
        assertEquals("Ben", late.segment("hi", "Ben", 0, 0L, emptyList(), 0L)!!.speaker)
    }

    @Test
    fun aDiscardedOrEmptyChunkHasNoLine() {
        // Never sealed: the session was discarded, its meeting deleted.
        assertNull(LateChunks("s").segment("hello", null, null, 0L, words, 0L))
        val late = LateChunks("s").apply { seal(withAudio = true, clusterNames = emptyMap()) }
        assertNull(late.segment("  ", null, null, 0L, words, 0L))
        val noSession = LateChunks("").apply { seal(withAudio = true, clusterNames = emptyMap()) }
        assertNull(noSession.segment("hello", null, null, 0L, words, 0L))
    }

    @Test
    fun aStarTappedWhileTheModelLoadsGoesOnTheLineSpokenThen() {
        // Tapped 40 s in; the backlog arrives from the start of the recording.
        val star = QueuedHighlight(tapMs = 40_000L)
        assertNull(star.onAppended(0, 0L))
        assertNull(star.onAppended(1, 18_000L))
        assertNull(star.onAppended(2, 35_000L))
        // The first line after the tap settles it: the tap fell in line 2.
        assertEquals(2, star.onAppended(3, 52_000L))
    }

    @Test
    fun aStarTappedWhileTheBacklogIsDecodedGoesOnTheLineSpokenThen() {
        // The model loaded 40 s in. Line 0, from the start of the recording,
        // is on screen and the rest of the backlog is still to come when the
        // star is tapped 50 s in: not line 0, but the line spoken then.
        val star = QueuedHighlight(tapMs = 50_000L, newestLine = 0)
        assertNull(star.onAppended(1, 18_000L))
        assertNull(star.onAppended(2, 41_000L))
        assertEquals(2, star.onAppended(3, 55_000L))
        // The tap fell in the newest line there was: that one, once a line
        // after the tap arrives, or at the end.
        assertEquals(4, QueuedHighlight(tapMs = 50_000L, newestLine = 4).onAppended(5, 52_000L))
        assertEquals(4, QueuedHighlight(tapMs = 50_000L, newestLine = 4).atEnd())
        // Without a time, the next line is the one, as before.
        assertNull(QueuedHighlight(tapMs = null, newestLine = 3).atEnd())
        assertEquals(4, QueuedHighlight(tapMs = null, newestLine = 3).onAppended(4, 1_000L))
    }

    @Test
    fun aStarTappedInASilenceGoesOnTheNextLine() {
        // No line had started by the tap: the first one after it.
        val star = QueuedHighlight(tapMs = 3_000L)
        assertEquals(0, star.onAppended(0, 8_000L))
        assertNull(QueuedHighlight(tapMs = 3_000L).atEnd())
    }

    @Test
    fun aStarStillWaitingAtTheEndGoesOnTheLastLineBeforeIt() {
        val star = QueuedHighlight(tapMs = 40_000L)
        assertNull(star.onAppended(0, 0L))
        assertNull(star.onAppended(1, 30_000L))
        assertEquals(1, star.atEnd())
        // No line came at all (the live model did not load): a mark at the
        // tap, for the transcript made from the audio.
        val none = QueuedHighlight(tapMs = 40_000L)
        assertNull(none.atEnd())
        assertEquals(40_000L, none.tapOffsetMs)
    }

    @Test
    fun aStarWithoutATimeGoesOnTheNextLine() {
        // A partial line on screen, or lines with no time in the recording
        // (the phone's speech recogniser): the next line, as before.
        assertEquals(4, QueuedHighlight(tapMs = null).onAppended(4, 1_000L))
        assertEquals(0, QueuedHighlight(tapMs = 2_000L).onAppended(0, null))
    }

    @Test
    fun aStarWaitsForItsLineWhileThereIsNoneOrTheDecodingIsBehind() {
        // No line yet, as while the live model loads: timed, for the line
        // spoken at the tap.
        val loading = QueuedHighlight.forTap(partialShown = false, lines = 0, decodingBehind = false, tapMs = 40_000L)!!
        assertEquals(40_000L, loading.tapOffsetMs)
        assertNull(loading.atEnd())
        // Lines on screen with the backlog still decoding: timed, and the
        // tap may fall in the newest line so far.
        val behind = QueuedHighlight.forTap(partialShown = false, lines = 3, decodingBehind = true, tapMs = 50_000L)!!
        assertEquals(50_000L, behind.tapOffsetMs)
        assertEquals(2, behind.atEnd())
        // A partial line on screen: the next line, untimed.
        val partial = QueuedHighlight.forTap(partialShown = true, lines = 3, decodingBehind = false, tapMs = 50_000L)!!
        assertNull(partial.tapOffsetMs)
        assertEquals(3, partial.onAppended(3, 49_000L))
        // Caught up: the newest line is toggled, nothing waits.
        assertNull(QueuedHighlight.forTap(partialShown = false, lines = 3, decodingBehind = false, tapMs = 50_000L))
    }

    @Test
    fun aTimedStarIsAMarkAtFinishWhenTheLiveWordsStopShort() {
        val star = QueuedHighlight(tapMs = 40_000L)
        assertNull(star.onAppended(0, 0L))
        assertNull(star.onAppended(1, 30_000L))
        // The line it fell in may never have been decoded: a mark at the tap
        // for the transcript made from the audio.
        assertNull(star.lineAtFinish(liveIncomplete = true, hasAudio = true))
        assertEquals(40_000L, star.markAtFinish(hasAudio = true))
        // Complete, or no audio to transcribe: the line it fell in.
        assertEquals(1, star.lineAtFinish(liveIncomplete = false, hasAudio = true))
        assertEquals(1, star.lineAtFinish(liveIncomplete = true, hasAudio = false))
        // No audio and no line: nothing to keep.
        assertNull(QueuedHighlight(tapMs = 40_000L).markAtFinish(hasAudio = false))
        // Untimed: still the line it waited for.
        assertNull(QueuedHighlight(tapMs = null).lineAtFinish(liveIncomplete = true, hasAudio = true))
        assertNull(QueuedHighlight(tapMs = null).markAtFinish(hasAudio = true))
    }

    @Test
    fun finishWaitsForALoadingModelUpToItsCap() {
        // Still loading at the 15 s checks before the cap: wait another step.
        assertTrue(RecordingService.finishWaitsForLoad(liveLoading = true, sinceFinishMs = 15_000L))
        assertTrue(RecordingService.finishWaitsForLoad(liveLoading = true, sinceFinishMs = 45_000L))
        // At the cap, and past it: the session is saved as it stands.
        assertEquals(60_000L, RecordingService.FINISH_LOAD_WAIT_MS)
        assertFalse(RecordingService.finishWaitsForLoad(liveLoading = true, sinceFinishMs = 60_000L))
        assertFalse(RecordingService.finishWaitsForLoad(liveLoading = true, sinceFinishMs = 75_000L))
        // Loaded or failed: the 15 s rule, as in 3.21.0-beta1.
        assertFalse(RecordingService.finishWaitsForLoad(liveLoading = false, sinceFinishMs = 15_000L))
    }

    @Test
    fun theAudioCloseWaitEndsAtItsDeadlineAndNeverWaitsForever() {
        assertEquals(2_000L, RecordingService.closeWaitMs(closeByMs = 3_000L, nowMs = 1_000L))
        // At or past the deadline: 1 ms, never 0 or less, since the caller
        // joins with it directly and a join of 0 would wait forever.
        assertEquals(1L, RecordingService.closeWaitMs(closeByMs = 3_000L, nowMs = 3_000L))
        assertEquals(1L, RecordingService.closeWaitMs(closeByMs = 3_000L, nowMs = 9_000L))
    }

    @Test
    fun aWaitingStarIsSavedOnTheLineItFellInSoFar() {
        val lines = listOf(line(0L), line(18_000L), line(41_000L))
        // Tapped 50 s in, line 0 on screen, the backlog still decoding.
        val star = QueuedHighlight(tapMs = 50_000L, newestLine = 0)
        assertNull(star.onAppended(1, 18_000L))
        assertNull(star.onAppended(2, 41_000L))
        val saved = QueuedHighlight.withWaitingStars(lines, listOf(star))
        assertEquals(listOf(false, false, true), saved.map { it.highlighted })
        // Only the copy: the lines themselves are left as they were.
        assertEquals(listOf(false, false, false), lines.map { it.highlighted })
        // Tapped during the load with no line yet, or untimed: nothing yet.
        assertEquals(
            listOf(false, false, false),
            QueuedHighlight.withWaitingStars(lines, listOf(QueuedHighlight(tapMs = 40_000L))).map { it.highlighted }
        )
        assertEquals(
            listOf(false, false, false),
            QueuedHighlight.withWaitingStars(lines, listOf(QueuedHighlight(tapMs = null))).map { it.highlighted }
        )
        // A line already starred stays starred, and a line that is not there
        // is ignored.
        val starred = listOf(line(0L), line(18_000L, highlighted = true))
        assertEquals(
            listOf(false, true),
            QueuedHighlight.withWaitingStars(starred, listOf(QueuedHighlight(50_000L, newestLine = 1)))
                .map { it.highlighted }
        )
        assertEquals(
            listOf(false, true),
            QueuedHighlight.withWaitingStars(starred, listOf(QueuedHighlight(50_000L, newestLine = 5)))
                .map { it.highlighted }
        )
    }

    private fun line(audioMs: Long, highlighted: Boolean = false) = TranscriptSegment(
        timestampMs = audioMs, text = "line at $audioMs", highlighted = highlighted, audioMs = audioMs
    )
}
