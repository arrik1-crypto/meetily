package com.meetily.mobile

import com.meetily.mobile.summarize.AiProvenance
import com.meetily.mobile.summarize.QuotedAiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report AI output on screens that show a saved summary away from its
 * meeting: offered for text a model wrote (or may have), never for text no
 * model wrote.
 */
class QuotedAiTextTest {

    private val summary = "## Overview\nThe team agreed to ship on Friday."

    @Test
    fun modelWrittenSummaryCounts() {
        assertTrue(QuotedAiText.modelWrote(summary, "local:gemma-3-1b"))
        assertTrue(QuotedAiText.modelWrote(summary, "endpoint:llama3.1"))
    }

    @Test
    fun summaryFromBeforeTheModelWasRecordedCounts() {
        // It may have been written by a model; reporting it is the safe side.
        assertTrue(QuotedAiText.modelWrote(summary, null))
    }

    @Test
    fun extractiveSummaryDoesNotCount() {
        assertFalse(QuotedAiText.modelWrote(summary, AiProvenance.EXTRACTIVE))
        assertFalse(QuotedAiText.modelWrote(summary, " ${AiProvenance.EXTRACTIVE} "))
    }

    @Test
    fun blankSummaryDoesNotCount() {
        assertFalse(QuotedAiText.modelWrote("", "local:gemma-3-1b"))
        assertFalse(QuotedAiText.modelWrote("  \n", null))
    }

    @Test
    fun lastCardReportsTheSavedSummaryAsWritten() {
        assertEquals(summary, QuotedAiText.lastCardText("\n$summary\n", "local:gemma-3-1b"))
    }

    @Test
    fun lastCardHasNothingToReportWithoutAModelWrittenSummary() {
        // No saved summary: the card shows extractive key points or the
        // no-summary line, and neither is generated text.
        assertNull(QuotedAiText.lastCardText("", null))
        assertNull(QuotedAiText.lastCardText(summary, AiProvenance.EXTRACTIVE))
    }

    @Test
    fun joinedKeysAreDistinctAndInOrder() {
        assertEquals(
            "local:gemma-3-1b, endpoint:llama3.1",
            QuotedAiText.joinKeys(
                listOf("local:gemma-3-1b", "endpoint:llama3.1", "local:gemma-3-1b")
            )
        )
    }

    @Test
    fun joinedKeysNameTheUnrecordedOnes() {
        assertEquals(
            "soap, ${QuotedAiText.UNRECORDED}",
            QuotedAiText.joinKeys(listOf("soap", null, " ", "soap"))
        )
    }

    @Test
    fun noRecordedKeysGiveNull() {
        assertNull(QuotedAiText.joinKeys(listOf(null, "")))
        assertNull(QuotedAiText.joinKeys(emptyList()))
    }
}
