package com.meetily.mobile

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.Attachment
import com.meetily.mobile.data.AttachmentStore
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.PhotoOcr
import com.meetily.mobile.data.PhotoStore
import com.meetily.mobile.data.QaEntry
import com.meetily.mobile.data.TranscriptSplitter
import com.meetily.mobile.export.DocxExport
import com.meetily.mobile.export.MeetingExporter
import com.meetily.mobile.export.SubtitleExport
import com.meetily.mobile.notes.NotesMarkdown
import com.meetily.mobile.notes.NotesRenderer
import com.meetily.mobile.reminders.Reminders
import com.meetily.mobile.summarize.ActionItems
import com.meetily.mobile.summarize.AiDisclaimers
import com.meetily.mobile.summarize.CustomTemplates
import com.meetily.mobile.summarize.ExtractiveSummarizer
import com.meetily.mobile.summarize.LlmClient
import com.meetily.mobile.summarize.QuotedAiText
import com.meetily.mobile.summarize.TopicChapters
import com.meetily.mobile.summarize.SummaryTemplate
import com.meetily.mobile.summarize.SummaryTemplates
import com.meetily.mobile.whisper.AudioWindowExtractor
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.SherpaEmbedder
import com.meetily.mobile.whisper.VoiceProfileStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MeetingDetailActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var settings: AppSettings
    private var meeting: Meeting? = null

    /**
     * The meeting is read off the main thread (onCreate, onMeetingRead);
     * work that needs it before then waits here. See whenLoaded.
     */
    private val loadGate = LoadGate<Meeting>()
    private var loadModel: MeetingLoadModel? = null

    // The document layout above the transcript lives in one pre-inflated
    // header view inside a ConcatAdapter; transcript lines are recycled so
    // multi-hour meetings scroll smoothly.
    private lateinit var headerView: View
    private lateinit var transcriptAdapter: TranscriptLinesAdapter

    private lateinit var titleView: TextView
    private lateinit var dateView: TextView
    private lateinit var metaView: TextView
    private lateinit var summaryView: TextView
    private lateinit var aiPanel: View
    private lateinit var tagHint: TextView
    private lateinit var transcriptHeading: TextView
    private lateinit var topicsProgress:
        com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var notesInput: EditText
    private lateinit var attendeesInput: EditText
    private lateinit var tagsInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var qaList: LinearLayout
    private lateinit var askInput: EditText
    private lateinit var askSend: MaterialButton
    private lateinit var askRow: View
    private lateinit var askDisabledHint: View

    /** The summary's fixed clinical or legal notice, above it (AiDisclaimers). */
    private lateinit var summaryNoticeView: TextView

    /**
     * The action items' own notice, above them, when another run wrote them
     * than the summary shown (a switch) and the summary's notice does not
     * already cover them.
     */
    private lateinit var actionNoticeView: TextView
    private lateinit var reportSummaryButton: MaterialButton

    /** The styles the meeting has summaries in, above the summary (SummaryAlternatives). */
    private lateinit var summaryVersions: SummaryVersionsBar

    /** The title a model wrote on this screen (maybeAutoTitle), while it stands. */
    private var modelTitle: String? = null

    // --- Summary generation (owned by SummaryService) -----------------------

    private var summaryService: SummaryService? = null
    private var summaryBound = false

    /** Which summary-progress updates are read out; see ProgressAnnouncer. */
    private val summaryAnnouncer = ProgressAnnouncer()

    private val summaryObserver = object : SummaryService.Observer {
        override fun onSummaryProgress(meetingId: String, percent: Int, stage: String) {
            if (isFinishing || isDestroyed) return
            if (meetingId != meeting?.id) return
            // Notes and speaker runs report through the notification and the
            // meeting card on the home screen; only a summary run may take
            // over the summary section here.
            if (SummaryService.currentMode != SummaryService.MODE_SUMMARY) {
                if (SummaryService.currentMode == SummaryService.MODE_SPEAKERS) {
                    setSpeakersBusy(percent, stage)
                }
                return
            }
            showSummarizingUi()
            A11y.setStatusText(summaryView, stage, summaryAnnouncer.shouldAnnounce(percent))
            setSummaryProgress(percent)
        }

        override fun onSummaryDone(meetingId: String, failed: Boolean) {
            if (isFinishing || isDestroyed) return
            if (meetingId != meeting?.id) return
            summaryAnnouncer.reset()
            when (SummaryService.currentMode) {
                SummaryService.MODE_NOTES -> if (!failed) requestSync()
                SummaryService.MODE_SPEAKERS -> {
                    setSpeakersBusy(null, null)
                    // Straight into the review: the user is looking at the
                    // meeting the proposals belong to.
                    if (!failed) offerSpeakerSuggestions()
                }
                else -> {
                    // The summarizing UI comes down whatever the run saved.
                    requestSync(summaryDone = true)
                    // The meeting was left as it was; say why nothing changed.
                    if (failed) {
                        val why = getString(
                            R.string.summary_failed_kept,
                            SummaryService.lastFailure ?: getString(R.string.platform_error_unknown)
                        )
                        if (SummaryService.lastFailureHint ==
                            com.meetily.mobile.security.LocalNetworkAccess.Hint.ALLOW_ACCESS
                        ) {
                            // The fix is a permission: offer the way to it.
                            LocalNetworkUi.showPermissionDialog(this@MeetingDetailActivity, why)
                        } else {
                            Toast.makeText(this@MeetingDetailActivity, why, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    /** Switches the header bar between indeterminate (-1) and a real percent. */
    private fun setSummaryProgress(percent: Int) {
        val wantIndeterminate = percent < 0
        if (progress.isIndeterminate != wantIndeterminate) {
            // Material indicators refuse an in-place mode switch while visible.
            val wasVisible = progress.visibility == View.VISIBLE
            progress.visibility = View.GONE
            progress.isIndeterminate = wantIndeterminate
            if (wasVisible) progress.visibility = View.VISIBLE
        }
        if (!wantIndeterminate) progress.progress = percent
    }

    private val summaryConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(
            name: android.content.ComponentName?,
            binder: android.os.IBinder?
        ) {
            val svc = (binder as? SummaryService.SummaryBinder)?.service ?: return
            summaryService = svc
            svc.addObserver(summaryObserver)
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            summaryService = null
        }
    }

    /**
     * [create] false watches without starting anything: the connection
     * completes whenever the service is next started — by JobGate draining a
     * queued run, say — so a run this screen did not start is still seen.
     */
    private fun bindSummaryService(create: Boolean = true) {
        if (summaryBound) return
        bindService(
            Intent(this, SummaryService::class.java),
            summaryConnection,
            if (create) android.content.Context.BIND_AUTO_CREATE else 0
        )
        summaryBound = true
    }

    /**
     * Reached only by an intent with FLAG_ACTIVITY_SINGLE_TOP while a meeting
     * screen is on top: the playback notification or the lock screen's media
     * controls (PlaybackService). The meeting already shown stays as it is
     * rather than opening again over itself; another meeting opens in a
     * screen of its own, as it would without the flag.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getStringExtra(EXTRA_MEETING_ID) ?: return
        if (id == this.intent.getStringExtra(EXTRA_MEETING_ID)) return
        startActivity(Intent(this, MeetingDetailActivity::class.java).putExtras(intent))
    }

    /** Between onStart and onStop; and whether onStart has run at all. */
    private var started = false
    private var hasStarted = false

    override fun onStart() {
        super.onStart()
        started = true
        hasStarted = true
        // Set up with the player once the meeting is read (setUpClearVoice).
        clearVoiceBar?.attach()
        // Hears what happens to this meeting's player while shown, and shows
        // what happened to it while away (it may have played on, or ended).
        playbackScreenId?.let { MeetingPlayback.attach(it, playbackListener) }
        syncPlayerFromPlayback()
        // Nothing to refresh or attach to until the meeting has been read;
        // onMeetingRead does this itself when the read lands.
        val m = meeting ?: return
        attachOnStart(m)
    }

    /** onStart's work, for a screen that has its meeting. */
    private fun attachOnStart(m: Meeting) {
        // One read, off the main thread, for everything that may have changed
        // while this screen was away: late lines, a summary, enhanced notes,
        // an answer, chapters, an accepted accuracy check. Skipped straight
        // after the screen's own read of the file (onMeetingRead).
        if (skipStartSync) {
            skipStartSync = false
            offerPendingReviews()
        } else {
            requestSync(offerReviews = true)
        }
        if (SummaryService.isRunning && SummaryService.currentMeetingId == m.id) {
            // Coming back (or rotating) mid-generation: restore progress UI
            // and reattach to the run. Only a summary run owns the summary
            // section — notes and speaker runs report elsewhere, and taking
            // it over would blank a summary the user already has.
            if (SummaryService.currentMode == SummaryService.MODE_SUMMARY) {
                showSummarizingUi()
            }
            bindSummaryService()
        } else {
            // A run that ended while this screen was stopped never reached
            // onSummaryDone here, so its progress UI would stay up for good.
            if (progress.visibility == View.VISIBLE) {
                progress.visibility = View.GONE
                renderSummary(m.summary)
                renderActionItems(m)
            }
            bindSummaryService(create = false)
        }
    }

    private fun offerPendingReviews() {
        maybeOfferCheckReview()
        maybeOfferSpeakerSuggestions()
    }

    override fun onStop() {
        started = false
        clearVoiceBar?.detach()
        MeetingPlayback.detach(playbackListener)
        // A screen out of sight has nothing to follow; the chain would
        // otherwise keep waking the main thread every 90 ms.
        playerHandler.removeCallbacks(playerTick)
        // Away for a while during the read, not just rotated: the file may
        // have been read before something saved meanwhile (onMeetingRead).
        if (loadGate.isLoading && !isChangingConfigurations) loadModel?.load?.ownerLeft()
        summaryService?.removeObserver(summaryObserver)
        if (summaryBound) {
            try {
                unbindService(summaryConnection)
            } catch (_: Exception) {
            }
            summaryBound = false
        }
        summaryService = null
        super.onStop()
    }

    /**
     * The newest transcript timestamp this screen has seen on disk. Anything
     * later than this that turns up is a line another writer appended;
     * anything at or before it that is missing from our copy is a line the
     * user deleted, and must stay deleted.
     */
    private var segmentsSeenThroughMs = Long.MIN_VALUE

    /** Saves without erasing a line that landed while this screen was open. */
    /**
     * True when this screen was opened straight off the end of a recording
     * and should offer the follow-on link once. Cleared as soon as it is
     * answered either way, and never persisted.
     */
    private var offerFollowLink = false

    /**
     * What this screen last received from the store: the base of every
     * three-way merge in MeetingStore.syncScreenCopy. A field of [meeting]
     * that differs from it is an edit made here; every other field is simply
     * whatever is stored, however it got there.
     */
    private var syncBase: Meeting? = null

    /** A save-and-refresh running on the worker, and the copy it was taken from. */
    private class PendingSync(
        val ours: Meeting,
        val task: java.util.concurrent.FutureTask<Meeting?>,
        val summaryDone: Boolean,
        val offerReviews: Boolean
    )

    private var pendingSync: PendingSync? = null

    /** Asked for while [pendingSync] was running; served once it lands. */
    private var syncWanted = false
    private var summaryDoneWanted = false
    private var offerReviewsWanted = false

    /** The screen has just read the file, so the next onStart has nothing to fetch. */
    private var skipStartSync = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Writes this screen's edits back, and takes in whatever else changed.
     *
     * The screen used to write its whole copy, so anything saved by someone
     * else while it was open — a summary from a queued run, a Q&A answer or
     * chapters finished after a rotation, an accepted accuracy check — was
     * put back the way this screen first loaded it by the next incidental
     * save: a highlight, a tag, a star. Now only the fields changed here are
     * written (MeetingMerge.mergeScreenEdits) and everything else is adopted
     * from disk.
     *
     * The load, merge and write run on a worker: on a long meeting that is a
     * megabyte of JSON per tap, under the lock every other writer shares.
     * Calls made while a save is running collapse into one follow-up save.
     */
    private fun persist(m: Meeting) {
        if (m === meeting) requestSync()
    }

    private fun requestSync(
        summaryDone: Boolean = false,
        offerReviews: Boolean = false
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestSync(summaryDone, offerReviews) }
            return
        }
        if (summaryDone) summaryDoneWanted = true
        if (offerReviews) offerReviewsWanted = true
        if (pendingSync != null) {
            syncWanted = true
            return
        }
        startSync()
    }

    private fun startSync() {
        val m = meeting ?: return
        val base = syncBase ?: return
        // The worker reads a copy: the live one keeps changing under it.
        val ours = com.meetily.mobile.data.MeetingMerge.snapshot(m)
        val mark = segmentsSeenThroughMs
        val store = this.store
        val pending = PendingSync(
            ours = ours,
            task = java.util.concurrent.FutureTask(
                java.util.concurrent.Callable<Meeting?> {
                    store.syncScreenCopy(base, ours, mark)
                }
            ),
            summaryDone = summaryDoneWanted,
            offerReviews = offerReviewsWanted
        )
        syncWanted = false
        summaryDoneWanted = false
        offerReviewsWanted = false
        pendingSync = pending
        SYNC_EXECUTOR.execute {
            pending.task.run()
            mainHandler.post { completeSync(pending) }
        }
    }

    private fun renderMeta(m: Meeting) {
        // Per segment rather than over one joined copy of the transcript.
        val wordCount = m.segments.sumOf {
            com.meetily.mobile.summarize.MeetingStats.countWords(it.text)
        }
        metaView.text = getString(R.string.detail_meta, m.segments.size, wordCount)
        LanguagePicker.bindChip(headerView.findViewById(R.id.detailLanguageChip), m) {
            showLanguageOptions()
        }
        bindResumeCard(m)
    }

    /** The Continue card: an unfinished transcript with its audio still here. */
    private fun bindResumeCard(m: Meeting) {
        ContinueTranscription.bindCard(
            headerView.findViewById(R.id.resume_card),
            headerView.findViewById(R.id.resume_card_text),
            headerView.findViewById(R.id.resume_card_button),
            m
        ) { continueTranscription() }
    }

    /** "Continue transcription": the card, the menu item and the home card's option. */
    private fun continueTranscription() {
        val m = meeting ?: return
        // One sync, waited for, before anything is decided from this copy.
        // It writes the edits made here (the pass reads the stored lines
        // before the seam) and takes in what is stored. This screen does not
        // watch transcription runs: one started from here can be cancelled
        // from its notification, stop at its time limit or finish while the
        // screen stays open, and nothing syncs the screen then. Its copy
        // would ask about, and refuse every answer over, a point the store
        // no longer holds (ContinueTranscription.refused), or start a
        // finished transcript over from 0:00 without asking.
        requestSync()
        flushSync()
        if (m.transcriptResumeMs == null) {
            // Finished, or ended (audio removed, an accuracy check applied),
            // since this screen last showed it. adoptSynced has bound the
            // card again and asked for the menu again.
            Toast.makeText(this, R.string.resume_nothing_left, Toast.LENGTH_LONG).show()
            return
        }
        ContinueTranscription.start(this, m) {
            if (isFinishing || isDestroyed) return@start
            headerView.findViewById<View>(R.id.resume_card).visibility = View.GONE
            invalidateOptionsMenu()
        }
    }

    /** The language chip: transcribe the saved audio again as a chosen language. */
    private fun showLanguageOptions() {
        val m = meeting ?: return
        if (audioFileOrNull() == null) {
            Toast.makeText(this, R.string.no_audio_kept, Toast.LENGTH_SHORT).show()
            return
        }
        // A finished pass is waiting to be reviewed; a new one would replace it unseen.
        if (com.meetily.mobile.data.TranscriptDraft.isPending(this, m.id)) {
            openCheckReview(m.id)
            return
        }
        LanguagePicker.retranscribe(this, m, checkAudioMs(m)) { modelKey, language ->
            startCheck(m, modelKey, language)
        }
    }

    private fun completeSync(pending: PendingSync) {
        if (pendingSync !== pending) return
        pendingSync = null
        val merged = try {
            pending.task.get()
        } catch (_: Exception) {
            null
        }
        val summaryChanged = merged != null && adoptSynced(pending.ours, merged)
        if (pending.summaryDone) finishSummaryUi(reveal = summaryChanged)
        // A screen being rotated away leaves the offer to the new one (attachOnStart).
        if (pending.offerReviews && !isFinishing && !isDestroyed && !isChangingConfigurations) {
            offerPendingReviews()
        }
        if (syncWanted || summaryDoneWanted || offerReviewsWanted) startSync()
    }

    /**
     * Waits for every requested save to land. For handing the meeting to
     * something that reads it from disk — a service, another screen — which
     * must see the edits made here.
     */
    private fun flushSync() {
        while (true) {
            val pending = pendingSync ?: return
            completeSync(pending)
        }
    }

    /**
     * Takes what the store now holds into this screen, field by field, and
     * re-renders what moved. A field edited here since [ours] was taken is
     * left alone: the next sync writes it. Returns true when the summary
     * text changed.
     */
    private fun adoptSynced(ours: Meeting, merged: Meeting): Boolean {
        val m = meeting ?: return false
        val live = !isFinishing && !isDestroyed
        // Becomes the next base; its fields are overridden below wherever
        // this screen deliberately kept its own value.
        val base = merged
        var transcriptChanged = false
        var summaryChanged = false
        var summaryTextChanged = false

        if (m.title == ours.title && m.title != merged.title) {
            m.title = merged.title
            if (live) titleView.text = m.title
        }
        if (m.segments == ours.segments && m.segments != merged.segments) {
            m.segments.clear()
            m.segments.addAll(merged.segments)
            transcriptChanged = true
        }
        if (m.segments == merged.segments) {
            // Never lowered: a line deleted here must not come back as "late".
            segmentsSeenThroughMs = maxOf(
                segmentsSeenThroughMs,
                com.meetily.mobile.data.MeetingMerge.highWaterMs(m.segments)
            )
        }
        if (m.chapters == ours.chapters && m.chapters != merged.chapters) {
            m.chapters.clear()
            m.chapters.addAll(merged.chapters)
            transcriptChanged = true
        }
        // The summary shown, its stale mark, template and model (which decide
        // its notice; see renderSummary) and the summaries kept beside it
        // move as one, keeping a switch or deletion made here while the save
        // ran (SummaryAlternatives.adopt).
        val summaryBefore = m.summary
        if (com.meetily.mobile.data.SummaryAlternatives.adopt(m, ours, merged)) {
            summaryChanged = true
            if (m.summary != summaryBefore) summaryTextChanged = true
        }
        val itemsEditedHere = m.actionItems != ours.actionItems
        if (!itemsEditedHere && m.actionItems != merged.actionItems) {
            m.actionItems = merged.actionItems.toMutableList()
            summaryChanged = true
        }
        // Which run wrote the items (it decides their notice) goes with the
        // items: kept with this screen's own list while that waits to be
        // saved, taken with the stored one otherwise.
        if (!itemsEditedHere &&
            (m.actionItemsTemplate != merged.actionItemsTemplate ||
                m.actionItemsModel != merged.actionItemsModel)
        ) {
            m.actionItemsTemplate = merged.actionItemsTemplate
            m.actionItemsModel = merged.actionItemsModel
            summaryChanged = true
        }
        if (m.notes == ours.notes && m.notesOriginal == ours.notesOriginal &&
            (m.notes != merged.notes || m.notesOriginal != merged.notesOriginal)
        ) {
            // Don't clobber an in-progress manual edit; keeping the base at
            // this screen's value means the next save does not write it.
            if (headerView.findViewById<View>(R.id.notesEditMode).visibility == View.VISIBLE) {
                base.notes = m.notes
                base.notesOriginal = m.notesOriginal
            } else {
                m.notes = merged.notes
                m.notesOriginal = merged.notesOriginal
                if (live) {
                    notesInput.setText(m.notes)
                    setNotesMode(viewMode = m.notes.isNotBlank())
                }
            }
        }
        if (m.attendees == ours.attendees && m.attendees != merged.attendees) {
            if (Meeting.parseAttendees(attendeesInput.text.toString()) != m.attendees) {
                base.attendees = m.attendees.toMutableList()
            } else {
                m.attendees = merged.attendees.toMutableList()
                if (live) attendeesInput.setText(m.attendeesText())
            }
        }
        if (m.tags == ours.tags && m.tags != merged.tags) {
            if (Meeting.parseAttendees(tagsInput.text.toString()) != m.tags) {
                base.tags = m.tags.toMutableList()
            } else {
                m.tags = merged.tags.toMutableList()
                if (live) tagsInput.setText(m.tags.joinToString(", "))
            }
        }
        if (m.qa == ours.qa && m.qa != merged.qa) {
            m.qa.clear()
            m.qa.addAll(merged.qa)
            // A question in flight has its own row in the list; the answer's
            // UI hop settles it.
            if (live && askSend.isEnabled) renderQaHistory(m)
        }
        if (m.photos == ours.photos && m.photos != merged.photos) {
            m.photos = merged.photos.toMutableList()
            if (live) renderPhotos(m)
        }
        if (m.photoTexts == ours.photoTexts && m.photoTexts != merged.photoTexts) {
            m.photoTexts.clear()
            m.photoTexts.putAll(merged.photoTexts)
        }
        if (m.attachmentsList == ours.attachmentsList &&
            m.attachmentsList != merged.attachmentsList
        ) {
            m.attachmentsList.clear()
            m.attachmentsList.addAll(merged.attachmentsList)
            if (live) renderAttachments(m)
        }
        if (m.audioFile == ours.audioFile && m.audioFile != merged.audioFile) {
            m.audioFile = merged.audioFile
            if (live) applyTabState()
            // The audio removed elsewhere (Storage, multi-select): the
            // language chip offers re-transcription only with audio to run.
            if (live && !transcriptChanged) renderMeta(m)
            if (live) clearVoiceBar?.render()
        }
        // A copy finished by ClearVoiceService, or deleted elsewhere.
        if (m.clearVoiceFile == ours.clearVoiceFile && m.clearVoiceFile != merged.clearVoiceFile) {
            m.clearVoiceFile = merged.clearVoiceFile
            if (live) clearVoiceBar?.render()
        }
        if (m.starred == ours.starred && m.starred != merged.starred) {
            m.starred = merged.starred
            if (live) invalidateOptionsMenu()
        }
        if (m.transcriptModel == ours.transcriptModel &&
            m.transcriptModel != merged.transcriptModel
        ) {
            m.transcriptModel = merged.transcriptModel
        }
        if (m.transcriptResumeMs == ours.transcriptResumeMs &&
            m.transcriptResumeMs != merged.transcriptResumeMs
        ) {
            m.transcriptResumeMs = merged.transcriptResumeMs
            // The Continue card and menu item follow where the words stop.
            if (live && !transcriptChanged) renderMeta(m)
            if (live) invalidateOptionsMenu()
        }
        if (m.language == ours.language && m.translated == ours.translated &&
            (m.language != merged.language || m.translated != merged.translated)
        ) {
            m.language = merged.language
            m.translated = merged.translated
            if (live && !transcriptChanged) renderMeta(m)
        }
        if (m.followsEvent == ours.followsEvent && m.followsEvent != merged.followsEvent) {
            m.followsEvent = merged.followsEvent
            if (live && !transcriptChanged) renderFollowsFrom(m)
        }
        syncBase = base
        // Hidden by hand once a run was started from it; a run that then
        // stopped without saving anything (cancelled while matching voices,
        // refused, failed before its first batch) changes nothing on disk,
        // so the card is bound again with every sync, not only on a change.
        if (live && !transcriptChanged) bindResumeCard(m)

        if (live && transcriptChanged) {
            // Includes a line folded in by a save from here: without this it
            // was on disk but missing from the list, the follow timeline and
            // the stats until something else re-rendered.
            renderMeta(m)
            renderTranscript(m)
            renderStats(m)
        }
        // While a run shows its progress here, finishSummaryUi renders the
        // result when it ends; rendering now would clear the progress text.
        if (live && summaryChanged && progress.visibility != View.VISIBLE) {
            renderSummary(m.summary)
            renderActionItems(m)
        }
        return summaryTextChanged
    }

    /**
     * Ends the summarizing UI after a run, whatever the run did. It used to
     * be cleared only when the stored summary had changed, so a run that
     * saved nothing new — an unplug stop, a failed load, an identical
     * extractive result — left "Summarizing…" up until the screen was rebuilt.
     */
    private fun finishSummaryUi(reveal: Boolean) {
        if (isFinishing || isDestroyed) return
        val m = meeting ?: return
        progress.visibility = View.GONE
        renderSummary(m.summary)
        renderActionItems(m)
        if (reveal) revealSummarySections()
    }

    /**
     * The JPEG the camera app is currently filling in.
     *
     * Saved across re-creation: this is the one photo-capturing screen the
     * manifest does not orientation-lock, and while the camera app is in
     * front this Activity is backgrounded and can be reclaimed. The result
     * still arrives, but with the field back to null the callback took the
     * failure branch, deleted nothing, and left the photo the user had just
     * taken orphaned in filesDir/photos with no error shown.
     */
    private var pendingPhotoFile: File? = null

    /** The camera's answer, held while the meeting is read; saved with the state. */
    private var pendingPhotoResult: Boolean? = null
    private val takePicture =
        registerForActivityResult(TakePictureWithGrant()) { success ->
            onPhotoResult(success)
        }

    /**
     * Records the photo just taken once the meeting is in hand. A result
     * replayed into a re-created screen arrives at its onStart, before the
     * meeting has been read again; it found no meeting, took the failure
     * branch and deleted the photo the user had just taken.
     */
    private fun onPhotoResult(success: Boolean) {
        pendingPhotoResult = success
        whenLoaded(onFailed = { recordPhoto(null) }) { m -> recordPhoto(m) }
    }

    private fun recordPhoto(m: Meeting?) {
        val success = pendingPhotoResult ?: return
        pendingPhotoResult = null
        val file = pendingPhotoFile
        pendingPhotoFile = null
        if (success && file != null && file.exists() && file.length() > 0 && m != null) {
            m.photos.add(file.name)
            persist(m)
            renderPhotos(m)
            ocrPhoto(m, file.name)
        } else {
            file?.delete()
        }
    }

    /**
     * Leaving for good before the read landed: a photo the camera handed
     * back is still recorded, straight onto the stored meeting, as runCopy
     * does for a file whose screen went away. Its text is read on the next
     * visit (backfillPhotoOcr).
     */
    private fun recordPhotoUnread() {
        val success = pendingPhotoResult ?: return
        pendingPhotoResult = null
        val file = pendingPhotoFile ?: return
        pendingPhotoFile = null
        val meetingId = intent.getStringExtra(EXTRA_MEETING_ID)
        val store = this.store
        SYNC_EXECUTOR.execute {
            val kept = success && meetingId != null && file.length() > 0 &&
                store.mutate(meetingId) { it.photos.add(file.name) }
            if (!kept) file.delete()
        }
    }

    // Like the camera's, these results can reach a re-created screen before
    // its meeting has been read, so they wait for it.
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { picked -> whenLoaded { importPhoto(picked) } }
        }
    private val pickAttachment =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { picked -> whenLoaded { importAttachment(picked) } }
        }
    private val exportMd =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            uri?.let { target -> whenLoaded { writeExport(target, isPdf = false) } }
        }
    private val exportPdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            uri?.let { target -> whenLoaded { writeExport(target, isPdf = true) } }
        }
    private val exportIcs =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
            uri?.let { target -> whenLoaded { writeActionItemsIcs(target) } }
        }
    private val exportDocx =
        registerForActivityResult(ActivityResultContracts.CreateDocument(DocxExport.MIME_TYPE)) { uri ->
            uri?.let { target -> whenLoaded { writeDocx(target) } }
        }
    private val exportSrt =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
            uri?.let { target -> whenLoaded { writeSubtitles(target, vtt = false) } }
        }
    private val exportVtt =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { uri ->
            uri?.let { target -> whenLoaded { writeSubtitles(target, vtt = true) } }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else: the activity-result registry replays a pending
        // camera result once this screen restarts, and the callback needs the
        // file back to know what it is looking at.
        savedInstanceState?.getString(STATE_PENDING_PHOTO)?.let {
            pendingPhotoFile = File(it)
        }
        ThemeManager.apply(this)
        setContentView(R.layout.activity_detail)
        EdgeToEdge.setUp(this) {
            root(bottom = false)
            // The player bar is gone for meetings without audio; whichever
            // of the two is on the bottom edge takes the navigation bar.
            listAbove(R.id.detailRecycler, R.id.playerBar)
        }

        store = MeetingStore(this)
        settings = AppSettings(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.detailToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = ""
        toolbar.setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.detailRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.itemAnimator = null
        headerView = LayoutInflater.from(this)
            .inflate(R.layout.detail_header, recycler, false)
        transcriptAdapter = TranscriptLinesAdapter(
            onClick = { index -> assignSpeaker(index) },
            onLongClick = { index -> toggleHighlight(index) },
            onWordTap = { index, wordMs ->
                val base = meeting?.segments?.getOrNull(index)?.audioMs
                if (base != null) playFrom(base + wordMs)
            },
            lineActions = { index -> lineAccessibility(index) }
        )
        recycler.adapter = ConcatAdapter(StaticViewAdapter(headerView), transcriptAdapter)
        transcriptAdapter.wordHighlightColor = themeColor(
            com.google.android.material.R.attr.colorPrimaryContainer
        )
        findBar = MeetingFindBar(this, transcriptAdapter, findHost)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                // A drag means the user is reading or tagging somewhere else.
                // Stop moving the list under them; the highlight keeps going,
                // and the follow button re-arms the scroll in one tap.
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    followScrolling = false
                }
            }
        })

        titleView = headerView.findViewById(R.id.detailTitle)
        dateView = headerView.findViewById(R.id.detailDate)
        metaView = headerView.findViewById(R.id.detailMeta)
        summaryView = headerView.findViewById(R.id.detailSummary)
        aiPanel = headerView.findViewById(R.id.aiPanel)
        tagHint = headerView.findViewById(R.id.tagHint)
        transcriptHeading = headerView.findViewById(R.id.transcriptHeading)
        topicsProgress = headerView.findViewById(R.id.topicsProgress)
        notesInput = headerView.findViewById(R.id.detailNotes)
        attendeesInput = headerView.findViewById(R.id.detailAttendees)
        tagsInput = headerView.findViewById(R.id.detailTags)
        progress = headerView.findViewById(R.id.summaryProgress)
        qaList = headerView.findViewById(R.id.qaList)
        askInput = headerView.findViewById(R.id.askInput)
        askSend = headerView.findViewById(R.id.askSend)
        askRow = headerView.findViewById(R.id.askRow)
        askDisabledHint = headerView.findViewById(R.id.askDisabledHint)
        // Added in code beside the existing header views: a notice banner
        // above the summary and one above its action items, and "Report AI
        // output" after the items.
        summaryNoticeView = DisclaimerViews.addBannerBefore(summaryView)
        actionNoticeView = DisclaimerViews.addBannerBefore(headerView.findViewById(R.id.actionList))
        reportSummaryButton = AiReport.addButton(headerView.findViewById(R.id.actionList)) {
            meeting?.let { current -> AiReport.choose(this, summarySubjects(current)) }
        }
        reportSummaryButton.visibility = View.GONE
        DisclaimerViews.copyWithNotices(summaryView) { summaryNotices() }
        summaryVersions = SummaryVersionsBar(this, progress, object : SummaryVersionsBar.Host {
            override fun meeting(): Meeting? = meeting

            override fun summariesChanged(m: Meeting) {
                if (m !== meeting) return
                persist(m)
                renderSummary(m.summary)
            }

            override fun newSummary() = chooseTemplateAndSummarize()
        })

        val id = intent.getStringExtra(EXTRA_MEETING_ID)
        if (id == null) {
            Toast.makeText(this, R.string.meeting_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Counted for as long as this screen lives (onDestroy), so a player
        // left paused goes with the last screen on its meeting.
        playbackScreenId = id
        MeetingPlayback.screenOpened(id)
        // Follows a passage being removed from this meeting (PassageUi).
        passage.attach(id)
        // The title and date go up at once when they are known without the
        // file. Everything else waits for the read (onMeetingRead): on a
        // long meeting that is a megabyte of JSON and its word timings, which
        // used to be parsed here on the main thread.
        knownHeader(savedInstanceState)?.let { (title, createdAtMs) ->
            showHeader(title, createdAtMs)
        }

        headerView.findViewById<View>(R.id.generateButton).setOnClickListener {
            chooseTemplateAndSummarize()
        }
        askSend.setOnClickListener { sendQuestion() }
        headerView.findViewById<View>(R.id.addPhotoCamera).setOnClickListener { capturePhoto() }
        headerView.findViewById<View>(R.id.addPhotoGallery).setOnClickListener {
            try {
                pickImage.launch("image/*")
            } catch (_: Exception) {
                Toast.makeText(this, R.string.photo_attach_failed, Toast.LENGTH_SHORT).show()
            }
        }
        headerView.findViewById<View>(R.id.addFileButton).setOnClickListener {
            try {
                pickAttachment.launch("*/*")
            } catch (_: Exception) {
                Toast.makeText(this, R.string.attach_failed, Toast.LENGTH_SHORT).show()
            }
        }
        setUpNotesEditor()

        // Before the first render, not after: renderTranscript ->
        // renderFollowsFrom is the only thing that makes the offer card
        // visible, and nothing re-runs it on this path. Setting the flag
        // afterwards left the card hidden on arrival from a finished
        // recording — the one moment it exists for — and then popped it up
        // out of context on the next unrelated re-render.
        if (savedInstanceState == null) {
            if (intent?.getBooleanExtra(EXTRA_OFFER_FOLLOW, false) == true) {
                offerFollowLink = true
                // Removed so a rotation cannot re-show a card already dismissed.
                intent.removeExtra(EXTRA_OFFER_FOLLOW)
            }
        } else {
            // Carried in the state until answered: a rotation while the
            // meeting is read must not lose the offer, and one after "Not
            // now" must not bring it back.
            offerFollowLink = savedInstanceState.getBoolean(STATE_OFFER_FOLLOW)
        }
        requestedActionPending = savedInstanceState?.getBoolean(STATE_ACTION_PENDING) ?: true
        playingClearVoice = savedInstanceState?.getBoolean(STATE_CLEAR_VOICE) ?: false
        restoreView = savedInstanceState?.getBundle(STATE_VIEW)
        // A search hit opens at its line once the meeting is read; the find
        // bar comes back as it was left.
        momentPending = savedInstanceState?.getBoolean(STATE_MOMENT_PENDING)
            ?: (intent.hasExtra(EXTRA_SEGMENT) || intent.hasExtra(EXTRA_QUERY))
        if (momentPending) whenLoaded { landOnMoment(it) }
        // Kept until the bar takes it, so a screen re-created again before
        // the read lands passes it on (onSaveInstanceState) instead of
        // dropping a find, or a landing still to come.
        pendingFindState = savedInstanceState?.getBundle(STATE_FIND)
        pendingFindState?.let { find ->
            whenLoaded {
                pendingFindState = null
                findBar.restore(find)
            }
        }
        if (savedInstanceState?.containsKey(STATE_PHOTO_RESULT) == true) {
            // The camera answered a screen that went before its meeting was
            // read, so the photo is still to be recorded.
            onPhotoResult(savedInstanceState.getBoolean(STATE_PHOTO_RESULT))
        }
        showLoading()
        // Last: a read that finished while no screen was attached is handed
        // over (onMeetingRead) before this returns.
        val model = ViewModelProvider(this, MeetingLoadModel.FACTORY)[MeetingLoadModel::class.java]
        loadModel = model
        model.open(
            id, store, SYNC_EXECUTOR,
            wantHeader = headerCreatedAtMs < 0,
            listener = loadListener
        )
    }

    /**
     * The home screen's long-press sheet and the accuracy check hand work
     * over as an intent extra so every heavy action keeps its one
     * implementation here, with its existing service and progress wiring.
     * Posted so the screen is laid out before a dialog or sheet appears.
     */
    private fun runRequestedAction() {
        // Once per screen, however often it is re-created: the original
        // intent comes back with a re-created screen, so without this the
        // rename dialog would reopen (or a summary start twice) on every
        // configuration change. The flag travels in the saved state, so a
        // rotation before the read landed still runs it.
        if (!requestedActionPending) return
        headerView.post {
            // Taken only here, by a screen still alive to run it. A
            // re-creation queued ahead of this post destroys this screen
            // first; the next one must then still find the action pending in
            // the saved state and the extra on the intent the two share.
            if (isFinishing || isDestroyed || !requestedActionPending) return@post
            requestedActionPending = false
            if (intent.getBooleanExtra(EXTRA_REGENERATE_SUMMARY, false)) {
                intent.removeExtra(EXTRA_REGENERATE_SUMMARY)
                chooseTemplateAndSummarize()
                return@post
            }
            val action = intent.getStringExtra(EXTRA_ACTION) ?: return@post
            intent.removeExtra(EXTRA_ACTION)
            when (action) {
                ACTION_RENAME -> renameMeeting()
                ACTION_SUMMARIZE -> chooseTemplateAndSummarize()
                ACTION_TOPICS -> topicsAction()
                ACTION_CHECK_ACCURACY -> confirmCheckAccuracy()
                ACTION_SHARE -> shareMeeting()
                ACTION_CONTINUE_TRANSCRIPTION -> continueTranscription()
            }
        }
    }

    // --- Opening the meeting ---------------------------------------------------

    /**
     * Runs [action] with the meeting once it has been read and rendered, and
     * at once when it already has. The meeting is read off the main thread,
     * so this is the way in for anything that needs it straight after
     * onCreate: a jump to a segment, a find bar, an edit to apply, an
     * activity result. Main thread only; actions run in the order given.
     * When the meeting cannot be read the screen shows its error and
     * finishes, and only [onFailed] runs.
     */
    private fun whenLoaded(onFailed: (() -> Unit)? = null, action: (Meeting) -> Unit) {
        loadGate.whenLoaded(action, onFailed)
    }

    /** True until the intent's action (runRequestedAction) has run; saved with the state. */
    private var requestedActionPending = false

    /** Tab, chapters and scroll from the screen before a re-creation, put back once read. */
    private var restoreView: Bundle? = null

    /** The date the header shows, or -1 while it shows nothing yet. */
    private var headerCreatedAtMs = -1L

    private val loadListener = object : RetainedLoad.Listener<Meeting, Meeting> {
        override fun onHeader(header: Meeting) {
            if (meeting == null) showHeader(header.title, header.createdAtMs)
        }

        override fun onResult(result: Meeting?) {
            onMeetingRead(result)
        }
    }

    /**
     * The read has landed. Renders everything onCreate used to render with
     * the meeting in hand, does onStart's part, then runs the intent's action
     * and whatever waited (whenLoaded). A missing or unreadable meeting takes
     * the error path the synchronous read had.
     */
    private fun onMeetingRead(read: Meeting?) {
        if (isFinishing || isDestroyed || !loadGate.isLoading) return
        val m = read?.takeIf { it.id == intent.getStringExtra(EXTRA_MEETING_ID) }
        if (m == null) {
            hideLoading()
            loadGate.failed()
            Toast.makeText(this, R.string.meeting_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        meeting = m
        hideLoading()
        showHeader(m.title, m.createdAtMs)
        segmentsSeenThroughMs = com.meetily.mobile.data.MeetingMerge.highWaterMs(m.segments)
        syncBase = com.meetily.mobile.data.MeetingMerge.snapshot(m)
        renderMeta(m)

        val view = restoreView
        restoreView = null
        if (view != null) restoreTabAndChapters(view)
        setUpTabs()
        // Before the first renderTranscript: a restored Audio tab starts
        // preparing the player there.
        setUpPlayer()
        renderSummary(m.summary)
        renderActionItems(m)
        renderTranscript(m)
        renderQaHistory(m)
        renderPhotos(m)
        backfillPhotoOcr(m)
        renderAttachments(m)
        notesInput.setText(m.notes)
        setNotesMode(viewMode = m.notes.isNotBlank())
        attendeesInput.setText(m.attendeesText())
        tagsInput.setText(m.tags.joinToString(", "))
        renderStats(m)
        if (view != null) restoreScroll(view)
        invalidateOptionsMenu()
        maybeAutoTitle(m)

        // What onStart could not do without the meeting. Its refresh is
        // skipped only when this read is as new as the visit: a screen left
        // and come back to while the file was read may hold a copy from
        // before a save made meanwhile (a queued summary finishing, enhanced
        // notes, an accepted accuracy check), which nothing else fetches.
        val fresh = loadModel?.load?.ownerLeftDuringRead != true
        when {
            started -> {
                skipStartSync = fresh
                attachOnStart(m)
            }
            // The first onStart is still to come, and finds the meeting.
            !hasStarted -> skipStartSync = fresh
            // Stopped since: the next onStart reads again, as after any absence.
            else -> skipStartSync = false
        }
        runRequestedAction()
        loadGate.loaded(m)
    }

    /**
     * A title and date to show before the file is read: kept by the screen
     * before a re-creation, or handed over by the list that opened this one
     * (EXTRA_TITLE). Null when the read has to supply them.
     */
    private fun knownHeader(saved: Bundle?): Pair<String, Long>? {
        saved?.getString(STATE_TITLE)?.let { return it to saved.getLong(STATE_CREATED_AT) }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return null
        val createdAtMs = intent.getLongExtra(EXTRA_CREATED_AT_MS, -1L)
        return if (createdAtMs >= 0) title to createdAtMs else null
    }

    private fun showHeader(title: String, createdAtMs: Long) {
        titleView.text = title
        dateView.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(createdAtMs))
        headerCreatedAtMs = createdAtMs
    }

    /** The header's views below the date while the meeting is read, with their visibility. */
    private var hiddenWhileLoading: List<Pair<View, Int>> = emptyList()
    private var loadingRow: View? = null
    private var loadingLabel: TextView? = null

    /**
     * Shows the progress row once a read has taken long enough to notice, so
     * a short meeting opens without a flash of it, and has a screen reader
     * say so politely.
     */
    private val revealLoading = Runnable {
        val row = loadingRow ?: return@Runnable
        val label = loadingLabel ?: return@Runnable
        row.visibility = View.VISIBLE
        A11y.setStatusText(label, getString(R.string.meetload_opening), announce = true)
    }

    /**
     * Holds the screen at its title and date while the meeting is read: the
     * rest of the header is hidden and a progress row stands in for it. The
     * attendee and tag fields beside the date go too, since anything typed
     * there would be overwritten by the meeting's own when the read lands.
     * The row keeps its space while it waits to appear, so nothing on screen
     * moves when it does.
     */
    private fun showLoading() {
        val header = headerView as? LinearLayout ?: return
        var dateRow: View = dateView
        while (dateRow.parent !== header) dateRow = dateRow.parent as? View ?: return
        val below = header.indexOfChild(dateRow) + 1
        val hide = (below until header.childCount).map { header.getChildAt(it) } +
            listOf<View>(attendeesInput, tagsInput)
        hiddenWhileLoading = hide.map { it to it.visibility }
        for (view in hide) view.visibility = View.GONE
        val label = TextView(this).apply {
            textSize = 14f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = true
            // The label says what is happening; the bar alone says nothing.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, dp(8))
            visibility = View.INVISIBLE
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
        }
        header.addView(
            row, below,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        loadingRow = row
        loadingLabel = label
        mainHandler.postDelayed(revealLoading, LOADING_REVEAL_MS)
    }

    private fun hideLoading() {
        mainHandler.removeCallbacks(revealLoading)
        loadingLabel?.let { A11y.endAnnouncement(it) }
        loadingRow?.let { row -> (row.parent as? android.view.ViewGroup)?.removeView(row) }
        loadingRow = null
        loadingLabel = null
        for ((view, visibility) in hiddenWhileLoading) view.visibility = visibility
        hiddenWhileLoading = emptyList()
    }

    /** Tab, collapsed chapters and scroll position, for the screen after a re-creation. */
    private fun viewState(): Bundle? {
        // Re-created again before the read landed: pass on what came in.
        if (meeting == null) return restoreView
        val recycler = findViewById<RecyclerView>(R.id.detailRecycler) ?: return null
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return null
        val position = manager.findFirstVisibleItemPosition()
        val top = manager.findViewByPosition(position)?.top ?: recycler.paddingTop
        return Bundle().apply {
            putString(VIEW_TAB, tab.name)
            putBoolean(VIEW_CHAPTERS_ARRANGED, collapsedApplied)
            putIntArray(VIEW_COLLAPSED, transcriptAdapter.collapsedChapters())
            putInt(VIEW_POSITION, position)
            putInt(VIEW_OFFSET, top - recycler.paddingTop)
        }
    }

    /** Before the first render: the tab, and the chapters as they were left. */
    private fun restoreTabAndChapters(state: Bundle) {
        tab = Tab.entries.firstOrNull { it.name == state.getString(VIEW_TAB) } ?: Tab.SUMMARY
        if (state.getBoolean(VIEW_CHAPTERS_ARRANGED)) {
            // Not collapsed all over again on the way back to the transcript.
            collapsedApplied = true
            transcriptAdapter.setCollapsedChapters(
                state.getIntArray(VIEW_COLLAPSED) ?: IntArray(0)
            )
        }
    }

    /** After the render: back to where the list was. */
    private fun restoreScroll(state: Bundle) {
        val position = state.getInt(VIEW_POSITION, RecyclerView.NO_POSITION)
        if (position < 0) return
        val recycler = findViewById<RecyclerView>(R.id.detailRecycler) ?: return
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        manager.scrollToPositionWithOffset(position, state.getInt(VIEW_OFFSET))
    }

    /** Conversation insights: talk-time bars + monologue/questions/pace line. */
    private fun renderStats(m: Meeting) {
        val stats = com.meetily.mobile.summarize.MeetingStats.compute(m.segments)
            ?: return
        val header = headerView.findViewById<TextView>(R.id.statsHeader)
        val list = headerView.findViewById<LinearLayout>(R.id.statsList)
        val footer = headerView.findViewById<TextView>(R.id.statsFooter)
        header.visibility = View.VISIBLE
        list.visibility = View.VISIBLE
        footer.visibility = View.VISIBLE
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (share in stats.shares.take(8)) {
            val row = inflater.inflate(R.layout.item_stat_speaker, list, false)
            row.findViewById<TextView>(R.id.statSpeakerName).text =
                if (share.name == com.meetily.mobile.summarize.MeetingStats.UNATTRIBUTED) {
                    getString(R.string.stats_unattributed)
                } else {
                    share.name
                }
            row.findViewById<TextView>(R.id.statSpeakerDetail).text = getString(
                R.string.stats_share_detail,
                share.percent, formatClock(share.ms.toInt()), share.turns
            )
            val bar = row.findViewById<
                com.google.android.material.progressindicator.LinearProgressIndicator
            >(R.id.statSpeakerBar)
            bar.progress = share.percent
            list.addView(row)
        }
        footer.text = buildString {
            val monologue = stats.longestMonologueSpeaker
            if (monologue != null) {
                append(
                    getString(
                        R.string.stats_monologue,
                        monologue, formatClock(stats.longestMonologueMs.toInt())
                    )
                )
                append("   ")
            }
            append(getString(R.string.stats_questions, stats.questionCount))
            append("   ")
            append(getString(R.string.stats_pace, stats.wordsPerMinute))
        }
    }

    override fun onResume() {
        super.onResume()
        syncNotesButtons()
        val llmReady = settings.useLlm && settings.llmConfigured
        askRow.visibility = if (llmReady) View.VISIBLE else View.GONE
        askDisabledHint.visibility = if (llmReady) View.GONE else View.VISIBLE
    }

    private fun renderSummary(summary: String) {
        // The transcript changed under this summary (an accepted accuracy
        // check). Say so rather than quietly presenting stale wording.
        headerView.findViewById<View>(R.id.summaryStaleHint).visibility =
            if (summary.isNotBlank() && meeting?.summaryStale == true) {
                View.VISIBLE
            } else {
                View.GONE
            }
        if (summary.isBlank()) {
            summaryView.visibility = View.GONE
            aiPanel.visibility = View.VISIBLE
        } else {
            // Templates emit "## Heading" markup; render it rather than show
            // the marks. m.summary keeps the raw text for share, export and
            // anything handing the summary back to a model.
            // The view carried the progress line; its last announcement must
            // not carry over and read the whole summary aloud.
            A11y.endAnnouncement(summaryView)
            summaryView.text = com.meetily.mobile.notes.MarkdownSpans.render(summary)
            summaryView.visibility = View.VISIBLE
            aiPanel.visibility = View.GONE
        }
        DisclaimerViews.show(
            summaryNoticeView, if (summary.isBlank()) emptyList() else summaryNotices()
        )
        // A switch changes which notices the summary's banner already shows.
        renderActionNotice()
        // An extractive summary is sentences picked from the transcript, not
        // generated text, so it is not offered for report (QuotedAiText);
        // action items a model wrote in another run still are.
        reportSummaryButton.visibility =
            if (meeting?.let {
                    QuotedAiText.modelWrote(summary, it.summaryModel) ||
                        QuotedAiText.itemsFromAnotherModelRun(it)
                } == true
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        summaryVersions.render()
    }

    /** The shown summary's fixed notices, by the template and model that wrote it. */
    private fun summaryNotices(): List<String> {
        val m = meeting ?: return emptyList()
        return AiDisclaimers.texts(this, AiDisclaimers.summaryCategories(m))
    }

    /**
     * The notices of everything an export carries: the summary shown and
     * the action items, which after a switch another run wrote.
     */
    private fun meetingNotices(m: Meeting): List<String> =
        AiDisclaimers.texts(this, AiDisclaimers.categories(m))

    /**
     * The action items' notices the summary's banner does not already show:
     * after a switch, a clinical run's items under plain minutes.
     */
    private fun renderActionNotice() {
        val m = meeting
        val notices = if (m == null || m.actionItems.isEmpty()) {
            emptyList()
        } else {
            val shown = if (m.summary.isBlank()) emptySet() else AiDisclaimers.summaryCategories(m)
            AiDisclaimers.texts(this, AiDisclaimers.actionItemCategories(m) - shown)
        }
        DisclaimerViews.show(actionNoticeView, notices)
    }

    private fun actionItemsText(m: Meeting): String = buildString {
        for (item in m.actionItems) {
            append("- ").append(item.task)
            if (!item.owner.isNullOrBlank()) append(" (").append(item.owner).append(')')
            append('\n')
        }
    }

    /**
     * The summary and its action items as reportable texts: one when the
     * same run wrote both, two when the items came from another run (a
     * switch since), each under the run that wrote it. Only what a model
     * wrote; empty when nothing.
     */
    private fun summarySubjects(m: Meeting): List<AiReport.Subject> {
        val out = ArrayList<AiReport.Subject>()
        val separate = com.meetily.mobile.data.SummaryAlternatives.actionItemsFromAnotherRun(m)
        if (QuotedAiText.modelWrote(m.summary, m.summaryModel)) {
            val text = buildString {
                append(m.summary.trim())
                if (!separate && m.actionItems.isNotEmpty()) {
                    append("\n\nAction items:\n").append(actionItemsText(m))
                }
            }
            out.add(
                AiReport.Subject(
                    label = getString(
                        if (separate) R.string.summaries_report_summary else R.string.aisafety_subject_summary
                    ),
                    surface = "meeting_summary",
                    text = text,
                    templateKey = m.summaryTemplate ?: QuotedAiText.UNRECORDED,
                    modelKey = m.summaryModel
                )
            )
        }
        if (separate && QuotedAiText.itemsFromAnotherModelRun(m)) {
            val run = com.meetily.mobile.data.SummaryAlternatives.actionItemsRun(m)
            out.add(
                AiReport.Subject(
                    label = getString(R.string.summaries_report_items),
                    surface = "meeting_action_items",
                    text = actionItemsText(m),
                    templateKey = run.template ?: QuotedAiText.UNRECORDED,
                    modelKey = run.model
                )
            )
        }
        return out
    }

    private fun answerSubject(question: String, answer: String) = AiReport.Subject(
        label = getString(R.string.aisafety_subject_answer, question.take(48)),
        surface = "meeting_answer",
        text = "Q: $question\nA: $answer"
    )

    /** Overflow "Report AI output": everything generated on this meeting. */
    private fun reportFromMenu() {
        val m = meeting ?: return
        val subjects = mutableListOf<AiReport.Subject>()
        subjects.addAll(summarySubjects(m))
        // notesOriginal is only kept once the notes were AI-enhanced.
        if (m.notesOriginal.isNotEmpty() && m.notes.isNotBlank()) {
            subjects.add(
                AiReport.Subject(
                    getString(R.string.aisafety_subject_notes), "meeting_notes_enhanced", m.notes
                )
            )
        }
        for (entry in m.qa) {
            if (entry.answer.isNotBlank()) subjects.add(answerSubject(entry.question, entry.answer))
        }
        if (m.chapters.isNotEmpty()) {
            subjects.add(
                AiReport.Subject(
                    getString(R.string.aisafety_subject_chapters), "meeting_chapters",
                    m.chapters.joinToString("\n") { it.title }
                )
            )
        }
        // A typed or default title is not AI output. Offered beside other
        // generated text, since an earlier run's model may have written it,
        // and alone only when a model wrote it on this screen.
        if (subjects.isNotEmpty() || (modelTitle != null && m.title == modelTitle)) {
            subjects.add(
                AiReport.Subject(
                    getString(R.string.aisafety_subject_title), "meeting_title", m.title
                )
            )
        }
        AiReport.choose(this, subjects)
    }

    private fun renderTranscript(m: Meeting) {
        renderFollowsFrom(m)
        if (tab == Tab.AUDIO) renderAudioTab(m)
        tagHint.text = getString(
            when {
                m.segments.isNotEmpty() -> R.string.tap_to_tag_hint
                // Recorded as audio only: say how to get the words, not that
                // none were captured. With no model on the phone that starts
                // with a download; once one is there, the manual check is the
                // route, since nothing transcribes an earlier meeting by itself.
                m.audioFile != null ->
                    if (com.meetily.mobile.whisper.TranscriptionModels
                            .downloadedKeys(this).isEmpty()
                    ) {
                        R.string.privacy_transcript_needs_model
                    } else {
                        R.string.privacy_transcript_ready
                    }
                else -> R.string.no_transcript
            }
        )
        // Transcript lines belong to the Transcript tab only — and so does
        // the heading that introduces them. On the Summary tab the lines are
        // submitted empty, so a heading left behind stands over nothing.
        tagHint.visibility = if (onTranscriptTab) View.VISIBLE else View.GONE
        transcriptHeading.visibility = if (onTranscriptTab) View.VISIBLE else View.GONE
        if (topicsRunning || speakersBusy) {
            // The status outlives a transcript re-render (tab switch, late
            // lines landing) — it is the only sign work is happening.
            tagHint.text = getString(
                if (speakersBusy) R.string.suggest_analyzing else R.string.topics_working
            )
            tagHint.visibility = View.VISIBLE
            topicsProgress.visibility = View.VISIBLE
        } else {
            topicsProgress.visibility = View.GONE
        }
        transcriptAdapter.textSizeSp = when (settings.transcriptTextSize) {
            "small" -> 13.5f
            "large" -> 17f
            else -> 15f
        }
        // Set before submit(): chapter spans are computed during the rebuild.
        transcriptAdapter.meetingStartMs = m.createdAtMs
        // Chapters start collapsed the first time a meeting is opened, so a
        // chaptered transcript reads as an outline you drill into.
        val collapseFirst = onTranscriptTab && !collapsedApplied && m.chapters.isNotEmpty()
        if (collapseFirst) collapsedApplied = true
        transcriptAdapter.submit(
            if (onTranscriptTab) m.segments else emptyList(),
            if (onTranscriptTab) m.chapters else emptyList(),
            collapseAllInitially = collapseFirst
        )
        // Editing, splitting or accepting a second-pass check all renumber
        // segments; a stale timeline would follow onto the wrong line.
        rebuildFollowTimeline()
        if (onTranscriptTab && followPlayback) {
            readyPlayer?.let { updateFollow(it.currentPosition.toLong()) }
        }
        // An open find bar recounts when the lines changed (an edit, a split,
        // late lines, a replace); its marks follow at once as lines rebind.
        findBar.transcriptChanged()
    }

    /** Chapters are auto-collapsed once per screen, not on every re-render. */
    private var collapsedApplied = false

    // --- Summary | Transcript segmented tabs --------------------------------

    private enum class Tab { SUMMARY, TRANSCRIPT, AUDIO }

    private var tab = Tab.SUMMARY

    /**
     * Derived rather than stored: the transcript list, chapter collapsing and
     * playback-follow all key off "am I on the transcript", and there is only
     * one place that can now be true.
     */
    private val onTranscriptTab: Boolean get() = tab == Tab.TRANSCRIPT

    private fun setUpTabs() {
        headerView.findViewById<View>(R.id.tabSummary).setOnClickListener {
            switchTab(Tab.SUMMARY)
        }
        headerView.findViewById<View>(R.id.tabTranscript).setOnClickListener {
            switchTab(Tab.TRANSCRIPT)
        }
        headerView.findViewById<View>(R.id.tabAudio).setOnClickListener {
            switchTab(Tab.AUDIO)
        }
        applyTabState()
    }

    private fun switchTab(next: Tab) {
        if (tab == next) return
        tab = next
        applyTabState()
        meeting?.let { renderTranscript(it) }
    }

    private fun applyTabState() {
        val tabSummary = headerView.findViewById<TextView>(R.id.tabSummary)
        val tabTranscript = headerView.findViewById<TextView>(R.id.tabTranscript)
        val tabAudio = headerView.findViewById<TextView>(R.id.tabAudio)
        val summaryContent = headerView.findViewById<View>(R.id.summaryTabContent)
        val audioContent = headerView.findViewById<View>(R.id.audioTabContent)
        val active = themeColor(com.google.android.material.R.attr.colorOnPrimary)
        val inactive = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        // A meeting that kept no audio has nothing for this tab to show, and
        // an empty third tab reads as a broken feature rather than an absent
        // one.
        val hasAudio = meeting?.audioFile?.isNotBlank() == true
        tabAudio.visibility = if (hasAudio) View.VISIBLE else View.GONE
        if (!hasAudio && tab == Tab.AUDIO) tab = Tab.SUMMARY

        for ((view, isActive) in listOf(
            tabSummary to (tab == Tab.SUMMARY),
            tabTranscript to (tab == Tab.TRANSCRIPT),
            tabAudio to (tab == Tab.AUDIO)
        )) {
            view.setBackgroundResource(if (isActive) R.drawable.bg_tab_active else 0)
            view.setTextColor(if (isActive) active else inactive)
            // Read as "selected"; the pill alone said which tab was open.
            view.isSelected = isActive
            // Weight has to be set here alongside the pill and the colour.
            // Summary carried android:textStyle="bold" in the layout, so it
            // rendered bold on every tab — two tabs looked selected at once,
            // and which one actually was depended on spotting the pill.
            view.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        }
        summaryContent.visibility = if (tab == Tab.SUMMARY) View.VISIBLE else View.GONE
        audioContent.visibility = if (tab == Tab.AUDIO) View.VISIBLE else View.GONE
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    /** The design's staggered fade-up after a summary (re)generates. */
    private fun revealSummarySections() {
        val sections = listOf<View>(
            summaryView,
            headerView.findViewById(R.id.actionsHeader),
            headerView.findViewById(R.id.actionList)
        )
        val rise = 14f * resources.displayMetrics.density
        var delay = 0L
        for (view in sections) {
            if (view.visibility != View.VISIBLE) continue
            view.alpha = 0f
            view.translationY = rise
            view.animate().alpha(1f).translationY(0f)
                .setDuration(450).setStartDelay(delay).start()
            delay += 250
        }
    }

    private fun toggleHighlight(index: Int) {
        val m = meeting ?: return
        if (index !in m.segments.indices) return
        m.segments[index] = m.segments[index].copy(
            highlighted = !m.segments[index].highlighted
        )
        persist(m)
        transcriptAdapter.update(index, m.segments[index])
    }

    /**
     * A line's tap opens "Who said this?" (which also holds play, edit, split
     * and share clip) and its long-press toggles the highlight — neither says
     * so to a screen reader. This names both gestures and offers the dialog's
     * line actions directly, using the same functions the dialog calls.
     */
    private fun lineAccessibility(index: Int): TranscriptLineActions {
        val segment = meeting?.segments?.getOrNull(index)
        val audioMs = segment?.audioMs
        // The player bar is shown exactly when the meeting has playable
        // audio; checking the file itself here would stat it on every bind.
        val playable = audioMs != null && ::playerBar.isInitialized &&
            playerBar.visibility == View.VISIBLE
        val actions = mutableListOf<A11y.Action>()
        if (playable) {
            actions.add(A11y.Action(getString(R.string.a11y_line_play)) { playFrom(audioMs) })
        }
        actions.add(A11y.Action(getString(R.string.edit_text_action)) { editSegmentText(index) })
        actions.add(A11y.Action(getString(R.string.split_action)) { showSplitDialog(index) })
        actions.add(A11y.Action(getString(R.string.replace_line_action)) { replace.fixWord(index) })
        if (playable) {
            actions.add(A11y.Action(getString(R.string.share_clip_action)) { shareClip(index) })
        }
        if (audioMs != null) {
            actions.add(A11y.Action(getString(R.string.passage_line_action)) { passage.open(index) })
        }
        return TranscriptLineActions(
            clickLabel = getString(R.string.a11y_line_options),
            longClickLabel = getString(
                if (segment?.highlighted == true) R.string.a11y_line_unhighlight
                else R.string.a11y_line_highlight
            ),
            actions = actions
        )
    }

    private fun assignSpeaker(index: Int) {
        val m = meeting ?: return
        if (index !in m.segments.indices) return
        saveEdits()
        val segment = m.segments[index]
        val clusterId = if (segment.speaker.isNullOrBlank()) segment.clusterId else null
        val clusterLabel = clusterId?.let { getString(R.string.speaker_cluster_label, it) }
        val addAttendee: (String) -> Unit = { name ->
            if (m.attendees.none { it.equals(name, ignoreCase = true) }) {
                m.attendees.add(name)
                attendeesInput.setText(m.attendeesText())
            }
        }
        val audioMs = segment.audioMs
        SpeakerPicker.show(
            this, m.attendees, segment.speaker,
            clusterLabel = clusterLabel,
            onPlayFrom = if (audioMs != null && audioFileOrNull() != null) {
                { playFrom(audioMs) }
            } else null,
            onEditText = { editSegmentText(index) },
            onSplit = { showSplitDialog(index) },
            onShareClip = if (audioMs != null && audioFileOrNull() != null) {
                { shareClip(index) }
            } else null,
            onFixWord = { replace.fixWord(index) },
            onRemovePassage = if (audioMs != null) {
                { passage.open(index) }
            } else null,
            onRenameCluster = if (clusterId != null) {
                { name ->
                    val tagged = mutableListOf<Int>()
                    for (i in m.segments.indices) {
                        val s = m.segments[i]
                        if (s.clusterId == clusterId && s.speaker.isNullOrBlank()) {
                            m.segments[i] = s.copy(speaker = name)
                            tagged.add(i)
                        }
                    }
                    addAttendee(name)
                    persist(m)
                    renderTranscript(m)
                    promptVoiceprintUpdate(name, tagged)
                }
            } else null
        ) { name ->
            if (index !in m.segments.indices) return@show
            m.segments[index] = m.segments[index].copy(speaker = name)
            if (!name.isNullOrBlank()) addAttendee(name)
            persist(m)
            transcriptAdapter.update(index, m.segments[index])
            if (!name.isNullOrBlank()) {
                promptVoiceprintUpdate(name, listOf(index))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Closed before its meeting was read: the read would otherwise go on
        // to the end on the worker the next screen opened reads on.
        if (isFinishing) loadModel?.close()
        saveEdits()
        // The next screen for this meeting (a rotation, or the meeting opened
        // again) reads the file on SYNC_EXECUTOR (MeetingLoadModel.open). A
        // save asked for while another was running is only marked
        // (syncWanted) and queued by completeSync, which would run after that
        // read was queued: the read would miss the edit, count as fresh, and
        // the next edit there would write over it. Queue it now. A rotation
        // waits only for the running save, since SYNC_EXECUTOR runs in order;
        // a close waits for every save to land.
        if (isFinishing) {
            flushSync()
        } else if (isChangingConfigurations && syncWanted) {
            pendingSync?.let { completeSync(it) }
        }
        // Playback itself carries on (MeetingPlayback and its service): with
        // the screen off, behind another app, or from the notification. But
        // a tap made while the player was still preparing must not start
        // playback behind a screen the user has left.
        pendingPlayerAction = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(revealLoading)
        mainHandler.removeCallbacks(endFlash)
        findBar.release()
        passage.detach()
        // The model outlives a rotation; it must not hold on to this screen.
        loadModel?.load?.detach(loadListener)
        // A rotation hands an unrecorded photo on in the saved state.
        if (isFinishing && !isChangingConfigurations && loadGate.isLoading) recordPhotoUnread()
        playerHandler.removeCallbacks(playerTick)
        pendingPlayerAction = null
        MeetingPlayback.detach(playbackListener)
        // A rotation keeps the player; closing the screen keeps it only
        // while it plays (it carries on in the background).
        playbackScreenId?.let { MeetingPlayback.screenClosed(it, isChangingConfigurations) }
        super.onDestroy()
    }

    private fun saveEdits() {
        val m = meeting ?: return
        val newNotes = notesInput.text.toString()
        val newAttendees = Meeting.parseAttendees(attendeesInput.text.toString())
        val newTags = Meeting.parseAttendees(tagsInput.text.toString())
        if (newNotes != m.notes || newAttendees != m.attendees || newTags != m.tags) {
            m.notes = newNotes
            m.attendees = newAttendees
            m.tags = newTags
            persist(m)
        }
    }

    private fun maybeAutoTitle(m: Meeting) {
        if (!settings.autoTitleFromTranscript) return
        val defaultTitle = getString(
            R.string.default_meeting_title,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(m.createdAtMs))
        )
        if (m.title != defaultTitle || m.transcriptText().isBlank()) return

        val offline = ExtractiveSummarizer.titleFor(
            m.transcriptText(),
            com.meetily.mobile.data.MeetingLanguage.englishHeuristics(m.transcriptLanguage())
        )
        if (offline.isNotBlank()) {
            m.title = offline
            titleView.text = offline
            persist(m)
        }
        if (settings.autoTitleAllowed) {
            val baseUrl = settings.llmBaseUrl
            val apiKey = settings.llmApiKey
            val model = settings.llmModel
            val localOnly = settings.localOnlyLlm
            val transcript = m.transcriptTextWithSpeakers()
            val notes = m.notes
            val languageClause = com.meetily.mobile.summarize.OutputLanguage.clauseFor(
                com.meetily.mobile.summarize.OutputLanguage.Kind.TITLE,
                settings.summaryLanguage, m.language, m.translated
            )
            // The title this run is refining. A generation takes seconds to
            // minutes and the field is editable throughout, so without this
            // the result lands on top of a rename the user made meanwhile —
            // their words replaced by the model's, with no undo.
            val startedFrom = m.title
            Thread {
                try {
                    val generated = LlmClient.title(
                        baseUrl, apiKey, model, localOnly, transcript, notes, languageClause
                    )
                    if (generated.isNotBlank()) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (m.title != startedFrom) return@runOnUiThread
                            m.title = generated
                            modelTitle = generated
                            titleView.text = generated
                            persist(m)
                        }
                    }
                } catch (_: Exception) {
                    // Offline title already applied; a failed refinement is fine.
                }
            }.start()
        }
    }

    private fun renderActionItems(m: Meeting) {
        val header = headerView.findViewById<View>(R.id.actionsHeader)
        val list = headerView.findViewById<LinearLayout>(R.id.actionList)
        list.removeAllViews()
        val visible = m.actionItems.isNotEmpty()
        header.visibility = if (visible) View.VISIBLE else View.GONE
        list.visibility = if (visible) View.VISIBLE else View.GONE
        renderActionNotice()
        if (!visible) return
        val inflater = LayoutInflater.from(this)
        for ((index, item) in m.actionItems.withIndex()) {
            val row = inflater.inflate(R.layout.item_action, list, false)
            val check = row.findViewById<CheckBox>(R.id.actionCheck)
            val text = row.findViewById<TextView>(R.id.actionText)
            val owner = row.findViewById<TextView>(R.id.actionOwner)
            text.text = item.task
            applyStrike(text, item.done)
            check.isChecked = item.done
            val remindAt = item.remindAtMs
            val ownerLine = buildString {
                if (!item.owner.isNullOrBlank()) append(item.owner)
                if (remindAt != null && !item.done) {
                    if (isNotEmpty()) append(" · ")
                    append("⏰ ")
                    append(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(remindAt))
                    )
                }
            }
            if (ownerLine.isEmpty()) {
                owner.visibility = View.GONE
            } else {
                owner.text = ownerLine
                owner.visibility = View.VISIBLE
            }
            check.setOnCheckedChangeListener { _, checked ->
                if (index in m.actionItems.indices) {
                    val updated = m.actionItems[index].copy(done = checked)
                    m.actionItems[index] = updated
                    applyStrike(text, checked)
                    persist(m)
                    val at = updated.remindAtMs
                    if (at != null) {
                        if (checked) {
                            Reminders.cancelActionItem(this, m.id, updated.task)
                        } else if (at > System.currentTimeMillis()) {
                            Reminders.scheduleActionItem(this, m.id, updated.task, at)
                        }
                    }
                }
            }
            row.setOnLongClickListener {
                showActionItemOptions(index)
                true
            }
            A11y.labelLongClick(row, getString(R.string.a11y_action_item_options))
            A11y.setActions(
                row,
                listOfNotNull(
                    A11y.Action(
                        getString(
                            if (remindAt != null) R.string.reminder_change
                            else R.string.reminder_set
                        )
                    ) { pickReminderTime(index) },
                    if (remindAt != null) {
                        A11y.Action(getString(R.string.reminder_cancel)) { clearReminder(index) }
                    } else null,
                    A11y.Action(getString(R.string.a11y_remove_action_item)) {
                        confirmRemoveAction(index)
                    }
                )
            )
            list.addView(row)
        }
    }

    private fun applyStrike(view: TextView, done: Boolean) {
        view.paintFlags = if (done) {
            view.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            view.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }

    private fun showActionItemOptions(index: Int) {
        val m = meeting ?: return
        val item = m.actionItems.getOrNull(index) ?: return
        val options = mutableListOf(
            getString(
                if (item.remindAtMs != null) R.string.reminder_change
                else R.string.reminder_set
            )
        )
        val hasReminder = item.remindAtMs != null
        if (hasReminder) options.add(getString(R.string.reminder_cancel))
        options.add(getString(R.string.remove_action_item))
        AlertDialog.Builder(this)
            .setTitle(item.task.take(120))
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> pickReminderTime(index)
                    hasReminder && which == 1 -> clearReminder(index)
                    else -> confirmRemoveAction(index)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    private fun pickReminderTime(index: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71
            )
        }
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.HOUR_OF_DAY, 1)
            set(java.util.Calendar.MINUTE, 0)
        }
        android.app.DatePickerDialog(
            this,
            { _, year, month, day ->
                android.app.TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        cal.set(year, month, day, hour, minute, 0)
                        cal.set(java.util.Calendar.MILLISECOND, 0)
                        setReminder(index, cal.timeInMillis)
                    },
                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(this)
                ).show()
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setReminder(index: Int, atMs: Long) {
        val m = meeting ?: return
        if (index !in m.actionItems.indices) return
        if (atMs <= System.currentTimeMillis()) {
            Toast.makeText(this, R.string.reminder_past, Toast.LENGTH_SHORT).show()
            return
        }
        val item = m.actionItems[index].copy(remindAtMs = atMs, done = false)
        m.actionItems[index] = item
        persist(m)
        Reminders.scheduleActionItem(this, m.id, item.task, atMs)
        renderActionItems(m)
        Toast.makeText(
            this,
            getString(
                R.string.reminder_set_toast,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(atMs))
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun clearReminder(index: Int) {
        val m = meeting ?: return
        if (index !in m.actionItems.indices) return
        val item = m.actionItems[index]
        Reminders.cancelActionItem(this, m.id, item.task)
        m.actionItems[index] = item.copy(remindAtMs = null)
        persist(m)
        renderActionItems(m)
    }

    private fun confirmRemoveAction(index: Int) {
        val m = meeting ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_action_item)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (index in m.actionItems.indices) {
                    val removed = m.actionItems.removeAt(index)
                    if (removed.remindAtMs != null) {
                        Reminders.cancelActionItem(this, m.id, removed.task)
                    }
                    persist(m)
                    renderActionItems(m)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderPhotos(m: Meeting) {
        val strip = headerView.findViewById<LinearLayout>(R.id.photoStrip)
        val scroll = headerView.findViewById<View>(R.id.photoScroll)
        strip.removeAllViews()
        scroll.visibility = if (m.photos.isEmpty()) View.GONE else View.VISIBLE
        if (m.photos.isEmpty()) return
        val density = resources.displayMetrics.density
        val sizePx = (84 * density).toInt()
        val marginPx = (8 * density).toInt()
        val radiusPx = 14 * density
        // A photo whose file is gone was skipped before; still skip it here,
        // without touching its pixels on the main thread. Filtered first so
        // each thumbnail can say which of the remaining photos it is.
        val shown = m.photos.toList().filter { PhotoStore.fileFor(this, it).exists() }
        for ((position, name) in shown.withIndex()) {
            val file = PhotoStore.fileFor(this, name)
            val image = ShapeableImageView(this).apply {
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(radiusPx)
                    .build()
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = marginPx
                }
                // "Photos" on every thumbnail told a screen-reader user
                // nothing about which one had focus.
                contentDescription = getString(R.string.a11y_photo_n, position + 1, shown.size)
                setOnClickListener { showPhoto(file) }
                setOnLongClickListener {
                    confirmDeletePhoto(name)
                    true
                }
                A11y.labelClick(this, getString(R.string.a11y_view_photo))
                A11y.labelLongClick(this, getString(R.string.a11y_remove_photo))
            }
            strip.addView(image)
            // Decoded off the main thread and cached; set only while this
            // view still shows this photo and the screen is alive.
            PhotoThumbLoader.loadThumb(this, image, file, sizePx) {
                image.visibility = View.GONE
            }
        }
    }

    /** Set while the viewer's bitmap decodes, so repeated taps open one dialog. */
    private var photoViewerLoading = false

    private fun showPhoto(file: File) {
        if (photoViewerLoading) return
        photoViewerLoading = true
        PhotoThumbLoader.loadFull(this, file) { bitmap ->
            photoViewerLoading = false
            if (bitmap == null) return@loadFull
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                contentDescription = getString(R.string.a11y_photo)
            }
            AlertDialog.Builder(this)
                .setView(image)
                .setPositiveButton(android.R.string.ok, null)
                .showSecure()
        }
    }

    private fun confirmDeletePhoto(name: String) {
        val m = meeting ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_photo_title)
            .setPositiveButton(R.string.delete) { _, _ ->
                m.photos.remove(name)
                m.photoTexts.remove(name)
                PhotoThumbLoader.evict(PhotoStore.fileFor(this, name))
                PhotoStore.delete(this, name)
                persist(m)
                renderPhotos(m)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoFile?.let { outState.putString(STATE_PENDING_PHOTO, it.absolutePath) }
        pendingPhotoResult?.let { outState.putBoolean(STATE_PHOTO_RESULT, it) }
        outState.putBoolean(STATE_ACTION_PENDING, requestedActionPending)
        outState.putBoolean(STATE_OFFER_FOLLOW, offerFollowLink)
        outState.putBoolean(STATE_CLEAR_VOICE, playingClearVoice)
        // The next screen shows these at once, before its own read.
        if (headerCreatedAtMs >= 0) {
            outState.putString(STATE_TITLE, meeting?.title ?: titleView.text.toString())
            outState.putLong(STATE_CREATED_AT, headerCreatedAtMs)
        }
        viewState()?.let { outState.putBundle(STATE_VIEW, it) }
        outState.putBoolean(STATE_MOMENT_PENDING, momentPending)
        (findBar.saveState() ?: pendingFindState)?.let { outState.putBundle(STATE_FIND, it) }
    }

    private fun capturePhoto() {
        val m = meeting ?: return
        val file = PhotoStore.newPhotoFile(this, m.id)
        pendingPhotoFile = file
        try {
            takePicture.launch(PhotoStore.uriFor(this, file))
        } catch (_: Exception) {
            pendingPhotoFile = null
            file.delete()
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importPhoto(uri: Uri) {
        val m = meeting ?: return
        val file = PhotoStore.newPhotoFile(this, m.id)
        // Off the main thread: a picked photo can live in a cloud provider
        // that downloads it while it is read, which froze the screen for as
        // long as that took.
        runCopy(
            copy = {
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: throw RuntimeException("cannot open image")
            },
            file = file,
            failedText = R.string.photo_attach_failed,
            record = { it.photos.add(file.name) }
        ) {
            renderPhotos(m)
            ocrPhoto(m, file.name)
        }
    }

    /**
     * Runs [copy] on a worker, then [record]s the file on the meeting and
     * calls [done] back on the main thread. A copy that throws deletes
     * whatever part of [file] it had written, rather than leaving it orphaned
     * in filesDir. If the screen went away meanwhile (a rotation), the file
     * is still recorded, straight onto the stored meeting.
     */
    private fun runCopy(
        copy: () -> Unit,
        file: File,
        failedText: Int,
        record: (Meeting) -> Unit,
        done: () -> Unit
    ) {
        val meetingId = meeting?.id ?: return
        val store = this.store
        Toast.makeText(this, R.string.file_copying, Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try {
                copy()
                true
            } catch (_: Exception) {
                file.delete()
                false
            }
            runOnUiThread {
                val m = meeting
                if (isFinishing || isDestroyed || m == null) {
                    if (ok) SYNC_EXECUTOR.execute { store.mutate(meetingId) { record(it) } }
                    return@runOnUiThread
                }
                if (ok) {
                    record(m)
                    persist(m)
                    done()
                } else {
                    Toast.makeText(this, failedText, Toast.LENGTH_SHORT).show()
                }
            }
        }.apply {
            name = "detail-file-copy"
            start()
        }
    }

    /** Extracts photo text on-device; feeds search and LLM summary context. */
    private fun ocrPhoto(m: Meeting, name: String) {
        val file = PhotoStore.fileFor(this, name)
        if (!file.exists()) return
        PhotoOcr.extract(this, file) { text ->
            // Blank is stored too, as "done, no text", so the backfill does
            // not decode and OCR the photo again on every open. A failure
            // (null) stores nothing and is retried next time.
            if (text != null && name in m.photos && m.photoTexts[name] != text) {
                m.photoTexts[name] = text
                persist(m)
            }
        }
    }

    private fun backfillPhotoOcr(m: Meeting) {
        for (name in m.photos.toList()) {
            if (!m.photoTexts.containsKey(name)) ocrPhoto(m, name)
        }
    }

    private fun photoTextBlock(m: Meeting): String {
        val texts = m.photoTexts.values.filter { it.isNotBlank() }
        if (texts.isEmpty()) return ""
        return "\n\n[Text captured from attached photos and whiteboards]\n" +
            texts.joinToString("\n---\n").take(4_000)
    }

    private fun showExportDialog() {
        val m = meeting ?: return
        // Each entry's label and what it does, in the order shown. Subtitles
        // need lines with a time in the recording; without any, one entry
        // says so and explains instead.
        val entries = ArrayList<Pair<String, () -> Unit>>()
        entries.add(getString(R.string.export_markdown) to {
            exportMd.launch(MeetingExporter.suggestedFileName(m, "md"))
        })
        entries.add(getString(R.string.export_pdf) to {
            exportPdf.launch(MeetingExporter.suggestedFileName(m, "pdf"))
        })
        entries.add(getString(R.string.export_docx) to {
            exportDocx.launch(MeetingExporter.suggestedFileName(m, "docx"))
        })
        if (SubtitleExport.hasTimedLines(m.segments)) {
            entries.add(getString(R.string.export_srt) to {
                exportSrt.launch(MeetingExporter.suggestedFileName(m, "srt"))
            })
            entries.add(getString(R.string.export_vtt) to {
                exportVtt.launch(MeetingExporter.suggestedFileName(m, "vtt"))
            })
        } else {
            entries.add(getString(R.string.export_subtitles_unavailable) to { explainNoSubtitles() })
        }
        entries.add(getString(R.string.export_actions_ics) to {
            if (openActionItems(m).isEmpty()) {
                Toast.makeText(this, R.string.no_action_items, Toast.LENGTH_SHORT).show()
            } else {
                exportIcs.launch(MeetingExporter.suggestedFileName(m, "ics"))
            }
        })
        entries.add(getString(R.string.export_actions_text) to { shareActionItemsText(m) })
        AlertDialog.Builder(this)
            .setTitle(R.string.export)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                saveEdits()
                try {
                    entries[which].second()
                } catch (e: Exception) {
                    Toast.makeText(
                        this, getString(R.string.export_failed, e.message ?: "no file picker"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openActionItems(m: Meeting): List<com.meetily.mobile.export.TaskExport.Item> =
        m.actionItems.filter { !it.done }.map {
            com.meetily.mobile.export.TaskExport.Item(
                it.task, it.owner, it.remindAtMs, m.title
            )
        }

    private fun writeActionItemsIcs(uri: Uri) {
        val m = meeting ?: return
        val ics = com.meetily.mobile.export.TaskExport.ics(
            openActionItems(m), System.currentTimeMillis()
        )
        runExport {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(ics.toByteArray(Charsets.UTF_8))
            } ?: throw RuntimeException("could not open destination")
        }
    }

    /**
     * Writes an export on a worker and reports on the main thread. The
     * destination is a document provider — possibly Drive, uploading as it
     * is written — so the write can take as long as the network does.
     * [doneMessage] replaces the plain "Exported" when the export has more
     * to say.
     */
    private fun runExport(doneMessage: String? = null, write: () -> Unit) {
        Thread {
            val failure = try {
                write()
                null
            } catch (e: Exception) {
                e.message ?: "unknown error"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (failure == null && doneMessage != null) {
                    Toast.makeText(this, doneMessage, Toast.LENGTH_LONG).show()
                } else if (failure == null) {
                    Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this, getString(R.string.export_failed, failure), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.apply {
            name = "detail-export"
            start()
        }
    }

    private fun shareActionItemsText(m: Meeting) {
        val items = openActionItems(m)
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.no_action_items, Toast.LENGTH_SHORT).show()
            return
        }
        // The items carry the notice of the summary run that wrote them,
        // which after a switch is not the summary shown: a clinical plan's
        // drugs and doses leave with the clinical notice.
        val text = AiDisclaimers.appendTo(
            com.meetily.mobile.export.TaskExport.text(items),
            AiDisclaimers.texts(this, AiDisclaimers.actionItemCategories(m))
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.actions_share_subject, m.title))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.export_actions_text)))
    }

    private fun writeExport(uri: Uri, isPdf: Boolean) {
        // The worker reads a copy: laying out a long transcript takes a
        // while, and the live meeting keeps changing under it.
        val m = com.meetily.mobile.data.MeetingMerge.snapshot(meeting ?: return)
        val notices = meetingNotices(m)
        runExport {
            contentResolver.openOutputStream(uri)?.use { out ->
                if (isPdf) {
                    MeetingExporter.writePdf(m, out, notices)
                } else {
                    out.write(MeetingExporter.markdown(m, notices).toByteArray(Charsets.UTF_8))
                }
            } ?: throw RuntimeException("could not open destination")
        }
    }

    /** Word, with the fixed notices as in the Markdown and PDF exports. */
    private fun writeDocx(uri: Uri) {
        val m = com.meetily.mobile.data.MeetingMerge.snapshot(meeting ?: return)
        val notices = meetingNotices(m)
        runExport {
            contentResolver.openOutputStream(uri)?.use { out ->
                DocxExport.write(m, out, notices)
            } ?: throw RuntimeException("could not open destination")
        }
    }

    /**
     * SubRip or WebVTT from the lines that have a time in the recording.
     * Lines without one cannot be placed and are left out, and the toast
     * says how many.
     */
    private fun writeSubtitles(uri: Uri, vtt: Boolean) {
        val m = com.meetily.mobile.data.MeetingMerge.snapshot(meeting ?: return)
        if (!SubtitleExport.hasTimedLines(m.segments)) {
            explainNoSubtitles()
            return
        }
        val leftOut = SubtitleExport.untimedLines(m.segments)
        val done = if (leftOut > 0) {
            resources.getQuantityString(R.plurals.export_subtitles_left_out, leftOut, leftOut)
        } else {
            null
        }
        runExport(done) {
            val cues = SubtitleExport.cues(m.segments)
            val text = if (vtt) SubtitleExport.vtt(cues) else SubtitleExport.srt(cues)
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw RuntimeException("could not open destination")
        }
    }

    private fun explainNoSubtitles() {
        AlertDialog.Builder(this)
            .setTitle(R.string.export_subtitles_untimed_title)
            .setMessage(R.string.export_subtitles_untimed_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun renderQaHistory(m: Meeting) {
        qaList.removeAllViews()
        for (entry in m.qa) {
            addQaView(entry.question, entry.answer)
        }
    }

    /** Adds a question/answer pair to the thread; returns the answer view for updating. */
    private fun addQaView(question: String, answer: String): TextView {
        val item = LayoutInflater.from(this).inflate(R.layout.item_qa, qaList, false)
        item.findViewById<TextView>(R.id.qaQuestion).text = question
        val answerView = item.findViewById<TextView>(R.id.qaAnswer)
        answerView.text = answer
        // Reads the answer when tapped: a pending question's row is filled
        // in later.
        val report = AiReport.addButton(answerView, R.string.aisafety_report_short) {
            val shown = answerView.text.toString()
            if (shown == getString(R.string.ask_thinking)) {
                Toast.makeText(this, R.string.aisafety_nothing_to_report, Toast.LENGTH_SHORT).show()
            } else {
                AiReport.show(this, answerSubject(question, shown))
            }
        }
        report.contentDescription = getString(R.string.aisafety_report_answer_desc)
        qaList.addView(item)
        return answerView
    }

    private fun sendQuestion() {
        val m = meeting ?: return
        val question = askInput.text.toString().trim()
        if (question.isBlank()) return
        saveEdits()

        askInput.setText("")
        askSend.isEnabled = false
        val answerView = addQaView(question, getString(R.string.ask_thinking))

        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val localOnly = settings.localOnlyLlm
        val transcript = m.transcriptTextWithSpeakers()
        val notes = m.notes
        val summary = m.summary
        val attendees = m.attendees.toList()
        val history = m.qa.map { it.question to it.answer }
        val languageClause = com.meetily.mobile.summarize.OutputLanguage.clauseFor(
            com.meetily.mobile.summarize.OutputLanguage.Kind.ANSWER,
            settings.summaryLanguage, m.language, m.translated
        )

        Thread {
            val answer = try {
                LlmClient.ask(
                    baseUrl, apiKey, model, localOnly, transcript, notes, summary,
                    attendees, history, question, languageClause
                )
            } catch (e: Exception) {
                getString(R.string.ask_failed, e.message ?: "unknown error")
            }
            // SAVE FIRST, on this thread, against the stored copy. A local
            // model can take minutes on a long transcript; rotating the phone
            // or backing out during it used to throw away both the answer and
            // the question, because the append only ran inside the UI hop
            // below — which returns early once the screen is gone.
            val entry = QaEntry(question, answer)
            val saved = try {
                store.mutate(m.id) { it.qa.add(entry) }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                answerView.text = answer
                askSend.isEnabled = true
                if (saved) {
                    // Taken in from disk like any other background write;
                    // adding it here as well could list it twice.
                    requestSync()
                } else {
                    m.qa.add(entry)
                    persist(m)
                }
            }
        }.start()
    }

    /**
     * LLM-based speaker attribution from conversational context. Applies only
     * to untagged lines, never overwriting manual tags, and always behind an
     * explicit confirmation with a preview.
     */
    /**
     * Hands speaker attribution to the service that already owns long AI
     * runs. It used to hold the screen behind a modal dialog for as long as
     * the model took — which on a long meeting is minutes — and threw the
     * result away if you left. Now it runs in the background with progress on
     * the meeting card, and the proposals wait for you to come back.
     */
    private fun suggestSpeakers() {
        val m = meeting ?: return
        if (com.meetily.mobile.data.SpeakerSuggestions.isPending(this, m.id)) {
            offerSpeakerSuggestions()
            return
        }
        if (!settings.useLlm || settings.llmBaseUrl.isBlank()) {
            Toast.makeText(this, R.string.suggest_requires_llm, Toast.LENGTH_LONG).show()
            return
        }
        if (m.segments.isEmpty()) {
            Toast.makeText(this, R.string.no_transcript, Toast.LENGTH_SHORT).show()
            return
        }
        if (SummaryService.isRunning) {
            Toast.makeText(this, R.string.ai_busy, Toast.LENGTH_LONG).show()
            return
        }
        // Refused rather than queued: JobQueue only carries summaries, checks
        // and imports, so a queued speaker pass would come back as a plain
        // summary. This is a short interactive action — asking again later is
        // the right answer.
        if (!JobGate.canStartBatch()) {
            Toast.makeText(this, R.string.busy_heavy_job, Toast.LENGTH_LONG).show()
            return
        }
        saveEdits()
        flushSync()
        SummaryService.start(this, m.id, "", SummaryService.MODE_SPEAKERS)
        Toast.makeText(this, R.string.speakers_started, Toast.LENGTH_LONG).show()
    }

    /** Asked once per visit: a finished analysis is waiting to be reviewed. */
    private var speakerPromptShown = false

    private fun maybeOfferSpeakerSuggestions() {
        val m = meeting ?: return
        if (speakerPromptShown || isFinishing || isDestroyed) return
        if (!com.meetily.mobile.data.SpeakerSuggestions.isPending(this, m.id)) return
        speakerPromptShown = true
        offerSpeakerSuggestions()
    }

    private fun offerSpeakerSuggestions() {
        val m = meeting ?: return
        val staged = com.meetily.mobile.data.SpeakerSuggestions.load(this, m.id)
        // Re-filtered against the transcript as it stands now: any line the
        // user tagged themselves while this was running keeps their tag.
        // Indices only mean something against the transcript they came from.
        // A deletion, a split, or an accepted accuracy check renumbers
        // everything after it, and applying them then tags the wrong lines.
        val stale = !com.meetily.mobile.data.SpeakerSuggestions
            .stillMatch(this, m.id, m.segments)
        val applicable = if (stale) {
            emptyList()
        } else {
            com.meetily.mobile.data.SpeakerSuggestions.applicable(staged, m.segments)
        }
        if (applicable.isEmpty()) {
            com.meetily.mobile.data.SpeakerSuggestions.delete(this, m.id)
            // "Found nothing" is only true when the lines still match; a run
            // that did find names but was outdated by an edit says so.
            Toast.makeText(
                this, if (stale) R.string.suggest_stale else R.string.suggest_none,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        confirmSpeakerSuggestions(m, applicable)
    }

    private fun confirmSpeakerSuggestions(m: Meeting, applicable: List<Pair<Int, String>>) {
        val preview = buildString {
            for ((index, name) in applicable.take(5)) {
                val text = m.segments[index].text
                append("“")
                append(text.take(40))
                if (text.length > 40) append("…")
                append("” → ").append(name).append('\n')
            }
            if (applicable.size > 5) {
                append("…")
            }
        }.trimEnd()
        AlertDialog.Builder(this)
            .setTitle(R.string.suggest_apply_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.suggest_apply_message,
                    applicable.size, applicable.size, preview
                )
            )
            .setPositiveButton(R.string.suggest_apply) { _, _ ->
                com.meetily.mobile.data.SpeakerSuggestions.delete(this, m.id)
                for ((index, suggested) in applicable) {
                    if (index !in m.segments.indices) continue
                    // Canonicalize to the existing attendee's casing so the
                    // transcript never mixes "Bob" and "bob".
                    val name = m.attendees
                        .firstOrNull { it.equals(suggested, ignoreCase = true) }
                        ?: suggested
                    m.segments[index] = m.segments[index].copy(speaker = name)
                    if (m.attendees.none { it.equals(name, ignoreCase = true) }) {
                        m.attendees.add(name)
                    }
                }
                attendeesInput.setText(m.attendeesText())
                persist(m)
                renderTranscript(m)
                Toast.makeText(
                    this,
                    resources.getQuantityString(
                        R.plurals.suggest_applied, applicable.size, applicable.size
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            // An explicit decline discards them; backing out of the dialog
            // without choosing keeps them, since the run took real time and
            // the menu item is the way back to this prompt.
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                com.meetily.mobile.data.SpeakerSuggestions.delete(this, m.id)
            }
            .showSecure()
    }

    private fun chooseTemplateAndSummarize() {
        val templates = SummaryTemplates.allWithCustom(this)
        // Per-series memory: a standup series preselects standup, a sales
        // series preselects the sales report — falling back to the global
        // last-used template for one-off meetings.
        val seriesKey = meeting?.let {
            com.meetily.mobile.search.MeetingGroups.normalizeTitle(it.title)
        }.orEmpty()
        val preferred = settings.seriesTemplate(seriesKey) ?: settings.summaryTemplate
        val current = templates
            .indexOfFirst { it.key == preferred }
            .coerceAtLeast(0)

        val view = layoutInflater.inflate(R.layout.dialog_summary_style, null)
        val toggle = view.findViewById<
            com.google.android.material.button.MaterialButtonToggleGroup
        >(R.id.depthToggle)
        toggle.check(
            when (settings.summaryDepth) {
                "brief" -> R.id.depthBrief
                "detailed" -> R.id.depthDetailed
                else -> R.id.depthStandard
            }
        )
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                settings.summaryDepth = when (checkedId) {
                    R.id.depthBrief -> "brief"
                    R.id.depthDetailed -> "detailed"
                    else -> "standard"
                }
            }
        }
        val list = view.findViewById<android.widget.ListView>(R.id.templateList)
        // A style that already has a summary says so: choosing it writes
        // that one again, and the others are kept (SummaryAlternatives).
        val withSummary = meeting?.let { m ->
            com.meetily.mobile.data.SummaryAlternatives.versions(m, emptyList())
                .mapNotNull { it.template }.toSet()
        }.orEmpty()
        list.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            templates.map {
                if (it.key in withSummary) {
                    getString(R.string.summaries_picker_has_summary, it.label(this))
                } else {
                    it.label(this)
                }
            }
        )
        list.setItemChecked(current, true)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.choose_template)
            .setView(view)
            .setNeutralButton(R.string.template_custom_button) { _, _ ->
                showCustomTemplateMenu()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        list.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            val template = templates[which]
            settings.summaryTemplate = template.key
            settings.setSeriesTemplate(seriesKey, template.key)
            generateSummary(template)
        }
    }

    private fun showCustomTemplateMenu() {
        val customs = CustomTemplates.load(this)
        val items = mutableListOf(getString(R.string.template_new))
        items.addAll(customs.map { getString(R.string.template_delete_fmt, it.name) })
        AlertDialog.Builder(this)
            .setTitle(R.string.template_custom_title)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    showNewTemplateDialog()
                } else {
                    val doomed = customs[which - 1]
                    CustomTemplates.delete(this, doomed.key)
                    if (settings.summaryTemplate == doomed.key) {
                        settings.summaryTemplate = "general"
                    }
                    Toast.makeText(this, R.string.template_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNewTemplateDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val nameInput = EditText(this).apply {
            hint = getString(R.string.template_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val instructionsInput = EditText(this).apply {
            hint = getString(R.string.template_instructions_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput)
            addView(instructionsInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.template_new)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text.toString().trim()
                val instructions = instructionsInput.text.toString().trim()
                if (name.isBlank() || instructions.isBlank()) {
                    Toast.makeText(this, R.string.template_fields_required, Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                val custom = CustomTemplates.add(this, name, instructions)
                settings.summaryTemplate = custom.key
                Toast.makeText(this, R.string.template_saved, Toast.LENGTH_SHORT).show()
                generateSummary(SummaryTemplates.byKey(this, custom.key))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateSummary(template: SummaryTemplate) {
        val m = meeting ?: return
        saveEdits()
        // The service reads the meeting from disk; it has to see these edits.
        flushSync()

        if (m.transcriptText().isBlank() && m.notes.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_summarize, Toast.LENGTH_SHORT).show()
            return
        }

        if (SummaryService.isRunning) {
            Toast.makeText(this, R.string.summary_busy, Toast.LENGTH_SHORT).show()
            return
        }
        // SummaryService.isRunning on its own is not admission control: a live
        // recording or a running import would happily have a multi-GB GGUF
        // loaded on top of it, which is the exact double-load JobGate exists
        // to prevent. Queue instead — a summary is only ever delayed.
        if (!JobGate.canStartBatch()) {
            JobGate.requestSummary(
                this, m.id, template.key, whenCharging = false, required = true
            )
            Toast.makeText(this, R.string.summary_queued, Toast.LENGTH_LONG).show()
            // Watch for the run JobGate starts later, so its progress and
            // result show here live rather than on the next visit.
            bindSummaryService(create = false)
            return
        }
        showSummarizingUi()
        setSummaryProgress(-1)
        summaryAnnouncer.reset()
        // Generation lives in SummaryService: it survives rotation and
        // navigation, saves the result itself, and this screen just observes.
        SummaryService.start(this, m.id, template.key)
        bindSummaryService()
    }

    private fun showSummarizingUi() {
        aiPanel.visibility = View.GONE
        summaryNoticeView.visibility = View.GONE
        reportSummaryButton.visibility = View.GONE
        summaryVersions.hide()
        progress.visibility = View.VISIBLE
        summaryView.visibility = View.VISIBLE
        A11y.setStatusText(summaryView, getString(R.string.summarizing), announce = false)
    }

    private fun shareMeeting() {
        val m = meeting ?: return
        saveEdits()
        val text = buildString {
            append(m.title).append('\n')
            append(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(m.createdAtMs))
            ).append("\n\n")
            if (m.attendees.isNotEmpty()) {
                append("ATTENDEES\n").append(m.attendeesText()).append("\n\n")
            }
            if (m.summary.isNotBlank()) {
                append("SUMMARY\n")
                append(AiDisclaimers.prependTo(m.summary, summaryNotices())).append("\n\n")
            }
            if (m.notes.isNotBlank()) {
                append("NOTES\n").append(m.notes).append("\n\n")
            }
            append("TRANSCRIPT\n").append(m.transcriptTextWithSpeakers())
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, m.title)
        }
        if (text.length <= SHARE_TEXT_CAP) {
            send.putExtra(Intent.EXTRA_TEXT, text)
        } else {
            // A multi-hour transcript goes as a file; the text carries only
            // the part a message preview can use.
            val file = try {
                val dir = File(cacheDir, "shares").apply { mkdirs() }
                val safe = m.title.take(60).replace(Regex("[^\\w\\-]+"), "_").ifBlank { "meeting" }
                File(dir, "$safe.txt").apply { writeText(text) }
            } catch (_: Exception) {
                Toast.makeText(this, R.string.share_too_long, Toast.LENGTH_LONG).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.putExtra(Intent.EXTRA_TEXT, text.take(SHARE_PREVIEW_CHARS))
            send.clipData = android.content.ClipData.newRawUri(m.title, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_meeting)))
        } catch (_: RuntimeException) {
            // Too large for the binder after all, or no app to take it.
            Toast.makeText(this, R.string.share_too_long, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Every item acts on the meeting, so none is offered before it has
        // been read; onMeetingRead asks for the menu again once it lands.
        val loaded = meeting != null
        for (i in 0 until menu.size()) menu.getItem(i).isEnabled = loaded
        menu.findItem(R.id.action_star)?.setTitle(
            if (meeting?.starred == true) R.string.unstar_meeting else R.string.star_meeting
        )
        menu.findItem(R.id.replace_action_undo)?.isVisible = replace.canUndo(meeting?.id)
        menu.findItem(R.id.resume_action_continue)?.isVisible =
            meeting?.let { ContinueTranscription.offeredAt(this, it) } != null
        // No audio (removed, never kept, or a transcript imported from
        // captions): nothing to share, check or remove, as the Audio tab
        // already says by not being there.
        val hasAudio = !meeting?.audioFile.isNullOrBlank()
        for (id in intArrayOf(
            R.id.action_share_audio, R.id.action_check_accuracy, R.id.storage_action_remove_audio
        )) {
            menu.findItem(id)?.isVisible = hasAudio
        }
        return super.onPrepareOptionsMenu(menu)
    }

    // --- "Follows on from" ---------------------------------------------------

    /**
     * Renders the link line, and the one-shot offer card when the recording
     * that just finished handed one over.
     */
    private fun renderFollowsFrom(m: Meeting) {
        val line = headerView.findViewById<TextView>(R.id.followLinkLine)
        val link = m.followsEvent
        if (link != null) {
            val stamp = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
            line.text = getString(
                R.string.follow_linked, link.title, stamp.format(Date(link.beginMs))
            )
            line.visibility = View.VISIBLE
            line.setOnClickListener { showFollowPicker() }
        } else {
            line.visibility = View.GONE
        }
        val card = headerView.findViewById<View>(R.id.followOfferCard)
        // Only when this screen was opened straight off the end of a
        // recording, nothing is linked yet, and the calendar is already
        // readable. Asking for calendar permission out of nowhere after a
        // recording would be intrusive, and the overflow route always works.
        val offer = offerFollowLink && link == null &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CALENDAR
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        card.visibility = if (offer) View.VISIBLE else View.GONE
        if (offer) {
            headerView.findViewById<View>(R.id.followChooseButton).setOnClickListener {
                showFollowPicker()
            }
            headerView.findViewById<View>(R.id.followDismissButton).setOnClickListener {
                // Nothing persisted for a "no": the extra was one-shot and
                // the overflow item remains. Zero new state for a negative.
                offerFollowLink = false
                card.visibility = View.GONE
            }
        }
    }

    private fun showFollowPicker() {
        val m = meeting ?: return
        CalendarFollowSheet.show(
            activity = this,
            beforeMs = m.createdAtMs,
            hasExistingLink = m.followsEvent != null
        ) { picked ->
            val current = meeting ?: return@show
            current.followsEvent = picked
            // Through persist(), i.e. a merge onto the stored copy: this screen holds the
            // user's unsaved notes and attendee edits, so a load-then-save
            // would drop them.
            persist(current)
            offerFollowLink = false
            renderFollowsFrom(current)
            Toast.makeText(
                this,
                if (picked == null) getString(R.string.follow_unlinked)
                else getString(R.string.follow_linked_toast, picked.title),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun toggleStar() {
        val m = meeting ?: return
        m.starred = !m.starred
        persist(m)
        invalidateOptionsMenu()
        Toast.makeText(
            this,
            if (m.starred) R.string.starred_toast else R.string.unstarred_toast,
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * ⋮ → Remove audio (keep transcript). Asks first and says what stops
     * working (AudioRemoval.confirm), or, with no transcript yet, that the
     * recording is all the meeting holds. Then the player is freed and this
     * screen's copy drops the reference through the normal save (persist,
     * merged like any other edit made here). The stored copy drops it too,
     * on the save thread, before the file, its waveform cache and its clips
     * are deleted there: persist may only be queued behind a save already
     * running, and a stored meeting must never name a deleted file.
     */
    private fun confirmRemoveAudio() {
        val m = meeting ?: return
        val name = m.audioFile
        if (name.isNullOrBlank()) {
            Toast.makeText(this, R.string.no_audio_kept, Toast.LENGTH_SHORT).show()
            return
        }
        if (AudioRemoval.isBlocked(this, m.id)) {
            Toast.makeText(this, removeAudioBusyText(m.id), Toast.LENGTH_LONG).show()
            return
        }
        // The clear-voice copy goes with the recording, and is in the size.
        val bytes = AudioStore.bytesWithCopy(this, name)
        val hasCopy = bytes > AudioStore.fileFor(this, name).length()
        AudioRemoval.confirm(
            this, 1, bytes, untranscribed = m.segments.isEmpty(),
            unfinishedAtMs = m.transcriptResumeMs,
            clearVoiceCopies = if (hasCopy) 1 else 0
        ) {
            val current = meeting ?: return@confirm
            // Asked again: a job may have started while the question was up.
            if (current.audioFile != name || AudioRemoval.isBlocked(this, current.id)) {
                Toast.makeText(this, removeAudioBusyText(current.id), Toast.LENGTH_LONG).show()
                return@confirm
            }
            playerHandler.removeCallbacks(playerTick)
            pendingPlayerAction = null
            // Also ends playback carried on in the background, and its notification.
            MeetingPlayback.stopMeeting(current.id)
            if (::playerBar.isInitialized) showPlayerBar(false)
            val untranscribed = current.segments.isEmpty()
            val copy = current.clearVoiceFile
            current.audioFile = null
            // Nothing can continue the transcript now; it keeps what it has.
            current.transcriptResumeMs = null
            current.clearVoiceFile = null
            playingClearVoice = false
            persist(current)
            applyTabState()
            // The language chip offers "Re-transcribe as…" only while there
            // is audio to run it on (LanguagePicker.bindChip).
            renderMeta(current)
            renderTranscript(current)
            // The audio items go from the menu with it (onPrepareOptionsMenu).
            invalidateOptionsMenu()
            val app = applicationContext
            val id = current.id
            SYNC_EXECUTOR.execute {
                // True once the stored copy no longer names the file: cleared
                // here, or already by the screen save. A screen save queued
                // after this one merges base = name, ours = null,
                // stored = null, so null stays.
                val store = MeetingStore(app)
                val released = store.mutateIf(id) { stored ->
                    if (stored.audioFile == name) {
                        stored.audioFile = null
                        stored.transcriptResumeMs = null
                        // Made from the recording; it goes with it.
                        stored.clearVoiceFile = null
                        true
                    } else {
                        false
                    }
                }
                // A meeting deleted meanwhile names nothing either.
                if (released || !store.exists(id)) AudioRemoval.deleteFiles(app, name, copy)
            }
            Toast.makeText(
                this,
                if (untranscribed) R.string.storage_audio_removed_only else R.string.storage_audio_removed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Why Remove audio is held back, naming clear voice when that is the job. */
    private fun removeAudioBusyText(meetingId: String): Int =
        if (ClearVoiceService.isPreparing(meetingId)) R.string.clearvoice_busy
        else R.string.storage_audio_busy

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // The items are disabled until the meeting has been read
        // (onPrepareOptionsMenu); none of them may run before then, however
        // it is reached.
        if (meeting == null) return true
        return when (item.itemId) {
            R.id.moment_action_find -> {
                findBar.open()
                true
            }
            R.id.action_summarize -> {
                chooseTemplateAndSummarize()
                true
            }
            R.id.action_share -> {
                shareMeeting()
                true
            }
            R.id.action_export -> {
                showExportDialog()
                true
            }
            R.id.action_suggest_speakers -> {
                suggestSpeakers()
                true
            }
            R.id.action_share_audio -> {
                shareAudio()
                true
            }
            R.id.action_rename -> {
                renameMeeting()
                true
            }
            R.id.replace_action_find -> {
                // Starts from what Find in meeting is finding, when it is open.
                // An applied replace re-renders the transcript, and the bar
                // recounts from there (renderTranscript).
                replace.open(find = findBar.query)
                true
            }
            R.id.replace_action_undo -> {
                replace.undo()
                true
            }
            R.id.action_topics -> {
                topicsAction()
                true
            }
            R.id.action_follows_from -> {
                showFollowPicker()
                return true
            }
            R.id.action_pre_brief -> {
                meeting?.let {
                    startActivity(
                        Intent(this, PreMeetingBriefActivity::class.java)
                            .putExtra(PreMeetingBriefActivity.EXTRA_QUERY, it.title)
                    )
                }
                true
            }
            R.id.action_check_accuracy -> {
                confirmCheckAccuracy()
                true
            }
            R.id.resume_action_continue -> {
                continueTranscription()
                true
            }
            R.id.storage_action_remove_audio -> {
                confirmRemoveAudio()
                true
            }
            R.id.action_star -> {
                toggleStar()
                true
            }
            R.id.aisafety_action_report -> {
                reportFromMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- Audio playback ----------------------------------------------------

    /**
     * This meeting's player, prepared or still preparing; null when there is
     * none. It belongs to MeetingPlayback, so it outlives the screen:
     * playback goes on in the background, and a rotation keeps it.
     */
    private val player: MediaPlayer?
        get() = MeetingPlayback.playerFor(meeting?.id)
    private val playerReady: Boolean
        get() = MeetingPlayback.isPrepared(meeting?.id)

    /** The meeting this screen counts itself open on with MeetingPlayback. */
    private var playbackScreenId: String? = null
    private lateinit var playerBar: View
    private lateinit var playPauseButton: ImageButton
    private lateinit var playerSeek: SeekBar
    private lateinit var playerTime: TextView
    private lateinit var playerSpeedButton: TextView
    private lateinit var skipSilenceButton: ImageButton
    private var playbackSpeed = 1.0f
    private var skipSilence = false
    private lateinit var playerBoostButton: TextView
    private lateinit var followButton: ImageButton
    /** Follow the transcript along with playback (highlight + auto-scroll). */
    private var followPlayback = true
    /**
     * Cleared when the user drags the transcript themselves. Scrolling the
     * list out from under someone who is reading or tagging is the fastest
     * way to make a follow feature infuriating, so a drag wins and the
     * toggle visibly turns off — one tap re-arms it.
     */
    private var followScrolling = true
    /** (audio offset, segment index) for locatable lines; see PlaybackFollow. */
    private var followTimeline: List<Pair<Long, Int>> = emptyList()
    /** The volume boost setting; MeetingPlayback holds the effect itself. */
    private var boostDb = 0

    /** Speech spans over the audio timeline; built lazily for skip-silence. */
    private val speechSpans: List<com.meetily.mobile.whisper.SpeechSpans.Span> by lazy {
        com.meetily.mobile.whisper.SpeechSpans.build(meeting?.segments ?: emptyList())
    }

    private val playerHandler = Handler(Looper.getMainLooper())
    private val playerTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (playerReady) {
                // Skip-silence runs in MeetingPlayback, so it carries on
                // in the background; this tick only shows where it went.
                updatePlayerUi(p)
                if (followPlayback) updateFollow(p.currentPosition.toLong())
                if (p.isPlaying) {
                    // Words last a few hundred milliseconds, so a 400 ms tick
                    // visibly lags the audio. The extra work is one rebind of
                    // a single row, and only when the word actually changes.
                    // The highlight exists on the transcript tab only; the
                    // other tabs keep the slow tick.
                    playerHandler.postDelayed(
                        this,
                        if (followPlayback && onTranscriptTab) FOLLOW_TICK_MS else IDLE_TICK_MS
                    )
                }
            }
        }
    }

    private fun cyclePlaybackSpeed() {
        val steps = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 0.5f)
        val at = steps.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.01f }
        playbackSpeed = steps[(at + 1).mod(steps.size)]
        settings.playbackSpeed = playbackSpeed
        renderSpeedLabel()
        // Applied at once while playing, and to every later start.
        MeetingPlayback.setSpeed(playbackSpeed)
    }

    private fun renderSpeedLabel() {
        val label = if (playbackSpeed == playbackSpeed.toInt().toFloat()) {
            playbackSpeed.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", playbackSpeed)
                .trimEnd('0').trimEnd('.')
        }
        playerSpeedButton.text = "$label×"
        // "1.5×" is read as "1.5 multiplication sign", with no name.
        playerSpeedButton.contentDescription = getString(R.string.a11y_playback_speed)
        androidx.core.view.ViewCompat.setStateDescription(
            playerSpeedButton, getString(R.string.a11y_playback_speed_state, label)
        )
    }

    private fun renderSkipSilence() {
        A11y.setOnOff(skipSilenceButton, skipSilence)
        skipSilenceButton.alpha = if (skipSilence) 1.0f else 0.45f
        skipSilenceButton.setColorFilter(
            themeColor(
                if (skipSilence) {
                    com.google.android.material.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                }
            )
        )
    }

    private fun audioFileOrNull(): File? {
        val m = meeting ?: return null
        val name = m.audioFile ?: return null
        val file = AudioStore.fileFor(this, name)
        return if (file.length() > 0) file else null
    }

    private fun setUpPlayer() {
        playerBar = findViewById(R.id.playerBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        playerSeek = findViewById(R.id.playerSeek)
        playerTime = findViewById(R.id.playerTime)
        playerSpeedButton = findViewById(R.id.playerSpeed)
        skipSilenceButton = findViewById(R.id.skipSilenceButton)
        if (audioFileOrNull() == null) {
            showPlayerBar(false)
            return
        }
        showPlayerBar(true)
        playerTime.text = formatClock(0)
        playbackSpeed = settings.playbackSpeed
        MeetingPlayback.setSpeed(playbackSpeed)
        renderSpeedLabel()
        playerSpeedButton.setOnClickListener { cyclePlaybackSpeed() }
        findViewById<View>(R.id.playerBack15).setOnClickListener {
            skipBy(-com.meetily.mobile.data.PlaybackSkip.STEP_MS)
        }
        findViewById<View>(R.id.playerForward15).setOnClickListener {
            skipBy(com.meetily.mobile.data.PlaybackSkip.STEP_MS)
        }
        followButton = findViewById(R.id.followButton)
        followPlayback = settings.followPlayback
        renderFollowButton()
        followButton.setOnClickListener { toggleFollow() }
        playerBoostButton = findViewById(R.id.playerBoost)
        boostDb = settings.playbackBoostDb
        MeetingPlayback.setBoost(boostDb)
        renderBoostLabel()
        playerBoostButton.setOnClickListener { cycleBoost() }
        skipSilence = settings.skipSilence && speechSpans.isNotEmpty()
        renderSkipSilence()
        skipSilenceButton.setOnClickListener {
            if (speechSpans.isEmpty()) {
                // No word/offset data (edited or system-recognizer lines).
                Toast.makeText(this, R.string.skip_silence_unavailable, Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            skipSilence = !skipSilence
            settings.skipSilence = skipSilence
            renderSkipSilence()
            MeetingPlayback.setSkipSpans(meeting?.id, skipSpans())
        }
        // Back to a player that outlived the last screen (a rotation, or
        // playback carried on in the background): whichever recording it
        // plays is the one the chip shows.
        MeetingPlayback.playingCopy(meeting?.id)?.let { playingClearVoice = it }
        setUpClearVoice()
        playPauseButton.setOnClickListener { togglePlayback() }
        playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser && playerReady) {
                    player?.seekTo(value)
                    player?.let { updatePlayerUi(it) }
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        syncPlayerFromPlayback()
    }

    /**
     * Shows or hides the player bar. The list below the toolbar takes the
     * navigation-bar inset only while the bar is gone (EdgeToEdge.listAbove),
     * and a visibility change does not re-send insets, so ask for them here.
     */
    private fun showPlayerBar(shown: Boolean) {
        playerBar.visibility = if (shown) View.VISIBLE else View.GONE
        androidx.core.view.ViewCompat.requestApplyInsets(playerBar)
    }

    /** The player, only once it is prepared; null while it is still preparing. */
    private val readyPlayer: MediaPlayer?
        get() = player?.takeIf { playerReady }

    /** What the user asked of the player before it was ready; the latest wins. */
    private var pendingPlayerAction: ((MediaPlayer) -> Unit)? = null

    /**
     * Runs [action] on the prepared player, creating it on first use.
     *
     * Preparing is asynchronous. Meeting audio is raw ADTS, which has no
     * index, so the platform walks every frame header of the file to find
     * its length — seconds of frozen screen on a multi-hour meeting when it
     * was done with prepare() on the first tap. [action] now waits for it.
     *
     * The player is MeetingPlayback's. Opening this meeting's takes it from
     * any other meeting, except when nothing was asked ([action] null, the
     * Audio tab wanting the length) and another meeting is still playing.
     */
    private fun withPlayer(action: ((MediaPlayer) -> Unit)? = null) {
        readyPlayer?.let { p ->
            action?.invoke(p)
            return
        }
        if (action != null) pendingPlayerAction = action
        if (player != null) return // preparing
        val m = meeting ?: return
        if (action == null && MeetingPlayback.busyElsewhere(m.id)) return
        val file = playbackFileOrNull() ?: return
        val opened = MeetingPlayback.open(
            this, m.id, m.audioFile, file,
            copy = file.name != m.audioFile,
            spans = skipSpans()
        )
        if (opened) {
            // prepareAsync can fail at once, which has already said so.
            if (player != null && !playerReady) playPauseButton.alpha = 0.5f
        } else {
            onPlayerFailed()
        }
    }

    /** The speech spans skip-silence jumps between, while it is on. */
    private fun skipSpans(): List<com.meetily.mobile.whisper.SpeechSpans.Span>? =
        if (skipSilence) speechSpans else null

    private fun onPlayerPrepared(p: MediaPlayer) {
        if (player !== p || isDestroyed) return
        playPauseButton.alpha = 1f
        if (MeetingPlayback.boostUnavailable) playerBoostButton.visibility = View.GONE
        playerSeek.max = p.duration.coerceAtLeast(1)
        updatePlayerUi(p)
        // Reopened on the other recording (Clear voice), it carries on
        // playing by itself.
        setPlayPauseIcon(playing = MeetingPlayback.isPlaying())
        if (MeetingPlayback.isPlaying()) restartTick()
        // The strip was scaled from the transcript while the real length
        // was unknown; now it is known.
        if (tab == Tab.AUDIO) meeting?.let { renderAudioTab(it) }
        val action = pendingPlayerAction
        pendingPlayerAction = null
        action?.invoke(p)
    }

    /** The player could not be opened; MeetingPlayback has let it go. */
    private fun onPlayerFailed() {
        pendingPlayerAction = null
        if (isFinishing || isDestroyed) return
        // A screen re-created while the player was preparing hears this
        // before its own bar is set up.
        if (!::playPauseButton.isInitialized) return
        playPauseButton.alpha = 1f
        setPlayPauseIcon(playing = false)
        Toast.makeText(this, R.string.audio_play_failed, Toast.LENGTH_SHORT).show()
    }

    private fun togglePlayback() {
        withPlayer { p ->
            if (p.isPlaying) {
                MeetingPlayback.pause()
                setPlayPauseIcon(playing = false)
            } else if (startPlayback()) {
                setPlayPauseIcon(playing = true)
                restartTick()
            }
        }
    }

    /**
     * Starts this meeting's prepared player: with audio focus, and never
     * while a recording runs. Says why when it does not start.
     */
    private fun startPlayback(): Boolean =
        when (MeetingPlayback.play(this)) {
            MeetingPlayback.PlayResult.STARTED -> true
            MeetingPlayback.PlayResult.RECORDING -> {
                Toast.makeText(this, R.string.playback_blocked_recording, Toast.LENGTH_LONG).show()
                false
            }
            MeetingPlayback.PlayResult.FOCUS_DENIED -> {
                Toast.makeText(this, R.string.playback_focus_denied, Toast.LENGTH_LONG).show()
                false
            }
            MeetingPlayback.PlayResult.NOT_READY -> false
        }

    /** The player bar's 15-second skips; the highlight follows at once. */
    private fun skipBy(deltaMs: Long) {
        withPlayer { p ->
            val target = MeetingPlayback.skipBy(deltaMs) ?: return@withPlayer
            updatePlayerUi(p)
            updateFollow(target)
        }
    }

    /**
     * Shows the player as MeetingPlayback has it now: on coming back to the
     * screen or after a rotation (playback may have gone on, been paused
     * from the notification, or ended meanwhile), and when something outside
     * the screen played, paused, moved or stopped it.
     */
    private fun syncPlayerFromPlayback() {
        if (!::playerBar.isInitialized || playerBar.visibility != View.VISIBLE) return
        val p = readyPlayer
        if (p == null) {
            playerHandler.removeCallbacks(playerTick)
            if (player != null) {
                // Preparing (or reopening on the other recording):
                // onPlayerPrepared shows the rest.
                playPauseButton.alpha = 0.5f
                return
            }
            // Let go (a recording started, Stop, a long pause, the audio
            // deleted): the next play opens it again from the start.
            playPauseButton.alpha = 1f
            setPlayPauseIcon(playing = false)
            playerSeek.progress = 0
            playerTime.text = formatClock(0)
            return
        }
        playPauseButton.alpha = 1f
        if (MeetingPlayback.boostUnavailable) playerBoostButton.visibility = View.GONE
        playerSeek.max = p.duration.coerceAtLeast(1)
        updatePlayerUi(p)
        val playing = MeetingPlayback.isPlaying()
        setPlayPauseIcon(playing)
        if (playing) {
            if (started) restartTick()
        } else {
            updateFollow(p.currentPosition.toLong())
        }
    }

    /** What MeetingPlayback tells the started screen about this meeting's player. */
    private val playbackListener = object : MeetingPlayback.Listener {
        override fun onPrepared(player: MediaPlayer) = onPlayerPrepared(player)

        override fun onFailed() = onPlayerFailed()

        override fun onPlayingChanged(playing: Boolean) = syncPlayerFromPlayback()

        override fun onMoved() = syncPlayerFromPlayback()

        override fun onReleased() {
            pendingPlayerAction = null
            syncPlayerFromPlayback()
        }
    }

    /** The icon and its spoken name together; the name used to stay "Play". */
    private fun setPlayPauseIcon(playing: Boolean) {
        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playPauseButton.contentDescription =
            getString(if (playing) R.string.pause else R.string.play_audio)
    }

    private fun playFrom(audioMs: Long) {
        withPlayer { p ->
            p.seekTo(audioMs.toInt().coerceIn(0, p.duration))
            if (!p.isPlaying && startPlayback()) {
                setPlayPauseIcon(playing = true)
            }
            restartTick()
        }
    }

    /**
     * Exactly one live tick chain, always.
     *
     * playerTick reposts itself while the player is playing, so a bare post()
     * starts an additional independent chain that nothing ever stops. Reviewing
     * a meeting by tapping words — which is what word-level seek is for — used
     * to leave one 90 ms chain per tap, each running updatePlayerUi and a
     * reverse scan of the segment list on the main thread.
     */
    private fun restartTick() {
        playerHandler.removeCallbacks(playerTick)
        playerHandler.post(playerTick)
    }

    // --- Playback follow ------------------------------------------------

    /**
     * Rebuilt whenever the transcript changes: accepting a second-pass check,
     * splitting a line, or an edit can all renumber segments, and a stale
     * timeline would highlight the wrong line.
     */
    private fun rebuildFollowTimeline() {
        followTimeline = com.meetily.mobile.data.PlaybackFollow.timeline(
            meeting?.segments.orEmpty()
        )
    }

    private fun toggleFollow() {
        followPlayback = !followPlayback
        settings.followPlayback = followPlayback
        // Turning it back on re-arms the auto-scroll that a drag suspended.
        followScrolling = followPlayback
        renderFollowButton()
        if (followPlayback) {
            readyPlayer?.let { updateFollow(it.currentPosition.toLong()) }
        } else {
            transcriptAdapter.clearActive()
        }
    }

    private fun renderFollowButton() {
        A11y.setOnOff(followButton, followPlayback)
        followButton.alpha = if (followPlayback) 1.0f else 0.45f
        followButton.setColorFilter(
            themeColor(
                if (followPlayback) {
                    com.google.android.material.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                }
            )
        )
    }

    /** Moves the highlight (and possibly the list) to [positionMs]. */
    private fun updateFollow(positionMs: Long) {
        if (!followPlayback) return
        val segmentIndex = com.meetily.mobile.data.PlaybackFollow
            .segmentAt(followTimeline, positionMs)
        if (segmentIndex < 0) {
            transcriptAdapter.clearActive()
            return
        }
        val segment = meeting?.segments?.getOrNull(segmentIndex)
        val words = segment?.words
        val base = segment?.audioMs
        val wordIndex = if (words.isNullOrEmpty() || base == null) {
            -1
        } else {
            com.meetily.mobile.data.PlaybackFollow.wordAt(words, positionMs - base)
        }
        val moved = segmentIndex != transcriptAdapter.activeSegment
        transcriptAdapter.setActive(segmentIndex, wordIndex)
        if (moved && followScrolling) scrollToActive(segmentIndex)
    }

    /**
     * Keeps the spoken line on screen without yanking the list on every line.
     * Only acts when the line has drifted outside a comfortable band, so a
     * reader a couple of lines ahead is left alone.
     */
    private fun scrollToActive(segmentIndex: Int) {
        val recycler = findViewById<RecyclerView>(R.id.detailRecycler) ?: return
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        // +1 for the header, which is its own adapter inside the ConcatAdapter.
        val position = transcriptAdapter.positionOfSegment(segmentIndex) + 1
        val holder = manager.findViewByPosition(position)
        val height = recycler.height
        if (holder != null && height > 0) {
            val top = holder.top
            val bottom = holder.bottom
            val bandTop = height / 5
            val bandBottom = height * 3 / 5
            if (top in bandTop..bandBottom && bottom <= height) return
        }
        manager.scrollToPositionWithOffset(position, (height * 0.3f).toInt())
    }

    // --- Volume boost -------------------------------------------------

    private fun cycleBoost() {
        val steps = intArrayOf(0, 4, 8, 12)
        val at = steps.indexOfFirst { it == boostDb }
        boostDb = steps[(at + 1).mod(steps.size)]
        settings.playbackBoostDb = boostDb
        renderBoostLabel()
        // The gain stage lives with the player (MeetingPlayback), so it
        // carries on in the background. Not every device provides the
        // effect; a failure hides the control rather than killing playback.
        MeetingPlayback.setBoost(boostDb)
        if (MeetingPlayback.boostUnavailable) playerBoostButton.visibility = View.GONE
        if (boostDb > 0) {
            Toast.makeText(this, R.string.playback_boost_noise, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderBoostLabel() {
        playerBoostButton.text = if (boostDb <= 0) {
            getString(R.string.playback_boost_off)
        } else {
            getString(R.string.playback_boost_on, boostDb)
        }
        playerBoostButton.alpha = if (boostDb <= 0) 0.45f else 1.0f
        // The button's name is fixed, so its text (the level) never reached
        // the screen reader.
        androidx.core.view.ViewCompat.setStateDescription(
            playerBoostButton,
            if (boostDb <= 0) getString(R.string.a11y_off)
            else getString(R.string.a11y_boost_state, boostDb)
        )
    }

    private fun updatePlayerUi(p: MediaPlayer) {
        playerSeek.progress = p.currentPosition
        setTextIfChanged(
            playerTime,
            getString(
                R.string.player_time,
                formatClock(p.currentPosition),
                formatClock(p.duration)
            )
        )
        if (tab == Tab.AUDIO) updateAudioTabPosition(p.currentPosition, p.duration)
    }

    /**
     * These labels are wrap_content, so every setText asks for a layout pass
     * — through the whole RecyclerView for the ones in the header — even
     * when the text is the same. On the playback tick that was ten wasted
     * passes a second: the clock only changes once a second.
     */
    private fun setTextIfChanged(view: TextView, text: CharSequence) {
        if (!android.text.TextUtils.equals(view.text, text)) view.text = text
    }

    // --- Clear voice ---------------------------------------------------------

    /**
     * The player plays (or will play) the meeting's clear-voice copy rather
     * than its recording. Kept across rotation; falls back to the recording
     * whenever there is no copy to play (playbackFileOrNull).
     */
    private var playingClearVoice = false

    /** The player bar's clear-voice row; built with the player. */
    private var clearVoiceBar: ClearVoiceBar? = null

    /** The file the player opens: the copy when chosen and on disk, else the recording. */
    private fun playbackFileOrNull(): File? {
        if (playingClearVoice) clearVoiceBar?.copyFile()?.let { return it }
        return audioFileOrNull()
    }

    private fun setUpClearVoice() {
        if (clearVoiceBar != null) return
        clearVoiceBar = ClearVoiceBar(
            this,
            row = findViewById(R.id.clearVoiceRow),
            chip = findViewById(R.id.clearVoiceChip),
            status = findViewById(R.id.clearVoiceStatus),
            delete = findViewById(R.id.clearVoiceDelete),
            host = object : ClearVoiceBar.Host {
                override fun meeting(): Meeting? = meeting

                override fun audioDurationMs(): Long =
                    readyPlayer?.duration?.toLong()?.takeIf { it > 0L }
                        ?: meeting?.let { checkAudioMs(it) } ?: 0L

                override fun playingCopy(): Boolean = playingClearVoice

                override fun switchTo(copy: Boolean) = switchPlaybackSource(copy)

                override fun dropCopy(name: String) = dropClearVoiceCopy(name)

                override fun refresh() = requestSync()
            }
        )
        if (started) clearVoiceBar?.attach() else clearVoiceBar?.render()
    }

    /**
     * Moves the player between the recording and the clear-voice copy. Both
     * share one timeline, so a prepared player is rebuilt on the other file
     * at the same position, playing again if it was playing; one not made
     * yet just opens the other file when it is first used.
     * MeetingPlayback does the reopen, keeping audio focus and the
     * notification through it, so this works the same with the screen
     * showing playback that carried on in the background.
     */
    private fun switchPlaybackSource(copy: Boolean) {
        if (copy == playingClearVoice) return
        playingClearVoice = copy
        playerHandler.removeCallbacks(playerTick)
        pendingPlayerAction = null
        val m = meeting ?: return
        if (player == null) return
        val file = playbackFileOrNull()
        if (file == null) {
            MeetingPlayback.stopMeeting(m.id)
            return
        }
        if (!MeetingPlayback.switchSource(this, m.id, file, copy = file.name != m.audioFile)) {
            onPlayerFailed()
            return
        }
        // Reopening (onPlayerPrepared follows), or let go while it was
        // still preparing: the next use opens the chosen file.
        playPauseButton.alpha = if (player != null && !playerReady) 0.5f else 1f
    }

    /**
     * Deletes the clear-voice copy [name] through the screen's save path, as
     * Remove audio does: the screen's copy drops the reference (persist),
     * the stored copy drops it on the save thread, and only then does the
     * file go. The recording is untouched.
     */
    private fun dropClearVoiceCopy(name: String) {
        val current = meeting ?: return
        if (playingClearVoice) switchPlaybackSource(false)
        playingClearVoice = false
        current.clearVoiceFile = null
        persist(current)
        val app = applicationContext
        val id = current.id
        SYNC_EXECUTOR.execute {
            val store = MeetingStore(app)
            val released = store.mutateIf(id) { stored ->
                if (stored.clearVoiceFile == name) {
                    stored.clearVoiceFile = null
                    true
                } else {
                    false
                }
            }
            // Not under a copy being made again meanwhile: it has this name too.
            if ((released || !store.exists(id)) && !ClearVoiceService.isPreparing(id)) {
                AudioStore.fileFor(app, name).delete()
            }
        }
    }

    // --- Audio tab -----------------------------------------------------------

    private var waveformBars: FloatArray? = null
    private var waveformLoading = false
    private var speakerTurns: List<com.meetily.mobile.data.SpeakerTurns.Turn> = emptyList()

    /**
     * Builds the tab: the waveform (decoded once and cached), the speaker
     * strip, and the legend. The player bar above stays exactly where it was
     * — its volume boost, skip-silence and follow-along have no equivalent in
     * the design's transport row, and moving playback behind a tab would hide
     * follow-along at the moment it is most useful.
     */
    private fun renderAudioTab(m: Meeting) {
        val audio = m.audioFile
        if (audio.isNullOrBlank()) return
        val waveform = headerView.findViewById<WaveformView>(R.id.waveform)
        val strip = headerView.findViewById<SpeakerStripView>(R.id.speakerStrip)

        // The player was created lazily by play/seek only, so opening a
        // meeting and tapping straight to Audio left it null: both seek
        // callbacks below were no-ops, and the duration fell back to the last
        // segment's offset — which on a recording left running past the last
        // word is far short of the real length. The strip is scaled by that
        // value once and never re-submitted, so the two stacked timelines
        // disagreed for the life of the screen. Preparing is asynchronous;
        // onPlayerPrepared renders this tab again with the real length.
        val ready = readyPlayer
        if (ready == null) withPlayer()

        waveform.onSeek = { fraction ->
            withPlayer { p ->
                val target = (fraction * p.duration).toInt()
                p.seekTo(target)
                updatePlayerUi(p)
            }
        }
        strip.onSeekMs = { ms ->
            withPlayer { p ->
                p.seekTo(ms.toInt().coerceIn(0, p.duration))
                updatePlayerUi(p)
            }
        }

        val durationMs = (ready?.duration?.toLong() ?: 0L).takeIf { it > 0L }
            ?: m.segments.mapNotNull { it.audioMs }.maxOrNull()
            ?: 0L

        val built = com.meetily.mobile.data.SpeakerTurns.build(
            m.segments, durationMs
        ) { segment -> segmentSpeakerDisplay(this, segment) }
        speakerTurns = built.turns
        strip.submit(
            built.turns.map { SpeakerStripView.Turn(it.startMs, it.endMs, it.slot) },
            durationMs
        )
        renderSpeakerLegend(built.names, strip)

        val cached = waveformBars ?: com.meetily.mobile.data.Waveform.cached(this, audio)
        if (cached != null) {
            waveformBars = cached
            waveform.setBars(cached)
            setAudioAnalysing(false)
        } else if (!waveformLoading) {
            // Normally the import pass has already cached these, having
            // decoded the whole file to transcribe it. This is the fallback:
            // audio that was never imported, a cancelled run, or a sidecar
            // from an older version.
            //
            // Decoding an hour of AAC takes seconds — longer when a
            // transcription is running, since this thread competes with it —
            // so it happens once, off the main thread, and says so meanwhile.
            waveformLoading = true
            setAudioAnalysing(true)
            Thread {
                val bars = com.meetily.mobile.data.Waveform.compute(this, audio)
                runOnUiThread {
                    waveformLoading = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setAudioAnalysing(false)
                    if (bars == null) return@runOnUiThread
                    waveformBars = bars
                    waveform.setBars(bars)
                }
            }.apply { name = "waveform-decode" }.start()
        }
        updateAudioTabPosition(readyPlayer?.currentPosition ?: 0, durationMs.toInt())
    }

    /**
     * Says, in words, that the bars are not real yet.
     *
     * The card alone cannot carry this: a row of equal bars reads as a flat
     * recording, not as a pending one, and that is a claim about the user's
     * audio rather than about the app.
     */
    private fun setAudioAnalysing(analysing: Boolean) {
        headerView.findViewById<WaveformView>(R.id.waveform)
            ?.setAnalysing(analysing)
        headerView.findViewById<TextView>(R.id.audioStorageNote)?.setText(
            if (analysing) R.string.audio_reading_levels else R.string.audio_kept_here
        )
    }

    private fun renderSpeakerLegend(names: List<String>, strip: SpeakerStripView) {
        val row = headerView.findViewById<LinearLayout>(R.id.speakerLegendRow)
        row.removeAllViews()
        row.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        val density = resources.displayMetrics.density
        names.forEachIndexed { slot, name ->
            val label = TextView(this)
            label.text = name
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            label.setTextColor(strip.colorForSlot(slot))
            label.setPadding(0, 0, (12 * density).toInt(), 0)
            row.addView(label)
        }
    }

    /** Moves the playhead and the "now playing" line. Called on every tick. */
    private fun updateAudioTabPosition(positionMs: Int, durationMs: Int) {
        val waveform = headerView.findViewById<WaveformView>(R.id.waveform)
        val strip = headerView.findViewById<SpeakerStripView>(R.id.speakerStrip)
        val total = durationMs.coerceAtLeast(1)
        waveform.setProgress(positionMs.toFloat() / total)
        strip.setPositionMs(positionMs.toLong())

        setTextIfChanged(
            headerView.findViewById(R.id.audioElapsed), formatClock(positionMs)
        )
        setTextIfChanged(
            headerView.findViewById(R.id.audioRemaining),
            getString(R.string.audio_remaining, formatClock((total - positionMs).coerceAtLeast(0)))
        )

        val line = headerView.findViewById<TextView>(R.id.nowPlayingLine)
        // Through the follow index (a binary search) rather than a scan of
        // the whole transcript on every tick.
        val index = com.meetily.mobile.data.PlaybackFollow
            .segmentAt(followTimeline, positionMs.toLong())
        val spoken = meeting?.segments?.getOrNull(index)
        if (spoken == null || spoken.text.isBlank()) {
            line.visibility = View.GONE
        } else {
            val who = segmentSpeakerDisplay(this, spoken)
            line.visibility = View.VISIBLE
            setTextIfChanged(line, if (who.isNullOrBlank()) spoken.text else "$who — ${spoken.text}")
        }
    }

    private fun formatClock(ms: Int): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /**
     * Cuts the tapped line's moment (with a second of lead-in/out) into a
     * small WAV and hands it to the share sheet.
     */
    private fun shareClip(index: Int) {
        val m = meeting ?: return
        val segment = m.segments.getOrNull(index) ?: return
        val audioMs = segment.audioMs ?: return
        val source = audioFileOrNull() ?: return
        // Word stamps are chunk-relative: the speech ends at audioMs +
        // last-word start (+ tail), not at audioMs + duration-from-first-word.
        val words = segment.words
        val start = (audioMs - 1_000L).coerceAtLeast(0)
        val end = if (!words.isNullOrEmpty()) {
            audioMs + words.last().ms + 1_600L
        } else {
            audioMs + com.meetily.mobile.summarize.MeetingStats
                .estimateDurationMs(segment.text, null) + 1_000L
        }
        Toast.makeText(this, R.string.clip_preparing, Toast.LENGTH_SHORT).show()
        // What the player is playing: the clear-voice copy or the recording.
        val clearVoiceInUse = playingClearVoice && clearVoiceBar?.copyFile() != null
        Thread {
            val clip = com.meetily.mobile.export.ClipExporter
                .export(this, source, start, end, useEnhanced = clearVoiceInUse)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (clip == null) {
                    Toast.makeText(this, R.string.clip_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(
                        Intent.EXTRA_STREAM,
                        AudioStore.uriFor(this@MeetingDetailActivity, clip)
                    )
                    putExtra(Intent.EXTRA_TEXT, "“" + segment.text.take(400) + "”")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(
                    Intent.createChooser(send, getString(R.string.share_clip_action))
                )
            }
        }.apply {
            name = "clip-export"
            start()
        }
    }

    private fun shareAudio() {
        val file = audioFileOrNull()
        if (file == null) {
            Toast.makeText(this, R.string.no_audio_kept, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "aac") "audio/aac" else "audio/*"
            putExtra(Intent.EXTRA_STREAM, AudioStore.uriFor(this@MeetingDetailActivity, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_audio)))
    }

    // --- Transcript editing -------------------------------------------------

    /** Fix transcription errors in place; clearing all text deletes the line. */
    private fun editSegmentText(index: Int) {
        val m = meeting ?: return
        val segment = m.segments.getOrNull(index) ?: return
        val input = EditText(this).apply {
            setText(segment.text)
            setSelection(segment.text.length)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_text_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (index !in m.segments.indices) return@setPositiveButton
                val newText = input.text.toString().trim()
                if (newText == segment.text) return@setPositiveButton
                if (newText.isBlank()) {
                    confirmDeleteSegment(index)
                } else {
                    // Timings re-aligned to the new wording, not dropped:
                    // fixing one word kept costing the line its tap-to-seek.
                    val old = m.segments[index]
                    m.segments[index] = old.copy(
                        text = newText,
                        words = com.meetily.mobile.data.WordRealign
                            .realign(old.text, old.words, newText)
                    )
                    persist(m)
                    transcriptAdapter.update(index, m.segments[index])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    // --- Find & replace (MeetingReplace) -------------------------------------

    private val replace by lazy {
        MeetingReplace(this, object : MeetingReplace.Host {
            override fun liveMeeting(): Meeting? = meeting

            override fun takeFieldEdits() = saveEdits()

            override fun replaced(meeting: Meeting) = onReplaced(meeting)

            override fun snackbarAnchor(): View? = shownPlayerBar()
        })
    }

    private fun shownPlayerBar(): View? =
        if (::playerBar.isInitialized && playerBar.visibility == View.VISIBLE) playerBar else null

    /** Saves a replace (or its undo) and re-renders everything it can reach. */
    private fun onReplaced(m: Meeting) {
        if (m !== meeting) return
        persist(m)
        renderMeta(m)
        renderTranscript(m)
        renderStats(m)
        renderSummary(m.summary)
        renderActionItems(m)
        notesInput.setText(m.notes)
        if (headerView.findViewById<View>(R.id.notesViewMode).visibility == View.VISIBLE) {
            renderNotes(m)
        }
        syncNotesButtons()
        invalidateOptionsMenu()
    }

    // --- Remove a passage (PassageUi, PassageService) -------------------------

    private val passage by lazy {
        PassageUi(this, object : PassageUi.Host {
            override fun liveMeeting(): Meeting? = meeting

            override fun flushEdits() {
                saveEdits()
                requestSync()
                flushSync()
            }

            override fun audioFile(): File? = audioFileOrNull()

            override fun playerDurationMs(): Long = readyPlayer?.duration?.toLong()?.takeIf { it > 0L } ?: 0L

            override fun releasePlayer() = releasePlayerForPassage()

            override fun passageRemoved(span: com.meetily.mobile.data.PassagePlan.Span) = onPassageRemoved(span)
        })
    }

    /**
     * Stops playback and lets go of the recording, as Remove audio does:
     * the passage run replaces the file, and the player must not go on
     * playing the passage from the one it opened. The player is
     * MeetingPlayback's, so this also ends playback carried on in the
     * background and its notification; the next play opens the new file.
     */
    private fun releasePlayerForPassage() {
        playerHandler.removeCallbacks(playerTick)
        pendingPlayerAction = null
        (meeting?.id ?: playbackScreenId)?.let { MeetingPlayback.stopMeeting(it) }
        if (::playPauseButton.isInitialized) setPlayPauseIcon(playing = false)
    }

    /**
     * A passage was removed from the stored meeting. This copy takes the
     * same removal (so a line edited here meanwhile cannot bring it back
     * through the next save), the player and the waveform let go of the
     * old recording, and the sync takes in the new audio, the clear-voice
     * copy gone and the resume point.
     */
    private fun onPassageRemoved(span: com.meetily.mobile.data.PassagePlan.Span) {
        releasePlayerForPassage()
        playingClearVoice = false
        waveformBars = null
        whenLoaded { m ->
            // The span as confirmed, never moved again: this copy may hold
            // the stored result already (a sync or the load may have read it
            // first), and moving an edge over the line before the passage
            // would delete that line too (PassagePlan.plan).
            val plan = com.meetily.mobile.data.PassagePlan.plan(
                m.segments, span, null, m.highlightMarksMs, m.chapters, m.transcriptResumeMs,
                snap = false
            )
            com.meetily.mobile.data.PassagePlan.applyTo(m, plan)
            // The file the run wrote (PassageSwap names it from the old one),
            // so the Audio tab draws its waveform now rather than the old one's.
            val replaced = m.audioFile?.let { com.meetily.mobile.data.PassageSwap.newAudioName(it) }
            if (replaced != null && AudioStore.exists(this, replaced)) {
                m.audioFile = replaced
                m.clearVoiceFile = null
            }
            requestSync()
            if (isFinishing || isDestroyed) return@whenLoaded
            applyTabState()
            clearVoiceBar?.render()
            renderMeta(m)
            renderTranscript(m)
            renderStats(m)
            renderSummary(m.summary)
            invalidateOptionsMenu()
        }
    }

    private fun confirmDeleteSegment(index: Int) {
        val m = meeting ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_segment_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (index !in m.segments.indices) return@setPositiveButton
                m.segments.removeAt(index)
                persist(m)
                renderTranscript(m)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Separate overlapping speakers: place the cursor where the second voice
     * starts and split. The first part keeps its speaker; the second opens
     * the speaker picker so it can be tagged (and, with audio, feed that
     * person's voice profile).
     */
    private fun showSplitDialog(index: Int) {
        val m = meeting ?: return
        val segment = m.segments.getOrNull(index) ?: return
        val input = EditText(this).apply {
            setText(segment.text)
            setSelection(segment.text.length / 2)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.split_title)
            .setMessage(R.string.split_instructions)
            .setView(container)
            .setPositiveButton(R.string.split_button) { _, _ ->
                if (index !in m.segments.indices) return@setPositiveButton
                val parts = TranscriptSplitter.split(
                    segment = m.segments[index],
                    next = m.segments.getOrNull(index + 1),
                    charPos = input.selectionStart,
                    editedText = input.text.toString()
                )
                if (parts == null) {
                    Toast.makeText(this, R.string.split_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                m.segments[index] = parts.first
                m.segments.add(index + 1, parts.second)
                persist(m)
                renderTranscript(m)
                // Tag who said the second half right away.
                assignSpeaker(index + 1)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    /**
     * After-the-fact voiceprint learning: when a segment (or whole cluster)
     * gets tagged with a name and the meeting kept its audio, offer to feed
     * those exact audio windows into the person's voice profile so future
     * meetings label them automatically.
     */
    private fun promptVoiceprintUpdate(name: String, segmentIndices: List<Int>) {
        val m = meeting ?: return
        val file = audioFileOrNull() ?: return
        val dModel = DiarizationModels.byKey(settings.diarizationModel)
        if (!DiarizationModels.isDownloaded(this, dModel)) return
        // Window = this segment's offset up to the next segment's offset.
        val windows = segmentIndices.mapNotNull { idx ->
            val segment = m.segments.getOrNull(idx) ?: return@mapNotNull null
            val start = segment.audioMs ?: return@mapNotNull null
            val end = m.segments.drop(idx + 1).firstNotNullOfOrNull { it.audioMs }
                ?.takeIf { it > start }
                ?: (start + 8_000L)
            start to end
        }.take(3)
        if (windows.isEmpty()) return

        AlertDialog.Builder(this)
            .setMessage(getString(R.string.voice_save_prompt, name))
            .setPositiveButton(R.string.voice_save_yes) { _, _ ->
                val modelPath = DiarizationModels.fileFor(this, dModel).absolutePath
                Thread {
                    var added = 0
                    val embedder = SherpaEmbedder.create(modelPath, dModel.key)
                    if (embedder != null) {
                        try {
                            for ((start, end) in windows) {
                                val pcm = AudioWindowExtractor
                                    .extract(this, file, start, end) ?: continue
                                val embedding = embedder.embed(pcm) ?: continue
                                // Audio is banked so the profile can roll
                                // over when the speaker model changes.
                                if (VoiceProfileStore.addSample(
                                        this, name, embedding, dModel.key, pcm
                                    )
                                ) {
                                    added++
                                }
                            }
                        } finally {
                            embedder.release()
                        }
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(
                            this,
                            if (added > 0) getString(R.string.voice_saved, name)
                            else getString(R.string.voice_save_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            }
            .setNegativeButton(R.string.voice_save_no, null)
            .showSecure()
    }

    // --- Transcript accuracy check -------------------------------------------

    /**
     * Runs the saved audio through a second model and compares the result
     * with what is stored. Nothing is overwritten without the user seeing the
     * differences first — that is why this is a check and not an "improve".
     */
    private fun confirmCheckAccuracy() {
        val m = meeting ?: return
        val file = audioFileOrNull()
        if (file == null) {
            Toast.makeText(this, R.string.no_audio_kept, Toast.LENGTH_SHORT).show()
            return
        }
        if (com.meetily.mobile.data.TranscriptDraft.isPending(this, m.id)) {
            openCheckReview(m.id)
            return
        }
        val downloaded = com.meetily.mobile.whisper.TranscriptionModels.downloadedKeys(this)
        if (downloaded.isEmpty()) {
            Toast.makeText(this, R.string.import_needs_whisper, Toast.LENGTH_LONG).show()
            return
        }
        // Ranked by transcription quality and across families — a second
        // Whisper size agrees with the first pass about most of what it gets
        // wrong, and file size says nothing about speed.
        val ranked = com.meetily.mobile.whisper.TranscriptionModels
            .rankedForCheck(
                this, m.transcriptModel, m.segments.take(60).joinToString(" ") { it.text }
            )
            .ifEmpty { downloaded }
        // The cross-family pick is only the RECOMMENDATION. The picker gets
        // everything downloaded — see allForCheck.
        val choices = com.meetily.mobile.whisper.TranscriptionModels
            .allForCheck(this, m.transcriptModel)
            .ifEmpty { downloaded }
        val willUse = ranked.first()
        val audioMs = checkAudioMs(m)
        // Offered only with a clear-voice copy on disk, and never ticked to
        // begin with: denoising can cost words, so the recording is checked
        // unless the user picks the copy, and the review then compares.
        val useCopy = clearVoiceBar?.copyFile()?.let { clearVoiceCheckOption() }
        AlertDialog.Builder(this)
            .setTitle(R.string.check_accuracy_title)
            .setView(useCopy?.first)
            .setMessage(
                // State the cost up front. The only speed signal before this
                // was the word "slow" inside a model's display name, in the
                // same parenthesis as "best accuracy" — and the first honest
                // number arrived two minutes after committing to the run.
                getString(
                    R.string.check_accuracy_message_estimate,
                    com.meetily.mobile.whisper.TranscriptionModels.displayName(willUse),
                    com.meetily.mobile.whisper.ModelSpeed
                        .estimateLabel(this, willUse, audioMs)
                )
            )
            .setPositiveButton(R.string.check_accuracy_go) { _, _ ->
                val clearVoice = useCopy?.second?.isChecked == true
                if (choices.size == 1) {
                    // No picker to show, but the same fit warning as one.
                    val only = choices.first()
                    FitBadge.confirmChoice(
                        this,
                        com.meetily.mobile.whisper.TranscriptionModels.displayName(only),
                        com.meetily.mobile.data.DeviceProbe.verdict(
                            this,
                            com.meetily.mobile.whisper.TranscriptionModels.fitNeedOrNull(only)
                        ),
                        alreadyActive = false
                    ) { startCheck(m, only, clearVoice = clearVoice) }
                } else {
                    chooseCheckModel(m, choices, willUse, clearVoice)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The accuracy check's "Transcribe the clear-voice copy instead" box,
     * unticked, with its note on what denoising can cost. The view goes
     * into the dialog; the box is read when it is confirmed.
     */
    private fun clearVoiceCheckOption(): Pair<View, android.widget.CheckBox> {
        val density = resources.displayMetrics.density
        val side = (24 * density).toInt()
        val box = android.widget.CheckBox(this).apply {
            setText(R.string.clearvoice_check_use_copy)
            isChecked = false
            minHeight = A11y.minTouchPx(this@MeetingDetailActivity)
        }
        val note = TextView(this).apply {
            setText(R.string.clearvoice_check_use_copy_note)
            setTextAppearance(R.style.TextAppearance_Nocturne_BodySmall)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, (8 * density).toInt(), side, 0)
            addView(box)
            addView(note)
        }
        return column to box
    }

    /** Audio length this check would cover, for the time estimate. */
    private fun checkAudioMs(m: Meeting): Long {
        m.segments.lastOrNull { it.audioMs != null }?.audioMs?.let { return it }
        val first = m.segments.firstOrNull()?.timestampMs ?: return 0L
        val last = m.segments.lastOrNull()?.timestampMs ?: return 0L
        return (last - first).coerceAtLeast(0L)
    }

    private fun chooseCheckModel(
        m: Meeting,
        ranked: List<String>,
        preferred: String,
        clearVoice: Boolean = false
    ) {
        val models = com.meetily.mobile.whisper.TranscriptionModels
        ModelPickerSheet.show(
            this,
            getString(R.string.check_choose_model),
            entriesProvider = {
                ranked.map { key ->
                    ModelPickerSheet.Entry(
                        key = key,
                        title = models.displayName(key),
                        meta = if (key == m.transcriptModel) {
                            getString(R.string.check_model_made_current)
                        } else {
                            models.metaLineFor(this, key, checkAudioMs(m))
                        },
                        downloaded = true,
                        selected = key == preferred,
                        fit = com.meetily.mobile.data.DeviceProbe.verdict(
                            this, models.fitNeedOrNull(key)
                        ),
                        // Selected marks the recommendation here, not the
                        // model in use, and a check run is never a no-op
                        // re-pick: a tight or too-big model always warns.
                        active = false
                    )
                }
            },
            onPick = { key -> startCheck(m, key, clearVoice = clearVoice) }
        )
    }

    /**
     * [language]: "auto" or a code for this pass only (the language chip);
     * null uses Settings. [clearVoice]: transcribe the clear-voice copy, as
     * the user chose in the accuracy check (never otherwise).
     */
    private fun startCheck(
        m: Meeting,
        modelKey: String,
        language: String? = null,
        clearVoice: Boolean = false
    ) {
        val file = audioFileOrNull() ?: return
        val copy = if (clearVoice) clearVoiceBar?.copyFile() else null
        if (clearVoice && copy == null) {
            Toast.makeText(this, R.string.clearvoice_check_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        // A second pass queued behind the one already checking this meeting
        // would overwrite that pass's draft before the user had reviewed it.
        if (ImportService.isRunning && ImportService.isRecheck &&
            ImportService.currentMeetingId == m.id
        ) {
            Toast.makeText(this, R.string.check_already_running, Toast.LENGTH_LONG).show()
            return
        }
        // The check reads the meeting's own saved audio, so there is nothing
        // to stage — queueing it is enough when something else is running.
        if (!JobGate.canStartBatch()) {
            JobGate.requestCheck(
                this, m.id, modelKey, whenCharging = false, required = true,
                language = language.orEmpty(), clearVoice = copy != null
            )
            Toast.makeText(this, R.string.check_queued, Toast.LENGTH_LONG).show()
            return
        }
        val start = Intent(this, ImportService::class.java)
            .setAction(ImportService.ACTION_START)
            .setData(AudioStore.uriFor(this, file))
            .putExtra(ImportService.EXTRA_NAME, m.title)
            .putExtra(ImportService.EXTRA_MODEL, modelKey)
            .putExtra(ImportService.EXTRA_RECHECK_MEETING_ID, m.id)
            .putExtra(ImportService.EXTRA_USER_STARTED, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        language?.let { start.putExtra(ImportService.EXTRA_LANGUAGE, it) }
        if (copy != null) start.putExtra(ImportService.EXTRA_CLEAR_VOICE, true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(start)
        } else {
            startService(start)
        }
        Toast.makeText(this, R.string.check_started, Toast.LENGTH_LONG).show()
    }

    private fun openCheckReview(meetingId: String) {
        // The review compares against the stored transcript.
        flushSync()
        startActivity(
            Intent(this, TranscriptCheckActivity::class.java)
                .putExtra(TranscriptCheckActivity.EXTRA_MEETING_ID, meetingId)
        )
    }

    /** Asked once per visit: a finished check is waiting to be looked at. */
    private var checkPromptShown = false

    private fun maybeOfferCheckReview() {
        val m = meeting ?: return
        if (checkPromptShown || isFinishing || isDestroyed) return
        if (!com.meetily.mobile.data.TranscriptDraft.isPending(this, m.id)) return
        checkPromptShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.check_ready_notif)
            .setMessage(R.string.check_ready_prompt)
            .setPositiveButton(R.string.check_review) { _, _ -> openCheckReview(m.id) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    // --- Rich notes ----------------------------------------------------------

    /** True while a notes enhancement is running for THIS meeting. */
    private fun notesEnhanceActive(): Boolean =
        SummaryService.isRunning &&
            SummaryService.currentMode == SummaryService.MODE_NOTES &&
            SummaryService.currentMeetingId == meeting?.id

    private fun setUpNotesEditor() {
        headerView.findViewById<View>(R.id.editNotesButton).setOnClickListener {
            // Editing during an active enhancement would race the service's
            // save and silently discard the enhanced notes — block it.
            if (notesEnhanceActive()) {
                Toast.makeText(this, R.string.notes_enhance_wait, Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            setNotesMode(viewMode = false)
            notesInput.requestFocus()
        }
        headerView.findViewById<View>(R.id.doneNotesButton).setOnClickListener {
            val m = meeting ?: return@setOnClickListener
            m.notes = notesInput.text.toString()
            persist(m)
            setNotesMode(viewMode = m.notes.isNotBlank())
        }
        headerView.findViewById<View>(R.id.fmtBold).setOnClickListener {
            wrapSelection("**", "**")
        }
        headerView.findViewById<View>(R.id.fmtItalic).setOnClickListener {
            wrapSelection("*", "*")
        }
        headerView.findViewById<View>(R.id.fmtHeading).setOnClickListener {
            prefixCurrentLine("## ")
        }
        headerView.findViewById<View>(R.id.fmtBullet).setOnClickListener {
            prefixCurrentLine("- ")
        }
        headerView.findViewById<View>(R.id.fmtCheck).setOnClickListener {
            prefixCurrentLine("- [ ] ")
        }
        headerView.findViewById<View>(R.id.enhanceNotesButton).setOnClickListener {
            startNotesEnhance()
        }
        headerView.findViewById<View>(R.id.revertNotesButton).setOnClickListener {
            revertEnhancedNotes()
        }
    }

    private fun setNotesMode(viewMode: Boolean) {
        headerView.findViewById<View>(R.id.notesViewMode).visibility =
            if (viewMode) View.VISIBLE else View.GONE
        headerView.findViewById<View>(R.id.notesEditMode).visibility =
            if (viewMode) View.GONE else View.VISIBLE
        if (viewMode) meeting?.let { renderNotes(it) }
        syncNotesButtons()
    }

    /** Enhance is offered when there are notes + transcript + an LLM; revert
     *  appears once an enhancement has something to roll back to. */
    private fun syncNotesButtons() {
        val m = meeting ?: return
        val canEnhance = settings.useLlm && settings.llmConfigured &&
            m.notes.isNotBlank() && m.segments.isNotEmpty()
        headerView.findViewById<View>(R.id.enhanceNotesButton).visibility =
            if (canEnhance) View.VISIBLE else View.GONE
        headerView.findViewById<View>(R.id.revertNotesButton).visibility =
            if (m.notesOriginal.isNotBlank()) View.VISIBLE else View.GONE
    }

    /** Hands the enhancement run to SummaryService (survives navigation). */
    private fun startNotesEnhance() {
        val m = meeting ?: return
        saveEdits()
        flushSync()
        if (m.notes.isBlank()) return
        if (SummaryService.isRunning) {
            Toast.makeText(this, R.string.summary_busy, Toast.LENGTH_SHORT).show()
            return
        }
        // As with the speaker pass: notes enhancement has no queue kind, so
        // it is refused rather than deferred into something else.
        if (!JobGate.canStartBatch()) {
            Toast.makeText(this, R.string.busy_heavy_job, Toast.LENGTH_LONG).show()
            return
        }
        SummaryService.start(
            this, m.id, settings.summaryTemplate, SummaryService.MODE_NOTES
        )
        bindSummaryService()
        Toast.makeText(this, R.string.notes_enhance_started, Toast.LENGTH_LONG).show()
    }

    private fun revertEnhancedNotes() {
        val m = meeting ?: return
        if (m.notesOriginal.isBlank()) return
        m.notes = m.notesOriginal
        m.notesOriginal = ""
        persist(m)
        notesInput.setText(m.notes)
        renderNotes(m)
        syncNotesButtons()
        Toast.makeText(this, R.string.notes_reverted, Toast.LENGTH_SHORT).show()
    }

    private fun renderNotes(m: Meeting) {
        val container = headerView.findViewById<LinearLayout>(R.id.notesRendered)
        NotesRenderer.render(container, m.notes) { line ->
            m.notes = NotesMarkdown.toggleCheck(m.notes, line)
            notesInput.setText(m.notes)
            persist(m)
            renderNotes(m)
        }
    }

    private fun wrapSelection(prefix: String, suffix: String) {
        val rawStart = notesInput.selectionStart.coerceAtLeast(0)
        val rawEnd = notesInput.selectionEnd.coerceAtLeast(0)
        val start = minOf(rawStart, rawEnd)
        val end = maxOf(rawStart, rawEnd)
        notesInput.text.insert(end, suffix)
        notesInput.text.insert(start, prefix)
        notesInput.setSelection(start + prefix.length, end + prefix.length)
    }

    private fun prefixCurrentLine(prefix: String) {
        val pos = notesInput.selectionStart.coerceAtLeast(0)
        val text = notesInput.text.toString()
        val lineStart = text.lastIndexOf('\n', pos - 1) + 1
        notesInput.text.insert(lineStart, prefix)
    }

    // --- File attachments ----------------------------------------------------

    private fun importAttachment(uri: Uri) {
        val m = meeting ?: return
        val display = attachmentDisplayName(uri)
        val file = try {
            AttachmentStore.newFile(this, m.id, display)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.attach_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // Any file type from any provider: a large video, or a document that
        // Drive downloads while it is read, blocked the main thread for the
        // whole copy.
        runCopy(
            copy = {
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: throw RuntimeException("cannot open file")
                if (file.length() <= 0) throw RuntimeException("empty file")
            },
            file = file,
            failedText = R.string.attach_failed,
            record = { it.attachmentsList.add(Attachment(file.name, display)) }
        ) {
            renderAttachments(m)
        }
    }

    private fun attachmentDisplayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index =
                    cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.section_files)
    }

    private fun renderAttachments(m: Meeting) {
        val list = headerView.findViewById<LinearLayout>(R.id.attachmentList)
        list.removeAllViews()
        for (attachment in m.attachmentsList.toList()) {
            val file = AttachmentStore.fileFor(this, attachment.file)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                // One line of 15sp text plus that padding came to about 36dp.
                minimumHeight = dp(A11y.MIN_TOUCH_DP)
                setBackgroundResource(
                    android.R.attr.selectableItemBackground.let { attr ->
                        val tv = android.util.TypedValue()
                        theme.resolveAttribute(attr, tv, true)
                        tv.resourceId
                    }
                )
            }
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_attach_file)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    themeColor(com.google.android.material.R.attr.colorPrimary)
                )
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(this).apply {
                text = attachment.name
                textSize = 15f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(10) }
            }
            val size = TextView(this).apply {
                text = formatFileSize(file.length())
                textSize = 12f
                setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
            }
            row.addView(icon)
            row.addView(label)
            row.addView(size)
            row.setOnClickListener { openAttachment(attachment) }
            row.setOnLongClickListener {
                confirmRemoveAttachment(attachment)
                true
            }
            A11y.labelClick(row, getString(R.string.a11y_open_file))
            A11y.labelLongClick(row, getString(R.string.a11y_remove_file))
            list.addView(row)
        }
    }

    private fun openAttachment(attachment: Attachment) {
        val file = AttachmentStore.fileFor(this, attachment.file)
        if (!file.exists()) {
            Toast.makeText(this, R.string.attachment_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = AttachmentStore.uriFor(this, file)
        val mime = contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                file.extension.lowercase()
            )
            ?: "*/*"
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.attachment_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRemoveAttachment(attachment: Attachment) {
        val m = meeting ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.attachment_remove_confirm, attachment.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                m.attachmentsList.remove(attachment)
                AttachmentStore.delete(this, attachment.file)
                persist(m)
                renderAttachments(m)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // --- Rename ---------------------------------------------------------------

    private fun renameMeeting() {
        val m = meeting ?: return
        val input = EditText(this).apply {
            setText(m.title)
            setSelection(text.length)
            hint = getString(R.string.rename_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_meeting_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name == m.title) return@setPositiveButton
                m.title = name
                persist(m)
                titleView.text = name
                Toast.makeText(this, R.string.renamed, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    // --- Topic chapters ------------------------------------------------------

    private fun topicsAction() {
        val m = meeting ?: return
        if (m.chapters.isEmpty()) {
            detectTopics(m)
            return
        }
        val labels = m.chapters.map { it.title } +
            getString(R.string.topics_redetect) +
            getString(R.string.topics_remove)
        AlertDialog.Builder(this)
            .setTitle(R.string.topics_title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < m.chapters.size -> jumpToChapter(m.chapters[which])
                    which == m.chapters.size -> detectTopics(m)
                    else -> {
                        m.chapters.clear()
                        persist(m)
                        renderTranscript(m)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    private fun jumpToChapter(chapter: com.meetily.mobile.data.Chapter) {
        val m = meeting ?: return
        if (!onTranscriptTab) switchTab(Tab.TRANSCRIPT)
        val segIndex = m.segments.indexOfFirst { it.timestampMs >= chapter.startMs }
        if (segIndex < 0) return
        val recycler = findViewById<RecyclerView>(R.id.detailRecycler)
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        // +1 skips the static document header inside the ConcatAdapter.
        lm.scrollToPositionWithOffset(
            1 + transcriptAdapter.positionOfSegment(segIndex), 48
        )
    }

    @Volatile private var topicsRunning = false

    private fun detectTopics(m: Meeting) {
        if (m.segments.size < 8) {
            Toast.makeText(this, R.string.topics_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        if (topicsRunning) {
            Toast.makeText(this, R.string.topics_working, Toast.LENGTH_SHORT).show()
            return
        }
        topicsRunning = true
        setTopicsBusy(true)
        val useLlm = settings.useLlm && settings.llmConfigured
        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val localOnly = settings.localOnlyLlm
        val segmentsSnapshot = m.segments.toList()
        val englishHeuristics = com.meetily.mobile.data.MeetingLanguage
            .englishHeuristics(m.transcriptLanguage())
        val meetingId = m.id
        val store = this.store
        Thread {
            var chapters: List<com.meetily.mobile.data.Chapter> = emptyList()
            var failure: String? = null
            var usedLlm = false
            if (useLlm) {
                try {
                    val lines = segmentsSnapshot.map { seg ->
                        val speaker = seg.speaker
                        if (speaker.isNullOrBlank()) seg.text else "$speaker: ${seg.text}"
                    }
                    // Windowed: a single call truncates to the model context,
                    // which used to leave long meetings chaptered only at the
                    // start. Progress is reported per window.
                    chapters = LlmClient
                        .chaptersWindowed(baseUrl, apiKey, model, localOnly, lines) { i, total ->
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    tagHint.text = getString(
                                        R.string.summary_stage_condense, i, total
                                    )
                                    // Bar and text step together: "window i
                                    // of total", both counting the window
                                    // being worked on.
                                    setTopicsProgress(i, total)
                                }
                            }
                        }
                        .filter { it.first in segmentsSnapshot.indices }
                        .map { (index, title) ->
                            com.meetily.mobile.data.Chapter(
                                title.take(60),
                                segmentsSnapshot[index].timestampMs
                            )
                        }
                    if (chapters.size < 2) chapters = emptyList() else usedLlm = true
                } catch (e: Exception) {
                    failure = e.message ?: "AI request failed"
                }
            }
            if (chapters.isEmpty()) {
                chapters = TopicChapters.buildLocal(segmentsSnapshot, englishHeuristics)
            }
            val result = chapters
            // SAVE FIRST, on this thread, against a freshly loaded copy: the
            // result must survive the screen being closed or rotated. This
            // used to be discarded whenever the activity had gone away.
            var saved = false
            if (result.isNotEmpty()) {
                try {
                    // One read-modify-write under the store lock: a save
                    // from the screen between a load and a save here was lost.
                    saved = store.mutate(meetingId) { target ->
                        target.chapters.clear()
                        target.chapters.addAll(result)
                    }
                } catch (_: Exception) {
                }
            }
            val hadFailure = failure
            val wasLlm = usedLlm
            runOnUiThread {
                topicsRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                setTopicsBusy(false)
                if (result.isEmpty()) {
                    Toast.makeText(this, R.string.topics_none, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (saved) {
                    meeting?.let { current ->
                        current.chapters.clear()
                        current.chapters.addAll(result)
                    }
                }
                collapsedApplied = false // re-collapse around the new chapters
                if (!onTranscriptTab) switchTab(Tab.TRANSCRIPT)
                else meeting?.let { renderTranscript(it) }
                when {
                    hadFailure != null -> Toast.makeText(
                        this, getString(R.string.topics_failed, hadFailure), Toast.LENGTH_LONG
                    ).show()
                    !wasLlm && useLlm -> Toast.makeText(
                        this, R.string.topics_offline_note, Toast.LENGTH_LONG
                    ).show()
                    else -> Toast.makeText(
                        this, getString(R.string.topics_found, result.size), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.apply {
            name = "topic-detect"
            start()
        }
    }

    /**
     * Speaker attribution reuses the transcript tab's status line and bar —
     * it is the same kind of work over the same lines, and a second widget
     * for it would just be clutter. [percent] null means the run is over.
     */
    private var speakersBusy = false

    private fun setSpeakersBusy(percent: Int?, stage: String?) {
        speakersBusy = percent != null
        if (!speakersBusy) {
            topicsProgress.visibility = View.GONE
            meeting?.let { renderTranscript(it) }
            return
        }
        tagHint.visibility = View.VISIBLE
        tagHint.text = stage?.takeIf { it.isNotBlank() }
            ?: getString(R.string.suggest_analyzing)
        if (percent != null && percent >= 0) {
            setTopicsProgress(percent, 100)
        } else {
            topicsProgress.isIndeterminate = true
            topicsProgress.visibility = View.VISIBLE
        }
    }

    /** Shows that topic detection is working; the menu item is gated too. */
    private fun setTopicsBusy(busy: Boolean) {
        if (busy) {
            tagHint.visibility = View.VISIBLE
            tagHint.text = getString(R.string.topics_working)
            // Starts indeterminate: the window count is only known once the
            // transcript has been split, which happens on the worker thread.
            topicsProgress.isIndeterminate = true
            topicsProgress.visibility = View.VISIBLE
        } else {
            topicsProgress.visibility = View.GONE
            meeting?.let { renderTranscript(it) }
        }
    }

    /** Drives the bar from chaptersWindowed's (window, total) callback. */
    private fun setTopicsProgress(window: Int, total: Int) {
        if (total <= 0) return
        if (topicsProgress.isIndeterminate) {
            // Material indicators refuse an in-place mode switch while
            // visible, so blink the bar around the change.
            topicsProgress.visibility = View.GONE
            topicsProgress.isIndeterminate = false
            topicsProgress.max = total
            topicsProgress.visibility = View.VISIBLE
        } else if (topicsProgress.max != total) {
            topicsProgress.max = total
        }
        topicsProgress.progress = window.coerceIn(0, total)
    }

    // --- Search moments and Find in meeting -----------------------------------

    /** Find in meeting, under the toolbar. Made in onCreate. */
    private lateinit var findBar: MeetingFindBar

    /** True until the line the screen was opened at (EXTRA_SEGMENT) is shown; saved with the state. */
    private var momentPending = false

    /** The find bar from the screen before a re-creation, until the read lands and it is restored. */
    private var pendingFindState: Bundle? = null

    private val endFlash = Runnable { transcriptAdapter.clearFlash() }

    private val findHost = object : MeetingFindBar.Host {
        override fun findTexts(): List<String>? = meeting?.segments?.map { it.text }

        override fun findFirstVisibleSegment(): Int {
            if (!onTranscriptTab) return 0
            val recycler = findViewById<RecyclerView>(R.id.detailRecycler) ?: return 0
            val manager = recycler.layoutManager as? LinearLayoutManager ?: return 0
            // -1 for the header, which is its own adapter inside the ConcatAdapter.
            return transcriptAdapter.segmentAtOrAfter(manager.findFirstVisibleItemPosition() - 1)
        }

        override fun findReveal(segment: Int, landing: Boolean) = revealSegment(segment, landing)

        override fun findLineLabel(segment: Int): String {
            val m = meeting ?: return ""
            val line = m.segments.getOrNull(segment) ?: return ""
            val time = com.meetily.mobile.data.ElapsedTime.label(line, m.createdAtMs)
            val speaker = segmentSpeakerDisplay(this@MeetingDetailActivity, line)
            return if (speaker.isNullOrBlank()) time else "$time, $speaker"
        }
    }

    /**
     * Opens at the moment a home search hit or an Ask-your-library source
     * points to (EXTRA_SEGMENT), with the words searched for ready in Find
     * in meeting (EXTRA_QUERY). Runs through whenLoaded, after the restored
     * scroll; once per screen, like the requested action.
     */
    private fun landOnMoment(m: Meeting) {
        if (!momentPending) return
        momentPending = false
        val segment = intent.getIntExtra(EXTRA_SEGMENT, -1)
        val query = intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }
        intent.removeExtra(EXTRA_SEGMENT)
        intent.removeExtra(EXTRA_QUERY)
        val line = if (segment in m.segments.indices) segment else -1
        if (query != null) {
            // Lands once the bar has found the words: in that line, or in the
            // nearest one still holding them if the meeting changed since.
            findBar.open(query, focusField = false, landOn = line)
        } else if (line >= 0) {
            revealSegment(line, landing = true)
        }
    }

    /**
     * Brings transcript line [segment] on screen by the expand-then-scroll
     * path: the transcript tab, the line's chapter expanded, then scrolled
     * to. [landing] (opening at a search hit) also marks the line for a
     * moment and gives it TalkBack's focus, which reads it out.
     */
    private fun revealSegment(segment: Int, landing: Boolean) {
        val m = meeting ?: return
        if (segment !in m.segments.indices || isFinishing || isDestroyed) return
        val switched = !onTranscriptTab
        if (switched) switchTab(Tab.TRANSCRIPT)
        // Asked for, like a drag: playback follow must not pull the list away.
        followScrolling = false
        val expanded = transcriptAdapter.ensureVisible(segment)
        if (switched || expanded || landing) {
            // The rows were just rebuilt, so the rows on screen cannot say
            // whether the line is already in view: always scroll.
            val recycler = findViewById<RecyclerView>(R.id.detailRecycler) ?: return
            val manager = recycler.layoutManager as? LinearLayoutManager ?: return
            manager.scrollToPositionWithOffset(
                transcriptAdapter.positionOfSegment(segment) + 1,
                (recycler.height * 0.3f).toInt()
            )
        } else {
            scrollToActive(segment)
        }
        if (landing) {
            transcriptAdapter.flashSegment(segment)
            mainHandler.removeCallbacks(endFlash)
            mainHandler.postDelayed(endFlash, MOMENT_FLASH_MS)
            focusLine(segment)
        }
    }

    /** Gives line [segment] TalkBack's focus once the list has laid it out, so it is read out. */
    private fun focusLine(segment: Int) {
        if (!A11y.isTouchExploring(this)) return
        val recycler = findViewById<RecyclerView>(R.id.detailRecycler) ?: return
        recycler.postDelayed({
            if (isFinishing || isDestroyed || !onTranscriptTab) return@postDelayed
            // Not positionOfSegment: a chapter collapsed since must stay so.
            val position = transcriptAdapter.visiblePositionOf(segment)
            if (position < 0) return@postDelayed
            // +1 for the header, which is its own adapter inside the ConcatAdapter.
            recycler.layoutManager?.findViewByPosition(position + 1)?.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null
            )
        }, MOMENT_FOCUS_DELAY_MS)
    }

    companion object {
        /**
         * Follow refresh while playing. Words last a few hundred ms, so a
         * slower tick visibly lags the audio; the cost is one row rebind, and
         * only when the highlighted word actually changes.
         */
        private const val FOLLOW_TICK_MS = 90L
        private const val IDLE_TICK_MS = 400L

        /**
         * Every meeting screen's saves, and its reads (MeetingLoadModel.open),
         * in order. Shared rather than per instance so a save from a screen
         * being rotated away still lands before the new instance's first read.
         * That holds for a save already queued; a follow-up save collapsed
         * into syncWanted is queued by onPause, before the new screen exists.
         */
        private val SYNC_EXECUTOR: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "meeting-screen-save")
            }

        /**
         * Above this many characters Share sends a file instead of text: the
         * text crosses binder up to three times as UTF-16, and past about
         * 1 MB the transaction fails and takes the app down with it.
         */
        private const val SHARE_TEXT_CAP = 100_000
        private const val SHARE_PREVIEW_CHARS = 2_000

        /**
         * Set only by RecordingActivity when a recording has just finished,
         * so the follow-on offer appears once rather than on every visit.
         */
        private const val STATE_PENDING_PHOTO = "pending_photo"
        const val EXTRA_OFFER_FOLLOW = "offer_follow"
        const val EXTRA_MEETING_ID = "meeting_id"

        /**
         * Optional, together: the meeting's title and createdAtMs, from a
         * caller that already has them (the home list), so the screen shows
         * them in its first frame. Without them the read supplies both.
         */
        const val EXTRA_TITLE = "meeting_title"
        const val EXTRA_CREATED_AT_MS = "meeting_created_at_ms"

        /** How long a read may take before the progress row appears. */
        private const val LOADING_REVEAL_MS = 250L

        // What a re-created screen needs before (and after) its own read.
        private const val STATE_PHOTO_RESULT = "photo_result"
        private const val STATE_ACTION_PENDING = "action_pending"
        private const val STATE_OFFER_FOLLOW = "offer_follow_pending"
        private const val STATE_CLEAR_VOICE = "clear_voice_playing"
        private const val STATE_TITLE = "header_title"
        private const val STATE_CREATED_AT = "header_created_at"
        private const val STATE_VIEW = "view_state"
        private const val VIEW_TAB = "tab"
        private const val VIEW_CHAPTERS_ARRANGED = "chapters_arranged"
        private const val VIEW_COLLAPSED = "collapsed_chapters"
        private const val VIEW_POSITION = "position"
        private const val VIEW_OFFSET = "offset"

        /** Set by the accuracy check when the user asked to redo the summary. */
        const val EXTRA_REGENERATE_SUMMARY = "regenerate_summary"

        /** One of the ACTION_* values below, run once the screen is up. */
        const val EXTRA_ACTION = "detail_action"
        const val ACTION_RENAME = "rename"
        const val ACTION_SUMMARIZE = "summarize"
        const val ACTION_TOPICS = "topics"
        const val ACTION_CHECK_ACCURACY = "check_accuracy"
        const val ACTION_SHARE = "share"
        /** Continue an unfinished transcription (ContinueTranscription). */
        const val ACTION_CONTINUE_TRANSCRIPTION = "continue_transcription"

        /**
         * Optional: the transcript line (segment index, Int) to open at, as
         * a home search hit or an Ask-your-library source does. The screen
         * switches to the transcript, expands the line's chapter, scrolls to
         * it and marks it for a moment.
         */
        const val EXTRA_SEGMENT = "moment_segment"

        /**
         * Optional: words (String) to open Find in meeting with, marked
         * throughout. With EXTRA_SEGMENT, the screen lands on that line's
         * match, or the nearest line still holding the words.
         */
        const val EXTRA_QUERY = "moment_query"

        private const val STATE_MOMENT_PENDING = "moment_pending"
        private const val STATE_FIND = "find_bar"

        /** How long a line opened at stays marked. */
        private const val MOMENT_FLASH_MS = 2_500L

        /** Time for the list to lay out the line, and for TalkBack's own focus on a new screen to settle. */
        private const val MOMENT_FOCUS_DELAY_MS = 400L
    }
}
