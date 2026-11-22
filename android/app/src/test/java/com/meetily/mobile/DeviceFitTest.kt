package com.meetily.mobile

import com.meetily.mobile.data.DeviceFit
import com.meetily.mobile.data.DeviceFit.CpuTier
import com.meetily.mobile.data.DeviceFit.Level
import com.meetily.mobile.data.DeviceFit.Limit
import com.meetily.mobile.data.DownloadSpace
import com.meetily.mobile.data.FirstRunSetup
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.NemoModels
import com.meetily.mobile.whisper.TranscriptionModels
import com.meetily.mobile.whisper.WhisperModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory fit and the first-run suggestions: which models each common phone
 * size gets, that a model too big for the phone is never suggested, that an
 * English-only model is suggested only for English, and that the estimated
 * requirements agree with the RAM the catalogue's names already state.
 */
class DeviceFitTest {

    private val gib = 1024L * 1024L * 1024L

    /** What a phone sold as [gb] GB typically reports as totalMem. */
    private fun totalMemFor(gb: Int): Long = (gb * gib * 0.92).toLong()

    private fun device(gb: Int, tier: CpuTier = CpuTier.DOTPROD, language: String = "en") =
        DeviceFit.Device(totalMemFor(gb), null, tier, null, language)

    private fun need(key: String): DeviceFit.Need? =
        TranscriptionModels.fitNeedOrNull(key) ?: LocalLlmModels.fitNeedOrNull(key)

    private fun recommend(gb: Int, tier: CpuTier = CpuTier.DOTPROD, language: String = "en") =
        DeviceFit.recommend(device(gb, tier, language), ::need)

    private fun level(key: String, gb: Int): Level =
        DeviceFit.classify(need(key)!!, device(gb))!!.level

    // --- RAM size ----------------------------------------------------------

    @Test
    fun totalMemRoundsUpToTheSizeThePhoneWasSoldAs() {
        assertEquals(3, DeviceFit.ramClassGb((2.7 * gib).toLong()))
        assertEquals(4, DeviceFit.ramClassGb((3.6 * gib).toLong()))
        assertEquals(6, DeviceFit.ramClassGb((5.5 * gib).toLong()))
        assertEquals(8, DeviceFit.ramClassGb((7.4 * gib).toLong()))
        assertEquals(12, DeviceFit.ramClassGb((11.2 * gib).toLong()))
        assertEquals(16, DeviceFit.ramClassGb((15.0 * gib).toLong()))
        // A large carve-out on a 12 GB phone reads low, never high.
        assertEquals(10, DeviceFit.ramClassGb((9.8 * gib).toLong()))
        assertEquals(40, DeviceFit.ramClassGb((39.5 * gib).toLong()))
        assertEquals(0, DeviceFit.ramClassGb(0L))
    }

    @Test
    fun advertisedMemIsReadInEitherUnit() {
        assertEquals(8, DeviceFit.advertisedGb(8L * gib))
        assertEquals(8, DeviceFit.advertisedGb(8_000_000_000L))
        assertEquals(6, DeviceFit.advertisedGb(6_000_000_000L))
        assertEquals(12, DeviceFit.advertisedGb(12L * gib))
        assertEquals(0, DeviceFit.advertisedGb(0L))
    }

    @Test
    fun advertisedMemIsOnlyADisplayHint() {
        val withHint = DeviceFit.Device((5.5 * gib).toLong(), 8L * gib, CpuTier.DOTPROD, null, "en")
        assertEquals(8, withHint.displayRamGb)
        // The verdicts still come from totalMem.
        assertEquals(6, withHint.ramGb)
        val gemma4 = LocalLlmModels.byKey("gemma4-e2b").fitNeed
        assertEquals(Level.TOO_BIG, DeviceFit.classify(gemma4, withHint)!!.level)
        val noHint = DeviceFit.Device((5.5 * gib).toLong(), null, CpuTier.DOTPROD, null, "en")
        assertEquals(6, noHint.displayRamGb)
    }

    // --- Requirements --------------------------------------------------------

