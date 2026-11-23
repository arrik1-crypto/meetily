package com.meetily.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.ForcedAligner
import com.k2fsa.sherpa.onnx.ForcedAlignerConfig
import com.k2fsa.sherpa.onnx.ForcedAlignmentResult
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.WordAlign
import com.meetily.mobile.whisper.AlignerModels
import com.meetily.mobile.whisper.AudioFileDecoder
import java.util.concurrent.CopyOnWriteArraySet

/**
 * "Re-time words": measures the word timings of edited lines against the
 * meeting's recording with sherpa-onnx's forced aligner and the optional
 * English MFA model (AlignerModels), as a foreground job. What is aligned
 * and how the result maps back onto a line's words is WordAlign's.
 *
 * A foreground service rather than work tied to the meeting screen: a
 * meeting with many edited lines takes minutes (the model alone takes
 * seconds to load, then each line is decoded and aligned), and the run
 * must go on when the user leaves the screen. Like a clear-voice copy
 * (ClearVoiceService) it is the processing of a recorded audio file, so
 * mediaProcessing on Android 15 and later and dataSync below
 * (ImportFgsType); onTimeout ends a run that reaches the type's daily
 * allowance, keeping the lines measured so far. One line goes the same
 * way: it still loads the model, and one path is one set of busy rules.
 *
 * Started only by the user, from the meeting screen, for one line or for
 * every line whose timings an edit estimated. It does not start while a
 * recording runs (JobGate's first rule), while a transcription, summary or
 * passage removal runs (the aligner holds about 200 MB; JobGate's second
 * rule), or while anything works on the meeting or waits for its audio;
 * and JobGate starts no queued batch until it ends. While it runs the
 * meeting counts as being written (AudioRemoval.isWriting): its audio is
 * not removed and it is not deleted, trashed or cleaned up underneath.
 * Its transcript is not locked (AudioRemoval.isWritingContent): a line
 * edited meanwhile simply keeps its edit, because results are matched to
 * lines by their wording when they are saved (WordAlign.apply).
 *
 * Results are saved through the store's lock onto what is stored, after
 * each pass through the recording (so a run ended part-way keeps what it
 * measured), and all of them are handed to the meeting screen at the end
 * when it shows the meeting, which applies them to its own copy by the
 * same rule and saves through its merge (MeetingDetailActivity.persist),
 * so neither a screen edit nor a result is lost. A trashed or deleted
 * meeting is not in the store, and is not written. The recording is
 * opened once for the whole run (AudioFileDecoder.Reader).
 */
class AlignService : Service() {

    /** How a run ended, for the meeting screen and the notification. */
    enum class Result { DONE, CANCELLED, FAILED, TIMED_OUT, GONE, NO_AUDIO, NO_MODEL, LOAD_FAILED, RECORDING, BUSY }

    /**
     * What a run did: [retimed] lines measured (as they read when the run
     * began), [saved] of them stored, [unaligned] lines the alignment gave
     * nothing for (they keep their timings), [skipped] lines too long or
     * outside the recording, of [total] asked for.
     */
    data class Outcome(
        val result: Result,
        val retimed: List<WordAlign.Retimed> = emptyList(),
        val saved: Int = 0,
        val unaligned: Int = 0,
        val skipped: Int = 0,
        val total: Int = 0
    )

    /** The meeting screen's view of a run. Called on the main thread. */
    interface Listener {
        /** True when this listener shows [meetingId], so the outcome is seen there. */
        fun showsMeeting(meetingId: String): Boolean

        /** [done] of [total] lines measured; total 0 while the model loads. */
        fun onAlignProgress(meetingId: String, done: Int, total: Int)

        fun onAlignDone(meetingId: String, outcome: Outcome)
    }

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wakeLockAcquiredMs = 0L

    /** Set by onTimeout: the daily allowance for this service type ran out. */
    @Volatile private var timedOut = false

