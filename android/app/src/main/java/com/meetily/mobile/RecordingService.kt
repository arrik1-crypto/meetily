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
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.ElapsedTime
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

        /**
         * Microphone level, roughly ten times a second, ON THE AUDIO THREAD.
         * Implementations must post to the main thread before touching views.
         * Default no-op: only the recording screen cares.
         */
        fun onLevel(rms: Float) {}

        /**
         * Which capture the session actually runs: true when it records audio
         * with no live words (the transcript is made from the file after the
         * meeting). Sent once the session has resolved its engine.
         */
        fun onCaptureMode(audioOnly: Boolean) {}
    }

    /** What a tap on Highlight did. */
    enum class HighlightResult { TOGGLED, QUEUED, MARKED }

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

    /**
     * The on-device session transcribes as it records. False in whisper mode
     * means capture only: the audio is kept and transcribed afterwards.
     */
    private var liveWhisper = false
    private var finishing = false

    /**
     * The live transcript is known to be missing words (the transcriber fell
     * behind, or its backlog was abandoned at finish), so the saved audio has
     * to be transcribed after the meeting.
     */
    private var liveIncomplete = false

    // Device-audio capture (webinars): AudioPlaybackCapture via MediaProjection.
    private var deviceAudioMode = false

    /**
     * Android ended this session's device-audio projection: the status bar
     * chip, another app's projection, or — from Android 15 QPR1 — simply the
     * screen locking (see DeviceAudioLock). Only a fresh consent could bring
     * it back, so the session stops its clock and waits for Finish.
     */
    var deviceAudioEnded = false
        private set

    // Written on the audio thread (deviceAudioRecord), stopped on main.
    @Volatile
    private var mediaProjection: android.media.projection.MediaProjection? = null

    private val segments = mutableListOf<TranscriptSegment>()

    /** A star tapped before there was a line to put it on; see [QueuedHighlight]. */
    private var pendingHighlight: QueuedHighlight? = null

    /**
     * Highlight taps in an audio-only session, as offsets into the captured
     * audio. There is no line to star yet; the first transcript stars the
     * line each one falls in (see HighlightMarks).
     */
    private val highlightMarks = mutableListOf<Long>()

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

    /** The running on-device session's [LateChunks], sealed by completeFinish. */
    private var lateChunks: LateChunks? = null

    // Metadata edited from the UI, mirrored here so it is saved incrementally.
    private var title = ""
    private var attendeesRaw = ""
    private var notes = ""
    private val photos = mutableListOf<String>()

    // Which metadata changed through this service since the last snapshot.
    // Only those overwrite the stored copy; see MeetingStore.saveRecordingSnapshot.
    private var titleDirty = false
    private var notesDirty = false
    private var attendeesDirty = false

    private var status = Status.STARTING
    private var statusText = ""
    private var partial = ""

    private var observer: Observer? = null

    // Engines
    private var recognizer: SpeechRecognizer? = null
    private val mutedStreams = mutableListOf<Int>()

    /** Ringer mode when ring-aliased streams were muted; -1 when none were. */
    private var savedRingerMode = -1
    private var whisperRecorder: WhisperRecorder? = null

    /**
     * The live model is still loading on the recorder's transcriber, while
     * capture already runs: the status says so rather than "Processing".
     */
    private var liveLoading = false

    /**
     * The microphone capture stopped for good on its own (WhisperRecorder's
     * onCaptureEnded): the session is held as a pause holds it, clock
     * stopped, and only Finish is left. The device-audio equivalent is
     * [deviceAudioEnded].
     */
    var captureEnded = false
        private set

    private var saveScheduled = false

    override fun onCreate() {
        super.onCreate()
        store = MeetingStore(this)
        settings = AppSettings(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The newest start this instance has been handed, whatever its action.
     * The service stops with it ([stopForNewestStart]), never with a bare
     * stopSelf(): that is stopSelf(-1), which also cancels a start already
     * on its way. A finished session stops the service only once its work
     * has been issued (WorkThenStop), and a new session can be starting on
     * this instance by then. Its start was cancelled under it, so it ran on
     * a service that was only bound: a swipe from Recents no longer reached
     * onTaskRemoved, and the screen's next unbind brought the service down
     * mid-meeting.
     */
    private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> startSessionIfNeeded(
                intent.getBooleanExtra(EXTRA_DEVICE_AUDIO, false),
                intent.getStringExtra(EXTRA_INITIAL_TITLE).orEmpty()
            )
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_HIGHLIGHT -> markFromNotification()
            ACTION_FINISH -> finishAndSave(null)
            // Swiped away: not posted as a Live Update again this session
            // (LiveUpdates). Nothing is reposted now; the next change of
            // state posts the ordinary notification.
            ACTION_LIVE_DISMISSED -> if (active) liveUpdateDismissed = true
            else -> return START_STICKY
        }
        if (!active) {
            // A notification action that raced the end of a session can be
            // what created this service, or reach it after a stop that it
            // kept from taking effect (see [stopForNewestStart]). With
            // nothing recording it must not stay started, or be restarted,
            // holding nothing. A finish still issuing its work stops the
            // service when that is done, and a session whose start is on
            // its way (start() has set isRunning) takes the service over.
            if (pendingStops == 0 && !isRunning) stopForNewestStart()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * Stops the service for [lastStartId] and takes it out of the
     * foreground only if that stopped it. A newer start still on its way
     * keeps the service started and in the foreground: a session start
     * takes the foreground over, and any other start, finding nothing
     * recording, comes back here itself from onStartCommand. Main thread.
     */
    private fun stopForNewestStart() {
        if (stopSelfResult(lastStartId)) stopForegroundCompat()
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

    /**
     * True while the session records audio with no live words. The recording
     * screen follows this rather than the settings, which say what was asked
     * for, not what the session could actually run.
     */
    fun capturesAudioOnly(): Boolean = active && whisperMode && !liveWhisper

    /**
     * Wall clock at which this session started — the same value the saved
     * meeting carries as createdAtMs. The live transcript needs it to label
     * lines by their offset into the recording when audio is not being kept
     * and the lines therefore have no audioMs of their own.
     */
    fun startedAt(): Long = startedAtMs

    /**
     * Zero until the session is actually live. start() uses
     * startForegroundService, so onStartCommand — where lastResumeAt is
     * assigned — is a queued main-thread message that runs after the caller
     * has returned and posted its first timer tick. Without the guard that
     * tick subtracted zero from elapsedRealtime and rendered time since boot:
     * a phone up for six days showed "142:07:33" for half a second, and much
     * longer on the device-audio path, where start() waits on the consent
     * dialog.
     */
    fun elapsedMs(): Long {
        if (lastResumeAt == 0L) return accumulatedMs
        return accumulatedMs + if (paused) 0L else SystemClock.elapsedRealtime() - lastResumeAt
    }

    // --- Session lifecycle ------------------------------------------------

    private fun startSessionIfNeeded(
        deviceAudio: Boolean = false,
        initialTitle: String = ""
    ) {
        if (active) return
        active = true
        deviceAudioMode = deviceAudio && Build.VERSION.SDK_INT >= 29
        isRunning = true
        // An idle on-device LLM left over from a summary or an Ask would sit
        // in memory beside live Whisper for the whole meeting. Never blocks:
        // a generation in flight frees it when it ends.
        com.meetily.mobile.llm.LocalLlm.release()
        finishing = false
        // Everything that belongs to one session starts empty. A screen still
        // bound to this service keeps the same instance alive after a session
        // ends, and the next one started on it inherited the last meeting's
        // lines and photos, a closed save executor that refused every
        // snapshot, and finished = true, so Finish never completed and the
        // safety saves were skipped: the whole meeting was lost.
        finished = false
        // A snapshot still pending when the last session was discarded left
        // this set, and scheduleSave() then wrote nothing all session. The
        // dirty flags stay: text typed before the session began needs them.
        main.removeCallbacks(saveRunnable)
        saveScheduled = false
        segments.clear()
        photos.clear()
        pendingHighlight = null
        partial = ""
        captureEnded = false
        liveLoading = false
        if (saveExecutor.isShutdown) saveExecutor = newSaveExecutor()
        meetingId = UUID.randomUUID().toString()
        startedAtMs = System.currentTimeMillis()
        lastResumeAt = SystemClock.elapsedRealtime()
        accumulatedMs = 0L
        paused = false
        clusterNames.clear()
        highlightMarks.clear()
        liveIncomplete = false
        silentSinceMs = 0L
        micWarned = false
        deviceAudioEnded = false
        markConfirmation = null
        liveUpdateDismissed = false
        main.removeCallbacks(clearMarkConfirmation)
        // The notification line reads it (an ERROR is not "recording device
        // audio"), so a previous session's must not carry over.
        status = Status.STARTING
        audioWriter = null
        audioFileName = null
        // The title has to arrive on THIS Intent rather than be pushed
        // afterwards. start() uses startForegroundService, so onStartCommand
        // is a queued main-thread message that runs after the caller has
        // finished — anything set through updateTitle() in the meantime is
        // overwritten by the assignment below.
        title = initialTitle.ifBlank {
            getString(
                R.string.default_meeting_title,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(startedAtMs))
            )
        }
        store.markActive(meetingId)
        startForegroundNotification()
        // Sounds an earlier session left muted go back first, in the
        // foreground where Android 17 honours it, before this one mutes any.
        SystemSoundsGuard.restoreLeftover(this, duringRecording = true)

        whisperMode = settings.transcriptionEngine == "whisper"
        liveWhisper = whisperMode && !plannedAudioOnly(this, settings)
        // Device audio rides the Whisper pipeline; without it, fall back to
        // the normal microphone path (the activity gates this upstream too).
        if (deviceAudioMode && !whisperMode) deviceAudioMode = false
        observer?.onCaptureMode(capturesAudioOnly())
        // The first snapshot, so the meeting (and whatever was typed before
        // the session existed) is on disk without waiting for a first line —
        // which in audio-only mode never comes.
        scheduleSave()
        if (whisperMode) {
            startWhisper()
        } else if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus(Status.UNAVAILABLE, getString(R.string.recognition_unavailable))
        } else {
            startListening()
        }
    }

    /** The status line for a running, unpaused on-device session. */
    private fun normalListeningText(): String = getString(
        if (liveWhisper && whisperRecorder?.liveSuspended != true) {
            R.string.status_listening_whisper
        } else {
            R.string.status_recording_audio
        }
    )

    fun togglePause() {
        // Resuming a projection Android has ended, or a microphone capture
        // that stopped, would only restart the clock over a capture that
        // records nothing.
        if (!active || finishing || deviceAudioEnded || captureEnded) return
        paused = !paused
        // Silence from before the pause must not count toward the dead-mic
        // window after it, and a warning raised before it is moot.
        silentSinceMs = 0L
        micWarned = false
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
                setStatus(runningStatus(), runningStatusText())
            } else {
                startListening()
            }
        }
        updateNotification()
    }

    /**
     * [markOnly] is the notification's Mark moment, which adds a star and
     * never takes one off; the on-screen star toggles the latest line.
     */
    fun requestHighlight(markOnly: Boolean = false): HighlightResult {
        if (capturesAudioOnly() || whisperRecorder?.liveSuspended == true) {
            // No line will arrive during this session, so a queued highlight
            // was silently dropped. Remember where in the audio it was tapped
            // instead; the transcript made after the meeting stars that line.
            // Live transcription that fell behind and stopped is the same
            // case: the last line on screen can be minutes old, and the
            // saved audio is transcribed after the meeting (liveIncomplete).
            highlightMarks.add(elapsedMs())
            scheduleSave()
            return HighlightResult.MARKED
        }
        if (partial.isNotEmpty() || segments.isEmpty()) {
            // With no line yet, the star goes on the line spoken at the tap.
            // While the live model loads that is not the next line to
            // arrive: those are the backlog, from the start of the recording.
            // A partial on screen is the line being spoken, so it is the one.
            if (pendingHighlight == null) {
                pendingHighlight = QueuedHighlight(if (segments.isEmpty()) elapsedMs() else null)
            }
            return HighlightResult.QUEUED
        }
        val index = segments.size - 1
        val nowHi = NotificationMark.highlightAfterTap(segments[index].highlighted, markOnly)
        if (nowHi != segments[index].highlighted) {
            segments[index] = segments[index].copy(highlighted = nowHi)
            observer?.onSegmentUpdated(index, segments[index])
            scheduleSave()
        }
        return HighlightResult.TOGGLED
    }

    /** "Marked at 0:12:34" while the notification shows it; null otherwise. */
    private var markConfirmation: String? = null

    /** The user swiped this session's Live Update away; see [ACTION_LIVE_DISMISSED]. */
    private var liveUpdateDismissed = false

    private val clearMarkConfirmation = Runnable {
        markConfirmation = null
        updateNotification()
    }

    /**
     * The notification's Mark moment: the on-screen star's path, answered in
     * the notification's own line for a few seconds, since a service has no
     * screen to show a toast on. The time is the recording's clock, the one
     * the transcript labels its lines with. False when there was nothing to
     * mark: no session, a pause, Finish under way, or device audio ended.
     */
    private fun markFromNotification(): Boolean {
        if (!NotificationMark.accepts(active, finishing, paused, deviceAudioEnded)) return false
        val atMs = elapsedMs()
        requestHighlight(markOnly = true)
        markConfirmation = getString(R.string.services_notif_marked_at, ElapsedTime.format(atMs))
        main.removeCallbacks(clearMarkConfirmation)
        main.postDelayed(clearMarkConfirmation, NotificationMark.CONFIRM_MS)
        updateNotification()
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
        titleDirty = true
        scheduleSave()
        // The notification shows the title; status changes no longer repost
        // it, so a rename has to.
        updateNotification()
    }

    fun updateAttendees(value: String) {
        attendeesRaw = value
        attendeesDirty = true
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
        notesDirty = true
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
        // Both callbacks below can land after this session is over, and a
        // next session started on this instance must not be finished by them.
        val session = meetingId
        // Also when paused: the chunk flushed at pause time is still being
        // transcribed, and finishing straight away closed the session under
        // it, so the last sentence before the pause was lost.
        if (recorder != null) {
            setStatus(Status.FINISHING, getString(R.string.status_finishing))
            if (recorder.liveSuspended) {
                // Live gave up and the saved audio is transcribed afterwards;
                // decoding the rest of the backlog now would only delay this.
                recorder.abandonBacklog()
                liveIncomplete = true
            }
            recorder.finish {
                main.post { if (meetingId == session) completeFinish(onDone) }
            }
            main.postDelayed({
                if (meetingId != session) return@postDelayed
                if (!finished && audioFileName != null) {
                    // Still transcribing a backlog. With the audio kept, the
                    // words are not lost by stopping: skip the rest and have
                    // the saved file transcribed instead, rather than decode
                    // for minutes after the service has gone.
                    recorder.abandonBacklog()
                    liveIncomplete = true
                }
                completeFinish(onDone)
            }, 15_000)
        } else {
            completeFinish(onDone)
        }
    }

    private var finished = false
    private fun completeFinish(onDone: ((String) -> Unit)?) {
        if (finished) return
        finished = true
        // On the normal path finish() has barriered on the transcriber
        // queue, so the merge sees every cluster. On the watchdog/paused/
        // discard paths it may not have — late segments then keep their
        // original cluster ids, which is why they resolve names through
        // this session's clusterNames rather than assuming a remap.
        applyClusterMerge()
        // What the session's late chunks need, kept as it is now: a next
        // session on this instance resets both (LateChunks).
        lateChunks?.takeIf { it.session == meetingId }
            ?.seal(withAudio = audioFileName != null, clusterNames = clusterNames)
        lateChunks = null
        placeQueuedHighlight()
        // Leaves the foreground and stops once the queued work is issued.
        teardownEngines(afterAudioClosed = WorkThenStop(workAfterSave(meetingId)))
        saveAndDrain()
        store.clearActive()
        active = false
        isRunning = false
        val id = meetingId
        observer?.onFinished(id)
        onDone?.invoke(id)
        activeSpeaker = null
    }

    /**
     * A star still waiting for its line as the session finishes goes on the
     * line it was tapped in. With no line since the tap, it is kept as a
     * mark, which the transcript made from the audio stars (HighlightMarks).
     */
    private fun placeQueuedHighlight() {
        val queued = pendingHighlight ?: return
        pendingHighlight = null
        val line = queued.atEnd()
        if (line != null) {
            starQueuedLine(line)
        } else if (audioFileName != null) {
            queued.tapOffsetMs?.let { highlightMarks.add(it) }
        }
    }

    private fun starQueuedLine(index: Int) {
        if (index !in segments.indices || segments[index].highlighted) return
        segments[index] = segments[index].copy(highlighted = true)
        observer?.onSegmentUpdated(index, segments[index])
    }

    /**
     * Finished sessions whose [WorkThenStop] has not run yet. Usually one,
     * but a session can be started and finished on this instance while the
     * last one's audio file is still closing, and the first of them to stop
     * must not take the service out of the foreground under the other.
     */
    private var pendingStops = 0

    /**
     * Runs a finished session's queued work ([workAfterSave]) and then takes
     * the service out of the foreground and stops it.
     *
     * The work waits for the audio file to close (teardownEngines), and the
     * service used to leave the foreground before that, as Finish or the
     * swipe from Recents returned. The work starts ImportService or
     * SummaryService, which on Android 12+ a background app may do only
     * while something keeps it exempt, and this service's own foreground is
     * what does at the end of a meeting. Finish tapped and the app left
     * while the backlog drained, or a swipe well after the screen went
     * away, had those starts refused, and the jobs only waited in the queue
     * until Recap was next opened. So the foreground stays until the work
     * has been issued, or [STOP_AFTER_WORK_TIMEOUT_MS] at most if the file
     * is slow to close; the work still runs when it does. Main thread.
     */
    private inner class WorkThenStop(private val work: () -> Unit) : () -> Unit {
        private var stopped = false
        private val fallback = Runnable { stop() }

        init {
            pendingStops++
            main.postDelayed(fallback, STOP_AFTER_WORK_TIMEOUT_MS)
        }

        /** Posted by teardownEngines once the audio file is closed. */
        override fun invoke() {
            try {
                work()
            } finally {
                stop()
            }
        }

        private fun stop() {
            if (stopped) return
            stopped = true
            main.removeCallbacks(fallback)
            pendingStops--
            // A session started on this instance meanwhile owns the
            // foreground and the service now. So does one whose start is
            // still on its way: start() sets isRunning on the main thread
            // before it sends the start, and onStartCommand runs it later.
            // That start's id is newer than lastStartId as well, so the
            // stop below could not cancel it either. Another finished
            // session still issuing its work stops the service itself.
            if (!stopsAfterWork(active, isRunning, pendingStops)) return
            stopForNewestStart()
        }
    }

    /**
     * What a saved session queues: the transcription of its audio when it
     * has no live words (or they stop short), otherwise the optional
     * accuracy check, and the optional summary. Shared by Finish and by
     * swiping the app away, which is a finish too.
     *
     * Returned to run once the audio file is closed (teardownEngines). The
     * check for the file ran straight after its close was started, and the
     * writer buffers 64 KB (about 8 s at 64 kbps) before the first byte
     * reaches the disk, so a short recording looked empty and its only
     * transcript was never queued. Everything it reads is taken now, since
     * the service may be gone by the time it runs.
     */
    private fun workAfterSave(id: String): () -> Unit {
        val app = applicationContext
        val audio = audioFileName
        val lines = segments.toList()
        val audioMs = lines.lastOrNull()?.audioMs ?: elapsedMs()
        val incomplete = liveIncomplete
        val meetingTitle = title
        val createdAt = startedAtMs
        return {
            if (lines.isEmpty()) {
                // Nothing was transcribed live, so the meeting is audio only
                // and its transcription is not a bonus pass — it is the
                // transcript. The summary cannot be started here alongside
                // it: the queue runs one job at a time and would reach a
                // summary first, summarising an empty transcript.
                // ImportService starts it when the words actually exist.
                queueFirstTranscript(app, id, audio)
            } else if (incomplete) {
                // The live transcript stops short of the meeting. The saved
                // audio is transcribed in full and offered as a comparison,
                // whatever the accuracy-check setting says — without it the
                // tail is gone.
                queueFirstTranscript(app, id, audio)
                maybeStartAutoSummary(app, id, meetingTitle, createdAt, lines.size)
            } else {
                maybeStartAutoCheck(app, id, audio, lines, audioMs)
                maybeStartAutoSummary(app, id, meetingTitle, createdAt, lines.size)
            }
        }
    }

    /**
     * Optional post-meeting accuracy pass. Live capture has to keep up in
     * real time and cuts the audio at silences, so a single unhurried pass
     * with a heavier model can genuinely do better. It stages a comparison
     * and notifies — it never edits the transcript on its own.
     */
    private fun maybeStartAutoCheck(
        context: Context,
        meetingId: String,
        audio: String?,
        lines: List<TranscriptSegment>,
        audioMs: Long
    ) {
        try {
            val settings = AppSettings(context)
            if (!settings.autoCheckTranscript) return
            if (audio == null) return
            if (!AudioStore.exists(context, audio)) return
            if (lines.size < MIN_SEGMENTS_FOR_AUTO_WORK) return
            val chargingOnly = settings.autoCheckWhileChargingOnly
            // When the user has asked to wait for a charger the pass is
            // deferred, not skipped, so the battery check does not apply —
            // it will run on power, which is the whole point.
            if (!chargingOnly && !Power.allowsHeavyWork(context)) return
            val current = if (settings.transcriptionEngine == "whisper") {
                settings.whisperModel
            } else {
                null
            }
            // Ranked by transcription quality and across families, not by
            // file size. Ranking by bytes put Parakeet (632 MB of files)
            // above turbo Q (547 MB), and the "not the same model twice"
            // rule then removed Parakeet precisely because it made the
            // transcript — handing an unattended pass to the slowest model
            // installed.
            // Some of the words, as the only hint of the meeting's language.
            val ranked = com.meetily.mobile.whisper.TranscriptionModels
                .rankedForCheck(context, current, lines.take(60).joinToString(" ") { it.text })
            if (ranked.isEmpty()) return
            val speed = com.meetily.mobile.whisper.ModelSpeed
            // Nothing unattended should quietly commit the phone to hours.
            // Prefer the best model that can finish in a sane time; only if
            // none can does the run get skipped, leaving the manual path —
            // which now states the cost — as the way to ask for it anyway.
            val model = ranked.firstOrNull { !speed.isLongRun(context, it, audioMs) }
                ?: return
            JobGate.requestCheck(context, meetingId, model, chargingOnly)
        } catch (_: Throwable) {
            // Strictly a bonus pass: it must never stop a meeting finishing.
        }
    }

    /**
     * Optional post-meeting summary.
     *
     * If the accuracy pass above is also on, this deliberately does NOT wait
     * for it: that pass stages a draft and requires the user to review it, so
     * waiting could wait forever. The summary is written from the transcript
     * as it stands, and accepting a check later marks it stale so the meeting
     * offers a regenerate.
     */
    private fun maybeStartAutoSummary(
        context: Context,
        meetingId: String,
        title: String,
        createdAtMs: Long,
        lines: Int
    ) {
        com.meetily.mobile.data.AutoSummary.maybeStart(context, meetingId, title, createdAtMs, lines)
    }

    /** Discards the in-progress recording and its media entirely. */
    fun discard() {
        // Only a session still under way. A recording screen left over from
        // one that has already been saved offered Discard too, and this then
        // erased the saved meeting, its audio and photos, past Recently
        // deleted.
        if (!active) return
        finishing = true
        finished = true
        // Nothing will keep these words; do not decode them, and a chunk
        // that finishes anyway has no meeting to go to (never sealed).
        whisperRecorder?.abandonBacklog()
        lateChunks = null
        teardownEngines()
        main.removeCallbacks(saveRunnable)
        // Cleared with it: left set, the next session on this instance
        // (a screen still bound keeps it alive) never saved a snapshot.
        saveScheduled = false
        titleDirty = false
        notesDirty = false
        attendeesDirty = false
        // Kill any queued snapshot first: an in-flight write landing after
        // the delete would recreate the meeting the user just discarded.
        try {
            saveExecutor.shutdownNow()
            saveExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        store.clearActive()
        store.delete(meetingId)
        for (name in photos) {
            PhotoStore.delete(this, name)
        }
        AudioStore.delete(this, audioFileName)
        audioFileName = null
        active = false
        isRunning = false
        activeSpeaker = null
        stopForegroundCompat()
        // With the start id, not a bare stopSelf() (see lastStartId). A
        // finish of the session before this one, still issuing its work,
        // stops the service when that is done.
        if (pendingStops == 0) stopSelfResult(lastStartId)
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

    /**
     * Silences the recognizer's start/stop beeps, touching only what it can
     * put back exactly.
     *
     * A stream the user had already muted is left alone, or restoring would
     * unmute it. The system and notification streams share the ringer on
     * phones, and unmuting them in vibrate mode switches the phone to ring —
     * so they are only touched when the ringer is on normal anyway (in
     * vibrate or silent they are already quiet).
     */
    private fun muteSystemSounds() {
        if (!settings.muteRecognizerSounds || mutedStreams.isNotEmpty()) return
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val ringerNormal = try {
            am.ringerMode == AudioManager.RINGER_MODE_NORMAL
        } catch (_: Exception) {
            false
        }
        for (stream in intArrayOf(
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_MUSIC
        )) {
            val ringAliased = stream != AudioManager.STREAM_MUSIC
            if (ringAliased && !ringerNormal) continue
            try {
                if (am.isStreamMute(stream)) continue
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                mutedStreams.add(stream)
                if (ringAliased) savedRingerMode = AudioManager.RINGER_MODE_NORMAL
            } catch (_: Exception) {
            }
        }
        // Muting a ring-aliased stream can move the ringer to vibrate or
        // silent; where it landed is written down with the streams.
        val ringerAfter = if (savedRingerMode >= 0) {
            try {
                am.ringerMode
            } catch (_: Exception) {
                -1
            }
        } else {
            -1
        }
        // Written down, so a session that dies muted, or a restore Android 17
        // ignores, is put right at the next start (SystemSoundsGuard).
        SystemSoundsGuard.remember(this, mutedStreams, savedRingerMode, ringerAfter)
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
            // Muting a ring-aliased stream can move the ringer itself; put it
            // back to what it was when this started.
            if (savedRingerMode >= 0) {
                try {
                    if (am.ringerMode != savedRingerMode) am.ringerMode = savedRingerMode
                } catch (_: Exception) {
                }
            }
            // Android 17 ignores the unmute once the service has left the
            // foreground (only onDestroy restores that late); what is still
            // muted stays recorded for the next start.
            SystemSoundsGuard.settle(this, am, mutedStreams.toList())
        }
        mutedStreams.clear()
        savedRingerMode = -1
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
            setStatus(Status.LISTENING, getString(R.string.status_listening))
        } catch (e: Exception) {
            setStatus(
                Status.ERROR,
                getString(R.string.status_error, e.message ?: getString(R.string.platform_status_unknown))
            )
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
            scheduleRestart(150)
        }

        override fun onError(error: Int) {
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
                    setStatus(
                        Status.ERROR,
                        getString(R.string.status_error, getString(R.string.platform_status_code, error))
                    )
                    scheduleRestart(1200)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // --- Whisper path -----------------------------------------------------

    /**
     * Wires up the recorder, straight away.
     *
     * Nothing heavy runs on this thread. The live model (the whisper.cpp
     * context, or a NeMo engine over a 622-658 MB int8 encoder) and the
     * diarization embedder (up to 109 MB) are loaded by the recorder on its
     * transcriber thread once capture is already running, so the words
     * spoken while they load wait in its queue instead of being lost.
     * Building the NeMo engine on this thread froze the recording screen
     * long enough to trip the input-dispatch ANR; building it on a worker
     * before the recorder existed kept the microphone closed for those
     * seconds instead, while the clock ran.
     */
    private fun startWhisper() {
        if (whisperRecorder != null) return
        val model = WhisperModels.byKey(settings.whisperModel)
        if (!liveWhisper) {
            // Capture only: no ASR model, no diarization embedder, no voice
            // profiles, nothing loaded at all. The whole recording is
            // transcribed from the saved audio once the meeting ends, which
            // is both cheaper and more accurate. Straight to the recorder.
            // Also where a session lands when live transcription was asked
            // for but the model is not ready: never the system recognizer,
            // which on most phones sends the audio off the device.
            finishStartWhisper(meetingId, model, null, null)
            return
        }
        // NeMo path (Parakeet/Nemotron), built by the recorder. Its key is
        // never looked up as a Whisper model: WhisperModels.byKey answers
        // the default Whisper model for it, which need not be downloaded at
        // all, so an engine that failed to load used to fall back to a file
        // that might not exist (and with the NeMo model's language settings).
        val nemoModel = com.meetily.mobile.whisper.NemoModels
            .byKeyOrNull(settings.whisperModel)
        // Optional acoustic diarization: only when enabled, the model is
        // downloaded, and the native stack loads. Failure of any piece
        // degrades silently to plain transcription.
        val dModel = if (settings.diarizationEnabled) {
            DiarizationModels.byKey(settings.diarizationModel)
                .takeIf { DiarizationModels.isDownloaded(this, it) }
        } else {
            null
        }
        finishStartWhisper(meetingId, model, nemoModel, dModel)
    }

    /**
     * The speaker embedder the recorder built for [session], handed over on
     * the main thread, where this service's state lives. Released instead
     * when that session is over: nothing else owns it yet.
     */
    private fun adoptEmbedder(
        session: String,
        dModel: com.meetily.mobile.whisper.DiarizationModel,
        c: SpeakerClusterer,
        created: SherpaEmbedder,
        profiles: List<VoiceProfile>
    ) {
        if (!active || meetingId != session) {
            Thread { created.release() }.start()
            return
        }
        embedder = created
        diarizeModelKey = dModel.key
        clusterer = c
        voiceProfiles = profiles
        // Voiceprint rollover gets its own thread. Run inline in the
        // first labeler call it held back every transcript line until
        // each stale profile was re-embedded. embed() is serialised
        // against release(), so sharing the embedder is safe; a
        // cluster that first speaks before this lands is matched again
        // on its next line, since matching retries unnamed clusters.
        Thread {
            val updated = VoiceProfileStore.reembedForModel(
                this, dModel.key, cancelled = { !active }
            ) { created.embed(it) }
            main.post { if (active && meetingId == session) voiceProfiles = updated }
        }.apply {
            name = "voiceprint-rollover"
            start()
        }
    }

    /**
     * Opens the meeting's audio file. One the user did not ask to keep is
     * kept only until its transcript exists (UnkeptAudio).
     */
    private fun openAudioWriter(): MeetingAudioWriter? = try {
        val file = AudioStore.newRecordingFile(this, meetingId)
        MeetingAudioWriter(file).also { writer ->
            audioWriter = writer
            audioFileName = file.name
            if (!settings.saveAudio) UnkeptAudio.mark(this, meetingId)
        }
    } catch (_: Throwable) {
        audioWriter = null
        audioFileName = null
        null
    }

    private fun finishStartWhisper(
        session: String,
        model: com.meetily.mobile.whisper.WhisperModel,
        nemoModel: com.meetily.mobile.whisper.NemoModel?,
        dModel: com.meetily.mobile.whisper.DiarizationModel?
    ) {
        if (!active || finishing || whisperRecorder != null || meetingId != session) return
        val englishOnly = nemoModel?.englishOnly ?: model.englishOnly
        val pin = com.meetily.mobile.data.MeetingLanguage.pinFor(null, settings.spokenLanguage)

        var labeler: ((FloatArray) -> Int?)? = null
        var prepareSpeakers: (() -> Unit)? = null
        if (dModel != null) {
            val c = SpeakerClusterer()
            // Built by the recorder's transcriber right after the model and
            // before its first chunk, so every line can be labelled.
            val loaded = java.util.concurrent.atomic.AtomicReference<SherpaEmbedder?>()
            prepareSpeakers = {
                val created = SherpaEmbedder.create(
                    DiarizationModels.fileFor(this, dModel).absolutePath, dModel.key
                )
                if (created != null) {
                    val profiles = VoiceProfileStore.load(this)
                    loaded.set(created)
                    main.post { adoptEmbedder(session, dModel, c, created, profiles) }
                }
            }
            labeler = { audio ->
                loaded.get()?.let { created ->
                    c.assign(
                        if (audio.size >= MIN_EMBED_SAMPLES) {
                            created.embed(
                                com.meetily.mobile.whisper.EmbedWindow.centre(
                                    audio, com.meetily.mobile.whisper.EmbedWindow.MAX_CHUNK_SAMPLES
                                )
                            )
                        } else null
                    )
                }
            }
        }

        /*
         * With live transcription off (the default) the words come from the
         * saved audio afterwards — so if audio is not kept either, the
         * recorder has no output at all. It captured happily, wrote nothing,
         * and finished into a meeting with no words and no file; the first
         * transcript then had no audio to start from and said nothing.
         * Keeping the audio is the only way this session can produce
         * anything, so it wins over the preference for this run, and only
         * until the transcript exists (UnkeptAudio).
         */
        val mustKeepAudio = !liveWhisper
        if ((settings.saveAudio || mustKeepAudio) && audioWriter == null) openAudioWriter()
        val writer = audioWriter
        val modelName = nemoModel?.displayName ?: model.displayName
        val late = LateChunks(session).also { lateChunks = it }

        whisperRecorder = WhisperRecorder(
            modelPath = if (nemoModel != null) "" else {
                WhisperModels.fileFor(this, model).absolutePath
            },
            modelKey = nemoModel?.key ?: model.key,
            // Also what the NeMo path records as the language (see
            // knownLanguage); NeMo itself is told its language at load.
            languageHint = if (nemoModel != null) {
                com.meetily.mobile.data.MeetingLanguage.nemoRecorded(
                    pin, nemoModel.englishOnly, nemoModel.languagePrompt
                ) ?: com.meetily.mobile.data.MeetingLanguage.AUTO
            } else {
                com.meetily.mobile.data.MeetingLanguage.whisperArgument(pin, englishOnly)
            },
            // Translate + vocab prompts are whisper features; NeMo models
            // transcribe in the spoken language and ignore both.
            translate = nemoModel == null &&
                settings.whisperTranslate && !englishOnly,
            vocabPrompt = if (nemoModel != null) null else {
                com.meetily.mobile.whisper.Vocab.promptFor(settings.customVocab)
            },
            nemoLoader = nemoModel?.let { nemo ->
                {
                    com.meetily.mobile.whisper.NemoEngine.create(
                        this, nemo, com.meetily.mobile.data.HeavyWork.recordingThreads(), pin
                    )
                }
            },
            prepare = prepareSpeakers,
            audioSource = CaptureTuning.audioSourceFor(this, settings.micSource),
            preferredDevice = CaptureTuning.findPreferred(this, settings.micDevice),
            recordFactory = if (deviceAudioMode && Build.VERSION.SDK_INT >= 29) {
                { deviceAudioRecord(session) }
            } else null,
            speakerSupplier = { activeSpeaker },
            chunkLabeler = labeler,
            // Opened by the recorder on its transcriber thread, off the main
            // thread and not in the way of capture: the first open copies
            // the bundled model to app storage and runs its self-test.
            openSpeechGate = if (liveWhisper && settings.vadSpeechGate) {
                { com.meetily.mobile.whisper.SileroVad.open(applicationContext) }
            } else null,
            transcribe = liveWhisper,
            frameSink = if (writer != null) {
                { frame -> writer.write(frame) }
            } else null,
            onLevel = { rms ->
                observer?.onLevel(rms)
                watchForDeadMicrophone(rms)
            },
            onSegment = { text, speaker, clusterId, audioMs, words ->
                main.post {
                    if (active && meetingId == session) {
                        appendSegment(text, speaker, clusterId, audioMs, words)
                    } else {
                        // The session already finished (the 15 s watchdog can
                        // fire while chunks are still transcribing). Keeping
                        // it silently dropped the tail of long meetings.
                        appendLateSegment(late, text, speaker, clusterId, audioMs, words)
                    }
                }
            },
            onProcessingChange = { processing ->
                main.post {
                    if (active && meetingId == session && !finishing && !paused && !liveLoading) {
                        setStatus(
                            if (processing) Status.PROCESSING else Status.LISTENING,
                            if (processing) getString(R.string.status_processing)
                            else normalListeningText()
                        )
                    }
                }
            },
            onError = { message ->
                main.post {
                    // A dead read is how the recorder sees the projection
                    // ending; the device-audio status already says why, and
                    // says it better than a read error code.
                    if (active && meetingId == session && !finishing &&
                        !deviceAudioEnded && !captureEnded
                    ) {
                        setStatus(Status.ERROR, message)
                    }
                }
            },
            onLiveReady = { main.post { liveModelReady(session) } },
            onLiveUnavailable = { main.post { liveModelFailed(session, modelName) } },
            onCaptureEnded = { main.post { captureStopped(session) } }
        ).also { it.start() }
        liveLoading = liveWhisper
        if (paused) {
            whisperRecorder?.pause()
            setStatus(Status.PAUSED, getString(R.string.status_paused))
        } else {
            setStatus(runningStatus(), runningStatusText())
        }
    }

    /** The status of a running, unpaused on-device session. */
    private fun runningStatus(): Status = if (liveLoading) Status.PROCESSING else Status.LISTENING

    private fun runningStatusText(): String =
        if (liveLoading) getString(R.string.status_loading_model) else normalListeningText()

    /** The live model loaded: the session transcribes from here on. */
    private fun liveModelReady(session: String) {
        if (!active || meetingId != session || !liveLoading) return
        liveLoading = false
        if (!finishing && !paused) setStatus(Status.LISTENING, normalListeningText())
    }

    /**
     * The live model did not load. The session carries on as one with live
     * transcription off: capture only, its audio kept and transcribed once
     * the meeting ends. It used to stop before opening the microphone at
     * all, while the clock and the notification carried on, so the whole
     * meeting was lost.
     */
    private fun liveModelFailed(session: String, modelName: String) {
        if (!active || meetingId != session || !liveWhisper) return
        liveLoading = false
        liveWhisper = false
        // "Keep meeting audio" off: without a file this session would
        // produce nothing. Kept only until it is transcribed. The recorder
        // hands it what it captured while the model loaded, so the file
        // starts with the meeting; that is also why a Finish tapped during
        // the load still gets one, which completeFinish then closes and
        // queues for transcription.
        if (audioWriter == null && (!finishing || whisperRecorder?.holdsAudio == true)) {
            openAudioWriter()?.let { writer ->
                whisperRecorder?.attachFrameSink { frame -> writer.write(frame) }
            }
        }
        observer?.onCaptureMode(capturesAudioOnly())
        if (!finishing && !paused) {
            setStatus(Status.LISTENING, getString(R.string.services_live_model_failed, modelName))
        }
    }

    /**
     * The recorder's capture stopped for good without being asked to. Held
     * the way an ended device-audio capture is: clock stopped, nothing more
     * recorded, only Finish left. It used to set an error line only, so the
     * notification kept a running "Recording in progress" clock with Mark,
     * Resume put "Recording" back on screen, and the user learned at Finish
     * that the rest of the meeting was never recorded.
     */
    private fun captureStopped(session: String) {
        if (!active || finishing || meetingId != session) return
        // In device-audio mode a dead source is the projection ending, and
        // that path says why.
        if (deviceAudioMode) {
            onDeviceAudioEnded()
            return
        }
        if (captureEnded) return
        captureEnded = true
        if (!paused) {
            accumulatedMs += SystemClock.elapsedRealtime() - lastResumeAt
            paused = true
        }
        setStatus(Status.PAUSED, getString(R.string.services_capture_ended_status))
    }

    // --- Shared segment handling -----------------------------------------

    /**
     * A transcript chunk that finished after the session was closed out.
     * Persisted straight to the stored meeting so the last words of a long
     * recording are not lost; the open meeting screen picks it up on reload.
     */
    private fun appendLateSegment(
        late: LateChunks,
        text: String,
        speaker: String?,
        clusterId: Int?,
        audioMs: Long?,
        words: List<com.meetily.mobile.data.WordStamp>
    ) {
        // The session the chunk was cut in, not meetingId, and that
        // session's audio and speaker names, not the service's: a next
        // session started on this instance has its own (LateChunks).
        val segment = late.segment(
            text, speaker, clusterId, audioMs, words, System.currentTimeMillis()
        ) ?: return
        val id = late.session
        try {
            lateExecutor.execute {
                // mutate() runs load, change and save inside
                // AtomicJson.exclusive. This was a raw load-then-save, which
                // is a lost update waiting to happen: two late chunks
                // overlapping dropped one, and either could land on top of —
                // and silently revert — an edit made on the meeting screen
                // while the tail was still arriving.
                try {
                    store.mutate(id) { target -> target.segments.add(segment) }
                } catch (_: Exception) {
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Service already torn down; the chunk is genuinely too late.
        }
    }

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
        val index = segments.size
        // A queued star goes on this line, an earlier one that the tap fell
        // in, or (null) waits for the next line (QueuedHighlight).
        val star = pendingHighlight?.onAppended(index, audioMs)
        if (star != null) pendingHighlight = null
        val segment = TranscriptSegment(
            timestampMs = System.currentTimeMillis(),
            text = text,
            speaker = resolved,
            highlighted = star == index,
            clusterId = clusterId,
            audioMs = if (audioFileName != null) audioMs else null,
            // Word timings only matter when the audio is kept for playback.
            words = if (audioFileName != null && words.isNotEmpty()) words else null
        )
        segments.add(segment)
        observer?.onSegmentAppended(index, segment)
        if (star != null && star != index) starQueuedLine(star)
        scheduleSave()
    }

    private fun setPartial(text: String) {
        partial = text
        observer?.onPartial(text)
    }

    /** Wall-clock ms since the input last carried any signal at all. */
    @Volatile
    private var silentSinceMs = 0L

    /** Set once the warning has been raised, so it is not raised repeatedly. */
    @Volatile
    private var micWarned = false

    /**
     * Notices when the microphone stops delivering ANY signal, and says so.
     *
     * Android hands the microphone to one app at a time. The app that loses
     * does not get an error — it gets buffers of zeros, indefinitely. A
     * screen recorder, a call, or another recorder starting up is enough, and
     * the result is a full-length recording of nothing: the timer counts, the
     * file grows, the notification says "recording", and the meeting is gone.
     * Confirmed on a Pixel 10 Pro XL, where starting a screen recording
     * during a capture took the microphone away silently.
     *
     * The threshold is far below the silence used for chunking (0.008). A
     * quiet room still has a noise floor in the 1e-4..1e-3 range; what is
     * being detected here is the absence of a signal rather than the absence
     * of speech, so a long pause in a real meeting does not trip it.
     *
     * Runs on the AUDIO thread — every touch of state beyond the two fields
     * here is posted to main.
     */
    private fun watchForDeadMicrophone(rms: Float) {
        // Device audio has no microphone in it: playback capture is exact
        // digital silence whenever nothing is playing, and losing the capture
        // itself is reported by the projection's onStop.
        if (paused || deviceAudioMode) {
            silentSinceMs = 0L
            return
        }
        if (rms >= DEAD_MIC_RMS) {
            silentSinceMs = 0L
            if (micWarned) {
                micWarned = false
                // Recomputed, not statusText: that holds the warning itself,
                // so re-sending it left the warning on screen for good.
                main.post {
                    if (active && !paused && !finishing) {
                        setStatus(Status.LISTENING, normalListeningText())
                    }
                }
            }
            return
        }
        val now = System.currentTimeMillis()
        if (silentSinceMs == 0L) {
            silentSinceMs = now
            return
        }
        if (!micWarned && now - silentSinceMs >= DEAD_MIC_AFTER_MS) {
            micWarned = true
            main.post {
                // Never over "Paused" or "Saving…".
                if (!active || paused || finishing) return@post
                setStatus(status, getString(R.string.mic_no_signal))
            }
        }
    }

    private fun setStatus(s: Status, text: String) {
        status = s
        statusText = text
        observer?.onStatus(s, text)
        updateNotification()
    }

    /**
     * [afterAudioClosed] runs on the main thread once the audio file is
     * complete on disk (at once, posted, when there is none): anything that
     * reads the recording has to wait for that.
     */
    private fun teardownEngines(afterAudioClosed: (() -> Unit)? = null) {
        main.removeCallbacks(restartRunnable)
        stopRecognizer()
        restoreSystemSounds()
        // A live model still loading is freed by the recorder's transcriber
        // once the load returns (WhisperRecorder.prepareLiveLater).
        whisperRecorder?.destroy()
        whisperRecorder = null
        liveLoading = false
        // A recorder still opening its capture reaches deviceAudioRecord only
        // after this point; with the consent gone it cannot open a projection
        // that nothing would ever stop.
        pendingProjectionData = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        // Off the main thread: release() waits for an in-flight embed,
        // which for a voiceprint rollover can be seconds.
        embedder?.let { e -> Thread { e.release() }.start() }
        embedder = null
        clusterer = null
        // Finalize the audio file off the main thread; ADTS stays playable
        // regardless, and the player UI checks the file, not this flag.
        // The waveform comes along for free: the writer measured every frame
        // it encoded. A discarded recording's file is deleted meanwhile, and
        // saveWaveform then finds nothing to describe.
        val writer = audioWriter
        audioWriter = null
        val context = applicationContext
        closeAudioThen(
            close = writer?.let { w ->
                { if (w.finish()) runCatching { w.saveWaveform(context) } }
            },
            afterClosed = afterAudioClosed,
            post = { main.post(it) },
            background = { Thread(it).start() }
        )
    }

    // --- Incremental persistence -----------------------------------------

    private val saveRunnable = Runnable {
        saveScheduled = false
        saveNow()
    }

    private fun scheduleSave() {
        // Before start() the service is bound but has no session: no id and a
        // 1970 start time, which saved a phantom "meetings/.json" whenever
        // the device-audio consent dialog stayed up past the delay.
        if (!active) return
        if (saveScheduled) return
        saveScheduled = true
        main.postDelayed(saveRunnable, 2500)
    }

    /**
     * Single writer thread for the periodic snapshots.
     *
     * buildMeeting() reads live capture state so it has to run on the main
     * thread, but MeetingStore.save() is a full JSON serialise plus a file
     * write whose cost grows with the transcript. Doing that on the main
     * thread every 2.5 seconds is invisible on an idle phone and an ANR when
     * something else is hammering storage. One executor also keeps the
     * writes ordered, so a slow one can never land on top of a newer one.
     * Shut down as a session closes, so each session that follows on the
     * same instance gets a new one (startSessionIfNeeded).
     */
    private var saveExecutor = newSaveExecutor()

    private fun newSaveExecutor() = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "meeting-save")
    }

    private fun saveNow() {
        main.removeCallbacks(saveRunnable)
        saveScheduled = false
        if (meetingId.isEmpty()) return
        val snapshot = buildMeeting()
        val withTitle = titleDirty
        val withNotes = notesDirty
        val withAttendees = attendeesDirty
        titleDirty = false
        notesDirty = false
        attendeesDirty = false
        try {
            saveExecutor.execute {
                // A merge, not an overwrite: the meeting is in the library
                // while it records, and a star, tag, attachment or summary
                // added from there was reset by the next snapshot.
                store.saveRecordingSnapshot(
                    snapshot,
                    withTitle = withTitle,
                    withNotes = withNotes,
                    withAttendees = withAttendees
                )
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shut down by the final flush; that write already happened.
        }
    }

    /**
     * Final snapshot, with a bounded wait so the meeting is on disk before
     * the service reports itself finished. Writes are small and there is at
     * most one queued, so this returns in milliseconds; the timeout exists
     * only so a wedged filesystem cannot hang the main thread.
     */
    private fun saveAndDrain() {
        saveNow()
        try {
            saveExecutor.shutdown()
            saveExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
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
        audioFile = audioFileName,
        highlightMarksMs = highlightMarks.toMutableList()
    ).also { meeting ->
        // Only a model that actually transcribed live made these lines; the
        // system recognizer has no key, and a capture-only session has no
        // lines yet — the pass that writes them stamps its own model.
        if (whisperMode && liveWhisper) {
            meeting.transcriptModel = settings.whisperModel
            // Null until known, and after teardown: saveRecordingSnapshot
            // then keeps what an earlier snapshot stored.
            whisperRecorder?.let { recorder ->
                meeting.language = recorder.knownLanguage
                meeting.translated = recorder.translating
            }
        }
    }

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
        postedNotificationKey = notificationKey()
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
     * The device-audio projection ended mid-session. It cannot be resumed
     * without a fresh consent, and a device-audio session has no other source
     * (it records playback only, never the microphone), so the session is
     * held the way a pause holds it — clock stopped, nothing more written —
     * and says why, on screen and in the notification, until Finish. Moving
     * the session over to the microphone instead would need a mid-session
     * source swap that WhisperRecorder does not have.
     */
    private fun onDeviceAudioEnded() {
        if (!active || finishing || deviceAudioEnded) return
        deviceAudioEnded = true
        if (!paused) {
            accumulatedMs += SystemClock.elapsedRealtime() - lastResumeAt
            paused = true
        }
        // Whatever the dead AudioRecord still hands back must not reach the
        // recording after this point.
        whisperRecorder?.pause()
        setStatus(
            Status.PAUSED,
            getString(DeviceAudioLock.endedStatusRes(Build.VERSION.SDK_INT))
        )
    }

    /**
     * AudioRecord fed by other apps' playback (AudioPlaybackCapture). Runs on
     * the recorder's audio thread, after the mediaProjection-typed foreground
     * start — the ordering Android 14 enforces. VoIP apps that flag their
     * audio as voice-communication are excluded by the OS; media/webinar
     * playback is capturable.
     */
    @androidx.annotation.RequiresApi(29)
    private fun deviceAudioRecord(session: String): android.media.AudioRecord? {
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
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        // The projection is the ONLY audio source in device
                        // mode, so losing it means the recording is capturing
                        // nothing. This callback was empty, which left the
                        // notification saying Listening while the transcript
                        // silently stopped growing — the user found out when
                        // they opened the finished meeting.
                        //
                        // Only while this is still the session's projection:
                        // teardown stops it too, and clears the field first
                        // thing after, so that stop is not mistaken for
                        // Android's. Taken for one after onDestroy, it would
                        // repost an ongoing notification nothing removes.
                        main.post {
                            if (mediaProjection === projection && meetingId == session) {
                                onDeviceAudioEnded()
                            }
                        }
                    }
                },
                main
            )
        } catch (_: Exception) {
        }
        mediaProjection = projection
        // This runs on the audio thread and can land after the session was
        // torn down (a slow model load, then Discard). teardownEngines has
        // already stopped whatever it found, so a projection created now is
        // stopped here or it keeps the cast indicator on for good.
        main.post {
            if (!active || finishing || meetingId != session) {
                try {
                    projection.stop()
                } catch (_: Exception) {
                }
                if (mediaProjection === projection) mediaProjection = null
            }
        }
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

    /**
     * Everything the notification shows. It was rebuilt and reposted on every
     * status change — several times per utterance on the recognizer path —
     * though status never appears in it; each post is binder calls plus a
     * SystemUI re-inflate, all for an identical row.
     */
    private fun notificationKey(): NotificationKey =
        NotificationKey(title, notificationLine(), markConfirmation, finishing)

    /** [finishing] is there because it takes Mark off; see NotificationMark.offered. */
    private data class NotificationKey(
        val title: String,
        val line: DeviceAudioLock.NotificationLine,
        val markConfirmation: String?,
        val finishing: Boolean
    )

    private var postedNotificationKey: NotificationKey? = null

    /** The one line the notification shows, which also decides its actions. */
    private fun notificationLine(): DeviceAudioLock.NotificationLine =
        DeviceAudioLock.notificationLine(
            paused = paused,
            micWarned = micWarned,
            // A capture that never opened (no consent data, getMediaProjection
            // or the AudioRecord failing) ends in ERROR with the mode still
            // set; "Recording device audio" over it would claim a capture
            // that is not there.
            deviceAudio = deviceAudioMode && status != Status.ERROR,
            deviceAudioEnded = deviceAudioEnded,
            sdkInt = Build.VERSION.SDK_INT,
            captureEnded = captureEnded
        )

    private fun updateNotification() {
        if (!active) return
        val key = notificationKey()
        if (key == postedNotificationKey) return
        postedNotificationKey = key
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    // Fixed request codes and extras, so these never change: built once.
    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this, 0,
            Intent(this, RecordingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
    }

    private val pauseIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            pendingFlags()
        )
    }

    private val stopIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this, 2,
            Intent(this, RecordingService::class.java).setAction(ACTION_FINISH),
            pendingFlags()
        )
    }

    private val markIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this, 3,
            Intent(this, RecordingService::class.java).setAction(ACTION_HIGHLIGHT),
            pendingFlags()
        )
    }

    private val liveDismissedIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this, 4,
            Intent(this, RecordingService::class.java).setAction(ACTION_LIVE_DISMISSED),
            pendingFlags()
        )
    }

    /**
     * On Android 16 a Live Update whenever it can be one (LiveUpdates): its
     * chip runs the recording's clock in the status bar and it heads the
     * shade, lock screen and always-on display. A custom lock-screen
     * stand-in rules a Live Update out, so that version's stand-in is a
     * standard one without the Mark button. The classic notification, button
     * and all, stays wherever a Live Update is out of reach anyway: before
     * Android 16, with Live Updates turned off for the app, on a release or
     * device that would not promote it, or once the user swiped it away.
     */
    private fun buildNotification(): Notification {
        val requested = LiveUpdates.requestsPromotion(
            LiveUpdates.Job.RECORDING, userStarted = true,
            sdkInt = Build.VERSION.SDK_INT, dismissed = liveUpdateDismissed
        )
        // Anything unexpected from the new platform calls falls back to the
        // classic notification: the foreground start must never fail on it.
        try {
            val canPost = requested &&
                NotificationManagerCompat.from(this).canPostPromotedNotifications()
            if (canPost) {
                val live = buildNotification(liveUpdate = true)
                val promotable = NotificationCompat.hasPromotableCharacteristics(live)
                if (!LiveUpdates.recordingKeepsLockScreenMark(requested, canPost, promotable)) {
                    return live
                }
            }
        } catch (_: RuntimeException) {
        }
        return buildNotification(liveUpdate = false)
    }

    private fun buildNotification(liveUpdate: Boolean): Notification {
        val line = notificationLine()
        val ended = line.ended
        val markOffered = NotificationMark.offered(line, finishing)
        val chip = if (liveUpdate) chipText(line) else null
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // PRIVATE, with a redacted stand-in for the lock screen. This was
            // VISIBILITY_PUBLIC, which printed the meeting's title — often
            // the most sensitive string in the app — to anyone glancing at a
            // locked phone, overriding the user's own "hide sensitive
            // notifications" choice.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                if (liveUpdate) {
                    livePublicVersion(line, markOffered, chip)
                } else {
                    classicPublicVersion(line, markOffered)
                }
            )
        if (liveUpdate) {
            builder.setRequestPromotedOngoing(true)
                .setShortCriticalText(chip)
                .setDeleteIntent(liveDismissedIntent)
        }
        if (!ended) {
            // No Resume once Android has ended device audio, or the
            // microphone capture stopped: nothing could be resumed. Finish
            // is all that is left.
            builder.addAction(
                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                getString(if (paused) R.string.resume else R.string.pause),
                pauseIntent
            )
        }
        // Between Pause and Finish, keeping the three-action limit. Gone
        // while paused, when no moment is being recorded.
        if (markOffered) {
            builder.addAction(
                R.drawable.ic_star, getString(R.string.services_notif_mark), markIntent
            )
        }
        builder.addAction(R.drawable.ic_check, getString(R.string.finish_save), stopIntent)

        when (line) {
            DeviceAudioLock.NotificationLine.DEVICE_AUDIO_ENDED -> {
                builder.setContentText(getString(R.string.devaudio_notif_ended))
                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            getString(DeviceAudioLock.endedDetailRes(Build.VERSION.SDK_INT))
                        )
                )
                builder.setUsesChronometer(false)
            }
            DeviceAudioLock.NotificationLine.CAPTURE_ENDED -> {
                builder.setContentText(getString(R.string.services_notif_capture_ended))
                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.services_notif_capture_ended_detail))
                )
                builder.setUsesChronometer(false)
            }
            DeviceAudioLock.NotificationLine.PAUSED -> {
                builder.setContentText(getString(R.string.status_paused))
                builder.setUsesChronometer(false)
            }
            DeviceAudioLock.NotificationLine.MIC_NO_SIGNAL -> {
                // Louder than the status line: by the time this matters the
                // phone is usually face down on a table with the screen off.
                builder.setContentText(markConfirmation ?: getString(R.string.mic_no_signal))
                // Expanded, the long text replaces the line, so a mark's
                // confirmation leads it there.
                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            listOfNotNull(
                                markConfirmation, getString(R.string.mic_no_signal_detail)
                            ).joinToString("\n")
                        )
                )
                builder.setWhen(System.currentTimeMillis() - elapsedMs())
                builder.setUsesChronometer(true)
            }
            DeviceAudioLock.NotificationLine.DEVICE_AUDIO_LIVE,
            DeviceAudioLock.NotificationLine.RECORDING -> {
                builder.setContentText(
                    markConfirmation ?: getString(
                        if (line == DeviceAudioLock.NotificationLine.DEVICE_AUDIO_LIVE) {
                            R.string.devaudio_notif_live
                        } else {
                            R.string.notif_recording
                        }
                    )
                )
                builder.setWhen(System.currentTimeMillis() - elapsedMs())
                builder.setUsesChronometer(true)
            }
        }
        return builder.build()
    }

    /**
     * What the lock screen's stand-ins say [line] is: which capture ended,
     * or that a recording runs. Names nothing about the meeting.
     */
    private fun publicTitleRes(line: DeviceAudioLock.NotificationLine): Int = when (line) {
        DeviceAudioLock.NotificationLine.DEVICE_AUDIO_ENDED -> R.string.devaudio_notif_ended_public
        DeviceAudioLock.NotificationLine.CAPTURE_ENDED -> R.string.services_notif_capture_ended_public
        else -> R.string.notif_recording
    }

    /** The lock screen's stand-in for the classic notification, Mark button and all. */
    private fun classicPublicVersion(
        line: DeviceAudioLock.NotificationLine,
        markOffered: Boolean
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            // The lock screen is where an ended device-audio capture
            // is first seen, since from Android 15 locking is what
            // usually ended it.
            .setContentTitle(getString(publicTitleRes(line)))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // A lock screen showing notification content shows the
            // full version, Mark included, and its tap needs no
            // unlock. One hiding it draws this version, collapsed,
            // and SystemUI never draws a collapsed view's actions,
            // so Mark is a button in the content view instead.
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(publicContentView(line, markOffered))
            .build()

    /**
     * The Live Update's stand-in for a lock screen that hides notification
     * contents. Standard, since a custom one rules the Live Update out, so
     * there is no Mark button on it: Mark is on the full notification, which
     * a lock screen showing contents draws expanded. It names nothing about
     * the meeting, only the state and the recording's clock, which the chip
     * and the always-on display take from here while contents are hidden.
     */
    private fun livePublicVersion(
        line: DeviceAudioLock.NotificationLine,
        markOffered: Boolean,
        chip: String?
    ): Notification {
        val ended = line.ended
        val paused = line == DeviceAudioLock.NotificationLine.PAUSED
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(publicTitleRes(line)))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShortCriticalText(chip)
        val confirmation = markConfirmation
        when {
            markOffered && confirmation != null -> builder.setContentText(confirmation)
            paused -> builder.setContentText(getString(R.string.status_paused))
        }
        if (!ended && !paused) {
            builder.setWhen(System.currentTimeMillis() - elapsedMs())
            builder.setUsesChronometer(true)
        }
        return builder.build()
    }

    /** The Live Update's status-bar chip in words; null runs the clock there instead. */
    private fun chipText(line: DeviceAudioLock.NotificationLine): String? =
        when (LiveUpdates.recordingChip(line, finishing)) {
            LiveUpdates.RecordingChip.ELAPSED -> null
            LiveUpdates.RecordingChip.PAUSED -> getString(R.string.liveupdates_chip_paused)
            LiveUpdates.RecordingChip.NO_MIC -> getString(R.string.liveupdates_chip_no_mic)
            LiveUpdates.RecordingChip.ENDED -> getString(R.string.liveupdates_chip_ended)
            LiveUpdates.RecordingChip.SAVING -> getString(R.string.liveupdates_chip_saving)
        }

    /**
     * The redacted version's content: a line naming nothing about the meeting
     * and Mark alone, the one action harmless in a stranger's hands. Pause
     * and Finish stay behind the redaction. The line carries the mark's
     * confirmation too, which is only the recording's clock, so a tap made
     * on a locked phone is answered there as well.
     */
    private fun publicContentView(
        line: DeviceAudioLock.NotificationLine,
        markOffered: Boolean
    ): RemoteViews =
        RemoteViews(packageName, R.layout.services_notif_public).apply {
            val confirmation = markConfirmation
            setTextViewText(
                R.id.notifPublicTitle,
                when {
                    line.ended -> getString(publicTitleRes(line))
                    markOffered && confirmation != null -> confirmation
                    else -> getString(R.string.notif_recording)
                }
            )
            setViewVisibility(R.id.notifPublicMark, if (markOffered) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.notifPublicMark, markIntent)
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
            saveAndDrain()
        }
        // lateExecutor is deliberately left running: see its declaration.
        isRunning = false
        super.onDestroy()
    }

    companion object {
        /**
         * Below this a meeting is a false start or a stray few seconds, and
         * spending a heavy pass on it is worse than useless.
         */
        private const val MIN_SEGMENTS_FOR_AUTO_WORK = 4

        /**
         * How long a finished session stays in the foreground at most while
         * its audio file closes (WorkThenStop). The close is normally well
         * under a second; the writer's own wait is bounded at 20 s.
         */
        private const val STOP_AFTER_WORK_TIMEOUT_MS = 10_000L

        const val ACTION_START = "com.meetily.mobile.action.START"
        const val ACTION_TOGGLE_PAUSE = "com.meetily.mobile.action.TOGGLE_PAUSE"
        const val ACTION_FINISH = "com.meetily.mobile.action.FINISH"

        /** The notification's Mark moment; a no-op with no session running. */
        const val ACTION_HIGHLIGHT = "com.meetily.mobile.action.HIGHLIGHT"

        /** The user swiped the session's Live Update away (its delete intent). */
        const val ACTION_LIVE_DISMISSED = "com.meetily.mobile.action.LIVE_DISMISSED"
        const val EXTRA_DEVICE_AUDIO = "device_audio"

        /** Title to open the session with; blank means "use the date". */
        const val EXTRA_INITIAL_TITLE = "initial_title"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001
        // ~1.5 s of 16 kHz audio: shorter chunks embed unreliably.
        private const val MIN_EMBED_SAMPLES = 24_000

        /**
         * Below this the input is carrying no signal at all, not merely no
         * speech. Two orders of magnitude under the chunking threshold, so a
         * quiet room's noise floor stays comfortably above it.
         */
        private const val DEAD_MIC_RMS = 1e-4f

        /** Long enough that no ordinary pause reaches it. */
        private const val DEAD_MIC_AFTER_MS = 45_000L

        /**
         * Late transcript chunks, one at a time and in arrival order.
         *
         * Deliberately NOT saveExecutor: that one is shut down by
         * saveAndDrain() as the session closes, which is the exact moment
         * these begin arriving. And deliberately process-wide, never shut
         * down: it lived on the service and was shut down in onDestroy, which
         * follows the watchdog finish within about a second — so every chunk
         * that finished after that was rejected and the meeting's tail lost.
         * An idle single thread costs nothing.
         */
        private val lateExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
            Thread(it, "meeting-late-append")
        }

        /**
         * Whether a session started now would record audio with no live
         * words: the on-device engine with live transcription off, or on but
         * with a model that is not ready. Shared by the service, which acts on
         * it, and the recording screen, which lays itself out before the
         * service has started.
         */
        fun plannedAudioOnly(context: Context, settings: AppSettings): Boolean {
            if (settings.transcriptionEngine != "whisper") return false
            return !settings.liveTranscription ||
                !com.meetily.mobile.whisper.TranscriptionModels
                    .isReady(context, settings.whisperModel)
        }

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

        fun start(context: Context, deviceAudio: Boolean = false, initialTitle: String = "") {
            isRunning = true
            // A recording always wins: meeting playback stops before capture
            // starts, so it is neither recorded nor heard over the meeting.
            MeetingPlayback.stopForRecording()
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE_AUDIO, deviceAudio)
                .putExtra(EXTRA_INITIAL_TITLE, initialTitle)
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

        /**
         * Whether a recording's first transcript is queued, once its caller
         * has found it needs one (no live words, or live words that stop
         * short): there has to be audio to transcribe and a model on the
         * phone to do it with. With no model yet, as on a fresh install's
         * first meeting, the run could only fail, and an "Import failed"
         * notice would be all the user heard of it; the audio stays with
         * the meeting, whose transcript tab says how to transcribe it.
         * Pure, so it is unit-tested.
         */
        fun wantsFirstTranscript(audioExists: Boolean, modelReady: Boolean): Boolean =
            audioExists && modelReady

        /**
         * Closes a finished session's audio file ([close], on a thread of
         * its own through [background]) and only then hands [afterClosed]
         * to [post], the main thread; with no file, at once. The work that
         * runs then looks for audio to transcribe or check, and the writer
         * holds back 64 KB (about 8 s) before its first byte reaches the
         * disk, so a short recording looked empty when the work ran beside
         * the close. A close that throws still runs the work. Pure apart
         * from what it is handed, so the order is unit-tested.
         */
        fun closeAudioThen(
            close: (() -> Unit)?,
            afterClosed: (() -> Unit)?,
            post: (() -> Unit) -> Unit,
            background: (() -> Unit) -> Unit
        ) {
            if (close == null) {
                afterClosed?.let(post)
                return
            }
            background {
                try {
                    close()
                } finally {
                    afterClosed?.let(post)
                }
            }
        }

        /**
         * Whether a finished session's WorkThenStop, its work issued, stops
         * the service: not while a session runs on it ([active]) or is on
         * its way ([starting]: start() has set isRunning), and not while
         * another finished session still has work to issue ([stillIssuing]),
         * whose own stop comes later. Pure, so it is unit-tested.
         */
        fun stopsAfterWork(active: Boolean, starting: Boolean, stillIssuing: Int): Boolean =
            !active && !starting && stillIssuing <= 0

        /**
         * Queues the transcription of a recording captured as audio only:
         * at Finish, when the app is swiped away, and for a recording an
         * interrupted session left behind (MainActivity).
         *
         * Not gated on the accuracy-check setting: that one asks whether to
         * spend effort double-checking words the app already has. Here there
         * are no words yet, so this always runs — the recording is not
         * finished without it. It does honour the same "wait for a charger"
         * preference, since the deferral is about when the work happens, not
         * whether. Call once the audio file is closed.
         */
        fun queueFirstTranscript(context: Context, meetingId: String, audioFile: String?) {
            try {
                val app = context.applicationContext
                val settings = AppSettings(app)
                if (!wantsFirstTranscript(
                        audioExists = AudioStore.exists(app, audioFile),
                        modelReady = com.meetily.mobile.whisper.TranscriptionModels
                            .isDownloaded(app, settings.whisperModel)
                    )
                ) {
                    return
                }
                JobGate.requestCheck(
                    app,
                    meetingId,
                    // The model the user chose, not the accuracy-check
                    // ranking: that ranking deliberately picks something
                    // OTHER than what produced the existing transcript, which
                    // makes no sense when nothing has produced one.
                    settings.whisperModel,
                    whenCharging = settings.autoCheckWhileChargingOnly,
                    // It is the transcript: never trimmed from the queue, and
                    // resumed as a first pass if an unplug stops it part-way.
                    firstTranscript = true
                )
            } catch (_: Throwable) {
                // The audio is saved either way; the meeting screen offers the
                // manual route. Never let this break finishing a meeting.
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away mid-recording, save and stop cleanly.
        if (active && !finished) {
            // The removed task's screens are gone: none is opened when the
            // meeting is saved (RecordingActivity.onFinished).
            observer = null
            // Swiping away is a finish, not a discard: it is Finish, barrier
            // and all. This path saved the audio and then stopped, so an
            // audio-only recording (the default) was left with a file and no
            // words, and nothing ever queued its transcription. Doing it in
            // place instead claimed the transcriber at once: the chunk being
            // spoken was dropped from the live transcript with nothing to
            // cover it, the queued ones kept decoding into the meeting after
            // the summary had read it, and the summary or accuracy check
            // started (isRunning off) with the live model still loaded.
            // finishAndSave flushes the last chunk and waits for the queue,
            // 15 s at most (with the audio kept, the rest is transcribed from
            // it), and completeFinish then queues the work and leaves the
            // foreground once it is issued (WorkThenStop). Meanwhile the
            // foreground service keeps the process alive. A Finish already
            // under way is left to do just that: its completeFinish has not
            // run yet, so there is no pending stop to wait for, and stopping
            // here would cut it short.
            finishAndSave(null)
        } else {
            isRunning = false
            // A finish still issuing its work stops the service when that is
            // done; stopping here would take its exemption away first. With
            // the start id, so a start still on its way is not cancelled.
            if (pendingStops == 0) stopForNewestStart()
        }
        super.onTaskRemoved(rootIntent)
    }
}