    @Test
    fun llmRequirementsMatchTheRamTheirNamesState() {
        val expected = mapOf(
            "qwen2.5-0.5b" to 2, "gemma3-1b" to 3, "qwen2.5-1.5b" to 4,
            "llama3.2-3b" to 6, "gemma3-4b" to 6, "medgemma-4b" to 6,
            "gemma4-e2b" to 8, "gemma4-e4b" to 12, "mistral-7b" to 12,
            "llama3.1-8b" to 12, "qwen3.5-9b" to 16,
            "gemma4-e2b-qat" to 8, "gemma4-e4b-qat" to 12,
            "qwen35-2b" to 4, "qwen35-4b" to 8
        )
        assertEquals(LocalLlmModels.ALL.map { it.key }.toSet(), expected.keys)
        for (model in LocalLlmModels.ALL) {
            assertEquals(model.key, expected[model.key], model.minRamGb)
        }
    }

    @Test
    fun aRamFigureInTheNameIsTheRequirement() {
        val stated = Regex("""(\d+) GB\+? RAM""")
        for (model in LocalLlmModels.ALL) {
            val inName = stated.find(model.displayName)?.groupValues?.get(1)?.toInt()
            // Every name that states a figure carries it as documentedRamGb,
            // and the badge then agrees with the name.
            assertEquals(model.key, inName, model.documentedRamGb)
            if (inName != null) assertEquals(model.key, inName, model.minRamGb)
        }
    }

    @Test
    fun documentedRamOnlyEverRaisesTheEstimate() {
        // Qwen 3.5 9B's formula figure is 12; its label says 16.
        assertEquals(12, DeviceFit.minRamGb(DeviceFit.llmWorkingSetMb(5680)))
        assertEquals(16, DeviceFit.minRamGb(DeviceFit.llmWorkingSetMb(5680), 16))
        // A label lower than the estimate does not lower it.
        assertEquals(12, DeviceFit.minRamGb(DeviceFit.llmWorkingSetMb(5680), 8))
    }

    // --- LLM context window ----------------------------------------------------

    @Test
    fun theOverheadGrowsWithTheWindowAndNotBelowTheBase() {
        // At 4096 tokens the formula is the one every Need is costed at,
        // unchanged, so no catalogue figure moves.
        assertEquals(1732, DeviceFit.llmWorkingSetMb(1120))
        assertEquals(DeviceFit.llmWorkingSetMb(1120), DeviceFit.llmWorkingSetMb(1120, 4096))
        assertEquals(DeviceFit.LLM_RUNTIME_MB, DeviceFit.llmRuntimeMb(1120, 4096))
        // An eighth of the file per extra 4096 tokens: Qwen 2.5 1.5B at 8K
        // is costed 140 MB more (its q8_0 cache grows by about 62 MB).
        assertEquals(1872, DeviceFit.llmWorkingSetMb(1120, 8192))
        assertEquals(500 + 615, DeviceFit.llmRuntimeMb(4920, 8192))
        assertEquals(500 + 420, DeviceFit.llmRuntimeMb(1120, 16384))
        // A smaller window does not shrink the compute buffers.
        assertEquals(DeviceFit.LLM_RUNTIME_MB, DeviceFit.llmRuntimeMb(1120, 2048))
    }

    @Test
    fun smallModelsAreSetUpForTheLargerWindow() {
        assertEquals(8192, DeviceFit.defaultLlmContextTokens(DeviceFit.SMALL_LLM_MAX_MB))
        assertEquals(4096, DeviceFit.defaultLlmContextTokens(DeviceFit.SMALL_LLM_MAX_MB + 1))
        val wide = setOf("qwen2.5-0.5b", "qwen2.5-1.5b", "gemma3-1b")
        for (model in LocalLlmModels.ALL) {
            val expected = if (model.key in wide) 8192 else 4096
            assertEquals(model.key, expected, model.contextTokens)
            assertEquals(model.key, model.contextTokens, model.fitNeed.contextTokens)
        }
    }

    private fun plan(key: String, gb: Int): DeviceFit.LlmPlan =
        DeviceFit.llmPlan(need(key)!!, device(gb))

