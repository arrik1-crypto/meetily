package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingTest {

    @Test
    fun json_roundTripsTheFollowUpAndTranscriptFields() {
        val meeting = Meeting(id = "m1", title = "Standup", createdAtMs = 42L).apply {
            starred = true
            transcriptModel = "large-v3-turbo-q5_0"
            summaryStale = true
        }
        val restored = Meeting.fromJson(meeting.toJson())
        assertTrue(restored.starred)
        assertEquals("large-v3-turbo-q5_0", restored.transcriptModel)
        assertTrue(restored.summaryStale)
    }

    @Test
    fun json_meetingsSavedBeforeTheseFieldsExistedStillLoad() {
        val legacy = org.json.JSONObject()
            .put("id", "old")
            .put("title", "Older meeting")
            .put("createdAtMs", 1L)
        val restored = Meeting.fromJson(legacy)
        assertTrue(!restored.starred)
        assertEquals(null, restored.transcriptModel)
        assertTrue(!restored.summaryStale)
    }

    @Test
    fun json_omitsTheDefaultsSoStoredFilesDoNotGrow() {
        val plain = Meeting(id = "m2", title = "Plain", createdAtMs = 0L).toJson()
        assertTrue(!plain.has("starred"))
        assertTrue(!plain.has("transcriptModel"))
        assertTrue(!plain.has("summaryStale"))
    }

    // --- "Follows on from" ---------------------------------------------------

    @Test
    fun json_roundTripsTheFollowsOnFromLink() {
        val meeting = Meeting(id = "m3", title = "Follow-up", createdAtMs = 100L).apply {
            followsEvent = com.meetily.mobile.data.FollowsEvent(
                title = "Q3 roadmap review", beginMs = 50L, eventId = 77L
            )
        }
        val restored = Meeting.fromJson(meeting.toJson())
        assertEquals("Q3 roadmap review", restored.followsEvent?.title)
        assertEquals(50L, restored.followsEvent?.beginMs)
        assertEquals(77L, restored.followsEvent?.eventId)
    }

    @Test
    fun json_omitsTheLinkWhenThereIsNone() {
        assertTrue(!Meeting(id = "m4", title = "x", createdAtMs = 0L).toJson().has("followsEvent"))
    }

    @Test
    fun json_aMalformedLinkCostsTheLinkAndNotTheMeeting() {
        // fromJson failures are swallowed by mapNotNull in MeetingStore.list(),
        // so throwing here would make the whole meeting disappear with nothing
        // logged. A blank title prunes the link and leaves everything else.
        val obj = org.json.JSONObject()
            .put("id", "m5")
            .put("title", "Survivor")
            .put("createdAtMs", 5L)
            .put("followsEvent", org.json.JSONObject().put("beginMs", "not a number"))
        val restored = Meeting.fromJson(obj)
        assertEquals("Survivor", restored.title)
        assertEquals(null, restored.followsEvent)
    }

    @Test
    fun json_aLinkWithNoTitleIsNotALink() {
        val obj = org.json.JSONObject()
            .put("id", "m6")
            .put("title", "x")
            .put("createdAtMs", 0L)
            .put(
                "followsEvent",
                org.json.JSONObject().put("title", "   ").put("beginMs", 1L).put("eventId", 2L)
            )
        assertEquals(null, Meeting.fromJson(obj).followsEvent)
    }

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
