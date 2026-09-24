package com.meetily.mobile

import com.meetily.mobile.llm.PromptShaping
import com.meetily.mobile.security.ModelIntegrity
import com.meetily.mobile.summarize.MeetingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the model is handed (a long transcript cut by its middle, never its
 * end), what a model download must prove before it is loaded, and the
 * allocation-free word count the stats card relies on.
 */
class LlmInputShapingTest {

    @Test
    fun excerptKeepsTheEndingOfALongTranscript() {
        val transcript = buildString {
            append("OPENING ")
            repeat(5_000) { append("line $it of the meeting\n") }
            append("WE DECIDED TO SHIP FRIDAY")
        }
        val cut = PromptShaping.excerpt(transcript, 48_000)
        assertTrue(cut.length <= 48_000)
        assertTrue("head kept", cut.startsWith("OPENING"))
        assertTrue("the wrap-up survives", cut.endsWith("WE DECIDED TO SHIP FRIDAY"))
        assertTrue("the model is told", cut.contains(PromptShaping.OMISSION_MARKER))
    }

    @Test
    fun excerptLeavesShortTextAlone() {
        val text = "short meeting"
        assertSame(text, PromptShaping.excerpt(text, 48_000))
    }

    @Test
    fun omissionMarkerIsRuntimeNeutral() {
        // Network prompts are cut with it too.
        assertFalse(PromptShaping.OMISSION_MARKER.contains("on-device"))
    }

    @Test
    fun integrityAcceptsACompleteUnpinnedDownload() {
        val digest = ModelIntegrity.newDigest()
        digest.update("model".toByteArray())
        ModelIntegrity.verify("m.gguf", 5, 5, null, digest)
        // No declared length (chunked) cannot be checked, and is not refused.
        ModelIntegrity.verify("m.gguf", 5, -1, null, ModelIntegrity.newDigest())
    }

    @Test
    fun integrityRefusesATruncatedDownload() {
        expectRefused { ModelIntegrity.verify("m.gguf", 4, 5, null, ModelIntegrity.newDigest()) }
        expectRefused { ModelIntegrity.verify("m.gguf", 0, -1, null, ModelIntegrity.newDigest()) }
    }

    @Test
    fun integrityChecksAPinnedHash() {
        // SHA-256("abc"), the FIPS 180-2 test vector.
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val good = ModelIntegrity.newDigest().apply { update("abc".toByteArray()) }
        ModelIntegrity.verify("m.gguf", 3, 3, abc.uppercase(), good)
        val bad = ModelIntegrity.newDigest().apply { update("abd".toByteArray()) }
        expectRefused { ModelIntegrity.verify("m.gguf", 3, 3, abc, bad) }
    }

    @Test
    fun countWordsMatchesSplittingOnWhitespace() {
        for (text in listOf("", "   ", "one", " two  words ", "tabs\tand\nnewlines  too", "a b c")) {
            val expected = text.split(Regex("\\s+")).count { it.isNotBlank() }
            assertEquals(text, expected, MeetingStats.countWords(text))
        }
    }

    private fun expectRefused(block: () -> Unit) {
        try {
            block()
            fail("expected the download to be refused")
        } catch (_: ModelIntegrity.IntegrityException) {
        }
    }
}