    @Test
    fun theWindowEachModelGetsAcrossPhoneSizes() {
        // (RAM GB, window, RAM-tight): 8K only where the model is still
        // comfortable with the larger cache, 4K and tight below that.
        val table = mapOf(
            "qwen2.5-0.5b" to listOf(
                Triple(2, 4096, true), Triple(3, 8192, false), Triple(16, 8192, false)
            ),
            "gemma3-1b" to listOf(
                Triple(3, 4096, true), Triple(4, 8192, false), Triple(8, 8192, false)
            ),
            "qwen2.5-1.5b" to listOf(
                Triple(3, 4096, true), Triple(4, 4096, true), Triple(6, 8192, false),
                Triple(12, 8192, false)
            ),
            // 3B and larger stay at 4K everywhere; tight below their comfort.
            "llama3.2-3b" to listOf(Triple(6, 4096, true), Triple(8, 4096, false)),
            "gemma3-4b" to listOf(Triple(6, 4096, true), Triple(8, 4096, false)),
            "gemma4-e2b" to listOf(
                Triple(6, 4096, true), Triple(8, 4096, true), Triple(12, 4096, false)
            ),
            "gemma4-e4b" to listOf(Triple(12, 4096, true), Triple(16, 4096, false)),
            "qwen3.5-9b" to listOf(Triple(16, 4096, true), Triple(24, 4096, false))
        )
        for ((key, rows) in table) {
            for ((gb, window, tight) in rows) {
                val p = plan(key, gb)
                assertEquals("$key $gb GB window", window, p.contextTokens)
                assertEquals("$key $gb GB tight", tight, p.ramTight)
            }
        }
    }

    @Test
    fun unknownRamGetsTheBaseWindowAndTheSmallCache() {
        val unknown = DeviceFit.Device(0L, null, CpuTier.DOTPROD, null, "en")
        val p = DeviceFit.llmPlan(need("qwen2.5-1.5b")!!, unknown)
        assertEquals(4096, p.contextTokens)
        assertTrue(p.ramTight)
        assertEquals(p, DeviceFit.llmPlan(need("qwen2.5-1.5b")!!, null))
    }

    @Test
    fun aLargeModelAskingForMoreIsHeldTo4kUnder8Gb() {
        // No catalogue entry does this today; the rule holds if one does.
        val big = DeviceFit.llmNeed("big", 2020, null, slowOnBaseline = true, contextTokens = 8192)
        assertEquals(4096, DeviceFit.llmPlan(big, device(6)).contextTokens)
        // From 8 GB it is the comfort check that decides: 2974 MB at 8K
        // is within 40% of 8 GB.
        assertEquals(8192, DeviceFit.llmPlan(big, device(8)).contextTokens)
        assertEquals(8192, DeviceFit.llmPlan(big, device(16)).contextTokens)
    }

    @Test
    fun theWindowIsNeverMoreThanTheModelIsSetUpFor() {
        val short = DeviceFit.llmNeed("short", 800, null, slowOnBaseline = false, contextTokens = 2048)
        for (gb in listOf(2, 4, 8, 16)) {
            assertEquals(2048, DeviceFit.llmPlan(short, device(gb)).contextTokens)
        }
        // A larger request is costed at its own size: 16K needs 6 GB here
        // where the base window is comfortable from 4 GB.
        val long = DeviceFit.llmNeed("long", 1000, null, slowOnBaseline = false, contextTokens = 16384)
        assertEquals(4096, DeviceFit.llmPlan(long, device(4)).contextTokens)
        assertEquals(16384, DeviceFit.llmPlan(long, device(6)).contextTokens)
    }

    /** The verdict from a Need's own figures, all costed at 4096 tokens. */
    private fun baseLevel(n: DeviceFit.Need, gb: Int): Level = when {
        gb < n.minRamGb -> Level.TOO_BIG
        gb < n.comfortableRamGb -> Level.TIGHT
        else -> Level.FITS
    }

