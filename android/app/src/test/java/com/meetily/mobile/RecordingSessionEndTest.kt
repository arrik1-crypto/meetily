package com.meetily.mobile

import com.meetily.mobile.data.ActiveRecording
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.whisper.LiveModelSlot
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a recording session leaves behind as it ends, on a service that may
 * already be running the next one: its late transcript chunks
 * (LateChunks), a star tapped before its line existed (QueuedHighlight),
 * when the service may stop (RecordingService.stopsAfterWork), the live
 * model batch work waits for (LiveModelSlot), and what a recording the
 * process died under gets (ActiveRecording).
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
    fun theServiceStopsOnlyAfterTheLastFinishedSessionIssuedItsWork() {
        // Two sessions finished on one service while the first one's audio
        // file was still closing: the first to issue its work leaves the
        // foreground to the other.
        assertFalse(RecordingService.stopsAfterWork(active = false, starting = false, stillIssuing = 1))
        assertTrue(RecordingService.stopsAfterWork(active = false, starting = false, stillIssuing = 0))
        // A session running, or one whose start is on its way, owns it.
        assertFalse(RecordingService.stopsAfterWork(active = true, starting = false, stillIssuing = 0))
        assertFalse(RecordingService.stopsAfterWork(active = false, starting = true, stillIssuing = 0))
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
    fun batchWorkWaitsUntilEveryLiveModelIsFreed() {
        val count = AtomicInteger(0)
        val first = LiveModelSlot(count)
        val second = LiveModelSlot(count)
        first.take()
        first.take()
        second.take()
        assertEquals(2, count.get())
        assertTrue(first.isHeld)
        // Given back once, whichever of the release, a failed load or a
        // rejected one comes first.
        assertTrue(first.giveBack())
        assertFalse(first.giveBack())
        assertFalse(first.isHeld)
        assertEquals(1, count.get())
        assertTrue(second.giveBack())
        assertEquals(0, count.get())
        // Never taken: nothing to give back.
        assertFalse(LiveModelSlot(count).giveBack())
        assertEquals(0, count.get())
    }

    @Test
    fun theRecoveryMarkerSaysWhetherTheLiveWordsStopShort() {
        assertEquals("abc", ActiveRecording("abc").format())
        assertEquals(ActiveRecording("abc", liveShort = true), ActiveRecording.parse(ActiveRecording("abc", true).format()))
        assertEquals(ActiveRecording("abc", liveShort = false), ActiveRecording.parse(ActiveRecording("abc", false).format()))
        // A marker from before the flag: the id alone, maybe with a newline.
        assertEquals(ActiveRecording("abc"), ActiveRecording.parse("abc\n"))
        assertNull(ActiveRecording.parse(""))
        assertNull(ActiveRecording.parse("  \nlive-short"))
    }

    @Test
    fun aRecoveredRecordingIsTranscribedWhenItHasNoLinesOrTheyStopShort() {
        assertTrue(RecordingService.recoveryWantsTranscript(hasLines = false, liveShort = false))
        assertTrue(RecordingService.recoveryWantsTranscript(hasLines = true, liveShort = true))
        assertFalse(RecordingService.recoveryWantsTranscript(hasLines = true, liveShort = false))
    }
}
