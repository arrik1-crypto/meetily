package com.meetily.mobile

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.CalendarHelper
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.PhotoStore
import com.meetily.mobile.data.TranscriptSegment
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * UI shell for a recording session. All capture/transcription/state lives in
 * [RecordingService] (a foreground service), so recording continues when this
 * screen is backgrounded. This Activity binds to the service, renders its
 * state, and forwards user actions.
 */
class RecordingActivity : AppCompatActivity(), RecordingService.Observer {

    private lateinit var settings: AppSettings

    private lateinit var titleInput: EditText
    private lateinit var attendeesInput: EditText
    private lateinit var statusView: TextView
    private lateinit var elapsedView: TextView
    private lateinit var recordDot: View
    private lateinit var transcriptRecycler: RecyclerView
    private lateinit var transcriptAdapter: LiveTranscriptAdapter
    private lateinit var speakerChipScroll: View
    private lateinit var speakerChipRow: LinearLayout
    private lateinit var notesInput: EditText
    private lateinit var pauseButton: MaterialButton
    private lateinit var highlightButton: MaterialButton
    private lateinit var cameraButton: MaterialButton
    private lateinit var finishButton: MaterialButton
    private lateinit var calendarButton: MaterialButton
    private lateinit var initialDefaultTitle: String

    private var service: RecordingService? = null
    private var bound = false
    private var attached = false
    private var suppressWatchers = false

