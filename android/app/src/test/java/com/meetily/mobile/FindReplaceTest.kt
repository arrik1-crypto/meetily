package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.FindReplace
import com.meetily.mobile.data.FindReplace.Area
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixing a misheard name across a meeting in one go. Over-broad replacement
 * is the risk, so whole word is the default, the preview counts exactly what
 * Apply changes, and Undo puts back only what the replace itself changed.
 */
class FindReplaceTest {

    private val all = Area.values().toSet()

    private fun query(
        find: String,
        replace: String,
        wholeWord: Boolean = true,
        matchCase: Boolean = false
    ) = FindReplace.Query.of(find, replace, FindReplace.Options(wholeWord, matchCase))

    private fun replaced(text: String, q: FindReplace.Query): String =
        FindReplace.replaceIn(text, FindReplace.pattern(q)!!, q.replace)

    private fun meeting() = Meeting(
        id = "m1",
        title = "Weekly",
        createdAtMs = 0L,
        segments = mutableListOf(
            TranscriptSegment(
                timestampMs = 1_000L,
                text = "Hello Jon, how are you",
                audioMs = 0L,
                words = listOf(
                    WordStamp(0, "Hello"), WordStamp(400, "Jon,"), WordStamp(900, "how"),
                    WordStamp(1100, "are"), WordStamp(1300, "you")
                )
            ),
            TranscriptSegment(timestampMs = 2_000L, text = "Jonas said jon's plan works", speaker = "Priya"),
            TranscriptSegment(timestampMs = 3_000L, text = "nothing to see here")
        ),
        notes = "- ask Jon about the budget",
        summary = "## Overview\nJon presented the plan.",
        actionItems = mutableListOf(
            ActionItem("Jon to send the deck", owner = "Jon", done = true),
            ActionItem("Book the room")
        )
    )

    @Test
    fun wholeWordIsOnAndMatchCaseOffByDefault() {
        val options = FindReplace.Options()
        assertTrue(options.wholeWord)
        assertFalse(options.matchCase)
        val q = FindReplace.Query.of("Jon", "John", options)
        assertEquals(
            "John, John's plan, JONAS and John.",
            replaced("Jon, jon's plan, JONAS and JON.", q)
        )
    }

    @Test
    fun matchCaseLeavesOtherCasingAlone() {
        val q = query("Jon", "John", matchCase = true)
        assertEquals("John, jon's plan and JON.", replaced("Jon, jon's plan and JON.", q))
    }

    @Test
    fun wholeWordOffMatchesInsideWords() {
        val q = query("Jon", "John", wholeWord = false)
        assertEquals("John, Johnas", replaced("Jon, Jonas", q))
    }

    @Test
    fun wholeWordStillFindsTermsEndingInPunctuation() {
        assertEquals("I like Kotlin, not C#", replaced("I like C++, not C#", query("C++", "Kotlin")))
        // The left edge is a letter, so it is still bounded: "ABC++" is not a match.
        assertEquals("ABC++", replaced("ABC++", query("C++", "Kotlin")))
        assertEquals("ask Doctor Lee", replaced("ask Dr. Lee", query("Dr.", "Doctor")))
    }

    @Test
    fun accentedAndNonLatinLettersCountAsWordCharacters() {
        // "é" is a letter, so "Jon" inside "Joné" is not a whole word.
        assertEquals("Joné and John", replaced("Joné and Jon", query("Jon", "John")))
    }

    @Test
    fun queryIsTrimmedAndBlankFindsNothing() {
        val q = query(" Jon ", "John ")
        assertEquals("Jon", q.find)
        assertEquals("John", q.replace)
        assertNull(FindReplace.pattern(query("   ", "x")))
    }

    @Test
    fun aMatchAlreadyReadingAsTheReplacementIsNotAChange() {
        val q = query("john", "John")
        val pattern = FindReplace.pattern(q)!!
        // Two matches, one of them already right.
        assertEquals(1, FindReplace.edits("John and john", pattern, q.replace).size)
    }

    @Test
    fun replacingWithNothingTakesOneSpaceWithIt() {
        val q = query("um", "")
        assertEquals("we should go", replaced("we um should go", q))
        assertEquals("we said.", replaced("we said um.", q))
        assertEquals("so we go", replaced("um so we go", q))
    }

    @Test
    fun countsEachAreaAsApplyWould() {
        val m = meeting()
        val q = query("Jon", "John")
        val counts = FindReplace.count(FindReplace.Source.of(m), q)
        // "Jon," and "jon's", not "Jonas".
        assertEquals(2, counts.transcriptMatches)
        assertEquals(2, counts.transcriptLines)
        assertEquals(1, counts.notes)
        assertEquals(1, counts.summary)
        // The task and its owner.
        assertEquals(2, counts.actionItems)
        assertEquals(6, counts.total())
        assertEquals(3, counts.total(setOf(Area.TRANSCRIPT, Area.NOTES)))

        val snapshot = FindReplace.apply(m, q, all)!!
        assertEquals(counts, snapshot.counts)
    }

