package com.meetily.mobile.whisper

import android.content.Context
import com.meetily.mobile.data.Bzip2InputStream
import com.meetily.mobile.data.DeviceFit
import com.meetily.mobile.data.ResumableDownload
import com.meetily.mobile.data.TarExtract
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * A forced-alignment model for "Re-time words" (WordAlign, AlignService):
 * an English MFA acoustic model with its pronunciation lexicon, packaged by
 * sherpa-onnx for its ForcedAligner and published only as one .tar.bz2 in
 * the fa-models release.
 */
data class AlignerModel(
    val key: String,
    val displayName: String,
    /** The upstream release asset, which [url] fetches. */
    val fileName: String,
    /** The archive's own folder, and the folder the model is unpacked into. */
    val dirName: String,
    /** The download, MB (binary, as every catalogue counts). */
    val approxSizeMb: Int,
    /** The files kept once unpacked, MB. */
    val unpackedMb: Int,
    /**
     * Pinned SHA-256 of the archive, when known. Deliberately not pinned
     * yet: the owner deferred pinning model downloads. The archive is
     * treated as untrusted either way (TarExtract).
     */
    val sha256: String? = null
) {
    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/fa-models/$fileName"
}

object AlignerModels {

    /**
     * english-mfa v3.0.0: the 95,000-word MFA dictionary (v3.1.0's is 42,000
     * words and leaves out inflections such as "deletes"), for 1 MB more of
     * download. Words the dictionary lacks are aligned as unknown speech and
     * placed between their neighbours (WordAlign), so a larger dictionary
     * means more words timed from the audio itself; meetings are full of
     * names and jargon. 48,540,205 bytes; unpacked, the files kept are
     * 56,289,729 bytes (the test recording, en.wav, is not kept).
     */
    val ALL: List<AlignerModel> = listOf(
        AlignerModel(
            key = "english-mfa-v3.0.0",
            displayName = "English MFA v3.0.0 (95,000 words)",
            fileName = "english-mfa-v3.0.0.tar.bz2",
            dirName = "english-mfa-v3.0.0",
            approxSizeMb = 47,
            unpackedMb = 54
        )
    )

    /** The model "Re-time words" uses. */
    val DEFAULT: AlignerModel get() = ALL.first()

    fun byKey(key: String): AlignerModel = ALL.firstOrNull { it.key == key } ?: ALL.first()

    /**
     * Estimated native memory while the aligner is loaded, MB: the GMM
     * acoustic model as stored (final.alimdl, 48 MB), the lexicon parsed
     * into one string vector per pronunciation (about 204,000 of them,
     * roughly 120 MB), and the per-line decoding graph and features, which
     * are small. Read off the file sizes and sherpa-onnx's data structures,
     * not measured on a phone.
     */
    const val WORKING_SET_MB = 200

    /**
     * The files taken out of the archive, with a cap on each: more than
     * twice its size in v3.0.0 (final.alimdl 50,223,203 bytes, lexicon.txt
     * 5,530,360, tree 514,250, the rest under 15 KB), so a repackaging
     * fits, while an archive that is damaged or not what it claims writes
     * 172 MB at most, however it is made (the space check asks for 101 MB).
     * Anything else in it is read past, not kept, and refused above
     * TarExtract.MAX_OTHER_BYTES.
     */
    private const val MB = 1024L * 1024L
    val WANTED: List<TarExtract.Wanted> = listOf(
        TarExtract.Wanted("meta.json", MB),
        TarExtract.Wanted("final.alimdl", 128 * MB),
        TarExtract.Wanted("tree", 8 * MB),
        TarExtract.Wanted("lda.mat", MB),
        TarExtract.Wanted("phones.txt", MB),
        TarExtract.Wanted("lexicon.txt", 32 * MB),
        TarExtract.Wanted("README.md", MB)
    )

    /** What the aligner cannot load without (phones.txt and lda.mat it checks itself). */
    val REQUIRED: Set<String> = setOf("meta.json", "final.alimdl", "tree", "lexicon.txt")

    /** Trailing archive padding read past the last file, at most; more is refused. */
    private const val MAX_TRAILER_BYTES = 4L * MB

    /** Download share of the progress; unpacking takes the rest. */
    private const val DOWNLOAD_SHARE = 90

    /** Room a download needs: the archive and the unpacked files side by side. */
    fun spaceNeededMb(model: AlignerModel): Int = model.approxSizeMb + model.unpackedMb

    /** For DeviceFit: an English-only model of [WORKING_SET_MB]. */
    fun need(model: AlignerModel): DeviceFit.Need {
        val min = DeviceFit.minRamGb(WORKING_SET_MB)
        return DeviceFit.Need(
            key = model.key,
            kind = DeviceFit.Kind.SPEAKER_ID,
            sizeMb = model.unpackedMb,
            workingSetMb = WORKING_SET_MB,
            minRamGb = min,
            comfortableRamGb = DeviceFit.comfortableRamGb(WORKING_SET_MB, min),
            englishOnly = true,
            slowOnBaseline = false
        )
    }

