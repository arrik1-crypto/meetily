package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.CompleteActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.summarize.ActionItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the meeting's action items change: a summary whose item list was cut
 * off, a tick from Follow-ups or the pre-meeting brief, and what Follow-ups
 * exports.
 */
class ActionItemUpdatesTest {

    private val existing = listOf(
        ActionItem("Send the deck", "Ana", done = true),
        ActionItem("Book the room", remindAtMs = 1_800_000_000_000L)
    )

    @Test
    fun aModelSummaryWithoutItsItemListKeepsTheItems() {
        // The reply limit cut the JSON tail off: the items stay, ticks and
        // reminders with them, instead of regex guesses replacing them.
        assertTrue(ActionItems.keepExisting(true, null, existing))
    }

    @Test
    fun aListThatArrivedIsTheRunsAnswerEvenWhenEmpty() {
        assertFalse(ActionItems.keepExisting(true, emptyList(), existing))
        assertFalse(
            ActionItems.keepExisting(true, listOf(ActionItem("Call the vendor")), existing)
        )
    }

    @Test
    fun aMeetingWithNoItemsStillGetsTheFallback() {
        assertFalse(ActionItems.keepExisting(true, null, emptyList()))
    }

    @Test
    fun theExtractiveSummaryRefreshesItemsAsBefore() {
        // No model wrote it (AI off, or a failed run's fallback): its regex
        // list is that run's list.
        assertFalse(ActionItems.keepExisting(false, null, existing))
    }

    @Test
    fun aTickFindsItsItemByIndexWhileItIsStillThere() {
        assertEquals(1, CompleteActionItem.indexIn(existing, 1, "Book the room"))
    }

    @Test
    fun aTickFallsBackToTheTextWhenTheListMoved() {
        val moved = listOf(ActionItem("New task")) + existing
        assertEquals(2, CompleteActionItem.indexIn(moved, 1, "Book the room"))
    }

    @Test
    fun aTickOnAnItemThatIsGoneFindsNothing() {
        assertEquals(-1, CompleteActionItem.indexIn(existing, 1, "Renamed elsewhere"))
        assertEquals(-1, CompleteActionItem.indexIn(emptyList(), 0, "Book the room"))
        // By text only an open item matches; this one is already done.
        assertEquals(-1, CompleteActionItem.indexIn(existing, 5, "Send the deck"))
    }

    @Test
    fun followUpsExportsOpenItemsLeavingOutThoseTickedHere() {
        val meetings = listOf(
            Meeting(
                id = "m1", title = "Sync", createdAtMs = 2L,
                actionItems = mutableListOf(
                    ActionItem("Send the deck", "Ana"),
                    ActionItem("Done already", done = true),
                    ActionItem("Book the room", remindAtMs = 5L)
                )
            ),
            Meeting(
                id = "m2", title = "Review", createdAtMs = 1L,
                actionItems = mutableListOf(ActionItem("Send the deck"))
            )
        )
        val items = FollowUpsActivity.openItemsOf(meetings, setOf("m1" to "Send the deck"))
        assertEquals(listOf("Book the room", "Send the deck"), items.map { it.task })
        assertEquals(listOf("Sync", "Review"), items.map { it.meetingTitle })
        assertEquals(5L, items.first().dueMs)
        // Nothing open: the export says so rather than writing an empty file.
        assertTrue(FollowUpsActivity.openItemsOf(emptyList(), emptySet()).isEmpty())
    }
}
