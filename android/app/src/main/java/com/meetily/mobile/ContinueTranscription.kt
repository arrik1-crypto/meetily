package com.meetily.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.TranscriptDraft
import com.meetily.mobile.data.TranscriptResume
import com.meetily.mobile.whisper.TranscriptionModels

/**
 * "Continue transcription": carries an unfinished transcript on from where
 * it stopped (TranscriptResume) instead of starting it over.
 *
 * Offered on the meeting screen (a card above the tabs, and the ⋮ menu) and
 * in the home card's options, which hand over to the meeting screen, while
 * a meeting's words stop short of its recording, its audio is still here
 * and no run is going or queued for it. The run is a first-transcript pass
 * over the meeting's own audio with the model that made the words; the
 * importer keeps the lines before the seam and adds the rest. When that
 * model is gone it can only start over with the current one, which is
 * asked first because its lines replace the ones there. So is a transcript
 * that stopped before it reached any pause to carry on from (its resume
 * point is 0:00): it, too, can only start over. A continuation is asked
 * first too when the transcript already has lines from its resume point
 * on: the point is the last pause everything before was finished up to,
 * and lines can run well past it, which the pass makes again and replaces
 * as it reaches them (one that stops again keeps those it never reached).
 *
 * While it waits for the user, the meeting is not busy: it can be opened,
 * edited, summarised or deleted. Its audio is kept from the automatic
 * clean-up and multi-select, and removing it by hand says the transcript
 * can then never be finished (AudioRemoval.confirm).
 */
object ContinueTranscription {

    /** Where [m]'s words stop, when they can be continued now; null otherwise. */
    fun offeredAt(context: Context, m: Meeting): Long? {
        val at = m.transcriptResumeMs ?: return null
        if (!AudioStore.exists(context, m.audioFile)) return null
        if (busy(context, m.id)) return null
        return at
    }

    /**
     * A run is writing [meetingId], or its transcription is queued (a
     * continuation, an unplugged pass waiting for a charger): another now
     * would only run behind or beside it. A queued accuracy check is not
     * one: it stages a draft and leaves the transcript as it is. A finished
     * check waiting for review does not hide Continue either, but [start]
     * refuses until it has been reviewed (see [checkPending]).
     */
    fun busy(context: Context, meetingId: String): Boolean =
        AudioRemoval.isWriting(context, meetingId) ||
            JobQueue.load(context).any {
                it.kind == JobQueue.KIND_CHECK && it.meetingId == meetingId &&
                    !it.interrupted && it.firstTranscript
            }

    /**
     * An accuracy check queued for [meetingId]. The queue holds one check
     * per meeting, so a continuation queued now would replace it.
     */
    private fun checkQueued(context: Context, meetingId: String): Boolean =
        JobQueue.load(context).any {
            it.kind == JobQueue.KIND_CHECK && it.meetingId == meetingId &&
                !it.interrupted && !it.firstTranscript
        }

    /**
     * A finished accuracy check of [meetingId] waiting for review. It was
     * compared against the transcript as it is now, and an apply that lands
     * while a continuation runs is undone by the continuation's next save,
     * so the check is reviewed (or discarded) first. The same rule Check
     * accuracy and Re-transcribe as follow.
     */
    private fun checkPending(context: Context, meetingId: String): Boolean =
        TranscriptDraft.isPending(context, meetingId)

    /**
     * "From 42:10" for a transcript whose words stop at [at], or "From the
     * beginning" when it never reached a pause to carry on from.
     */
    fun fromLabel(context: Context, at: Long): String =
        if (TranscriptResume.canContinue(at)) {
            context.getString(R.string.resume_continue_from, TranscriptResume.clock(at))
        } else {
            context.getString(R.string.resume_continue_from_start)
        }

    /** Shows the meeting screen's card for [m], or hides it. */
    fun bindCard(card: View, text: TextView, button: TextView, m: Meeting, onContinue: () -> Unit) {
        val at = offeredAt(card.context, m)
        if (at == null) {
            card.visibility = View.GONE
            return
        }
        if (TranscriptResume.canContinue(at)) {
            text.text = card.context.getString(R.string.resume_card_text, TranscriptResume.clock(at))
            button.setText(R.string.resume_continue)
        } else {
            // Stopped before any pause it could carry on from.
            text.setText(R.string.resume_card_text_restart)
            button.setText(R.string.resume_start_over)
        }
        button.setOnClickListener { onContinue() }
        card.visibility = View.VISIBLE
    }

