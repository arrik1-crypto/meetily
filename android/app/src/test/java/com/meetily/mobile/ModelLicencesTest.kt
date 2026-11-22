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
        val permissive = listOf(
            "base.en", "parakeet-tdt-v3", "parakeet-ultra", "resnet34-en", "qwen2.5-1.5b",
            "qwen35-2b", "qwen35-4b"
        )
        for (key in permissive) {
            assertNull("$key should download without an acceptance", ModelLicences.familyFor(key))
        }
        // Unconfirmed licences are labelled as such rather than guessed at.
        assertEquals(Licence.SEE_MODEL_PAGE, ModelLicences.forKey("mistral-7b"))
        assertNull(ModelLicences.familyFor("mistral-7b"))
        assertNull(ModelLicences.familyFor("unknown-model"))
    }

    @Test
    fun gemma4IsApacheAndOnlyGemma3KeepsTheGemmaTerms() {
        // Google publishes Gemma 4 under Apache 2.0 (its licence page is the
        // Apache text), so neither the unsloth files nor Google's QAT builds
        // ask for the Gemma Terms. Gemma 3 and MedGemma still do.
        for (key in listOf("gemma4-e2b", "gemma4-e2b-qat", "gemma4-e4b", "gemma4-e4b-qat")) {
            assertEquals(key, Licence.APACHE_2, ModelLicences.forKey(key))
            assertNull("$key should download without an acceptance", ModelLicences.familyFor(key))
        }
        for (key in listOf("gemma3-1b", "gemma3-4b")) {
            assertEquals(key, TermsFamily.GEMMA, ModelLicences.familyFor(key))
        }
        assertEquals(TermsFamily.HAI_DEF, ModelLicences.familyFor("medgemma-4b"))
    }

    @Test
    fun parakeetUltraCreditsMoondreamAndNvidia() {
        assertEquals(Licence.CC_BY_4, ModelLicences.forKey("parakeet-ultra"))
        val entry = noticesEntryText("Parakeet Ultra 0.6B (25 languages)")
        assertTrue(entry, entry.contains("Moondream"))
        assertTrue(entry, entry.contains("NVIDIA"))
        assertTrue(entry, entry.contains("https://huggingface.co/moondream/parakeet-ultra"))
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
    fun noticesNameTheSameLicenceAsThePickers() {
        // The picker cards and the acceptance dialog read ModelLicences; the
        // licences screen reads part two of notices.txt, written by hand in
        // build_notices.py. Each model's "Licence:" line there must name the
        // licence ModelLicences gives it, or the two screens disagree.
        val partTwo = asset("notices.txt")
            .substringAfter("PART TWO - MODELS DOWNLOADED ON DEMAND")
            .substringBefore("PART THREE - LICENCE TEXTS")
            .lines()
        for (key in catalogueKeys) {
            val heading = noticesEntry[key]
                ?: throw AssertionError("no notices.txt entry heading for $key in this test")
            val start = partTwo.indexOf(heading)
            assertTrue("notices.txt part two has no entry \"$heading\" ($key)", start >= 0)
            // An entry runs from its heading to the next unindented line.
            val licenceLine = partTwo.drop(start + 1)
                .takeWhile { it.isBlank() || it.startsWith(" ") }
                .map { it.trim() }
                .firstOrNull { it.startsWith("Licence:") }
                ?: throw AssertionError("notices.txt entry \"$heading\" has no Licence: line")
            val licence = ModelLicences.forKey(key)!!
            assertTrue(
                "$key is $licence in ModelLicences but notices.txt says \"$licenceLine\"",
                licenceLine.contains(noticesName(licence))
            )
            if (licence != Licence.SEE_MODEL_PAGE) {
                assertFalse(
                    "$key is $licence in ModelLicences but notices.txt says \"$licenceLine\"",
                    licenceLine.contains("see model page")
                )
            }
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

    /**
     * The heading of each model's entry in part two of notices.txt. A model
     * added to a catalogue, or an entry heading renamed, must be added here.
     */
    private val noticesEntry: Map<String, String> =
        WhisperModels.ALL.associate {
            it.key to "Whisper (tiny, base, small, large-v3-turbo; several quantised)"
        } + mapOf(
            "parakeet-tdt-v2" to "Parakeet TDT 0.6B v2 (English)",
            "parakeet-tdt-v3" to "Parakeet TDT 0.6B v3 (25 languages)",
            "parakeet-ultra" to "Parakeet Ultra 0.6B (25 languages)",
            "nemotron-3.5" to "Nemotron 3.5 ASR Streaming 0.6B (40 language-locales)",
            "nemotron-en" to "Nemotron Speech Streaming 0.6B (English)",
            "resnet34-en" to "WeSpeaker ResNet34 / ResNet152 / ResNet293 (VoxCeleb)",
            "resnet152-en" to "WeSpeaker ResNet34 / ResNet152 / ResNet293 (VoxCeleb)",
            "resnet293-en" to "WeSpeaker ResNet34 / ResNet152 / ResNet293 (VoxCeleb)",
            "titanet-large-en" to "NeMo TitaNet large / small",
            "titanet-en" to "NeMo TitaNet large / small",
            "campplus-zh-en" to "3D-Speaker CAM++ (English + Chinese)",
            "qwen2.5-0.5b" to "Qwen 2.5 0.5B Instruct, Qwen 2.5 1.5B Instruct",
            "qwen2.5-1.5b" to "Qwen 2.5 0.5B Instruct, Qwen 2.5 1.5B Instruct",
            "qwen35-2b" to "Qwen 3.5 2B, Qwen 3.5 4B",
            "qwen35-4b" to "Qwen 3.5 2B, Qwen 3.5 4B",
            "qwen3.5-9b" to "Qwen 3.5 9B",
            "llama3.2-3b" to "Llama 3.2 3B Instruct - Built with Llama",
            "llama3.1-8b" to "Llama 3.1 8B Instruct - Built with Llama",
            "gemma3-1b" to "Gemma 3 1B Instruct, Gemma 3 4B Instruct",
            "gemma3-4b" to "Gemma 3 1B Instruct, Gemma 3 4B Instruct",
            "gemma4-e2b" to "Gemma 4 E2B Instruct, Gemma 4 E4B Instruct",
            "gemma4-e4b" to "Gemma 4 E2B Instruct, Gemma 4 E4B Instruct",
            "gemma4-e2b-qat" to "Gemma 4 E2B Instruct, Gemma 4 E4B Instruct",
            "gemma4-e4b-qat" to "Gemma 4 E2B Instruct, Gemma 4 E4B Instruct",
            "mistral-7b" to "Mistral 7B Instruct v0.3",
            "medgemma-4b" to "MedGemma 4B"
        )

    /** How a "Licence:" line in part two of notices.txt names [licence]. */
    private fun noticesName(licence: Licence): String = when (licence) {
        Licence.MIT -> "MIT License"
        Licence.APACHE_2 -> "Apache License 2.0"
        Licence.CC_BY_4 -> "CC BY 4.0"
        Licence.GEMMA -> "Gemma Terms of Use"
        Licence.HAI_DEF -> "Health AI Developer Foundations Terms of Use"
        Licence.LLAMA_3_2 -> "Llama 3.2 Community License"
        Licence.LLAMA_3_1 -> "Llama 3.1 Community License"
        Licence.SEE_MODEL_PAGE -> "see model page"
    }

    private fun squash(text: String): String = text.split(Regex("\\s+")).joinToString(" ")

    /** One part-two entry of notices.txt: its heading to the next unindented line. */
    private fun noticesEntryText(heading: String): String {
        val partTwo = asset("notices.txt")
            .substringAfter("PART TWO - MODELS DOWNLOADED ON DEMAND")
            .substringBefore("PART THREE - LICENCE TEXTS")
            .lines()
        val start = partTwo.indexOf(heading)
        assertTrue("notices.txt part two has no entry \"$heading\"", start >= 0)
        return partTwo.drop(start + 1)
            .takeWhile { it.isBlank() || it.startsWith(" ") }
            .joinToString("\n")
    }

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
