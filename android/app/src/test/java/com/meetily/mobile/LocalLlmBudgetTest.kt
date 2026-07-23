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
}
