package com.meetily.mobile

import com.meetily.mobile.data.TranscriptReconcile
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accuracy check compares two transcripts of the same audio. Getting the
 * alignment wrong would either drown the user in false differences or hide
 * real ones, so the block grouping and the merge are pinned here.
 */
class TranscriptReconcileTest {

    private fun seg(
        audioMs: Long,
        text: String,
        speaker: String? = null,
        highlighted: Boolean = false
    ) = TranscriptSegment(
        timestampMs = 1_000_000L + audioMs,
        text = text,
        speaker = speaker,
        highlighted = highlighted,
        audioMs = audioMs
    )

    @Test
    fun normalize_ignoresCasePunctuationAndSpacing() {
        assertEquals(
            TranscriptReconcile.normalize("Hello,  there!"),
            TranscriptReconcile.normalize("hello there")
        )
        assertEquals(
            TranscriptReconcile.normalize("don't"),
            TranscriptReconcile.normalize("dont")
        )
    }

    @Test
    fun identicalWordsAgreeDespitePunctuation() {
        val current = listOf(seg(0, "Hello there"), seg(5_000, "How are you"))
        val fresh = listOf(seg(0, "hello, there."), seg(5_000, "How are you?"))
        val blocks = TranscriptReconcile.align(current, fresh)
        assertEquals(2, blocks.size)
        assertTrue(TranscriptReconcile.differences(blocks).isEmpty())
    }

    @Test
    fun realWordChangeIsADifference() {
        val current = listOf(seg(0, "Meet Dr. Chen on Tuesday"))
        val fresh = listOf(seg(0, "Meet Dr. Chan on Tuesday"))
        val diffs = TranscriptReconcile.differences(
            TranscriptReconcile.align(current, fresh)
        )
        assertEquals(1, diffs.size)
        assertTrue(diffs[0].currentText.contains("Chen"))
        assertTrue(diffs[0].freshText.contains("Chan"))
    }

