package com.meetily.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.PhotoStore
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.whisper.CaptureTuning
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.MeetingAudioWriter
import com.meetily.mobile.whisper.VoiceProfile
import com.meetily.mobile.whisper.VoiceProfileStore
import com.meetily.mobile.whisper.SherpaEmbedder
import com.meetily.mobile.whisper.SpeakerClusterer
import com.meetily.mobile.whisper.WhisperModels
import com.meetily.mobile.whisper.WhisperRecorder
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * Owns the live recording session (audio capture + transcription + meeting
 * state) as a foreground service so it keeps running when the recording
 * screen is backgrounded or the phone is locked. The Activity binds to it
 * for UI and forwards user actions; all state lives here and is persisted
 * incrementally for crash recovery.
 */
class RecordingService : Service() {

    enum class Status { STARTING, LISTENING, HEARING, PROCESSING, PAUSED, ERROR, FINISHING, UNAVAILABLE }

    interface Observer {
        fun onSegmentAppended(index: Int, segment: TranscriptSegment)
        fun onSegmentUpdated(index: Int, segment: TranscriptSegment)
        fun onPartial(text: String)
        fun onStatus(status: Status, text: String)
        fun onFinished(meetingId: String)
    }

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var store: MeetingStore
    private lateinit var settings: AppSettings

    // --- Session state (main-thread only) --------------------------------
    var active = false
        private set
    var meetingId: String = ""
        private set
    private var startedAtMs = 0L
    private var accumulatedMs = 0L
    private var lastResumeAt = 0L
    var paused = false
        private set
    private var whisperMode = false
    private var finishing = false

    // Device-audio capture (webinars): AudioPlaybackCapture via MediaProjection.
    private var deviceAudioMode = false
    private var mediaProjection: android.media.projection.MediaProjection? = null

    private val segments = mutableListOf<TranscriptSegment>()
    private var pendingHighlight = false

    // Sticky speaker: new segments inherit this until it changes. Set from
    // the chip row, or by tagging the most recent segment. Volatile because
    // the Whisper audio thread snapshots it at chunk-cut time.
    @Volatile
    private var activeSpeaker: String? = null

    // Acoustic diarization (Whisper engine only, experimental).
    private var embedder: SherpaEmbedder? = null
    private var audioWriter: MeetingAudioWriter? = null
    private var audioFileName: String? = null
    private var voiceProfiles: List<VoiceProfile> = emptyList()
    /** Speaker-model key the live embedder was created from ("" = none). */
    private var diarizeModelKey: String = ""
    private var clusterer: SpeakerClusterer? = null
    private val clusterNames = mutableMapOf<Int, String>()

    // Metadata edited from the UI, mirrored here so it is saved incrementally.
    private var title = ""
    private var attendeesRaw = ""
    private var notes = ""
    private val photos = mutableListOf<String>()

    private var status = Status.STARTING
    private var statusText = ""
    private var partial = ""

    private var observer: Observer? = null

    // Engines
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val mutedStreams = mutableListOf<Int>()
    private var whisperRecorder: WhisperRecorder? = null

    private var saveScheduled = false

    override fun onCreate() {
        super.onCreate()
        store = MeetingStore(this)
        settings = AppSettings(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSessionIfNeeded(
                intent.getBooleanExtra(EXTRA_DEVICE_AUDIO, false)
            )
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_FINISH -> finishAndSave(null)
        }
        return START_STICKY
    }

    // --- Observer wiring --------------------------------------------------

    fun setObserver(o: Observer) {
        observer = o
    }

    fun clearObserver(o: Observer) {
        if (observer === o) observer = null
    }

    fun segmentsSnapshot(): List<TranscriptSegment> = segments.toList()
    fun currentStatus(): Status = status
    fun currentStatusText(): String = statusText
    fun currentPartial(): String = partial
    fun titleValue(): String = title
    fun attendeesValue(): String = attendeesRaw
    fun notesValue(): String = notes
    fun photosSnapshot(): List<String> = photos.toList()

    fun elapsedMs(): Long =
        accumulatedMs + if (paused) 0L else SystemClock.elapsedRealtime() - lastResumeAt

    // --- Session lifecycle ------------------------------------------------

