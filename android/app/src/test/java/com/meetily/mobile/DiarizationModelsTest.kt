package com.meetily.mobile

import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.VoiceProfile
import com.meetily.mobile.whisper.VoiceProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speaker-model catalogue, and the re-key of CAM++ after upstream
 * replaced its asset in place with a different graph.
 */
class DiarizationModelsTest {

    private val byKey = DiarizationModels.ALL.associateBy { it.key }

    @Test
    fun keysAndLocalFilesAreUnique() {
        // A shared local file would let isDownloaded() take one model's file
        // for another's, which is the mix-up the re-key exists to prevent.
        val keys = DiarizationModels.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val local = DiarizationModels.ALL.map { it.localName }
        assertEquals(local.size, local.toSet().size)
    }

    @Test
    fun theDefaultIsCurrent() {
        // byKey falls back to the first entry; it must never be a legacy one.
        assertEquals("resnet34-en", DiarizationModels.ALL.first().key)
        assertFalse(DiarizationModels.ALL.first().legacy)
        assertEquals("resnet34-en", DiarizationModels.byKey("no-such-model").key)
    }

    @Test
    fun legacyEntriesNameACurrentSuccessorOnTheSameAsset() {
        val legacy = DiarizationModels.ALL.filter { it.legacy }
        assertTrue(legacy.isNotEmpty())
        for (old in legacy) {
            val next = byKey[old.successor]
            assertNotNull("${old.key} has no successor in the catalogue", next)
            assertFalse(next!!.legacy)
            assertEquals(old.url, next.url)
            assertNotEquals(old.localName, next.localName)
        }
        for (current in DiarizationModels.ALL.filter { !it.legacy }) {
            assertNull(current.successor)
        }
    }

    @Test
    fun camPlusPlusIsReKeyed() {
        val old = byKey.getValue("campplus-zh-en")
        val new = byKey.getValue("campplus-zh-en-v2")
        assertTrue(old.legacy)
        assertEquals("campplus-zh-en-v2", old.successor)
        // The old key keeps the file name older installs downloaded.
        assertEquals("3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx", old.localName)
        assertTrue(new.url.endsWith("/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"))
        assertTrue(new.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"))
        // The pins stay for model_hashes.py and the owner to fill.
        assertNull(new.sha256)
    }

    @Test
    fun legacyIsOfferedOnlyWhereDownloaded() {
        val none = DiarizationModels.offered { false }.map { it.key }
        assertFalse("campplus-zh-en" in none)
        assertTrue("campplus-zh-en-v2" in none)
        assertEquals(DiarizationModels.ALL.count { !it.legacy }, none.size)

        val all = DiarizationModels.offered { true }.map { it.key }
        assertEquals(DiarizationModels.ALL.map { it.key }, all)
    }

    @Test
    fun aMissingLegacySelectionMovesToItsSuccessor() {
        val nothing = { _: com.meetily.mobile.whisper.DiarizationModel -> false }
        val everything = { _: com.meetily.mobile.whisper.DiarizationModel -> true }
        assertEquals("campplus-zh-en-v2", DiarizationModels.replacementFor("campplus-zh-en", nothing))
        // Still on the phone: it keeps working with the profiles made with it.
        assertNull(DiarizationModels.replacementFor("campplus-zh-en", everything))
        // Current models and unknown keys are never moved here.
        assertNull(DiarizationModels.replacementFor("campplus-zh-en-v2", nothing))
        assertNull(DiarizationModels.replacementFor("resnet34-en", nothing))
        assertNull(DiarizationModels.replacementFor("no-such-model", nothing))
    }

    @Test
    fun profilesFromTheOldExportNeverMatchTheNewOne() {
        // The same voice embedded by the two graphs is not comparable, so a
        // profile tagged with the old key is skipped under the new key (and
        // re-embedded from its banked audio by reembedForModel).
        val v = floatArrayOf(1f, 0f, 0f)
        val profiles = listOf(VoiceProfile("Mei", v, 3, model = "campplus-zh-en"))
        assertNull(VoiceProfileStore.match(profiles, v, model = "campplus-zh-en-v2"))
        assertEquals("Mei", VoiceProfileStore.match(profiles, v, model = "campplus-zh-en"))
        assertFalse(
            VoiceProfileStore.continuesProfile(profiles.single(), v, "campplus-zh-en-v2")
        )
    }
}
