package com.meetily.mobile

import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.JobQueue.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen works out its offer of interrupted work on a worker and
 * opens the prompt only if it still may once the answer is back
 * (MainActivity.offerInterruptedWork).
 */
class InterruptedOfferTest {

    private val firstCheck = Job(
        JobQueue.KIND_CHECK, "m1", "whisper-base", interrupted = true, firstTranscript = true
    )
    private val summary = Job(JobQueue.KIND_SUMMARY, "m2", "general", interrupted = true)
    private val import = Job(
        JobQueue.KIND_IMPORT, "", "whisper-base", interrupted = true,
        stagedFile = "import-1.m4a", sourceName = "Standup.m4a"
    )

    @Test
    fun anImportGoesByThePickedFileAndStartsOver() {
        val offer = InterruptedOffer.offer(import, null, false, null)
        assertEquals("Standup.m4a", offer.title)
        assertNull(offer.continueFromMs)
        assertEquals(import, offer.job)
    }

    @Test
    fun aFirstTranscriptStoppedPartWayCarriesOnWhileItsAudioIsThere() {
        val offer = InterruptedOffer.offer(firstCheck, "Weekly", true, 61_000L)
        assertEquals("Weekly", offer.title)
        assertEquals(61_000L, offer.continueFromMs)
        assertNull(InterruptedOffer.offer(firstCheck, "Weekly", false, 61_000L).continueFromMs)
    }

    @Test
    fun aFirstTranscriptWithNothingToCarryOnFromStartsOver() {
        assertNull(InterruptedOffer.offer(firstCheck, "Weekly", true, 0L).continueFromMs)
        assertNull(InterruptedOffer.offer(firstCheck, "Weekly", true, null).continueFromMs)
    }

    @Test
    fun onlyAFirstTranscriptCarriesOn() {
        val secondOpinion = firstCheck.copy(firstTranscript = false)
        assertNull(InterruptedOffer.offer(secondOpinion, "Weekly", true, 61_000L).continueFromMs)
        val offer = InterruptedOffer.offer(summary, "Review", true, 61_000L)
        assertEquals("Review", offer.title)
        assertNull(offer.continueFromMs)
    }

    @Test
    fun aMeetingThatNoLongerReadsIsOfferedUntitledFromTheStart() {
        val offer = InterruptedOffer.offer(firstCheck, null, true, 61_000L)
        assertEquals("", offer.title)
        assertNull(offer.continueFromMs)
    }

    @Test
    fun stillInterruptedWhileTheSameMarkerIsQueued() {
        assertTrue(InterruptedOffer.stillInterrupted(listOf(summary, firstCheck), firstCheck))
        assertTrue(InterruptedOffer.stillInterrupted(listOf(import), import))
    }

    @Test
    fun notStillInterruptedOnceSettled() {
        // Answered: the marker is gone.
        assertFalse(InterruptedOffer.stillInterrupted(listOf(summary), firstCheck))
        // Yes on a summary queues it again, deferred rather than interrupted.
        val requeued = summary.copy(interrupted = false)
        assertFalse(InterruptedOffer.stillInterrupted(listOf(requeued), summary))
        // Another import: every import shares a blank meeting id.
        val other = import.copy(stagedFile = "import-2.m4a")
        assertFalse(InterruptedOffer.stillInterrupted(listOf(other), import))
        // The same meeting's other kind of run.
        val otherKind = firstCheck.copy(kind = JobQueue.KIND_SUMMARY)
        assertFalse(InterruptedOffer.stillInterrupted(listOf(otherKind), firstCheck))
    }

    @Test
    fun opensOnlyWhenEveryGuardStillHolds() {
        assertTrue(mayShow())
        assertFalse(mayShow(visible = false))
        assertFalse(mayShow(promptShowing = true))
        assertFalse(mayShow(crashShowing = true))
        assertFalse(mayShow(canStartBatch = false))
        assertFalse(mayShow(stillInterrupted = false))
    }

    private fun mayShow(
        visible: Boolean = true,
        promptShowing: Boolean = false,
        crashShowing: Boolean = false,
        canStartBatch: Boolean = true,
        stillInterrupted: Boolean = true
    ) = InterruptedOffer.mayShow(visible, promptShowing, crashShowing, canStartBatch, stillInterrupted)
}
