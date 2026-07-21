package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.export.MeetingExporter
import com.meetily.mobile.summarize.ExtractiveSummarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerAndExportTest {

    @Test
    fun isActionSentence_detectsCommitments() {
        assertTrue(ExtractiveSummarizer.isActionSentence("I will send the report tomorrow"))
        assertTrue(ExtractiveSummarizer.isActionSentence("We need to schedule the review"))
        assertFalse(ExtractiveSummarizer.isActionSentence("The weather is nice today"))
    }

    @Test
    fun titleFor_emptyTranscript_isBlank() {
        assertEquals("", ExtractiveSummarizer.titleFor(""))
    }

    @Test
    fun titleFor_producesCapitalizedShortTitle() {
        val transcript = "We discussed the budget planning for next quarter. " +
            "The budget planning needs approval. Budget planning is the priority."
        val title = ExtractiveSummarizer.titleFor(transcript)
        assertTrue(title.isNotBlank())
        assertTrue(title[0].isUpperCase())
        assertTrue(title.split(" ").size <= 7)
    }

    @Test
    fun summarize_generalContainsHeader() {
        val transcript = "Alice presented the roadmap. Bob asked about timelines. " +
            "We agreed to ship in March. Everyone was aligned on priorities."
        val summary = ExtractiveSummarizer.summarize(transcript, "note", emptyList(), false)
        assertTrue(summary.contains("MEETING SUMMARY"))
    }

    @Test
    fun summarize_actionsOnlyContainsActionsHeader() {
        val transcript = "Alice will send the deck. Bob should book the room."
        val summary = ExtractiveSummarizer.summarize(transcript, "", emptyList(), true)
        assertTrue(summary.contains("ACTION ITEMS"))
    }

    @Test
    fun summarize_emptyInput_reportsNothing() {
        val summary = ExtractiveSummarizer.summarize("", "", emptyList(), false)
        assertTrue(summary.contains("Nothing to summarize"))
    }

    @Test
    fun exporter_fileName_sanitizesTitle() {
        val meeting = Meeting(id = "m", title = "Q3 Review: Budget/Plan!", createdAtMs = 1L)
        val name = MeetingExporter.suggestedFileName(meeting, "md")
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
        assertFalse(name.contains("!"))
    }

    @Test
    fun exporter_fileName_blankTitleFallsBack() {
        val meeting = Meeting(id = "m", title = "***", createdAtMs = 1L)
        assertEquals("meeting.pdf", MeetingExporter.suggestedFileName(meeting, "pdf"))
    }

    @Test
    fun exporter_markdown_includesSections() {
        val meeting = Meeting(
            id = "m",
            title = "Team Sync",
            createdAtMs = 1_700_000_000_000L,
            segments = mutableListOf(TranscriptSegment(1_700_000_001_000L, "hello", speaker = "Al")),
            notes = "some notes",
            summary = "the summary",
            attendees = mutableListOf("Al", "Bo"),
            actionItems = mutableListOf(ActionItem("Do thing", owner = "Al", done = true))
        )
        val md = MeetingExporter.markdown(meeting)
        assertTrue(md.contains("# Team Sync"))
        assertTrue(md.contains("## Summary"))
        assertTrue(md.contains("## Action items"))
        assertTrue(md.contains("- [x] Do thing"))
        assertTrue(md.contains("## Notes"))
        assertTrue(md.contains("## Transcript"))
        assertTrue(md.contains("Al"))
    }
}
