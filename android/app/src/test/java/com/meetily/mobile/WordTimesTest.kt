package com.meetily.mobile

import com.meetily.mobile.data.FindReplace
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.TranscriptSplitter
import com.meetily.mobile.data.WordAlign
import com.meetily.mobile.data.WordRealign
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.data.WordTimes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a line's word timings came from (TranscriptSegment.wordTimes): how
 * it is stored, read from older meetings, carried through merges and set
 * by every edit that estimates timings, so "Re-time edited lines" finds
 * exactly the lines it should.
 */
class WordTimesTest {

    private val timed = listOf(
        WordStamp(0, "Hello"), WordStamp(400, "Jon,"), WordStamp(900, "how"),
        WordStamp(1_100, "are"), WordStamp(1_300, "you")
    )

    private fun line(times: WordTimes? = null, words: List<WordStamp>? = timed) =
        TranscriptSegment(1_000L, "Hello Jon, how are you", audioMs = 0L, words = words, wordTimes = times)

    @Test
    fun storedValuesParseAndAnythingElseIsUnknown() {
        assertEquals(WordTimes.ESTIMATED, WordTimes.parse("estimated"))
        assertEquals(WordTimes.ALIGNED, WordTimes.parse("aligned"))
        assertNull(WordTimes.parse(""))
        assertNull(WordTimes.parse(null))
        assertNull(WordTimes.parse("Aligned"))
        assertNull(WordTimes.parse("measured"))
    }

    @Test
    fun aLineRoundTripsWithItsFlag() {
        for (times in listOf(null, WordTimes.ESTIMATED, WordTimes.ALIGNED)) {
            val json = Meeting.segmentToJson(line(times))
            assertEquals(times?.json, json.optString("wordTimes").ifEmpty { null })
            assertEquals(line(times), Meeting.segmentFromJson(JSONObject(json.toString())))
        }
    }

    @Test
    fun linesFromBeforeTheFlagReadAsUnknown() {
        val old = JSONObject(
            """{"t":1000,"text":"Hello there","audioMs":0,"words":[[0,"Hello"],[300,"there"]]}"""
        )
        val segment = Meeting.segmentFromJson(old)
        assertNull(segment.wordTimes)
        assertEquals(2, segment.words!!.size)
        // Unknown is not offered for re-timing.
        assertFalse(WordAlign.needsRetime(segment))
        val future = JSONObject(old.toString()).put("wordTimes", "something-new")
        assertNull(Meeting.segmentFromJson(future).wordTimes)
    }

    @Test
    fun aWholeMeetingRoundTripsAsABackupCopiesIt() {
        val meeting = Meeting(
            id = "m1", title = "Weekly", createdAtMs = 5L,
            segments = mutableListOf(line(WordTimes.ESTIMATED), line(WordTimes.ALIGNED).copy(timestampMs = 2_000L))
        )
        val back = Meeting.fromJson(JSONObject(meeting.toJson().toString()))
        assertEquals(meeting.segments, back.segments)
        // Per line, beside its words: the meeting's own keys are unchanged.
        assertFalse(meeting.toJson().has("wordTimes"))
    }

    @Test
    fun anEditThatKeepsEveryTimingKeepsTheFlag() {
        assertEquals(WordTimes.ALIGNED, WordTimes.afterEdit(WordTimes.ALIGNED, timed, timed))
        // Deleting a word measures nothing anew.
        assertEquals(WordTimes.ALIGNED, WordTimes.afterEdit(WordTimes.ALIGNED, timed, timed.drop(1)))
        assertNull(WordTimes.afterEdit(null, timed, timed.take(2)))
    }

    @Test
    fun anEditThatEstimatesATimingMarksTheLine() {
        val words = WordRealign.realign("Hello Jon, how are you", timed, "Hello John, how are you")
        assertEquals(WordTimes.ESTIMATED, WordTimes.afterEdit(null, timed, words))
        assertEquals(WordTimes.ESTIMATED, WordTimes.afterEdit(WordTimes.ALIGNED, timed, words))
        // A line left without timings has no flag: they are missing.
        assertNull(WordTimes.afterEdit(WordTimes.ALIGNED, timed, null))
        assertNull(WordTimes.afterEdit(WordTimes.ESTIMATED, timed, emptyList()))
    }

