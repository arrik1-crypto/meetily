package com.meetily.mobile

import com.meetily.mobile.data.DeviceFit
import com.meetily.mobile.data.DeviceFit.CpuTier
import com.meetily.mobile.data.DeviceFit.Level
import com.meetily.mobile.data.DeviceFit.Limit
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
            "llama3.1-8b" to 12, "qwen3.5-9b" to 16
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

    @Test
    fun transcriptionRequirements() {
        val expected = mapOf(
            "tiny.en" to (1 to 2), "base.en" to (2 to 2), "small.en-q5_1" to (2 to 2),
            "small.en" to (3 to 4), "large-v3-turbo-q5_0" to (3 to 4),
            "parakeet-tdt-v2" to (3 to 4), "nemotron-3.5" to (3 to 6)
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
