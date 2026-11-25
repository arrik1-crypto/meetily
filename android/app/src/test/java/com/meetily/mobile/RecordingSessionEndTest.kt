package com.meetily.mobile

import com.meetily.mobile.data.WordStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a recording session leaves behind as it ends, on a service that may
 * already be running the next one: its late transcript chunks
 * (LateChunks), a star tapped before its line existed (QueuedHighlight),
 * and when the service may stop (RecordingService.stopsAfterWork).
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
}
