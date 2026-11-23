package com.meetily.mobile

import com.meetily.mobile.data.Attachment
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.Retention
import com.meetily.mobile.data.StorageUsage
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.Trash
import com.meetily.mobile.data.WordStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Recently deleted: the trash file names, when a deleted meeting is erased
 * (30 days, whatever the clock did), what backups and restores leave alone,
 * and the trash directory itself.
 */
class TrashTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val day = Trash.DAY_MS
    private val now = 1_800_000_000_000L
    private val id = "3f2a9c1e-77b4-4c1d-9e0a-5b6c7d8e9f01"

    private fun meeting(meetingId: String = id): Meeting =
        Meeting(id = meetingId, title = "Weekly sync", createdAtMs = now - 100 * day).apply {
            audioFile = "$meetingId.aac"
            photos.add("${meetingId}_1.jpg")
            attachmentsList.add(Attachment("${meetingId}_2_deck.pdf", "deck.pdf"))
            starred = true
            segments.add(
                TranscriptSegment(
                    timestampMs = 1L, text = "hello there", audioMs = 0L,
                    words = listOf(WordStamp(0L, "hello"), WordStamp(300L, "there"))
                )
            )
        }

    // --- Names -------------------------------------------------------------

    @Test
    fun fileName_roundTripsThroughParse_evenWithDashesInTheId() {
        val name = Trash.fileName(id, now)!!
        assertEquals("$now-$id.json", name)
        assertEquals(Trash.Entry(id, now), Trash.parse(name))
        assertEquals(name, Trash.Entry(id, now).fileName)
        assertEquals(Trash.Entry("m1", 0L), Trash.parse("0-m1.json"))
    }

    @Test
    fun fileName_refusesWhatCouldNotBeAPlainEntry() {
        assertNull(Trash.fileName("../voice_profiles", now))
        assertNull(Trash.fileName("", now))
        assertNull(Trash.fileName("m1", -1L))
    }

    @Test
    fun parse_ignoresAnythingThisDidNotWrite() {
        assertNull(Trash.parse("$id.json"))
        assertNull(Trash.parse("notes.txt"))
        assertNull(Trash.parse("-m1.json"))
        assertNull(Trash.parse("12a-m1.json"))
        assertNull(Trash.parse("123-.json"))
        assertNull(Trash.parse("123-m1.json.tmp"))
        assertNull(Trash.parse("12345678901234567890-m1.json"))
        assertNull(Trash.parse("99999999999999999999-m1.json"))
    }

    // --- When it is erased ---------------------------------------------------

    @Test
    fun dueExactlyThirtyDaysAfterDeletion() {
        val deleted = now - 30 * day
        val saved = deleted - 3_600_000L
        assertTrue(Trash.isDue(deleted, saved, now))
        assertFalse(Trash.isDue(deleted + 1, saved, now))
        assertEquals(0, Trash.daysLeft(deleted, saved, now))
        assertEquals(1, Trash.daysLeft(deleted + 1, saved, now))
    }

    @Test
    fun daysLeft_countsDownFromThirty() {
        assertEquals(30, Trash.daysLeft(now, now, now))
        assertEquals(30, Trash.daysLeft(now - 60_000L, now - 60_000L, now))
        assertEquals(29, Trash.daysLeft(now - day, now - day, now))
        assertEquals(1, Trash.daysLeft(now - 29 * day - day / 2, 0L, now))
        assertEquals(0, Trash.daysLeft(now - 400 * day, 0L, now))
    }

    @Test
    fun aDateInTheFuture_countsFromNow_andIsNotWrittenBack() {
        // Deleted while the clock was ten days ahead; it has been put back.
        val deleted = now + 10 * day
        assertFalse(Trash.isDue(deleted, deleted, now))
        assertEquals(30, Trash.daysLeft(deleted, deleted, now))
        assertEquals(now, Trash.countsFrom(deleted, deleted, now))
        assertNull(Trash.redated(deleted, now))
        // The stored date is what counts once the clock reaches it.
        assertEquals(1, Trash.daysLeft(deleted, deleted, deleted + 29 * day))
        assertTrue(Trash.isDue(deleted, deleted, deleted + 30 * day))
    }

    @Test
    fun theClockGoingBackAfterADeletion_neverErasesEarly() {
        // Deleted today, then the clock goes back a year and forward again.
        val deleted = now
        val backwards = now - 365 * day
        assertFalse(Trash.isDue(deleted, deleted, backwards))
        assertEquals(30, Trash.daysLeft(deleted, deleted, backwards))
        assertFalse(Trash.isDue(deleted, deleted, now + day))
    }

    @Test
    fun aClockBehindAtDeletion_countsFromTheLastSave() {
        // Saved today; deleted under a clock running 200 days behind.
        val saved = now
        val deleted = now - 200 * day
        assertTrue(deleted >= Trash.PLAUSIBLE_FROM_MS)
        assertEquals(saved, Trash.countsFrom(deleted, saved, now + day))
        assertFalse(Trash.isDue(deleted, saved, now + day))
        assertTrue(Trash.isDue(deleted, saved, now + 30 * day))
        // A file time from a wrong clock is no floor.
        assertEquals(deleted, Trash.countsFrom(deleted, 5L, now))
    }

    @Test
    fun anImplausibleDate_isUndatedUntilTheClockIsRight() {
        // A phone that lost its time: the deletion is dated 1970.
        val deleted = 5_000L
        assertEquals(now, Trash.countsFrom(deleted, now - 400 * day, now))
        assertFalse(Trash.isDue(deleted, 0L, now))
        assertEquals(30, Trash.daysLeft(deleted, 0L, now))
        assertEquals(now, Trash.redated(deleted, now))
        // Still wrong now: nothing is written yet.
        assertNull(Trash.redated(deleted, 9_000L))
        // A plausible date is never rewritten.
        assertNull(Trash.redated(Trash.PLAUSIBLE_FROM_MS, now))
    }

    @Test
    fun newestPerId_keepsOneCopyPerMeeting() {
        val older = Trash.Entry("a", now - 2 * day)
        val newer = Trash.Entry("a", now - day)
        val other = Trash.Entry("b", now - 3 * day)
        val (kept, stale) = Trash.newestPerId(listOf(older, other, newer))
        assertEquals(listOf(newer, other), kept)
        assertEquals(listOf(older), stale)
    }

    @Test
    fun cleanUpWouldRetake_onlyAnOldUnstarredMeetingUnderTheMeetingsRule() {
        val rule = Retention.Rule(Retention.Mode.MEETINGS, 90)
        val old = now - 100 * day
        assertTrue(Trash.cleanUpWouldRetake(rule, old, starred = false, nowMs = now))
        assertFalse(Trash.cleanUpWouldRetake(rule, old, starred = true, nowMs = now))
        assertFalse(Trash.cleanUpWouldRetake(rule, now - 10 * day, starred = false, nowMs = now))
        assertFalse(Trash.cleanUpWouldRetake(rule, 0L, starred = false, nowMs = now))
        assertFalse(Trash.cleanUpWouldRetake(Retention.OFF, old, starred = false, nowMs = now))
        assertFalse(
            Trash.cleanUpWouldRetake(Retention.Rule(Retention.Mode.AUDIO, 90), old, false, now)
        )
    }

    // --- Backups -------------------------------------------------------------

    @Test
    fun heldBy_namesEveryFileTheMeetingKeepsOnDisk() {
        val m = meeting().apply { clearVoiceFile = "$id.clear.m4a" }
        val held = Trash.heldBy(m)
        assertEquals(setOf("$id.aac", "$id.aac.peaks", "$id.clear.m4a"), held.audio)
        assertEquals(setOf("${id}_1.jpg"), held.photos)
        assertEquals(setOf("${id}_2_deck.pdf"), held.attachments)
    }

    @Test
    fun heldBy_leavesOutNamesThatAreNotPlain() {
        val m = meeting().apply {
            audioFile = "../voice_profiles.json"
            photos.add("../x.jpg")
            attachmentsList.add(Attachment("..", "x"))
        }
        val held = Trash.heldBy(m)
        assertTrue(held.audio.isEmpty())
        assertEquals(setOf("${id}_1.jpg"), held.photos)
        assertEquals(setOf("${id}_2_deck.pdf"), held.attachments)
    }

    @Test
    fun restoreGuard_leavesTrashedMeetingsAndTheirFilesAlone() {
        val guard = Trash.RestoreGuard(setOf(id), Trash.heldBy(meeting()))
        assertTrue(guard.isTrashed("$id.json"))
        assertFalse(guard.isTrashed("other.json"))
        assertFalse(guard.isTrashed(id))
        assertTrue(guard.keepsAudio("$id.aac"))
        assertTrue(guard.keepsPhoto("${id}_1.jpg"))
        assertTrue(guard.keepsAttachment("${id}_2_deck.pdf"))
        assertFalse(guard.keepsPhoto("other_1.jpg"))
        // The backup's copy names a photo the deleted one no longer had.
        guard.skipped(meeting().apply { photos.add("${id}_9.jpg") })
        guard.skipped(null)
        assertEquals(2, guard.leftInTrash)
        assertTrue(guard.keepsPhoto("${id}_9.jpg"))
    }

    @Test
    fun storage_countsTheTrashWithTranscripts() {
        assertEquals(
            StorageUsage.Area.TRANSCRIPTS,
            StorageUsage.areaFor(StorageUsage.Root.FILES, "trash/$now-$id.json")
        )
    }

    // --- The directory -------------------------------------------------------

    private fun put(dir: File, entry: Trash.Entry, m: Meeting = meeting(entry.id)): File =
        File(dir, entry.fileName).apply { writeText(m.toJson().toString()) }

    @Test
    fun folder_listsOnlyEntries_oneCopyPerMeeting() {
        val dir = folder.newFolder("trash")
        val trash = Trash.Folder(dir)
        assertFalse(trash.hasAny())
        File(dir, "notes.txt").writeText("x")
        File(dir, "$id.json").writeText("{}")
        assertFalse(trash.hasAny())
        val a = Trash.Entry(id, now - day)
        val b = Trash.Entry("m2", now)
        val stale = Trash.Entry(id, now - 5 * day)
        put(dir, a)
        put(dir, b)
        put(dir, stale)
        assertTrue(trash.hasAny())
        assertEquals(listOf(b, a), trash.entries())
        assertEquals(listOf(stale), trash.stale())
        assertEquals(setOf(a, stale), trash.entriesFor(id).toSet())
        assertTrue(Trash.Folder(File(dir, "missing")).all().isEmpty())
    }

    @Test
    fun folder_readKeepsTheMeetingAsItWas() {
        val dir = folder.newFolder("trash")
        val trash = Trash.Folder(dir)
        val entry = Trash.Entry(id, now)
        val original = meeting()
        put(dir, entry, original)
        // Moving a meeting here changes nothing in it.
        val whole = trash.read(entry, null)!!
        assertEquals(original.toJson().toString(), whole.toJson().toString())
        assertTrue(whole.loadedPartially)
        // The default read is the header and the file names, without the transcript.
        val light = trash.read(entry)!!
        assertEquals("Weekly sync", light.title)
        assertEquals("$id.aac", light.audioFile)
        assertEquals(listOf("${id}_1.jpg"), light.photos)
        assertTrue(light.starred)
        assertTrue(light.segments.isEmpty())
        assertEquals(Trash.heldBy(original), Trash.heldBy(light))
    }

    @Test
    fun folder_readOfAMissingOrDamagedFileIsNull() {
        val dir = folder.newFolder("trash")
        val trash = Trash.Folder(dir)
        assertNull(trash.read(Trash.Entry(id, now)))
        val broken = Trash.Entry("m3", now)
        File(dir, broken.fileName).writeText("{\"id\":\"m3\",\"title\":")
        assertNull(trash.read(broken))
    }

    @Test
    fun folder_redateMovesTheFileToItsNewName() {
        val dir = folder.newFolder("trash")
        val trash = Trash.Folder(dir)
        val undated = Trash.Entry(id, 5_000L)
        put(dir, undated)
        val dated = trash.redate(undated, now)
        assertNotNull(dated)
        assertEquals(Trash.Entry(id, now), dated)
        assertFalse(File(dir, undated.fileName).exists())
        assertEquals(listOf(Trash.Entry(id, now)), trash.entries())
        assertEquals("Weekly sync", trash.read(dated!!)!!.title)
    }
}
