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
import com.meetily.mobile.data.PhotoStore
import com.meetily.mobile.search.MeetingGroups

class MainActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var adapter: MeetingAdapter
    private lateinit var emptyState: View
    private lateinit var meetingCount: TextView
    private lateinit var searchInput: EditText
    private lateinit var orbCaption: TextView
    private var allMeetings: List<Meeting> = emptyList()

    // Library filters: at most one active — a tag or a recurring series.
    private var selectedTag: String? = null
    private var selectedSeriesKey: String? = null
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
        override fun onImportProgress(percent: Int) {
            if (isFinishing || isDestroyed) return
            importBanner.visibility = View.VISIBLE
            importBannerName.text = getString(
                R.string.import_notif_title,
                importService?.sourceName?.ifBlank { null }
                    ?: getString(R.string.import_title)
            )
            importBannerBar.isIndeterminate = percent == 0
            importBannerBar.progress = percent
            importBannerPct.text = getString(R.string.percent_fmt, percent)
        }

        override fun onImportDone(
            meetingId: String?,
            wasCancelled: Boolean,
            error: String?,
            warning: String?
        ) {
            if (isFinishing || isDestroyed) return
            importBanner.visibility = View.GONE
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
            importBanner.visibility = View.GONE
        }
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
        super.onStop()
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
            onLongClick = { meeting -> confirmDelete(meeting) }
        )
        recycler.adapter = adapter

        orbCaption = findViewById(R.id.orbCaption)
        findViewById<View>(R.id.recordOrb).setOnClickListener {
            startActivity(Intent(this, RecordingActivity::class.java))
        }
        importBanner = findViewById(R.id.importBanner)
        importBannerName = findViewById(R.id.importBannerName)
        importBannerPct = findViewById(R.id.importBannerPct)
        importBannerBar = findViewById(R.id.importBannerBar)
        importBanner.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
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
            if (RecordingService.isRunning) R.string.orb_recording_caption
            else R.string.tap_to_record
        )
        findViewById<ImageButton>(R.id.themeToggle).setImageResource(
            if (isNightNow()) R.drawable.ic_sun else R.drawable.ic_moon
        )
        startIdleGlow()
        refresh()
    }

    private fun refresh() {
        allMeetings = store.list()
        searchInput.visibility = if (allMeetings.isEmpty()) View.GONE else View.VISIBLE
        rebuildFilterChips()
        applyFilter()
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
        filterChips.removeAllViews()
        if (tags.isEmpty() && series.isEmpty()) {
            filterChipsScroll.visibility = View.GONE
            return
        }
        filterChipsScroll.visibility = View.VISIBLE
        for (tag in tags) {
            addFilterChip(
                label = "#$tag",
                selected = tag.equals(selectedTag, ignoreCase = true)
            ) {
                selectedSeriesKey = null
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
    }

    private fun confirmDelete(meeting: Meeting) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_meeting_title)
            .setMessage(getString(R.string.delete_meeting_message, meeting.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                for (photo in meeting.photos) {
                    PhotoStore.delete(this, photo)
                }
                AudioStore.delete(this, meeting.audioFile)
                store.delete(meeting.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
    }
}
