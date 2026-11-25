package com.meetily.mobile

import com.meetily.mobile.data.BackupManager
import com.meetily.mobile.data.ClearVoice
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.LightJson
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.data.MeetingStore
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
 * three-way merge, what it counts for in storage, that a backup cannot
 * use the field to reach another file, and that the codec's delay is taken
 * off its start so it decodes on the recording's timeline.
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
    fun theHeaderReadSkipsTheFieldWhereverItIs() {
        // MeetingStore.loadStaged's quick header read keeps only the header
        // keys, in whatever order the file has them: the copy's name never
        // reaches the display-only copy, whether it comes before them or
        // after. The full read has it either way.
        val written = meeting(copy = "m1.clear.m4a").toJson().toString()
        val copyFirst = "{\"clearVoiceFile\":\"m1.clear.m4a\",\"audioFile\":\"m1.aac\"," +
            "\"id\":\"m1\",\"title\":\"Standup\",\"createdAtMs\":1}"
        for (text in listOf(written, copyFirst)) {
            val parsed = LightJson.parseLeading(text, MeetingStore.HEADER_FIELDS)
            assertEquals(MeetingStore.HEADER_FIELDS, parsed.keySet())
            val header = Meeting.fromJson(parsed)
            assertEquals("Standup", header.title)
            assertEquals(1L, header.createdAtMs)
            assertNull(header.clearVoiceFile)
            assertEquals("m1.clear.m4a", Meeting.fromJson(JSONObject(text)).clearVoiceFile)
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

    @Test
    fun aCheckOnTheCopyStaysOnItThroughTheQueueAndAnUnplug() {
        // Queued on the copy and part-way through (TranscriptResume), beside
        // a plain check: both come back from the stored queue as they were.
        val copy = JobQueue.Job(
            JobQueue.KIND_CHECK, "m1", "base", queuedAtMs = 1L,
            resumeFromMs = 600_000L, clearVoice = true
        )
        val plain = JobQueue.Job(JobQueue.KIND_CHECK, "m2", "base", queuedAtMs = 2L)
        assertEquals(listOf(copy, plain), JobQueue.decode(JobQueue.encode(listOf(copy, plain))))
        // A row queued before the field reads as the recording.
        assertFalse(JobQueue.decode("""[{"kind":"check","meetingId":"m1"}]""").single().clearVoice)
        // The charger stopped it: it carries its kept draft on from the copy
        // that made it, not from the recording.
        val requeued = JobQueue.chargingRequeue(
            "m1", "base", 3L, firstTranscript = false, language = "",
            resumeFromMs = 600_000L, clearVoice = true
        )
        assertTrue(requeued.clearVoice)
        assertTrue(requeued.required)
        assertEquals(600_000L, requeued.resumeFromMs)
        // One on the recording stays on the recording.
        assertFalse(JobQueue.chargingRequeue("m1", "base", 3L, false, "", 600_000L).clearVoice)
    }

    @Test
    fun theDelayIsHeldBackFromTheStartAcrossWrites() {
        val trim = ClearVoice.DelayTrim(1500)
        assertEquals(1024, trim.skip(1024))
        assertEquals(476, trim.skip(1024))
        assertEquals(0, trim.skip(1024))
        assertEquals(0, trim.skip(0))
        assertEquals(1500, trim.tail)
        // No delay measured as zero: nothing is held back or added.
        val none = ClearVoice.DelayTrim(0)
        assertEquals(0, none.skip(1024))
        assertEquals(0, none.tail)
    }

    /**
     * The copy as a codec that delays everything by [delay] samples gives it
     * back: its priming, then what it was fed. One that does not flush at the
     * end gives back only as many samples as it was fed.
     */
    private fun roundTrip(input: FloatArray, chunk: Int, delay: Int, flushes: Boolean): FloatArray {
        val trim = ClearVoice.DelayTrim(delay)
        val fed = ArrayList<Float>()
        var at = 0
        while (at < input.size) {
            val n = minOf(chunk, input.size - at)
            val skip = trim.skip(n)
            for (i in at + skip until at + n) fed.add(input[i])
            at += n
        }
        repeat(trim.tail) { fed.add(0f) }
        val decoded = FloatArray(delay) + fed.toFloatArray()
        return if (flushes) decoded else decoded.copyOf(fed.size)
    }

    @Test
    fun theCopyDecodesOnTheRecordingsTimelineAndKeepsItsEnd() {
        val input = FloatArray(10_000) { (it % 97 + 1) / 100f }
        for (delay in intArrayOf(0, 1, 1024, 2048, 5120)) {
            for (flushes in booleanArrayOf(true, false)) {
                val decoded = roundTrip(input, chunk = 3000, delay = delay, flushes = flushes)
                assertTrue("delay $delay: the copy is cut short", decoded.size >= input.size)
                // Every sample after the priming decodes at its own time, the last one too.
                for (n in delay until input.size) {
                    assertEquals("delay $delay, sample $n", input[n], decoded[n], 0f)
                }
            }
        }
    }

    @Test
    fun aRecordingShorterThanTheDelayStillGivesACopyAsLong() {
        val input = FloatArray(500) { 0.5f }
        val decoded = roundTrip(input, chunk = 160, delay = 2048, flushes = false)
        assertTrue(decoded.size >= input.size)
    }
}
