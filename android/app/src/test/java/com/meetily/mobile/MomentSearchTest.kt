package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.search.FindInMeeting
import com.meetily.mobile.search.FindInMeeting.Anchor
import com.meetily.mobile.search.FindInMeeting.Match
import com.meetily.mobile.search.HomeSearch
import com.meetily.mobile.search.LibrarySearch
import com.meetily.mobile.search.MatchSnippet
import com.meetily.mobile.search.TextFold
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search that lands on the moment: the snippet under a home search hit, the
 * line it opens at, and Find in meeting's matching and next / previous.
 */
class MomentSearchTest {

    private val now = 1_800_000_000_000L

    private fun highlighted(snippet: MatchSnippet.Snippet): String =
        snippet.text.substring(snippet.matchStart, snippet.matchEnd)

    // --- Snippets --------------------------------------------------------------

    private val long = "We opened with the weather and the commute, then spent a long while on " +
        "hiring before Priya said the vendor contract is finally signed and the legal team " +
        "can stop chasing it, which everyone was relieved to hear after three months of " +
        "back and forth with procurement."

    @Test
    fun shortTextIsShownWholeWithTheMatchMarked() {
        val text = "The Vendor contract is signed"
        val snippet = MatchSnippet.around(text, 4, 10)
        assertEquals(text, snippet.text)
        assertEquals("Vendor", highlighted(snippet))
    }

    @Test
    fun longTextIsCutAroundTheMatchWithEllipses() {
        val at = long.indexOf("vendor contract")
        val snippet = MatchSnippet.around(long, at, at + "vendor contract".length)
        assertEquals("vendor contract", highlighted(snippet))
        assertTrue(snippet.text, snippet.text.startsWith(MatchSnippet.ELLIPSIS))
        assertTrue(snippet.text, snippet.text.endsWith(MatchSnippet.ELLIPSIS))
        val words = snippet.spoken()
        // About 80 to 120 characters, as asked for.
        assertTrue(words.length.toString(), words.length in 70..MatchSnippet.LENGTH)
        // Some context comes before the match, more after it.
        assertTrue(snippet.matchStart > 10)
        assertTrue(snippet.text.length - snippet.matchEnd > snippet.matchStart)
    }

    @Test
    fun cutsFallBetweenWords() {
        val at = long.indexOf("vendor")
        val snippet = MatchSnippet.around(long, at, at + 6)
        val words = snippet.spoken()
        // Each end of the quote is a whole word of the original.
        val first = words.substringBefore(' ')
        val last = words.substringAfterLast(' ')
        assertTrue(first, Regex("(^|\\s)" + Regex.escape(first) + "\\s").containsMatchIn(long))
        assertTrue(last, Regex("\\s" + Regex.escape(last) + "(\\s|$)").containsMatchIn(long))
    }

    @Test
    fun aMatchAtEitherEndGivesItsRoomToTheOtherSide() {
        val start = MatchSnippet.around(long, 0, 2)
        assertEquals("We", highlighted(start))
        assertFalse(start.text.startsWith(MatchSnippet.ELLIPSIS))
        assertTrue(start.text.endsWith(MatchSnippet.ELLIPSIS))
        assertTrue(start.spoken().length > 80)

        val end = MatchSnippet.around(long, long.length - "procurement.".length, long.length)
        assertEquals("procurement.", highlighted(end))
        assertTrue(end.text.startsWith(MatchSnippet.ELLIPSIS))
        assertFalse(end.text.endsWith(MatchSnippet.ELLIPSIS))
        assertTrue(end.spoken().length > 80)
    }

    @Test
    fun lineBreaksAndRunsOfSpaceBecomeOneSpace() {
        val text = "ROADMAP\n\nMilestone   Kappa\n— Whiteboard"
        val at = text.indexOf("Milestone")
        val snippet = MatchSnippet.around(text, at, at + "Milestone   Kappa".length)
        assertEquals("ROADMAP Milestone Kappa — Whiteboard", snippet.text)
        assertEquals("Milestone Kappa", highlighted(snippet))
    }

    @Test
    fun unspacedScriptsAreCutWithoutHuntingForSpaces() {
        val text = "我们讨论了很多事情".repeat(20) + "预算" + "然后大家都同意了这个计划".repeat(20)
        val at = text.indexOf("预算")
        val snippet = MatchSnippet.around(text, at, at + 2)
        assertEquals("预算", highlighted(snippet))
        assertTrue(snippet.spoken().length in 90..MatchSnippet.LENGTH)
    }

