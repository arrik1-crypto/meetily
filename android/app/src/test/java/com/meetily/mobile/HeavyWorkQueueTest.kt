package com.meetily.mobile

import com.meetily.mobile.data.HeavyWork
import com.meetily.mobile.data.JobQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CPU budget and the deferred-job queue: the two pieces that decide
 * whether three heavy engines coexist or starve the UI thread into an ANR.
 */
class HeavyWorkQueueTest {

    // --- Thread budget ------------------------------------------------------

    @Test
    fun aLoneBatchJobStillLeavesTheUiThreadACore() {
        // 8 cores, nothing else running: capped at MAX rather than all 8.
        assertEquals(6, HeavyWork.batchThreads(8, recordingActive = false, batchJobs = 1))
    }

    @Test
    fun recordingReservesItsSharePlusTheUiThread() {
        // Recording takes 4 of 8; one more is held for the UI; 3 remain.
        assertEquals(4, HeavyWork.recordingThreads(8))
        assertEquals(3, HeavyWork.batchThreads(8, recordingActive = true, batchJobs = 1))
    }

    @Test
    fun twoBatchJobsSplitWhatIsLeftRatherThanEachTakingEverything() {
        // The bug this replaces: each engine independently asked for 6.
        assertEquals(3, HeavyWork.batchThreads(8, recordingActive = false, batchJobs = 2))
    }

    @Test
    fun aSmallPhoneNeverDropsBelowTheFloor() {
        // 4 cores, recording plus a batch job: the arithmetic goes negative,
        // and a zero-thread engine would simply never finish.
        assertEquals(2, HeavyWork.batchThreads(4, recordingActive = true, batchJobs = 2))
        assertEquals(2, HeavyWork.recordingThreads(2))
    }

    // --- Queue rules --------------------------------------------------------

    private fun job(kind: String, id: String, at: Long = 0L, interrupted: Boolean = false) =
        JobQueue.Job(kind, id, "payload", at, interrupted)

    @Test
    fun theSameMeetingNeverQueuesTwiceForTheSameKind() {
        val once = JobQueue.add(emptyList(), job(JobQueue.KIND_SUMMARY, "m1", 1))
        val twice = JobQueue.add(once, job(JobQueue.KIND_SUMMARY, "m1", 2))
        assertEquals(1, twice.size)
        assertEquals(2L, twice.first().queuedAtMs)
    }

    @Test
    fun aCheckAndASummaryForOneMeetingAreDifferentJobs() {
        var jobs = JobQueue.add(emptyList(), job(JobQueue.KIND_SUMMARY, "m1"))
        jobs = JobQueue.add(jobs, job(JobQueue.KIND_CHECK, "m1"))
        assertEquals(2, jobs.size)
    }

    @Test
    fun aWeekOffChargerDoesNotDetonateOnPlugIn() {
        var jobs = emptyList<JobQueue.Job>()
        for (i in 1..12) {
            jobs = JobQueue.add(jobs, job(JobQueue.KIND_SUMMARY, "m$i", i.toLong()))
        }
        assertEquals(JobQueue.MAX_JOBS, jobs.size)
        // Oldest fall off the back; the newest survive.
        assertEquals("m12", jobs.last().meetingId)
        assertEquals("m8", jobs.first().meetingId)
    }

    @Test
    fun removeTakesOnlyTheMatchingKind() {
        var jobs = JobQueue.add(emptyList(), job(JobQueue.KIND_SUMMARY, "m1"))
        jobs = JobQueue.add(jobs, job(JobQueue.KIND_CHECK, "m1"))
        val left = JobQueue.remove(jobs, JobQueue.KIND_SUMMARY, "m1")
        assertEquals(1, left.size)
        assertEquals(JobQueue.KIND_CHECK, left.first().kind)
    }

    @Test
    fun interruptedWorkNeverDrainsOnItsOwn() {
        // The user may have force-stopped the app precisely to kill this;
        // restarting it unasked would be its own bug.
        val jobs = listOf(
            job(JobQueue.KIND_SUMMARY, "deferred"),
            job(JobQueue.KIND_SUMMARY, "died", interrupted = true)
        )
        val runnable = JobQueue.runnable(jobs)
        assertEquals(1, runnable.size)
        assertEquals("deferred", runnable.first().meetingId)

        val offered = JobQueue.interrupted(jobs)
        assertEquals(1, offered.size)
        assertEquals("died", offered.first().meetingId)
    }

    @Test
    fun queueingTwoFilesKeepsBoth() {
        // Imports share a blank meeting id, so without the staged file as
        // identity the second pick would silently replace the first — the
        // exact loss the queue exists to prevent.
        val a = JobQueue.Job(JobQueue.KIND_IMPORT, "", "model", 1L, stagedFile = "a.m4a")
        val b = JobQueue.Job(JobQueue.KIND_IMPORT, "", "model", 2L, stagedFile = "b.m4a")
        val jobs = JobQueue.add(JobQueue.add(emptyList(), a), b)
        assertEquals(2, jobs.size)
        assertEquals(listOf("a.m4a", "b.m4a"), jobs.map { it.stagedFile })
    }

    @Test
    fun automaticJobsAreTrimmedButImportsAreNot() {
        // Deferred checks are a bonus and may be dropped; an import is a file
        // the user explicitly picked.
        var jobs = emptyList<JobQueue.Job>()
        for (i in 1..4) {
            jobs = JobQueue.add(
                jobs,
                JobQueue.Job(JobQueue.KIND_IMPORT, "", "m", i.toLong(), stagedFile = "f$i")
            )
        }
        for (i in 1..8) {
            jobs = JobQueue.add(jobs, job(JobQueue.KIND_SUMMARY, "m$i", i.toLong()))
        }
        assertEquals(4, jobs.count { it.kind == JobQueue.KIND_IMPORT })
        assertEquals(JobQueue.MAX_JOBS, jobs.count { it.kind != JobQueue.KIND_IMPORT })
    }

    @Test
    fun removingOneQueuedImportLeavesTheOthers() {
        val a = JobQueue.Job(JobQueue.KIND_IMPORT, "", "m", 1L, stagedFile = "a")
        val b = JobQueue.Job(JobQueue.KIND_IMPORT, "", "m", 2L, stagedFile = "b")
        val left = JobQueue.removeStaged(JobQueue.add(JobQueue.add(emptyList(), a), b), "a")
        assertEquals(listOf("b"), left.map { it.stagedFile })
    }

    @Test
    fun aRunMarkerReplacesTheDeferredEntryForTheSameMeeting() {
        // Queued while off charger, then actually started: one entry, now
        // flagged as in flight, so finishing it clears the right row.
        val queued = JobQueue.add(emptyList(), job(JobQueue.KIND_SUMMARY, "m1"))
        val running = JobQueue.add(queued, job(JobQueue.KIND_SUMMARY, "m1", interrupted = true))
        assertEquals(1, running.size)
        assertTrue(running.first().interrupted)
        assertTrue(JobQueue.runnable(running).isEmpty())
        assertFalse(JobQueue.interrupted(running).isEmpty())
    }
}
