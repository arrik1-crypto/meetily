package com.meetily.mobile

import com.meetily.mobile.data.BackupManager
import com.meetily.mobile.data.ClearVoice
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.data.StorageUsage
import com.meetily.mobile.data.StorageUsage.Area
import com.meetily.mobile.data.StorageUsage.Root
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clear-voice copy's bookkeeping: its name beside the recording, when a
 * meeting may play it, how it is stored on the meeting and survives a
 * three-way merge, what it counts for in storage, and that a backup cannot
 * use the field to reach another file.
 */
class ClearVoiceTest {

    private fun meeting(audio: String? = "m1.aac", copy: String? = null) = Meeting(
        id = "m1",
        title = "Standup",
        createdAtMs = 1L,
        audioFile = audio,
        clearVoiceFile = copy
    )

    @Test
    fun theCopyIsNamedAfterItsRecording() {
        assertEquals("m1.clear.m4a", ClearVoice.copyNameFor("m1.aac"))
        assertEquals("m1.clear.m4a", ClearVoice.copyNameFor("m1.m4a"))
        assertEquals("m1.clear.m4a", ClearVoice.copyNameFor("m1"))
        // An imported file keeps its own extension (up to five letters, so
        // even "clear"); the copy is never the recording's own name.
        assertEquals("m1.clear.m4a", ClearVoice.copyNameFor("m1.clear"))
        for (audio in listOf("m1.aac", "m1.clear", "m1.m4a", "m1")) {
            assertFalse(audio, ClearVoice.copyNameFor(audio) == audio)
        }
        assertEquals("m1.clear.m4a.part", ClearVoice.partNameFor("m1.aac"))
    }

    @Test
    fun copiesAndPartFilesAreRecognisedAndRecordingsAreNot() {
        assertTrue(ClearVoice.isCopyName("m1.clear.m4a"))
        assertTrue(ClearVoice.isCopyName("m1.clear.m4a.part"))
        assertFalse(ClearVoice.isCopyName("m1.aac"))
        assertFalse(ClearVoice.isCopyName("m1.m4a"))
        assertFalse(ClearVoice.isCopyName("m1.clear"))
        assertFalse(ClearVoice.isCopyName("m1.aac.peaks"))
        for (audio in listOf("a.aac", "b.m4a", "c.wav", "d")) {
            assertFalse(ClearVoice.isCopyName(audio))
            assertTrue(ClearVoice.isCopyName(ClearVoice.copyNameFor(audio)))
            assertTrue(ClearVoice.isCopyName(ClearVoice.partNameFor(audio)))
        }
    }

    @Test
    fun onlyACopyOfTheCurrentRecordingIsPlayed() {
        assertEquals("m1.clear.m4a", ClearVoice.copyOf("m1.aac", "m1.clear.m4a"))
        // Made from a recording the meeting no longer has.
        assertNull(ClearVoice.copyOf("m1-import.mp3", "m1.clear.m4a"))
        // The audio was removed, or no copy was made.
        assertNull(ClearVoice.copyOf(null, "m1.clear.m4a"))
        assertNull(ClearVoice.copyOf("m1.aac", null))
        assertNull(ClearVoice.copyOf("m1.aac", ""))
        // A name this rule would not produce, as a crafted backup might carry.
        assertNull(ClearVoice.copyOf("m1.aac", "../voice_profiles.json"))
        assertNull(ClearVoice.copyOf("m1.aac", "m1.aac"))
    }

    @Test
    fun theEstimateIsTheBitrateWithALittleForTheIndex() {
        val hour = 60 * 60_000L
        val bytes = ClearVoice.estimatedBytes(hour)
        // 64 kbps for an hour is 28.8 MB; the index adds 2 %.
        assertEquals(28_800_000L + 576_000L, bytes)
        assertEquals(0L, ClearVoice.estimatedBytes(0L))
        assertEquals(0L, ClearVoice.estimatedBytes(-5L))
        assertFalse(ClearVoice.isLong(29 * 60_000L))
        assertTrue(ClearVoice.isLong(30 * 60_000L))
        assertFalse(ClearVoice.isLong(0L))
    }

    @Test
    fun theFieldRoundTripsAndOldMeetingsHaveNone() {
        val json = meeting(copy = "m1.clear.m4a").toJson()
        assertEquals("m1.clear.m4a", json.getString("clearVoiceFile"))
        assertEquals("m1.clear.m4a", Meeting.fromJson(JSONObject(json.toString())).clearVoiceFile)
        // Saved before the field existed.
        val old = JSONObject().put("id", "m1").put("title", "Old").put("createdAtMs", 5L)
            .put("audioFile", "m1.aac")
        assertNull(Meeting.fromJson(old).clearVoiceFile)
        // Blank reads as none, and none is not written.
        assertNull(Meeting.fromJson(JSONObject(old.toString()).put("clearVoiceFile", "")).clearVoiceFile)
        assertFalse(meeting().toJson().has("clearVoiceFile"))
        // The same meeting either way.
        assertEquals(meeting(copy = "m1.clear.m4a"), Meeting.fromJson(json))
    }

