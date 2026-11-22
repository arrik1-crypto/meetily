package com.meetily.mobile

import com.meetily.mobile.llm.LocalLlmModel
import com.meetily.mobile.llm.LocalLlmModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opt-in summary models (Gemma 4 QAT, Qwen 3.5 2B and 4B): each one is
 * set up like the shipped entry it sits next to, downloads the text GGUF its
 * repo publishes, and the picker lists each family smallest first.
 */
class OptInModelsTest {

    private fun model(key: String): LocalLlmModel =
        LocalLlmModels.ALL.single { it.key == key }

    @Test
    fun gemma4QatCopiesItsUnslothSibling() {
        val pairs = listOf("gemma4-e2b-qat" to "gemma4-e2b", "gemma4-e4b-qat" to "gemma4-e4b")
        for ((qat, sibling) in pairs) {
            val a = model(qat)
            val b = model(sibling)
            assertEquals(qat, b.documentedRamGb, a.documentedRamGb)
            assertEquals(qat, b.minRamGb, a.minRamGb)
            assertEquals(qat, b.fitNeed.slowOnBaseline, a.fitNeed.slowOnBaseline)
        }
        // The unsloth entries themselves are unchanged.
        assertTrue(model("gemma4-e2b").url.startsWith("https://huggingface.co/unsloth/"))
        assertTrue(model("gemma4-e4b").url.startsWith("https://huggingface.co/unsloth/"))
    }

    @Test
    fun gemma4QatComesFromGooglesOwnRepos() {
        for (size in listOf("E2B", "E4B")) {
            val key = "gemma4-${size.lowercase()}-qat"
            val repo = "google/gemma-4-$size-it-qat-q4_0-gguf"
            val file = "gemma-4-${size}_q4_0-it.gguf"
            val m = model(key)
            assertEquals(key, file, m.fileName)
            assertEquals(key, "https://huggingface.co/$repo/resolve/main/$file", m.url)
            assertFalse(key, m.url.contains("mmproj"))
        }
    }

    @Test
    fun qwen35SmallSizesFollowThe9b() {
        val nine = model("qwen3.5-9b")
        for (key in listOf("qwen35-2b", "qwen35-4b")) {
            val m = model(key)
            // Same quantiser and quantisation as the 9B.
            assertTrue(key, m.url.startsWith("https://huggingface.co/bartowski/Qwen_Qwen3.5-"))
            assertTrue(key, m.fileName.endsWith("-Q4_K_M.gguf"))
            assertTrue(key, m.url.endsWith("/resolve/main/${m.fileName}"))
            assertFalse(key, m.url.contains("mmproj"))
            assertEquals(key, nine.fitNeed.slowOnBaseline, m.fitNeed.slowOnBaseline)
            assertTrue(key, m.minRamGb <= nine.minRamGb)
        }
        assertEquals("Qwen_Qwen3.5-2B-Q4_K_M.gguf", model("qwen35-2b").fileName)
        assertEquals("Qwen_Qwen3.5-4B-Q4_K_M.gguf", model("qwen35-4b").fileName)
    }

    @Test
    fun sizesAreTheRepoFilesInDecimalMegabytes() {
        // Byte counts from Hugging Face; isDownloaded accepts a file once it
        // is past 90% of the size, so the figure must not overstate it.
        val bytes = mapOf(
            "gemma4-e2b-qat" to 3_349_516_256L,
            "gemma4-e4b-qat" to 5_154_941_280L,
            "qwen35-2b" to 1_396_198_496L,
            "qwen35-4b" to 3_013_027_808L
        )
        for ((key, size) in bytes) {
            val m = model(key)
            assertEquals(key, Math.round(size / 1e6).toInt(), m.sizeMb)
            assertTrue(key, size > m.sizeMb * 1024L * 1024L * 9 / 10)
        }
    }

    @Test
    fun eachFamilyIsListedSmallestFirstAndTogether() {
        val keys = LocalLlmModels.ALL.map { it.key }
        val families = listOf(
            listOf("gemma4-e2b", "gemma4-e2b-qat", "gemma4-e4b", "gemma4-e4b-qat"),
            listOf("qwen35-2b", "qwen35-4b", "qwen3.5-9b")
        )
        for (family in families) {
            val at = family.map { keys.indexOf(it) }
            assertEquals(family.toString(), (at.first()..at.last()).toList(), at)
            val sizes = family.map { model(it).sizeMb }
            assertEquals(family.toString(), sizes.sorted(), sizes)
        }
    }

    @Test
    fun namesStateTheirRamLikeTheRestOfTheCatalogue() {
        for (key in listOf("gemma4-e2b-qat", "gemma4-e4b-qat", "qwen35-2b", "qwen35-4b")) {
            val m = model(key)
            assertTrue(key, m.displayName.contains("· ${m.minRamGb} GB+ RAM)"))
        }
    }
}
