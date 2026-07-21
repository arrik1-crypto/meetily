package com.meetily.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.TranscriptSegment
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class RecordingActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var settings: AppSettings

    private lateinit var titleInput: EditText
    private lateinit var statusView: TextView
    private lateinit var partialView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var notesInput: EditText
    private lateinit var pauseButton: Button
    private lateinit var finishButton: Button

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var paused = false
    private var destroyed = false

    private val segments = mutableListOf<TranscriptSegment>()
    private val handler = Handler(Looper.getMainLooper())
    private val meetingId = UUID.randomUUID().toString()
    private val startedAtMs = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = MeetingStore(this)
        settings = AppSettings(this)

        titleInput = findViewById(R.id.titleInput)
        statusView = findViewById(R.id.statusView)
        partialView = findViewById(R.id.partialView)
        transcriptView = findViewById(R.id.transcriptView)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        notesInput = findViewById(R.id.notesInput)
        pauseButton = findViewById(R.id.pauseButton)
        finishButton = findViewById(R.id.finishButton)

        val defaultTitle = getString(
            R.string.default_meeting_title,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(startedAtMs))
        )
        titleInput.setText(defaultTitle)

        pauseButton.setOnClickListener { togglePause() }
        finishButton.setOnClickListener { finishAndSave() }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = getString(R.string.recognition_unavailable)
            Toast.makeText(this, R.string.recognition_unavailable, Toast.LENGTH_LONG).show()
        } else {
            ensurePermissionAndStart()
        }
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

    private fun startListening() {
        if (destroyed || paused) return
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
            }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = texts?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                appendSegment(text)
            }
            partialView.text = ""
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
        segments.add(TranscriptSegment(System.currentTimeMillis(), text))
        val current = transcriptView.text.toString()
        transcriptView.text = if (current.isBlank()) text else "$current\n$text"
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            handler.removeCallbacksAndMessages(null)
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
            listening = false
            pauseButton.text = getString(R.string.resume)
            statusView.text = getString(R.string.status_paused)
        } else {
            pauseButton.text = getString(R.string.pause)
            startListening()
        }
    }

    private fun finishAndSave() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null

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
            notes = notesInput.text.toString()
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
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        super.onDestroy()
    }

    companion object {
        private const val PERMISSION_REQUEST = 4001
    }
}
