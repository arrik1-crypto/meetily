package com.meetily.mobile

import com.meetily.mobile.data.Chapter
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.PassagePlan
import com.meetily.mobile.data.PassagePlan.Span
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing a passage: which lines go, which are shortened at a word, where
 * a span that cannot be cut moves to, and what happens to highlight marks,
 * chapters, photos, the summary and an unfinished transcript's resume point.
 * The audio keeps its length, so nothing outside the span moves.
 */
class PassagePlanTest {

    /** Wall clock of the meeting's start; lines are stamped from it. */
    private val t0 = 1_700_000_000_000L

    private fun line(
        atMs: Long,
        text: String,
        words: List<WordStamp>? = null,
        highlighted: Boolean = false
    ) = TranscriptSegment(
        timestampMs = t0 + atMs,
        text = text,
        highlighted = highlighted,
        audioMs = atMs,
        words = words
    )

    /** A line whose words are its text split at spaces, [stepMs] apart from its start. */
    private fun worded(atMs: Long, text: String, stepMs: Long = 500L) = line(
        atMs, text, text.split(' ').mapIndexed { i, w -> WordStamp(i * stepMs, w) }
    )

    // Lines at 0 s, 10 s, 20 s, 30 s; the audio lasts 40 s.
    private val plain = listOf(
        line(0, "Good morning everyone."),
        line(10_000, "First the budget."),
        line(20_000, "Then the hiring plan."),
        line(30_000, "Any questions?")
    )

