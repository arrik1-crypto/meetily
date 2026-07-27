package com.meetily.mobile.data

import android.content.Context
import com.meetily.mobile.security.BackupCrypto
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-library backup: a single .zip containing every meeting JSON and every
 * photo. Import merges into the current library (existing ids are overwritten).
 * Settings — which can hold LLM API keys — are deliberately excluded.
 *
 * Backups can optionally be passphrase-encrypted ([exportEncrypted]); import
 * sniffs the header and handles both formats.
 */
object BackupManager {

    private const val MEETINGS = "meetings/"
    private const val PHOTOS = "photos/"
    private const val AUDIO = "audio/"
    private const val ATTACHMENTS = "attachments/"
    // Banked voiceprint audio: lets restored profiles roll over to a new
    // speaker model on the destination device.
    private const val VOICE_AUDIO = "voiceprint-audio/"

    private const val VOICES = "voice_profiles.json"

    fun export(context: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            addDir(zip, File(context.filesDir, "meetings"), MEETINGS) { it.endsWith(".json") }
            addDir(zip, File(context.filesDir, "photos"), PHOTOS) { true }
            addDir(zip, File(context.filesDir, "audio"), AUDIO) { true }
            addDir(zip, File(context.filesDir, "attachments"), ATTACHMENTS) { true }
            addDir(zip, File(context.filesDir, "voiceprint-audio"), VOICE_AUDIO) { true }
            val voices = File(context.filesDir, VOICES)
            if (voices.isFile) {
                zip.putNextEntry(ZipEntry(VOICES))
                voices.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** [export], AES-256-GCM-encrypted under [passphrase]. */
    fun exportEncrypted(context: Context, out: OutputStream, passphrase: CharArray) {
        export(context, BackupCrypto.encryptingStream(passphrase, out))
    }

    /** True when the stream at [uri]-like [input] starts with the encrypted-backup magic. */
    fun sniffEncrypted(input: InputStream): Boolean {
        val header = ByteArray(BackupCrypto.MAGIC.size)
        var got = 0
        while (got < header.size) {
            val n = input.read(header, got, header.size - got)
            if (n < 0) break
            got += n
        }
        return got == header.size && BackupCrypto.isEncryptedHeader(header)
    }

    /**
     * What a restore actually did.
     *
     * [skipped] is not noise: an entry is skipped when it would not parse, or
     * could not be moved into place. Saying "restored 12" while silently
     * dropping 3 is how a user comes to trust a backup that is not whole.
     */
    data class Restored(val meetings: Int, val skipped: Int)

    /**
     * Restores meetings + photos + audio from a backup, plain or encrypted.
     * Throws [BackupCrypto.WrongPassphraseException] for a bad passphrase, and
     * IllegalStateException when the backup is encrypted but no passphrase was
     * supplied.
     */
    fun import(
        context: Context,
        input: InputStream,
        passphrase: CharArray? = null
    ): Restored {
        val pushback = PushbackInputStream(input, BackupCrypto.MAGIC.size)
        val header = ByteArray(BackupCrypto.MAGIC.size)
        var got = 0
        while (got < header.size) {
            val n = pushback.read(header, got, header.size - got)
            if (n < 0) break
            got += n
        }
        val stream = if (got == header.size && BackupCrypto.isEncryptedHeader(header)) {
            checkNotNull(passphrase) { "This backup is encrypted; a passphrase is required." }
            BackupCrypto.decryptingStream(passphrase, pushback)
        } else {
            pushback.unread(header, 0, got)
            pushback
        }
        return importZip(context, stream)
    }

    private fun importZip(context: Context, input: InputStream): Restored {
        val meetingsDir = File(context.filesDir, "meetings").apply { mkdirs() }
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val voiceAudioDir = File(context.filesDir, "voiceprint-audio").apply { mkdirs() }
        var restored = 0
        var skipped = 0
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    // File(name).name strips any path components, so a crafted
                    // entry like "../../x" cannot escape the target directory.
                    val target = when {
                        name.startsWith(MEETINGS) && name.endsWith(".json") ->
                            File(meetingsDir, File(name).name)
                        name.startsWith(PHOTOS) ->
                            File(photosDir, File(name).name)
                        name.startsWith(AUDIO) ->
                            File(audioDir, File(name).name)
                        name.startsWith(ATTACHMENTS) ->
                            File(attachmentsDir, File(name).name)
                        name.startsWith(VOICE_AUDIO) ->
                            File(voiceAudioDir, File(name).name)
                        name == VOICES ->
                            File(context.filesDir, VOICES)
                        else -> null
                    }
                    if (target != null && isUnder(target.parentFile, target)) {
                        val isMeeting = name.startsWith(MEETINGS)
                        if (extractSafely(zip, target, validateJson = isMeeting)) {
                            if (isMeeting) restored++
                        } else {
                            skipped++
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return Restored(restored, skipped)
    }

    /**
     * Writes one entry aside, then moves it into place — never over the live
     * file directly.
     *
     * Restore is routinely run onto a device that already holds these
     * meetings, and the source is often a SAF/Drive document whose stream can
     * drop, or an archive an interrupted export left half-written. Copying
     * straight into the destination truncated the existing file first, so a
     * failure partway left something that was neither the old meeting nor the
     * new one — invalid JSON, which MeetingStore.list() silently drops. The
     * user saw "restore failed", which sounds harmless, and lost a meeting.
     *
     * [validateJson] additionally parses the staged file before the rename,
     * so a corrupt entry inside an otherwise-readable archive cannot replace
     * a working meeting either. Returns false when nothing was moved.
     */
    private fun extractSafely(input: InputStream, target: File, validateJson: Boolean): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        try {
            part.outputStream().use { input.copyTo(it) }
            if (validateJson) {
                // Parsed, not merely non-empty: a truncated JSON object is
                // perfectly plausible as bytes and useless as a meeting.
                Meeting.fromJson(org.json.JSONObject(part.readText()))
            }
            // Delete-then-rename: renameTo does not replace on every Android
            // filesystem, and a failed rename must not leave the old file gone.
            if (target.exists() && !target.delete()) return false
            if (part.renameTo(target)) return true
            // Rename refused — fall back to a copy, which is not atomic but is
            // the only remaining way to honour the restore.
            part.inputStream().use { src -> target.outputStream().use { src.copyTo(it) } }
            return true
        } catch (_: Throwable) {
            return false
        } finally {
            part.delete()
        }
    }

    private fun addDir(
        zip: ZipOutputStream,
        dir: File,
        prefix: String,
        accept: (String) -> Boolean
    ) {
        val files = dir.listFiles { f -> f.isFile && accept(f.name) } ?: return
        for (file in files) {
            zip.putNextEntry(ZipEntry(prefix + file.name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun isUnder(dir: File?, file: File): Boolean {
        if (dir == null) return false
        return file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
    }
}