    @Test
    fun applyKeepsWordTimingsOneForOne() {
        val m = meeting()
        FindReplace.apply(m, query("Jon", "John"), setOf(Area.TRANSCRIPT))
        val line = m.segments[0]
        assertEquals("Hello John, how are you", line.text)
        assertEquals(
            listOf(
                WordStamp(0, "Hello"), WordStamp(400, "John,"), WordStamp(900, "how"),
                WordStamp(1100, "are"), WordStamp(1300, "you")
            ),
            line.words
        )
        // Speaker, timestamp and audio offset are untouched.
        assertEquals(1_000L, line.timestampMs)
        assertEquals(0L, line.audioMs)
        assertEquals("Priya", m.segments[1].speaker)
        assertEquals("Jonas said John's plan works", m.segments[1].text)
    }

    @Test
    fun applyReAlignsWhenTheWordCountChangesAndKeepsUntouchedTimings() {
        val m = meeting()
        FindReplace.apply(m, query("Jon", "John Smith"), setOf(Area.TRANSCRIPT))
        val words = m.segments[0].words!!
        assertEquals(listOf("Hello", "John", "Smith,", "how", "are", "you"), words.map { it.text })
        assertEquals(WordStamp(0, "Hello"), words[0])
        assertEquals(400L, words[1].ms)
        assertTrue(words[2].ms in 401L until 900L)
        assertEquals(
            listOf(WordStamp(900, "how"), WordStamp(1100, "are"), WordStamp(1300, "you")),
            words.subList(3, 6)
        )
    }

    @Test
    fun onlyTheChosenAreasChange() {
        val m = meeting()
        FindReplace.apply(m, query("Jon", "John"), setOf(Area.NOTES))
        assertEquals("- ask John about the budget", m.notes)
        assertEquals("Hello Jon, how are you", m.segments[0].text)
        assertEquals("## Overview\nJon presented the plan.", m.summary)
        assertEquals("Jon to send the deck", m.actionItems[0].task)
    }

    @Test
    fun transcriptChangeMarksAnExistingSummaryStale() {
        val m = meeting()
        assertFalse(m.summaryStale)
        val snapshot = FindReplace.apply(m, query("Jon", "John"), all)!!
        assertTrue(m.summaryStale)
        // Recorded by which summary it marked, for Undo.
        assertEquals(listOf(FindReplace.SummaryId(null, 0L)), snapshot.markedStale)

        val notesOnly = meeting()
        FindReplace.apply(notesOnly, query("Jon", "John"), setOf(Area.NOTES, Area.SUMMARY))
        assertFalse(notesOnly.summaryStale)

        val noSummary = meeting().apply { summary = "" }
        FindReplace.apply(noSummary, query("Jon", "John"), setOf(Area.TRANSCRIPT))
        assertFalse(noSummary.summaryStale)
    }

    @Test
    fun aLineIsNeverEmptiedByAReplace() {
        val m = meeting().apply {
            segments.add(TranscriptSegment(timestampMs = 4_000L, text = "um"))
        }
        val q = query("um", "")
        assertEquals(0, FindReplace.count(FindReplace.Source.of(m), q).transcriptMatches)
        assertNull(FindReplace.apply(m, q, setOf(Area.TRANSCRIPT)))
        assertEquals("um", m.segments[3].text)
    }

    @Test
    fun nothingToChangeGivesNoSnapshot() {
        val m = meeting()
        assertNull(FindReplace.apply(m, query("Zed", "Zara"), all))
    }

    @Test
    fun undoRoundTripRestoresTheMeetingExactly() {
        val original = meeting()
        val m = meeting()
        val snapshot = FindReplace.apply(m, query("Jon", "John Smith"), all)!!
        assertTrue(m.summaryStale)
        val result = FindReplace.undo(m, snapshot)
        assertEquals(0, result.skipped)
        // Two lines, notes, summary, one action item.
        assertEquals(5, result.restored)
        assertEquals(original.segments, m.segments)
        assertEquals(original.notes, m.notes)
        assertEquals(original.summary, m.summary)
        assertEquals(original.actionItems, m.actionItems)
        assertEquals(original.summaryStale, m.summaryStale)
        assertEquals(original.toJson().toString(), m.toJson().toString())
    }

