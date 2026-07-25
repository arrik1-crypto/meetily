package com.meetily.mobile

import android.content.Intent
import android.graphics.Paint
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
import com.meetily.mobile.export.MeetingExporter
import com.meetily.mobile.notes.NotesMarkdown
import com.meetily.mobile.notes.NotesRenderer
import com.meetily.mobile.reminders.Reminders
import com.meetily.mobile.summarize.ActionItems
import com.meetily.mobile.summarize.CustomTemplates
import com.meetily.mobile.summarize.ExtractiveSummarizer
import com.meetily.mobile.summarize.LlmClient
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

    // --- Summary generation (owned by SummaryService) -----------------------

    private var summaryService: SummaryService? = null
    private var summaryBound = false

    private val summaryObserver = object : SummaryService.Observer {
        override fun onSummaryProgress(meetingId: String, percent: Int, stage: String) {
            if (isFinishing || isDestroyed) return
            if (meetingId != meeting?.id) return
            // Notes-enhancement progress lives in the notification + home
            // banner; only summary runs take over the summary section UI.
            if (SummaryService.currentMode == SummaryService.MODE_NOTES) return
            showSummarizingUi()
            summaryView.text = stage
            setSummaryProgress(percent)
        }

        override fun onSummaryDone(meetingId: String, failed: Boolean) {
            if (isFinishing || isDestroyed) return
            if (meetingId != meeting?.id) return
            if (SummaryService.currentMode == SummaryService.MODE_NOTES) {
                if (!failed) refreshNotesFromStore()
            } else {
                refreshSummaryFromStore(reveal = true)
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

    private fun bindSummaryService() {
        if (summaryBound) return
        bindService(
            Intent(this, SummaryService::class.java),
            summaryConnection,
            android.content.Context.BIND_AUTO_CREATE
        )
        summaryBound = true
    }

    override fun onStart() {
        super.onStart()
        val m = meeting ?: return
        refreshSegmentsFromStore()
        if (SummaryService.isRunning && SummaryService.currentMeetingId == m.id) {
            // Coming back (or rotating) mid-generation: restore progress UI
            // and reattach to the run. (Notes runs show no summary-section
            // UI; the observer refreshes notes when they land.)
            if (SummaryService.currentMode != SummaryService.MODE_NOTES) {
                showSummarizingUi()
            }
            bindSummaryService()
        } else {
            // A generation may have finished while this screen was away.
            refreshSummaryFromStore(reveal = false)
            refreshNotesFromStore()
        }
        maybeOfferCheckReview()
    }

    override fun onStop() {
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
    private fun persist(m: Meeting) {
        store.saveMerging(m, segmentsSeenThroughMs)
        segmentsSeenThroughMs = maxOf(
            segmentsSeenThroughMs,
            com.meetily.mobile.data.MeetingMerge.highWaterMs(m.segments)
        )
    }

    /**
     * Picks up transcript lines that landed on disk while this screen held an
     * older copy — the recording service can still deliver a final chunk after
     * the session closes. Folds rather than replaces: this copy carries the
     * user's edits, splits, highlights and speaker tags.
     */
    private fun refreshSegmentsFromStore() {
        val m = meeting ?: return
        val saved = store.load(m.id) ?: return
        val merge = com.meetily.mobile.data.MeetingMerge
        if (merge.foldLateSegments(m, saved, segmentsSeenThroughMs) == 0) return
        segmentsSeenThroughMs = merge.highWaterMs(m.segments)
        renderMeta(m)
        renderTranscript(m)
        renderStats(m)
    }

    private fun renderMeta(m: Meeting) {
        val wordCount = m.transcriptText()
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
        metaView.text = getString(R.string.detail_meta, m.segments.size, wordCount)
    }

    /** Pulls summary + action items saved by SummaryService into this screen. */
    private fun refreshSummaryFromStore(reveal: Boolean) {
        val m = meeting ?: return
        val saved = store.load(m.id) ?: return
        if (saved.summary == m.summary &&
            saved.actionItems == m.actionItems &&
            saved.summaryStale == m.summaryStale
        ) {
            return
        }
        m.summary = saved.summary
        m.actionItems = saved.actionItems
        // Regenerating settles the "transcript changed" flag on disk; without
        // copying it back, the hint would linger here and the next save from
        // this screen would write the stale flag out again.
        m.summaryStale = saved.summaryStale
        progress.visibility = View.GONE
        renderSummary(m.summary)
        renderActionItems(m)
        if (reveal) revealSummarySections()
    }

    private var suggestDialog: AlertDialog? = null
    private var pendingPhotoFile: File? = null
    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingPhotoFile
            pendingPhotoFile = null
            val m = meeting
            if (success && file != null && file.exists() && file.length() > 0 && m != null) {
                m.photos.add(file.name)
                persist(m)
                renderPhotos(m)
                ocrPhoto(m, file.name)
            } else {
                file?.delete()
            }
        }
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importPhoto(it) }
        }
    private val pickAttachment =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importAttachment(it) }
        }
    private val exportMd =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            uri?.let { writeExport(it, isPdf = false) }
        }
    private val exportPdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            uri?.let { writeExport(it, isPdf = true) }
        }
    private val exportIcs =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
            uri?.let { writeActionItemsIcs(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_detail)

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
            }
        )
        recycler.adapter = ConcatAdapter(StaticViewAdapter(headerView), transcriptAdapter)

        titleView = headerView.findViewById(R.id.detailTitle)
        dateView = headerView.findViewById(R.id.detailDate)
        metaView = headerView.findViewById(R.id.detailMeta)
        summaryView = headerView.findViewById(R.id.detailSummary)
        aiPanel = headerView.findViewById(R.id.aiPanel)
        tagHint = headerView.findViewById(R.id.tagHint)
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

        val id = intent.getStringExtra(EXTRA_MEETING_ID)
        meeting = id?.let { store.load(it) }
        val m = meeting
        if (m == null) {
            Toast.makeText(this, R.string.meeting_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        titleView.text = m.title
        dateView.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(m.createdAtMs))
        segmentsSeenThroughMs = com.meetily.mobile.data.MeetingMerge.highWaterMs(m.segments)
        renderMeta(m)

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

        setUpTabs()
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
        setUpPlayer()
        renderStats(m)
        maybeAutoTitle(m)
        runRequestedAction(firstCreate = savedInstanceState == null)
    }

    /**
     * The home screen's long-press sheet and the accuracy check hand work
     * over as an intent extra so every heavy action keeps its one
     * implementation here, with its existing service and progress wiring.
     * Posted so the screen is laid out before a dialog or sheet appears.
     */
    private fun runRequestedAction(firstCreate: Boolean) {
        // Rotating re-delivers the original intent, so without this the
        // rename dialog would reopen (or a summary start twice) on every
        // configuration change.
        if (!firstCreate) return
        if (intent.getBooleanExtra(EXTRA_REGENERATE_SUMMARY, false)) {
            intent.removeExtra(EXTRA_REGENERATE_SUMMARY)
            headerView.post { chooseTemplateAndSummarize() }
            return
        }
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        intent.removeExtra(EXTRA_ACTION)
        headerView.post {
            if (isFinishing || isDestroyed) return@post
            when (action) {
                ACTION_RENAME -> renameMeeting()
                ACTION_SUMMARIZE -> chooseTemplateAndSummarize()
                ACTION_TOPICS -> topicsAction()
                ACTION_CHECK_ACCURACY -> confirmCheckAccuracy()
                ACTION_SHARE -> shareMeeting()
            }
        }
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
            summaryView.text = com.meetily.mobile.notes.MarkdownSpans.render(summary)
            summaryView.visibility = View.VISIBLE
            aiPanel.visibility = View.GONE
        }
    }

    private fun renderTranscript(m: Meeting) {
        tagHint.text = getString(
            if (m.segments.isEmpty()) R.string.no_transcript else R.string.tap_to_tag_hint
        )
        // Transcript lines belong to the Transcript tab only.
        tagHint.visibility = if (onTranscriptTab) View.VISIBLE else View.GONE
        if (topicsRunning) {
            // Detection status outlives a transcript re-render (tab switch,
            // late lines landing) — it is the only sign work is happening.
            tagHint.text = getString(R.string.topics_working)
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
        // Chapters start collapsed the first time a meeting is opened, so a
        // chaptered transcript reads as an outline you drill into.
        val collapseFirst = onTranscriptTab && !collapsedApplied && m.chapters.isNotEmpty()
        if (collapseFirst) collapsedApplied = true
        transcriptAdapter.submit(
            if (onTranscriptTab) m.segments else emptyList(),
            if (onTranscriptTab) m.chapters else emptyList(),
            collapseAllInitially = collapseFirst
        )
    }

    /** Chapters are auto-collapsed once per screen, not on every re-render. */
    private var collapsedApplied = false

    // --- Summary | Transcript segmented tabs --------------------------------

    private var onTranscriptTab = false

    private fun setUpTabs() {
        headerView.findViewById<View>(R.id.tabSummary).setOnClickListener { switchTab(false) }
        headerView.findViewById<View>(R.id.tabTranscript).setOnClickListener { switchTab(true) }
        applyTabState()
    }

    private fun switchTab(transcript: Boolean) {
        if (onTranscriptTab == transcript) return
        onTranscriptTab = transcript
        applyTabState()
        meeting?.let { renderTranscript(it) }
    }

    private fun applyTabState() {
        val tabSummary = headerView.findViewById<TextView>(R.id.tabSummary)
        val tabTranscript = headerView.findViewById<TextView>(R.id.tabTranscript)
        val content = headerView.findViewById<View>(R.id.summaryTabContent)
        val active = themeColor(com.google.android.material.R.attr.colorOnPrimary)
        val inactive = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (onTranscriptTab) {
            tabSummary.setBackgroundResource(0)
            tabTranscript.setBackgroundResource(R.drawable.bg_tab_active)
            tabSummary.setTextColor(inactive)
            tabTranscript.setTextColor(active)
            content.visibility = View.GONE
        } else {
            tabSummary.setBackgroundResource(R.drawable.bg_tab_active)
            tabTranscript.setBackgroundResource(0)
            tabSummary.setTextColor(active)
            tabTranscript.setTextColor(inactive)
            content.visibility = View.VISIBLE
        }
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
        saveEdits()
        player?.let { p ->
            if (playerReady && p.isPlaying) {
                p.pause()
                playPauseButton.setImageResource(R.drawable.ic_play)
            }
        }
    }

    override fun onDestroy() {
        // Avoid a WindowLeaked crash if a config change lands mid-analysis.
        suggestDialog?.dismiss()
        suggestDialog = null
        playerHandler.removeCallbacks(playerTick)
        playerReady = false
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
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
        val defaultTitle = getString(
            R.string.default_meeting_title,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(m.createdAtMs))
        )
        if (m.title != defaultTitle || m.transcriptText().isBlank()) return

        val offline = ExtractiveSummarizer.titleFor(m.transcriptText())
        if (offline.isNotBlank()) {
            m.title = offline
            titleView.text = offline
            persist(m)
        }
        if (settings.useLlm && settings.llmConfigured) {
            val baseUrl = settings.llmBaseUrl
            val apiKey = settings.llmApiKey
            val model = settings.llmModel
            val localOnly = settings.localOnlyLlm
            val transcript = m.transcriptTextWithSpeakers()
            val notes = m.notes
            Thread {
                try {
                    val generated = LlmClient.title(baseUrl, apiKey, model, localOnly, transcript, notes)
                    if (generated.isNotBlank()) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            m.title = generated
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
            .show()
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
        for (name in m.photos.toList()) {
            val file = PhotoStore.fileFor(this, name)
            val thumb = PhotoStore.decodeSampled(file, 256) ?: continue
            val image = ShapeableImageView(this).apply {
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(radiusPx)
                    .build()
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(thumb)
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = marginPx
                }
                contentDescription = getString(R.string.section_photos)
                setOnClickListener { showPhoto(file) }
                setOnLongClickListener {
                    confirmDeletePhoto(name)
                    true
                }
            }
            strip.addView(image)
        }
    }

    private fun showPhoto(file: File) {
        val bitmap = PhotoStore.decodeSampled(file, 1400) ?: return
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setView(image)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDeletePhoto(name: String) {
        val m = meeting ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_photo_title)
            .setPositiveButton(R.string.delete) { _, _ ->
                m.photos.remove(name)
                m.photoTexts.remove(name)
                PhotoStore.delete(this, name)
                persist(m)
                renderPhotos(m)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        try {
            val file = PhotoStore.newPhotoFile(this, m.id)
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: throw RuntimeException("cannot open image")
            m.photos.add(file.name)
            persist(m)
            renderPhotos(m)
            ocrPhoto(m, file.name)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.photo_attach_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Extracts photo text on-device; feeds search and LLM summary context. */
    private fun ocrPhoto(m: Meeting, name: String) {
        val file = PhotoStore.fileFor(this, name)
        if (!file.exists()) return
        PhotoOcr.extract(this, file) { text ->
            if (text != null && name in m.photos) {
                m.photoTexts[name] = text
                persist(m)
            }
        }
    }

    private fun backfillPhotoOcr(m: Meeting) {
        for (name in m.photos) {
            if (!m.photoTexts.containsKey(name)) ocrPhoto(m, name)
        }
    }

    private fun photoTextBlock(m: Meeting): String {
        if (m.photoTexts.isEmpty()) return ""
        return "\n\n[Text captured from attached photos and whiteboards]\n" +
            m.photoTexts.values.joinToString("\n---\n").take(4_000)
    }

    private fun showExportDialog() {
        val m = meeting ?: return
        val options = arrayOf(
            getString(R.string.export_markdown),
            getString(R.string.export_pdf),
            getString(R.string.export_actions_ics),
            getString(R.string.export_actions_text)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.export)
            .setItems(options) { _, which ->
                saveEdits()
                try {
                    when (which) {
                        0 -> exportMd.launch(MeetingExporter.suggestedFileName(m, "md"))
                        1 -> exportPdf.launch(MeetingExporter.suggestedFileName(m, "pdf"))
                        2 -> {
                            if (openActionItems(m).isEmpty()) {
                                Toast.makeText(
                                    this, R.string.no_action_items, Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                exportIcs.launch(MeetingExporter.suggestedFileName(m, "ics"))
                            }
                        }
                        else -> shareActionItemsText(m)
                    }
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
        try {
            val ics = com.meetily.mobile.export.TaskExport.ics(
                openActionItems(m), System.currentTimeMillis()
            )
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(ics.toByteArray(Charsets.UTF_8))
            } ?: throw RuntimeException("could not open destination")
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.export_failed, e.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun shareActionItemsText(m: Meeting) {
        val items = openActionItems(m)
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.no_action_items, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.actions_share_subject, m.title))
            putExtra(Intent.EXTRA_TEXT, com.meetily.mobile.export.TaskExport.text(items))
        }
        startActivity(Intent.createChooser(send, getString(R.string.export_actions_text)))
    }

    private fun writeExport(uri: Uri, isPdf: Boolean) {
        val m = meeting ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                if (isPdf) {
                    MeetingExporter.writePdf(m, out)
                } else {
                    out.write(MeetingExporter.markdown(m).toByteArray(Charsets.UTF_8))
                }
            } ?: throw RuntimeException("could not open destination")
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.export_failed, e.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
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

        Thread {
            val answer = try {
                LlmClient.ask(
                    baseUrl, apiKey, model, localOnly, transcript, notes, summary,
                    attendees, history, question
                )
            } catch (e: Exception) {
                getString(R.string.ask_failed, e.message ?: "unknown error")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                answerView.text = answer
                askSend.isEnabled = true
                m.qa.add(QaEntry(question, answer))
                persist(m)
            }
        }.start()
    }

    /**
     * LLM-based speaker attribution from conversational context. Applies only
     * to untagged lines, never overwriting manual tags, and always behind an
     * explicit confirmation with a preview.
     */
    private fun suggestSpeakers() {
        val m = meeting ?: return
        if (!settings.useLlm || settings.llmBaseUrl.isBlank()) {
            Toast.makeText(this, R.string.suggest_requires_llm, Toast.LENGTH_LONG).show()
            return
        }
        if (m.segments.isEmpty()) {
            Toast.makeText(this, R.string.no_transcript, Toast.LENGTH_SHORT).show()
            return
        }
        saveEdits()

        // Cancelable: a hung endpoint (up to ~200s of timeouts) must not trap
        // the screen. Cancel abandons the in-flight result.
        val progressDialog = AlertDialog.Builder(this)
            .setMessage(R.string.suggest_analyzing)
            .setCancelable(true)
            .create()
        suggestDialog = progressDialog
        progressDialog.show()

        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val localOnly = settings.localOnlyLlm
        val lines = m.segments.map { it.text to it.speaker }
        val attendees = m.attendees.toList()

        Thread {
            var error: String? = null
            val suggestions = try {
                LlmClient.suggestSpeakers(baseUrl, apiKey, model, localOnly, lines, attendees)
            } catch (e: Exception) {
                error = e.message ?: "unknown error"
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val canceled = !progressDialog.isShowing
                progressDialog.dismiss()
                suggestDialog = null
                if (canceled) return@runOnUiThread
                if (error != null) {
                    Toast.makeText(
                        this, getString(R.string.ask_failed, error), Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                val applicable = suggestions
                    .distinctBy { it.first }
                    .filter { (index, _) ->
                        index in m.segments.indices &&
                            m.segments[index].speaker.isNullOrBlank()
                    }
                if (applicable.isEmpty()) {
                    Toast.makeText(this, R.string.suggest_none, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                confirmSpeakerSuggestions(m, applicable)
            }
        }.start()
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
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        list.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            templates.map { it.label(this) }
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

        if (m.transcriptText().isBlank() && m.notes.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_summarize, Toast.LENGTH_SHORT).show()
            return
        }

        if (SummaryService.isRunning) {
            Toast.makeText(this, R.string.summary_busy, Toast.LENGTH_SHORT).show()
            return
        }
        showSummarizingUi()
        setSummaryProgress(-1)
        // Generation lives in SummaryService: it survives rotation and
        // navigation, saves the result itself, and this screen just observes.
        SummaryService.start(this, m.id, template.key)
        bindSummaryService()
    }

    private fun showSummarizingUi() {
        aiPanel.visibility = View.GONE
        progress.visibility = View.VISIBLE
        summaryView.visibility = View.VISIBLE
        summaryView.text = getString(R.string.summarizing)
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
                append("SUMMARY\n").append(m.summary).append("\n\n")
            }
            if (m.notes.isNotBlank()) {
                append("NOTES\n").append(m.notes).append("\n\n")
            }
            append("TRANSCRIPT\n").append(m.transcriptTextWithSpeakers())
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, m.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_meeting)))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_star)?.setTitle(
            if (meeting?.starred == true) R.string.unstar_meeting else R.string.star_meeting
        )
        return super.onPrepareOptionsMenu(menu)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
            R.id.action_topics -> {
                topicsAction()
                true
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
            R.id.action_star -> {
                toggleStar()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- Audio playback ----------------------------------------------------

    private var player: MediaPlayer? = null
    private var playerReady = false
    private lateinit var playerBar: View
    private lateinit var playPauseButton: ImageButton
    private lateinit var playerSeek: SeekBar
    private lateinit var playerTime: TextView
    private lateinit var playerSpeedButton: TextView
    private lateinit var skipSilenceButton: ImageButton
    private var playbackSpeed = 1.0f
    private var skipSilence = false

    /** Speech spans over the audio timeline; built lazily for skip-silence. */
    private val speechSpans: List<com.meetily.mobile.whisper.SpeechSpans.Span> by lazy {
        com.meetily.mobile.whisper.SpeechSpans.build(meeting?.segments ?: emptyList())
    }

    private val playerHandler = Handler(Looper.getMainLooper())
    private val playerTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (playerReady) {
                if (skipSilence && p.isPlaying) {
                    val target = com.meetily.mobile.whisper.SpeechSpans.skipTarget(
                        p.currentPosition.toLong(), speechSpans
                    )
                    if (target != null && target < p.duration) {
                        p.seekTo(target.toInt())
                    }
                }
                updatePlayerUi(p)
                if (p.isPlaying) playerHandler.postDelayed(this, 400)
            }
        }
    }

    /** Applies the persisted speed to an actively playing player (API 23+). */
    private fun applyPlaybackSpeed(p: MediaPlayer) {
        try {
            p.playbackParams = p.playbackParams.setSpeed(playbackSpeed)
        } catch (_: Exception) {
            // Some codecs refuse non-1x; playback continues at normal speed.
        }
    }

    private fun cyclePlaybackSpeed() {
        val steps = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 0.5f)
        val at = steps.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.01f }
        playbackSpeed = steps[(at + 1).mod(steps.size)]
        settings.playbackSpeed = playbackSpeed
        renderSpeedLabel()
        player?.let { if (it.isPlaying) applyPlaybackSpeed(it) }
    }

    private fun renderSpeedLabel() {
        val label = if (playbackSpeed == playbackSpeed.toInt().toFloat()) {
            playbackSpeed.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", playbackSpeed)
                .trimEnd('0').trimEnd('.')
        }
        playerSpeedButton.text = "$label×"
    }

    private fun renderSkipSilence() {
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
            playerBar.visibility = View.GONE
            return
        }
        playerBar.visibility = View.VISIBLE
        playerTime.text = formatClock(0)
        playbackSpeed = settings.playbackSpeed
        renderSpeedLabel()
        playerSpeedButton.setOnClickListener { cyclePlaybackSpeed() }
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
        }
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
    }

    /** Creates the player on first use; returns null if the file won't play. */
    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        val file = audioFileOrNull() ?: return null
        return try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener {
                playPauseButton.setImageResource(R.drawable.ic_play)
                updatePlayerUi(p)
            }
            playerReady = true
            playerSeek.max = p.duration.coerceAtLeast(1)
            player = p
            updatePlayerUi(p)
            p
        } catch (_: Exception) {
            playerReady = false
            player = null
            Toast.makeText(this, R.string.audio_play_failed, Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun togglePlayback() {
        val p = ensurePlayer() ?: return
        if (p.isPlaying) {
            p.pause()
            playPauseButton.setImageResource(R.drawable.ic_play)
        } else {
            p.start()
            applyPlaybackSpeed(p)
            playPauseButton.setImageResource(R.drawable.ic_pause)
            playerHandler.post(playerTick)
        }
    }

    private fun playFrom(audioMs: Long) {
        val p = ensurePlayer() ?: return
        p.seekTo(audioMs.toInt().coerceIn(0, p.duration))
        if (!p.isPlaying) {
            p.start()
            applyPlaybackSpeed(p)
            playPauseButton.setImageResource(R.drawable.ic_pause)
        }
        playerHandler.post(playerTick)
    }

    private fun updatePlayerUi(p: MediaPlayer) {
        playerSeek.progress = p.currentPosition
        playerTime.text = getString(
            R.string.player_time,
            formatClock(p.currentPosition),
            formatClock(p.duration)
        )
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
        Thread {
            val clip = com.meetily.mobile.export.ClipExporter
                .export(this, source, start, end)
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
                    m.segments[index] =
                        m.segments[index].copy(text = newText, words = null)
                    persist(m)
                    transcriptAdapter.update(index, m.segments[index])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            .show()
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
                    val embedder = SherpaEmbedder.create(modelPath)
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
            .show()
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
        if (RecordingService.isRunning) {
            Toast.makeText(this, R.string.import_wait_recording, Toast.LENGTH_LONG).show()
            return
        }
        if (ImportService.isRunning) {
            Toast.makeText(this, R.string.import_busy, Toast.LENGTH_LONG).show()
            return
        }
        val downloaded = com.meetily.mobile.whisper.TranscriptionModels.downloadedKeys(this)
        if (downloaded.isEmpty()) {
            Toast.makeText(this, R.string.import_needs_whisper, Toast.LENGTH_LONG).show()
            return
        }
        // Heaviest first: with no real-time budget a bigger model is the
        // whole reason to run a second pass.
        val ranked = downloaded.sortedByDescending {
            com.meetily.mobile.whisper.TranscriptionModels.sizeMb(it)
        }
        val willUse = ranked.firstOrNull { it != m.transcriptModel } ?: ranked.first()
        AlertDialog.Builder(this)
            .setTitle(R.string.check_accuracy_title)
            .setMessage(
                getString(
                    R.string.check_accuracy_message,
                    com.meetily.mobile.whisper.TranscriptionModels.displayName(willUse)
                )
            )
            .setPositiveButton(R.string.check_accuracy_go) { _, _ ->
                if (ranked.size == 1) startCheck(m, ranked.first())
                else chooseCheckModel(m, ranked, willUse)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseCheckModel(m: Meeting, ranked: List<String>, preferred: String) {
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
                            models.metaLine(this, key)
                        },
                        downloaded = true,
                        selected = key == preferred
                    )
                }
            },
            onPick = { key -> startCheck(m, key) }
        )
    }

    private fun startCheck(m: Meeting, modelKey: String) {
        val file = audioFileOrNull() ?: return
        val start = Intent(this, ImportService::class.java)
            .setAction(ImportService.ACTION_START)
            .setData(AudioStore.uriFor(this, file))
            .putExtra(ImportService.EXTRA_NAME, m.title)
            .putExtra(ImportService.EXTRA_MODEL, modelKey)
            .putExtra(ImportService.EXTRA_RECHECK_MEETING_ID, m.id)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(start)
        } else {
            startService(start)
        }
        Toast.makeText(this, R.string.check_started, Toast.LENGTH_LONG).show()
    }

    private fun openCheckReview(meetingId: String) {
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
        if (m.notes.isBlank()) return
        if (SummaryService.isRunning) {
            Toast.makeText(this, R.string.summary_busy, Toast.LENGTH_SHORT).show()
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

    /** Pulls notes written by a background enhancement into this screen. */
    private fun refreshNotesFromStore() {
        val m = meeting ?: return
        val saved = store.load(m.id) ?: return
        if (saved.notes == m.notes && saved.notesOriginal == m.notesOriginal) return
        // Don't clobber an in-progress manual edit.
        if (headerView.findViewById<View>(R.id.notesEditMode).visibility == View.VISIBLE) {
            return
        }
        m.notes = saved.notes
        m.notesOriginal = saved.notesOriginal
        notesInput.setText(m.notes)
        setNotesMode(viewMode = m.notes.isNotBlank())
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
        try {
            val display = attachmentDisplayName(uri)
            val file = AttachmentStore.newFile(this, m.id, display)
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: throw RuntimeException("cannot open file")
            if (file.length() <= 0) {
                file.delete()
                throw RuntimeException("empty file")
            }
            m.attachmentsList.add(Attachment(file.name, display))
            persist(m)
            renderAttachments(m)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.attach_failed, Toast.LENGTH_SHORT).show()
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
            .show()
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
            .show()
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
            .show()
    }

    private fun jumpToChapter(chapter: com.meetily.mobile.data.Chapter) {
        val m = meeting ?: return
        if (!onTranscriptTab) switchTab(true)
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
                chapters = TopicChapters.buildLocal(segmentsSnapshot)
            }
            val result = chapters
            // SAVE FIRST, on this thread, against a freshly loaded copy: the
            // result must survive the screen being closed or rotated. This
            // used to be discarded whenever the activity had gone away.
            var saved = false
            if (result.isNotEmpty()) {
                try {
                    val target = store.load(meetingId)
                    if (target != null) {
                        target.chapters.clear()
                        target.chapters.addAll(result)
                        store.save(target)
                        saved = true
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
                if (!onTranscriptTab) switchTab(true) else meeting?.let { renderTranscript(it) }
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

    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"

        /** Set by the accuracy check when the user asked to redo the summary. */
        const val EXTRA_REGENERATE_SUMMARY = "regenerate_summary"

        /** One of the ACTION_* values below, run once the screen is up. */
        const val EXTRA_ACTION = "detail_action"
        const val ACTION_RENAME = "rename"
        const val ACTION_SUMMARIZE = "summarize"
        const val ACTION_TOPICS = "topics"
        const val ACTION_CHECK_ACCURACY = "check_accuracy"
        const val ACTION_SHARE = "share"
    }
}