    @Test
    fun outOfRangeOffsetsDoNotThrow() {
        val snippet = MatchSnippet.around("short", 3, 99)
        assertEquals("short", snippet.text)
        assertEquals("rt", highlighted(snippet))
        val empty = MatchSnippet.around("", 0, 0)
        assertEquals("", empty.text)
        assertFalse(empty.hasMatch)
    }

    @Test
    fun spokenTextHasNoEllipses() {
        val at = long.indexOf("vendor")
        val spoken = MatchSnippet.around(long, at, at + 6).spoken()
        assertFalse(spoken.contains(MatchSnippet.ELLIPSIS))
        assertEquals(spoken.trim(), spoken)
    }

    // --- Home search: which line, and the snippet ------------------------------

    private fun meeting(vararg lines: Pair<String?, String>): Meeting =
        Meeting(id = "m", title = "Weekly sync", createdAtMs = now).apply {
            for ((i, line) in lines.withIndex()) {
                segments.add(
                    TranscriptSegment(
                        timestampMs = now + i * 60_000L,
                        text = line.second,
                        speaker = line.first,
                        audioMs = i * 60_000L
                    )
                )
            }
        }

    private fun moment(meeting: Meeting, typed: String): HomeSearch.Moment? {
        val query = HomeSearch.normalizeQuery(typed)
        val indexed = HomeSearch.index(meeting)
        assertTrue(HomeSearch.matches(indexed.blob, query) || HomeSearch.moment(meeting, indexed, query) == null)
        return HomeSearch.moment(meeting, indexed, query)
    }

    @Test
    fun pointsAtTheLineWithTheMatchAndQuotesIt() {
        val m = meeting(
            null to "Morning everyone",
            "Ana" to "",
            "Ana" to "The vendor contract is signed",
            null to "Great news"
        )
        val hit = moment(m, "VENDOR contract")!!
        assertEquals(HomeSearch.Field.TRANSCRIPT, hit.field)
        // Blank lines are left out of the blob; the index still names the segment.
        assertEquals(2, hit.segmentIndex)
        assertEquals("0:02:00", hit.timeLabel)
        assertEquals("Ana: The vendor contract is signed", hit.snippet.text)
        assertEquals("vendor contract", highlighted(hit.snippet))
        assertTrue(hit.inLineText)
    }

    @Test
    fun aWholeWordBeatsAnEarlierPartOfAWord() {
        val m = meeting(
            null to "The planet is warming",
            null to "Here is the plan for Monday"
        )
        val hit = moment(m, "plan")!!
        assertEquals(1, hit.segmentIndex)
        assertEquals("plan", highlighted(hit.snippet))
    }

    @Test
    fun theEarliestOfEqualMatchesWins() {
        val m = meeting(null to "budget first", null to "budget again")
        assertEquals(0, moment(m, "budget")!!.segmentIndex)
    }

    @Test
    fun wordsBeatASpeakersName() {
        val m = meeting("Priya" to "Thanks all", null to "I spoke to Priya yesterday")
        val hit = moment(m, "priya")!!
        assertEquals(1, hit.segmentIndex)
        assertTrue(hit.inLineText)
    }

    @Test
    fun aMatchOnlyInASpeakersNameStillLandsButNotForFind() {
        val m = meeting(null to "Hello", "Priya" to "Thanks all")
        val hit = moment(m, "priya")!!
        assertEquals(1, hit.segmentIndex)
        assertFalse(hit.inLineText)
        assertEquals("Priya", highlighted(hit.snippet))
        // As typed across the label and the words.
        val across = moment(m, "priya: thanks")!!
        assertEquals(1, across.segmentIndex)
        assertFalse(across.inLineText)
    }

    @Test
    fun aTitleOrTagMatchKeepsTheRowAsItWas() {
        val m = meeting(null to "Nothing relevant").apply { tags.add("client-x") }
        assertNull(moment(m, "weekly"))
        assertNull(moment(m, "client-x"))
    }

    @Test
    fun otherFieldsAreQuotedWithoutALine() {
        val m = meeting(null to "Nothing relevant").apply {
            summary = "We agreed the Q3 launch date."
            actionItems.add(ActionItem("Send the revised deck", "Priya"))
            photoTexts["p.jpg"] = "ROADMAP\nMilestone Kappa"
        }
        val summary = moment(m, "q3 launch")!!
        assertEquals(HomeSearch.Field.SUMMARY, summary.field)
        assertEquals(-1, summary.segmentIndex)
        assertNull(summary.timeLabel)
        assertEquals("Q3 launch", highlighted(summary.snippet))

        val action = moment(m, "revised deck")!!
        assertEquals(HomeSearch.Field.ACTION_ITEM, action.field)
        assertEquals("Send the revised deck — Priya", action.snippet.text)

        val photo = moment(m, "roadmap milestone")!!
        assertEquals(HomeSearch.Field.PHOTO_TEXT, photo.field)
        assertEquals("ROADMAP Milestone", highlighted(photo.snippet))
    }

