package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.search.LibrarySearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersAndOcrDataTest {

    private val now = 1_800_000_000_000L

    @Test
    fun reminderTimeSurvivesJsonRoundTrip() {
        val meeting = Meeting(id = "m1", title = "T", createdAtMs = now)
        meeting.actionItems.add(ActionItem("Send the deck", "Sam", false, now + 3_600_000L))
        meeting.actionItems.add(ActionItem("No reminder on this one"))
        val restored = Meeting.fromJson(meeting.toJson())
        assertEquals(now + 3_600_000L, restored.actionItems[0].remindAtMs)
        assertEquals("Sam", restored.actionItems[0].owner)
        assertNull(restored.actionItems[1].remindAtMs)
    }

    @Test
    fun photoTextsSurviveJsonRoundTrip() {
        val meeting = Meeting(id = "m2", title = "T", createdAtMs = now)
        meeting.photoTexts["wb1.jpg"] = "Q3 roadmap: ship exporter"
        val restored = Meeting.fromJson(meeting.toJson())
        assertEquals("Q3 roadmap: ship exporter", restored.photoTexts["wb1.jpg"])
    }

    @Test
    fun searchFindsMeetingsByWhiteboardText() {
        val withBoard = Meeting(id = "a", title = "Design sync", createdAtMs = now - 86_400_000L)
        withBoard.photoTexts["wb.jpg"] = "flywheel diagram onboarding funnel"
        val without = Meeting(id = "b", title = "Design sync 2", createdAtMs = now - 86_400_000L)
        val hits = LibrarySearch.search(listOf(without, withBoard), "onboarding funnel", nowMs = now)
        assertTrue(hits.isNotEmpty())
        assertEquals("a", hits.first().meeting.id)
    }
}