    /** The newest start this instance was handed; finishRun stops with it. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (isRunning) cancelRequested = true else stopSelfResult(startId)
            }
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_MEETING_ID)
                val audioMs = intent.getLongExtra(EXTRA_AUDIO_MS, -1L)
                val text = intent.getStringExtra(EXTRA_TEXT)
                val line = if (audioMs >= 0L && text != null) {
                    WordAlign.Target(intent.getLongExtra(EXTRA_STAMP, 0L), audioMs, text)
                } else {
                    null
                }
                if (isRunning || timedOut || id.isNullOrBlank()) {
                    val running = isRunning
                    val result = if (timedOut) Result.TIMED_OUT else Result.BUSY
                    refuseStart(startId)
                    if (!running && !id.isNullOrBlank()) {
                        listeners.forEach { it.onAlignDone(id, Outcome(result)) }
                    }
                    return START_NOT_STICKY
                }
                isRunning = true
                currentMeetingId = id
                claimedId = null
                claimedAtMs = 0L
                cancelRequested = false
                done = 0
                total = 0
                createChannel()
                startForegroundCompat(buildNotification(id))
                // The foreground service keeps the process, not the CPU.
                acquireWakeLock()
                run(id, line, intent.getLongExtra(EXTRA_DURATION_MS, 0L))
            }
            else -> if (!isRunning) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Turns a start down without breaking the startForegroundService
     * contract, which needs startForeground even from a start that is
     * refused (see ImportService.refuseStart).
     */
    private fun refuseStart(startId: Int) {
        if (isRunning && !timedOut) {
            try {
                startForegroundCompat(buildNotification(currentMeetingId))
            } catch (_: Exception) {
            }
            return
        }
        claimedId = null
        claimedAtMs = 0L
        try {
            createChannel()
            startForegroundCompat(buildNotification(null))
        } catch (_: Exception) {
        }
        stopForegroundCompat()
        if (!isRunning) stopSelfResult(startId)
    }

