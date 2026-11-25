package com.meetily.mobile

import android.Manifest
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
import android.os.SystemClock
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

    /** Which status changes are read out; see RecordingStatusSpeech. */
    private val statusSpeech = RecordingStatusSpeech()
    private lateinit var elapsedView: TextView
    private lateinit var recordDot: View
    private lateinit var transcriptRecycler: RecyclerView
    private lateinit var transcriptAdapter: LiveTranscriptAdapter
    private lateinit var levelMeter: LevelMeterView
    private lateinit var audioOnlyPanel: View

    // Read on the audio thread in onLevel.
    @Volatile
    private var audioOnly = false
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

    /**
     * The meeting id of the session this screen has seen running, or null
     * before that. Once that session is over the screen has nothing left to
     * show; see [closeIfSessionOver].
     */
    private var sessionId: String? = null
    private var suppressWatchers = false

    /**
     * Saved with the screen's state. On a large screen the portrait lock is
     * ignored at targetSdk 36, so turning the device while the camera app is
     * in front re-creates this Activity; the result still arrives, and with
     * the field back to null the photo the user had just taken was dropped.
     */
    private var pendingPhotoFile: File? = null

    /**
     * A photo whose result reached a re-created screen before its service
     * connection did (results are delivered at onStart, the bind lands
     * later). Handed to the session in [onServiceReady]. Saved with the
     * screen's state too, so a second re-creation inside that window does
     * not drop it and leave its file behind.
     */
    private var photoAwaitingService: File? = null
    private val takePicture =
        registerForActivityResult(TakePictureWithGrant()) { success ->
            val file = pendingPhotoFile
            pendingPhotoFile = null
            if (success && file != null && file.exists() && file.length() > 0) {
                val svc = service
                if (svc != null) addPhotoToSession(svc, file) else photoAwaitingService = file
            } else {
                file?.delete()
            }
        }

    private fun addPhotoToSession(svc: RecordingService, file: File) {
        svc.addPhoto(file.name)
        Toast.makeText(this, R.string.photo_added_short, Toast.LENGTH_SHORT).show()
    }

    /** Saved with the screen's state; see [RecordingStartStage]. */
    private var startStage = RecordingStartStage.IDLE

    /** Until when [awaitStart] keeps waiting for a queued start to run. */
    private var startWaitDeadline = 0L

    /**
     * A re-created screen whose session start was sent but not yet run by the
     * service: attach once it is live, close if it never comes or has
     * already ended. Never starts anything itself.
     */
    private val awaitStart = object : Runnable {
        override fun run() {
            val svc = service ?: return
            if (attached || isFinishing || isDestroyed) return
            when {
                svc.active -> attachExistingSession(svc)
                RecordingService.isRunning &&
                    SystemClock.elapsedRealtime() < startWaitDeadline ->
                    handler.postDelayed(this, START_POLL_MS)
                else -> finish()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Between onStart and onStop; nothing animates or ticks outside it. */
    private var visible = false

    private val timerTick = object : Runnable {
        override fun run() {
            // Only once the session is live. startTimer() runs immediately
            // after RecordingService.start(), and startForegroundService
            // defers onStartCommand to a later main-thread message — so this
            // first tick fires before the session's clock has been set, and
            // used to render time since boot ("142:07:33" on a phone up for
            // six days). The device-audio path makes that window much wider,
            // because start() waits on the consent dialog.
            service?.takeIf { it.active }?.let {
                if (sessionId == null) sessionId = it.meetingId
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
        // A session already under way keeps its capture. Starting it again
        // would be ignored by the service, and the unused consent would sit
        // in pendingProjectionData until that session ended.
        if (RecordingService.isRunning) {
            startStage = RecordingStartStage.STARTED
            return@registerForActivityResult
        }
        startStage = RecordingStartStage.STARTED
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
        // Before anything else: the activity-result registry replays a pending
        // camera or consent result once this screen restarts, and both
        // callbacks need to know what the previous instance had asked for.
        startStage = RecordingStartStage.fromSaved(
            savedInstanceState?.getString(STATE_START_STAGE)
        )
        savedInstanceState?.getString(STATE_PENDING_PHOTO)?.let {
            pendingPhotoFile = File(it)
        }
        savedInstanceState?.getString(STATE_PHOTO_AWAITING_SERVICE)?.let {
            photoAwaitingService = File(it)
        }
        ThemeManager.apply(this)
        settings = AppSettings(this)
        warmNativeEngines()
        setContentView(R.layout.activity_recording)
        EdgeToEdge.setUp(this)
        deviceAudioRequested =
            intent?.getBooleanExtra(EXTRA_DEVICE_AUDIO, false) == true
        readNudgeExtras(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        // Audio-only is the default way the app records: there is no live
        // transcript to show, so the list is replaced by a level meter that
        // proves the microphone is picking the room up. This is only the
        // layout before the service starts; the service reports the capture
        // it actually runs (onCaptureMode), and that is what the screen keeps.
        levelMeter = findViewById(R.id.levelMeter)
        audioOnlyPanel = findViewById(R.id.audioOnlyPanel)
        applyCaptureMode(RecordingService.plannedAudioOnly(this, settings))
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
        // The same listener keeps the screen clear of the system bars and the
        // keyboard (a view holds one listener): edge-to-edge, the window is
        // no longer resized for the IME, so the root pads itself by the
        // keyboard or the navigation bar, whichever is taller.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.recordingRoot)) { root, insets ->
            EdgeToEdge.padRoot(root, insets)
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
                isSelected = selected
                contentDescription = if (selected) {
                    getString(R.string.chip_active_desc, name)
                } else {
                    name
                }
                // A 40dp pill inside a 48dp touch target.
                A11y.pillTouchTarget(
                    this,
                    if (selected) R.drawable.bg_pill_accent else R.drawable.bg_pill,
                    visualHeightPx = (40 * density).toInt(),
                    paddingHorizontalPx = padH,
                    paddingVerticalPx = padV
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
        photoAwaitingService?.let { file ->
            photoAwaitingService = null
            // Taken during a session that has since ended: nothing to add it to.
            if (svc.active) addPhotoToSession(svc, file) else file.delete()
        }
        if (attached) return
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        when (
            RecordingStartStage.resume(
                startStage, svc.active, RecordingService.isRunning, micGranted
            )
        ) {
            RecordingStartStage.Resume.ATTACH -> attachExistingSession(svc)
            RecordingStartStage.Resume.START_FLOW -> {
                startStage = RecordingStartStage.IDLE
                maybeConsentThenStart()
            }
            RecordingStartStage.Resume.WAIT_FOR_RESULT -> {
                // The consent result starts the session in its callback; until
                // then the screen looks as it did once startNewSession ran.
                if (startStage == RecordingStartStage.AWAITING_PROJECTION) {
                    attached = true
                    rebuildSpeakerChips()
                    startTimer()
                }
            }
            RecordingStartStage.Resume.WAIT_FOR_START -> {
                startWaitDeadline = SystemClock.elapsedRealtime() + START_WAIT_MS
                handler.post(awaitStart)
            }
            RecordingStartStage.Resume.SHOW_MIC_DENIED ->
                statusView.text = getString(R.string.mic_permission_denied)
            RecordingStartStage.Resume.SHOW_DEVICE_AUDIO_WARNING -> {
                // The previous instance already ran consent, the permission
                // prompts, the model check and the calendar prefill; only the
                // warning it had up is shown again.
                attached = true
                rebuildSpeakerChips()
                startTimer()
                showDeviceAudioWarning()
            }
            RecordingStartStage.Resume.CLOSE -> finish()
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
        sessionId = svc.meetingId
        startStage = RecordingStartStage.STARTED
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
        statusSpeech.seed(svc.currentStatus(), svc.currentStatusText())
        applyCaptureMode(svc.capturesAudioOnly())
        applyPausedUi(svc.paused)
        // Re-created while the session was saving: a second Finish would open
        // the meeting before the save that the first one started has landed.
        if (svc.currentStatus() == RecordingService.Status.FINISHING) {
            finishButton.isEnabled = false
            pauseButton.isEnabled = false
        }
        applyDeviceAudioEndedUi()
        rebuildSpeakerChips()
        startTimer()
    }

    private fun startNewSession() {
        // A late or repeated permission result must not start a second time.
        if (attached) return
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
            } else {
                // The first runtime check loads the native library, so it runs
                // off the main thread; the Toast is all that depends on it.
                Thread {
                    if (!com.meetily.mobile.whisper.TranscriptionModels.isRuntimeAvailable(key)) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            Toast.makeText(this, R.string.whisper_unavailable, Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                }.start()
            }
        }
        if (deviceAudioRequested &&
            android.os.Build.VERSION.SDK_INT >= 29 &&
            whisperReadyForDeviceAudio()
        ) {
            // Android 15 QPR1+ ends the capture when the screen locks; that
            // has to be said before the meeting, not found out after it.
            // The warning is a plain dialog that a re-creation dismisses.
            // Consent and permissions are settled by now, so the stage
            // records that the warning is up: the new screen shows it again
            // by itself instead of re-running the start flow, which would
            // ask for notifications a second time.
            startStage = RecordingStartStage.AWAITING_DEVICE_AUDIO_WARNING
            showDeviceAudioWarning()
        } else {
            if (deviceAudioRequested) {
                Toast.makeText(
                    this, R.string.device_audio_needs_whisper, Toast.LENGTH_LONG
                ).show()
            }
            startStage = RecordingStartStage.STARTED
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

    /**
     * The screen-lock warning before the device-audio consent, ending in that
     * consent or, if the user would rather, a microphone-only session.
     */
    private fun showDeviceAudioWarning() {
        DeviceAudioLockDialog.showIfNeeded(
            this, settings,
            onContinue = { launchProjectionConsent() },
            onMicrophone = {
                Toast.makeText(
                    this, R.string.devaudio_lock_mic_chosen, Toast.LENGTH_SHORT
                ).show()
                startStage = RecordingStartStage.STARTED
                RecordingService.start(this, initialTitle = nudgeTitle)
            }
        )
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            ensureNotificationPermissionThenStart()
        } else {
            startStage = RecordingStartStage.AWAITING_PERMISSION
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
            startStage = RecordingStartStage.AWAITING_PERMISSION
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
                    startStage = RecordingStartStage.MIC_DENIED
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

    /** Arrives on the AUDIO thread, ~10x a second. Hop before touching views. */
    override fun onLevel(rms: Float) {
        if (audioOnly) {
            levelMeter.post {
                if (!isFinishing && !isDestroyed) levelMeter.push(rms)
            }
        }
    }

    override fun onCaptureMode(audioOnly: Boolean) {
        applyCaptureMode(audioOnly)
    }

    /**
     * The level-meter panel only when the session records audio with no live
     * words — which is also the only case its "the transcript is made when
     * you stop" note is true. Otherwise the transcript list: on the system
     * recognizer it is the session's only output, and hiding it behind a
     * meter that never moved made a working recording look dead.
     */
    private fun applyCaptureMode(audioOnly: Boolean) {
        this.audioOnly = audioOnly
        transcriptRecycler.visibility = if (audioOnly) View.GONE else View.VISIBLE
        findViewById<View>(R.id.transcriptHeading).visibility =
            if (audioOnly) View.GONE else View.VISIBLE
        audioOnlyPanel.visibility = if (audioOnly) View.VISIBLE else View.GONE
    }

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
        A11y.setStatusText(statusView, text, statusSpeech.shouldAnnounce(status, text))
        if (status == RecordingService.Status.PAUSED) applyPausedUi(true)
        else if (status != RecordingService.Status.FINISHING) applyPausedUi(false)
        applyDeviceAudioEndedUi()
    }

    /**
     * Android ended the device-audio capture, and nothing short of a new
     * consent brings it back: Resume would only claim to be recording again.
     * Finish stays, so what was captured can be saved.
     */
    private fun applyDeviceAudioEndedUi() {
        val svc = service ?: return
        val deviceAudio = svc.deviceAudioEnded
        // The microphone capture stopping on its own ends the session's
        // capture the same way.
        if (!deviceAudio && !svc.captureEnded) return
        pauseButton.isEnabled = false
        // applyPausedUi labelled it "Resume", which TalkBack would still
        // announce for an action that no longer exists.
        pauseButton.contentDescription = getString(
            if (deviceAudio) R.string.devaudio_notif_ended else R.string.services_notif_capture_ended
        )
        // In audio-only capture (the default) this panel is the screen's main
        // text, and "Recording audio." under a status line saying device
        // audio stopped left the user to guess which one was true.
        findViewById<TextView>(R.id.audioOnlyNote).setText(
            if (deviceAudio) DeviceAudioLock.endedDetailRes(Build.VERSION.SDK_INT)
            else R.string.services_notif_capture_ended_detail
        )
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
     * Uses the configured LLM when available; otherwise the instant
     * extractive summarizer — a late joiner gets an answer either way. The
     * portrait lock no longer holds on large screens at targetSdk 36, so a
     * rotation can re-create the screen mid-run; the result is then dropped
     * by the isDestroyed check below and the user simply asks again.
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
        com.meetily.mobile.security.AppLock.secure(sheet)
        sheet.show()

        val useLlm = settings.useLlm && settings.llmConfigured
        if (!useLlm) {
            progress.visibility = View.GONE
            body.text = extractiveCatchUp(transcript)
            return
        }
        catchUpRunning = true
        // After the text's scroll container: the scroll view holds one child.
        val scroll = body.parent as View
        val report = AiReport.addButton(scroll) {}
        report.visibility = View.GONE
        // A long result would otherwise take all the height the sheet has
        // and squeeze the button to nothing. Weighted at height 0, the
        // scroll view gives up the overflow and the button keeps its size.
        (scroll.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
            lp.height = 0
            lp.weight = 1f
            scroll.layoutParams = lp
        }
        Thread {
            var modelKey = AiReport.currentModelKey(this)
            val result = try {
                com.meetily.mobile.summarize.LlmClient.catchUp(
                    settings.llmBaseUrl, settings.llmApiKey, settings.llmModel,
                    settings.localOnlyLlm, transcript
                )
            } catch (_: Exception) {
                modelKey = com.meetily.mobile.summarize.AiProvenance.EXTRACTIVE
                extractiveCatchUp(transcript)
            }
            runOnUiThread {
                catchUpRunning = false
                if (isFinishing || isDestroyed || !sheet.isShowing) return@runOnUiThread
                progress.visibility = View.GONE
                body.text = result
                // The extractive fallback picks sentences from the transcript;
                // no model wrote it, so there is nothing to report.
                if (modelKey == com.meetily.mobile.summarize.AiProvenance.EXTRACTIVE) {
                    report.visibility = View.GONE
                } else {
                    report.visibility = View.VISIBLE
                    report.setOnClickListener {
                        AiReport.show(
                            this,
                            AiReport.Subject(
                                label = getString(R.string.aisafety_report_action),
                                surface = "live_catch_up",
                                text = result,
                                modelKey = modelKey
                            )
                        )
                    }
                }
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
        val result = service?.requestHighlight() ?: return
        Toast.makeText(
            this,
            when (result) {
                RecordingService.HighlightResult.TOGGLED -> R.string.highlighted_toast
                RecordingService.HighlightResult.QUEUED -> R.string.highlight_pending_toast
                RecordingService.HighlightResult.MARKED -> R.string.highlight_marked_toast
            },
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
            .showSecure()
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
        val finishing = getString(R.string.status_finishing)
        A11y.setStatusText(
            statusView, finishing,
            statusSpeech.shouldAnnounce(RecordingService.Status.FINISHING, finishing)
        )
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
        // Nothing is recording: a screen left over from a session that has
        // been saved must not offer to discard it (the service refuses too).
        if (service?.active != true) {
            finish()
            return
        }
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
                            .showSecure()
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
     * A nudge, the tile or the widget tapped while this screen is up: it is
     * singleTop, and those launches ask for the existing screen, so the
     * Intent arrives here instead of a second screen being stacked over this
     * one. Re-reading it covers what can be covered; the rest degrades to
     * the old behaviour of a date title, never to a title from the wrong
     * meeting.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (sessionOver()) {
            // This screen's session ended without it, and it cannot start
            // another: a fresh screen takes the tap, with what it carried.
            finish()
            startActivity(
                Intent(intent).setClass(this, RecordingActivity::class.java).setFlags(0)
            )
            return
        }
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
            // Nothing is being heard; the last bars would stay up, frozen.
            levelMeter.clear()
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
    // alpha + scale on the radial-gradient halo behind the orb) and the
    // pulsing record dot.
    //
    // Stepped from one ~15 fps handler tick rather than two infinite
    // animators. The animators asked for a frame on every vsync for the whole
    // meeting — with the screen held on, that kept the display composing at
    // its full 60-120 Hz and stopped an LTPO panel ever idling down — and on
    // Android 13 and below they kept ticking while the screen was in the
    // background. The motion is slow enough that 15 fps reads the same.
    private var glowing = false
    private var pulsing = false

    private val ambientTick = object : Runnable {
        override fun run() {
            if (!visible || !(glowing || pulsing)) return
            val now = SystemClock.uptimeMillis()
            if (glowing) {
                val value = easedWave(now, GLOW_HALF_PERIOD_MS)
                val glow = findViewById<View>(R.id.recOrbGlow)
                glow.alpha = 0.55f + 0.45f * value
                val scale = 0.94f + 0.12f * value
                glow.scaleX = scale
                glow.scaleY = scale
            }
            if (pulsing) {
                recordDot.alpha = 1f - 0.75f * easedWave(now, PULSE_HALF_PERIOD_MS)
            }
            handler.postDelayed(this, AMBIENT_FRAME_MS)
        }
    }

    /** 0 to 1 and back over twice [halfPeriodMs], eased at both ends. */
    private fun easedWave(nowMs: Long, halfPeriodMs: Long): Float {
        val phase = (nowMs % (2 * halfPeriodMs)).toDouble() / halfPeriodMs
        return ((1.0 - kotlin.math.cos(Math.PI * phase)) / 2.0).toFloat()
    }

    /** Runs the tick exactly when something should move and it can be seen. */
    private fun scheduleAmbient() {
        handler.removeCallbacks(ambientTick)
        if (visible && (glowing || pulsing)) handler.post(ambientTick)
    }

    private fun startGlow() {
        if (glowing) return
        glowing = true
        scheduleAmbient()
    }

    private fun stopGlow() {
        glowing = false
        scheduleAmbient()
        findViewById<View>(R.id.recOrbGlow).alpha = 0.35f
    }

    private fun startPulse() {
        if (pulsing) return
        pulsing = true
        scheduleAmbient()
    }

    private fun stopPulse() {
        pulsing = false
        scheduleAmbient()
        recordDot.alpha = 0.3f
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (closeIfSessionOver()) return
        scheduleAmbient()
        // The timer only ever shows a live session's clock; it idles on its
        // own until the service is active.
        if (attached) startTimer()
    }

    /**
     * Finishes a screen whose session has ended without it. The service
     * tells one screen that a session finished, so another one still in
     * the back stack stayed bound with the last meeting's title, notes and
     * a frozen clock, offering Pause, Finish and Discard over a meeting
     * that was already saved.
     */
    private fun closeIfSessionOver(): Boolean {
        if (!sessionOver()) return false
        if (!isFinishing) finish()
        return true
    }

    /** True once the session this screen showed is no longer running. */
    private fun sessionOver(): Boolean {
        val svc = service ?: return false
        val seen = sessionId ?: return false
        return !svc.active || svc.meetingId != seen
    }

    override fun onStop() {
        // Back only moves the task behind, so this screen lives for the whole
        // meeting; nothing it draws is visible from here until onStart.
        visible = false
        handler.removeCallbacks(ambientTick)
        handler.removeCallbacks(timerTick)
        super.onStop()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_START_STAGE, startStage.name)
        pendingPhotoFile?.let { outState.putString(STATE_PENDING_PHOTO, it.absolutePath) }
        photoAwaitingService?.let {
            outState.putString(STATE_PHOTO_AWAITING_SERVICE, it.absolutePath)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerTick)
        handler.removeCallbacks(ambientTick)
        handler.removeCallbacks(awaitStart)
        stopPulse()
        stopGlow()
        if (bound) {
            service?.clearObserver(this)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    /**
     * Device audio needs the on-device pipeline, not a ready model: with no
     * model the session captures audio only and transcribes it afterwards.
     */
    private fun whisperReadyForDeviceAudio(): Boolean =
        settings.transcriptionEngine == "whisper"

    /**
     * Starts loading the native library in the background when this screen
     * is going to need it anyway: [RecordingService.plannedAudioOnly] loads it
     * on the main thread for live Whisper, and [startNewSession] checks the
     * runtime of a downloaded Whisper model. The library holds llama.cpp as
     * well as whisper.cpp, so its first load is worth overlapping with
     * inflating the layout. NeMo models run on sherpa-onnx instead.
     */
    private fun warmNativeEngines() {
        if (settings.transcriptionEngine != "whisper") return
        val key = settings.whisperModel
        if (com.meetily.mobile.whisper.TranscriptionModels.isNemo(key)) return
        if (settings.liveTranscription ||
            com.meetily.mobile.whisper.TranscriptionModels.isDownloaded(this, key)
        ) {
            com.meetily.mobile.data.NativeEngines.warmUp()
        }
    }

    /**
     * The system screen-capture consent; declined or failed means microphone.
     * Records the stage first, so a screen re-created while the consent is up
     * waits for its result instead of asking twice (see [RecordingStartStage]).
     */
    private fun launchProjectionConsent() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        try {
            startStage = RecordingStartStage.AWAITING_PROJECTION
            projectionConsent.launch(manager.createScreenCaptureIntent())
        } catch (_: Exception) {
            startStage = RecordingStartStage.STARTED
            RecordingService.start(this, initialTitle = nudgeTitle)
        }
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

        private const val STATE_START_STAGE = "recording_start_stage"
        private const val STATE_PENDING_PHOTO = "recording_pending_photo"
        private const val STATE_PHOTO_AWAITING_SERVICE = "recording_photo_awaiting_service"

        /** How often, and for how long, a re-created screen waits on a queued start. */
        private const val START_POLL_MS = 100L
        private const val START_WAIT_MS = 10_000L

        private const val AMBIENT_FRAME_MS = 66L
        private const val GLOW_HALF_PERIOD_MS = 1600L
        private const val PULSE_HALF_PERIOD_MS = 750L
    }
}
