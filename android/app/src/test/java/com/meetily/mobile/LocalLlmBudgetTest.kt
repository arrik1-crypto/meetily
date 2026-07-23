package com.meetily.mobile

import com.meetily.mobile.llm.LocalLlm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmBudgetTest {

    @Test
    fun underBudgetMessagesPassThroughUntouched() {
        val messages = listOf("system" to "be brief", "user" to "short question")
        assertEquals(messages, LocalLlm.budgetMessages(messages, 1_000))
    }

    @Test
    fun stripThinkingRemovesReasoningBlocks() {
        assertEquals(
            "The answer.",
            LocalLlm.stripThinking("<think>step 1... step 2...</think>\nThe answer.")
        )
        // Truncated thought (budget ran out): never surface it.
        assertEquals("", LocalLlm.stripThinking("<think>endless pondering"))
        // Non-thinking replies pass through; embedded blocks are excised.
        assertEquals("Plain reply.", LocalLlm.stripThinking("Plain reply."))
        assertEquals(
            "Before after.",
            LocalLlm.stripThinking("Before <think>hmm</think>after.")
        )
    }

    @Test
    fun overBudgetShrinksTheLongestMessageKeepingHeadAndTail() {
        val transcript = buildString {
            append("OPENING_MARKER ")
            repeat(500) { append("filler sentence number $it. ") }
            append("CLOSING_MARKER")
        }
        val messages = listOf("system" to "be brief", "user" to transcript)
        val budget = 3_000
        val out = LocalLlm.budgetMessages(messages, budget)
        assertEquals("be brief", out[0].second)
        val shrunk = out[1].second
        assertTrue(shrunk.length < transcript.length)
        assertTrue(out.sumOf { it.second.length } <= budget + 100)
        assertTrue("head kept", shrunk.startsWith("OPENING_MARKER"))
        assertTrue("tail kept", shrunk.endsWith("CLOSING_MARKER"))
        assertTrue("marker present", shrunk.contains("omitted"))
    }

    @Test
    fun shortMessagesAreNeverShrunkBelowFloor() {
        val messages = listOf("user" to "x".repeat(700))
        val out = LocalLlm.budgetMessages(messages, 100)
        // Floor keeps a usable stub rather than truncating to nothing.
        assertTrue(out[0].second.length >= 400)
    }

    // --- Map-reduce chunking -------------------------------------------------

    @Test
    fun shortTextIsOneChunk() {
        assertEquals(listOf("short"), LocalLlm.splitIntoChunks("short", 100, 12))
    }

    @Test
    fun chunksConcatenateBackToTheOriginal() {
        val text = (1..400).joinToString("\n") { "line $it with some words in it" }
        val chunks = LocalLlm.splitIntoChunks(text, 2_000, 12)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun chunksPreferLineBoundaries() {
        val text = (1..400).joinToString("\n") { "line $it with some words in it" }
        val chunks = LocalLlm.splitIntoChunks(text, 2_000, 12)
        for (chunk in chunks.dropLast(1)) {
            assertTrue("chunk should end at a line break", chunk.endsWith("\n"))
        }
    }

    @Test
    fun chunkCountIsCappedForHugeInput() {
        val text = "word ".repeat(60_000) // 300k chars
        val chunks = LocalLlm.splitIntoChunks(text, 8_000, 12)
        assertEquals(12, chunks.size)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun mapReduceThresholdSitsAboveTheBudget() {
        assertTrue(!LocalLlm.needsMapReduce(LocalLlm.CHAR_BUDGET))
        assertTrue(LocalLlm.needsMapReduce(LocalLlm.MAP_REDUCE_THRESHOLD + 1))
    }
}
