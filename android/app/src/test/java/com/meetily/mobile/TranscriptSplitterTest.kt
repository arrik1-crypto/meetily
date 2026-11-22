package com.meetily.mobile

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.TranscriptSplitter
import com.meetily.mobile.data.WordStamp
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

    /**
     * The regression that erased the tail of a meeting: splitting the last
     * line right after a recording stopped produced a timestamp seconds into
     * the future, which became the merge high-water mark and made a genuine
     * late segment look already-seen.
     */
    @Test
    fun estimateNeverRunsPastNow() {
        val now = 1_700_000_000_000L
        val live = base.copy(timestampMs = now - 500L)
        val pos = live.text.indexOf("no wait")
        val (_, second) = TranscriptSplitter.split(live, null, pos, nowMs = now)!!
        // The raw estimate would be ~1.4 s past `now`.
        assertTrue(second.timestampMs <= now)
        assertTrue(second.timestampMs >= live.timestampMs)
    }

    @Test
    fun estimateNeverRunsPastTheNextSegment() {
        // A next segment only 100 ms away, so the pace estimate overshoots it
        // — the fraction path is skipped because the span is used only when
        // positive, and 100 ms of span still beats a 1.4 s guess.
        val next = TranscriptSegment(timestampMs = base.timestampMs + 100L, text = "next")
        val pos = base.text.indexOf("no wait")
        val (_, second) = TranscriptSplitter.split(base, next, pos)!!
        assertTrue(second.timestampMs < next.timestampMs)
    }

    private val timed = base.copy(
        words = listOf(
            WordStamp(0, "we"), WordStamp(200, "should"), WordStamp(500, "ship"),
            WordStamp(700, "on"), WordStamp(900, "friday"), WordStamp(1500, "no"),
            WordStamp(1700, "wait"), WordStamp(2000, "monday"), WordStamp(2400, "is"),
            WordStamp(2600, "better")
        )
    )

    @Test
    fun wordTimingsArePartitionedAtTheSplit() {
        val pos = timed.text.indexOf("no wait")
        val (first, second) = TranscriptSplitter.split(timed, null, pos)!!
        assertEquals(timed.words!!.subList(0, 5), first.words)
        // The second part starts at its first word, and its words are now
        // timed from there: same moments in the audio as before.
        assertEquals(timed.audioMs!! + 1500L, second.audioMs)
        assertEquals(
            listOf(
                WordStamp(0, "no"), WordStamp(200, "wait"), WordStamp(500, "monday"),
                WordStamp(900, "is"), WordStamp(1100, "better")
            ),
            second.words
        )
        for (word in second.words!!) {
            val original = timed.words!!.first { it.text == word.text }
            assertEquals(timed.audioMs!! + original.ms, second.audioMs!! + word.ms)
        }
    }

    @Test
    fun wordTimingsFollowAnEditMadeInTheSplitDialog() {
        val edited = "we should ship on Friday no wait Monday is better"
        val pos = edited.indexOf("no wait")
        val (first, second) =
            TranscriptSplitter.split(timed, null, pos, editedText = edited)!!
        assertEquals("Friday", first.words!![4].text)
        assertEquals(900L, first.words!![4].ms)
        assertEquals(WordStamp(500, "Monday"), second.words!![2])
        assertEquals(timed.audioMs!! + 1500L, second.audioMs)
    }

    @Test
    fun splitInsideAWordGivesEachPartItsPiece() {
        val pos = timed.text.indexOf("day no")
        val (first, second) = TranscriptSplitter.split(timed, null, pos)!!
        assertEquals("we should ship on fri", first.text)
        assertEquals(WordStamp(900, "fri"), first.words!!.last())
        assertEquals("day", second.words!!.first().text)
        assertEquals(timed.audioMs!! + 900L, second.audioMs)
        assertEquals(0L, second.words!!.first().ms)
    }

    @Test
    fun wordOffsetNeverPassesTheNextLine() {
        // A next line whose audio starts before the word the split lands on.
        val next = TranscriptSegment(timestampMs = 110_000L, text = "next", audioMs = 61_000L)
        val pos = timed.text.indexOf("no wait")
        val (_, second) = TranscriptSplitter.split(timed, next, pos)!!
        assertTrue(second.audioMs!! < next.audioMs!!)
        // Rebased against where the line now starts, so every word still
        // seeks to its own moment.
        assertEquals(timed.audioMs!! + 1500L, second.audioMs!! + second.words!!.first().ms)
    }

    @Test
    fun noWordTimingsStayNull() {
        val pos = base.text.indexOf("no wait")
        val (first, second) = TranscriptSplitter.split(base, null, pos)!!
        assertNull(first.words)
        assertNull(second.words)
    }

    @Test
    fun nullAudioStaysNull() {
        val noAudio = base.copy(audioMs = null)
        val (_, second) = TranscriptSplitter.split(noAudio, null, 10)!!
        assertNull(second.audioMs)
    }
}