    @Test
    fun differentChunkingStillLinesUp() {
        // The second model split one line in two; same words, no difference.
        val current = listOf(seg(0, "one two three four"))
        val fresh = listOf(seg(0, "one two"), seg(2_000, "three four"))
        val blocks = TranscriptReconcile.align(current, fresh)
        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].freshIndices.size)
        assertTrue(blocks[0].agrees)
    }

    @Test
    fun linesWithoutAnAudioOffsetAnchorOnTheMeetingStart() {
        // System-recognizer meetings carry no audioMs. Anchoring on the first
        // segment instead of the meeting start would shift the whole
        // transcript by however long the room was quiet at the top, and every
        // line would then read as a difference.
        val base = 1_000_000L
        val current = listOf(
            TranscriptSegment(timestampMs = base + 30_000, text = "late start"),
            TranscriptSegment(timestampMs = base + 35_000, text = "second line")
        )
        val fresh = listOf(seg(30_000, "late start"), seg(35_000, "second line"))
        val blocks = TranscriptReconcile.align(current, fresh, base)
        assertTrue(TranscriptReconcile.differences(blocks).isEmpty())
    }

    @Test
    fun everySegmentLandsInExactlyOneBlock() {
        val current = List(5) { seg(it * 3_000L, "line $it") }
        val fresh = List(7) { seg(it * 2_000L, "fresh $it") }
        val blocks = TranscriptReconcile.align(current, fresh)
        assertEquals(
            (0..4).toList(),
            blocks.flatMap { it.currentIndices }.sorted()
        )
        assertEquals(
            (0..6).toList(),
            blocks.flatMap { it.freshIndices }.sorted()
        )
    }

    @Test
    fun mergeKeepsCurrentWhereNothingIsAccepted() {
        val current = listOf(seg(0, "original", speaker = "Alice"))
        val fresh = listOf(seg(0, "replacement"))
        val blocks = TranscriptReconcile.align(current, fresh)
        val merged = TranscriptReconcile.merge(current, fresh, blocks, emptySet())
        assertEquals(listOf("original"), merged.map { it.text })
        assertEquals("Alice", merged[0].speaker)
    }

    @Test
    fun acceptedBlockTakesNewWordsButKeepsTheUsersTags() {
        val current = listOf(seg(0, "hello", speaker = "Alice", highlighted = true))
        val fresh = listOf(seg(0, "hallo"))
        val blocks = TranscriptReconcile.align(current, fresh)
        val merged = TranscriptReconcile.merge(current, fresh, blocks, setOf(0))
        assertEquals(listOf("hallo"), merged.map { it.text })
        assertEquals("Alice", merged[0].speaker)
        assertTrue(merged[0].highlighted)
    }

    @Test
    fun freshSpeakerSurvivesWhenTheOldLineHadNone() {
        val current = listOf(seg(0, "hello"))
        val fresh = listOf(seg(0, "hallo", speaker = "Speaker 2"))
        val blocks = TranscriptReconcile.align(current, fresh)
        val merged = TranscriptReconcile.merge(current, fresh, blocks, setOf(0))
        assertEquals("Speaker 2", merged[0].speaker)
    }

    @Test
    fun aStretchOnlyOneModelHeardCountsAsADifference() {
        val current = listOf(seg(0, "shared line"), seg(30_000, "a cough"))
        val fresh = listOf(seg(0, "shared line"))
        val diffs = TranscriptReconcile.differences(
            TranscriptReconcile.align(current, fresh)
        )
        assertEquals(1, diffs.size)
        assertEquals("a cough", diffs[0].currentText)
        assertEquals("", diffs[0].freshText)
    }

    @Test
    fun acceptingAnEmptyBlockDropsTheLine() {
        val current = listOf(seg(0, "shared line"), seg(30_000, "a cough"))
        val fresh = listOf(seg(0, "shared line"))
        val blocks = TranscriptReconcile.align(current, fresh)
        val drop = TranscriptReconcile.differences(blocks).map { it.ordinal }.toSet()
        val merged = TranscriptReconcile.merge(current, fresh, blocks, drop)
        assertEquals(listOf("shared line"), merged.map { it.text })
    }

    @Test
    fun aWholesaleSwapKeepsLinesTheSecondPassWasSilentOn() {
        // What "Use the new transcript" accepts: every difference the new
        // pass actually has words for. A block it heard nothing in is left
        // alone — dropping a line is a per-span decision, made after looking.
        val current = listOf(
            seg(0, "shared line"),
            seg(20_000, "quiet aside"),
            seg(40_000, "misheard word")
        )
        val fresh = listOf(seg(0, "shared line"), seg(40_000, "misheard ward"))
        val blocks = TranscriptReconcile.align(current, fresh)
        val wholesale = TranscriptReconcile.differences(blocks)
            .filter { it.freshIndices.isNotEmpty() }
            .map { it.ordinal }
            .toSet()
        val merged = TranscriptReconcile.merge(current, fresh, blocks, wholesale)
        assertEquals(
            listOf("shared line", "quiet aside", "misheard ward"),
            merged.map { it.text }
        )
    }

    @Test
    fun mergedOutputStaysInAudioOrder() {
        val current = List(4) { seg(it * 5_000L, "old $it") }
        val fresh = List(4) { seg(it * 5_000L, "new $it") }
        val blocks = TranscriptReconcile.align(current, fresh)
        val merged = TranscriptReconcile.merge(current, fresh, blocks, setOf(1, 3))
        assertEquals(
            listOf("old 0", "new 1", "old 2", "new 3"),
            merged.map { it.text }
        )
    }

    @Test
    fun emptyFreshPassLeavesEveryLineDisputed() {
        val current = List(3) { seg(it * 4_000L, "line $it") }
        val blocks = TranscriptReconcile.align(current, emptyList())
        assertEquals(3, blocks.size)
        assertEquals(3, TranscriptReconcile.differences(blocks).size)
    }
}
