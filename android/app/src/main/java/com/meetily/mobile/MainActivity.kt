package com.meetily.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.search.MeetingGroups

class MainActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var adapter: MeetingAdapter
    private lateinit var emptyState: View
    private lateinit var meetingCount: TextView
    private lateinit var searchInput: EditText
    private lateinit var orbCaption: TextView
    private var allMeetings: List<Meeting> = emptyList()

    // Library filters: at most one active — flagged, a tag, or a series.
    private var selectedTag: String? = null
    private var selectedSeriesKey: String? = null
    private var flaggedOnly = false
    private lateinit var filterChips: android.widget.LinearLayout
    private lateinit var filterChipsScroll: View

    private var appliedAccent: String = ""

    // --- Import progress banner (mirrors ImportService state) ---------------

    private lateinit var importBanner: View
    private lateinit var importBannerName: TextView
    private lateinit var importBannerPct: TextView
    private lateinit var importBannerBar:
        com.google.android.material.progressindicator.LinearProgressIndicator
    private var importService: ImportService? = null
    private var importBound = false

    private val importObserver = object : ImportService.Observer {
        override fun onImportProgress(meetingId: String?, percent: Int, detail: String) {
            if (isFinishing || isDestroyed) return
            val recheck = ImportService.isRecheck
            val source = importService?.sourceName?.ifBlank { null }
                ?: getString(R.string.import_title)
            importWork = Work(
                meetingId = meetingId,
                bannerTitle = getString(
                    if (recheck) R.string.check_notif_title
                    else R.string.import_notif_title,
                    source
                ),
                // The detail line carries minutes done and minutes left. On a
                // long file a percentage alone barely moves, which reads as a
                // hang; this is what shows the run is alive.
                inlineLabel = if (detail.isNotBlank()) {
                    getString(
                        if (recheck) R.string.card_progress_checking_detail
                        else R.string.card_progress_transcribing_detail,
                        detail
                    )
                } else {
                    getString(
                        if (recheck) R.string.card_progress_checking
                        else R.string.card_progress_transcribing,
                        percent
                    )
                },
                percent = percent
            )
            // An import's meeting only reaches disk once its first line is
            // transcribed, so the list has to be reloaded to show it. One
            // reload per run: after that the card is there to paint into.
            if (meetingId != null && meetingId != importListedId &&
                !adapter.hasMeeting(meetingId) && store.load(meetingId) != null
            ) {
                importListedId = meetingId
                refresh()
                return
            }
            syncProgressViews()
        }

        override fun onImportDone(
            meetingId: String?,
            wasCancelled: Boolean,
            error: String?,
            warning: String?
        ) {
            if (isFinishing || isDestroyed) return
            importWork = null
            importListedId = null
            refresh() // the imported meeting (or its final state) shows up
        }
    }

    private val importConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? ImportService.ImportBinder)?.service ?: return
            importService = svc
            svc.addObserver(importObserver)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            importService = null
        }
    }

    // --- Summary progress banner (mirrors SummaryService state) -------------

    private lateinit var summaryBanner: View
    private lateinit var summaryBannerName: TextView
    private lateinit var summaryBannerPct: TextView
    private lateinit var summaryBannerBar:
        com.google.android.material.progressindicator.LinearProgressIndicator
    private var summaryService: SummaryService? = null
    private var summaryBound = false

    private val summaryObserver = object : SummaryService.Observer {
        override fun onSummaryProgress(meetingId: String, percent: Int, stage: String) {
            if (isFinishing || isDestroyed) return
            val notesRun = SummaryService.currentMode == SummaryService.MODE_NOTES
            val speakersRun = SummaryService.currentMode == SummaryService.MODE_SPEAKERS
            summaryWork = Work(
                meetingId = meetingId,
                bannerTitle = SummaryService.currentTitle.ifBlank {
                    getString(R.string.summary_banner_untitled)
                },
                inlineLabel = when {
                    percent < 0 && notesRun -> getString(R.string.card_progress_notes_plain)
                    percent < 0 && speakersRun ->
                        getString(R.string.card_progress_speakers_plain)
                    percent < 0 -> getString(R.string.card_progress_summarising_plain)
                    notesRun -> getString(R.string.card_progress_notes, percent)
                    speakersRun -> getString(R.string.card_progress_speakers, percent)
                    else -> getString(R.string.card_progress_summarising, percent)
                },
                percent = percent
            )
            syncProgressViews()
        }

        override fun onSummaryDone(meetingId: String, failed: Boolean) {
            if (isFinishing || isDestroyed) return
            summaryWork = null
            // currentTitle/currentMode are still set when observers hear
            // about the finish.
            val title = SummaryService.currentTitle
            val notesMode = SummaryService.currentMode == SummaryService.MODE_NOTES
            val speakersMode = SummaryService.currentMode == SummaryService.MODE_SPEAKERS
            Toast.makeText(
                this@MainActivity,
                when {
                    notesMode && failed -> getString(R.string.notes_failed_notif)
                    notesMode -> getString(R.string.notes_done_notif)
                    speakersMode && failed -> getString(R.string.speakers_failed_notif)
                    speakersMode -> getString(R.string.speakers_done_notif)
                    title.isBlank() -> getString(R.string.summary_ready_plain)
                    else -> getString(R.string.summary_ready_toast, title)
                },
                Toast.LENGTH_SHORT
            ).show()
            refresh() // summary badge/preview on the meeting card updates
        }
    }

    private val summaryConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? SummaryService.SummaryBinder)?.service ?: return
            summaryService = svc
            svc.addObserver(summaryObserver)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            summaryService = null
        }
    }

    // --- Where progress goes -------------------------------------------------

    /** One piece of background work, as the home screen needs to show it. */
    private data class Work(
        val meetingId: String?,
        val bannerTitle: String,
        val inlineLabel: String,
        val percent: Int
    )

    private var importWork: Work? = null
    private var summaryWork: Work? = null

    /** The import meeting the list has already been reloaded for. */
    private var importListedId: String? = null

    // The card each observer is currently painting, so a run that moves to a
    // different meeting (or ends) takes its progress row with it instead of
    // leaving a frozen bar behind on the old card.
    private var importPaintedId: String? = null
    private var summaryPaintedId: String? = null

    private fun syncProgressViews() {
        importPaintedId = renderWork(
            importWork, importPaintedId,
            importBanner, importBannerName, importBannerBar, importBannerPct
        )
        summaryPaintedId = renderWork(
            summaryWork, summaryPaintedId,
            summaryBanner, summaryBannerName, summaryBannerBar, summaryBannerPct
        )
    }

    /**
     * Progress belongs inside the meeting's own card — the card already names
     * the meeting, so a banner repeating it is redundant. The banner stays as
     * the fallback for work whose card is not on screen: filtered out by the
     * search box or a chip, or an import whose meeting is not in the list
     * yet. Never both at once.
     */
    private fun renderWork(
        work: Work?,
        paintedId: String?,
        banner: View,
        name: TextView,
        bar: com.google.android.material.progressindicator.LinearProgressIndicator,
        pct: TextView
    ): String? {
        // Whatever this observer painted last, if it is not what it is
        // painting now, has to be wiped first — including when the work has
        // finished entirely.
        if (paintedId != null && paintedId != work?.meetingId) {
            adapter.setProgress(paintedId, null)
        }
        if (work == null) {
            banner.visibility = View.GONE
            return null
        }
        val inline = work.meetingId != null && adapter.setProgress(
            work.meetingId, MeetingAdapter.Progress(work.inlineLabel, work.percent)
        )
        if (inline) {
            banner.visibility = View.GONE
            return work.meetingId
        }
        banner.visibility = View.VISIBLE
        name.text = work.bannerTitle
        val wantIndeterminate = work.percent <= 0
        if (bar.isIndeterminate != wantIndeterminate) {
            // Material indicators refuse an in-place mode switch while
            // visible, so blink the bar around the change.
            bar.visibility = View.INVISIBLE
            bar.isIndeterminate = wantIndeterminate
            bar.visibility = View.VISIBLE
        }
        if (!wantIndeterminate) bar.progress = work.percent
        pct.text = if (work.percent < 0) "" else getString(R.string.percent_fmt, work.percent)
        // Nothing was painted into a card, so there is nothing to wipe later.
        return null
    }

    override fun onStart() {
        super.onStart()
        if (ImportService.isRunning) {
            bindService(
                Intent(this, ImportService::class.java),
                importConnection,
                Context.BIND_AUTO_CREATE
            )
            importBound = true
        } else {
            importWork = null
        }
        if (SummaryService.isRunning) {
            bindService(
                Intent(this, SummaryService::class.java),
                summaryConnection,
                Context.BIND_AUTO_CREATE
            )
            summaryBound = true
        } else {
            summaryWork = null
        }
        syncProgressViews()
    }

    override fun onStop() {
        importService?.removeObserver(importObserver)
        if (importBound) {
            try {
                unbindService(importConnection)
            } catch (_: Exception) {
            }
            importBound = false
        }
        importService = null
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
     * If the app crashed last time, offer the locally saved report — the
     * user decides where (or whether) it goes, via their own share sheet.
     */
    private fun maybeOfferCrashReport() {
        val crash = com.meetily.mobile.diag.CrashLog.pendingCrash(this) ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.crash_prompt_title)
            .setMessage(R.string.crash_prompt_body)
            .setPositiveButton(R.string.crash_share) { _, _ ->
                com.meetily.mobile.diag.CrashLog.markConsumed(this, crash)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.bug_report_subject))
                    putExtra(
                        Intent.EXTRA_TEXT,
                        com.meetily.mobile.diag.CrashLog.shareText(crash)
                    )
                }
                startActivity(
                    Intent.createChooser(send, getString(R.string.crash_share))
                )
            }
            .setNegativeButton(R.string.crash_dismiss) { _, _ ->
                com.meetily.mobile.diag.CrashLog.markConsumed(this, crash)
            }
            .show()
    }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                startActivity(
                    Intent(this, ImportActivity::class.java)
                        .setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedAccent = ThemeManager.apply(this)
        if (!AppSettings(this).onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        setContentView(R.layout.activity_main)

        store = MeetingStore(this)
        emptyState = findViewById(R.id.emptyState)
        meetingCount = findViewById(R.id.meetingCount)
        filterChips = findViewById(R.id.filterChips)
        filterChipsScroll = findViewById(R.id.filterChipsScroll)
        searchInput = findViewById(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })

        val recycler = findViewById<RecyclerView>(R.id.meetingList)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MeetingAdapter(
            onClick = { meeting ->
                startActivity(
                    Intent(this, MeetingDetailActivity::class.java)
                        .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meeting.id)
                )
            },
            onLongClick = { meeting -> showMeetingActions(meeting) },
            onToggleStar = { meeting -> toggleStar(meeting) }
        )
        recycler.adapter = adapter

        orbCaption = findViewById(R.id.orbCaption)
        val orb = findViewById<View>(R.id.recordOrb)
        orb.setOnClickListener {
            startActivity(Intent(this, RecordingActivity::class.java))
        }
        orb.setOnLongClickListener {
            showRecordSourceChooser()
            true
        }
        // Without a label the long-press is announced as "long press" with no
        // hint of what it does, so the microphone / device-audio choice is
        // effectively hidden from a screen-reader user.
        androidx.core.view.ViewCompat.replaceAccessibilityAction(
            orb,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                .AccessibilityActionCompat.ACTION_LONG_CLICK,
            getString(R.string.record_button_sources),
            null
        )
        importBanner = findViewById(R.id.importBanner)
        importBannerName = findViewById(R.id.importBannerName)
        importBannerPct = findViewById(R.id.importBannerPct)
        importBannerBar = findViewById(R.id.importBannerBar)
        importBanner.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        maybeOfferCrashReport()
        summaryBanner = findViewById(R.id.summaryBanner)
        summaryBannerName = findViewById(R.id.summaryBannerName)
        summaryBannerPct = findViewById(R.id.summaryBannerPct)
        summaryBannerBar = findViewById(R.id.summaryBannerBar)
        summaryBanner.setOnClickListener {
            val id = SummaryService.currentMeetingId
            if (id.isNotBlank()) {
                startActivity(
                    Intent(this, MeetingDetailActivity::class.java)
                        .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, id)
                )
            }
        }
        findViewById<View>(R.id.importButton).setOnClickListener {
            if (ImportService.isRunning) {
                // An import is in flight — show its progress screen instead
                // of the picker (one import runs at a time).
                startActivity(Intent(this, ImportActivity::class.java))
                return@setOnClickListener
            }
            try {
                pickAudio.launch("audio/*")
            } catch (_: Exception) {
                Toast.makeText(this, R.string.import_no_picker, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.followupsButton).setOnClickListener {
            startActivity(Intent(this, FollowUpsActivity::class.java))
        }
        findViewById<View>(R.id.askButton).setOnClickListener {
            startActivity(Intent(this, AskLibraryActivity::class.java))
        }
        findViewById<View>(R.id.digestButton).setOnClickListener {
            startActivity(Intent(this, DigestActivity::class.java))
        }
        findViewById<View>(R.id.themeToggle).setOnClickListener { v ->
            // Sun shows in dark (tap for daylight); moon shows in light.
            val next = if (isNightNow()) "light" else "dark"
            AppSettings(this).themeMode = next
            // Posted: the recreate must not run inside this click dispatch
            // (see SettingsActivity's theme toggle for the full story).
            v.post { ThemeManager.applyNightMode(next) }
        }

        recoverInterruptedRecording()

        if (intent?.action == ACTION_IMPORT_PICK && savedInstanceState == null) {
            try {
                pickAudio.launch("audio/*")
            } catch (_: Exception) {
                Toast.makeText(this, R.string.import_no_picker, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * If a recording was interrupted (process killed mid-session), its meeting
     * was still saved incrementally. Surface that once and clear the marker.
     */
    private fun recoverInterruptedRecording() {
        if (RecordingService.isRunning) return
        val id = store.activeId() ?: return
        store.clearActive()
        if (store.load(id) != null) {
            Toast.makeText(this, R.string.recording_recovered, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedAccent.isNotEmpty() && appliedAccent != AppSettings(this).accentColor) {
            recreate()
            return
        }
        orbCaption.setText(
            when {
                RecordingService.isRunning -> R.string.orb_recording_caption
                // Only claim it when the settings actually keep it. The
                // system recognizer is cloud-backed on most phones, and a
                // remote AI endpoint sends the transcript off-device — so
                // the strong line is earned, not decorative.
                staysOnDevice() -> R.string.tap_to_record
                else -> R.string.tap_to_record_mixed
            }
        )
        findViewById<ImageButton>(R.id.themeToggle).setImageResource(
            if (isNightNow()) R.drawable.ic_sun else R.drawable.ic_moon
        )
        startIdleGlow()
        refresh()
        // The foreground is the one place starting a service is always legal,
        // so this is the reliable drain trigger — the charger broadcast can
        // only ask the user to come here.
        JobGate.drain(this)
        offerInterruptedWork()
    }

    /**
     * Offers back work that died with the process — a force-stop from an ANR
     * dialog, or an out-of-memory kill.
     *
     * Deliberately a prompt rather than an automatic restart: the user may
     * have closed the app precisely to stop this, and silently spending
     * another twenty minutes of inference they thought they had killed would
     * be its own bug.
     */
    private fun offerInterruptedWork() {
        if (!JobGate.canStartBatch()) return
        val job = com.meetily.mobile.data.JobQueue
            .interrupted(com.meetily.mobile.data.JobQueue.load(this))
            .firstOrNull { store.load(it.meetingId) != null } ?: return
        val title = store.load(job.meetingId)?.title.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.resume_job_title)
            .setMessage(getString(R.string.resume_job_body, title))
            .setPositiveButton(R.string.resume_job_yes) { _, _ ->
                com.meetily.mobile.data.JobQueue
                    .dequeue(this, job.kind, job.meetingId)
                if (job.kind == com.meetily.mobile.data.JobQueue.KIND_SUMMARY) {
                    JobGate.requestSummary(this, job.meetingId, job.payload, false)
                } else {
                    JobGate.requestCheck(this, job.meetingId, job.payload, false)
                }
            }
            .setNegativeButton(R.string.resume_job_no) { _, _ ->
                com.meetily.mobile.data.JobQueue
                    .dequeue(this, job.kind, job.meetingId)
            }
            .show()
    }

    /** Long-press the orb: choose microphone or device audio (webinars). */
    private fun showRecordSourceChooser() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            startActivity(Intent(this, RecordingActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.record_source_title)
            .setItems(
                arrayOf(
                    getString(R.string.record_source_mic),
                    getString(R.string.record_source_device)
                )
            ) { _, which ->
                startActivity(
                    Intent(this, RecordingActivity::class.java)
                        .putExtra(RecordingActivity.EXTRA_DEVICE_AUDIO, which == 1)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Serialises library loads, so a burst of refreshes cannot apply out of
     * order and show an older library over a newer one.
     */
    private val libraryLoader = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "library-load")
    }

    private fun refresh() {
        // store.list() reads and JSON-parses EVERY meeting file, every segment
        // included. Invisible with ten meetings, a stall you can feel with
        // hundreds — and this is the resume path, so the cost lands on the
        // most-used interaction in the app and grows the longer it is used.
        libraryLoader.execute {
            val loaded = try {
                store.list()
            } catch (_: Throwable) {
                return@execute
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allMeetings = loaded
                searchInput.visibility = if (allMeetings.isEmpty()) View.GONE else View.VISIBLE
                rebuildFilterChips()
                applyFilter()
            }
        }
    }

    /** One chip per tag (#tag) and per recurring series (title ×N). */
    private fun rebuildFilterChips() {
        val tags = allMeetings.flatMap { it.tags }
            .groupBy { it.lowercase() }
            .map { (_, variants) -> variants.first() }
            .sortedBy { it.lowercase() }
        val series = MeetingGroups.series(allMeetings)
        // Drop a stale selection (tag removed, series dissolved).
        if (selectedTag != null && tags.none { it.equals(selectedTag, true) }) {
            selectedTag = null
        }
        if (selectedSeriesKey != null && series.none { it.key == selectedSeriesKey }) {
            selectedSeriesKey = null
        }
        val anyFlagged = allMeetings.any { it.starred }
        if (!anyFlagged) flaggedOnly = false
        filterChips.removeAllViews()
        if (tags.isEmpty() && series.isEmpty() && !anyFlagged) {
            filterChipsScroll.visibility = View.GONE
            return
        }
        filterChipsScroll.visibility = View.VISIBLE
        if (anyFlagged) {
            addFilterChip(getString(R.string.filter_flagged), flaggedOnly) {
                selectedTag = null
                selectedSeriesKey = null
                flaggedOnly = !flaggedOnly
                rebuildFilterChips()
                applyFilter()
            }
        }
        for (tag in tags) {
            addFilterChip(
                label = "#$tag",
                selected = tag.equals(selectedTag, ignoreCase = true)
            ) {
                selectedSeriesKey = null
                flaggedOnly = false
                selectedTag = if (tag.equals(selectedTag, true)) null else tag
                rebuildFilterChips()
                applyFilter()
            }
        }
        for (s in series) {
            addFilterChip(
                label = getString(R.string.series_chip, s.displayName, s.meetings.size),
                selected = s.key == selectedSeriesKey
            ) {
                selectedTag = null
                flaggedOnly = false
                selectedSeriesKey = if (s.key == selectedSeriesKey) null else s.key
                rebuildFilterChips()
                applyFilter()
            }
        }
    }

    private fun addFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
        val density = resources.displayMetrics.density
        val chip = TextView(this).apply {
            text = label
            maxLines = 1
            setBackgroundResource(
                if (selected) R.drawable.bg_pill_accent else R.drawable.bg_pill
            )
            setPadding(
                (14 * density).toInt(), (7 * density).toInt(),
                (14 * density).toInt(), (7 * density).toInt()
            )
            textSize = 13f
            minHeight = (36 * density).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
            isSelected = selected
            setOnClickListener { onClick() }
        }
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * density).toInt() }
        filterChips.addView(chip, params)
    }

    private fun applyFilter() {
        val query = searchInput.text.toString().trim().lowercase()
        var filtered = allMeetings
        if (flaggedOnly) {
            filtered = filtered.filter { it.starred }
        }
        selectedTag?.let { tag ->
            filtered = filtered.filter { meeting ->
                meeting.tags.any { it.equals(tag, ignoreCase = true) }
            }
        }
        selectedSeriesKey?.let { key ->
            filtered = filtered.filter { MeetingGroups.normalizeTitle(it.title) == key }
        }
        if (query.isNotBlank()) {
            filtered = filtered.filter { meeting ->
                meeting.title.lowercase().contains(query) ||
                    meeting.notes.lowercase().contains(query) ||
                    meeting.summary.lowercase().contains(query) ||
                    meeting.attendeesText().lowercase().contains(query) ||
                    meeting.tags.any { it.lowercase().contains(query) } ||
                    meeting.transcriptTextWithSpeakers().lowercase().contains(query)
            }
        }
        adapter.submit(filtered)
        emptyState.visibility = if (allMeetings.isEmpty()) View.VISIBLE else View.GONE
        meetingCount.text = when {
            allMeetings.isEmpty() -> getString(R.string.empty_body)
            filtered.size != allMeetings.size ->
                getString(R.string.search_results, filtered.size, allMeetings.size)
            else ->
                resources.getQuantityString(
                    R.plurals.meeting_count, allMeetings.size, allMeetings.size
                )
        }
        // The rows just changed, so whether progress can live inside a card
        // (rather than the fallback banner) may have changed with them.
        syncProgressViews()
    }

    /**
     * Long-press used to delete immediately, which made the most destructive
     * action the easiest one to hit by accident. It now opens the full set of
     * things you can do to a meeting, with Delete set apart at the end.
     */
    private fun showMeetingActions(meeting: Meeting) {
        val hasAudio = AudioStore.exists(this, meeting.audioFile)
        val canSummarize = meeting.segments.isNotEmpty() || meeting.notes.isNotBlank()
        val canTopics = meeting.segments.size >= 8
        val items = listOf(
            ActionSheet.Item(
                MeetingDetailActivity.ACTION_RENAME,
                getString(R.string.rename_meeting)
            ),
            ActionSheet.Item(
                MeetingDetailActivity.ACTION_CHECK_ACCURACY,
                getString(R.string.check_accuracy),
                subtitle = if (hasAudio) null else getString(R.string.no_audio_kept),
                enabled = hasAudio
            ),
            ActionSheet.Item(
                MeetingDetailActivity.ACTION_SUMMARIZE,
                getString(R.string.summarize),
                subtitle = if (canSummarize) null else getString(R.string.no_transcript),
                enabled = canSummarize
            ),
            ActionSheet.Item(
                MeetingDetailActivity.ACTION_TOPICS,
                getString(R.string.topics_title),
                subtitle = if (canTopics) null else getString(R.string.topics_too_short),
                enabled = canTopics
            ),
            ActionSheet.Item(
                MeetingDetailActivity.ACTION_SHARE,
                getString(R.string.share_meeting)
            ),
            ActionSheet.Item(
                ACTION_FLAG,
                getString(
                    if (meeting.starred) R.string.unstar_meeting else R.string.star_meeting
                )
            ),
            ActionSheet.Item(
                ACTION_DELETE,
                getString(R.string.delete_meeting_title),
                destructive = true,
                separated = true
            )
        )
        ActionSheet.show(this, meeting.title, items) { id ->
            when (id) {
                ACTION_FLAG -> toggleStar(meeting)
                ACTION_DELETE -> confirmDelete(meeting)
                else -> startActivity(
                    Intent(this, MeetingDetailActivity::class.java)
                        .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meeting.id)
                        .putExtra(MeetingDetailActivity.EXTRA_ACTION, id)
                )
            }
        }
    }

    /**
     * Flags a meeting for follow-up. Written against a freshly loaded copy so
     * a list that has been open for a while cannot push stale content back.
     */
    private fun toggleStar(meeting: Meeting) {
        val stored = store.load(meeting.id)
        if (stored == null) {
            refresh()
            return
        }
        val starred = !meeting.starred
        stored.starred = starred
        store.save(stored)
        meeting.starred = starred // the list holds this instance
        // Chips first: unflagging the last flagged meeting drops the Flagged
        // filter entirely, and filtering before that would leave the library
        // rendered empty with nothing selected to explain why.
        val wasFlagFiltered = flaggedOnly
        rebuildFilterChips()
        if (wasFlagFiltered) {
            applyFilter() // the flagged set changed, or the filter just went
        } else {
            adapter.refreshMeeting(meeting.id)
        }
    }

    private fun confirmDelete(meeting: Meeting) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_meeting_title)
            .setMessage(getString(R.string.delete_meeting_message, meeting.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                com.meetily.mobile.data.MeetingAssets.deleteAll(this, meeting)
                store.delete(meeting.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * True when nothing this app does with a recording would leave the phone
     * under the current settings: on-device transcription, and either no LLM
     * or the embedded one.
     */
    private fun staysOnDevice(): Boolean {
        val settings = AppSettings(this)
        // "whisper" here means any on-device engine (whisper.cpp, Parakeet,
        // Nemotron); anything else is the system recognizer, which on most
        // phones is Google's and may process audio in the cloud.
        if (settings.transcriptionEngine != "whisper") return false
        if (!settings.useLlm) return true
        return settings.llmEngine == "local"
    }

    private fun isNightNow(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    // Gentle 4s idle pulse on the orb's glow halo (paused off-screen).
    private var glowAnimator: android.animation.ValueAnimator? = null

    private fun startIdleGlow() {
        if (glowAnimator != null) return
        val glow = findViewById<View>(R.id.orbGlow)
        glowAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                glow.alpha = 0.5f + 0.4f * value
                val scale = 0.96f + 0.08f * value
                glow.scaleX = scale
                glow.scaleY = scale
            }
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        glowAnimator?.cancel()
        glowAnimator = null
    }

    companion object {
        const val ACTION_IMPORT_PICK = "com.meetily.mobile.ACTION_IMPORT_PICK"

        // Long-press sheet entries handled here rather than on the detail
        // screen; the rest are MeetingDetailActivity.ACTION_* values.
        private const val ACTION_FLAG = "flag"
        private const val ACTION_DELETE = "delete"
    }
}
