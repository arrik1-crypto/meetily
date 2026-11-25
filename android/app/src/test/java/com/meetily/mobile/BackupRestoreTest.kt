package com.meetily.mobile

import com.meetily.mobile.data.Attachment
import com.meetily.mobile.data.BackupManager
import com.meetily.mobile.data.Meeting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backups: which audio goes in, a file that vanishes mid-export, and what a
 * restore that keeps this phone's copies leaves alone.
 */
class BackupRestoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun meeting(id: String, audio: String?, vararg photos: String): Meeting =
        Meeting(id = id, title = "Weekly sync", createdAtMs = 1_800_000_000_000L).apply {
            audioFile = audio
            this.photos.addAll(photos)
            attachmentsList.add(Attachment("${id}_2_deck.pdf", "deck.pdf"))
        }

    @Test
    fun audio_leavesOutClearVoiceCopiesAndQueuedImports() {
        assertTrue(BackupManager.backsUpAudio("m1.aac"))
        assertTrue(BackupManager.backsUpAudio("m1.m4a.peaks"))
        assertFalse(BackupManager.backsUpAudio("m1.clear.m4a"))
        assertFalse(BackupManager.backsUpAudio("m1.clear.m4a.part"))
        // Staged by ImportQueue while the import waits: its job is not backed up.
        assertFalse(BackupManager.backsUpAudio("queued-0b6e4c1a-1f2d-4c3b-9a8e-7d6c5b4a3f21.m4a"))
        // A meeting id that merely contains the word is not staged.
        assertTrue(BackupManager.backsUpAudio("m1-queued-copy.aac"))
    }

    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun export_leavesOutAFileGoneSinceTheListing() {
        val dir = folder.newFolder("audio")
        val kept = File(dir, "a.aac").apply { writeText("first") }
        val gone = File(dir, "b.aac").apply { writeText("second") }
        val listed = listOf(kept, gone)
        // Removed (Remove audio, a clean-up, a queued import renaming its
        // staged file) after the folder was listed.
        assertTrue(gone.delete())
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for (file in listed) BackupManager.addFile(zip, file, "audio/" + file.name)
        }
        // No empty entry for it, and the export went on.
        assertEquals(mapOf("audio/a.aac" to "first"), entries(bytes.toByteArray()))
    }

    @Test
    fun keptMeeting_leavesTheFilesBothCopiesNameAlone() {
        val kept = BackupManager.PhoneCopies()
        assertEquals(0, kept.count)
        assertFalse(kept.keepsAudio("m1.aac"))
        // The phone's copy took a new photo since; the backup's copy still
        // names one deleted here since.
        kept.kept(
            phoneCopy = meeting("m1", "m1.aac", "m1_1.jpg", "m1_3.jpg"),
            backupCopy = meeting("m1", "m1.aac", "m1_1.jpg", "m1_2.jpg")
        )
        assertEquals(1, kept.count)
        // The phone's recording, with its waveform and clear-voice copy.
        assertTrue(kept.keepsAudio("m1.aac"))
        assertTrue(kept.keepsAudio("m1.aac.peaks"))
        assertTrue(kept.keepsAudio("m1.clear.m4a"))
        assertTrue(kept.keepsPhoto("m1_1.jpg"))
        assertTrue(kept.keepsPhoto("m1_3.jpg"))
        // Restored, it would be a photo no meeting refers to.
        assertTrue(kept.keepsPhoto("m1_2.jpg"))
        assertTrue(kept.keepsAttachment("m1_2_deck.pdf"))
        // Another meeting's files are restored as usual.
        assertFalse(kept.keepsAudio("m2.aac"))
        assertFalse(kept.keepsPhoto("m2_1.jpg"))
    }

    @Test
    fun keptMeeting_countsEvenWhenACopyCannotBeRead() {
        val kept = BackupManager.PhoneCopies()
        kept.kept(phoneCopy = null, backupCopy = meeting("m1", "m1.aac"))
        kept.kept(phoneCopy = meeting("m2", null), backupCopy = null)
        kept.kept(phoneCopy = null, backupCopy = null)
        assertEquals(3, kept.count)
        assertTrue(kept.keepsAudio("m1.aac"))
        assertTrue(kept.keepsAttachment("m2_2_deck.pdf"))
        // A name that is not a plain name is never something to keep.
        kept.kept(phoneCopy = meeting("m3", "../voice_profiles.json"), backupCopy = null)
        assertFalse(kept.keepsAudio("../voice_profiles.json"))
    }

    @Test
    fun restoreResult_defaultsToNothingKept() {
        val result = BackupManager.Restored(4, 0)
        assertEquals(0, result.keptOnPhone)
        assertEquals(0, result.leftInTrash)
    }
}
