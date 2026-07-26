package com.meetily.mobile

import com.meetily.mobile.whisper.NemoModels
import com.meetily.mobile.whisper.TranscriptionModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NeMo model catalogue, and the two things about it that are easy to get
 * silently wrong: which models a picker offers, and which ones get told what
 * language to decode.
 */
class NemoModelsTest {

    private val nemotron35 = NemoModels.byKeyOrNull("nemotron-3.5")
    private val nemotronEn = NemoModels.byKeyOrNull("nemotron-en")

    @Test
    fun supersededModelStaysResolvableForInstallsThatHaveIt() {
        // Deleting the entry outright is the bug this guards. A user whose
        // setting still says "nemotron-en" would find the key unresolvable,
        // and TranscriptionModels falls back to the WHISPER family for
        // unknown keys — so their NeMo model would quietly become a whisper
        // one, and a missing whisper one at that.
        assertNotNull(nemotronEn)
        assertTrue(TranscriptionModels.allKeys().contains("nemotron-en"))
        assertTrue(TranscriptionModels.isNemo("nemotron-en"))
    }

    @Test
    fun supersededModelIsOfferedOnlyWhenAlreadyDownloaded() {
        val fresh = NemoModels.offered { false }.map { it.key }
        assertTrue("a new install must not be shown the superseded model",
            !fresh.contains("nemotron-en"))
        assertTrue(fresh.contains("nemotron-3.5"))

        val hasIt = NemoModels.offered { it.key == "nemotron-en" }.map { it.key }
        assertTrue("an install that has it must keep seeing it",
            hasIt.contains("nemotron-en"))
    }

    @Test
    fun onlyTheMultilingualCheckpointGetsALanguagePrompt() {
        // Setting the option on a model with no language-tag tokens changes
        // the behaviour of the engine people are using today for no gain.
        assertEquals("auto", nemotron35?.languageOption)
        assertNull(nemotronEn?.languageOption)
        assertNull(NemoModels.byKeyOrNull("parakeet-tdt-v3")?.languageOption)
    }

    @Test
    fun anEnglishOnlyPromptedModelWouldPinEnglishRatherThanAutoDetect() {
        // Guards the branch rather than a current model: englishOnly and
        // languagePrompt are independent flags, and "auto" on an
        // English-only checkpoint would ask it to detect what it cannot.
        val hypothetical = nemotron35!!.copy(englishOnly = true)
        assertEquals("en", hypothetical.languageOption)
    }

    @Test
    fun everyModelDeclaresItsFilesAndBuildsRealUrls() {
        assertTrue(NemoModels.ALL.isNotEmpty())
        for (model in NemoModels.ALL) {
            assertTrue("${model.key} has no files", model.files.isNotEmpty())
            assertTrue("${model.key} reports no size", model.totalMb > 0)
            for (file in model.files) {
                val url = model.urlFor(file)
                assertTrue(
                    "malformed url for ${model.key}/${file.name}: $url",
                    url.startsWith("https://huggingface.co/") &&
                        url.endsWith("/resolve/main/${file.name}") &&
                        !url.contains(" ")
                )
            }
        }
    }

    @Test
    fun nemotron35SizesAreItsOwnNotItsSiblings() {
        // They were assumed equal, and are not: the multilingual vocabulary
        // makes the decoder and joiner several times larger.
        val enEncoder = nemotronEn!!.files.first { it.name == "encoder.int8.onnx" }.sizeMb
        val newEncoder = nemotron35!!.files.first { it.name == "encoder.int8.onnx" }.sizeMb
        assertTrue("sizes must not be copied from the English sibling",
            newEncoder != enEncoder)
        assertTrue(nemotron35.totalMb > nemotronEn.totalMb)
    }

    @Test
    fun multilingualStreamingModelIsNotMarkedEnglishOnly() {
        assertTrue(nemotron35!!.streaming)
        assertTrue(!nemotron35.englishOnly)
        assertTrue(nemotron35.languagePrompt)
    }
}
