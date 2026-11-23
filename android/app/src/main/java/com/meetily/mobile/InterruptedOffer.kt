package com.meetily.mobile

import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.TranscriptResume

/**
 * The decisions behind the home screen's offer of work that died with the
 * process (MainActivity.offerInterruptedWork): what the prompt offers, worked
 * out on a worker from the interrupted meeting, and whether that answer may
 * still open the prompt once it is back on the main thread. Pure, so it is
 * unit-tested.
 */
internal object InterruptedOffer {

    /**
     * A prompt to show: the [job] offered back, the [title] it goes by, and
     * where a first transcript that stopped part-way carries on from
     * ([continueFromMs]; null when the run starts over).
     */
    data class Offer(val job: JobQueue.Job, val title: String, val continueFromMs: Long?)

    /**
     * The prompt for [job]. An import has no meeting yet and goes by the
     * file the user picked. A summary or check goes by its meeting's
     * [meetingTitle] (null: the file no longer reads, which leaves it
     * untitled); a first transcript that stopped part-way carries on from
     * [resumeMs] while the meeting's audio is still there ([audioPresent]),
     * and one that stopped before any pause to carry on from starts over.
     */
    fun offer(job: JobQueue.Job, meetingTitle: String?, audioPresent: Boolean, resumeMs: Long?): Offer {
        if (job.kind == JobQueue.KIND_IMPORT) return Offer(job, job.sourceName, null)
        val continueFrom = resumeMs?.takeIf {
            meetingTitle != null && job.kind == JobQueue.KIND_CHECK && job.firstTranscript &&
                audioPresent && TranscriptResume.canContinue(it)
        }
        return Offer(job, meetingTitle.orEmpty(), continueFrom)
    }

    /**
     * Whether [jobs] still hold [job] as an interrupted run: the same kind,
     * meeting and staged file (every queued import shares a blank meeting
     * id, so an import is told apart by its file).
     */
    fun stillInterrupted(jobs: List<JobQueue.Job>, job: JobQueue.Job): Boolean =
        JobQueue.interrupted(jobs).any {
            it.kind == job.kind && it.meetingId == job.meetingId && it.stagedFile == job.stagedFile
        }

    /**
     * Whether an offer worked out off the main thread may open its prompt
     * now: the screen is still showing ([visible]), no prompt is up already
     * (this one, or the crash report's, which goes first and asks again when
     * it is answered), nothing runs ([canStartBatch]: a run started meanwhile
     * owns its marker), and the job is still interrupted ([stillInterrupted]:
     * a tap, or another screen's prompt, may have settled it meanwhile).
     * Every no is safe to drop: the next resume, or the crash prompt's
     * dismissal, looks again.
     */
    fun mayShow(
        visible: Boolean,
        promptShowing: Boolean,
        crashShowing: Boolean,
        canStartBatch: Boolean,
        stillInterrupted: Boolean
    ): Boolean = visible && !promptShowing && !crashShowing && canStartBatch && stillInterrupted
}
