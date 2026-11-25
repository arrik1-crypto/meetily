package com.meetily.mobile

import com.meetily.mobile.data.ImportBatch
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.whisper.AudioWindowExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queued imports: the batch "Cancel all" takes off the queue, the model a
 * queued file runs with once its own was deleted, the light meeting reads
 * the start paths use on the main thread, and where a voiceprint window is
 * decoded from.
 */
class QueuedImportTest {

    private fun queued(staged: String, batch: String = "", interrupted: Boolean = false) =
        JobQueue.Job(
            JobQueue.KIND_IMPORT, "", "small", 0L, interrupted = interrupted,
            stagedFile = staged, sourceName = "$staged.m4a", batchId = batch
        )

    // --- Cancel all -----------------------------------------------------------

    @Test
    fun cancelAllTakesOnlyTheRestOfThatBatch() {
        val summary = JobQueue.Job(JobQueue.KIND_SUMMARY, "m1", "general", 0L)
        val jobs = listOf(
            queued("a2", "A"), summary, queued("b1", "B"), queued("a3", "A"),
            queued("lone"),
            // The running file's in-flight marker is finishRun's, not this.
            queued("a1", "A", interrupted = true)
        )
        assertEquals(listOf("a2", "a3"), JobQueue.batchImports(jobs, "A").map { it.stagedFile })
        assertEquals(
            listOf("b1", "lone", "a1"),
            JobQueue.removeBatch(jobs, "A").filter { it.kind == JobQueue.KIND_IMPORT }
                .map { it.stagedFile }
        )
        assertTrue(summary in JobQueue.removeBatch(jobs, "A"))
        // A file on its own, or one queued before batch ids, has no batch.
        assertTrue(JobQueue.batchImports(jobs, "").isEmpty())
        assertEquals(jobs, JobQueue.removeBatch(jobs, ""))
    }

    @Test
    fun theBatchIdSurvivesTheStoredQueue() {
        val jobs = listOf(queued("a2", "A"), queued("lone"))
        assertEquals(jobs, JobQueue.decode(JobQueue.encode(jobs)))
        assertFalse(JobQueue.encode(listOf(queued("lone"))).contains("batchId"))
        // A row from before the field: no batch.
        val old = """[{"kind":"import","meetingId":"","stagedFile":"x","batchIndex":2,"batchSize":3}]"""
        assertEquals("", JobQueue.decode(old).single().batchId)
    }

    @Test
    fun cancelAllIsOfferedWhileFilesComeAfterThisOne() {
        assertTrue(ImportBatch.offersCancelAll("A", 1, 3))
        assertTrue(ImportBatch.offersCancelAll("A", 2, 3))
        // The last file: Cancel already stops everything.
        assertFalse(ImportBatch.offersCancelAll("A", 3, 3))
        // A file on its own, or a batch queued before ids existed.
        assertFalse(ImportBatch.offersCancelAll("", 0, 0))
        assertFalse(ImportBatch.offersCancelAll("", 1, 3))
        assertFalse(ImportBatch.offersCancelAll("A", 0, 0))
    }

    // --- A queued file whose model was deleted ----------------------------------

    @Test
    fun aQueuedImportFallsBackToTheSettingsModelOrWaits() {
        val onPhone = setOf("small", "parakeet")
        fun model(chosen: String, settings: String) =
            JobQueue.importModel(chosen, settings) { it in onPhone }
        assertEquals("small", model("small", "parakeet"))
        // Turbo deleted while the batch waited: the Settings model runs it.
        assertEquals("parakeet", model("turbo", "parakeet"))
        assertEquals("parakeet", model("", "parakeet"))
        // No model at all: it waits, staged copy kept (JobGate.drain).
        assertNull(model("turbo", "turbo"))
        assertNull(JobQueue.importModel("turbo", "") { false })
    }

    // --- Light reads ------------------------------------------------------------

    private fun longMeeting(): Meeting =
        Meeting(id = "m1", title = "Weekly sync", createdAtMs = 42L).apply {
            audioFile = "m1.m4a"
            transcriptResumeMs = 61_000L
            summary = "Agreed the plan."
            for (i in 0 until 3) {
                segments.add(
                    TranscriptSegment(
                        timestampMs = i.toLong(), text = "line $i", audioMs = i * 1000L,
                        words = listOf(WordStamp(0L, "line"), WordStamp(10L, "$i"))
                    )
                )
            }
        }

    @Test
    fun aCheckStartReadsItsFieldsWithoutTheTranscript() {
        val text = longMeeting().toJson().toString()
        val m = MeetingStore.fromFields(text, setOf("title", "audioFile", "transcriptResumeMs"))
        assertEquals("m1", m.id)
        assertEquals("Weekly sync", m.title)
        assertEquals("m1.m4a", m.audioFile)
        assertEquals(61_000L, m.transcriptResumeMs)
        assertTrue(m.segments.isEmpty())
        assertTrue(m.loadedPartially)
    }

    @Test
    fun theLinesAreReadWithoutTheirWordTimings() {
        val text = longMeeting().toJson().toString()
        val m = MeetingStore.fromFields(text, setOf("title", "segments", "summary"))
        assertEquals(3, m.segments.size)
        assertNull(m.segments[0].words)
        assertEquals("Agreed the plan.", m.summary)
        // A meeting with no lines reads as having none: a first transcript.
        val empty = Meeting(id = "m2", title = "Audio only", createdAtMs = 1L)
        assertTrue(MeetingStore.fromFields(empty.toJson().toString(), setOf("segments")).segments.isEmpty())
    }

    @Test
    fun aHeaderIsTheTitleAndDate() {
        val m = MeetingStore.fromHeader(longMeeting().toJson().toString())
        assertEquals("Weekly sync", m.title)
        assertEquals(42L, m.createdAtMs)
        assertTrue(m.segments.isEmpty())
    }

    // --- Voiceprint window ---------------------------------------------------------

    @Test
    fun anMp3WindowIsDecodedFromTheTopRatherThanSought() {
        // MP3 seeks land seconds off: decoded from the start, counted.
        assertEquals(0L, AudioWindowExtractor.seekUs(2_530_000L, "audio/mpeg"))
        assertEquals(0L, AudioWindowExtractor.seekUs(2_530_000L, null))
        // Everything else seeks to the window.
        assertEquals(2_530_000_000L, AudioWindowExtractor.seekUs(2_530_000L, "audio/mp4a-latm"))
        assertEquals(0L, AudioWindowExtractor.seekUs(-5L, "audio/opus"))
    }
}