    fun root(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** The unpacked model. Exists only once complete: it is renamed into place. */
    fun dirFor(context: Context, model: AlignerModel): File = File(root(context), model.dirName)

    fun archiveFor(context: Context, model: AlignerModel): File = File(root(context), model.fileName)

    private fun stagingFor(context: Context, model: AlignerModel): File =
        File(root(context), model.dirName + STAGING_SUFFIX)

    fun lexiconFor(context: Context, model: AlignerModel): File = File(dirFor(context, model), "lexicon.txt")

    /**
     * True when the unpacked model is in place. Lenient on sizes (upstream
     * may repackage), strict on presence. A few stats: workers, or a tap.
     */
    fun isDownloaded(context: Context, model: AlignerModel): Boolean {
        val dir = File(File(context.filesDir, DIR), model.dirName)
        if (!dir.isDirectory) return false
        if (REQUIRED.any { !File(dir, it).isFile }) return false
        return File(dir, "final.alimdl").length() > 4L * MB && File(dir, "lexicon.txt").length() > 64L * 1024
    }

    /** The bytes the model holds on disk, archive and leftovers included. */
    fun bytesOnDisk(context: Context): Long =
        File(context.filesDir, DIR).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(context: Context, model: AlignerModel) {
        dirFor(context, model).deleteRecursively()
        stagingFor(context, model).deleteRecursively()
        val archive = archiveFor(context, model)
        archive.delete()
        ResumableDownload.discardPartial(archive)
    }

    /** Week-old resumable parts, and unpacking a process did not live to finish. */
    fun cleanPartials(context: Context) {
        val root = File(context.filesDir, DIR)
        if (!root.isDirectory) return
        ResumableDownload.cleanPartials(root)
        root.listFiles()?.filter { it.isDirectory && it.name.endsWith(STAGING_SUFFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * Blocking download and unpack with 0..100 progress; call from a worker
     * thread (ModelDownloadService). An interrupted download resumes from
     * its part file; an archive already downloaded is only unpacked again.
     * [cancelled] stops either half with InterruptedException, keeping the
     * downloaded part or archive for next time.
     */
    fun download(
        context: Context,
        model: AlignerModel,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false }
    ) {
        if (isDownloaded(context, model)) {
            onProgress(100)
            return
        }
        val archive = archiveFor(context, model)
        if (!archive.isFile || archive.length() <= 0L) {
            ResumableDownload.download(
                url = model.url,
                target = archive,
                label = model.fileName,
                expectedSha256 = model.sha256,
                cancelled = cancelled,
                onBytes = ResumableDownload.percentReporter { p -> onProgress(p * DOWNLOAD_SHARE / 100) }
            )
        }
        unpack(context, model, archive, onProgress, cancelled)
    }

    /**
     * True when an unpack that failed with [e] keeps its archive: a file it
     * could not write (a full disk) says nothing about the archive. Any
     * other failure (damage, an unsafe entry, a file missing) deletes it.
     */
    fun keepsArchive(e: IOException): Boolean = e is TarExtract.WriteFailedException

    /**
     * Unpacks [archive] into a staging folder, checks the files the aligner
     * needs are there, and renames it into place; then the archive goes. A
     * damaged or unsafe archive is deleted, so the next attempt downloads it
     * again; a cancelled unpack, or one that could not write its files (no
     * space left, say), keeps it, so the next attempt only unpacks it.
     */
    private fun unpack(
        context: Context,
        model: AlignerModel,
        archive: File,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean
    ) {
        val staging = stagingFor(context, model)
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Cannot unpack ${model.fileName}")
        val total = archive.length().coerceAtLeast(1L)
        var lastPercent = -1
        try {
            val written = Bzip2InputStream(BufferedInputStream(FileInputStream(archive), 256 * 1024)).use { bz ->
                val names = TarExtract.extract(bz, staging, WANTED, cancelled) {
                    val p = DOWNLOAD_SHARE + ((bz.compressedBytesRead * (100 - DOWNLOAD_SHARE)) / total).toInt()
                    if (p != lastPercent) {
                        lastPercent = p
                        onProgress(p.coerceIn(DOWNLOAD_SHARE, 99))
                    }
                }
                // Read to the end, so the last block's CRC and the stream's
                // are checked too: they cover the files' final bytes.
                val scratch = ByteArray(8192)
                var trailer = 0L
                while (true) {
                    if (cancelled()) throw InterruptedException("Unpacking cancelled")
                    val n = bz.read(scratch)
                    if (n < 0) break
                    trailer += n
                    if (trailer > MAX_TRAILER_BYTES) {
                        throw TarExtract.UnsafeArchiveException("${model.fileName} has unexpected data at its end")
                    }
                }
                names
            }
            val missing = REQUIRED - written
            if (missing.isNotEmpty()) {
                throw IOException("${model.fileName} lacks ${missing.sorted().joinToString()}")
            }
            val target = dirFor(context, model)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("Cannot move ${model.dirName} into place")
            archive.delete()
            onProgress(100)
        } catch (e: InterruptedException) {
            staging.deleteRecursively()
            throw e
        } catch (e: IOException) {
            staging.deleteRecursively()
            // A file that could not be written (a full disk) is not the
            // archive's fault: it is kept, and download() only unpacks it
            // next time. Damaged, unsafe or incomplete: fetched afresh.
            if (!keepsArchive(e)) {
                archive.delete()
                ResumableDownload.discardPartial(archive)
            }
            throw e
        } catch (e: RuntimeException) {
            staging.deleteRecursively()
            archive.delete()
            throw IOException("${model.fileName} could not be unpacked", e)
        }
    }

    private const val DIR = "aligner-models"
    private const val STAGING_SUFFIX = ".unpacking"
}