    /**
     * Continues [m] with the model that made its words, or offers to start
     * over with the current one when that model is gone. [onStarted] runs
     * (main thread) once a run has been started or queued.
     *
     * [m] has to be what is stored now: every branch acts on where its
     * words stop and on its lines, and the importer takes its seam from the
     * store. The meeting screen syncs and waits before calling this
     * (MeetingDetailActivity.continueTranscription).
     */
    fun start(activity: Activity, m: Meeting, onStarted: () -> Unit) {
        val at = m.transcriptResumeMs
        if (at == null || !AudioStore.exists(activity, m.audioFile)) {
            Toast.makeText(activity, R.string.no_audio_kept, Toast.LENGTH_SHORT).show()
            return
        }
        if (busy(activity, m.id)) {
            Toast.makeText(activity, R.string.resume_busy, Toast.LENGTH_LONG).show()
            return
        }
        if (checkPending(activity, m.id)) {
            Toast.makeText(activity, R.string.resume_check_pending, Toast.LENGTH_LONG).show()
            return
        }
        // Before any question: asking whether lines may be replaced and then
        // refusing would be the wrong way round.
        if (refusedForQueuedCheck(activity, m.id)) return
        val stored = m.transcriptModel
        val current = AppSettings(activity).whisperModel
        val plan = TranscriptResume.plan(
            stored,
            stored?.let { TranscriptionModels.isReady(activity, it) } == true,
            current,
            TranscriptionModels.isReady(activity, current)
        )
        val key = plan.modelKey
        if (plan.action == TranscriptResume.Action.NO_MODEL || key == null) {
            Toast.makeText(
                activity,
                activity.getString(
                    R.string.resume_no_model,
                    TranscriptionModels.displayName(stored ?: current)
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        when {
            plan.action == TranscriptResume.Action.CONTINUE && TranscriptResume.canContinue(at) -> {
                // The resume point can lag far behind the last line (a
                // background that never stays quiet is cut only at the length
                // limit), and the pass replaces every line from it on, edits
                // included: say how many before it starts.
                val redone = TranscriptResume.redoneCount(m.segments, at, m.createdAtMs)
                if (redone == 0) {
                    launch(activity, m, key, at, onStarted)
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.resume_continue)
                        .setMessage(
                            activity.resources.getQuantityString(
                                R.plurals.resume_redo_message, redone, redone, TranscriptResume.clock(at)
                            )
                        )
                        .setPositiveButton(R.string.resume_continue) { _, _ ->
                            // Asked again: a run may have started meanwhile.
                            if (!refused(activity, m.id, at)) launch(activity, m, key, at, onStarted)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .showSecure()
                }
            }
            // Nothing there for a start-over to replace.
            m.segments.isEmpty() -> launch(activity, m, key, 0L, onStarted)
            // Stopped before any pause to carry on from: the pass can only
            // start over, and its first save replaces every line there now.
            !TranscriptResume.canContinue(at) -> confirmStartOver(
                activity, m, key, at,
                activity.getString(
                    R.string.resume_restart_message, TranscriptionModels.displayName(key)
                ),
                onStarted
            )
            else -> confirmStartOver(
                activity, m, key, at,
                activity.getString(
                    R.string.resume_model_gone_message,
                    TranscriptionModels.displayName(stored.orEmpty()),
                    TranscriptResume.clock(at),
                    TranscriptionModels.displayName(key)
                ),
                onStarted
            )
        }
    }

    /**
     * Asks before a pass over [m] from the beginning replaces the lines
     * there. [askedAtMs] is where the transcript stopped when asked.
     */
    private fun confirmStartOver(
        activity: Activity,
        m: Meeting,
        modelKey: String,
        askedAtMs: Long,
        message: String,
        onStarted: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.resume_model_gone_title)
            .setMessage(message)
            .setPositiveButton(R.string.resume_start_over) { _, _ ->
                // Asked again: a run may have started meanwhile.
                if (!refused(activity, m.id, askedAtMs)) {
                    launch(activity, m, modelKey, 0L, onStarted)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    /**
     * Re-checked once a question has been answered, since a run may have
     * started, or a check finished, while it was up: true, with a toast
     * saying why, when [meetingId] may not be continued now.
     *
     * [askedAtMs] is where the transcript stopped when the question was
     * asked, which was what was stored then (see [start]); what is stored
     * must still say so. The question can outlive the state it was about
     * (another meeting screen, opened from a notification on top of this
     * one, continues the transcript to the end, or an accuracy check is
     * applied), and the importer takes its seam from what is stored: a
     * finished transcript would be started over from 0:00 and every line
     * of it, edits included, replaced. It is read from the store: the
     * screen's copy follows the store only when the screen next syncs.
     */
    private fun refused(activity: Activity, meetingId: String, askedAtMs: Long): Boolean {
        val reason = when {
            busy(activity, meetingId) -> R.string.resume_busy
            checkPending(activity, meetingId) -> R.string.resume_check_pending
            !TranscriptResume.stillAsAsked(
                askedAtMs, MeetingStore(activity).load(meetingId)?.transcriptResumeMs
            ) -> R.string.resume_changed
            else -> return false
        }
        Toast.makeText(activity, reason, Toast.LENGTH_LONG).show()
        return true
    }

    /**
     * An accuracy check of [meetingId] is queued: true, with a toast saying
     * so, and the check started when nothing else is running.
     *
     * The queue holds one check per meeting, and a run started now would
     * supersede it too (JobQueue.add drops deferred work for the same
     * meeting when ImportService.markRunning adds its marker): either way
     * the accuracy check the user asked for would vanish.
     */
    private fun refusedForQueuedCheck(activity: Activity, meetingId: String): Boolean {
        if (!checkQueued(activity, meetingId)) return false
        // Nothing may be running yet with the check still not started (no
        // drain since a recording ended, or after a timed-out job): start
        // it now rather than at the next home-screen resume. A no-op when
        // something is running, or when the check waits for a charger.
        JobGate.drain(activity)
        Toast.makeText(activity, R.string.resume_check_queued, Toast.LENGTH_LONG).show()
        return true
    }

    /**
     * A first-transcript pass over [m]'s own audio with [modelKey]. With the
     * model that made the words the importer carries on from where they
     * stop; with another it starts over ([fromMs] 0). Queued behind a job
     * already running, as the accuracy check is.
     */
    private fun launch(
        activity: Activity,
        m: Meeting,
        modelKey: String,
        fromMs: Long,
        onStarted: () -> Unit
    ) {
        val file = m.audioFile?.let { AudioStore.fileFor(activity, it) }
        if (file == null || file.length() <= 0L) {
            Toast.makeText(activity, R.string.no_audio_kept, Toast.LENGTH_SHORT).show()
            return
        }
        val clock = TranscriptResume.clock(fromMs)
        // Asked again: one may have been queued while a question was up.
        if (refusedForQueuedCheck(activity, m.id)) return
        fun queue() {
            JobGate.requestCheck(
                activity, m.id, modelKey, whenCharging = false, required = true,
                firstTranscript = true, resumeFromMs = fromMs
            )
            Toast.makeText(
                activity,
                if (fromMs > 0L) activity.getString(R.string.resume_queued, clock)
                else activity.getString(R.string.resume_restart_queued),
                Toast.LENGTH_LONG
            ).show()
            onStarted()
        }
        if (!JobGate.canStartBatch()) {
            queue()
            return
        }
        val start = Intent(activity, ImportService::class.java)
            .setAction(ImportService.ACTION_START)
            .setData(AudioStore.uriFor(activity, file))
            .putExtra(ImportService.EXTRA_NAME, m.title)
            .putExtra(ImportService.EXTRA_MODEL, modelKey)
            .putExtra(ImportService.EXTRA_RECHECK_MEETING_ID, m.id)
            // Its words go into the meeting, as the first pass's did.
            .putExtra(ImportService.EXTRA_FIRST_TRANSCRIPT, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (fromMs > 0L) start.putExtra(ImportService.EXTRA_RESUME_FROM_MS, fromMs)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(start)
            } else {
                activity.startService(start)
            }
        } catch (_: RuntimeException) {
            // Refused (the screen went to the background on the way): the
            // queue runs it from the next resume instead.
            queue()
            return
        }
        Toast.makeText(
            activity,
            if (fromMs > 0L) activity.getString(R.string.resume_started, clock)
            else activity.getString(R.string.resume_restarted, TranscriptionModels.displayName(modelKey)),
            Toast.LENGTH_LONG
        ).show()
        onStarted()
    }
}
