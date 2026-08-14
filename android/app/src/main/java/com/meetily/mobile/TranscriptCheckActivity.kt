package com.meetily.mobile

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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

    /** An apply is on a worker; the buttons are disabled but re-tappable. */
    private var applying = false

    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_transcript_check)

        store = MeetingStore(this)
        findViewById<MaterialToolbar>(R.id.checkToolbar)
            .setNavigationOnClickListener { finish() }
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
        val loaded = id?.let { store.load(it) }
        val pending = id?.let { TranscriptDraft.pending(this, it) }
        if (loaded == null || pending == null) {
            Toast.makeText(this, R.string.check_nothing_to_review, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        meeting = loaded
        draft = pending
        // Rotating re-runs the comparison over the same two transcripts, so
        // the block ordinals come out identical and the user's picks can be
        // restored onto them. Losing a long review to a screen rotation would
        // be its own kind of data loss.
        if (savedInstanceState != null) {
            savedInstanceState.getIntArray(STATE_ACCEPTED)?.forEach { acceptFresh.add(it) }
            restoreReview = savedInstanceState.getBoolean(STATE_REVIEWING, false)
        }

        findViewById<View>(R.id.checkReviewButton).setOnClickListener { showReview() }
        findViewById<View>(R.id.checkUseNewButton).setOnClickListener {
            acceptFresh.clear()
            acceptFresh.addAll(diffs.map { it.ordinal })
            applyChoices(wholesale = true)
        }
        findViewById<View>(R.id.checkKeepButton).setOnClickListener { discardDraft() }
        applyButton.setOnClickListener { applyChoices(wholesale = false) }

        compare(loaded, pending)
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
                blocks = aligned
                diffs = different
                loading.visibility = View.GONE
                if (restoreReview && different.isNotEmpty()) showReview() else showVerdict()
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
        applying = true
        // compare() already moved this exact work off the main thread with a
        // comment saying why, and then this button handler — the one that
        // runs it a second time, plus a merge, a full JSON parse and a full
        // JSON write — did all of it inline. On a three-hour meeting that is
        // seconds of frozen screen at the end of a span-by-span review the
        // user does not want to lose.
        loading.visibility = View.VISIBLE
        setButtonsEnabled(false)
        val accepted = acceptFresh.toSet()
        val ordinals = diffs.filter { it.ordinal in accepted }.map { it.startMs }.toSet()
        Thread {
            val outcome = applyOnWorker(current.id, pending, wholesale, ordinals)
            runOnUiThread {
                applying = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading.visibility = View.GONE
                setButtonsEnabled(true)
                when (outcome) {
                    is Applied.Gone -> {
                        Toast.makeText(this, R.string.meeting_not_found, Toast.LENGTH_SHORT)
                            .show()
                        finish()
                    }
                    is Applied.Nothing -> Toast.makeText(
                        this, R.string.check_nothing_to_apply, Toast.LENGTH_SHORT
                    ).show()
                    is Applied.SaveFailed -> Toast.makeText(
                        this, R.string.check_save_failed, Toast.LENGTH_LONG
                    ).show()
                    is Applied.Done -> {
                        meeting = outcome.meeting
                        if (outcome.hadSummary) offerRegenerate(outcome.meeting)
                        else openMeeting(outcome.meeting.id)
                    }
                }
            }
        }.apply {
            name = "transcript-apply"
            start()
        }
    }

    private sealed interface Applied {
        object Gone : Applied
        object Nothing : Applied
        /** The merge was built but never reached disk — nothing was deleted. */
        object SaveFailed : Applied
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
        val fresh = store.load(meetingId) ?: return Applied.Gone
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
        if (merged.isEmpty()) return Applied.Nothing
        fresh.segments.clear()
        fresh.segments.addAll(merged)
        if (wholesale && pending.modelKey.isNotBlank()) {
            // Only a wholesale swap can honestly claim one model produced
            // this transcript; a mixed result belongs to neither.
            fresh.transcriptModel = pending.modelKey
        }
        val hadSummary = fresh.summary.isNotBlank()
        if (hadSummary) fresh.summaryStale = true
        // Delete the draft ONLY once the merge is durable. The result was
        // discarded before, so a failed write took the draft with it and
        // reported success: the accepted words existed in neither place.
        if (!store.save(fresh)) return Applied.SaveFailed
        TranscriptDraft.delete(this, meetingId)
        return Applied.Done(fresh, hadSummary)
    }

    /**
     * The summary was written from the old words. Say so and offer to redo
     * it — never silently, and never over an edited summary without asking.
     */
    private fun offerRegenerate(m: Meeting) {
        AlertDialog.Builder(this)
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

    private fun playFrom(ms: Long) {
        val name = meeting?.audioFile ?: return
        val file = AudioStore.fileFor(this, name)
        if (!file.exists()) return
        var created: MediaPlayer? = null
        try {
            val active = player ?: MediaPlayer().also { fresh ->
                created = fresh
                fresh.setDataSource(file.absolutePath)
                fresh.prepare()
                player = fresh
            }
            active.seekTo(ms.toInt())
            active.start()
        } catch (_: Exception) {
            // Release whichever one we are holding — a player that failed in
            // prepare() was never stored, so `player` alone would leak it.
            (player ?: created)?.let {
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            player = null
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
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
        outState.putBoolean(STATE_REVIEWING, reviewPanel.visibility == View.VISIBLE)
    }

    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"

        private const val STATE_ACCEPTED = "accepted_blocks"
        private const val STATE_REVIEWING = "reviewing"
    }
}