    @Test
    fun theTranscriptComesBeforeOtherFields() {
        val m = meeting(null to "the budget is fine").apply { summary = "Budget approved" }
        assertEquals(HomeSearch.Field.TRANSCRIPT, moment(m, "budget")!!.field)
    }

    @Test
    fun aMeetingChangedSinceItWasIndexedIsNotPointedIntoBlindly() {
        val m = meeting(null to "alpha", null to "beta")
        val indexed = HomeSearch.index(m)
        m.segments.add(0, TranscriptSegment(now, "inserted"))
        assertNull(HomeSearch.moment(m, indexed, "beta"))
    }

    @Test
    fun offsetsHoldWhenLowercasingChangesALength() {
        // "İ" lowercases to two characters; offsets after it must still line up.
        val m = meeting(null to "İstanbul office", null to "the İzmir budget review")
        val hit = moment(m, "budget")!!
        assertEquals(1, hit.segmentIndex)
        assertEquals("budget", highlighted(hit.snippet))
    }

    @Test
    fun indexedBlobIsTheSameAsBefore() {
        val m = meeting("Ana" to "Hello There", null to "  ", null to "Bye").apply {
            notes = "Some Notes"
            summary = "A Summary"
        }
        assertEquals(
            "weekly sync\nsome notes\na summary\nana: hello there\nbye\n",
            HomeSearch.blobFor(m)
        )
    }

    // --- Ask your library: the moment a hit opens at ---------------------------

    @Test
    fun askLibraryHitsNameTheQuotedLineAndATerm() {
        val m = meeting(
            null to "Small talk",
            "Ana" to "The vendor contract deadline moved",
            null to "vendor again"
        ).apply { title = "Procurement" }
        val hit = LibrarySearch.search(listOf(m), "vendor contract deadline", nowMs = now).single()
        assertEquals(1, hit.segmentIndex)
        // The longest term in the line; the first of equals.
        assertEquals("contract", hit.term)
        assertTrue(hit.excerpts.first().contains("vendor contract deadline moved"))

        val summaryOnly = Meeting(id = "s", title = "Other", createdAtMs = now).apply {
            summary = "vendor contract review"
        }
        val other = LibrarySearch.search(listOf(summaryOnly), "vendor contract", nowMs = now).single()
        assertEquals(-1, other.segmentIndex)
        assertNull(other.term)
    }

    // --- Folding ---------------------------------------------------------------

    @Test
    fun foldingIgnoresCaseAndAccents() {
        assertEquals("cafe resume", TextFold.query("Café RÉSUMÉ"))
        assertEquals("naive senor", TextFold.query("naïve señor"))
        // A decomposed accent folds the same as a composed one.
        assertEquals("cafe", TextFold.query("Café"))
        assertEquals("οδοσ", TextFold.query("ΟΔΟΣ"))
        assertEquals("οδοσ", TextFold.query("οδος"))
    }

    @Test
    fun foldingLeavesOtherScriptsMarksAlone() {
        // が is not か: only lowercasing outside Latin, Greek and Cyrillic.
        assertEquals("が", TextFold.query("が"))
        assertEquals("한국", TextFold.query("한국"))
    }

    @Test
    fun foldedOffsetsMapBackToTheOriginal() {
        val text = "Café au lait"
        val folded = TextFold.fold(text)
        assertEquals("cafe au lait", folded.text)
        // "cafe" covers the combining accent after the e.
        assertEquals(0, folded.originalStart(0))
        assertEquals(5, folded.originalEnd(4))
        assertEquals(6, folded.originalStart(5))
    }

    // --- Find in meeting: matching ---------------------------------------------

    private val lines = listOf(
        "The budget is tight",
        "No mention here",
        "Budget, budget, BUDGET!",
        "Le budgét révisé"
    )

    private fun find(query: String, texts: List<String> = lines) =
        FindInMeeting.search(FindInMeeting.index(texts), query)