    private fun startSessionIfNeeded(deviceAudio: Boolean = false) {
        if (active) return
        active = true
        deviceAudioMode = deviceAudio && Build.VERSION.SDK_INT >= 29
        isRunning = true
        finishing = false
        meetingId = UUID.randomUUID().toString()
        startedAtMs = System.currentTimeMillis()
        lastResumeAt = SystemClock.elapsedRealtime()
        accumulatedMs = 0L
        paused = false
        clusterNames.clear()
        audioWriter = null
        audioFileName = null
        title = getString(
            R.string.default_meeting_title,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(startedAtMs))
        )
        store.markActive(meetingId)
        startForegroundNotification()

        whisperMode = resolveWhisperMode()
        // Device audio rides the Whisper pipeline; without it, fall back to
        // the normal microphone path (the activity gates this upstream too).
        if (deviceAudioMode && !whisperMode) deviceAudioMode = false
        if (whisperMode) {
            startWhisper()
        } else if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus(Status.UNAVAILABLE, getString(R.string.recognition_unavailable))
        } else {
            startListening()
        }
    }

    private fun resolveWhisperMode(): Boolean {
        if (settings.transcriptionEngine != "whisper") return false
        // "whisper" engine setting covers all on-device models — whisper.cpp
        // ggml files and sherpa-onnx NeMo models alike.
        return com.meetily.mobile.whisper.TranscriptionModels
            .isReady(this, settings.whisperModel)
    }

    fun togglePause() {
        if (!active || finishing) return
        paused = !paused
        if (paused) {
            accumulatedMs += SystemClock.elapsedRealtime() - lastResumeAt
            if (whisperMode) {
                whisperRecorder?.pause()
            } else {
                main.removeCallbacks(restartRunnable)
                stopRecognizer()
                restoreSystemSounds()
            }
            setStatus(Status.PAUSED, getString(R.string.status_paused))
        } else {
            lastResumeAt = SystemClock.elapsedRealtime()
            if (whisperMode) {
                whisperRecorder?.resume()
                setStatus(Status.LISTENING, getString(R.string.status_listening_whisper))
            } else {
                startListening()
            }
        }
        updateNotification()
    }

    fun requestHighlight(): Boolean {
        // Returns true if a live segment was toggled, false if the highlight
        // was queued for the next segment.
        if (partial.isNotEmpty() || segments.isEmpty()) {
            pendingHighlight = true
            return false
        }
        val index = segments.size - 1
        val nowHi = !segments[index].highlighted
        segments[index] = segments[index].copy(highlighted = nowHi)
        observer?.onSegmentUpdated(index, segments[index])
        scheduleSave()
        return true
    }

    /**
     * [makeSticky] is decided by the caller at dialog-open time (was this the
     * latest segment?), not re-checked here — segments keep arriving while
     * the picker is open, which would make recency at callback time racy.
     */
    fun assignSpeaker(index: Int, name: String?, makeSticky: Boolean) {
        if (index !in segments.indices) return
        segments[index] = segments[index].copy(speaker = name)
        if (makeSticky) {
            activeSpeaker = name?.takeIf { it.isNotBlank() }
        }
        observer?.onSegmentUpdated(index, segments[index])
        scheduleSave()
    }

    fun setActiveSpeaker(name: String?) {
        activeSpeaker = name?.takeIf { it.isNotBlank() }
    }

    fun activeSpeakerValue(): String? = activeSpeaker

    /**
     * Rename-once: names a diarization cluster, retroactively labeling every
     * segment in it (that wasn't manually tagged differently) and all future
     * segments the clusterer assigns to it.
     */
    fun renameCluster(clusterId: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        applyClusterName(clusterId, trimmed)
        scheduleSave()
    }

    /** Names a cluster and retroactively relabels its untagged segments. */
    private fun applyClusterName(clusterId: Int, name: String) {
        val old = clusterNames[clusterId]
        if (old == name) return
        clusterNames[clusterId] = name
        for (i in segments.indices) {
            val s = segments[i]
            if (s.clusterId == clusterId &&
                (s.speaker.isNullOrBlank() || s.speaker == old)
            ) {
                segments[i] = s.copy(speaker = name)
                observer?.onSegmentUpdated(i, segments[i])
            }
        }
    }

    /**
     * Saves the cluster's voice as [name]'s profile so future meetings label
     * this speaker automatically. Returns false if the cluster has no usable
     * centroid.
     */
    fun saveVoiceProfileFromCluster(clusterId: Int, name: String): Boolean {
        val centroid = clusterer?.centroidOf(clusterId) ?: return false
        // No clean per-speaker audio exists for a live cluster, so this
        // profile has no banked audio; tagging lines afterwards (which
        // extracts exact windows from the saved recording) adds some.
        val saved = VoiceProfileStore.addSample(this, name, centroid, diarizeModelKey)
        if (saved) voiceProfiles = VoiceProfileStore.load(this)
        return saved
    }

    /** Fuses clusters the online pass kept apart; run once, at finish. */
    private fun applyClusterMerge() {
        val remap = clusterer?.mergePass() ?: return
        if (remap.isEmpty()) return
        for ((from, to) in remap) {
            clusterNames.remove(from)?.let { name ->
                clusterNames.putIfAbsent(to, name)
            }
        }
        for (i in segments.indices) {
            val s = segments[i]
            val to = s.clusterId?.let { remap[it] } ?: continue
            var updated = s.copy(clusterId = to)
            if (updated.speaker.isNullOrBlank()) {
                clusterNames[to]?.let { updated = updated.copy(speaker = it) }
            }
            segments[i] = updated
            observer?.onSegmentUpdated(i, segments[i])
        }
    }

    fun toggleHighlightAt(index: Int) {
        if (index !in segments.indices) return
        segments[index] = segments[index].copy(highlighted = !segments[index].highlighted)
        observer?.onSegmentUpdated(index, segments[index])
        scheduleSave()
    }

    fun updateTitle(value: String) {
        title = value
        scheduleSave()
    }

    fun updateAttendees(value: String) {
        attendeesRaw = value
        // Reconcile the sticky speaker: if their name was renamed or removed,
        // clear it — otherwise segments keep getting silently tagged with a
        // name that no chip displays as active.
        val current = activeSpeaker
        if (current != null &&
            Meeting.parseAttendees(value).none { it.equals(current, ignoreCase = true) }
        ) {
            activeSpeaker = null
        }
        scheduleSave()
    }

    fun updateNotes(value: String) {
        notes = value
        scheduleSave()
    }

    fun addPhoto(name: String) {
        photos.add(name)
        scheduleSave()
    }

    /** Finishes, saving the meeting. onDone (if bound) receives the id. */
    fun finishAndSave(onDone: ((String) -> Unit)?) {
        if (!active || finishing) {
            if (active) onDone?.invoke(meetingId)
            return
        }
        finishing = true
        val recorder = whisperRecorder
        if (recorder != null && !paused) {
            setStatus(Status.FINISHING, getString(R.string.status_finishing))
            recorder.finish {
                main.post { completeFinish(onDone) }
            }
            main.postDelayed({ completeFinish(onDone) }, 15_000)
        } else {
            completeFinish(onDone)
        }
    }

    private var finished = false
    private fun completeFinish(onDone: ((String) -> Unit)?) {
        if (finished) return
        finished = true
        // All chunks are transcribed by now (finish() barriers on the
        // transcriber queue), so the merge sees the complete session.
        applyClusterMerge()
        teardownEngines()
        saveNow()
        store.clearActive()
        active = false
        isRunning = false
        val id = meetingId
        observer?.onFinished(id)
        onDone?.invoke(id)
        stopForegroundCompat()
        stopSelf()
    }

    /** Discards the in-progress recording and its media entirely. */
    fun discard() {
        finishing = true
        finished = true
        teardownEngines()
        main.removeCallbacks(saveRunnable)
        store.clearActive()
        store.delete(meetingId)
        for (name in photos) {
            PhotoStore.delete(this, name)
        }
        AudioStore.delete(this, audioFileName)
        audioFileName = null
        active = false
        isRunning = false
        stopForegroundCompat()
        stopSelf()
    }

    // --- Recognizer path (ported from RecordingActivity) ------------------

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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun muteSystemSounds() {
        if (!settings.muteRecognizerSounds || mutedStreams.isNotEmpty()) return
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        for (stream in intArrayOf(
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_MUSIC
        )) {
            try {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                mutedStreams.add(stream)
            } catch (_: Exception) {
            }
        }
    }

    private fun restoreSystemSounds() {
        if (mutedStreams.isEmpty()) return
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            for (stream in mutedStreams) {
                try {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {
                }
            }
        }
        mutedStreams.clear()
    }

    private val restartRunnable = Runnable { startListening() }

    private fun startListening() {
        if (!active || paused || finishing) return
        muteSystemSounds()
        try {
            recognizer?.destroy()
            recognizer = createRecognizer().apply {
                setRecognitionListener(recognitionListener)
                startListening(recognizerIntent())
            }
            listening = true
            setStatus(Status.LISTENING, getString(R.string.status_listening))
        } catch (e: Exception) {
            setStatus(Status.ERROR, getString(R.string.status_error, e.message ?: "unknown"))
            scheduleRestart(1500)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!active || paused || finishing) return
        main.postDelayed(restartRunnable, delayMs)
    }

    private fun stopRecognizer() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        listening = false
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            setStatus(Status.LISTENING, getString(R.string.status_listening))
        }

        override fun onBeginningOfSpeech() {
            setStatus(Status.HEARING, getString(R.string.status_hearing_speech))
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            setStatus(Status.PROCESSING, getString(R.string.status_processing))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) setPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) appendSegment(text)
            setPartial("")
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

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    setStatus(Status.ERROR, getString(R.string.mic_permission_denied))

                else -> {
                    setStatus(Status.ERROR, getString(R.string.status_error, "code $error"))
                    scheduleRestart(1200)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // --- Whisper path -----------------------------------------------------

    private fun startWhisper() {
        if (whisperRecorder != null) return
        val model = WhisperModels.byKey(settings.whisperModel)
        // NeMo path (Parakeet/Nemotron): loaded up front; null means "treat
        // as whisper" so a broken download degrades to the default model.
        val nemoModel = com.meetily.mobile.whisper.NemoModels
            .byKeyOrNull(settings.whisperModel)
        val nemoEngine = nemoModel?.let {
            com.meetily.mobile.whisper.NemoEngine.create(
                this, it, Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            )
        }
        val englishOnly = nemoModel?.englishOnly ?: model.englishOnly

        // Optional acoustic diarization: only when enabled, the model is
        // downloaded, and the native stack loads. Failure of any piece
        // degrades silently to plain transcription.
        var labeler: ((FloatArray) -> Int?)? = null
        if (settings.diarizationEnabled) {
            val dModel = DiarizationModels.byKey(settings.diarizationModel)
            if (DiarizationModels.isDownloaded(this, dModel)) {
                val created = SherpaEmbedder.create(
                    DiarizationModels.fileFor(this, dModel).absolutePath
                )
                if (created != null) {
                    embedder = created
                    diarizeModelKey = dModel.key
                    val c = SpeakerClusterer()
                    clusterer = c
                    voiceProfiles = VoiceProfileStore.load(this)
                    // Voiceprint rollover runs on the labeler's first call:
                    // that's the transcription worker thread (the embedder's
                    // normal home, so no lifecycle races) rather than the
                    // service start path, and its main.post lands before the
                    // first segment posts — so even the opening sentence is
                    // matched against the converted profiles.
                    val rolled = java.util.concurrent.atomic.AtomicBoolean(false)
                    labeler = { audio ->
                        if (rolled.compareAndSet(false, true)) {
                            val updated = VoiceProfileStore.reembedForModel(
                                this, dModel.key
                            ) { created.embed(it) }
                            main.post { if (active) voiceProfiles = updated }
                        }
                        c.assign(
                            if (audio.size >= MIN_EMBED_SAMPLES) created.embed(audio) else null
                        )
                    }
                }
            }
        }

        if (settings.saveAudio && audioWriter == null) {
            try {
                val file = AudioStore.newRecordingFile(this, meetingId)
                audioWriter = MeetingAudioWriter(file)
                audioFileName = file.name
            } catch (_: Throwable) {
                audioWriter = null
                audioFileName = null
            }
        }
        val writer = audioWriter

        whisperRecorder = WhisperRecorder(
            modelPath = if (nemoEngine != null) "" else {
                WhisperModels.fileFor(this, model).absolutePath
            },
            language = if (englishOnly) "en" else "auto",
            // Translate + vocab prompts are whisper features; NeMo models
            // transcribe in the spoken language and ignore both.
            translate = nemoEngine == null &&
                settings.whisperTranslate && !englishOnly,
            vocabPrompt = if (nemoEngine != null) null else {
                com.meetily.mobile.whisper.Vocab.promptFor(settings.customVocab)
            },
            nemoEngine = nemoEngine,
            audioSource = CaptureTuning.audioSourceFor(this, settings.micSource),
            preferredDevice = CaptureTuning.findPreferred(this, settings.micDevice),
            recordFactory = if (deviceAudioMode && Build.VERSION.SDK_INT >= 29) {
                { deviceAudioRecord() }
            } else null,
            speakerSupplier = { activeSpeaker },
            chunkLabeler = labeler,
            frameSink = if (writer != null) {
                { frame -> writer.write(frame) }
            } else null,
            onSegment = { text, speaker, clusterId, audioMs, words ->
                main.post {
                    if (active) appendSegment(text, speaker, clusterId, audioMs, words)
                }
            },
            onProcessingChange = { processing ->
                main.post {
                    if (active && !finishing && !paused) {
                        setStatus(
                            if (processing) Status.PROCESSING else Status.LISTENING,
                            getString(
                                if (processing) R.string.status_processing
                                else R.string.status_listening_whisper
                            )
                        )
                    }
                }
            },
            onError = { message ->
                main.post { if (active && !finishing) setStatus(Status.ERROR, message) }
            }
        ).also { it.start() }
        setStatus(Status.LISTENING, getString(R.string.status_listening_whisper))
    }

    // --- Shared segment handling -----------------------------------------

    private fun appendSegment(
        text: String,
        speaker: String? = activeSpeaker,
        clusterId: Int? = null,
        audioMs: Long? = null,
        words: List<com.meetily.mobile.data.WordStamp> = emptyList()
    ) {
        // Unknown cluster: see if its voice matches a saved profile — this is
        // how known people get named from their first sentence.
        if (clusterId != null && !clusterNames.containsKey(clusterId) &&
            voiceProfiles.isNotEmpty()
        ) {
            VoiceProfileStore.match(
                voiceProfiles, clusterer?.centroidOf(clusterId),
                model = diarizeModelKey
            )?.let { name -> applyClusterName(clusterId, name) }
        }
        // Precedence: manual/sticky speaker > named cluster > anonymous cluster.
        val resolved = speaker ?: clusterId?.let { clusterNames[it] }
        val segment = TranscriptSegment(
            timestampMs = System.currentTimeMillis(),
            text = text,
            speaker = resolved,
            highlighted = pendingHighlight,
            clusterId = clusterId,
            audioMs = if (audioFileName != null) audioMs else null,
            // Word timings only matter when the audio is kept for playback.
            words = if (audioFileName != null && words.isNotEmpty()) words else null
        )
        pendingHighlight = false
        segments.add(segment)
        observer?.onSegmentAppended(segments.size - 1, segment)
        scheduleSave()
    }

    private fun setPartial(text: String) {
        partial = text
        observer?.onPartial(text)
    }

    private fun setStatus(s: Status, text: String) {
        status = s
        statusText = text
        observer?.onStatus(s, text)
        updateNotification()
    }

    private fun teardownEngines() {
        main.removeCallbacks(restartRunnable)
        stopRecognizer()
        restoreSystemSounds()
        whisperRecorder?.destroy()
        whisperRecorder = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        embedder?.release()
        embedder = null
        clusterer = null
        // Finalize the audio file off the main thread; ADTS stays playable
        // regardless, and the player UI checks the file, not this flag.
        audioWriter?.let { writer ->
            Thread { writer.finish() }.start()
        }
        audioWriter = null
    }

    // --- Incremental persistence -----------------------------------------

    private val saveRunnable = Runnable {
        saveScheduled = false
        saveNow()
    }

    private fun scheduleSave() {
        if (saveScheduled) return
        saveScheduled = true
        main.postDelayed(saveRunnable, 2500)
    }

    private fun saveNow() {
        main.removeCallbacks(saveRunnable)
        saveScheduled = false
        store.save(buildMeeting())
    }

    private fun buildMeeting(): Meeting = Meeting(
        id = meetingId,
        title = title.ifBlank {
            getString(
                R.string.default_meeting_title,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(startedAtMs))
            )
        },
        createdAtMs = startedAtMs,
        segments = segments.toMutableList(),
        notes = notes,
        attendees = Meeting.parseAttendees(attendeesRaw),
        photos = photos.toMutableList(),
        audioFile = audioFileName
    )

    // --- Foreground notification -----------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.rec_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (deviceAudioMode) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * AudioRecord fed by other apps' playback (AudioPlaybackCapture). Runs on
     * the recorder's audio thread, after the mediaProjection-typed foreground
     * start — the ordering Android 14 enforces. VoIP apps that flag their
     * audio as voice-communication are excluded by the OS; media/webinar
     * playback is capturable.
     */
    @androidx.annotation.RequiresApi(29)
    private fun deviceAudioRecord(): android.media.AudioRecord? {
        val data = pendingProjectionData ?: return null
        val code = pendingProjectionCode
        pendingProjectionData = null
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(code, data)
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            projection.registerCallback(
                object : android.media.projection.MediaProjection.Callback() {}, main
            )
        } catch (_: Exception) {
        }
        mediaProjection = projection
        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()
        return try {
            android.media.AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(16_000)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(16_000 * 4 * 2)
                .build()
        } catch (_: Exception) {
            projection.stop()
            mediaProjection = null
            null
        }
    }

    private fun updateNotification() {
        if (!active) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, RecordingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            pendingFlags()
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, RecordingService::class.java).setAction(ACTION_FINISH),
            pendingFlags()
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                getString(if (paused) R.string.resume else R.string.pause),
                pauseIntent
            )
            .addAction(R.drawable.ic_check, getString(R.string.finish_save), stopIntent)

        if (paused) {
            builder.setContentText(getString(R.string.status_paused))
            builder.setUsesChronometer(false)
        } else {
            builder.setContentText(getString(R.string.notif_recording))
            builder.setWhen(System.currentTimeMillis() - elapsedMs())
            builder.setUsesChronometer(true)
        }
        return builder.build()
    }

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        // Safety net: if the process is going away with an active session,
        // persist what we have. The .active marker stays so the next launch
        // recovers it.
        if (active && !finished) {
            teardownEngines()
            saveNow()
        }
        isRunning = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.meetily.mobile.action.START"
        const val ACTION_TOGGLE_PAUSE = "com.meetily.mobile.action.TOGGLE_PAUSE"
        const val ACTION_FINISH = "com.meetily.mobile.action.FINISH"
        const val EXTRA_DEVICE_AUDIO = "device_audio"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001
        // ~1.5 s of 16 kHz audio: shorter chunks embed unreliably.
        private const val MIN_EMBED_SAMPLES = 24_000

        /** True while a recording session is live in this process. */
        @Volatile
        var isRunning = false
            set(value) {
                field = value
                // Timestamp the transition so the calendar nudge can tell
                // "recording THIS meeting" from "still recording the previous
                // one" — back-to-back meetings must still be nudged.
                runningSinceMs = if (value) System.currentTimeMillis() else null
            }

        /** When the current recording started, or null when idle. */
        @Volatile
        var runningSinceMs: Long? = null
            private set

        fun start(context: Context, deviceAudio: Boolean = false) {
            isRunning = true
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE_AUDIO, deviceAudio)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * MediaProjection consent handoff (RecordingActivity → service). An
         * activity-result Intent isn't parcel-safe across a service start's
         * extras on all OEMs, so it rides here and is consumed exactly once.
         */
        @Volatile var pendingProjectionData: Intent? = null

        @Volatile var pendingProjectionCode: Int = 0
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away mid-recording, save and stop cleanly.
        if (active && !finished) {
            teardownEngines()
            saveNow()
            store.clearActive()
            active = false
        }
        isRunning = false
        stopForegroundCompat()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
