package com.meetily.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a model download's end is reported: a cancelled run leaves the pick to
 * a re-pick of the same model waiting in the queue, a cancel reports no
 * error, and Android's time limit is explained.
 */
class DownloadRunEndTest {

    private val whisper = ModelDownloadService.KIND_WHISPER
    private val llm = ModelDownloadService.KIND_LLM

    @Test
    fun aCancelledRunLeavesThePickToTheSameModelPickedAgain() {
        // Turbo cancelled while its read stalled, then picked again: the
        // retry waits in the queue and must still switch to Turbo when done.
        assertTrue(
            DownloadRunEnd.leaveToRetry(true, whisper, "turbo", listOf(whisper to "turbo"))
        )
        assertTrue(
            DownloadRunEnd.leaveToRetry(
                true, llm, "gemma", listOf(whisper to "small", llm to "gemma")
            )
        )
    }

    @Test
    fun otherwiseTheRunSettlesItsOwnPick() {
        // Not cancelled: its own outcome applies, whatever waits.
        assertFalse(DownloadRunEnd.leaveToRetry(false, whisper, "turbo", listOf(whisper to "turbo")))
        // Cancelled with nothing of its own waiting: the pick is dropped.
        assertFalse(DownloadRunEnd.leaveToRetry(true, whisper, "turbo", emptyList()))
        // The same key of another kind, or another model of the same kind,
        // is not a retry of this one.
        assertFalse(DownloadRunEnd.leaveToRetry(true, whisper, "turbo", listOf(llm to "turbo")))
        assertFalse(DownloadRunEnd.leaveToRetry(true, whisper, "turbo", listOf(whisper to "small")))
    }

    @Test
    fun aCancelReportsNoErrorEvenWhenTheBrokenReadThrew() {
        assertNull(DownloadRunEnd.reportedError(true, false, "timeout", "limit"))
        assertNull(DownloadRunEnd.reportedError(true, false, null, "limit"))
    }

    @Test
    fun androidsTimeLimitIsExplainedAndFailuresPassThrough() {
        assertEquals("limit", DownloadRunEnd.reportedError(false, true, null, "limit"))
        assertEquals("limit", DownloadRunEnd.reportedError(false, true, "Socket closed", "limit"))
        assertEquals("HTTP 404", DownloadRunEnd.reportedError(false, false, "HTTP 404", "limit"))
        assertNull(DownloadRunEnd.reportedError(false, false, null, "limit"))
    }
}
