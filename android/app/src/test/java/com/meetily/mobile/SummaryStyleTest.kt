package com.meetily.mobile

import com.meetily.mobile.summarize.SummaryTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryStyleTest {

    @Test
    fun builtInTemplateKeysAreUnique() {
        val keys = SummaryTemplates.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun domainTemplatesExist() {
        val keys = SummaryTemplates.ALL.map { it.key }
        for (key in listOf("soap", "patient_recap", "legal_consult", "matter")) {
            assertTrue("missing template: $key", key in keys)
        }
    }

    @Test
    fun domainTemplatesAreFramedAsDrafts() {
        val soap = SummaryTemplates.ALL.first { it.key == "soap" }
        assertTrue(soap.llmInstructions.contains("draft"))
        val legal = SummaryTemplates.ALL.first { it.key == "legal_consult" }
        assertTrue(legal.llmInstructions.contains("not legal advice"))
    }

    @Test
    fun effectiveAppendsDepthAndSharedRules() {
        val base = SummaryTemplates.ALL.first()
        for (depth in listOf("brief", "standard", "detailed")) {
            val effective = SummaryTemplates.effective(base, depth)
            assertTrue(effective.llmInstructions.startsWith(base.llmInstructions))
            assertTrue(
                effective.llmInstructions.contains(
                    SummaryTemplates.depthInstructions(depth)
                )
            )
            assertTrue(effective.llmInstructions.endsWith(SummaryTemplates.OUTPUT_RULES))
        }
        // Unknown depth falls back to the standard scaling instruction.
        assertEquals(
            SummaryTemplates.depthInstructions("standard"),
            SummaryTemplates.depthInstructions("nonsense")
        )
    }

    @Test
    fun depthLevelsAreDistinct() {
        val brief = SummaryTemplates.depthInstructions("brief")
        val standard = SummaryTemplates.depthInstructions("standard")
        val detailed = SummaryTemplates.depthInstructions("detailed")
        assertNotEquals(brief, standard)
        assertNotEquals(standard, detailed)
        assertNotEquals(brief, detailed)
    }

    @Test
    fun effectiveKeepsTemplateIdentity() {
        val base = SummaryTemplates.ALL.first { it.key == "actions" }
        val effective = SummaryTemplates.effective(base, "brief")
        assertEquals(base.key, effective.key)
        assertEquals(base.extractiveActionsOnly, effective.extractiveActionsOnly)
    }
}
