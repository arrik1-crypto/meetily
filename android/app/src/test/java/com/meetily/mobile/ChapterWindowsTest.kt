package com.meetily.mobile

import com.meetily.mobile.summarize.LlmClient
import com.meetily.mobile.summarize.SummaryTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Topic detection used to send one truncated request, so only the opening
 * minutes of a long meeting were ever chaptered. These pin the windowing
 * and merge logic that fixed it, plus the summary-rule changes.
 */
class ChapterWindowsTest {

    @Test
    fun shortTranscriptIsASingleWindow() {
        val windows = LlmClient.chapterWindows(List(20) { 50 })
        assertEquals(1, windows.size)
        assertEquals(0..19, windows.first())
    }

    @Test
    fun emptyTranscriptProducesNoWindows() {
        assertTrue(LlmClient.chapterWindows(emptyList()).isEmpty())
    }

    @Test
    fun longTranscriptIsSplitAndFullyCovered() {
        // ~1200 lines of ~90 chars: far beyond a single request.
        val lengths = List(1200) { 90 }
        val windows = LlmClient.chapterWindows(lengths)
        assertTrue("expected several windows, got ${windows.size}", windows.size > 1)
        // Every line must appear in exactly one window, in order — the whole
        // point is that no part of the meeting is skipped.
        var expected = 0
        for (w in windows) {
            assertEquals(expected, w.first)
            expected = w.last + 1
        }
        assertEquals(lengths.size, expected)
    }

    @Test
    fun aSingleOversizeLineStillGetsItsOwnWindow() {
        val windows = LlmClient.chapterWindows(listOf(50, 999_999, 50))
        var covered = 0
        for (w in windows) covered += (w.last - w.first + 1)
        assertEquals(3, covered)
    }

    @Test
    fun mergeDropsWindowSeamDuplicates() {
        // Each window tends to declare a chapter at its own line 0; after
        // offsetting these land next to a real chapter and must collapse.
        val merged = LlmClient.mergeChapterMarks(
            listOf(0 to "Intro", 1 to "Intro again", 40 to "Budget", 41 to "Budget cont")
        )
        assertEquals(listOf(0 to "Intro", 40 to "Budget"), merged)
    }

    @Test
    fun mergeSortsAndKeepsDistantMarks() {
        val merged = LlmClient.mergeChapterMarks(listOf(80 to "Late", 0 to "Early", 40 to "Mid"))
        assertEquals(listOf(0, 40, 80), merged.map { it.first })
    }

    // --- Summary rule changes ------------------------------------------------

    @Test
    fun headingTemplatesReportEmptySectionsRatherThanDroppingThem() {
        val general = SummaryTemplates.ALL.first { it.key == "general" }
        val effective = SummaryTemplates.effective(general, "standard")
        assertTrue(effective.llmInstructions.contains("None identified"))
    }

    @Test
    fun proseTemplateOmitsEmptySectionsInstead() {
        val email = SummaryTemplates.ALL.first { it.key == "email" }
        assertTrue(email.omitEmptySections)
        val effective = SummaryTemplates.effective(email, "standard")
        assertFalse(effective.llmInstructions.contains("None identified"))
        assertTrue(effective.llmInstructions.contains("Leave out any section"))
    }

    @Test
    fun verbatimTemplatesAreSentExactlyAsWritten() {
        for (key in listOf("exec_summary", "note_to_self")) {
            val template = SummaryTemplates.ALL.first { it.key == key }
            assertTrue("$key should be verbatim", template.verbatim)
            for (depth in listOf("brief", "standard", "detailed")) {
                assertEquals(
                    template.llmInstructions,
                    SummaryTemplates.effective(template, depth).llmInstructions
                )
            }
        }
    }

    @Test
    fun executiveSummaryKeepsTheUserSuppliedFormat() {
        val t = SummaryTemplates.ALL.first { it.key == "exec_summary" }
        for (heading in listOf(
            "## Executive Summary", "## Key Decisions", "## Action Items", "## Open Questions"
        )) {
            assertTrue("missing $heading", t.llmInstructions.contains(heading))
        }
        assertTrue(t.llmInstructions.contains("under 300 words"))
    }

    @Test
    fun noteToSelfNamesTheUserWhenKnown() {
        val t = SummaryTemplates.ALL.first { it.key == "note_to_self" }
        val named = SummaryTemplates.personalised(t, "Alex")
        assertTrue(named.llmInstructions.contains("Alex"))
        // Unknown recorder: say so rather than inventing an attribution.
        val anon = SummaryTemplates.personalised(t, "  ")
        assertTrue(anon.llmInstructions.contains("not identified"))
        assertFalse(anon.llmInstructions.contains("Alex"))
    }

    @Test
    fun personalisationLeavesOtherTemplatesAlone() {
        val general = SummaryTemplates.ALL.first { it.key == "general" }
        assertEquals(general, SummaryTemplates.personalised(general, "Alex"))
    }
}
