package com.meetily.mobile

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.TranscriptSegment
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RecordingActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
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
    private lateinit var finishButton: MaterialButton

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var paused = false
    private var destroyed = false
    private val mutedStreams = mutableListOf<Int>()

    private val segments = mutableListOf<TranscriptSegment>()
    private val bubbleViews = mutableListOf<View>()
    private var pendingHighlight = false
    private val handler = Handler(Looper.getMainLooper())
    private val timerHandler = Handler(Looper.getMainLooper())
    private val meetingId = UUID.randomUUID().toString()
    private val startedAtMs = System.currentTimeMillis()

    // Elapsed recording time, excluding paused stretches.
    private var accumulatedMs = 0L
    private var lastResumeAt = 0L
    private var pulseAnimator: ObjectAnimator? = null

    private val timerTick = object : Runnable {
        override fun run() {
            elapsedView.text = formatElapsed(currentElapsedMs())
            timerHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = MeetingStore(this)
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
        finishButton = findViewById(R.id.finishButton)

        findViewById<MaterialToolbar>(R.id.recordingToolbar).setNavigationOnClickListener {
            confirmDiscard()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmDiscard()
            }
        })

        val defaultTitle = getString(
            R.string.default_meeting_title,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(startedAtMs))
        )
        titleInput.setText(defaultTitle)

        pauseButton.setOnClickListener { togglePause() }
        highlightButton.setOnClickListener { highlightNow() }
        finishButton.setOnClickListener { finishAndSave() }

        lastResumeAt = SystemClock.elapsedRealtime()
        timerHandler.post(timerTick)
        startPulse()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = getString(R.string.recognition_unavailable)
            Toast.makeText(this, R.string.recognition_unavailable, Toast.LENGTH_LONG).show()
        } else {
            ensurePermissionAndStart()
        }
    }

    private fun currentElapsedMs(): Long =
        accumulatedMs + if (paused) 0L else SystemClock.elapsedRealtime() - lastResumeAt

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

    private fun startPulse() {
        pulseAnimator?.cancel()
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

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.keep_recording, null)
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .show()
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                statusView.text = getString(R.string.mic_permission_denied)
                Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val preferOffline = settings.preferOfflineRecognition
        return if (preferOffline &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    /**
     * The system speech recognizer plays a chime every time listening starts
     * and stops, which becomes a constant beeping with continuous recognition.
     * There is no official API to disable it, so mute the streams it plays on
     * for the duration of the recording session and restore them afterwards.
     */
    private fun muteSystemSounds() {
        if (!settings.muteRecognizerSounds || mutedStreams.isNotEmpty()) return
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        for (stream in intArrayOf(
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_MUSIC
        )) {
            try {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                mutedStreams.add(stream)
            } catch (_: Exception) {
                // Some devices/DND modes forbid volume changes; skip that stream.
            }
        }
    }

    private fun restoreSystemSounds() {
        if (mutedStreams.isEmpty()) return
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            for (stream in mutedStreams) {
                try {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {
                }
            }
        }
        mutedStreams.clear()
    }

    private fun startListening() {
        if (destroyed || paused) return
        muteSystemSounds()
        try {
            recognizer?.destroy()
            recognizer = createRecognizer().apply {
                setRecognitionListener(listener)
                startListening(recognizerIntent())
            }
            listening = true
            statusView.text = getString(R.string.status_listening)
        } catch (e: Exception) {
            statusView.text = getString(R.string.status_error, e.message ?: "unknown")
            scheduleRestart(1500)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (destroyed || paused) return
        handler.postDelayed({ startListening() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            statusView.text = getString(R.string.status_listening)
        }

        override fun onBeginningOfSpeech() {
            statusView.text = getString(R.string.status_hearing_speech)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            statusView.text = getString(R.string.status_processing)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = texts?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                partialView.text = text
                partialView.visibility = View.VISIBLE
                scrollTranscriptToBottom()
            }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = texts?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                appendSegment(text)
            }
            partialView.text = ""
            partialView.visibility = View.GONE
            listening = false
            scheduleRestart(150)
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(150)

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.destroy()
                    recognizer = null
                    scheduleRestart(700)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    statusView.text = getString(R.string.mic_permission_denied)
                }

                else -> {
                    statusView.text = getString(R.string.status_error, "code $error")
                    scheduleRestart(1200)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun appendSegment(text: String) {
        val timestamp = System.currentTimeMillis()
        segments.add(TranscriptSegment(timestamp, text, highlighted = pendingHighlight))
        pendingHighlight = false
        val index = segments.size - 1

        val bubble = LayoutInflater.from(this)
            .inflate(R.layout.item_transcript_segment, transcriptContainer, false)
        bubble.findViewById<TextView>(R.id.segmentText).text = text
        bubble.setOnClickListener { assignSpeaker(index) }
        bubbleViews.add(bubble)

        val partialIndex = transcriptContainer.indexOfChild(partialView)
        transcriptContainer.addView(bubble, if (partialIndex >= 0) partialIndex else -1)
        refreshBubble(index)
        scrollTranscriptToBottom()
    }

    private fun timeLabel(segment: TranscriptSegment): String {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(segment.timestampMs))
        val star = if (segment.highlighted) "★ " else ""
        val speaker = segment.speaker
        return if (speaker.isNullOrBlank()) "$star$time" else "$star$time · $speaker"
    }

    private fun refreshBubble(index: Int) {
        val bubble = bubbleViews.getOrNull(index) ?: return
        val segment = segments.getOrNull(index) ?: return
        bubble.setBackgroundResource(
            if (segment.highlighted) R.drawable.bg_bubble_highlight else R.drawable.bg_bubble
        )
        bubble.findViewById<TextView>(R.id.segmentTime).text = timeLabel(segment)
    }

    private fun highlightNow() {
        val partialActive = partialView.visibility == View.VISIBLE
        if (partialActive || segments.isEmpty()) {
            // Speech is mid-utterance (or nothing transcribed yet): flag the
            // segment that is about to arrive.
            pendingHighlight = true
            Toast.makeText(this, R.string.highlight_pending_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val index = segments.size - 1
        val nowHighlighted = !segments[index].highlighted
        segments[index] = segments[index].copy(highlighted = nowHighlighted)
        refreshBubble(index)
        Toast.makeText(
            this,
            if (nowHighlighted) R.string.highlighted_toast else R.string.unhighlighted_toast,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun currentAttendees(): MutableList<String> =
        Meeting.parseAttendees(attendeesInput.text.toString())

    private fun assignSpeaker(index: Int) {
        if (index !in segments.indices) return
        val segment = segments[index]
        SpeakerPicker.show(this, currentAttendees(), segment.speaker) { name ->
            if (index !in segments.indices) return@show
            segments[index] = segments[index].copy(speaker = name)
            refreshBubble(index)
            if (!name.isNullOrBlank()) {
                val attendees = currentAttendees()
                if (attendees.none { it.equals(name, ignoreCase = true) }) {
                    attendees.add(name)
                    attendeesInput.setText(attendees.joinToString(", "))
                }
            }
        }
    }

    private fun scrollTranscriptToBottom() {
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            accumulatedMs += SystemClock.elapsedRealtime() - lastResumeAt
            handler.removeCallbacksAndMessages(null)
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
            listening = false
            restoreSystemSounds()
            stopPulse()
            pauseButton.setIconResource(R.drawable.ic_play)
            pauseButton.contentDescription = getString(R.string.resume)
            statusView.text = getString(R.string.status_paused)
        } else {
            lastResumeAt = SystemClock.elapsedRealtime()
            startPulse()
            pauseButton.setIconResource(R.drawable.ic_pause)
            pauseButton.contentDescription = getString(R.string.pause)
            startListening()
        }
    }

    private fun finishAndSave() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        timerHandler.removeCallbacksAndMessages(null)
        stopPulse()
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        restoreSystemSounds()

        val title = titleInput.text.toString().ifBlank {
            getString(
                R.string.default_meeting_title,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(startedAtMs))
            )
        }
        val meeting = Meeting(
            id = meetingId,
            title = title,
            createdAtMs = startedAtMs,
            segments = segments,
            notes = notesInput.text.toString(),
            attendees = currentAttendees()
        )
        store.save(meeting)

        startActivity(
            Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
        )
        finish()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        timerHandler.removeCallbacksAndMessages(null)
        stopPulse()
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        restoreSystemSounds()
        super.onDestroy()
    }

    companion object {
        private const val PERMISSION_REQUEST = 4001
    }
}
