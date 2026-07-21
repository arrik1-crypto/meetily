package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests only. JSON (de)serialization is exercised via instrumented
 * tests, since org.json is an Android-framework class not reliably available
 * to plain JVM unit tests.
 */
class MeetingTest {

    @Test
    fun parseAttendees_splitsTrimsAndDedupes() {
        val result = Meeting.parseAttendees("Alice, Bob ; alice\nCarol,")
        assertEquals(listOf("Alice", "Bob", "alice", "Carol"), result)
    }

    @Test
    fun parseAttendees_blankGivesEmpty() {
        assertTrue(Meeting.parseAttendees("   ,  ; \n").isEmpty())
    }

    @Test
    fun parseAttendees_caseSensitiveDistinct() {
        // Distinct is exact-match; "alice" and "Alice" are both kept.
        val result = Meeting.parseAttendees("Alice, Alice, alice")
        assertEquals(listOf("Alice", "alice"), result)
    }

    @Test
    fun transcriptTextWithSpeakers_prefixesNames() {
        val meeting = Meeting(
            id = "m", title = "t", createdAtMs = 1L,
            segments = mutableListOf(
                TranscriptSegment(1L, "hi", speaker = "Bob"),
                TranscriptSegment(2L, "no speaker here")
            )
        )
        val text = meeting.transcriptTextWithSpeakers()
        assertTrue(text.contains("Bob: hi"))
        assertTrue(text.contains("no speaker here"))
    }

    @Test
    fun highlightedTexts_returnsOnlyFlagged() {
        val meeting = Meeting(
            id = "m", title = "t", createdAtMs = 1L,
            segments = mutableListOf(
                TranscriptSegment(1L, "keep this", highlighted = true, speaker = "Al"),
                TranscriptSegment(2L, "drop this")
            )
        )
        val highlights = meeting.highlightedTexts()
        assertEquals(1, highlights.size)
        assertTrue(highlights[0].contains("keep this"))
    }
}