    @Test
    fun linesRunToTheNextLineAndTheLastToTheEndOfTheAudio() {
        val lines = PassagePlan.lines(plain, 40_000L)
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), lines.map { it.startMs })
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 40_000L), lines.map { it.endMs })
        // Unknown length: the last line runs to the end, whatever it is.
        assertEquals(PassagePlan.END, PassagePlan.lines(plain, null).last().endMs)
        // An untimed line is not on the timeline at all.
        val mixed = plain + TranscriptSegment(timestampMs = t0 + 50_000, text = "typed later")
        assertEquals(4, PassagePlan.lines(mixed, 40_000L).size)
    }

    @Test
    fun wholeLinesInsideTheSpanGoAndNothingElseMoves() {
        val plan = PassagePlan.plan(plain, Span(10_000, 30_000), 40_000L)
        assertEquals(listOf(1, 2), plan.removedLines)
        assertTrue(plan.trimmedLines.isEmpty())
        assertFalse(plan.snapped)
        assertEquals(listOf(plain[0], plain[3]), plan.segments)
        // The kept lines are the very same objects: same times, same words.
        assertTrue(plan.segments[1] === plain[3])
    }

    @Test
    fun aSpanFromLinesCoversExactlyThoseLines() {
        val span = PassagePlan.spanFor(plain, 1, 2, 40_000L)!!
        assertEquals(Span(10_000, 30_000), span)
        // The last line runs to the end of the audio.
        assertEquals(Span(30_000, 40_000), PassagePlan.spanFor(plain, 3, 3, 40_000L))
        assertNull(PassagePlan.spanFor(plain, 5, 5, 40_000L))
    }

    @Test
    fun aSpanAtTheStartAndOneAtTheEnd() {
        val atStart = PassagePlan.plan(plain, Span(0, 10_000), 40_000L)
        assertEquals(listOf(0), atStart.removedLines)
        assertEquals(plain.drop(1), atStart.segments)

        val atEnd = PassagePlan.plan(plain, Span(30_000, 40_000), 40_000L)
        assertEquals(listOf(3), atEnd.removedLines)
        // A span that reaches the end of the audio runs to the end.
        assertEquals(PassagePlan.END, atEnd.span.endMs)
        assertNull(atEnd.span.lengthMs())

        // Past the end of the audio: still to the end, not beyond.
        val past = PassagePlan.plan(plain, Span(30_000, 99_000), 40_000L)
        assertEquals(PassagePlan.END, past.span.endMs)
        assertEquals(listOf(3), past.removedLines)

        // A negative start is the start.
        assertEquals(0L, PassagePlan.plan(plain, Span(-500, 10_000), 40_000L).span.startMs)
    }

    @Test
    fun anEmptySpanChangesNothing() {
        val marks = listOf(12_000L)
        val chapters = listOf(Chapter("Budget", t0 + 10_000))
        for (span in listOf(Span(10_000, 10_000), Span(20_000, 10_000))) {
            val plan = PassagePlan.plan(plain, span, 40_000L, marks, chapters)
            assertFalse(plan.changesTranscript)
            assertEquals(plain, plan.segments)
            assertEquals(marks, plan.marks)
            assertEquals(chapters, plan.chapters)
            assertEquals(0, plan.marksDropped)
            assertNull(plan.wallStartMs)
            assertFalse(PassagePlan.staleSummary(plan, "A summary"))
        }
    }

    @Test
    fun aSpanInTheSilenceBetweenWordsTakesNoText() {
        val lines = listOf(worded(0, "one two three"), worded(10_000, "four five"))
        // Line one's words start at 0, 500 and 1000 ms; 2 to 9 s is its pause.
        val plan = PassagePlan.plan(lines, Span(2_000, 9_000), 20_000L)
        assertFalse(plan.changesTranscript)
        assertFalse(plan.snapped)
        assertEquals(lines, plan.segments)
    }

    @Test
    fun aLineAcrossTheStartKeepsItsWordsBeforeIt() {
        val lines = listOf(worded(0, "we agreed on the budget"), worded(10_000, "next item"))
        // Words at 0, 500, 1000, 1500, 2000 ms: "the" starts at 1500.
        val plan = PassagePlan.plan(lines, Span(1_500, 20_000), 20_000L)
        assertEquals(listOf(0), plan.trimmedLines)
        assertEquals(listOf(1), plan.removedLines)
        val kept = plan.segments.single()
        assertEquals("we agreed on", kept.text)
        assertEquals(0L, kept.audioMs)
        assertEquals(listOf("we", "agreed", "on"), kept.words!!.map { it.text })
        assertEquals(listOf(0L, 500L, 1000L), kept.words!!.map { it.ms })
        assertEquals(lines[0].timestampMs, kept.timestampMs)
    }

    @Test
    fun aLineAcrossTheEndKeepsItsWordsAfterItTimedFromItsFirstKeptWord() {
        val lines = listOf(worded(0, "intro"), worded(10_000, "so that was it okay next"))
        // Words at 10.0, 10.5, 11.0, 11.5, 12.0, 12.5 s; keep from "okay" (12.0 s).
        val plan = PassagePlan.plan(lines, Span(0, 12_000), 20_000L)
        assertEquals(listOf(0), plan.removedLines)
        assertEquals(listOf(1), plan.trimmedLines)
        val kept = plan.segments.single()
        assertEquals("okay next", kept.text)
        // The line now starts at its first kept word, and its words from there.
        assertEquals(12_000L, kept.audioMs)
        assertEquals(listOf(0L, 500L), kept.words!!.map { it.ms })
        // Every word keeps its place on the audio.
        assertEquals(listOf(12_000L, 12_500L), kept.words!!.map { kept.audioMs!! + it.ms })
        // The wall-clock stamp stays, so the order and what screens saw stay.
        assertEquals(lines[1].timestampMs, kept.timestampMs)
    }

    @Test
    fun aSpanInsideOneLineTakesTheWordsInTheMiddle() {
        val lines = listOf(worded(0, "please call me at home tomorrow"))
        // Remove "me at home" (1000 to 2500 ms).
        val plan = PassagePlan.plan(lines, Span(1_000, 2_500), 10_000L)
        val kept = plan.segments.single()
        assertEquals("please call tomorrow", kept.text)
        assertEquals(0L, kept.audioMs)
        assertEquals(listOf(0L, 500L, 2500L), kept.words!!.map { it.ms })
        assertEquals(listOf(0), plan.trimmedLines)
    }

    @Test
    fun spansFromWordsCutWhereTheUserPicked() {
        val lines = listOf(worded(0, "a b c d"), worded(10_000, "e f g h"))
        // From "c" (1000 ms) in line 0 to the end of "f" (that is, "g" at 11000) in line 1.
        val span = PassagePlan.spanFor(lines, 0, 1, 20_000L, firstWord = 2, lastWord = 1)!!
        assertEquals(Span(1_000, 11_000), span)
        val plan = PassagePlan.plan(lines, span, 20_000L)
        assertEquals(listOf("a b", "g h"), plan.segments.map { it.text })
        assertEquals(11_000L, plan.segments[1].audioMs)
        // Through a line's last word is to the end of the line.
        assertEquals(Span(10_000, 20_000), PassagePlan.spanFor(lines, 1, 1, 20_000L, lastWord = 3))
    }

    @Test
    fun aLineWithoutWordTimingsSnapsTheSpanOutToItsEdges() {
        // Asked 15 to 25 s: both edges fall inside untimed-word lines.
        val plan = PassagePlan.plan(plain, Span(15_000, 25_000), 40_000L)
        assertTrue(plan.snapped)
        assertEquals(Span(15_000, 25_000), plan.asked)
        assertEquals(Span(10_000, 30_000), plan.span)
        assertEquals(listOf(1, 2), plan.removedLines)
        assertTrue(plan.trimmedLines.isEmpty())
    }

    @Test
    fun onlyTheEdgeThatCannotBeCutMoves() {
        val lines = listOf(line(0, "no words here"), worded(10_000, "one two three four"))
        // Start inside the untimed line, end inside the worded one at "three".
        val plan = PassagePlan.plan(lines, Span(5_000, 11_000), 20_000L)
        assertEquals(Span(0, 11_000), plan.span)
        assertEquals(listOf(0), plan.removedLines)
        assertEquals("three four", plan.segments.single().text)
    }

    @Test
    fun wordsThatCannotBeFoundInTheTextSnapToo() {
        // The text was edited so the timed words no longer appear in it.
        val edited = line(0, "completely different", listOf(WordStamp(0, "alpha"), WordStamp(500, "beta")))
        val lines = listOf(edited, line(10_000, "after"))
        val plan = PassagePlan.plan(lines, Span(500, 10_000), 20_000L)
        assertEquals(Span(0, 10_000), plan.span)
        assertEquals(listOf(0), plan.removedLines)
    }

    @Test
    fun aLineLosingEveryWordGoesWhole() {
        val lines = listOf(worded(0, "intro"), worded(10_000, "gone too"))
        // The span starts after line 1 begins but before its first word.
        val words = listOf(WordStamp(400, "gone"), WordStamp(900, "too"))
        val late = lines[1].copy(words = words)
        val plan = PassagePlan.plan(listOf(lines[0], late), Span(10_200, 20_000), 20_000L)
        assertEquals(listOf(1), plan.removedLines)
    }

    @Test
    fun theSpanStopsAtAnUnfinishedTranscriptsResumePoint() {
        // Transcribed to 30 s of a 60 s recording.
        val lines = PassagePlan.lines(plain, 60_000L, resumeMs = 35_000L)
        assertEquals(35_000L, lines.last().endMs)
        val span = PassagePlan.spanFor(plain, 3, 3, 60_000L, resumeMs = 35_000L)!!
        assertEquals(Span(30_000, 35_000), span)
        val plan = PassagePlan.plan(plain, span, 60_000L, resumeMs = 35_000L)
        // The untranscribed rest is not part of it.
        assertEquals(35_000L, plan.span.endMs)
        assertEquals(35_000L, PassagePlan.resumeAfter(plan, 35_000L))
    }

    @Test
    fun theResumePointStaysEvenInsideTheSpanUnlessNothingIsLeftAfterIt() {
        val inside = PassagePlan.plan(plain, Span(10_000, 30_000), 40_000L)
        assertEquals(25_000L, PassagePlan.resumeAfter(inside, 25_000L))
        assertEquals(5_000L, PassagePlan.resumeAfter(inside, 5_000L))
        assertNull(PassagePlan.resumeAfter(inside, null))
        // From before the resume point to the end of the recording: complete.
        val toEnd = PassagePlan.plan(plain, Span(20_000, 40_000), 40_000L)
        assertNull(PassagePlan.resumeAfter(toEnd, 25_000L))
        // To the end but starting after the resume point: words remain before it.
        assertEquals(15_000L, PassagePlan.resumeAfter(toEnd, 15_000L))
    }

    @Test
    fun highlightMarksInsideTheSpanAreDroppedAndOthersKept() {
        val marks = listOf(5_000L, 10_000L, 19_999L, 20_000L, 30_000L)
        val plan = PassagePlan.plan(plain, Span(10_000, 20_000), 40_000L, marks)
        assertEquals(listOf(5_000L, 20_000L, 30_000L), plan.marks)
        assertEquals(2, plan.marksDropped)
    }

    @Test
    fun highlightedLinesRemovedAreCounted() {
        val lines = listOf(
            line(0, "a"),
            line(10_000, "b", highlighted = true),
            line(20_000, "c", highlighted = true),
            line(30_000, "d")
        )
        val plan = PassagePlan.plan(lines, Span(10_000, 30_000), 40_000L)
        assertEquals(2, plan.highlightedRemoved)
        // A shortened line keeps its star.
        val worded = listOf(worded(0, "x y z").copy(highlighted = true), line(10_000, "w"))
        val trim = PassagePlan.plan(worded, Span(500, 10_000), 20_000L)
        assertEquals("x", trim.segments.first().text)
        assertTrue(trim.segments.first().highlighted)
        assertEquals(0, trim.highlightedRemoved)
    }

    @Test
    fun aChapterStartingInsideTheSpanMovesToItsFirstKeptLine() {
        val chapters = listOf(
            Chapter("Opening", t0),
            Chapter("Budget", t0 + 10_000),
            Chapter("Questions", t0 + 30_000)
        )
        // Remove the first Budget line only: Budget keeps the hiring line.
        val plan = PassagePlan.plan(plain, Span(10_000, 20_000), 40_000L, chapters = chapters)
        assertEquals(listOf("Opening", "Budget", "Questions"), plan.chapters.map { it.title })
        assertEquals(t0 + 20_000, plan.chapters[1].startMs)
        assertEquals(1, plan.chaptersMoved)
        assertEquals(0, plan.chaptersDropped)
    }

    @Test
    fun aChapterLeftWithNoLinesIsDropped() {
        val chapters = listOf(
            Chapter("Opening", t0),
            Chapter("Budget", t0 + 10_000),
            Chapter("Questions", t0 + 30_000)
        )
        val plan = PassagePlan.plan(plain, Span(10_000, 30_000), 40_000L, chapters = chapters)
        assertEquals(listOf("Opening", "Questions"), plan.chapters.map { it.title })
        assertEquals(1, plan.chaptersDropped)
        assertEquals(0, plan.chaptersMoved)
        // Chapters the span does not touch are the same objects, in place.
        assertEquals(chapters[0], plan.chapters[0])
        assertEquals(chapters[2], plan.chapters[1])
    }

    @Test
    fun chaptersAreLeftAloneWhenOnlyWordsAreCut() {
        val lines = listOf(worded(0, "a b c"), worded(10_000, "d e f"))
        val chapters = listOf(Chapter("Two", t0 + 10_000), Chapter("One", t0))
        val plan = PassagePlan.plan(lines, Span(500, 10_500), 20_000L, chapters = chapters)
        assertEquals(chapters, plan.chapters)
    }

    @Test
    fun photosTakenWhileThePassageWasSpokenAreCounted() {
        val plan = PassagePlan.plan(plain, Span(10_000, 30_000), 40_000L)
        assertEquals(t0 + 10_000, plan.wallStartMs)
        assertEquals(t0 + 30_000, plan.wallEndMs)
        val photos = listOf(
            "m1_${t0 + 5_000}.jpg",
            "m1_${t0 + 12_000}.jpg",
            "m1_${t0 + 29_999}.jpg",
            "m1_${t0 + 30_000}.jpg",
            "imported.jpg"
        )
        assertEquals(2, PassagePlan.photosTakenDuring(photos, plan))
        assertNull(PassagePlan.captureTimeOf("imported.jpg"))
        assertEquals(42L, PassagePlan.captureTimeOf("m_1_42.jpg"))
        // Nothing timed was touched: no photos.
        val none = PassagePlan.plan(plain, Span(10_000, 10_000), 40_000L)
        assertEquals(0, PassagePlan.photosTakenDuring(photos, none))
    }

    @Test
    fun theSummaryIsStaleOnlyWhenThereIsOneAndTheWordsChanged() {
        val plan = PassagePlan.plan(plain, Span(0, 10_000), 40_000L)
        assertTrue(PassagePlan.staleSummary(plan, "Notes"))
        assertFalse(PassagePlan.staleSummary(plan, "  "))
        val silentOnly = PassagePlan.plan(listOf(worded(0, "a b")), Span(5_000, 8_000), 10_000L)
        assertFalse(PassagePlan.staleSummary(silentOnly, "Notes"))
    }

    @Test
    fun applyingThePlanToAMeetingWritesEveryPart() {
        val meeting = Meeting(id = "m1", title = "Weekly", createdAtMs = t0, summary = "S")
        meeting.segments.addAll(plain)
        meeting.highlightMarksMs.addAll(listOf(12_000L, 35_000L))
        meeting.chapters.addAll(listOf(Chapter("Opening", t0), Chapter("Budget", t0 + 10_000)))
        val plan = PassagePlan.plan(
            meeting.segments, Span(10_000, 30_000), 40_000L,
            meeting.highlightMarksMs, meeting.chapters
        )
        PassagePlan.applyTo(meeting, plan)
        assertEquals(listOf("Good morning everyone.", "Any questions?"), meeting.segments.map { it.text })
        assertEquals(listOf(35_000L), meeting.highlightMarksMs)
        // Budget owned every line from 10 s on; it now starts at the one it keeps.
        assertEquals(listOf("Opening", "Budget"), meeting.chapters.map { it.title })
        assertEquals(t0 + 30_000, meeting.chapters[1].startMs)
        assertTrue(meeting.summaryStale)
    }

    @Test
    fun removingAPassageMarksEverySummaryOutOfDate() {
        val kept = com.meetily.mobile.data.SavedSummary("standup", "m", "en", "Kept", 5L)
        val meeting = Meeting(id = "m1", title = "Weekly", createdAtMs = t0, summary = "S")
        meeting.segments.addAll(plain)
        meeting.summaries = mutableListOf(kept)
        val plan = PassagePlan.plan(meeting.segments, Span(10_000, 30_000), 40_000L)
        assertTrue(PassagePlan.staleSummaries(plan, meeting))
        PassagePlan.applyTo(meeting, plan)
        assertTrue(meeting.summaryStale)
        assertEquals(listOf(true), meeting.summaries.map { it.stale })
        assertEquals("Kept", meeting.summaries.single().text)

        // Only kept summaries (none shown): they go out of date all the same.
        val onlyKept = Meeting(id = "m2", title = "Weekly", createdAtMs = t0)
        onlyKept.segments.addAll(plain)
        onlyKept.summaries = mutableListOf(kept)
        val plan2 = PassagePlan.plan(onlyKept.segments, Span(10_000, 30_000), 40_000L)
        assertFalse(PassagePlan.staleSummary(plan2, onlyKept.summary))
        assertTrue(PassagePlan.staleSummaries(plan2, onlyKept))
        PassagePlan.applyTo(onlyKept, plan2)
        assertFalse(onlyKept.summaryStale)
        assertTrue(onlyKept.summaries.single().stale)

        // Silence only, no words changed: nothing is marked.
        val untouched = Meeting(id = "m3", title = "Weekly", createdAtMs = t0)
        untouched.segments.add(worded(0, "a b"))
        untouched.summaries = mutableListOf(kept)
        val silentOnly = PassagePlan.plan(untouched.segments, Span(5_000, 8_000), 10_000L)
        assertFalse(PassagePlan.staleSummaries(silentOnly, untouched))
        PassagePlan.applyTo(untouched, silentOnly)
        assertFalse(untouched.summaries.single().stale)
    }

    @Test
    fun applyingTheSameSpanAgainChangesNothing() {
        val lines = listOf(worded(0, "a b c d"), worded(10_000, "e f g h"), worded(20_000, "i j"))
        val span = Span(1_000, 11_000)
        val once = PassagePlan.plan(lines, span, 30_000L)
        val twice = PassagePlan.plan(once.segments, once.span, 30_000L)
        assertFalse(twice.changesTranscript)
        assertEquals(once.segments, twice.segments)
        val confirmed = PassagePlan.plan(once.segments, once.span, 30_000L, snap = false)
        assertFalse(confirmed.changesTranscript)
        assertEquals(once.segments, confirmed.segments)
    }

    @Test
    fun aConfirmedSpanAppliedAgainNeverTakesTheLineBeforeIt() {
        // Lines without word timings, as from a subtitle import with audio.
        val span = PassagePlan.spanFor(plain, 1, 1, 40_000L)!!
        assertEquals(Span(10_000, 20_000), span)
        val first = PassagePlan.plan(plain, span, 40_000L)
        assertEquals(listOf(1), first.removedLines)
        // Applied again to the result (the screen's copy after a sync, or the
        // stored meeting once the line was also deleted by hand): the line
        // before now runs to 20 s, across the span's start, and stays.
        for (duration in listOf(40_000L, null)) {
            val again = PassagePlan.plan(first.segments, first.span, duration, snap = false)
            assertEquals(span, again.span)
            assertFalse(again.snapped)
            assertFalse(again.changesTranscript)
            assertEquals(listOf(plain[0], plain[2], plain[3]), again.segments)
        }
    }

    @Test
    fun aConfirmedSpanToTheEndAppliedAgainKeepsEveryLineBeforeIt() {
        val span = PassagePlan.spanFor(plain, 2, 3, 40_000L)!!
        val first = PassagePlan.plan(plain, span, 40_000L)
        assertEquals(Span(20_000, PassagePlan.END), first.span)
        assertEquals(listOf(2, 3), first.removedLines)
        val again = PassagePlan.plan(first.segments, first.span, 40_000L, snap = false)
        assertFalse(again.changesTranscript)
        assertEquals(listOf(plain[0], plain[1]), again.segments)
    }

    @Test
    fun aConfirmedLineStillGoesWhenTheLineAfterItWasDeletedMeanwhile() {
        // The user confirmed 10 to 20 s (line 1) and deleted line 2 by hand
        // while the audio was written: line 1 now runs to 30 s, past the
        // span's end, but it was confirmed and its audio silenced.
        val span = Span(10_000, 20_000)
        val stored = listOf(plain[0], plain[1], plain[3])
        val plan = PassagePlan.plan(stored, span, 40_000L, snap = false)
        assertEquals(span, plan.span)
        assertEquals(listOf(1), plan.removedLines)
        assertEquals(listOf(plain[0], plain[3]), plan.segments)
        // A line with word timings is cut at its words as before.
        val timed = listOf(worded(0, "a b c d"), worded(10_000, "e f g h"), worded(30_000, "i j"))
        val cut = PassagePlan.plan(timed, Span(1_000, 11_000), 40_000L, snap = false)
        assertEquals(listOf("a b", "g h", "i j"), cut.segments.map { it.text })
    }

    @Test
    fun untimedLinesAreKept() {
        val lines = plain + TranscriptSegment(timestampMs = t0 + 15_000, text = "typed note")
        val plan = PassagePlan.plan(lines, Span(0, PassagePlan.END), 40_000L)
        assertEquals(listOf("typed note"), plan.segments.map { it.text })
        assertEquals(4, plan.removedLines.size)
    }
}