    @Test
    fun undoLeavesWhatWasEditedSinceAndKeepsTheStaleMark() {
        val m = meeting()
        val snapshot = FindReplace.apply(m, query("Jon", "John"), all)!!
        // Edited after the replace: this line and the notes stay as edited.
        m.segments[0] = m.segments[0].copy(text = "Hello John, how are you doing", words = null)
        m.notes = "- ask John about the budget today"
        // A tick on the action item is not a wording change; its wording still goes back.
        m.actionItems[0] = m.actionItems[0].copy(done = false)
        // A line added above shifts indices; the changed line is still found.
        m.segments.add(0, TranscriptSegment(timestampMs = 500L, text = "intro"))

        val result = FindReplace.undo(m, snapshot)
        assertEquals(2, result.skipped)
        assertEquals(3, result.restored)
        assertEquals("Hello John, how are you doing", m.segments[1].text)
        assertEquals("Jonas said jon's plan works", m.segments[2].text)
        assertEquals("## Overview\nJon presented the plan.", m.summary)
        assertEquals("- ask John about the budget today", m.notes)
        assertEquals("Jon to send the deck", m.actionItems[0].task)
        assertEquals("Jon", m.actionItems[0].owner)
        assertFalse(m.actionItems[0].done)
        // Not every line went back, so the summary still reads as stale.
        assertTrue(m.summaryStale)
    }

    @Test
    fun undoStillRestoresALineHighlightedOrTaggedSinceAndKeepsThat() {
        val original = meeting()
        val m = meeting()
        val snapshot = FindReplace.apply(m, query("Jon", "John"), all)!!
        // Not wording changes: a highlight, and a speaker named everywhere.
        m.segments[0] = m.segments[0].copy(highlighted = true)
        m.segments[1] = m.segments[1].copy(speaker = "Priya Shah", clusterId = 2)

        val result = FindReplace.undo(m, snapshot)
        assertEquals(0, result.skipped)
        assertEquals(original.segments[0].text, m.segments[0].text)
        assertEquals(original.segments[0].words, m.segments[0].words)
        assertTrue(m.segments[0].highlighted)
        assertEquals(original.segments[1].text, m.segments[1].text)
        assertEquals("Priya Shah", m.segments[1].speaker)
        assertEquals(2, m.segments[1].clusterId)
        // Every line went back, so the stale mark did too.
        assertEquals(original.summaryStale, m.summaryStale)
    }

    @Test
    fun snapshotRestoresAnOwnerThatTheReplaceCleared() {
        val m = meeting()
        val snapshot = FindReplace.apply(m, query("Jon", ""), setOf(Area.ACTION_ITEMS))!!
        assertNull(m.actionItems[0].owner)
        assertEquals("to send the deck", m.actionItems[0].task)
        FindReplace.undo(m, snapshot)
        assertEquals("Jon", m.actionItems[0].owner)
        assertEquals("Jon to send the deck", m.actionItems[0].task)
    }

    @Test
    fun vocabularyIsOfferedForCorrectedNamesOnly() {
        val counts = FindReplace.Counts(transcriptMatches = 3, transcriptLines = 2)
        assertEquals("John", FindReplace.vocabTerm(query("Jon", "John"), counts, ""))
        assertEquals("K8s", FindReplace.vocabTerm(query("kates", "K8s"), counts, "OKR"))
        assertEquals("東京", FindReplace.vocabTerm(query("東京都", "東京"), counts, ""))
        // A common word is not jargon.
        assertNull(FindReplace.vocabTerm(query("their", "there"), counts, ""))
        // Already listed, in any case.
        assertNull(FindReplace.vocabTerm(query("Jon", "John"), counts, "Recap, john\nOKR"))
        // Nothing in the transcript changed: not a misheard term.
        assertNull(FindReplace.vocabTerm(query("Jon", "John"), FindReplace.Counts(notes = 2), ""))
        // Too long to be a term, or would split into several.
        assertNull(FindReplace.vocabTerm(query("x", "Alpha Beta Gamma Delta Epsilon"), counts, ""))
        assertNull(FindReplace.vocabTerm(query("x", "Smith, John"), counts, ""))
        assertNull(FindReplace.vocabTerm(query("Jon", ""), counts, ""))
    }

    @Test
    fun lineWordsAreOfferedOnceEachWithoutPunctuation() {
        assertEquals(
            listOf("Hello", "Jon", "how", "are", "you"),
            FindReplace.wordsOf("Hello Jon, how are you? hello")
        )
        assertEquals(
            listOf("東京", "都", "で"),
            FindReplace.wordsOf(
                "東京都で",
                listOf(WordStamp(0, "東京"), WordStamp(1, "都"), WordStamp(2, "で"))
            )
        )
    }

    @Test
    fun countIsSafeOnACopyWhileTheMeetingChanges() {
        val m = meeting()
        val source = FindReplace.Source.of(m)
        m.segments.clear()
        val counts = FindReplace.count(source, query("Jon", "John"))
        assertNotNull(counts)
        assertEquals(2, counts.transcriptMatches)
    }
}
