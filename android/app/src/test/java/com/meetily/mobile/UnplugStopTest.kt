package com.meetily.mobile

import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.llm.PromptShaping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stopping charging-deferred work when the charger comes out.
 *
 * "Wait until I'm charging" used to govern only the moment a job STARTED.
 * A summary or a re-transcription that began on the charger then ran to
 * completion on the battery — twenty minutes of full-CPU inference bought
 * with exactly the power the setting existed to protect. What is covered
 * here is the pure half of the fix: the map-reduce loop noticing the stop,
 * and the queue accepting the job back on the same terms it was deferred on.
 */
class UnplugStopTest {

    private fun longTranscript(): String =
        (1..400).joinToString("\n") { "line $it with some words in it" }.repeat(40)

    @Test
    fun stoppingAbandonsTheRemainingSectionsInsteadOfRacingThroughThem() {
        // A stopped generation returns instantly. Without a check in the
        // loop the "stop" would spend no less CPU than finishing — it would
        // just sprint through every remaining chunk writing "(section notes
        // unavailable)" and hand back a page of nothing.
        var calls = 0
        val result = PromptShaping.condense(
            content = longTranscript(),
            charBudget = 9_000,
            generate = { _, _ -> calls++; "notes" },
            shouldStop = { calls >= 2 }
        )
        assertNull("a stopped condense must not produce notes", result)
        assertEquals("kept generating after the stop", 2, calls)
    }

    @Test
    fun aStopBeforeTheFirstSectionGeneratesNothingAtAll() {
        var calls = 0
        val result = PromptShaping.condense(
            content = longTranscript(),
            charBudget = 9_000,
            generate = { _, _ -> calls++; "notes" },
            shouldStop = { true }
        )
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun notStoppingBehavesExactlyAsBefore() {
        // The parameter defaults to "never stop", so every existing caller
        // and every prior recording keeps the behaviour it had.
        val result = PromptShaping.condense(
            content = longTranscript(),
            charBudget = 9_000,
            generate = { _, _ -> "notes" }
        )
        assertTrue(result!!.contains("--- Section 1 ---"))
    }

    @Test
    fun aRequeuedJobIsStillWaitingForPower() {
        // The requeue has to preserve chargingOnly. If it did not, the job
        // would drain the moment the app was next opened — on battery —
        // which is the precise thing unplugging just asked it not to do.
        val job = JobQueue.Job(
            JobQueue.KIND_SUMMARY, "m1", "default",
            queuedAtMs = 1L, chargingOnly = true
        )
        val queue = JobQueue.add(emptyList(), job)
        assertNull(JobQueue.nextRunnable(queue, charging = false))
        assertEquals(job, JobQueue.nextRunnable(queue, charging = true))
    }

    @Test
    fun requeueingAfterTheRunFinishedLeavesExactlyOneEntry() {
        // finishRun clears the "interrupted" marker for the run that just
        // ended and THEN requeues. Both entries are the same kind+meeting,
        // so doing it the other way round removes the one just added — the
        // job would vanish and never come back.
        val running = JobQueue.Job(
            JobQueue.KIND_SUMMARY, "m1", "default",
            queuedAtMs = 1L, interrupted = true
        )
        val cleared = JobQueue.remove(listOf(running), JobQueue.KIND_SUMMARY, "m1")
        assertTrue(cleared.isEmpty())

        val requeued = JobQueue.add(
            cleared,
            JobQueue.Job(
                JobQueue.KIND_SUMMARY, "m1", "default",
                queuedAtMs = 2L, chargingOnly = true
            )
        )
        assertEquals(1, requeued.size)
        assertTrue(requeued[0].chargingOnly)
        // Not interrupted: this was a deliberate stop, and deferred work
        // drains on its own where interrupted work waits to be offered back.
        assertFalse(requeued[0].interrupted)
        assertEquals(1, JobQueue.runnable(requeued).size)
    }

    @Test
    fun aCheckStoppedByUnplugDoesNotBlockAnUnrelatedSummary() {
        // nextRunnable SKIPS a charging-only job rather than blocking on it.
        // A re-transcription put back by an unplug must not hold up a summary
        // the user asked for by hand afterwards.
        val queue = listOf(
            JobQueue.Job(
                JobQueue.KIND_CHECK, "m1", "base",
                queuedAtMs = 1L, chargingOnly = true
            ),
            JobQueue.Job(JobQueue.KIND_SUMMARY, "m2", "default", queuedAtMs = 2L)
        )
        assertEquals("m2", JobQueue.nextRunnable(queue, charging = false)?.meetingId)
        assertEquals("m1", JobQueue.nextRunnable(queue, charging = true)?.meetingId)
    }
}