    private fun run(meetingId: String, line: WordAlign.Target?, durationMs: Long) {
        val app = applicationContext
        Thread {
            // A live recording started meanwhile wins the CPU.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {
            }
            val outcome = try {
                work(app, meetingId, line, durationMs)
            } catch (_: Throwable) {
                Outcome(Result.FAILED)
            }
            main.post { finishRun(meetingId, outcome) }
        }.apply {
            name = "word-align"
            start()
        }
    }

    /**
     * One line to measure: where it is, what it says, and its clip, held
     * only from its first decoded sample until it has been aligned, so a
     * pass over many lines holds a few clips at a time, not all of them.
     */
    private class Job(
        val target: WordAlign.Target,
        val words: List<String>,
        val span: WordAlign.Span
    ) {
        val startSample = span.clipStartMs * AudioFileDecoder.TARGET_RATE / 1000
        val capacity = ((span.clipEndMs - span.clipStartMs) * AudioFileDecoder.TARGET_RATE / 1000).toInt()
        var samples: FloatArray? = null
        var filled = 0
        var finished = false
    }

    @WorkerThread
    private fun work(
        app: Context,
        meetingId: String,
        line: WordAlign.Target?,
        knownDurationMs: Long
    ): Outcome {
        // Checked again here: the start was asynchronous.
        if (RecordingService.isRunning) return Outcome(Result.RECORDING)
        if (othersBusy(app, meetingId)) return Outcome(Result.BUSY)
        val store = MeetingStore(app)
        val meeting = store.load(meetingId) ?: return Outcome(Result.GONE)
        val audioName = meeting.audioFile
        if (audioName.isNullOrBlank() || !AudioStore.exists(app, audioName)) return Outcome(Result.NO_AUDIO)
        val audio = AudioStore.fileFor(app, audioName)
        if (audio.length() <= 0L) return Outcome(Result.NO_AUDIO)
        val model = AlignerModels.DEFAULT
        if (!AlignerModels.isDownloaded(app, model)) return Outcome(Result.NO_MODEL)

        val indices = if (line != null) {
            listOfNotNull(meeting.segments.indices.firstOrNull { line.matches(meeting.segments[it]) })
        } else {
            WordAlign.linesToRetime(meeting.segments)
        }
        if (indices.isEmpty()) return Outcome(if (line != null) Result.GONE else Result.DONE)
        // The player's length when the screen knew it. Without it the last
        // line's clip simply ends where the recording does.
        val durationMs = knownDurationMs.takeIf { it > 0L }
        val jobs = ArrayList<Job>(indices.size)
        var skipped = 0
        for (i in indices) {
            val segment = meeting.segments[i]
            val target = WordAlign.target(segment)
            val span = WordAlign.span(meeting.segments, i, durationMs)
            if (target == null || span == null || span.lengthMs > WordAlign.MAX_LINE_MS ||
                !WordAlign.alignable(segment)
            ) {
                skipped++
                continue
            }
            jobs.add(Job(target, WordAlign.words(segment.text), span))
        }
        if (jobs.isEmpty()) return Outcome(Result.DONE, skipped = skipped, total = indices.size)
        // "Loading" until the model is in.
        publish(meetingId, 0, 0)
        if (stopping()) return stopped(skipped, indices.size)

        // Seconds of parsing (the lexicon is 200,000 lines), on this worker;
        // never on the main thread. Released below on every path.
        val aligner = try {
            ForcedAligner(
                config = ForcedAlignerConfig(
                    modelDir = AlignerModels.dirFor(app, model).absolutePath,
                    lexicon = AlignerModels.lexiconFor(app, model).absolutePath
                )
            )
        } catch (_: Throwable) {
            return Outcome(Result.LOAD_FAILED, skipped = skipped, total = indices.size)
        }
        // Every result, for the meeting screen's copy (Outcome.retimed).
        val retimed = ArrayList<WordAlign.Retimed>(jobs.size)
        var unaligned = 0
        var saved = 0
        // The recording, opened once for every pass: opening raw ADTS walks
        // the whole file (AudioFileDecoder.Reader). Null until the first
        // pass, and again after a read that failed.
        var reader: AudioFileDecoder.Reader? = null
        try {
            var measured = 0
            // Out of the lines that are measured: a line skipped (too long,
            // outside the recording) never moves the count, so the total
            // leaves it out too, and a run ends at its total.
            publish(meetingId, measured, jobs.size)
            val runs = WordAlign.runs(jobs.map { it.span.clipStartMs to it.span.clipEndMs })
            for (run in runs) {
                if (stopping()) break
                val members = run.items.map { jobs[it] }
                val runResults = ArrayList<WordAlign.Retimed>(members.size)
                val open = reader ?: try {
                    AudioFileDecoder.Reader(app, Uri.fromFile(audio))
                } catch (_: Throwable) {
                    // Unreadable now: this pass's lines get no audio, so
                    // they keep their timings; the next pass tries again.
                    null
                }
                reader = open
                val readable = decodeRun(open, run.startMs, members) { job ->
                    val mapped = align(aligner, job)
                    if (mapped != null) {
                        runResults.add(WordAlign.Retimed(job.target, mapped.words))
                    } else {
                        unaligned++
                    }
                    measured++
                    renewWakeLockIfStale()
                    publish(meetingId, measured, jobs.size)
                }
                if (!readable) {
                    try {
                        open?.close()
                    } catch (_: Throwable) {
                    }
                    reader = null
                }
                // Saved pass by pass, so a run the system ends (or native
                // code aborts) part-way keeps what it measured before.
                retimed.addAll(runResults)
                saved += save(store, meetingId, runResults)
            }
        } finally {
            try {
                reader?.close()
            } catch (_: Throwable) {
            }
            try {
                aligner.release()
            } catch (_: Throwable) {
            }
        }

        val result = when {
            timedOut -> Result.TIMED_OUT
            cancelRequested -> Result.CANCELLED
            else -> Result.DONE
        }
        return Outcome(result, retimed, saved, unaligned, skipped, indices.size)
    }

    private fun stopping(): Boolean = cancelRequested || timedOut

    /**
     * Puts one pass's [results] into the stored meeting under the store's
     * lock (WordAlign.apply: only onto lines that still read as they did).
     * Returns how many lines took theirs. Kept even when the run was
     * stopped: what was measured stays measured.
     */
    @WorkerThread
    private fun save(store: MeetingStore, meetingId: String, results: List<WordAlign.Retimed>): Int {
        if (results.isEmpty()) return 0
        var count = 0
        store.mutateIf(meetingId) { stored ->
            count = WordAlign.apply(stored.segments, results)
            count > 0
        }
        return count
    }

    private fun stopped(skipped: Int, total: Int): Outcome =
        Outcome(if (timedOut) Result.TIMED_OUT else Result.CANCELLED, skipped = skipped, total = total)

    /**
     * Decodes the recording with [reader] from [startMs] (a seek, unless it
     * is the start of a reader's first read) through the clips of [jobs],
     * handing each to [onClip] as soon as it is whole, and the rest when
     * the recording ends before them (their clips are then shorter: clamped
     * to the recording). A sample is placed at its own time, so a decoder
     * that starts a little late leaves silence at a clip's start rather
     * than shifting it. With no [reader] (the recording could not be
     * opened) every clip is handed over empty, and counts as not aligned.
     *
     * Returns false when the read failed, so [reader] is not to be read
     * again; what it read before the failure counts.
     */
    @WorkerThread
    private fun decodeRun(
        reader: AudioFileDecoder.Reader?,
        startMs: Long,
        jobs: List<Job>,
        onClip: (Job) -> Unit
    ): Boolean {
        var position = 0L
        fun finish(job: Job) {
            if (job.finished) return
            job.finished = true
            // Too little audio to say anything: counted as not aligned.
            if (job.filled * 1000L / AudioFileDecoder.TARGET_RATE < WordAlign.MIN_CLIP_MS) job.filled = 0
            onClip(job)
            job.samples = null
        }
        var readable = true
        if (reader != null) {
            try {
                reader.decode(
                    onPcm = { pcm ->
                        val chunkStart = position
                        position += pcm.size
                        for (job in jobs) {
                            if (job.finished) continue
                            val end = job.startSample + job.capacity
                            val from = maxOf(chunkStart, job.startSample)
                            val to = minOf(position, end)
                            if (from < to) {
                                val into = job.samples ?: FloatArray(job.capacity).also { job.samples = it }
                                System.arraycopy(
                                    pcm, (from - chunkStart).toInt(),
                                    into, (from - job.startSample).toInt(),
                                    (to - from).toInt()
                                )
                                job.filled = maxOf(job.filled, (to - job.startSample).toInt())
                            }
                            if (position >= end) finish(job)
                        }
                    },
                    onProgress = {},
                    cancelled = { stopping() || jobs.all { it.finished } },
                    startUs = if (startMs > 0L) startMs * 1000L else 0L,
                    onStartUs = { us -> position = us * AudioFileDecoder.TARGET_RATE / 1_000_000 }
                )
            } catch (_: Throwable) {
                // A decoder error ends this run's audio here; what was read counts.
                readable = false
            }
        }
        if (!stopping()) for (job in jobs) finish(job)
        return readable
    }

    /** Aligns one clip; null when it gives nothing to go on. The stream is always released. */
    @WorkerThread
    private fun align(aligner: ForcedAligner, job: Job): WordAlign.Mapped? {
        val clip = job.samples ?: return null
        if (job.filled <= 0) return null
        val text = WordAlign.alignerText(job.words)
        if (text.isBlank()) return null
        val samples = if (job.filled == clip.size) clip else clip.copyOf(job.filled)
        val stream = try {
            aligner.createStream()
        } catch (_: Throwable) {
            return null
        }
        val result: ForcedAlignmentResult? = try {
            stream.acceptWaveform(samples, AudioFileDecoder.TARGET_RATE)
            aligner.compute(stream, text)
        } catch (_: Throwable) {
            // A line the aligner cannot place (RuntimeException from native
            // code): it keeps its timings.
            null
        } finally {
            try {
                stream.release()
            } catch (_: Throwable) {
            }
        }
        // The JNI returns null only with an exception pending, which the
        // catch above has taken; checked all the same.
        @Suppress("SENSELESS_COMPARISON")
        if (result == null || result.tiers == null) return null
        val words = result.tiers.firstOrNull { it.name == "words" } ?: return null
        val phones = result.tiers.firstOrNull { it.name == "phones" }
        val span = job.span
        // The clip may have ended early (the recording did); the line's
        // length stays as planned and mapWords clamps into it.
        return WordAlign.mapWords(
            job.words,
            words.intervals.map { WordAlign.Interval(it.start, it.end, it.text) },
            phones?.intervals?.map { WordAlign.Interval(it.start, it.end, it.text) }.orEmpty(),
            span.clipOffsetMs,
            span.lengthMs
        )
    }

    private fun publish(meetingId: String, measured: Int, of: Int) {
        if (measured == done && of == total && of != 0) return
        done = measured
        total = of
        main.post {
            if (!isRunning || currentMeetingId != meetingId) return@post
            listeners.forEach { it.onAlignProgress(meetingId, measured, of) }
            updateNotification(meetingId)
        }
    }

    private fun finishRun(meetingId: String, outcome: Outcome) {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        val wasTimedOut = timedOut
        isRunning = false
        cancelRequested = false
        val watching = listeners.any { it.showsMeeting(meetingId) }
        listeners.forEach { it.onAlignDone(meetingId, outcome) }
        if (wasTimedOut || (!watching && outcome.result != Result.CANCELLED)) {
            postDoneNotification(meetingId, outcome)
        }
        timedOut = false
        // Queued work held back meanwhile, handed over while this service is
        // still in the foreground (see PassageService.finishRun).
        if (!wasTimedOut) JobGate.drain(this)
        if (stopSelfResult(lastStartId)) stopForegroundCompat()
    }

    /** Stops between lines; the lines measured so far are kept. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        cancelRequested = true
        stopForegroundCompat()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "meetily:word-align"
            ).also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            lock.acquire(WAKE_LOCK_MS)
            wakeLockAcquiredMs = System.currentTimeMillis()
        } catch (_: Exception) {
        }
    }

    private fun renewWakeLockIfStale() {
        if (System.currentTimeMillis() - wakeLockAcquiredMs >= WAKE_LOCK_RENEW_MS) acquireWakeLock()
    }

    // --- Notifications ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.align_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun openMeeting(meetingId: String?, requestCode: Int): PendingIntent {
        val intent = if (meetingId != null) {
            Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
        } else {
            Intent(this, MainActivity::class.java)
        }
        return PendingIntent.getActivity(
            this, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(meetingId: String?): Notification {
        val cancelIntent = PendingIntent.getService(
            this, REQUEST_CANCEL,
            Intent(this, AlignService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // No meeting title or transcript text: the notification can show on the lock screen.
        val text = if (total > 0) {
            resources.getQuantityString(R.plurals.align_progress, total, done, total)
        } else {
            getString(R.string.align_progress_loading)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(getString(R.string.align_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openMeeting(meetingId, REQUEST_OPEN))
            .addAction(0, getString(R.string.align_stop), cancelIntent)
        if (total > 0) builder.setProgress(total, done.coerceIn(0, total), false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun updateNotification(meetingId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIF_ID, buildNotification(meetingId))
        } catch (_: SecurityException) {
        }
    }

    private fun postDoneNotification(meetingId: String, outcome: Outcome) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val result = if (timedOut) outcome.copy(result = Result.TIMED_OUT) else outcome
        val body = messageFor(this, result)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentTitle(
                getString(
                    if (result.result == Result.DONE && result.saved > 0) R.string.align_notif_done
                    else R.string.align_notif_not_done
                )
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openMeeting(meetingId, REQUEST_DONE))
        try {
            manager.notify(NOTIF_DONE_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ImportFgsType.forSdk(Build.VERSION.SDK_INT))
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.meetily.mobile.align.START"
        const val ACTION_CANCEL = "com.meetily.mobile.align.CANCEL"
        const val EXTRA_MEETING_ID = "meeting_id"

        /** One line: its stamp, start and wording (WordAlign.Target). Absent: every edited line. */
        const val EXTRA_STAMP = "line_stamp"
        const val EXTRA_AUDIO_MS = "line_audio_ms"
        const val EXTRA_TEXT = "line_text"

        /** The recording's length as the screen's player knows it; 0 when unknown. */
        const val EXTRA_DURATION_MS = "duration_ms"

        private const val CHANNEL_ID = "word_align"

        /** Out of the range the other services' notification ids use. */
        private const val NOTIF_ID = 7_330
        private const val NOTIF_DONE_ID = 7_331

        /** PendingIntent request codes of their own (see PassageService). */
        private const val REQUEST_OPEN = 4861
        private const val REQUEST_CANCEL = 4862
        private const val REQUEST_DONE = 4863

        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L

        /** How long a start from [start] counts as on its way. */
        private const val CLAIM_WINDOW_MS = 10_000L

        @Volatile var isRunning = false
            private set

        /** The meeting being re-timed, while [isRunning]. */
        @Volatile var currentMeetingId: String? = null
            private set

        /** The latest run's progress, for a screen that opens mid-run: [done] of [total] lines. */
        @Volatile var done = 0
            private set

        @Volatile var total = 0
            private set

        @Volatile private var cancelRequested = false

        /** [start] was called and onStartCommand has not run yet. */
        @Volatile private var claimedId: String? = null
        @Volatile private var claimedAtMs = 0L

        private val listeners = CopyOnWriteArraySet<Listener>()

        private fun claimed(): String? =
            claimedId?.takeIf {
                claimedAtMs != 0L && SystemClock.elapsedRealtime() - claimedAtMs < CLAIM_WINDOW_MS
            }

        /** The meeting being re-timed, or about to be, if any. */
        fun aligningId(): String? = if (isRunning) currentMeetingId else claimed()

        /** True while [meetingId] is being re-timed, or about to be. */
        fun isAligning(meetingId: String): Boolean = aligningId() == meetingId

        /** True while any run is going or about to: JobGate starts no queued work then. */
        fun isBusy(): Boolean = aligningId() != null

        fun addListener(listener: Listener) {
            listeners.add(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        /**
         * Something other than a re-timing run works on [meetingId] or waits
         * for its audio: its recording, import or accuracy check, summary,
         * passage removal or clear-voice copy, a queued or interrupted job
         * for it, or a staged accuracy check still to review.
         */
        private fun othersBusy(context: Context, meetingId: String): Boolean =
            AudioRemoval.isWritingContent(context, meetingId) ||
                ClearVoiceService.isPreparing(meetingId) ||
                meetingId in AudioRemoval.waitingForAudio(context, listOf(meetingId))

        /**
         * Why [meetingId] cannot be re-timed now, as a message, or null when
         * it can. Reads the job queue: a worker, or a tap.
         */
        fun blockReason(context: Context, meetingId: String): Int? = when {
            RecordingService.isRunning -> R.string.align_busy_recording
            aligningId() != null ->
                if (aligningId() == meetingId) R.string.align_busy_running else R.string.align_busy_other_meeting
            ImportService.isRunning || SummaryService.isRunning || PassageService.isBusy() ->
                if (AudioRemoval.isWritingContent(context, meetingId)) R.string.align_busy_meeting
                else R.string.align_busy_batch
            othersBusy(context, meetingId) -> R.string.align_busy_meeting
            else -> null
        }

        /**
         * Starts re-timing [meetingId]: the one line [line], or with null
         * every line whose timings were estimated. [durationMs] is the
         * recording's length when known (0 otherwise). Main thread, from a
         * screen in the foreground, after the screen's edits were saved.
         * Returns false when refused.
         */
        fun start(context: Context, meetingId: String, line: WordAlign.Target?, durationMs: Long): Boolean {
            if (isRunning || claimed() != null) return false
            done = 0
            total = 0
            claimedId = meetingId
            claimedAtMs = SystemClock.elapsedRealtime()
            val intent = Intent(context, AlignService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MEETING_ID, meetingId)
                .putExtra(EXTRA_DURATION_MS, durationMs)
            if (line != null) {
                intent.putExtra(EXTRA_STAMP, line.timestampMs)
                    .putExtra(EXTRA_AUDIO_MS, line.audioMs)
                    .putExtra(EXTRA_TEXT, line.text)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (_: Throwable) {
                claimedId = null
                claimedAtMs = 0L
                false
            }
        }

        /** Stops the run between lines; what was measured is kept. */
        fun cancel() {
            if (isRunning) cancelRequested = true
        }

        /** What to tell the user about a finished run. */
        fun messageFor(context: Context, outcome: Outcome): String {
            val res = context.resources
            return when (outcome.result) {
                Result.DONE -> {
                    val parts = mutableListOf<String>()
                    if (outcome.saved > 0) {
                        parts += res.getQuantityString(R.plurals.align_done_saved, outcome.saved, outcome.saved)
                    }
                    val changed = outcome.retimed.size - outcome.saved
                    if (changed > 0) {
                        parts += res.getQuantityString(R.plurals.align_done_changed, changed, changed)
                    }
                    if (outcome.unaligned > 0) {
                        parts += res.getQuantityString(
                            R.plurals.align_done_unaligned, outcome.unaligned, outcome.unaligned
                        )
                    }
                    if (outcome.skipped > 0) {
                        parts += res.getQuantityString(R.plurals.align_done_skipped, outcome.skipped, outcome.skipped)
                    }
                    if (parts.isEmpty()) context.getString(R.string.align_nothing) else parts.joinToString(" ")
                }
                Result.CANCELLED -> res.getQuantityString(R.plurals.align_cancelled, outcome.saved, outcome.saved)
                Result.TIMED_OUT -> context.getString(R.string.align_timed_out)
                Result.FAILED -> context.getString(R.string.align_failed)
                Result.GONE -> context.getString(R.string.align_gone)
                Result.NO_AUDIO -> context.getString(R.string.align_no_audio)
                Result.NO_MODEL -> context.getString(R.string.align_no_model)
                Result.LOAD_FAILED -> context.getString(R.string.align_load_failed)
                Result.RECORDING -> context.getString(R.string.align_busy_recording)
                Result.BUSY -> context.getString(R.string.align_busy_meeting)
            }
        }
    }
}