    @Test
    fun theLargerWindowNeverChangesAVerdict() {
        // classify costs each phone at the window it gets. A window is
        // granted only where the model is comfortable at it, so that is
        // the 4K verdict everywhere (badges and first-run suggestions do
        // not move), and "RAM-tight" is exactly "not FITS".
        for (model in LocalLlmModels.ALL) {
            for (gb in listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32)) {
                val verdict = DeviceFit.classify(model.fitNeed, device(gb))!!
                val p = DeviceFit.llmPlan(model.fitNeed, device(gb))
                assertEquals("${model.key} $gb", baseLevel(model.fitNeed, gb), verdict.level)
                if (p.contextTokens > DeviceFit.LLM_BASE_CONTEXT) {
                    assertEquals("${model.key} $gb", Level.FITS, verdict.level)
                    val atWindow = DeviceFit.llmWorkingSetMb(model.sizeMb, p.contextTokens)
                    assertTrue("${model.key} $gb", atWindow <= gb * 1024 * 0.40)
                }
                assertEquals("${model.key} $gb", verdict.level != Level.FITS, p.ramTight)
            }
        }
    }

    @Test
    fun aPhoneIsCostedAtTheWindowItGets() {
        // A 1.5 GB model set up for 8K. Costed at 8K everywhere it would
        // need a 6 GB phone even to run...
        val m = DeviceFit.llmNeed("m", 1500, null, slowOnBaseline = false, contextTokens = 8192)
        assertEquals(2337, DeviceFit.llmWorkingSetMb(1500, 8192))
        assertEquals(6, DeviceFit.minRamGb(DeviceFit.llmWorkingSetMb(1500, 8192)))
        // ...but a 4 GB phone runs it at 4K, where it is tight, not too big.
        assertEquals(4, m.minRamGb)
        assertEquals(4096, DeviceFit.llmPlan(m, device(4)).contextTokens)
        assertEquals(Level.TIGHT, DeviceFit.classify(m, device(4))!!.level)
        assertTrue(DeviceFit.llmPlan(m, device(4)).ramTight)
        // 6 GB holds it comfortably at 8K (2337 MB within 40% of 6 GB), so
        // it gets 8K and fits there.
        assertEquals(8192, DeviceFit.llmPlan(m, device(6)).contextTokens)
        assertEquals(Level.FITS, DeviceFit.classify(m, device(6))!!.level)
        assertFalse(DeviceFit.llmPlan(m, device(6)).ramTight)
    }

    @Test
    fun theTrainedWindowClampsTheRequest() {
        // The rule llama_jni.c's context_for_model applies; the same table
        // was run against the C function on the host.
        val cases = listOf(
            Triple(8192, 32768, 8192), Triple(8192, 131072, 8192), Triple(8192, 8192, 8192),
            Triple(8192, 4096, 4096), Triple(8192, 4000, 3840), Triple(4096, 2048, 2048),
            Triple(8192, 200, 200), Triple(8192, 0, 8192), Triple(0, 0, 4096),
            Triple(-5, 32768, 4096), Triple(4096, -1, 4096), Triple(300, 0, 300)
        )
        for ((requested, trained, want) in cases) {
            assertEquals(
                "$requested/$trained", want, DeviceFit.clampToTrainedContext(requested, trained)
            )
        }
        assertEquals(8192, DeviceFit.clampToTrainedContext(8192, null))
        // Never above the trained window, even after llama.cpp rounds UP to 256.
        for (trained in listOf(1000, 3000, 4000, 6000, 7999, 8191)) {
            val n = DeviceFit.clampToTrainedContext(8192, trained)
            assertTrue("$trained", n <= trained)
            assertEquals("$trained", 0, n % 256)
        }
    }

    @Test
    fun theDecisionTableAcrossRamModelSizeAndTrainedWindow() {
        // (model, RAM GB, n_ctx_train or null before the first load) -> window.
        val rows = listOf(
            // Small model, comfortable at 8K: the trained window can only cut it.
            Triple("qwen2.5-1.5b", 8, null) to 8192,
            Triple("qwen2.5-1.5b", 8, 32768) to 8192,
            Triple("qwen2.5-1.5b", 8, 6000) to 5888,
            Triple("qwen2.5-1.5b", 8, 4096) to 4096,
            // Small model on a phone too tight for 8K: 4K, or less if trained on less.
            Triple("qwen2.5-1.5b", 4, 32768) to 4096,
            Triple("qwen2.5-1.5b", 4, 2048) to 2048,
            Triple("gemma3-1b", 3, 32768) to 4096,
            Triple("gemma3-1b", 4, 32768) to 8192,
            Triple("qwen2.5-0.5b", 3, 32768) to 8192,
            // 3B and larger: 4K on every phone, under 8 GB or not.
            Triple("llama3.2-3b", 6, 131072) to 4096,
            Triple("gemma3-4b", 12, 131072) to 4096,
            Triple("gemma3-4b", 12, 2048) to 2048,
            Triple("llama3.1-8b", 16, 131072) to 4096
        )
        for ((input, want) in rows) {
            val (key, gb, trained) = input
            val p = DeviceFit.llmPlan(need(key)!!, device(gb), trained)
            assertEquals("$key $gb GB trained $trained", want, p.contextTokens)
        }
        // A trained window below the base never makes a tight phone roomy:
        // the compute buffers do not shrink with the window.
        assertTrue(DeviceFit.llmPlan(need("qwen2.5-1.5b")!!, device(4), 2048).ramTight)
        assertFalse(DeviceFit.llmPlan(need("gemma3-4b")!!, device(12), 2048).ramTight)
    }

    @Test
    fun transcriptionRequirements() {
        val expected = mapOf(
            "tiny.en" to (1 to 2), "base.en" to (2 to 2), "small.en-q5_1" to (2 to 2),
            "small.en" to (3 to 4), "large-v3-turbo-q5_0" to (3 to 4),
            "parakeet-tdt-v2" to (3 to 4), "nemotron-3.5" to (3 to 6),
            "parakeet-ultra" to (3 to 4)
        )
        for ((key, pair) in expected) {
            val n = TranscriptionModels.fitNeedOrNull(key)!!
            assertEquals(key, pair.first, n.minRamGb)
            assertEquals(key, pair.second, n.comfortableRamGb)
        }
    }

    @Test
    fun everyCatalogueEntryHasAConsistentRequirement() {
        val needs = WhisperModels.ALL.map { it.fitNeed } +
            NemoModels.ALL.map { it.fitNeed } +
            DiarizationModels.ALL.map { it.fitNeed } +
            LocalLlmModels.ALL.map { it.fitNeed }
        for (n in needs) {
            assertTrue(n.key, n.minRamGb >= 1)
            assertTrue(n.key, n.comfortableRamGb >= n.minRamGb)
            assertTrue(n.key, n.workingSetMb > n.sizeMb)
        }
        assertNull(TranscriptionModels.fitNeedOrNull("no-such-model"))
        assertNull(LocalLlmModels.fitNeedOrNull("no-such-model"))
    }

    @Test
    fun slowOnTheBaselineProcessor() {
        assertFalse(WhisperModels.byKey("tiny.en").fitNeed.slowOnBaseline)
        assertFalse(WhisperModels.byKey("base").fitNeed.slowOnBaseline)
        assertTrue(WhisperModels.byKey("small.en-q5_1").fitNeed.slowOnBaseline)
        assertTrue(WhisperModels.byKey("large-v3-turbo-q5_0").fitNeed.slowOnBaseline)
        // onnxruntime chooses its own kernels.
        assertFalse(NemoModels.byKeyOrNull("parakeet-tdt-v3")!!.fitNeed.slowOnBaseline)
        assertFalse(LocalLlmModels.byKey("qwen2.5-0.5b").fitNeed.slowOnBaseline)
        assertFalse(LocalLlmModels.byKey("gemma3-1b").fitNeed.slowOnBaseline)
        assertTrue(LocalLlmModels.byKey("qwen2.5-1.5b").fitNeed.slowOnBaseline)
    }

    // --- Verdicts across phone sizes ------------------------------------------

    @Test
    fun fitAcrossCommonPhoneSizes() {
        val sizes = listOf(3, 4, 6, 8, 12, 16)
        fun row(key: String) = sizes.map { level(key, it) }
        val f = Level.FITS
        val t = Level.TIGHT
        val x = Level.TOO_BIG
        assertEquals(listOf(f, f, f, f, f, f), row("small.en-q5_1"))
        assertEquals(listOf(t, f, f, f, f, f), row("large-v3-turbo-q5_0"))
        assertEquals(listOf(t, t, f, f, f, f), row("nemotron-3.5"))
        assertEquals(listOf(f, f, f, f, f, f), row("qwen2.5-0.5b"))
        assertEquals(listOf(t, f, f, f, f, f), row("gemma3-1b"))
        assertEquals(listOf(x, t, f, f, f, f), row("qwen2.5-1.5b"))
        assertEquals(listOf(x, x, t, f, f, f), row("gemma3-4b"))
        assertEquals(listOf(x, x, x, t, f, f), row("gemma4-e2b"))
        assertEquals(listOf(x, x, x, x, t, f), row("gemma4-e4b"))
        assertEquals(listOf(x, x, x, x, x, t), row("qwen3.5-9b"))
        // The opt-in entries.
        assertEquals(listOf(t, f, f, f, f, f), row("parakeet-ultra"))
        assertEquals(listOf(x, t, f, f, f, f), row("qwen35-2b"))
        assertEquals(listOf(x, x, x, t, f, f), row("qwen35-4b"))
        assertEquals(listOf(x, x, x, t, f, f), row("gemma4-e2b-qat"))
        assertEquals(listOf(x, x, x, x, t, f), row("gemma4-e4b-qat"))
    }

    @Test
    fun optInModelsAreNeverSuggested() {
        // Nothing about them has been measured on a phone, so the first-run
        // suggestions, and their order, are exactly what they were.
        val optIn = setOf(
            "parakeet-ultra", "gemma4-e2b-qat", "gemma4-e4b-qat", "qwen35-2b", "qwen35-4b"
        )
        assertEquals(
            listOf("qwen2.5-1.5b", "gemma3-1b", "qwen2.5-0.5b"), DeviceFit.llmCandidates()
        )
        assertEquals(
            listOf("small.en-q5_1", "base.en", "tiny.en"), DeviceFit.transcriptionCandidates("en")
        )
        assertEquals(listOf("small-q5_1", "base", "tiny"), DeviceFit.transcriptionCandidates("de"))
        for (gb in listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32)) {
            for (tier in CpuTier.values()) {
                for (language in listOf("en", "de", "ja")) {
                    val rec = recommend(gb, tier, language)
                    assertFalse("$gb $tier $language", rec.transcription?.need?.key in optIn)
                    assertFalse("$gb $tier $language", rec.llm?.need?.key in optIn)
                }
            }
        }
        // The fallback default for an unknown key is still Qwen 2.5 1.5B.
        assertEquals("qwen2.5-1.5b", LocalLlmModels.ALL.first().key)
        assertEquals("qwen2.5-1.5b", LocalLlmModels.byKey("no-such-model").key)
    }

    @Test
    fun unknownRamGivesNoVerdictAndNoSuggestion() {
        val unknown = DeviceFit.Device(0L, null, CpuTier.DOTPROD, null, "en")
        assertNull(DeviceFit.classify(need("base.en")!!, unknown))
        val rec = DeviceFit.recommend(unknown, ::need)
        assertNull(rec.transcription)
        assertNull(rec.llm)
    }

    @Test
    fun slowHereOnlyOnTheBaselineProcessor() {
        val turbo = need("large-v3-turbo-q5_0")!!
        assertTrue(DeviceFit.classify(turbo, device(8, CpuTier.BASELINE))!!.slowHere)
        assertFalse(DeviceFit.classify(turbo, device(8, CpuTier.DOTPROD))!!.slowHere)
    }

    @Test
    fun onlyTightOrTooBigWarnsAndNeverTheModelAlreadyInUse() {
        val small = need("qwen2.5-1.5b")!!
        val fits = DeviceFit.classify(small, device(8))
        val tight = DeviceFit.classify(small, device(4))
        val tooBig = DeviceFit.classify(small, device(3))
        assertFalse(DeviceFit.warnsOnChoice(fits, alreadyActive = false))
        assertTrue(DeviceFit.warnsOnChoice(tight, alreadyActive = false))
        assertTrue(DeviceFit.warnsOnChoice(tooBig, alreadyActive = false))
        assertFalse(DeviceFit.warnsOnChoice(tooBig, alreadyActive = true))
        assertFalse(DeviceFit.warnsOnChoice(null, alreadyActive = false))
    }

    // --- First-run suggestions --------------------------------------------------

    @Test
    fun englishFastProcessorAcrossPhoneSizes() {
        for (gb in listOf(3, 4, 6, 8, 12, 16)) {
            val rec = recommend(gb)
            assertEquals("$gb GB", "small.en-q5_1", rec.transcription!!.need.key)
            assertEquals("$gb GB", Limit.NONE, rec.transcription!!.limit)
        }
        assertEquals("qwen2.5-0.5b", recommend(3).llm!!.need.key)
        assertEquals(Limit.MEMORY, recommend(3).llm!!.limit)
        assertEquals("gemma3-1b", recommend(4).llm!!.need.key)
        assertEquals(Limit.MEMORY, recommend(4).llm!!.limit)
        for (gb in listOf(6, 8, 12, 16)) {
            assertEquals("$gb GB", "qwen2.5-1.5b", recommend(gb).llm!!.need.key)
            assertEquals("$gb GB", Limit.NONE, recommend(gb).llm!!.limit)
        }
    }

    @Test
    fun baselineProcessorGetsLighterModels() {
        for (gb in listOf(3, 4, 6, 8, 12, 16)) {
            val rec = recommend(gb, CpuTier.BASELINE)
            assertEquals("$gb GB", "base.en", rec.transcription!!.need.key)
            assertEquals("$gb GB", Limit.PROCESSOR, rec.transcription!!.limit)
            assertFalse(rec.transcription!!.verdict.slowHere)
            assertFalse(rec.llm!!.verdict.slowHere)
        }
        assertEquals("qwen2.5-0.5b", recommend(3, CpuTier.BASELINE).llm!!.need.key)
        assertEquals("gemma3-1b", recommend(4, CpuTier.BASELINE).llm!!.need.key)
        // Memory alone would allow Qwen 2.5 1.5B here: the processor is why not.
        val six = recommend(6, CpuTier.BASELINE).llm!!
        assertEquals("gemma3-1b", six.need.key)
        assertEquals(Limit.PROCESSOR, six.limit)
    }

    @Test
    fun multilingualLocalesGetMultilingualModels() {
        for (language in listOf("de", "fr", "es", "ja", "zh", "hi", "")) {
            for (tier in CpuTier.values()) {
                for (gb in listOf(3, 4, 6, 8, 12, 16)) {
                    val t = recommend(gb, tier, language).transcription!!
                    assertFalse("$language $tier $gb", t.need.englishOnly)
                }
            }
        }
        assertEquals("small-q5_1", recommend(8, language = "de").transcription!!.need.key)
        assertEquals("base", recommend(8, CpuTier.BASELINE, "de").transcription!!.need.key)
        // English, in any letter case, gets the English-only model.
        assertTrue(recommend(8, language = "EN").transcription!!.need.englishOnly)
    }

    @Test
    fun theSmallestPhonesStillGetATranscriptionModel() {
        val one = recommend(1)
        assertEquals("tiny.en", one.transcription!!.need.key)
        assertEquals(Level.TIGHT, one.transcription!!.verdict.level)
        assertEquals(Limit.MEMORY, one.transcription!!.limit)
        // The summary model is optional, so only a comfortable fit is offered.
        assertNull(one.llm)
        assertNull(recommend(2).llm)
        assertEquals("small.en-q5_1", recommend(2).transcription!!.need.key)
    }

    @Test
    fun neverSuggestsATooBigOrSlowModel() {
        for (gb in listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32)) {
            for (tier in CpuTier.values()) {
                for (language in listOf("en", "de")) {
                    val rec = recommend(gb, tier, language)
                    val t = rec.transcription
                    assertNotNull("$gb $tier $language", t)
                    assertNotEquals(Level.TOO_BIG, t!!.verdict.level)
                    assertFalse(t.verdict.slowHere)
                    rec.llm?.let {
                        assertEquals(Level.FITS, it.verdict.level)
                        assertFalse(it.verdict.slowHere)
                    }
                }
            }
        }
    }

    @Test
    fun theSummaryModelIsLeftOutWhenTheSpaceCannotHoldBoth() {
        fun withRoom(gb: Int, bytes: Long?, language: String = "en") = DeviceFit.recommend(
            DeviceFit.Device(totalMemFor(gb), null, CpuTier.DOTPROD, bytes, language), ::need
        )
        // Small.en Q (181 MB) and Qwen 2.5 1.5B (1120 MB), 10% on each.
        val both = DownloadSpace.requiredBytes(listOf(181, 1120))
        for (gb in listOf(6, 8, 12, 16)) {
            val unknown = withRoom(gb, null)
            assertEquals("$gb GB", "qwen2.5-1.5b", unknown.llm!!.need.key)
            assertFalse(unknown.llmLeftOutForSpace)
            val exact = withRoom(gb, both)
            assertEquals("$gb GB", "qwen2.5-1.5b", exact.llm!!.need.key)
            assertFalse(exact.llmLeftOutForSpace)
            val short = withRoom(gb, both - 1)
            assertNull("$gb GB", short.llm)
            assertTrue(short.llmLeftOutForSpace)
            // The transcription suggestion never depends on the space.
            assertEquals(unknown.transcription, short.transcription)
            assertEquals(unknown.transcription, withRoom(gb, 0L).transcription)
        }
        // A phone with no summary model to offer reports nothing left out.
        val small = withRoom(2, 0L)
        assertNull(small.llm)
        assertFalse(small.llmLeftOutForSpace)
        // The same rule for another pair: multilingual on a 3 GB phone.
        val three = withRoom(3, null, "de")
        val pair = DownloadSpace.requiredBytes(
            listOf(three.transcription!!.need.sizeMb, three.llm!!.need.sizeMb)
        )
        assertNotNull(withRoom(3, pair, "de").llm)
        assertNull(withRoom(3, pair - 1, "de").llm)
    }

    @Test
    fun suggestionsAreAlwaysCatalogueModels() {
        for (key in DeviceFit.transcriptionCandidates("en") +
            DeviceFit.transcriptionCandidates("de")
        ) {
            assertNotNull(key, TranscriptionModels.fitNeedOrNull(key))
        }
        for (key in DeviceFit.llmCandidates()) {
            assertNotNull(key, LocalLlmModels.fitNeedOrNull(key))
        }
    }

    // --- The setup step's choices ---------------------------------------------------

    @Test
    fun eachChoiceSetsUpWhatItSays() {
        val rec = recommend(8)
        val t = rec.transcription!!
        val l = rec.llm!!
        assertEquals(listOf(t, l), FirstRunSetup.picks(rec, FirstRunSetup.Choice.BOTH))
        assertEquals(listOf(t), FirstRunSetup.picks(rec, FirstRunSetup.Choice.TRANSCRIPTION_ONLY))
        assertTrue(FirstRunSetup.picks(rec, FirstRunSetup.Choice.LATER).isEmpty())
        // With no summary model to offer, "both" is the transcription model.
        val small = recommend(2)
        assertEquals(
            listOf(small.transcription!!),
            FirstRunSetup.picks(small, FirstRunSetup.Choice.BOTH)
        )
    }

    @Test
    fun onlyMissingModelsDownload() {
        val rec = recommend(8)
        val both = FirstRunSetup.picks(rec, FirstRunSetup.Choice.BOTH)
        val missing = FirstRunSetup.toDownload(both) { it.need.kind == DeviceFit.Kind.TRANSCRIPTION }
        assertEquals(listOf(rec.llm!!), missing)
        assertEquals(listOf(181, 1120), FirstRunSetup.sizesMb(both))
    }

    @Test
    fun theStepIsOfferedOnlyWhereItCanHelp() {
        val rec = recommend(8)
        assertTrue(FirstRunSetup.shouldOffer(rec, onDeviceEngine = true))
        assertFalse(FirstRunSetup.shouldOffer(rec, onDeviceEngine = false))
        assertFalse(FirstRunSetup.shouldOffer(null, onDeviceEngine = true))
        assertFalse(
            FirstRunSetup.shouldOffer(DeviceFit.Recommendation(null, null), onDeviceEngine = true)
        )
    }
}
