package com.meetily.mobile

import com.meetily.mobile.llm.LocalLlm
import com.meetily.mobile.llm.PromptShaping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shaping rules both on-device runtimes share.
 *
 * LocalLlmBudgetTest already covers the pure helpers through llama.cpp's
 * facade. What is tested here is what breaks if the two runtimes ever stop
 * sharing an implementation, plus the map-reduce loop that used to be
 * private and therefore untested.
 */
class PromptShapingTest {

    @Test
    fun bothRuntimesGetTheSameShapingForTheSameWindow() {
        // The whole reason the logic moved: llama.cpp's facade must be a
        // pass-through, not a second implementation that can drift.
        assertEquals(PromptShaping.charBudget(LocalLlm.N_CTX), LocalLlm.CHAR_BUDGET)
        assertEquals(
            PromptShaping.mapReduceThreshold(LocalLlm.N_CTX),
            LocalLlm.MAP_REDUCE_THRESHOLD
        )
    }

    @Test
    fun budgetScalesWithTheWindowRatherThanBeingFixed() {
        // A runtime with a bigger window must not inherit llama.cpp's number:
        // hard-coding 4096 either wastes the window or overflows it, and both
        // fail silently.
        assertTrue(PromptShaping.charBudget(8192) > PromptShaping.charBudget(4096))
        assertTrue(PromptShaping.mapReduceThreshold(4096) > PromptShaping.charBudget(4096))
    }

    @Test
    fun tinyWindowStillLeavesAWorkablePrompt() {
        // Guard against a negative or absurd budget if a model reports a
        // window smaller than the reply reservation.
        assertTrue(PromptShaping.charBudget(64) > 0)
    }

    @Test
    fun chunksConcatenateBackToTheOriginal() {
        val text = (1..500).joinToString("\n") { "line $it with some words in it" }
        val chunks = PromptShaping.splitIntoChunks(text, 2_000, 12)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun condenseReturnsNullWhenThereIsNothingToGain() {
        // One chunk means map-reduce cannot beat a straight pass; the caller
        // relies on null to fall back to head+tail trimming.
        var calls = 0
        val result = PromptShaping.condense(
            content = "short transcript",
            charBudget = 10_000,
            generate = { _, _ -> calls++; "notes" }
        )
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun condenseCoversEverySectionInOrder() {
        val text = (1..400).joinToString("\n") { "line $it" }
        val seen = mutableListOf<Int>()
        val result = PromptShaping.condense(
            content = text.repeat(40),
            charBudget = 9_000,
            generate = { _, _ -> "notes" },
            onSection = { index, _ -> seen.add(index) }
        )
        assertNotNull(result)
        assertTrue(result!!.contains("--- Section 1 ---"))
        // 1-based and strictly increasing: the progress bar depends on it.
        assertEquals(seen.sorted(), seen)
        assertEquals(1, seen.first())
    }

    @Test
    fun oneFailedSectionDoesNotLoseTheRest() {
        val text = (1..400).joinToString("\n") { "line $it" }.repeat(40)
        var call = 0
        val result = PromptShaping.condense(
            content = text,
            charBudget = 9_000,
            generate = { _, _ ->
                call++
                if (call == 2) throw IllegalStateException("section blew up") else "notes"
            }
        )
        assertNotNull(result)
        assertTrue(result!!.contains("(section notes unavailable)"))
        assertTrue(result.contains("notes"))
    }

    @Test
    fun anErrorInASectionIsCaughtTooNotJustAnException() {
        // Every catch site in the app catches Exception. A runtime that
        // allocates on the JVM heap raises OutOfMemoryError where llama.cpp
        // returned null, and one bad section must not lose the other eleven.
        val text = (1..400).joinToString("\n") { "line $it" }.repeat(40)
        var call = 0
        val result = PromptShaping.condense(
            content = text,
            charBudget = 9_000,
            generate = { _, _ ->
                call++
                if (call == 1) throw OutOfMemoryError("simulated") else "notes"
            }
        )
        assertNotNull(result)
        assertTrue(result!!.contains("(section notes unavailable)"))
    }

    @Test
    fun everySectionFailingReportsNothingRatherThanEmptyNotes() {
        val text = (1..400).joinToString("\n") { "line $it" }.repeat(40)
        val result = PromptShaping.condense(
            content = text,
            charBudget = 9_000,
            generate = { _, _ -> throw IllegalStateException("dead") }
        )
        // Null, so the caller falls back to trimming rather than summarising
        // a page of "(section notes unavailable)".
        assertNull(result)
    }

    @Test
    fun oversizedChunksAreTrimmedBeforeGenerating() {
        // MAP_MAX_CHUNKS can force chunks past the budget; the section pass
        // must trim them or the runtime silently truncates instead.
        val huge = "x".repeat(400_000)
        val budget = 9_000
        var longest = 0
        PromptShaping.condense(
            content = huge,
            charBudget = budget,
            generate = { messages, _ ->
                longest = maxOf(longest, messages.sumOf { it.second.length })
                "notes"
            }
        )
        assertTrue("section prompt exceeded the budget: $longest", longest <= budget)
    }

    @Test
    fun thinkingIsStrippedForEveryRuntimeNotJustLlamaCpp() {
        // The block comes from the MODEL's chat template, so the same Qwen
        // weights emit it whichever runtime loads them.
        assertEquals("Answer.", PromptShaping.stripThinking("<think>hmm</think>\nAnswer."))
        assertEquals("", PromptShaping.stripThinking("<think>never closed"))
        assertEquals(
            PromptShaping.stripThinking("<think>a</think>b"),
            LocalLlm.stripThinking("<think>a</think>b")
        )
    }

    @Test
    fun budgetingKeepsHeadAndTailAroundTheMarker() {
        val long = "S".repeat(50_000)
        val out = PromptShaping.budgetMessages(listOf("user" to long), 5_000)
        assertTrue(out[0].second.contains(PromptShaping.OMISSION_MARKER))
        assertTrue(out[0].second.startsWith("S"))
        assertTrue(out[0].second.endsWith("S"))
        assertTrue(out.sumOf { it.second.length } <= 5_000 + 100)
    }
}
