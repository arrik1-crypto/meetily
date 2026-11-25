package com.meetily.mobile

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.TranscriptDraft
import com.meetily.mobile.data.TranscriptReconcile
import com.meetily.mobile.whisper.TranscriptionModels

/**
 * The result of an accuracy check: what a second model heard, next to what is
 * already stored, with the audio one tap away.
 *
 * The transcript is only replaced when the user says so — and "both models
 * agree" is a real answer here, not a failure. Nothing on this screen edits
 * the meeting until Apply.
 */
class TranscriptCheckActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private var meeting: Meeting? = null
    private var draft: TranscriptDraft.Draft? = null
    private var blocks: List<TranscriptReconcile.Block> = emptyList()
    private var diffs: List<TranscriptReconcile.Block> = emptyList()

    /** Block ordinals the user wants the new pass's text for. */
    private val acceptFresh = mutableSetOf<Int>()

    /** Set when a rotation interrupted the span-by-span review. */
    private var restoreReview = false

    private lateinit var loading: LinearProgressIndicator
    private lateinit var verdictPanel: View
    private lateinit var verdictView: TextView
    private lateinit var detailView: TextView
    private lateinit var reviewPanel: View
    private lateinit var reviewHint: TextView
    private lateinit var diffList: RecyclerView
    private lateinit var applyButton: MaterialButton

    /**
     * An apply is on a worker. The buttons are disabled and Back is held
     * until it lands; the job itself lives in [applyJobs] so a rotation
     * re-attaches to it instead of losing the result.
     */
    private var applying = false

    /** The meeting this screen was opened for, even when it no longer loads. */
    private var meetingId: String? = null

    /** This instance's hook into an in-flight [ApplyJob]; compared by identity. */
    private val applyListener: (Applied) -> Unit = { onApplied(it) }

    /** Swallows Back while an apply is committing, so it cannot land behind a closed screen. */
    private val applyBackGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Toast.makeText(
                this@TranscriptCheckActivity, R.string.check_applying_wait, Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** The "summary is out of date" question is up; re-asked after a rotation. */
    private var offeringRegenerate = false
    private var regenerateDialog: AlertDialog? = null

    /** The meeting and its draft are being read (onCreate); nothing is shown yet. */
    private var reading = true

    /** The question was up before a rotation; asked again once the meeting is read. */
    private var askRegenerate = false

    /** The comparison is shown; until then a rotation keeps [restoreReview] as it came. */
    private var compared = false

    private var player: MediaPlayer? = null

    /** [player] has finished preparing; until then a play tap waits in [pendingPlayMs]. */
    private var playerPrepared = false

    /** Where the latest play tap asked to start while the player prepared; -1 for none. */
    private var pendingPlayMs = -1L

    /** The loading bar is up for [player] preparing, not for a read, comparison or apply. */
    private var loadingForPlayer = false

    /** This screen's hold on audio focus while it plays a line; null when it has none. */
    private var focusRequest: AudioFocusRequest? = null

    /** Speech for the user to hear, as MeetingPlayback plays a meeting. */
    private val playbackAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_transcript_check)
        EdgeToEdge.setUp(this) { root() }

        store = MeetingStore(this)
        onBackPressedDispatcher.addCallback(this, applyBackGuard)
        // Up goes through the dispatcher so the apply guard covers it too.
        findViewById<MaterialToolbar>(R.id.checkToolbar)
            .setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        loading = findViewById(R.id.checkLoading)
        verdictPanel = findViewById(R.id.checkVerdictPanel)
        verdictView = findViewById(R.id.checkVerdict)
        detailView = findViewById(R.id.checkDetail)
        reviewPanel = findViewById(R.id.checkReviewPanel)
        reviewHint = findViewById(R.id.checkReviewHint)
        diffList = findViewById(R.id.checkDiffList)
        applyButton = findViewById(R.id.checkApplyButton)
        diffList.layoutManager = LinearLayoutManager(this)

        val id = intent.getStringExtra(EXTRA_MEETING_ID)
        meetingId = id
        // Rotating re-runs the comparison over the same two transcripts, so
        // the block ordinals come out identical and the user's picks can be
        // restored onto them. Losing a long review to a screen rotation would
        // be its own kind of data loss.
        if (savedInstanceState != null) {
            savedInstanceState.getIntArray(STATE_ACCEPTED)?.forEach { acceptFresh.add(it) }
            restoreReview = savedInstanceState.getBoolean(STATE_REVIEWING, false)
            askRegenerate = savedInstanceState.getBoolean(STATE_OFFER_REGENERATE, false)
        }

        findViewById<View>(R.id.checkReviewButton).setOnClickListener { showReview() }
        findViewById<View>(R.id.checkUseNewButton).setOnClickListener {
            acceptFresh.clear()
            acceptFresh.addAll(diffs.map { it.ordinal })
            applyChoices(wholesale = true)
        }
        findViewById<View>(R.id.checkKeepButton).setOnClickListener { discardDraft() }
        applyButton.setOnClickListener { applyChoices(wholesale = false) }

        if (id == null) {
            Toast.makeText(this, R.string.check_nothing_to_review, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // An apply a rotated instance started is re-joined once the meeting
        // is read (onRead); Back waits for it from now, as it did.
        if (applyJobs.containsKey(id)) applyBackGuard.isEnabled = true
        // Both are whole JSON files with every word's timing, megabytes on
        // the long meetings this screen is for: read off the main thread,
        // under the loading bar, after a rotation too.
        val askAgain = askRegenerate
        Thread {
            val loaded = store.load(id)
            // The question after an apply needs only the meeting.
            val pending = if (loaded != null && askAgain) null else TranscriptDraft.pending(this, id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                onRead(loaded, pending)
            }
        }.apply {
            name = "transcript-check-read"
            start()
        }
    }

    /** The meeting and its draft ([pending] null when none waits) are read; carries on from there. */
    private fun onRead(loaded: Meeting?, pending: TranscriptDraft.Draft?) {
        reading = false
        // Rotated while the "regenerate the summary?" question was up: the
        // transcript is already applied and the draft deleted, so there is
        // nothing to compare — just ask again.
        val askAgain = askRegenerate
        askRegenerate = false
        if (loaded != null && askAgain) {
            applyBackGuard.isEnabled = applying
            meeting = loaded
            loading.visibility = View.GONE
            offerRegenerate(loaded)
            return
        }
        // An apply started before a rotation may still be running (the
        // draft is still there) or may already have finished (the draft is
        // gone); either way its result belongs to this screen.
        val job = meetingId?.let { applyJobs[it] }
        if (job == null) applyBackGuard.isEnabled = applying
        if ((loaded == null || pending == null) && job == null) {
            Toast.makeText(this, R.string.check_nothing_to_review, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        meeting = loaded
        draft = pending

        if (job != null) attachJob(job)
        // attachJob may have delivered a finished result that closed the
        // screen or put the regenerate question up.
        if (loaded != null && pending != null && !isFinishing && !offeringRegenerate) {
            compare(loaded, pending)
        } else if (applying) {
            loading.visibility = View.VISIBLE
        }
    }

    /** Re-joins an apply that a previous instance of this screen started. */
    private fun attachJob(job: ApplyJob) {
        val finished = job.result
        if (finished != null) {
            meetingId?.let { applyJobs.remove(it) }
            onApplied(finished)
            return
        }
        job.listener = applyListener
        setApplying(true)
    }

    private fun setApplying(on: Boolean) {
        applying = on
        applyBackGuard.isEnabled = on
        loading.visibility = if (on) View.VISIBLE else View.GONE
        setButtonsEnabled(!on)
    }

    /**
     * Alignment is linear, but a long meeting is thousands of segments and
     * this runs the moment the screen opens — keep it off the main thread.
     */
    private fun compare(m: Meeting, pending: TranscriptDraft.Draft) {
        Thread {
            val aligned = TranscriptReconcile.align(
                m.segments.toList(), pending.segments, m.createdAtMs
            )
            val different = TranscriptReconcile.differences(aligned)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                compared = true
                blocks = aligned
                diffs = different
                // An apply re-attached after a rotation keeps its spinner.
                loading.visibility = if (applying) View.VISIBLE else View.GONE
                if (restoreReview && different.isNotEmpty()) showReview() else showVerdict()
                setButtonsEnabled(!applying)
            }
        }.apply {
            name = "transcript-compare"
            start()
        }
    }

    private fun modelLabel(key: String?): String =
        key?.takeIf { it.isNotBlank() }
            ?.let { TranscriptionModels.displayName(it) }
            ?: getString(R.string.check_model_unknown)

    private fun showVerdict() {
        verdictPanel.visibility = View.VISIBLE
        reviewPanel.visibility = View.GONE
        val m = meeting ?: return
        val pending = draft ?: return
        val newModel = modelLabel(pending.modelKey)
        val oldModel = modelLabel(m.transcriptModel)
        if (diffs.isEmpty()) {
            verdictView.setText(R.string.check_agree_title)
            detailView.text = getString(R.string.check_agree_body, newModel, oldModel)
            findViewById<View>(R.id.checkReviewButton).visibility = View.GONE
            findViewById<View>(R.id.checkUseNewButton).visibility = View.GONE
            findViewById<MaterialButton>(R.id.checkKeepButton)
                .setText(R.string.check_done)
            return
        }
        verdictView.text = resources.getQuantityString(
            R.plurals.check_differences_title, diffs.size, diffs.size
        )
        detailView.text = getString(
            R.string.check_differences_body, newModel, oldModel, blocks.size
        )
    }

    private fun showReview() {
        verdictPanel.visibility = View.GONE
        reviewPanel.visibility = View.VISIBLE
        reviewHint.setText(R.string.check_review_hint)
        diffList.adapter = DiffAdapter()
        updateApplyLabel()
    }

    private fun updateApplyLabel() {
        applyButton.text = if (acceptFresh.isEmpty()) {
            getString(R.string.check_apply_none)
        } else {
            resources.getQuantityString(
                R.plurals.check_apply_n, acceptFresh.size, acceptFresh.size
            )
        }
    }

    /**
     * Writes the chosen transcript back. The meeting is reloaded first so a
     * check that sat open while something else touched the meeting cannot
     * write a stale copy over it.
     */
    private fun applyChoices(wholesale: Boolean) {
        val current = meeting ?: return
        val pending = draft ?: return
        if (acceptFresh.isEmpty()) {
            discardDraft()
            return
        }
        if (applying) return
        val id = current.id
        // A job for this meeting is still running (or its result is waiting
        // for a screen) — never start a second merge over it.
        if (applyJobs.containsKey(id)) return
        // compare() already moved this exact work off the main thread with a
        // comment saying why, and then this button handler — the one that
        // runs it a second time, plus a merge, a full JSON parse and a full
        // JSON write — did all of it inline. On a three-hour meeting that is
        // seconds of frozen screen at the end of a span-by-span review the
        // user does not want to lose.
        setApplying(true)
        val accepted = acceptFresh.toSet()
        val ordinals = diffs.filter { it.ordinal in accepted }.map { it.startMs }.toSet()
        val job = ApplyJob()
        job.listener = applyListener
        applyJobs[id] = job
        Thread {
            val outcome = applyOnWorker(id, pending, wholesale, ordinals)
            // Delivered to whichever instance of this screen is current —
            // the one that started it may have been rotated away.
            mainHandler.post { job.complete(id, outcome) }
        }.apply {
            name = "transcript-apply"
            start()
        }
    }

    /** An apply's result, on the main thread, for the live instance of this screen. */
    private fun onApplied(outcome: Applied) {
        setApplying(false)
        if (isFinishing || isDestroyed) return
        // Re-attached after a rotation that also lost the draft or the
        // meeting: there is no review left to go back to.
        val canReview = meeting != null && draft != null
        when (outcome) {
            is Applied.Gone -> {
                Toast.makeText(this, R.string.meeting_not_found, Toast.LENGTH_SHORT).show()
                finish()
            }
            is Applied.Nothing -> {
                Toast.makeText(this, R.string.check_nothing_to_apply, Toast.LENGTH_SHORT).show()
                if (!canReview) finish()
            }
            is Applied.SaveFailed -> {
                Toast.makeText(this, R.string.check_save_failed, Toast.LENGTH_LONG).show()
                if (!canReview) finish()
            }
            is Applied.Busy -> {
                Toast.makeText(this, R.string.check_wait_transcribing, Toast.LENGTH_LONG).show()
                if (!canReview) finish()
            }
            is Applied.Done -> {
                meeting = outcome.meeting
                if (outcome.hadSummary) offerRegenerate(outcome.meeting)
                else openMeeting(outcome.meeting.id)
            }
        }
    }

    /**
     * One apply in flight for a meeting. Main-thread only: the worker hands
     * its result over with a post, and each instance of the screen attaches
     * or detaches its [listener] as it is created and destroyed.
     */
    private class ApplyJob {
        var listener: ((Applied) -> Unit)? = null

        /** The result, held while no screen is attached (mid-rotation). */
        var result: Applied? = null

        fun complete(meetingId: String, outcome: Applied) {
            // Removed from the map: the screen was closed for good. The
            // commit itself is already durable; there is no one to tell.
            if (applyJobs[meetingId] !== this) return
            val target = listener
            if (target != null) {
                applyJobs.remove(meetingId)
                target(outcome)
            } else {
                result = outcome
            }
        }
    }

    private sealed interface Applied {
        object Gone : Applied
        object Nothing : Applied
        /** The merge was built but never reached disk — nothing was deleted. */
        object SaveFailed : Applied
        /**
         * Something is writing the meeting's content (a continuation of its
         * transcript, say), whose next save would undo the apply: nothing
         * was written, and the draft is kept.
         */
        object Busy : Applied
        class Done(val meeting: Meeting, val hadSummary: Boolean) : Applied
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        applyButton.isEnabled = enabled
        findViewById<View>(R.id.checkUseNewButton).isEnabled = enabled
        findViewById<View>(R.id.checkKeepButton).isEnabled = enabled
    }

    /**
     * The load / re-align / merge / save cycle, off the main thread.
     *
     * [acceptedStartMs] is precomputed by the caller because it reads
     * `acceptFresh` and `diffs`, which belong to the UI.
     */
    private fun applyOnWorker(
        meetingId: String,
        pending: TranscriptDraft.Draft,
        wholesale: Boolean,
        acceptedStartMs: Set<Long>
    ): Applied {
        // The alignment below takes seconds on a long meeting, far too long
        // to hold the store lock that every other writer shares. So the merge
        // is built outside it and only committed if the transcript it was
        // built from is still the one on disk; otherwise it is rebuilt.
        repeat(APPLY_ATTEMPTS) {
            // Answered before the seconds-long alignment, too; the check
            // under the lock below is the one that matters.
            if (AudioRemoval.isWritingContent(this, meetingId)) return Applied.Busy
            val built = buildMerge(meetingId, pending, wholesale, acceptedStartMs)
            if (built !is Built.Ready) return built.outcome()
            val committed = com.meetily.mobile.data.AtomicJson.exclusive<Applied?> {
                // A run writing this meeting (a continuation of its
                // transcript, a recording, a summary) saves over whatever is
                // applied now: a continuation drops the accepted lines after
                // its seam for its own, and marks the transcript unfinished
                // again. So nothing is written, and the draft stays.
                if (AudioRemoval.isWritingContent(this@TranscriptCheckActivity, meetingId)) {
                    return@exclusive Applied.Busy
                }
                val onDisk = store.load(meetingId) ?: return@exclusive Applied.Gone
                val merge = com.meetily.mobile.data.MeetingMerge
                if (merge.transcriptFingerprint(onDisk.segments) != built.fingerprint) {
                    return@exclusive null
                }
                // Only what the check owns goes onto the reloaded copy: a
                // summary, a rename or a tag saved during the alignment stays.
                onDisk.segments.clear()
                onDisk.segments.addAll(built.segments)
                if (built.modelKey != null) onDisk.transcriptModel = built.modelKey
                // The language goes with a wholesale swap only, like the
                // model: a pass re-run as another language is accepted whole,
                // and a mix of spans keeps what the meeting had.
                if (wholesale) {
                    pending.language?.let { onDisk.language = it }
                    onDisk.translated = pending.translated
                }
                val hadSummary = onDisk.summary.isNotBlank()
                // The summaries kept from other templates too.
                com.meetily.mobile.data.SummaryAlternatives.markStale(onDisk)
                // The check covered the whole recording and the user chose
                // what to keep from it: an unfinished first pass's resume
                // point no longer describes this transcript, and continuing
                // from it would redo lines just accepted.
                onDisk.transcriptResumeMs = null
                // Delete the draft ONLY once the merge is durable. The result
                // was discarded before, so a failed write took the draft with
                // it and reported success: the accepted words existed in
                // neither place.
                if (!store.save(onDisk)) return@exclusive Applied.SaveFailed
                Applied.Done(onDisk, hadSummary)
            }
            if (committed != null) {
                if (committed is Applied.Done) TranscriptDraft.delete(this, meetingId)
                return committed
            }
        }
        return Applied.SaveFailed
    }

    /** A merge built outside the store lock, and what it was built from. */
    private sealed interface Built {
        fun outcome(): Applied
        object Gone : Built {
            override fun outcome(): Applied = Applied.Gone
        }
        object Empty : Built {
            override fun outcome(): Applied = Applied.Nothing
        }
        class Ready(
            val segments: List<com.meetily.mobile.data.TranscriptSegment>,
            val modelKey: String?,
            val fingerprint: Int
        ) : Built {
            override fun outcome(): Applied = Applied.SaveFailed
        }
    }

    private fun buildMerge(
        meetingId: String,
        pending: TranscriptDraft.Draft,
        wholesale: Boolean,
        acceptedStartMs: Set<Long>
    ): Built {
        val fresh = store.load(meetingId) ?: return Built.Gone
        // Re-align against what is on disk now: the stored transcript is the
        // one being replaced, and it may have gained a late line.
        val liveBlocks = TranscriptReconcile.align(
            fresh.segments.toList(), pending.segments, fresh.createdAtMs
        )
        val accepted = if (wholesale) {
            // "Use the new transcript" means better words, never fewer lines.
            // Where the second pass heard nothing — a quiet passage, a chunk
            // it discarded as noise — the stored line stays. Dropping a line
            // is only ever something the user chooses span by span, having
            // seen it.
            TranscriptReconcile.differences(liveBlocks)
                .filter { it.freshIndices.isNotEmpty() }
                .map { it.ordinal }
                .toSet()
        } else {
            // Block ordinals are positions in an alignment that was computed
            // when the screen opened, so they cannot be trusted against a
            // re-alignment. Audio offsets can: they come from the second
            // pass, which has not changed.
            liveBlocks.filter { it.startMs in acceptedStartMs }.map { it.ordinal }.toSet()
        }
        val merged = TranscriptReconcile.merge(
            fresh.segments.toList(), pending.segments, liveBlocks, accepted,
            fresh.createdAtMs
        )
        if (merged.isEmpty()) return Built.Empty
        return Built.Ready(
            segments = merged,
            // Only a wholesale swap can honestly claim one model produced
            // this transcript; a mixed result belongs to neither.
            modelKey = pending.modelKey.takeIf { wholesale && it.isNotBlank() },
            fingerprint = com.meetily.mobile.data.MeetingMerge
                .transcriptFingerprint(fresh.segments)
        )
    }

    /**
     * The summary was written from the old words. Say so and offer to redo
     * it — never silently, and never over an edited summary without asking.
     */
    private fun offerRegenerate(m: Meeting) {
        offeringRegenerate = true
        verdictPanel.visibility = View.GONE
        reviewPanel.visibility = View.GONE
        regenerateDialog?.dismiss()
        regenerateDialog = AlertDialog.Builder(this)
            .setTitle(R.string.check_summary_stale_title)
            .setMessage(R.string.check_summary_stale_body)
            .setPositiveButton(R.string.check_regenerate) { _, _ ->
                openMeeting(m.id, regenerate = true)
            }
            .setNegativeButton(R.string.check_leave_summary) { _, _ ->
                openMeeting(m.id)
            }
            .setOnCancelListener { openMeeting(m.id) }
            .show()
    }

    private fun openMeeting(meetingId: String, regenerate: Boolean = false) {
        offeringRegenerate = false
        startActivity(
            android.content.Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
                .putExtra(MeetingDetailActivity.EXTRA_REGENERATE_SUMMARY, regenerate)
                // CLEAR_TOP without SINGLE_TOP: the meeting screen we came
                // from is holding the transcript we just replaced, so it has
                // to be rebuilt rather than resumed.
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun discardDraft() {
        val id = meeting?.id ?: return finish()
        TranscriptDraft.delete(this, id)
        Toast.makeText(this, R.string.check_kept_current, Toast.LENGTH_SHORT).show()
        finish()
    }

    // --- Audio ---------------------------------------------------------------

    /**
     * Plays the recording from [ms], as the meeting screen's player does:
     * never while a recording runs (it would go out of the speaker into the
     * recording), only with audio focus, and prepared asynchronously. Meeting
     * audio is raw ADTS, which the platform walks frame by frame to find its
     * length, seconds of frozen screen on a multi-hour meeting with prepare().
     * A tap made while it prepares plays once it is ready; the latest wins.
     */
    private fun playFrom(ms: Long) {
        if (RecordingService.isRunning) {
            Toast.makeText(this, R.string.playback_blocked_recording, Toast.LENGTH_SHORT).show()
            return
        }
        val active = player
        if (active != null) {
            if (playerPrepared) startAt(active, ms) else pendingPlayMs = ms
            return
        }
        val name = meeting?.audioFile ?: return
        val file = AudioStore.fileFor(this, name)
        if (!file.exists()) return
        val fresh = MediaPlayer()
        player = fresh
        playerPrepared = false
        pendingPlayMs = ms
        try {
            fresh.setAudioAttributes(playbackAttributes)
            fresh.setDataSource(file.absolutePath)
            fresh.setOnPreparedListener { prepared ->
                if (prepared !== player) return@setOnPreparedListener
                playerPrepared = true
                showPreparing(false)
                val at = pendingPlayMs
                pendingPlayMs = -1L
                // None waits once the screen was left (onPause drops it).
                if (at >= 0L && !isFinishing && !isDestroyed) startAt(prepared, at)
            }
            fresh.setOnCompletionListener { abandonFocus() }
            fresh.setOnErrorListener { failed, _, _ ->
                if (failed === player) releasePlayer()
                true
            }
            fresh.prepareAsync()
            showPreparing(true)
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    /** The loading bar, shown while [player] prepares when nothing else has it up. */
    private fun showPreparing(on: Boolean) {
        if (on) {
            if (loading.visibility == View.VISIBLE) return
            loadingForPlayer = true
            loading.visibility = View.VISIBLE
        } else if (loadingForPlayer) {
            loadingForPlayer = false
            if (!applying) loading.visibility = View.GONE
        }
    }

    private fun startAt(active: MediaPlayer, ms: Long) {
        // Asked again: a recording may have started while it prepared.
        if (RecordingService.isRunning) {
            Toast.makeText(this, R.string.playback_blocked_recording, Toast.LENGTH_SHORT).show()
            return
        }
        // The meeting's own playback (it carries on behind this screen)
        // must not talk over the line being checked. It gives up its focus.
        MeetingPlayback.pause()
        if (!requestFocus()) {
            Toast.makeText(this, R.string.playback_focus_denied, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            active.seekTo(ms.toInt())
            active.start()
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        val held = player
        player = null
        playerPrepared = false
        pendingPlayMs = -1L
        showPreparing(false)
        abandonFocus()
        try {
            held?.release()
        } catch (_: Exception) {
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A call, a recording or another app took the audio: a line under
        // another sound loses words, so it pauses rather than ducks, and
        // does not resume on its own.
        if (change < 0) {
            try {
                player?.takeIf { playerPrepared && it.isPlaying }?.pause()
            } catch (_: Exception) {
            }
            abandonFocus()
        }
    }

    /** Transient: a line is a few seconds, and what it interrupted resumes after. */
    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val manager = applicationContext.getSystemService(AudioManager::class.java) ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focusRequest = request
        return true
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            applicationContext.getSystemService(AudioManager::class.java)
                ?.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
        pendingPlayMs = -1L
        try {
            player?.takeIf { playerPrepared && it.isPlaying }?.pause()
        } catch (_: Exception) {
        }
        abandonFocus()
    }

    override fun onDestroy() {
        // A rotation recreates it from the saved flag; dismissing here only
        // avoids leaking the window. dismiss() does not fire the cancel
        // listener, so this does not count as an answer.
        regenerateDialog?.dismiss()
        regenerateDialog = null
        val id = meetingId
        val job = if (id != null) applyJobs[id] else null
        if (id != null && job != null && job.listener === applyListener) {
            job.listener = null
            // Closed for good (not a rotation): keep nothing for a screen
            // that is not coming back. The worker still commits.
            if (isFinishing) applyJobs.remove(id)
        } else if (id != null && job != null && job.listener == null && reading && isFinishing) {
            // Closed for good while the meeting was still being read, before
            // this screen re-joined the apply a rotated one started: the same.
            applyJobs.remove(id)
        }
        releasePlayer()
        super.onDestroy()
    }

    // --- Difference list -----------------------------------------------------

    private inner class DiffAdapter : RecyclerView.Adapter<DiffHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiffHolder =
            DiffHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_transcript_diff, parent, false)
            )

        override fun getItemCount(): Int = diffs.size

        override fun onBindViewHolder(holder: DiffHolder, position: Int) {
            val block = diffs[position]
            val chosenFresh = block.ordinal in acceptFresh
            holder.time.text = formatOffset(block.startMs)
            holder.currentText.text = block.currentText.ifBlank {
                getString(R.string.check_side_silent)
            }
            holder.freshText.text = block.freshText.ifBlank {
                getString(R.string.check_side_silent)
            }
            holder.freshLabel.text = getString(
                R.string.check_side_new, modelLabel(draft?.modelKey)
            )
            paintChoice(holder.currentCard, !chosenFresh)
            paintChoice(holder.freshCard, chosenFresh)
            holder.currentCard.setOnClickListener {
                acceptFresh.remove(block.ordinal)
                notifyItemChanged(position)
                updateApplyLabel()
            }
            holder.freshCard.setOnClickListener {
                acceptFresh.add(block.ordinal)
                notifyItemChanged(position)
                updateApplyLabel()
            }
            holder.play.setOnClickListener { playFrom(block.startMs) }
        }
    }

    private fun paintChoice(card: MaterialCardView, selected: Boolean) {
        card.strokeColor = MaterialColors.getColor(
            card,
            if (selected) {
                com.google.android.material.R.attr.colorPrimary
            } else {
                com.google.android.material.R.attr.colorOutlineVariant
            }
        )
        card.strokeWidth =
            ((if (selected) 2 else 1) * resources.displayMetrics.density).toInt()
        card.alpha = if (selected) 1f else 0.72f
        // The outline was the only sign of which side will be kept.
        androidx.core.view.ViewCompat.setStateDescription(
            card, getString(if (selected) R.string.a11y_chosen else R.string.a11y_not_chosen)
        )
    }

    private class DiffHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.diffTime)
        val play: ImageButton = view.findViewById(R.id.diffPlay)
        val currentCard: MaterialCardView = view.findViewById(R.id.diffCurrentCard)
        val currentText: TextView = view.findViewById(R.id.diffCurrentText)
        val freshCard: MaterialCardView = view.findViewById(R.id.diffFreshCard)
        val freshLabel: TextView = view.findViewById(R.id.diffFreshLabel)
        val freshText: TextView = view.findViewById(R.id.diffFreshText)
    }

    private fun formatOffset(ms: Long): String {
        val total = ms / 1000
        return String.format(
            java.util.Locale.getDefault(), "%d:%02d", total / 60, total % 60
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(STATE_ACCEPTED, acceptFresh.toIntArray())
        // Rotated again before anything was shown: what the instance before
        // was showing still stands.
        outState.putBoolean(
            STATE_REVIEWING,
            if (compared) reviewPanel.visibility == View.VISIBLE else restoreReview
        )
        outState.putBoolean(STATE_OFFER_REGENERATE, offeringRegenerate || askRegenerate)
    }

    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"

        private const val STATE_ACCEPTED = "accepted_blocks"
        private const val STATE_REVIEWING = "reviewing"
        private const val STATE_OFFER_REGENERATE = "offer_regenerate"

        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Applies in flight, by meeting id. Process-wide so the result of a
         * commit survives the activity being recreated for a rotation.
         * Touched on the main thread only.
         */
        private val applyJobs = HashMap<String, ApplyJob>()

        /** Rebuilds allowed when the transcript moves under an apply. */
        private const val APPLY_ATTEMPTS = 3
    }
}
