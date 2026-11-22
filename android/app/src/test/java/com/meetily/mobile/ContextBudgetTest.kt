package com.meetily.mobile

import com.meetily.mobile.llm.PromptShaping
import com.meetily.mobile.summarize.LlmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt budgets as functions of the loaded model's window.
 *
 * The on-device budgets used to be fixed numbers built from a 4096-token
 * window. Small models now load with 8192 where the phone has room, so
 * every budget that serves the local model follows the window the engine
 * really created, while a network endpoint's budgets stay exactly what they
 * were: its window has nothing to do with this phone.
 */
class ContextBudgetTest {

    @Test
    fun a4kWindowKeepsTheBudgetsItAlwaysHad() {
        assertEquals(9_828, PromptShaping.charBudget(4096))
        assertEquals(14_742, PromptShaping.mapReduceThreshold(4096))
        assertEquals(PromptShaping.MAP_CHUNK_CHARS, PromptShaping.mapChunkChars(4096))
        assertEquals(8_000, PromptShaping.mapChunkChars(4096))
    }

    @Test
    fun an8kWindowRoughlyDoublesThem() {
        assertEquals(22_116, PromptShaping.charBudget(8192))
        assertEquals(33_174, PromptShaping.mapReduceThreshold(8192))
        assertEquals(16_000, PromptShaping.mapChunkChars(8192))
    }

    @Test
    fun aSectionAlwaysFitsItsOwnWindow() {
        // condense trims a chunk longer than charBudget - 600; a section
        // size above that would be cut the moment it was made. Checked
        // against that limit itself at every window, small ones included:
        // a model trained on 1K reaches them through the trained clamp.
        for (window in listOf(256, 512, 1024, 1280, 1354, 2048, 4096, 6144, 8192, 16384, 32768)) {
            val chunk = PromptShaping.mapChunkChars(window)
            val trimAt = PromptShaping.charBudget(window) - 600
            assertTrue("$window", chunk <= trimAt)
            assertTrue("$window", chunk > 0)
            // The 1,000-character floor holds wherever the trim limit allows it.
            if (trimAt >= 1_000) assertTrue("$window", chunk >= 1_000)
        }
    }

    @Test
    fun aSmallWindowsSectionIsNotCutByCondense() {
        // At 1024 tokens charBudget is 1200, so condense trims at 600. A
        // section at the old 1,000-character floor came back with its middle
        // replaced by the omission marker every time.
        assertEquals(1_200, PromptShaping.charBudget(1024))
        assertEquals(600, PromptShaping.mapChunkChars(1024))
        val text = (1..200).joinToString("\n") { "line $it with words" }
        val bodies = mutableListOf<String>()
        PromptShaping.condense(
            content = text,
            charBudget = PromptShaping.charBudget(1024),
            generate = { messages, _ -> bodies.add(messages.last().second); "- note" },
            chunkChars = PromptShaping.mapChunkChars(1024)
        )
        assertTrue(bodies.isNotEmpty())
        // Only the last section can run long, when the 12-section cap forces it.
        for (body in bodies.dropLast(1)) {
            assertTrue(body, !body.contains(PromptShaping.OMISSION_MARKER))
        }
    }

    @Test
    fun condenseCutsSectionsAtTheSizeItIsGiven() {
        val text = (1..2_000).joinToString("\n") { "line $it with some words in it" }
        fun sections(chunkChars: Int): Int {
            var calls = 0
            PromptShaping.condense(
                content = text,
                charBudget = PromptShaping.charBudget(8192),
                generate = { _, _ -> calls++; "- note" },
                chunkChars = chunkChars
            )
            return calls
        }
        val base = sections(PromptShaping.mapChunkChars(4096))
        val wide = sections(PromptShaping.mapChunkChars(8192))
        assertEquals((text.length + 7_999) / 8_000, base)
        assertEquals((text.length + 15_999) / 16_000, wide)
        assertTrue(wide < base)
    }

    // --- LlmClient's one-pass windows ---------------------------------------------

    @Test
    fun endpointWindowsAreTheFixedNetworkSizes() {
        for (fixed in listOf(0, 400, 5_000)) {
            assertEquals(LlmClient.CHAPTER_WINDOW_CHARS, LlmClient.windowBudget(fixed, null))
        }
        assertEquals(11_000, LlmClient.CHAPTER_WINDOW_CHARS)
        val three = listOf("A" to "x", "B" to "y", "C" to "z")
        assertEquals(9_333, LlmClient.perBlockChars(three, 500, 28_000, 3_000, null))
        val twenty = List(20) { "M$it" to "body" }
        assertEquals(3_000, LlmClient.perBlockChars(twenty, 500, 28_000, 3_000, null))
        assertEquals(10_000, LlmClient.perBlockChars(three, 900, 30_000, 4_000, null))
    }

    @Test
    fun onDeviceWindowsFollowTheModelsBudget() {
        // At 4K these are the numbers LocalLlm.CHAR_BUDGET used to give.
        val at4k = PromptShaping.charBudget(4096)
        val at8k = PromptShaping.charBudget(8192)
        assertEquals(7_843, LlmClient.windowBudget(400, at4k))
        assertEquals(18_288, LlmClient.windowBudget(400, at8k))
        // A tiny window still gets a workable floor.
        assertEquals(2_000, LlmClient.windowBudget(400, PromptShaping.charBudget(512)))

        val three = listOf("A" to "x", "B" to "y", "C" to "z")
        assertEquals(2_596, LlmClient.perBlockChars(three, 500, 28_000, 3_000, at4k))
        assertTrue(
            LlmClient.perBlockChars(three, 500, 28_000, 3_000, at8k) >
                2 * LlmClient.perBlockChars(three, 500, 28_000, 3_000, at4k)
        )
        val many = List(40) { "M$it" to "body" }
        assertEquals(300, LlmClient.perBlockChars(many, 9_000, 28_000, 3_000, at4k))
    }

    @Test
    fun speakerWindowsKeepThe4kSizeOnAn8kModel() {
        // Each window's reply is capped at the same 700 tokens at any window,
        // about 45 attributions. A window with twice the lines would leave
        // the second half of every window unattributed, so speakers stay at
        // the 4K size while chapters (2-8 entries a reply) take the 8K one.
        val at4k = PromptShaping.charBudget(4096)
        val at8k = PromptShaping.charBudget(8192)
        assertEquals(at4k, LlmClient.speakerCharBudget(at8k))
        assertEquals(at4k, LlmClient.speakerCharBudget(at4k))
        assertEquals(
            LlmClient.windowBudget(400, at4k),
            LlmClient.windowBudget(400, LlmClient.speakerCharBudget(at8k))
        )
        assertEquals(7_843, LlmClient.windowBudget(400, LlmClient.speakerCharBudget(at8k)))
        // A model clamped below 4K by its trained window keeps its own budget.
        val at2k = PromptShaping.charBudget(2048)
        assertEquals(at2k, LlmClient.speakerCharBudget(at2k))
    }
}
