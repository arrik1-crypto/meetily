package com.meetily.mobile

import com.meetily.mobile.data.DeviceFit
import com.meetily.mobile.data.DownloadSpace
import com.meetily.mobile.data.StorageUsage
import com.meetily.mobile.licences.Licence
import com.meetily.mobile.licences.ModelLicences
import com.meetily.mobile.whisper.AlignerModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The word re-timing model's catalogue entry and what is taken out of its archive. */
class AlignerModelsTest {

    private val model = AlignerModels.DEFAULT

    @Test
    fun theModelIsTheNinetyFiveThousandWordEnglishPackage() {
        assertEquals("english-mfa-v3.0.0", model.key)
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/fa-models/english-mfa-v3.0.0.tar.bz2",
            model.url
        )
        assertEquals("english-mfa-v3.0.0", model.dirName)
        assertEquals(model, AlignerModels.byKey("unknown"))
    }

    @Test
    fun theDownloadIsNotPinnedYet() {
        // The owner deferred pinning model downloads; the archive is
        // handled as untrusted instead (TarExtract).
        assertNull(model.sha256)
    }

    @Test
    fun sizesMatchTheArchiveAsPublished() {
        // 48,540,205 bytes to download, 56,289,729 bytes of files kept.
        assertEquals(47, model.approxSizeMb)
        assertEquals(54, model.unpackedMb)
        // Archive and files side by side until the archive goes.
        assertEquals(101, AlignerModels.spaceNeededMb(model))
        assertTrue(DownloadSpace.requiredBytes(AlignerModels.spaceNeededMb(model)) > 48_540_205L + 56_289_729L)
    }

    @Test
    fun everyFileTheAlignerNeedsIsTakenAndCappedAboveItsSize() {
        val caps = AlignerModels.WANTED.associate { it.name to it.maxBytes }
        assertTrue(caps.keys.containsAll(AlignerModels.REQUIRED))
        // The files of english-mfa-v3.0.0, as published.
        val published = mapOf(
            "meta.json" to 4_146L, "final.alimdl" to 50_223_203L, "tree" to 514_250L,
            "lda.mat" to 14_575L, "phones.txt" to 650L, "lexicon.txt" to 5_530_360L, "README.md" to 2_545L
        )
        for ((name, size) in published) {
            assertTrue("$name is capped below its size", caps.getValue(name) > size * 2)
        }
        // The test recording is not kept.
        assertTrue("en.wav" !in caps)
    }

    @Test
    fun itFitsAnyPhoneAndSaysItIsEnglishOnly() {
        val need = AlignerModels.need(model)
        assertTrue(need.englishOnly)
        assertEquals(AlignerModels.WORKING_SET_MB, need.workingSetMb)
        assertTrue(need.minRamGb <= need.comfortableRamGb)
        assertEquals(DeviceFit.Kind.SPEAKER_ID, need.kind)
    }

    @Test
    fun itIsCountedAsAModelOnTheStorageScreen() {
        val area = StorageUsage.areaFor(StorageUsage.Root.FILES, "aligner-models/english-mfa-v3.0.0/final.alimdl")
        assertEquals(StorageUsage.Area.ALIGN_MODELS, area)
        assertEquals(
            StorageUsage.Area.ALIGN_MODELS,
            StorageUsage.areaFor(StorageUsage.Root.FILES, "aligner-models/english-mfa-v3.0.0.tar.bz2.part")
        )
        assertTrue(StorageUsage.isModels(area))
    }

    @Test
    fun itsLicenceIsCcBy() {
        assertEquals(Licence.CC_BY_4, ModelLicences.forKey(model.key))
        assertNull(ModelLicences.familyFor(model.key))
    }

    @Test
    fun itDownloadsAsItsOwnKind() {
        assertEquals("align", ModelDownloadService.KIND_ALIGN)
        assertEquals(
            AlignerModels.spaceNeededMb(model),
            PendingModelSelection.downloadSizeMb(ModelDownloadService.KIND_ALIGN, model.key)
        )
    }
}
