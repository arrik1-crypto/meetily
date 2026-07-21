package com.meetily.mobile

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.summarize.ActionItems
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the non-JSON code paths are unit-tested here; JSON tail parsing uses
 * org.json (an Android class) and is covered by instrumented tests.
 */
class ActionItemsTest {

    @Test
    fun splitLlmOutput_noMarker_returnsTextAndNull() {
        val (clean, items) = ActionItems.splitLlmOutput("Just a summary, no tail.")
        assertTrue(clean == "Just a summary, no tail.")
        assertNull(items)
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
