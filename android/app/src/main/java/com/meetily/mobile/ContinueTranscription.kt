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
 * asked first because its lines replace the ones there.
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
     * A run is writing [meetingId], or a transcription or check is queued
     * for it: a continuation now would only run behind or beside it.
     */
    fun busy(context: Context, meetingId: String): Boolean =
        AudioRemoval.isWriting(context, meetingId) ||
            JobQueue.isQueued(context, JobQueue.KIND_CHECK, meetingId)

    /** Shows the meeting screen's card for [m], or hides it. */
    fun bindCard(card: View, text: TextView, button: View, m: Meeting, onContinue: () -> Unit) {
        val at = offeredAt(card.context, m)
        if (at == null) {
            card.visibility = View.GONE
            return
        }
        text.text = card.context.getString(R.string.resume_card_text, TranscriptResume.clock(at))
        button.setOnClickListener { onContinue() }
        card.visibility = View.VISIBLE
    }

    /**
     * Continues [m] with the model that made its words, or offers to start
     * over with the current one when that model is gone. [onStarted] runs
     * (main thread) once a run has been started or queued.
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
        val stored = m.transcriptModel
        val current = AppSettings(activity).whisperModel
        val plan = TranscriptResume.plan(
            stored,
            stored?.let { TranscriptionModels.isReady(activity, it) } == true,
            current,
            TranscriptionModels.isReady(activity, current)
        )
        when (plan.action) {
            TranscriptResume.Action.CONTINUE ->
                launch(activity, m, plan.modelKey ?: return, at, onStarted)
            TranscriptResume.Action.START_OVER -> {
                val other = plan.modelKey ?: return
                AlertDialog.Builder(activity)
                    .setTitle(R.string.resume_model_gone_title)
                    .setMessage(
                        activity.getString(
                            R.string.resume_model_gone_message,
                            TranscriptionModels.displayName(stored.orEmpty()),
                            TranscriptResume.clock(at),
                            TranscriptionModels.displayName(other)
                        )
                    )
                    .setPositiveButton(R.string.resume_start_over) { _, _ ->
                        // Asked again: a run may have started meanwhile.
                        if (busy(activity, m.id)) {
                            Toast.makeText(activity, R.string.resume_busy, Toast.LENGTH_LONG).show()
                        } else {
                            launch(activity, m, other, 0L, onStarted)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showSecure()
            }
            TranscriptResume.Action.NO_MODEL -> Toast.makeText(
                activity,
                activity.getString(
                    R.string.resume_no_model,
                    TranscriptionModels.displayName(stored ?: current)
                ),
                Toast.LENGTH_LONG
            ).show()
        }
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
