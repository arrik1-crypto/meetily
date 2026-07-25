package com.meetily.mobile

import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.whisper.BatchSplit
import com.meetily.mobile.whisper.ImportEta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batching chunks into one Whisper call is what makes long imports finish in
 * hours instead of most of a day — but only if the transcript can be put back
 * together afterwards. These are the rules that do that.
 */
class BatchSplitEtaTest {

    private fun parts(vararg spans: Pair<Long, Long>) =
        spans.map { BatchSplit.Part(it.first, it.second) }

    @Test
    fun wordsLandInTheChunkTheyWereSpokenIn() {
        val p = parts(0L to 5_000L, 5_000L to 5_000L)
        val split = BatchSplit.split(
            p,
            listOf(WordStamp(100, "hello"), WordStamp(6_000, "world"))
        )
        assertEquals(listOf("hello"), split[0].map { it.text })
        assertEquals(listOf("world"), split[1].map { it.text })
    }

    @Test
    fun wordTimesAreRebasedOntoTheirOwnChunk() {
        // Playback seeks by word offset within a segment, so a word in the
        // second chunk must not keep its batch-relative time.
        val p = parts(0L to 5_000L, 5_000L to 5_000L)
        val split = BatchSplit.split(p, listOf(WordStamp(6_200, "world")))
        assertEquals(1_200L, split[1].first().ms)
    }

    @Test
    fun aWordInTheSeamIsKeptRatherThanDropped() {
        // Whisper's word clock drifts slightly from the samples it was fed.
        // Discarding strays would silently lose words at every boundary.
        val p = parts(0L to 5_000L, 6_000L to 5_000L)
        val split = BatchSplit.split(p, listOf(WordStamp(5_500, "seam")))
        assertEquals(1, split.sumOf { it.size })
    }

    @Test
    fun aWordPastTheEndSnapsToTheLastChunk() {
        val p = parts(0L to 5_000L, 5_000L to 5_000L)
        val split = BatchSplit.split(p, listOf(WordStamp(99_000, "trailing")))
        assertEquals(listOf("trailing"), split[1].map { it.text })
        assertTrue(split[0].isEmpty())
    }

    @Test
    fun rebasedTimesAreNeverNegative() {
        // A stray snapped backwards onto a later part would otherwise carry a
        // negative offset into the seek logic.
        val p = parts(0L to 1_000L, 10_000L to 1_000L)
        val split = BatchSplit.split(p, listOf(WordStamp(4_000, "stray")))
        assertTrue(split.flatten().all { it.ms >= 0L })
    }

    @Test
    fun everyWordIsAccountedForExactlyOnce() {
        val p = parts(0L to 3_000L, 3_000L to 4_000L, 7_000L to 2_000L)
        val words = (0 until 40).map { WordStamp(it * 250L, "w$it") }
        val split = BatchSplit.split(p, words)
        assertEquals(words.size, split.sumOf { it.size })
    }

    @Test
    fun noPartsMeansNothingToAssign() {
        assertTrue(BatchSplit.split(emptyList(), listOf(WordStamp(0, "x"))).isEmpty())
        assertEquals(-1, BatchSplit.indexFor(emptyList(), 0L))
    }

    // --- ETA ----------------------------------------------------------------

    @Test
    fun noEstimateUntilThereIsEnoughToMeasure() {
        // A rate measured over two chunks lurches; silence beats a wrong number.
        assertNull(ImportEta.remainingMs(5_000, 3_600_000, 60_000))
    }

    @Test
    fun theEstimateScalesWithMeasuredThroughput() {
        // 60 s of audio took 10 min; 540 s left => about 90 min.
        val left = ImportEta.remainingMinutes(60_000, 600_000, 600_000)
        assertNotNull(left)
        assertEquals(90L, left)
    }

    @Test
    fun nothingIsReportedOnceTheFileIsCovered() {
        assertNull(ImportEta.remainingMs(600_000, 600_000, 60_000))
        assertNull(ImportEta.remainingMs(600_000, -1, 60_000))
    }

    @Test
    fun anAlmostFinishedRunStillReadsAsAMinuteLeft() {
        // Rounding to zero would read as "done" while work is still going.
        assertEquals(1L, ImportEta.remainingMinutes(600_000, 600_100, 600_000))
    }
}
