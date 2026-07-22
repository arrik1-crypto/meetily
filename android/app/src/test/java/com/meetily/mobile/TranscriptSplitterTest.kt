package com.meetily.mobile

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.TranscriptSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSplitterTest {

    private val base = TranscriptSegment(
        timestampMs = 100_000L,
        text = "we should ship on friday no wait monday is better",
        speaker = "Priya",
        highlighted = true,
        clusterId = 3,
        audioMs = 60_000L
    )

    @Test
    fun splitKeepsFirstPartsIdentityAndUntagsSecond() {
        val pos = base.text.indexOf("no wait")
        val (first, second) = TranscriptSplitter.split(base, null, pos)!!
        assertEquals("we should ship on friday", first.text)
        assertEquals("no wait monday is better", second.text)
        assertEquals("Priya", first.speaker)
        assertEquals(3, first.clusterId)
        assertTrue(first.highlighted)
        assertNull(second.speaker)
        assertNull(second.clusterId)
        assertTrue(!second.highlighted)
    }

    @Test
    fun interpolatesAgainstNextSegmentWhenAvailable() {
        val next = TranscriptSegment(
            timestampMs = 110_000L, text = "next", audioMs = 70_000L
        )
        val pos = base.text.length / 2
        val (_, second) = TranscriptSplitter.split(base, next, pos)!!
        // Roughly halfway between the two anchors on both clocks.
        assertTrue(second.timestampMs in 104_000L..106_000L)
        assertTrue(second.audioMs!! in 64_000L..66_000L)
        assertTrue(second.timestampMs > base.timestampMs)
        assertTrue(second.audioMs!! < next.audioMs!!)
    }

    @Test
    fun estimatesPaceWithoutNextAnchor() {
        val pos = base.text.indexOf("no wait")
        val (first, second) = TranscriptSplitter.split(base, null, pos)!!
        val expected = base.timestampMs + first.text.length * TranscriptSplitter.MS_PER_CHAR
        // First part is trimmed, so the estimate uses the trimmed length.
        assertEquals(expected, second.timestampMs)
        assertEquals(
            base.audioMs!! + first.text.length * TranscriptSplitter.MS_PER_CHAR,
            second.audioMs
        )
    }

    @Test
    fun editedTextIsUsedForTheSplit() {
        val edited = "hello there general kenobi"
        val pos = edited.indexOf("general")
        val (first, second) = TranscriptSplitter.split(base, null, pos, editedText = edited)!!
        assertEquals("hello there", first.text)
        assertEquals("general kenobi", second.text)
    }

    @Test
    fun rejectsEdgeAndBlankSplits() {
        assertNull(TranscriptSplitter.split(base, null, 0))
        assertNull(TranscriptSplitter.split(base, null, base.text.length))
        // Splitting inside leading whitespace of part two leaves it blank.
        assertNull(TranscriptSplitter.split(base, null, 1, editedText = "a         "))
    }

    @Test
    fun nullAudioStaysNull() {
        val noAudio = base.copy(audioMs = null)
        val (_, second) = TranscriptSplitter.split(noAudio, null, 10)!!
        assertNull(second.audioMs)
    }
}
