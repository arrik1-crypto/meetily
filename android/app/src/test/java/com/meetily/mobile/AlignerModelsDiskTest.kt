package com.meetily.mobile

import com.meetily.mobile.data.ResumeLogic
import com.meetily.mobile.data.TarExtract
import com.meetily.mobile.whisper.AlignerModels
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The word re-timing model's files on disk: what an unpack that fails
 * leaves, what Settings' row then offers, and what the sweep removes. The
 * archive is src/test/resources/align/model-standin.tar.bz2 (491 bytes),
 * written by Python's tarfile (ustar) and bz2 modules: english-mfa-v3.0.0's
 * layout, with stand-in files just large enough for isDownloaded
 * (final.alimdl 5 MiB of zeros, lexicon.txt 84,000 bytes). Nothing here
 * downloads: the archive is always in place first.
 */
class AlignerModelsDiskTest {

    private val model = AlignerModels.DEFAULT
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("aligner-models").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun standIn(): ByteArray {
        val candidates = listOf(
            "src/test/resources/align/model-standin.tar.bz2",
            "app/src/test/resources/align/model-standin.tar.bz2",
            "android/app/src/test/resources/align/model-standin.tar.bz2"
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: throw AssertionError("model-standin.tar.bz2 not found from ${File(".").absolutePath}")
        return file.readBytes()
    }

    private fun placeArchive(bytes: ByteArray = standIn()): File =
        File(root, model.fileName).apply { writeBytes(bytes) }

    private val staging: File get() = File(root, model.dirName + ".unpacking")

    /**
     * Unpacks the archive in place, running [inject] on the staging folder
     * before the first entry is read, and returns the failure it causes.
     */
    private fun unpackFailing(inject: (File) -> Unit): IOException {
        var injected = false
        try {
            AlignerModels.download(root, model, {}, {
                if (!injected && staging.isDirectory) {
                    inject(staging)
                    injected = true
                }
                false
            })
        } catch (e: IOException) {
            assertTrue(injected)
            return e
        }
        throw AssertionError("unpacked although a file could not be written")
    }

    @Test
    fun theStandInUnpacksAndItsArchiveGoes() {
        val archive = placeArchive()
        val progress = mutableListOf<Int>()
        AlignerModels.download(root, model, { progress += it })
        assertTrue(AlignerModels.isDownloaded(root, model))
        assertFalse(archive.exists())
        assertFalse(AlignerModels.hasKeptArchive(root, model))
        assertEquals(100, progress.last())
    }

    @Test
    fun aDownloadThatCouldNotBeUnpackedIsKeptAndCanBeDeleted() {
        val archive = placeArchive()
        val size = archive.length()
        // final.alimdl cannot be created: a write that failed, not damage.
        val e = unpackFailing { File(it, "final.alimdl").mkdirs() }
        assertTrue(e is TarExtract.WriteFailedException)
        // Kept, whole, for the next try; nothing half-unpacked is left.
        assertTrue(archive.isFile)
        assertEquals(size, archive.length())
        assertFalse(staging.exists())
        assertFalse(AlignerModels.isDownloaded(root, model))
        // Settings' row offers to unpack it or delete it, sized by what is
        // on disk, and the space check asks only for the unpacked files.
        assertTrue(AlignerModels.hasKeptArchive(root, model))
        assertEquals(size, AlignerModels.bytesOnDisk(root))
        assertEquals(model.unpackedMb, AlignerModels.spaceNeededMb(root, model))
        assertTrue(AlignerModels.spaceNeededMb(root, model) < AlignerModels.spaceNeededMb(model))
        // Delete download takes the space back.
        AlignerModels.delete(root, model)
        assertFalse(archive.exists())
        assertFalse(AlignerModels.hasKeptArchive(root, model))
        assertEquals(0L, AlignerModels.bytesOnDisk(root))
        assertEquals(AlignerModels.spaceNeededMb(model), AlignerModels.spaceNeededMb(root, model))
    }

    @Test
    fun aFullDiskWhileUnpackingKeepsTheDownloadToo() {
        // Where lexicon.txt goes, a full disk: every write fails with ENOSPC.
        val full = File("/dev/full")
        assumeTrue(full.exists())
        val archive = placeArchive()
        val e = unpackFailing {
            Files.createSymbolicLink(File(it, "lexicon.txt").toPath(), full.toPath())
        }
        assertTrue(e is TarExtract.WriteFailedException)
        assertTrue(archive.isFile)
        assertTrue(AlignerModels.hasKeptArchive(root, model))
        assertEquals(model.unpackedMb, AlignerModels.spaceNeededMb(root, model))
        // The link went with the staging folder; /dev/full is untouched.
        assertFalse(staging.exists())
        assertTrue(full.exists())
        AlignerModels.delete(root, model)
        assertEquals(0L, AlignerModels.bytesOnDisk(root))
    }

    @Test
    fun aKeptDownloadIsOnlyUnpackedNextTime() {
        val archive = placeArchive()
        unpackFailing { File(it, "lexicon.txt").mkdirs() }
        assertTrue(AlignerModels.hasKeptArchive(root, model))
        val progress = mutableListOf<Int>()
        AlignerModels.download(root, model, { progress += it })
        assertTrue(AlignerModels.isDownloaded(root, model))
        assertFalse(archive.exists())
        assertFalse(AlignerModels.hasKeptArchive(root, model))
        // Nothing fetched: the progress starts at the unpacking share.
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { it >= 90 })
        assertEquals(100, progress.last())
    }