    @Test
    fun findAndReplaceMarksTheLinesItRetimesAndUndoPutsTheFlagBack() {
        val meeting = Meeting(
            id = "m1", title = "Weekly", createdAtMs = 0L,
            segments = mutableListOf(
                line(WordTimes.ALIGNED),
                TranscriptSegment(2_000L, "Jon again, untimed", audioMs = 3_000L),
                TranscriptSegment(3_000L, "nothing here", audioMs = 6_000L, words = listOf(WordStamp(0, "nothing")))
            )
        )
        val query = FindReplace.Query.of("Jon", "John", FindReplace.Options())
        val snapshot = FindReplace.apply(meeting, query, setOf(FindReplace.Area.TRANSCRIPT))!!
        assertEquals(WordTimes.ESTIMATED, meeting.segments[0].wordTimes)
        // No timings to estimate: still missing, and offered as such.
        assertNull(meeting.segments[1].wordTimes)
        assertTrue(WordAlign.needsRetime(meeting.segments[1]))
        assertNull(meeting.segments[2].wordTimes)
        assertEquals(listOf(0, 1), WordAlign.linesToRetime(meeting.segments))

        FindReplace.undo(meeting, snapshot)
        assertEquals(WordTimes.ALIGNED, meeting.segments[0].wordTimes)
        assertEquals(timed, meeting.segments[0].words)
    }

    @Test
    fun splittingBetweenWordsKeepsTheFlagOnBothParts() {
        val text = "Hello Jon, how are you"
        val (first, second) = TranscriptSplitter.split(
            line(WordTimes.ALIGNED), null, text.indexOf("how"), nowMs = 10_000L
        )!!
        assertEquals(WordTimes.ALIGNED, first.wordTimes)
        assertEquals(WordTimes.ALIGNED, second.wordTimes)
        // Unknown stays unknown.
        val (a, b) = TranscriptSplitter.split(line(null), null, text.indexOf("how"), nowMs = 10_000L)!!
        assertNull(a.wordTimes)
        assertNull(b.wordTimes)
    }

    @Test
    fun splittingThroughAWordEstimatesTheSecondPiece() {
        val text = "Hello Jon, how are you"
        // "ho|w": the second part's first word borrows the word's start.
        val (first, second) = TranscriptSplitter.split(
            line(WordTimes.ALIGNED), null, text.indexOf("how") + 2, nowMs = 10_000L
        )!!
        assertEquals(WordTimes.ESTIMATED, first.wordTimes)
        assertEquals(WordTimes.ESTIMATED, second.wordTimes)
        assertTrue(WordAlign.needsRetime(second.copy(audioMs = second.audioMs ?: 0L)))
    }

    @Test
    fun splittingWithEditedWordingEstimatesThePartThatChanged() {
        val edited = "Hello John, how are you"
        val (first, second) = TranscriptSplitter.split(
            line(null), null, edited.indexOf("how"), editedText = edited, nowMs = 10_000L
        )!!
        assertEquals(WordTimes.ESTIMATED, first.wordTimes)
        // The second part's words are the line's own, moved to its start.
        assertNull(second.wordTimes)
    }

    @Test
    fun theFlagTravelsWithTheTranscriptThroughAScreenMerge() {
        val base = Meeting(id = "m1", title = "T", createdAtMs = 0L, segments = mutableListOf(line(WordTimes.ESTIMATED)))
        // A run saved the line re-timed; the screen changed nothing there.
        val disk = MeetingMerge.snapshot(base).apply {
            segments[0] = segments[0].copy(words = timed.map { WordStamp(it.ms + 10, it.text) }, wordTimes = WordTimes.ALIGNED)
        }
        val ours = MeetingMerge.snapshot(base).apply { title = "Renamed" }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals(WordTimes.ALIGNED, merged.segments[0].wordTimes)
        assertEquals("Renamed", merged.title)

        // The screen edited the line meanwhile: its edit, and its flag, win.
        val edited = MeetingMerge.snapshot(base).apply {
            segments[0] = segments[0].copy(text = "Hello John, how are you", wordTimes = WordTimes.ESTIMATED)
        }
        val kept = MeetingMerge.mergeScreenEdits(base, edited, disk, Long.MIN_VALUE)
        assertEquals("Hello John, how are you", kept.segments[0].text)
        assertEquals(WordTimes.ESTIMATED, kept.segments[0].wordTimes)
    }

    @Test
    fun aRunsResultIsDiscardedForALineEditedOnTheScreen() {
        val screen = mutableListOf(line(WordTimes.ESTIMATED))
        val target = WordAlign.target(screen[0])!!
        screen[0] = screen[0].copy(text = "Hello John, how are you")
        assertEquals(0, WordAlign.apply(screen, listOf(WordAlign.Retimed(target, timed))))
        assertEquals(WordTimes.ESTIMATED, screen[0].wordTimes)
    }
}