    @Test
    fun findsEveryMatchInOrderCaseAndAccentInsensitive() {
        val result = find("budget")
        assertEquals(
            listOf(
                Match(0, 4, 10),
                Match(2, 0, 6), Match(2, 8, 14), Match(2, 16, 22),
                Match(3, 3, 9)
            ),
            result.matches
        )
        assertFalse(result.capped)
        assertEquals(5, find("BUDGÉT").matches.size)
        assertEquals(listOf(Match(3, 10, 16)), find("revise").matches)
    }

    @Test
    fun matchesDoNotOverlapAndBlankFindsNothing() {
        assertEquals(listOf(Match(0, 0, 2), Match(0, 2, 4)), find("aa", listOf("aaaaa")).matches)
        assertTrue(find("").isEmpty)
        assertTrue(find("   ").isEmpty)
        assertTrue(find("kickoff").isEmpty)
    }

    @Test
    fun aDecomposedAccentIsInsideTheMatch() {
        val text = "Café time"
        assertEquals(listOf(Match(0, 0, 5)), find("café", listOf(text)).matches)
    }

    @Test
    fun manyMatchesAreCapped() {
        val big = listOf("a".repeat(FindInMeeting.MAX_MATCHES + 10))
        val result = find("a", big)
        assertTrue(result.capped)
        assertEquals(FindInMeeting.MAX_MATCHES, result.matches.size)
    }

    @Test
    fun rangesInOneLineAreForItsCurrentText() {
        val folded = TextFold.query("budget")
        assertArrayEquals(intArrayOf(0, 6, 8, 14, 16, 22), FindInMeeting.rangesIn(lines[2], folded))
        assertArrayEquals(intArrayOf(), FindInMeeting.rangesIn(lines[1], folded))
        assertArrayEquals(intArrayOf(), FindInMeeting.rangesIn("", folded))
    }

    // --- Find in meeting: next, previous and where it starts -------------------

    private val matches = find("budget").matches

    @Test
    fun aNewSearchStartsAtTheLineOnScreen() {
        assertEquals(0, FindInMeeting.pick(matches, Anchor.AtOrAfter(0, 0)))
        assertEquals(1, FindInMeeting.pick(matches, Anchor.AtOrAfter(1, 0)))
        assertEquals(4, FindInMeeting.pick(matches, Anchor.AtOrAfter(3, 0)))
        // Past the last match: round to the first.
        assertEquals(0, FindInMeeting.pick(matches, Anchor.AtOrAfter(9, 0)))
    }

    @Test
    fun nextAndPreviousWrapRound() {
        assertEquals(1, FindInMeeting.step(0, 5, forward = true))
        assertEquals(0, FindInMeeting.step(4, 5, forward = true))
        assertEquals(4, FindInMeeting.step(0, 5, forward = false))
        assertEquals(0, FindInMeeting.step(-1, 5, forward = true))
        assertEquals(4, FindInMeeting.step(-1, 5, forward = false))
        assertEquals(-1, FindInMeeting.step(0, 0, forward = true))
    }

    @Test
    fun nextAndPreviousFromAPlaceSurviveARefreshedList() {
        // From the second match in line 2 (start 8).
        assertEquals(3, FindInMeeting.pick(matches, Anchor.After(2, 8)))
        assertEquals(1, FindInMeeting.pick(matches, Anchor.Before(2, 8)))
        // That match edited away: next and previous still go to its neighbours.
        val edited = matches.filterNot { it == Match(2, 8, 14) }
        assertEquals(2, FindInMeeting.pick(edited, Anchor.After(2, 8)))
        assertEquals(1, FindInMeeting.pick(edited, Anchor.Before(2, 8)))
        // Round the ends.
        assertEquals(0, FindInMeeting.pick(matches, Anchor.After(3, 3)))
        assertEquals(4, FindInMeeting.pick(matches, Anchor.Before(0, 4)))
    }

    @Test
    fun openingAtAHitPicksItsLineOrTheNearestOne() {
        assertEquals(1, FindInMeeting.pick(matches, Anchor.Nearest(2)))
        assertEquals(4, FindInMeeting.pick(matches, Anchor.Nearest(3)))
        // Line 1 has none: lines 0 and 2 are as near; the later one wins.
        assertEquals(1, FindInMeeting.pick(matches, Anchor.Nearest(1)))
        assertEquals(0, FindInMeeting.pick(listOf(Match(0, 0, 1), Match(5, 0, 1)), Anchor.Nearest(2)))
        assertEquals(4, FindInMeeting.pick(matches, Anchor.Nearest(40)))
        assertEquals(0, FindInMeeting.pick(matches, Anchor.Nearest(-1)))
        assertEquals(-1, FindInMeeting.pick(emptyList(), Anchor.Nearest(2)))
    }
}