    @Test
    fun theFieldIsWrittenAfterTheHeaderKeys() {
        // MeetingStore.loadStaged reads the header from the start of the file.
        val text = meeting(copy = "m1.clear.m4a").toJson().toString()
        val at = text.indexOf("\"clearVoiceFile\"")
        assertTrue(at > 0)
        for (key in listOf("\"id\"", "\"title\"", "\"createdAtMs\"")) {
            assertTrue("$key before the copy", text.indexOf(key) in 0 until at)
        }
    }

    @Test
    fun aCopyMadeInTheBackgroundReachesAScreenThatDidNotTouchIt() {
        val base = meeting()
        val ours = MeetingMerge.snapshot(base).also { it.title = "Renamed" }
        val disk = meeting(copy = "m1.clear.m4a")
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("m1.clear.m4a", merged.clearVoiceFile)
        assertEquals("Renamed", merged.title)
    }

    @Test
    fun aCopyDeletedOnTheScreenStaysDeleted() {
        val base = meeting(copy = "m1.clear.m4a")
        val ours = MeetingMerge.snapshot(base).also { it.clearVoiceFile = null }
        val disk = meeting(copy = "m1.clear.m4a").also { it.starred = true }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertNull(merged.clearVoiceFile)
        assertTrue(merged.starred)
    }

    @Test
    fun audioRemovedElsewhereTakesTheCopyWithItIntoTheScreen() {
        // Remove audio (Storage screen, multi-select, clean-up) clears both.
        val base = meeting(copy = "m1.clear.m4a")
        val ours = MeetingMerge.snapshot(base)
        val disk = meeting(audio = null, copy = null)
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertNull(merged.audioFile)
        assertNull(merged.clearVoiceFile)
    }

    @Test
    fun theSnapshotCarriesTheCopy() {
        assertEquals("m1.clear.m4a", MeetingMerge.snapshot(meeting(copy = "m1.clear.m4a")).clearVoiceFile)
    }

    @Test
    fun copiesCountAsMeetingAudio() {
        assertEquals(Area.AUDIO, StorageUsage.areaFor(Root.FILES, "audio/m1.clear.m4a"))
        assertEquals(Area.AUDIO, StorageUsage.areaFor(Root.FILES, "audio/m1.clear.m4a.part"))
        assertEquals(Area.AUDIO, StorageUsage.areaFor(Root.FILES, "audio/m1.aac"))
        val totals = StorageUsage.totals(
            listOf(
                StorageUsage.Entry(Root.FILES, "audio/m1.aac", 100L),
                StorageUsage.Entry(Root.FILES, "audio/m1.clear.m4a", 40L),
                StorageUsage.Entry(Root.FILES, "audio/m1.aac.peaks", 5L)
            )
        )
        assertEquals(140L, totals[Area.AUDIO])
        assertEquals(5L, totals[Area.CACHES])
    }

    @Test
    fun theLargestListCountsEachCopyWithItsRecording() {
        val a = StorageUsage.MeetingAudio("a", "A", audioBytes = 100L, hasTranscript = true)
        val b = StorageUsage.MeetingAudio("b", "B", audioBytes = 80L, hasTranscript = true, clearVoiceBytes = 40L)
        // A copy alone, its recording gone, is not offered for removal.
        val c = StorageUsage.MeetingAudio("c", "C", audioBytes = 0L, hasTranscript = true, clearVoiceBytes = 90L)
        assertEquals(120L, b.removableBytes)
        assertEquals(100L, a.removableBytes)
        assertEquals(listOf("b", "a"), StorageUsage.largestByAudio(listOf(a, b, c)).map { it.id })
    }

    @Test
    fun aBackupCannotPointTheCopyOutsideTheAudioDirectory() {
        assertTrue(BackupManager.isRestorable(meeting(copy = "m1.clear.m4a"), "m1.json"))
        assertTrue(BackupManager.isRestorable(meeting(copy = null), "m1.json"))
        assertFalse(BackupManager.isRestorable(meeting(copy = "../voice_profiles.json"), "m1.json"))
        assertFalse(BackupManager.isRestorable(meeting(copy = "sub/m1.clear.m4a"), "m1.json"))
    }

    @Test
    fun aCheckOnTheCopyIsAskedForAndKeptAsSuch() {
        val plain = JobQueue.Job(JobQueue.KIND_CHECK, "m1", "base")
        assertFalse(plain.clearVoice)
        val copy = plain.copy(clearVoice = true)
        // A request for the copy replaces one for the recording, not beside it.
        val queue = JobQueue.add(JobQueue.add(emptyList(), plain), copy)
        assertEquals(1, queue.size)
        assertTrue(queue.single().clearVoice)
    }
}
