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
    fun emptyQuestionReturnsNothing() {
        val m = meeting("a", "Team sync")
        assertTrue(LibrarySearch.search(listOf(m), "the a of", nowMs = now).isEmpty())
    }
}
