package com.meetily.mobile

import com.meetily.mobile.whisper.NemoModels
import com.meetily.mobile.whisper.TranscriptionModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun parakeetUltraLoadsExactlyLikeV3() {
        // Same graph, same vocabulary, same offline transducer path: the only
        // differences are the repo and the bytes.
        val v3 = NemoModels.byKeyOrNull("parakeet-tdt-v3")!!
        val ultra = NemoModels.byKeyOrNull("parakeet-ultra")!!
        assertEquals(v3.files.map { it.name }, ultra.files.map { it.name })
        assertEquals(v3.streaming, ultra.streaming)
        assertEquals(v3.englishOnly, ultra.englishOnly)
        assertEquals(v3.languagePrompt, ultra.languagePrompt)
        assertNull(ultra.languageOption)
        assertFalse(ultra.legacy)
        assertTrue(ultra.repo.startsWith("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra"))
    }

    @Test
    fun parakeetUltraIsOfferedBesideV3() {
        val fresh = NemoModels.offered { false }.map { it.key }
        assertTrue(fresh.contains("parakeet-tdt-v3"))
        assertTrue(fresh.contains("parakeet-ultra"))
        assertEquals(fresh.indexOf("parakeet-tdt-v3") + 1, fresh.indexOf("parakeet-ultra"))
    }

    @Test
    fun parakeetUltraSizesAreReadOffItsRepo() {
        // MiB, as NemoProgressAggregator counts them: 652,282,345 /
        // 11,845,274 / 6,355,277 / 93,939 bytes.
        val ultra = NemoModels.byKeyOrNull("parakeet-ultra")!!
        assertEquals(listOf(622, 11, 6, 1), ultra.files.map { it.sizeMb })
        assertEquals(640, ultra.totalMb)
    }

    @Test
    fun modelCardsCreditWhoeverTrainedTheWeights() {
        // The card's source is the same credit the licence list and
        // notices.txt give: Moondream for Parakeet Ultra, NVIDIA for the
        // rest of the NeMo family, Whisper for everything else.
        assertEquals("Moondream", TranscriptionModels.publisher("parakeet-ultra"))
        NemoModels.ALL.filter { it.key != "parakeet-ultra" }.forEach {
            assertEquals(it.key, "NVIDIA", TranscriptionModels.publisher(it.key))
        }
        assertEquals("Whisper", TranscriptionModels.publisher("base"))
    }

    @Test
    fun multilingualStreamingModelIsNotMarkedEnglishOnly() {
        assertTrue(nemotron35!!.streaming)
        assertTrue(!nemotron35.englishOnly)
        assertTrue(nemotron35.languagePrompt)
    }
}