    @Test
    fun aDamagedDownloadIsDeletedNotKept() {
        val whole = standIn()
        val archive = placeArchive(whole.copyOf(whole.size - 24))
        val failed = try {
            AlignerModels.download(root, model, {})
            null
        } catch (e: IOException) {
            e
        }
        assertTrue(failed != null && failed !is TarExtract.WriteFailedException)
        assertFalse(archive.exists())
        assertFalse(AlignerModels.hasKeptArchive(root, model))
        assertFalse(staging.exists())
        assertEquals(AlignerModels.spaceNeededMb(model), AlignerModels.spaceNeededMb(root, model))
    }

    @Test
    fun aKeptDownloadIsSweptAWeekAfterItWasDownloaded() {
        val archive = placeArchive()
        val at = 1_700_000_000_000L
        assertTrue(archive.setLastModified(at))
        AlignerModels.cleanPartials(root, at + 24L * 60 * 60 * 1000)
        assertTrue("a day-old download waits for the next try", archive.isFile)
        AlignerModels.cleanPartials(root, at + ResumeLogic.STALE_PART_MS + 60_000L)
        assertFalse("a week-old one goes, like a resumable part", archive.exists())
        assertEquals(0L, AlignerModels.bytesOnDisk(root))
    }

    @Test
    fun theSweepLeavesAnUnpackedModelAndTakesAnArchiveBesideIt() {
        placeArchive()
        AlignerModels.download(root, model, {})
        assertTrue(AlignerModels.isDownloaded(root, model))
        val stray = File(root, model.fileName).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(staging, "lexicon.txt").apply { parentFile!!.mkdirs() }.writeText("left by a dead process")
        assertFalse(AlignerModels.hasKeptArchive(root, model))
        AlignerModels.cleanPartials(root, stray.lastModified())
        assertFalse(stray.exists())
        assertFalse(staging.exists())
        assertTrue(AlignerModels.isDownloaded(root, model))
    }

    @Test
    fun withNothingOnThePhoneTheSpaceCheckAsksForBoth() {
        assertFalse(AlignerModels.hasKeptArchive(root, model))
        assertEquals(AlignerModels.spaceNeededMb(model), AlignerModels.spaceNeededMb(root, model))
        // An empty file is no download (download() fetches it again).
        File(root, model.fileName).createNewFile()
        assertFalse(AlignerModels.hasKeptArchive(root, model))
        assertEquals(AlignerModels.spaceNeededMb(model), AlignerModels.spaceNeededMb(root, model))
    }
}
