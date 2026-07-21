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
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.CalendarHelper
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.PhotoStore
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.whisper.WhisperModels
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
    private lateinit var partialView: TextView
    private lateinit var transcriptContainer: LinearLayout
    private lateinit var transcriptScroll: ScrollView
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

    private val segments = mutableListOf<TranscriptSegment>()
    private val bubbleViews = mutableListOf<View>()

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
            service?.let { elapsedView.text = formatElapsed(it.elapsedMs()) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_recording)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = AppSettings(this)

        titleInput = findViewById(R.id.titleInput)
        attendeesInput = findViewById(R.id.attendeesInput)
        statusView = findViewById(R.id.statusView)
        elapsedView = findViewById(R.id.elapsedView)
        recordDot = findViewById(R.id.recordDot)
        partialView = findViewById(R.id.partialView)
        transcriptContainer = findViewById(R.id.transcriptContainer)
        transcriptScroll = findViewById(R.id.transcriptScroll)
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

        pauseButton.setOnClickListener { service?.togglePause() }
        highlightButton.setOnClickListener { onHighlightClicked() }
        cameraButton.setOnClickListener { capturePhoto() }
        finishButton.setOnClickListener { finishAndSave() }
        calendarButton.setOnClickListener { requestCalendarPrefill(manual = true) }

        startPulse()

        bindService(
            Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    private fun installWatchers() {
        titleInput.addTextChangedListener(simpleWatcher { service?.updateTitle(it) })
        attendeesInput.addTextChangedListener(simpleWatcher { service?.updateAttendees(it) })
        notesInput.addTextChangedListener(simpleWatcher { service?.updateNotes(it) })
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
            ensurePermissionAndStart()
        }
    }

    private fun attachExistingSession(svc: RecordingService) {
        attached = true
        suppressWatchers = true
        titleInput.setText(svc.titleValue())
        attendeesInput.setText(svc.attendeesValue())
        notesInput.setText(svc.notesValue())
        suppressWatchers = false

        segments.clear()
        bubbleViews.clear()
        for (i in transcriptContainer.childCount - 1 downTo 0) {
            val child = transcriptContainer.getChildAt(i)
            if (child.id != R.id.partialView) transcriptContainer.removeViewAt(i)
        }
        for ((index, segment) in svc.segmentsSnapshot().withIndex()) {
            addBubble(index, segment)
        }
        renderPartial(svc.currentPartial())
        statusView.text = svc.currentStatusText()
        applyPausedUi(svc.paused)
        startTimer()
    }

    private fun startNewSession() {
        attached = true
        // Sync any values the user typed before the service connected.
        service?.let {
            it.updateTitle(titleInput.text.toString())
            it.updateAttendees(attendeesInput.text.toString())
            it.updateNotes(notesInput.text.toString())
        }
        if (settings.transcriptionEngine == "whisper") {
            val model = WhisperModels.byKey(settings.whisperModel)
            if (!WhisperModels.isDownloaded(this, model)) {
                Toast.makeText(this, R.string.whisper_model_missing, Toast.LENGTH_LONG).show()
            } else if (!WhisperModels.isRuntimeAvailable()) {
                Toast.makeText(this, R.string.whisper_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        RecordingService.start(this)
        startTimer()

        if (settings.calendarPrefill &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            == PackageManager.PERMISSION_GRANTED
        ) {
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
        addBubble(index, segment)
        scrollToBottom()
    }

    override fun onSegmentUpdated(index: Int, segment: TranscriptSegment) {
        if (index in segments.indices) segments[index] = segment
        refreshBubble(index)
    }

    override fun onPartial(text: String) {
        renderPartial(text)
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

    // --- Transcript rendering (bubble list) ------------------------------

    private fun addBubble(index: Int, segment: TranscriptSegment) {
        if (index < segments.size) {
            segments[index] = segment
        } else {
            segments.add(segment)
        }
        val bubble = LayoutInflater.from(this)
            .inflate(R.layout.item_transcript_segment, transcriptContainer, false)
        bubble.findViewById<TextView>(R.id.segmentText).text = segment.text
        bubble.setOnClickListener { assignSpeaker(index) }
        bubbleViews.add(bubble)

        val partialIndex = transcriptContainer.indexOfChild(partialView)
        transcriptContainer.addView(bubble, if (partialIndex >= 0) partialIndex else -1)
        refreshBubble(index)
    }

    private fun refreshBubble(index: Int) {
        val bubble = bubbleViews.getOrNull(index) ?: return
        val segment = segments.getOrNull(index) ?: return
        bubble.setBackgroundResource(
            if (segment.highlighted) R.drawable.bg_bubble_highlight else R.drawable.bg_bubble
        )
        bubble.findViewById<TextView>(R.id.segmentTime).text = timeLabel(segment)
    }

    private fun timeLabel(segment: TranscriptSegment): String {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(segment.timestampMs))
        val star = if (segment.highlighted) "★ " else ""
        val speaker = segment.speaker
        return if (speaker.isNullOrBlank()) "$star$time" else "$star$time · $speaker"
    }

    private fun renderPartial(text: String) {
        if (text.isBlank()) {
            partialView.text = ""
            partialView.visibility = View.GONE
        } else {
            partialView.text = text
            partialView.visibility = View.VISIBLE
        }
    }

    private fun scrollToBottom() {
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // --- User actions -----------------------------------------------------

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
        if (index !in segments.indices) return
        SpeakerPicker.show(this, currentAttendees(), segments[index].speaker) { name ->
            svc.assignSpeaker(index, name)
            if (!name.isNullOrBlank()) {
                val attendees = currentAttendees()
                if (attendees.none { it.equals(name, ignoreCase = true) }) {
                    attendees.add(name)
                    attendeesInput.setText(attendees.joinToString(", "))
                }
            }
        }
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

    private fun applyCalendarEvent(
        event: CalendarHelper.CalendarEvent,
        overwriteTitle: Boolean
    ) {
        Thread {
            val eventAttendees = CalendarHelper.attendeesFor(this, event.eventId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (overwriteTitle || titleInput.text.toString() == initialDefaultTitle) {
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
            pauseButton.setIconResource(R.drawable.ic_play)
            pauseButton.contentDescription = getString(R.string.resume)
        } else {
            startPulse()
            pauseButton.setIconResource(R.drawable.ic_pause)
            pauseButton.contentDescription = getString(R.string.pause)
        }
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
        if (bound) {
            service?.clearObserver(this)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val PERMISSION_REQUEST = 4001
        private const val CALENDAR_REQUEST = 4002
        private const val NOTIF_REQUEST = 4003
    }
}
