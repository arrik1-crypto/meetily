package com.meetily.mobile.data

import android.content.Context
import com.meetily.mobile.security.BackupCrypto
import com.meetily.mobile.whisper.VoiceProfileStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-library backup: a single .zip containing every meeting JSON and every
 * photo. Import merges into the current library. A meeting that is on this
 * phone too is replaced by the backup's copy only when the user said so
 * ([import]'s replaceExisting, asked with the count from [countOnPhone]);
 * otherwise the phone's copy stays as it is, with its files ([PhoneCopies]).
 * Settings — which can hold LLM API keys — are deliberately excluded.
 *
 * Recently deleted (MeetingTrash) is neither backed up nor changed by a
 * restore: its meetings and the files they name are left out of a backup,
 * and a backup's copy of a meeting that is there now is not restored, so a
 * restore never brings back or overwrites something the user deleted.
 * They can restore it from Recently deleted ([Restored.leftInTrash]).
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

    /**
     * Writes the backup to [out], which the caller closes.
     *
     * The archive is finished (closed) only once everything is in it.
     * Closing writes the zip's central directory and, in an encrypted
     * backup, the frame marked last, which is what makes a file read as a
     * whole backup. An export that fails part-way therefore throws without
     * closing it: an encrypted file then fails to restore as truncated, and
     * the caller deletes what was written (SettingsActivity.writeBackup).
     */
    fun export(context: Context, out: OutputStream) {
        // Files only deleted meetings name (their own JSON is not in
        // filesDir/meetings at all).
        val deleted = MeetingTrash(context).held()
        // Buffered: the deflater hands its output on in 512-byte pieces, and
        // unbuffered each one was its own write into the document provider.
        val zip = ZipOutputStream(BufferedOutputStream(out, IO_BUFFER))
        addDir(zip, File(context.filesDir, "meetings"), MEETINGS) { it.endsWith(".json") }
        // Audio and photos are already compressed; deflating them again
        // costs a lot of CPU for no size. Still DEFLATED entries (just
        // stored-level), so readers need nothing new and nothing has to
        // be read twice to precompute a CRC.
        zip.setLevel(Deflater.NO_COMPRESSION)
        addDir(zip, File(context.filesDir, "photos"), PHOTOS) { it !in deleted.photos }
        addDir(zip, File(context.filesDir, "audio"), AUDIO) {
            backsUpAudio(it) && it !in deleted.audio
        }
        addDir(zip, File(context.filesDir, "attachments"), ATTACHMENTS) { it !in deleted.attachments }
        addDir(zip, File(context.filesDir, "voiceprint-audio"), VOICE_AUDIO) { true }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        val voices = File(context.filesDir, VOICES)
        if (voices.isFile) addFile(zip, voices, VOICES)
        zip.close()
    }

    /**
     * Whether the audio folder's file [name] goes into a backup.
     *
     * Not the clear-voice copies (ClearVoice): each is made again from its
     * recording, which is in the backup, and would add about 29 MB an hour
     * to it. A restored meeting that names one plays its recording until
     * the copy is made again.
     *
     * Not an import waiting in the queue, staged as "queued-<id>.<ext>"
     * (ImportQueue): the job that names it is not in the backup, so restored
     * it would be audio nothing refers to, and the import renames it away
     * when it starts, maybe while a backup is reading the folder.
     */
    internal fun backsUpAudio(name: String): Boolean =
        !ClearVoice.isCopyName(name) && !name.startsWith(STAGED_IMPORT_PREFIX)

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
    data class Restored(
        val meetings: Int,
        val skipped: Int,
        /**
         * Meetings of the backup that are in Recently deleted here, and were
         * left there with their files rather than restored over.
         */
        val leftInTrash: Int = 0,
        /**
         * Meetings of the backup that are on this phone too, and were kept
         * as they are here with their files (no replaceExisting).
         */
        val keptOnPhone: Int = 0
    )

    /**
     * Restores meetings + photos + audio from a backup, plain or encrypted.
     * Throws [BackupCrypto.WrongPassphraseException] for a bad passphrase, and
     * IllegalStateException when the backup is encrypted but no passphrase was
     * supplied.
     *
     * A meeting that is on this phone too is replaced by the backup's copy,
     * with the files that copy names, only when [replaceExisting] is true.
     * Otherwise the phone's copy and its files are left as they are: edits
     * made since the backup (notes, summaries, ticks, renames, transcript
     * fixes) would be lost for good, and a restore is often run only to get
     * back one meeting. The user chooses (SettingsActivity).
     */
    fun import(
        context: Context,
        input: InputStream,
        passphrase: CharArray? = null,
        replaceExisting: Boolean = false
    ): Restored = importZip(context, archive(input, passphrase), replaceExisting)

    /**
     * How many of the backup's meetings are on this phone now: what a
     * restore with replaceExisting would write over. Reads the meeting
     * entries at the start of the archive (a backup writes its meetings
     * first) by name only, and stops at the first other entry. Throws as
     * [import] does, so a wrong passphrase shows before anything is asked.
     */
    fun countOnPhone(context: Context, input: InputStream, passphrase: CharArray? = null): Int {
        val meetingsDir = File(context.filesDir, "meetings")
        var count = 0
        ZipInputStream(archive(input, passphrase)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null && entry.name.startsWith(MEETINGS)) {
                if (!entry.isDirectory && isMeetingEntry(entry.name) &&
                    phoneCopy(meetingsDir, File(entry.name).name) != null
                ) {
                    count++
                }
                entry = zip.nextEntry
            }
        }
        return count
    }

    private fun isMeetingEntry(name: String): Boolean = name.startsWith(MEETINGS) && name.endsWith(".json")

    /** This phone's file for the backup's meeting file [plain], or null when there is none. */
    private fun phoneCopy(meetingsDir: File, plain: String): File? =
        if (SafeFiles.isPlainName(plain)) File(meetingsDir, plain).takeIf { it.isFile } else null

    /** The zip inside [input]: [input] itself, or decrypted when it starts with the encrypted magic. */
    private fun archive(input: InputStream, passphrase: CharArray?): InputStream {
        val pushback = PushbackInputStream(
            BufferedInputStream(input, IO_BUFFER), BackupCrypto.MAGIC.size
        )
        val header = ByteArray(BackupCrypto.MAGIC.size)
        var got = 0
        while (got < header.size) {
            val n = pushback.read(header, got, header.size - got)
            if (n < 0) break
            got += n
        }
        return if (got == header.size && BackupCrypto.isEncryptedHeader(header)) {
            checkNotNull(passphrase) { "This backup is encrypted; a passphrase is required." }
            // The magic decides which reader: archives written before the
            // format was framed are still restorable.
            BackupCrypto.decryptingStream(passphrase, pushback, header)
        } else {
            pushback.unread(header, 0, got)
            pushback
        }
    }

    private fun importZip(context: Context, input: InputStream, replaceExisting: Boolean): Restored {
        val meetingsDir = File(context.filesDir, "meetings").apply { mkdirs() }
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
        // Staged, not extracted over the device's banked audio: see
        // VoiceProfileStore.mergeRestored. Cleared first in case an earlier
        // restore died before its merge.
        val voiceAudioDir = File(context.filesDir, "$VOICE_AUDIO_DIR.restore").apply {
            deleteRecursively()
            mkdirs()
        }
        var restored = 0
        var skipped = 0
        // Recently deleted is left as it is; see the class comment.
        val trash = MeetingTrash(context).restoreGuard()
        // Meetings on this phone too, kept as they are; see [import].
        val kept = PhoneCopies()
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    // File(name).name strips any path components, so a crafted
                    // entry like "../../x" cannot escape the target directory.
                    val plain = File(name).name
                    val onPhone = if (isMeetingEntry(name) && !replaceExisting) {
                        phoneCopy(meetingsDir, plain)
                    } else {
                        null
                    }
                    val target = when {
                        isMeetingEntry(name) && trash.isTrashed(plain) -> {
                            trash.skipped(readMeeting(zip))
                            null
                        }
                        onPhone != null -> {
                            kept.kept(readNames(onPhone), readMeeting(zip))
                            null
                        }
                        name.startsWith(PHOTOS) && (trash.keepsPhoto(plain) || kept.keepsPhoto(plain)) -> null
                        name.startsWith(AUDIO) && (trash.keepsAudio(plain) || kept.keepsAudio(plain)) -> null
                        name.startsWith(ATTACHMENTS) &&
                            (trash.keepsAttachment(plain) || kept.keepsAttachment(plain)) -> null
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
                        // Staged, not written over the live file: see
                        // VoiceProfileStore.mergeRestored.
                        name == VOICES ->
                            File(context.filesDir, "$VOICES.restore")
                        else -> null
                    }
                    if (target != null && isUnder(target.parentFile, target)) {
                        val isMeeting = name.startsWith(MEETINGS)
                        if (extractSafely(zip, target, validateJson = isMeeting)) {
                            if (isMeeting) {
                                restored++
                                // Deleted earlier in this session and now
                                // restored on purpose: it may be saved again.
                                MeetingStore.forgetDeleted(target.name.removeSuffix(".json"))
                            }
                        } else {
                            skipped++
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // Restore used to rename the archive's list straight over the live
        // file, destroying every voiceprint enrolled on THIS phone — and a
        // voiceprint cannot be recovered from anything else on disk. The
        // merge (device wins on a name) lives in the store, under its lock.
        VoiceProfileStore.mergeRestored(
            context, File(context.filesDir, "$VOICES.restore"), voiceAudioDir
        )
        return Restored(restored, skipped, trash.leftInTrash, kept.count)
    }

    /**
     * The meetings a restore without replaceExisting keeps as they are on
     * this phone, and the files they name: neither those the phone's copy
     * names nor those the backup's copy names are written. So a kept
     * meeting's recording, photos and attachments stay the phone's, and
     * nothing lands that no meeting refers to. A backup writes its meetings
     * before their files, so every name is known before its file comes up.
     * Pure.
     */
    internal class PhoneCopies {
        private val audio = HashSet<String>()
        private val photos = HashSet<String>()
        private val attachments = HashSet<String>()

        /** Meetings of the backup kept as they are on this phone. */
        var count = 0
            private set

        /** Records one kept meeting; a copy is null when it could not be read. */
        fun kept(phoneCopy: Meeting?, backupCopy: Meeting?) {
            count++
            for (copy in listOfNotNull(phoneCopy, backupCopy)) {
                val held = Trash.heldBy(copy)
                audio.addAll(held.audio)
                photos.addAll(held.photos)
                attachments.addAll(held.attachments)
            }
        }

        fun keepsAudio(name: String): Boolean = name in audio
        fun keepsPhoto(name: String): Boolean = name in photos
        fun keepsAttachment(name: String): Boolean = name in attachments
    }

    /**
     * The meeting in [file] with only the members naming its files read
     * (Trash.heldBy), never its transcript; null when it does not parse.
     */
    private fun readNames(file: File): Meeting? = try {
        Meeting.fromJson(LightJson.parse(file.readText(), Trash.ENTRY_FIELDS + "id", WORDS))
    } catch (_: Exception) {
        null
    }

    private val WORDS = setOf("words")

    /**
     * The meeting in the zip entry [zip] is at, for a backup's copy that is
     * not restored (only the files it names are read). Null when it does
     * not parse.
     */
    private fun readMeeting(zip: InputStream): Meeting? = try {
        Meeting.fromJson(org.json.JSONObject(zip.readBytes().toString(Charsets.UTF_8)))
    } catch (_: Exception) {
        null
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
                val meeting = Meeting.fromJson(org.json.JSONObject(part.readText()))
                if (!isRestorable(meeting, target.name)) return false
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

    /**
     * Whether a parsed meeting may be restored as [fileName].
     *
     * Entry names are already reduced to plain names above, but the names
     * INSIDE the JSON are not: every store later joins the id, audio file,
     * photos and attachments onto its directory, so "../voice_profiles.json"
     * in a crafted backup would make deleting that meeting delete the
     * voiceprints, and an id like that would make saving it overwrite them.
     * The id must also match the file it arrives in, or the first save would
     * write a second copy of the meeting under another name. A backup this
     * app wrote always passes; one that fails is counted as skipped.
     */
    internal fun isRestorable(meeting: Meeting, fileName: String): Boolean =
        SafeFiles.isPlainName(fileName) &&
            fileName == "${meeting.id}.json" &&
            (meeting.audioFile == null || SafeFiles.isPlainName(meeting.audioFile)) &&
            (meeting.clearVoiceFile == null || SafeFiles.isPlainName(meeting.clearVoiceFile)) &&
            meeting.photos.all { SafeFiles.isPlainName(it) } &&
            meeting.attachmentsList.all { SafeFiles.isPlainName(it.file) }

    private fun addDir(
        zip: ZipOutputStream,
        dir: File,
        prefix: String,
        accept: (String) -> Boolean
    ) {
        val files = dir.listFiles { f -> f.isFile && accept(f.name) } ?: return
        for (file in files) addFile(zip, file, prefix + file.name)
    }

    /**
     * [file] as entry [name]. Opened before its entry is started, so a file
     * deleted or renamed since its folder was listed is left out instead of
     * failing the whole backup: audio can be removed (by hand or by the
     * clean-up), a passage removal swaps a recording, and a queued import
     * takes its staged file, all while a long export runs. Once open, the
     * file is read whole even if it is deleted meanwhile.
     */
    internal fun addFile(zip: ZipOutputStream, file: File, name: String) {
        val input = try {
            file.inputStream()
        } catch (_: FileNotFoundException) {
            return
        }
        input.use {
            zip.putNextEntry(ZipEntry(name))
            it.copyTo(zip)
            zip.closeEntry()
        }
    }

    private const val IO_BUFFER = 64 * 1024

    /** How ImportQueue.stage names an import's audio while it waits in the queue. */
    private const val STAGED_IMPORT_PREFIX = "queued-"

    private const val VOICE_AUDIO_DIR = "voiceprint-audio"

    private fun isUnder(dir: File?, file: File): Boolean {
        if (dir == null) return false
        return file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
    }
}