    private var pendingPhotoFile: File? = null
    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingPhotoFile
            pendingPhotoFile = null
            if (success && file != null && file.exists() && file.length() > 0) {
                service?.addPhoto(file.name)
                Toast.makeText(this, R.string.photo_added_short, Toast.LENGTH_SHORT).show()
            } else {
                file?.delete()
            }
        }

    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null

    private val timerTick = object : Runnable {
        override fun run() {
            service?.let {
                elapsedView.text = formatElapsed(it.elapsedMs())
                // Picked up here rather than at startNewSession(): the
                // service is started with startForegroundService, so
                // startedAtMs is only assigned once the queued
                // onStartCommand runs — after that method has returned.
                // The adapter ignores a repeat of the same value, so this
                // does real work exactly once.
                transcriptAdapter.meetingStartMs = it.startedAt()
            }
            handler.postDelayed(this, 500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? RecordingService.LocalBinder)?.service ?: return
            service = svc
            bound = true
            svc.setObserver(this@RecordingActivity)
            onServiceReady(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private var deviceAudioRequested = false

    /** Calendar event this session was launched for; blank/0 when not a nudge. */
    private var nudgeTitle = ""
    private var nudgeEventId = 0L

    private val projectionConsent = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            RecordingService.pendingProjectionCode = result.resultCode
            RecordingService.pendingProjectionData = data
            RecordingService.start(this, deviceAudio = true, initialTitle = nudgeTitle)
            Toast.makeText(this, R.string.device_audio_active, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.device_audio_denied, Toast.LENGTH_LONG).show()
            RecordingService.start(this, initialTitle = nudgeTitle)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_recording)
        deviceAudioRequested =
            intent?.getBooleanExtra(EXTRA_DEVICE_AUDIO, false) == true
        readNudgeExtras(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = AppSettings(this)

        titleInput = findViewById(R.id.titleInput)
        attendeesInput = findViewById(R.id.attendeesInput)
        statusView = findViewById(R.id.statusView)
        elapsedView = findViewById(R.id.elapsedView)
        recordDot = findViewById(R.id.recordDot)
        transcriptRecycler = findViewById(R.id.transcriptRecycler)
        transcriptAdapter = LiveTranscriptAdapter { index -> assignSpeaker(index) }
        transcriptAdapter.textSizeSp = when (AppSettings(this).transcriptTextSize) {
            "small" -> 13.5f
            "large" -> 17f
            else -> 15f
        }
        transcriptRecycler.layoutManager = LinearLayoutManager(this)
        transcriptRecycler.adapter = transcriptAdapter
        speakerChipScroll = findViewById(R.id.speakerChipScroll)
        speakerChipRow = findViewById(R.id.speakerChipRow)
        notesInput = findViewById(R.id.notesInput)
        pauseButton = findViewById(R.id.pauseButton)
        highlightButton = findViewById(R.id.highlightButton)
        cameraButton = findViewById(R.id.cameraButton)
        finishButton = findViewById(R.id.finishButton)
        calendarButton = findViewById(R.id.calendarButton)

        findViewById<MaterialToolbar>(R.id.recordingToolbar).setNavigationOnClickListener {
            confirmDiscard()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Backgrounding is fine — recording keeps running. Only the
                // toolbar back / discard tears the session down.
                moveTaskToBack(true)
            }
        })

        initialDefaultTitle = getString(
            R.string.default_meeting_title,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(System.currentTimeMillis()))
        )
        titleInput.setText(initialDefaultTitle)

        installWatchers()

        pauseButton.setOnClickListener { dismissNotesFocus(); service?.togglePause() }
        highlightButton.setOnClickListener { dismissNotesFocus(); onHighlightClicked() }
        cameraButton.setOnClickListener { dismissNotesFocus(); capturePhoto() }
        finishButton.setOnClickListener { dismissNotesFocus(); finishAndSave() }
        calendarButton.setOnClickListener {
            dismissNotesFocus()
            requestCalendarPrefill(manual = true)
        }
        findViewById<View>(R.id.catchUpButton).setOnClickListener {
            dismissNotesFocus()
            showCatchUp()
        }

        startPulse()

        bindService(
            Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    private fun installWatchers() {
        titleInput.addTextChangedListener(simpleWatcher { service?.updateTitle(it) })
        attendeesInput.addTextChangedListener(
            simpleWatcher {
                service?.updateAttendees(it)
                rebuildSpeakerChips()
            }
        )
        notesInput.addTextChangedListener(simpleWatcher { service?.updateNotes(it) })
        // With the keyboard up there is not enough room for the orb, the
        // timer AND the notes field, so typing collapses the capture header
        // and gives the space to notes.
        notesInput.setOnFocusChangeListener { _, hasFocus -> setNotesFocusMode(hasFocus) }
        // Dismissing the keyboard with Back leaves the EditText focused, so
        // focus alone would strand the screen in notes layout — no timer, no
        // recording indicator — for the rest of the session. Watch the IME.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.recordingRoot)) { _, insets ->
            if (!insets.isVisible(WindowInsetsCompat.Type.ime())) dismissNotesFocus()
            insets
        }
    }

    /**
     * One-tap live tagging: tap an attendee chip when they start talking and
     * every following segment is theirs until another chip (or the same one,
     * to clear) is tapped. The sticky state lives in the service.
     */
    private fun rebuildSpeakerChips() {
        val names = currentAttendees()
        speakerChipRow.removeAllViews()
        if (names.isEmpty()) {
            speakerChipScroll.visibility = View.GONE
            return
        }
        speakerChipScroll.visibility = View.VISIBLE
        val active = service?.activeSpeakerValue()
        val density = resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (6 * density).toInt()
        val margin = (8 * density).toInt()
        for (name in names) {
            val selected = name.equals(active, ignoreCase = true)
            val chip = TextView(this).apply {
                text = name
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                minHeight = (40 * density).toInt()
                isSelected = selected
                contentDescription = if (selected) {
                    getString(R.string.chip_active_desc, name)
                } else {
                    name
                }
                setBackgroundResource(
                    if (selected) R.drawable.bg_pill_accent else R.drawable.bg_pill
                )
                setTextColor(
                    themeColor(
                        if (selected) {
                            com.google.android.material.R.attr.colorOnPrimaryContainer
                        } else {
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                        }
                    )
                )
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = margin }
                setOnClickListener {
                    // Decide from live service state, not state captured at
                    // build time — the sticky speaker may have changed since.
                    val nowActive = service?.activeSpeakerValue()
                    val isActive = name.equals(nowActive, ignoreCase = true)
                    service?.setActiveSpeaker(if (isActive) null else name)
                    rebuildSpeakerChips()
                }
            }
            speakerChipRow.addView(chip)
        }
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!suppressWatchers) onChange(s?.toString().orEmpty())
        }
    }

    // --- Service session setup -------------------------------------------

    private fun onServiceReady(svc: RecordingService) {
        if (attached) return
        if (svc.active) {
            attachExistingSession(svc)
        } else {
            maybeConsentThenStart()
        }
    }

    private fun maybeConsentThenStart() {
        if (settings.recordingConsent) {
            ensurePermissionAndStart()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_message)
            .setCancelable(false)
            .setPositiveButton(R.string.consent_agree) { _, _ ->
                settings.recordingConsent = true
                ensurePermissionAndStart()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun attachExistingSession(svc: RecordingService) {
        attached = true
        suppressWatchers = true
        titleInput.setText(svc.titleValue())
        attendeesInput.setText(svc.attendeesValue())
        notesInput.setText(svc.notesValue())
        suppressWatchers = false

        // Before reset(), so the first bind already labels lines by their
        // offset into the recording rather than the time of day.
        transcriptAdapter.meetingStartMs = svc.startedAt()
        transcriptAdapter.reset(svc.segmentsSnapshot(), svc.currentPartial())
        scrollToBottom()
        statusView.text = svc.currentStatusText()
        applyPausedUi(svc.paused)
        rebuildSpeakerChips()
        startTimer()
    }

    private fun startNewSession() {
        attached = true
        // A nudge names the meeting. The service gets this on its start
        // Intent (see EXTRA_INITIAL_TITLE); this only mirrors it into the
        // field so the screen agrees with what was saved.
        if (nudgeTitle.isNotBlank()) {
            suppressWatchers = true
            titleInput.setText(nudgeTitle)
            suppressWatchers = false
        }
        // Sync any values the user typed before the service connected.
        service?.let {
            it.updateTitle(titleInput.text.toString())
            it.updateAttendees(attendeesInput.text.toString())
            it.updateNotes(notesInput.text.toString())
        }
        if (settings.transcriptionEngine == "whisper") {
            val key = settings.whisperModel
            if (!com.meetily.mobile.whisper.TranscriptionModels.isDownloaded(this, key)) {
                Toast.makeText(this, R.string.whisper_model_missing, Toast.LENGTH_LONG).show()
            } else if (
                !com.meetily.mobile.whisper.TranscriptionModels.isRuntimeAvailable(key)
            ) {
                Toast.makeText(this, R.string.whisper_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        if (deviceAudioRequested &&
            android.os.Build.VERSION.SDK_INT >= 29 &&
            whisperReadyForDeviceAudio()
        ) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            try {
                projectionConsent.launch(manager.createScreenCaptureIntent())
            } catch (_: Exception) {
                RecordingService.start(this, initialTitle = nudgeTitle)
            }
        } else {
            if (deviceAudioRequested) {
                Toast.makeText(
                    this, R.string.device_audio_needs_whisper, Toast.LENGTH_LONG
                ).show()
            }
            RecordingService.start(this, initialTitle = nudgeTitle)
        }
        rebuildSpeakerChips()
        startTimer()

        val calendarReadable = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (nudgeEventId != 0L) {
            // Never re-query on this path. findCurrentEvents picks whichever
            // event starts closest to now, which can legitimately be a
            // different one than the notification named — and the whole
            // point here is that the title came from a specific reminder.
            if (calendarReadable) mergeCalendarAttendees(nudgeEventId)
        } else if (settings.calendarPrefill && calendarReadable) {
            loadCalendarEvents(manual = false)
        }
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            ensureNotificationPermissionThenStart()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST
            )
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // The recording works without it; we just won't show the ongoing
            // notification. Ask once, then start regardless of the answer.
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQUEST
            )
        } else {
            startNewSession()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    ensureNotificationPermissionThenStart()
                } else {
                    statusView.text = getString(R.string.mic_permission_denied)
                    Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
                }
            }
            NOTIF_REQUEST -> startNewSession()
            CALENDAR_REQUEST -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    loadCalendarEvents(manual = true)
                } else {
                    Toast.makeText(this, R.string.calendar_permission_denied, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    // --- Observer callbacks (main thread) --------------------------------

    override fun onSegmentAppended(index: Int, segment: TranscriptSegment) {
        transcriptAdapter.append(segment)
        scrollToBottom()
    }

    override fun onSegmentUpdated(index: Int, segment: TranscriptSegment) {
        transcriptAdapter.update(index, segment)
    }

    override fun onPartial(text: String) {
        transcriptAdapter.setPartial(text)
        if (text.isNotEmpty()) scrollToBottom()
    }

    override fun onStatus(status: RecordingService.Status, text: String) {
        statusView.text = text
        if (status == RecordingService.Status.PAUSED) applyPausedUi(true)
        else if (status != RecordingService.Status.FINISHING) applyPausedUi(false)
    }

    override fun onFinished(meetingId: String) {
        openDetail(meetingId)
    }

    // --- Transcript rendering (recycled list) -----------------------------

    private fun scrollToBottom() {
        val last = transcriptAdapter.itemCount - 1
        if (last >= 0) {
            transcriptRecycler.post { transcriptRecycler.scrollToPosition(last) }
        }
    }

    // --- User actions -----------------------------------------------------

    // --- Live catch-up ------------------------------------------------------

    private var catchUpRunning = false

    /**
     * "Catch me up": summarizes the meeting so far into a bottom sheet.
     * Uses the configured LLM when available (portrait-locked screen, so no
     * rotation can kill the run); otherwise the instant extractive
     * summarizer — a late joiner gets an answer either way.
     */
    private fun showCatchUp() {
        if (catchUpRunning) return
        val segments = service?.segmentsSnapshot().orEmpty()
        val transcript = segments.joinToString("\n") { seg ->
            val speaker = seg.speaker
            if (speaker.isNullOrBlank()) seg.text else "$speaker: ${seg.text}"
        }
        if (transcript.isBlank()) {
            Toast.makeText(this, R.string.catch_up_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_catch_up, null)
        sheet.setContentView(content)
        val body = content.findViewById<TextView>(R.id.catchUpText)
        val progress = content.findViewById<View>(R.id.catchUpProgress)
        // Dismissing mid-request re-arms the button immediately; the orphaned
        // thread's result is discarded via the isShowing check below.
        sheet.setOnDismissListener { catchUpRunning = false }
        sheet.show()

        val useLlm = settings.useLlm && settings.llmConfigured
        if (!useLlm) {
            progress.visibility = View.GONE
            body.text = extractiveCatchUp(transcript)
            return
        }
        catchUpRunning = true
        Thread {
            val result = try {
                com.meetily.mobile.summarize.LlmClient.catchUp(
                    settings.llmBaseUrl, settings.llmApiKey, settings.llmModel,
                    settings.localOnlyLlm, transcript
                )
            } catch (_: Exception) {
                extractiveCatchUp(transcript)
            }
            runOnUiThread {
                catchUpRunning = false
                if (isFinishing || isDestroyed || !sheet.isShowing) return@runOnUiThread
                progress.visibility = View.GONE
                body.text = result
            }
        }.apply {
            name = "catch-up"
            start()
        }
    }

    private fun extractiveCatchUp(transcript: String): String = try {
        com.meetily.mobile.summarize.ExtractiveSummarizer.summarize(
            transcript.takeLast(12_000), "", emptyList(), false
        )
    } catch (_: Exception) {
        getString(R.string.catch_up_empty)
    }

    private fun onHighlightClicked() {
        val toggled = service?.requestHighlight() ?: return
        Toast.makeText(
            this,
            if (toggled) R.string.highlighted_toast else R.string.highlight_pending_toast,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun currentAttendees(): MutableList<String> =
        Meeting.parseAttendees(attendeesInput.text.toString())

    private fun assignSpeaker(index: Int) {
        val svc = service ?: return
        val segment = transcriptAdapter.segmentAt(index) ?: return
        // Recency decided now: segments arriving while the dialog is open
        // must not change whether this tag becomes sticky.
        val wasLatest = index == svc.segmentsSnapshot().size - 1
        val clusterId = if (segment.speaker.isNullOrBlank()) segment.clusterId else null
        val clusterLabel = clusterId?.let { getString(R.string.speaker_cluster_label, it) }
        val addAttendee: (String) -> Unit = { name ->
            val attendees = currentAttendees()
            if (attendees.none { it.equals(name, ignoreCase = true) }) {
                attendees.add(name)
                attendeesInput.setText(attendees.joinToString(", "))
            }
        }
        SpeakerPicker.show(
            this, currentAttendees(), segment.speaker,
            clusterLabel = clusterLabel,
            onRenameCluster = if (clusterId != null) {
                { name ->
                    svc.renameCluster(clusterId, name)
                    addAttendee(name)
                    rebuildSpeakerChips()
                    promptSaveVoiceprint(clusterId, name)
                }
            } else null
        ) { name ->
            svc.assignSpeaker(index, name, makeSticky = wasLatest)
            if (!name.isNullOrBlank()) addAttendee(name)
            rebuildSpeakerChips()
        }
    }

    /** Offer to keep this cluster's voice so future meetings auto-name it. */
    private fun promptSaveVoiceprint(clusterId: Int, name: String) {
        val svc = service ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.voice_save_prompt, name))
            .setPositiveButton(R.string.voice_save_yes) { _, _ ->
                val saved = svc.saveVoiceProfileFromCluster(clusterId, name)
                Toast.makeText(
                    this,
                    if (saved) getString(R.string.voice_saved, name)
                    else getString(R.string.voice_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.voice_save_no, null)
            .show()
    }

    private fun capturePhoto() {
        val svc = service ?: return
        val file = PhotoStore.newPhotoFile(this, svc.meetingId)
        pendingPhotoFile = file
        try {
            takePicture.launch(PhotoStore.uriFor(this, file))
        } catch (_: Exception) {
            pendingPhotoFile = null
            file.delete()
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishAndSave() {
        val svc = service
        if (svc == null || !svc.active) {
            finish()
            return
        }
        finishButton.isEnabled = false
        pauseButton.isEnabled = false
        statusView.text = getString(R.string.status_finishing)
        svc.finishAndSave { id ->
            if (!isFinishing && !isDestroyed) openDetail(id)
        }
    }

    private var opened = false
    private fun openDetail(meetingId: String) {
        if (opened) return
        opened = true
        try {
            startActivity(
                Intent(this, MeetingDetailActivity::class.java)
                    .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
                    // A conversation that just ended is exactly when the
                    // user knows what it followed on from.
                    .putExtra(MeetingDetailActivity.EXTRA_OFFER_FOLLOW, true)
            )
        } catch (_: Exception) {
            // Background-launch restrictions can block this when finishing from
            // the notification; the meeting is saved and appears in the list.
        }
        finish()
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.keep_recording, null)
            .setNegativeButton(R.string.discard) { _, _ ->
                service?.discard()
                finish()
            }
            .show()
    }

    // --- Calendar prefill -------------------------------------------------

    /**
     * Notes-first layout while typing: hides the orb/timer block and lets the
     * notes field take the freed height. Recording is unaffected — this is
     * purely how the screen is laid out.
     */
    /** Leaves notes-focus layout; the focus listener restores the header. */
    private fun dismissNotesFocus() {
        if (notesInput.hasFocus()) notesInput.clearFocus()
    }

    private fun setNotesFocusMode(active: Boolean) {
        val header = findViewById<View>(R.id.captureHeaderBlock) ?: return
        header.visibility = if (active) View.GONE else View.VISIBLE
        val transcript = findViewById<View>(R.id.transcriptRecycler)
        (transcript?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = if (active) 1f else 3f
            transcript.layoutParams = lp
        }
        (notesInput.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = if (active) 4f else 2f
            notesInput.layoutParams = lp
        }
    }

    private fun requestCalendarPrefill(manual: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            == PackageManager.PERMISSION_GRANTED
        ) {
            loadCalendarEvents(manual)
        } else if (manual) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_CALENDAR), CALENDAR_REQUEST
            )
        }
    }

    private fun loadCalendarEvents(manual: Boolean) {
        Thread {
            val events = CalendarHelper.findCurrentEvents(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    events.isEmpty() ->
                        if (manual) {
                            Toast.makeText(this, R.string.no_calendar_event, Toast.LENGTH_SHORT)
                                .show()
                        }
                    manual && events.size > 1 -> {
                        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                        val labels = events.map {
                            "${it.title} (${timeFormat.format(Date(it.beginMs))})"
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle(R.string.choose_calendar_event)
                            .setItems(labels) { _, which ->
                                applyCalendarEvent(events[which], overwriteTitle = true)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    else -> applyCalendarEvent(events.first(), overwriteTitle = manual)
                }
            }
        }.start()
    }

    /**
     * Reads the calendar-nudge identity off a launch Intent.
     *
     * Its presence is the entire signal: before this, a nudge tap and an orb
     * tap arrived here byte-identical, so there was no way to tell whether
     * the user had asserted "this recording is that meeting".
     */
    private fun readNudgeExtras(from: Intent?) {
        val title = from?.getStringExtra(EXTRA_NUDGE_TITLE).orEmpty()
        val id = from?.getLongExtra(EXTRA_NUDGE_EVENT_ID, 0L) ?: 0L
        if (title.isNotBlank() && id != 0L) {
            nudgeTitle = title
            nudgeEventId = id
        }
    }

    /**
     * A nudge Intent carries FLAG_ACTIVITY_NEW_TASK, and this Activity
     * declares no launchMode — so when the task is already rooted here (after
     * a widget, tile, shortcut or earlier nudge launch) the system fronts the
     * task and drops the Intent instead of calling onCreate. Re-reading it
     * covers what can be covered; the rest degrades to the old behaviour of a
     * date title, never to a title from the wrong meeting.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNudgeExtras(intent)
        // A session already under way keeps the title it started with:
        // renaming a recording in progress from a notification the user may
        // have tapped by accident would be worse than leaving it.
        if (!attached && nudgeTitle.isNotBlank()) {
            suppressWatchers = true
            titleInput.setText(nudgeTitle)
            suppressWatchers = false
        }
    }

    /**
     * Merges one known event's guests into the attendee field WITHOUT
     * touching the title — the nudge path has already set that.
     */
    private fun mergeCalendarAttendees(eventId: Long) {
        Thread {
            val eventAttendees = CalendarHelper.attendeesFor(this, eventId)
            if (eventAttendees.isEmpty()) return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val merged = currentAttendees()
                for (name in eventAttendees) {
                    if (merged.none { it.equals(name, ignoreCase = true) }) merged.add(name)
                }
                attendeesInput.setText(merged.joinToString(", "))
            }
        }.start()
    }

    private fun applyCalendarEvent(
        event: CalendarHelper.CalendarEvent,
        overwriteTitle: Boolean
    ) {
        Thread {
            val eventAttendees = CalendarHelper.attendeesFor(this, event.eventId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Only an explicit "From calendar" tap renames the meeting.
                // The automatic prefill still merges guests below, but a
                // recording the user started from the orb keeps its date
                // title — it was never asserted to be that calendar event.
                if (overwriteTitle) {
                    titleInput.setText(event.title)
                }
                if (eventAttendees.isNotEmpty()) {
                    val merged = currentAttendees()
                    for (name in eventAttendees) {
                        if (merged.none { it.equals(name, ignoreCase = true) }) merged.add(name)
                    }
                    attendeesInput.setText(merged.joinToString(", "))
                }
                Toast.makeText(
                    this, getString(R.string.calendar_prefilled, event.title), Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    // --- Timer + pulse ----------------------------------------------------

    private fun startTimer() {
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
    }

    private fun applyPausedUi(paused: Boolean) {
        if (paused) {
            stopPulse()
            stopGlow()
            findViewById<EqBarsView>(R.id.eqBars).setPaused(true)
            pauseButton.setIconResource(R.drawable.ic_play)
            pauseButton.contentDescription = getString(R.string.resume)
        } else {
            startPulse()
            startGlow()
            findViewById<EqBarsView>(R.id.eqBars).setPaused(false)
            pauseButton.setIconResource(R.drawable.ic_pause)
            pauseButton.contentDescription = getString(R.string.pause)
        }
    }

    // Breathing orb glow (the design's 3.2s box-shadow keyframe, done with
    // alpha + scale on the radial-gradient halo behind the orb).
    private var glowAnimator: ValueAnimator? = null

    private fun startGlow() {
        if (glowAnimator != null) return
        val glow = findViewById<View>(R.id.recOrbGlow)
        glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                glow.alpha = 0.55f + 0.45f * value
                val scale = 0.94f + 0.12f * value
                glow.scaleX = scale
                glow.scaleY = scale
            }
            start()
        }
    }

    private fun stopGlow() {
        glowAnimator?.cancel()
        glowAnimator = null
        findViewById<View>(R.id.recOrbGlow).alpha = 0.35f
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = ObjectAnimator.ofFloat(recordDot, View.ALPHA, 1f, 0.25f).apply {
            duration = 750
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        recordDot.alpha = 0.3f
    }

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerTick)
        stopPulse()
        stopGlow()
        if (bound) {
            service?.clearObserver(this)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun whisperReadyForDeviceAudio(): Boolean {
        if (settings.transcriptionEngine != "whisper") return false
        return com.meetily.mobile.whisper.TranscriptionModels
            .isReady(this, settings.whisperModel)
    }

    companion object {
        const val EXTRA_DEVICE_AUDIO = "device_audio"

        /**
         * Set only by a calendar nudge notification, naming the event it was
         * posted for. Its presence is what distinguishes "started from THIS
         * meeting's reminder" from every other way in.
         */
        const val EXTRA_NUDGE_TITLE = "nudge_title"
        const val EXTRA_NUDGE_EVENT_ID = "nudge_event_id"
        private const val PERMISSION_REQUEST = 4001
        private const val CALENDAR_REQUEST = 4002
        private const val NOTIF_REQUEST = 4003
    }
}
