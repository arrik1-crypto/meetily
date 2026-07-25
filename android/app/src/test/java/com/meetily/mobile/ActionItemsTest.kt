package com.meetily.mobile

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.summarize.ActionItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers both the JSON tail the LLM appends and the offline fallback, since
 * the same placeholder text ("None identified") can reach either.
 */
class ActionItemsTest {

    @Test
    fun splitLlmOutput_noMarker_returnsTextAndNull() {
        val (clean, items) = ActionItems.splitLlmOutput("Just a summary, no tail.")
        assertTrue(clean == "Just a summary, no tail.")
        assertNull(items)
    }

    @Test
    fun splitLlmOutput_dropsPlaceholdersFromTheJsonTail() {
        val text = "## Action Items\nNone identified\n\n" +
            ActionItems.MARKER +
            """ [{"task":"None identified","owner":null},""" +
            """{"task":"Send the deck to Bo","owner":"Ada"}]"""
        val (clean, items) = ActionItems.splitLlmOutput(text)
        // The body keeps its "None identified" — that is the reader's signal
        // the heading was considered.
        assertTrue(clean.contains("None identified"))
        assertEquals(1, items?.size)
        assertEquals("Send the deck to Bo", items?.first()?.task)
        assertEquals("Ada", items?.first()?.owner)
    }

    @Test
    fun isPlaceholderTask_catchesTheEmptySectionMarkers() {
        // The shared output rules ask for these under an empty heading; they
        // belong in the summary body, never in the task list.
        for (text in listOf(
            "None identified", "none identified.", "None", "N/A", "n/a",
            "- None identified", "  NONE IDENTIFIED  ", "No action items",
            "Nothing identified", "Not applicable", "none required"
        )) {
            assertTrue("expected placeholder: $text", ActionItems.isPlaceholderTask(text))
        }
    }

    @Test
    fun isPlaceholderTask_leavesRealTasksAlone() {
        for (text in listOf(
            "Confirm none of the vendors are on the old contract",
            "None of the team can make Friday — reschedule",
            "No decision yet on the budget; Alice to follow up",
            "Nothing to send until legal replies",
            "Identify the owner for the migration"
        )) {
            assertFalse("wrongly filtered: $text", ActionItems.isPlaceholderTask(text))
        }
    }

    @Test
    fun fromMeetingContent_dropsPlaceholderLines() {
        val items = ActionItems.fromMeetingContent(
            listOf(TranscriptSegment(1L, "Alice will send the report by Friday")),
            "- None identified\n- We need to finalize the budget"
        )
        assertTrue(items.none { ActionItems.isPlaceholderTask(it.task) })
        assertTrue(items.any { it.task.contains("finalize the budget") })
    }

    @Test
    fun fromMeetingContent_usesSpeakerAsOwner() {
        val segments = listOf(
            TranscriptSegment(1L, "Alice will send the report by Friday", speaker = "Alice"),
            TranscriptSegment(2L, "The weather is nice today", speaker = "Bob")
        )
        val items = ActionItems.fromMeetingContent(segments, "")
        assertTrue(items.any { it.task.contains("send the report") && it.owner == "Alice" })
        assertTrue(items.none { it.task.contains("weather") })
    }

    @Test
    fun fromMeetingContent_pullsActionsFromNotes() {
        val items = ActionItems.fromMeetingContent(
            emptyList(),
            "- We need to finalize the budget\nRandom observation line"
        )
        assertTrue(items.any { it.task.contains("finalize the budget") })
    }
}
