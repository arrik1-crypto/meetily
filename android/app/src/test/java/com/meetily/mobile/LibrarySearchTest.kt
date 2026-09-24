package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.search.LibrarySearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {

    private val now = 1_800_000_000_000L // fixed clock for determinism

    private fun meeting(
        id: String,
        title: String,
        summary: String = "",
        transcript: List<String> = emptyList(),
        ageDays: Long = 10
    ): Meeting {
        val m = Meeting(
            id = id,
            title = title,
            createdAtMs = now - ageDays * 86_400_000L
        )
        m.summary = summary
        for ((i, text) in transcript.withIndex()) {
            m.segments.add(TranscriptSegment(timestampMs = m.createdAtMs + i * 1000L, text = text))
        }
        return m
    }

    @Test
    fun tokenizeDropsStopwordsAndShortWords(): Unit {
        val terms = LibrarySearch.tokenize("When did we discuss the vendor contract?")
        assertTrue("vendor" in terms)
        assertTrue("contract" in terms)
        assertTrue("the" !in terms)
        assertTrue("we" !in terms)
        assertTrue("discuss" !in terms)
    }

    @Test
    fun titleHitsOutrankTranscriptHits() {
        val titled = meeting("a", "Vendor contract review")
        val mentioned = meeting(
            "b", "Weekly sync",
            transcript = listOf("we touched on the vendor topic briefly")
        )
        val hits = LibrarySearch.search(listOf(mentioned, titled), "vendor contract", nowMs = now)
        assertEquals("a", hits.first().meeting.id)
    }

    @Test
    fun unrelatedMeetingsAreExcluded() {
        val related = meeting("a", "Budget planning", summary = "Discussed the Q3 budget.")
        val unrelated = meeting("b", "Design crit", summary = "Reviewed mockups.")
        val hits = LibrarySearch.search(listOf(related, unrelated), "what happened with the budget", nowMs = now)
        assertEquals(1, hits.size)
        assertEquals("a", hits.first().meeting.id)
    }

    @Test
    fun recencyBreaksTies() {
        val old = meeting("old", "Pricing discussion", ageDays = 300)
        val fresh = meeting("new", "Pricing discussion", ageDays = 1)
        val hits = LibrarySearch.search(listOf(old, fresh), "pricing", nowMs = now)
        assertEquals("new", hits.first().meeting.id)
    }

    @Test
    fun excerptsComeFromMatchingSegments() {
        val m = meeting(
            "a", "Team sync",
            transcript = listOf(
                "good morning everyone",
                "the migration deadline moved to Friday",
                "ok next topic"
            )
        )
        val hits = LibrarySearch.search(listOf(m), "migration deadline", nowMs = now)
        assertTrue(hits.first().excerpts.any { it.contains("migration deadline moved") })
    }

    @Test
    fun unspacedScriptsTokenizeAsBigrams() {
        val terms = LibrarySearch.tokenize("上次会议关于预算说了什么")
        assertTrue("预算" in terms)
        // Question words are not terms.
        assertTrue("什么" !in terms)
        // A two-character word on its own is kept, not dropped as short.
        assertEquals(listOf("预算"), LibrarySearch.tokenize("预算"))
        // Latin tokens keep the existing rule.
        assertTrue(LibrarySearch.tokenize("Q3 预算").contains("预算"))
        assertTrue("q3" !in LibrarySearch.tokenize("Q3 预算"))
    }

    @Test
    fun chineseQuestionFindsChineseMeeting() {
        val budget = meeting("a", "周会", transcript = listOf("我们讨论了明年的预算和招聘"))
        val other = meeting("b", "设计评审", transcript = listOf("新的首页设计已经完成"))
        val hits = LibrarySearch.search(
            listOf(budget, other), "上次会议关于预算说了什么", nowMs = now
        )
        assertEquals("a", hits.first().meeting.id)
    }

    @Test
    fun emptyQuestionReturnsNothing() {
        val m = meeting("a", "Team sync")
        assertTrue(LibrarySearch.search(listOf(m), "the a of", nowMs = now).isEmpty())
    }
}
