package com.meetily.mobile

import com.meetily.mobile.licences.Licence
import com.meetily.mobile.licences.ModelLicences
import com.meetily.mobile.licences.TermsFamily
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.NemoModels
import com.meetily.mobile.whisper.WhisperModels
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Every downloadable model has a licence, the licences whose use restrictions
 * flow to users are the ones that ask first, and the two documents that carry
 * them (notices.txt and terms_of_use.txt) say what the code says.
 */
class ModelLicencesTest {

    private val catalogueKeys: List<String> =
        WhisperModels.ALL.map { it.key } +
            NemoModels.ALL.map { it.key } +
            DiarizationModels.ALL.map { it.key } +
            LocalLlmModels.ALL.map { it.key }

    @Test
    fun everyDownloadableModelHasALicence() {
        // A model missing here would download with no licence on its card
        // and no acceptance asked, so a new catalogue entry must land here.
        for (key in catalogueKeys) {
            assertNotNull("no licence entry for $key", ModelLicences.forKey(key))
        }
    }

    @Test
    fun licenceEntriesNameOnlyRealModels() {
        assertEquals(catalogueKeys.toSet(), ModelLicences.keys)
    }

    @Test
    fun modelKeysAreUniqueAcrossCatalogues() {
        // Licences are looked up by key alone, whatever the model's kind.
        assertEquals(catalogueKeys.size, catalogueKeys.toSet().size)
    }

    @Test
    fun llamaModelsAreBuiltWithLlamaUnderTheirOwnVersion() {
        assertEquals(Licence.LLAMA_3_2, ModelLicences.forKey("llama3.2-3b"))
        assertEquals(Licence.LLAMA_3_1, ModelLicences.forKey("llama3.1-8b"))
        assertTrue(ModelLicences.familyFor("llama3.2-3b")!!.builtWithLlama)
        assertTrue(ModelLicences.familyFor("llama3.1-8b")!!.builtWithLlama)
        assertFalse(TermsFamily.GEMMA.builtWithLlama)
        assertFalse(TermsFamily.HAI_DEF.builtWithLlama)
    }

    @Test
    fun medGemmaIsUnderHaiDefNotTheGemmaTerms() {
        assertEquals(TermsFamily.HAI_DEF, ModelLicences.familyFor("medgemma-4b"))
        assertEquals(TermsFamily.GEMMA, ModelLicences.familyFor("gemma3-1b"))
        assertEquals(TermsFamily.GEMMA, ModelLicences.familyFor("gemma3-4b"))
    }

    @Test
    fun permissiveLicencesAskNothing() {
        for (key in listOf("base.en", "parakeet-tdt-v3", "resnet34-en", "qwen2.5-1.5b")) {
            assertNull("$key should download without an acceptance", ModelLicences.familyFor(key))
        }
        // Unconfirmed licences are labelled as such rather than guessed at.
        assertNull(ModelLicences.familyFor("gemma4-e2b"))
        assertNull(ModelLicences.familyFor("unknown-model"))
    }

    @Test
    fun acceptanceIsAskedUntilTheCurrentVersionIsStored() {
        for (family in TermsFamily.values()) {
            assertTrue("never accepted", family.needsAcceptance(0))
            assertFalse("accepted as it stands", family.needsAcceptance(family.version))
        }
        // A publisher revising its terms (a raised version) asks again.
        assertTrue(TermsFamily.needsAcceptance(currentVersion = 2, storedVersion = 1))
        assertFalse(TermsFamily.needsAcceptance(currentVersion = 2, storedVersion = 2))
    }

    @Test
    fun familyIdsAreDistinct() {
        val ids = TermsFamily.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun noticesCarryEveryRequiredNotice() {
        val notices = asset("notices.txt")
        assertTrue(notices.contains("Built with Llama"))
        for (version in listOf("3.2", "3.1")) {
            assertTrue(
                "missing the Llama $version notice",
                squash(notices).contains(
                    "Llama $version is licensed under the Llama $version Community License, " +
                        "Copyright © Meta Platforms, Inc. All Rights Reserved."
                )
            )
            assertTrue(notices.contains("LLAMA $version COMMUNITY LICENSE AGREEMENT"))
        }
        assertTrue(
            squash(notices).contains(
                "Gemma is provided under and subject to the Gemma Terms of Use found at " +
                    "ai.google.dev/gemma/terms"
            )
        )
        assertTrue(
            squash(notices).contains(
                "HAI-DEF is provided under and subject to the Health AI Developer " +
                    "Foundations Terms of Use found at " +
                    "https://developers.google.com/health-ai-developer-foundations/terms"
            )
        )
        for (family in TermsFamily.values()) {
            assertTrue("notices lack ${family.policyUrl}", notices.contains(family.policyUrl))
        }
    }

    @Test
    fun termsOfUseIncorporateEveryFamilysTermsAndPolicy() {
        val terms = asset("terms_of_use.txt")
        for (family in TermsFamily.values()) {
            assertTrue("terms lack ${family.termsUrl}", terms.contains(family.termsUrl))
            assertTrue("terms lack ${family.policyUrl}", terms.contains(family.policyUrl))
        }
        assertTrue(terms.contains("Built with Llama", ignoreCase = true))
    }

    @Test
    fun withdrawnModelAppearsNowhere() {
        // SaulLM was removed from the catalogue; nothing the user reads may
        // still offer or credit it.
        // Word-start match, so "assault" in a policy text is not a hit.
        val saul = Regex("\\bsaul", RegexOption.IGNORE_CASE)
        for (model in LocalLlmModels.ALL) {
            assertFalse(saul.containsMatchIn(model.displayName))
        }
        assertFalse(saul.containsMatchIn(asset("notices.txt")))
        assertFalse(saul.containsMatchIn(asset("terms_of_use.txt")))
    }

    private fun squash(text: String): String = text.split(Regex("\\s+")).joinToString(" ")

    /**
     * Reads a shipped asset from the source tree. Gradle runs unit tests from
     * the module directory; any other runner skips these checks rather than
     * failing on a path.
     */
    private fun asset(name: String): String {
        val file = listOf("src/main/assets/$name", "app/src/main/assets/$name")
            .map { File(it) }
            .firstOrNull { it.isFile }
        assumeTrue("asset $name not found from ${File(".").absolutePath}", file != null)
        return file!!.readText()
    }
}
