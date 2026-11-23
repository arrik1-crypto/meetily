package com.meetily.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.meetily.mobile.data.AudioDeletions
import com.meetily.mobile.data.OpenMeetings
import com.meetily.mobile.data.PlaybackFocus
import com.meetily.mobile.data.PlaybackLifecycle
import com.meetily.mobile.data.PlaybackSkip
import com.meetily.mobile.whisper.SpeechSpans
import java.io.File

/**
 * The one meeting player in the process, shared by the meeting screen and
 * the playback service (PlaybackService).
 *
 * It lives here rather than in the screen so playback goes on when the
 * screen is left, the phone is locked or the screen rotates, and so the
 * notification, the lock screen and headset buttons control the very player
 * the screen shows. It is still Android's MediaPlayer, as before: meeting
 * recordings are raw ADTS, which MediaPlayer seeks exactly (it indexes every
 * frame) where ExoPlayer's ADTS extractor can only estimate from an average
 * bitrate, and follow-along, tap-a-word and the speaker strip all depend on
 * exact positions.
 *
 * Owns what has to outlive a screen: audio focus (speech content, paused
 * rather than ducked, see PlaybackFocus), pausing when headphones are
 * unplugged, the speed, the volume boost and the skipping of silences. A
 * recording always wins: starting one stops this player, and nothing plays
 * while one runs. Deleting the file (AudioDeletions) stops it too.
 *
 * Main thread only, except [stopMeeting] and [stopForRecording], which may
 * be called from anywhere.
 */
object MeetingPlayback {

    /** What the meeting screen showing the player's meeting hears. Main thread. */
    interface Listener {
        /** Ready: the screen's waiting action runs now. */
        fun onPrepared(player: MediaPlayer)

        /** Could not be opened; the player is gone. */
        fun onFailed()

        /**
         * Started or paused by something other than the screen's own
         * buttons: the notification, the lock screen, a headset, audio focus,
         * unplugged headphones, or the end of the audio.
         */
        fun onPlayingChanged(playing: Boolean)

        /** Moved by something other than the screen: a skip or seek outside the app. */
        fun onMoved()

        /**
         * Gone: a recording started, the audio was deleted, playback was
         * stopped from outside the app, or another meeting took the player.
         */
        fun onReleased()
    }

    /** Why [play] did not start. */
    enum class PlayResult { STARTED, NOT_READY, RECORDING, FOCUS_DENIED }

    private class Item(
        val meetingId: String,
        /** The meeting's recording; the file played may be its clear-voice copy. */
        val audioName: String?,
        val file: File,
        val copy: Boolean,
        val player: MediaPlayer
    ) {
        var prepared = false

        /** Started and not yet stopped, run to the end or left paused too long. */
        var active = false

        /** Reopened on the other file mid-play: carries on from here once prepared. */
        var switchToMs = -1
        var playAfterSwitch = false

        /** Speech spans to skip between, while skip-silence is on. */
        var spans: List<SpeechSpans.Span>? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var item: Item? = null
    private var listener: Listener? = null
    private var listenerMeetingId: String? = null

    /** Meeting screens alive per meeting (not counting a rotation in progress). */
    private val screens = HashMap<String, Int>()

    private var speed = 1.0f
    private var boostDb = 0
    private var boost: LoudnessEnhancer? = null

    /** The gain effect could not be made on this device; it will not be later either. */
    var boostUnavailable = false
        private set

    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnGain = false
    private var noisyRegistered = false

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // --- What the screen and the service read ---------------------------------

    /** The player for [meetingId], prepared or still preparing; null for any other meeting. */
    fun playerFor(meetingId: String?): MediaPlayer? =
        item?.takeIf { meetingId != null && it.meetingId == meetingId }?.player

    fun isPrepared(meetingId: String?): Boolean =
        item?.let { meetingId != null && it.meetingId == meetingId && it.prepared } == true

    /** Whether [meetingId]'s player plays the clear-voice copy; null with no player for it. */
    fun playingCopy(meetingId: String?): Boolean? =
        item?.takeIf { meetingId != null && it.meetingId == meetingId }?.copy

    val meetingId: String?
        get() = item?.meetingId

    fun isPlaying(): Boolean {
        val current = item ?: return false
        return current.prepared && isPlaying(current.player)
    }

    /** Playing, or paused only for a call or a reopen that carries on (PlaybackLifecycle.ongoing). */
    val isOngoing: Boolean
        get() = PlaybackLifecycle.ongoing(
            isPlaying(), resumeOnGain, item?.playAfterSwitch == true
        )

    /**
     * Another meeting is playing, or waiting to: preparing [meetingId]'s
     * audio just to learn its length (the Audio tab) would cut it off.
     */
    fun busyElsewhere(meetingId: String?): Boolean =
        item?.let { it.meetingId != meetingId } == true && isOngoing

    fun positionMs(): Long = item?.takeIf { it.prepared }?.let { safely { it.player.currentPosition } }?.toLong() ?: 0L

    fun durationMs(): Long = item?.takeIf { it.prepared }?.let { safely { it.player.duration } }?.toLong() ?: 0L

    fun speed(): Float = speed

    /** Whether the playback service should be up (PlaybackLifecycle.serviceWanted). */
    fun serviceWanted(): Boolean =
        PlaybackLifecycle.serviceWanted(item != null, item?.active == true, isOngoing)

    // --- The screen ------------------------------------------------------------

    /** The started meeting screen for [meetingId]; it alone hears [Listener] calls. */
    fun attach(meetingId: String, screen: Listener) {
        listener = screen
        listenerMeetingId = meetingId
    }

    fun detach(screen: Listener) {
        if (listener === screen) {
            listener = null
            listenerMeetingId = null
        }
    }

    /** A meeting screen for [meetingId] was created. */
    fun screenOpened(meetingId: String) {
        screens[meetingId] = (screens[meetingId] ?: 0) + 1
    }

    /**
     * A meeting screen for [meetingId] went away: rotated, closed, or
     * destroyed by the system. A player left paused goes with the last one;
     * one that is playing carries on in the background.
     */
    fun screenClosed(meetingId: String, changingConfigurations: Boolean) {
        val left = (screens[meetingId] ?: 0) - 1
        if (left <= 0) screens.remove(meetingId) else screens[meetingId] = left
        val current = item ?: return
        if (current.meetingId != meetingId) return
        val decision = PlaybackLifecycle.onScreenClosed(changingConfigurations, left.coerceAtLeast(0), isOngoing)
        if (decision == PlaybackLifecycle.Release.RELEASE) release()
    }

    /**
     * Opens [file] (the recording [audioName], or its clear-voice copy when
     * [copy]) for [meetingId] and prepares it in the background;
     * [Listener.onPrepared] follows, or [Listener.onFailed] when it cannot be
     * prepared. Whatever was open before is released first, another
     * meeting's audio included. False when the player could not even be
     * created (no listener call then).
     */
    fun open(
        context: Context,
        meetingId: String,
        audioName: String?,
        file: File,
        copy: Boolean,
        spans: List<SpeechSpans.Span>?
    ): Boolean {
        app = context.applicationContext
        release()
        val opened = create(context, meetingId, audioName, file, copy) ?: return false
        opened.spans = spans
        item = opened
        OpenMeetings.hold(meetingId)
        prepare(opened)
        return true
    }

    /**
     * Moves [meetingId]'s player onto the other file of the meeting (the
     * recording or its clear-voice copy). Both share one timeline, so a
     * prepared player is reopened at the same position, playing again if it
     * was playing, without giving up audio focus or the notification; one
     * still being reopened that way is retargeted; any other still preparing
     * is simply let go, and the next use opens the new file. False when the
     * new player could not be created (the old one is gone).
     */
    fun switchSource(context: Context, meetingId: String, file: File, copy: Boolean): Boolean {
        val current = item ?: return true
        if (current.meetingId != meetingId) return true
        val reopening = !current.prepared && current.switchToMs >= 0
        if (!current.prepared && !reopening) {
            release(notify = false)
            return true
        }
        val position = if (reopening) current.switchToMs else safely { current.player.currentPosition } ?: 0
        val wasPlaying = if (reopening) current.playAfterSwitch else isPlaying(current.player)
        main.removeCallbacks(skipTick)
        releaseBoost()
        try {
            current.player.release()
        } catch (_: Exception) {
        }
        item = null
        val opened = create(context, meetingId, current.audioName, file, copy)
        if (opened == null) {
            finishRelease(current, notify = false)
            return false
        }
        opened.spans = current.spans
        opened.active = current.active
        opened.switchToMs = position
        opened.playAfterSwitch = wasPlaying
        // The same meeting carries on: its hold passes to the new player.
        item = opened
        prepare(opened)
        return true
    }

    /** Skip-silence for [meetingId]'s player: [spans] while on, null when off. */
    fun setSkipSpans(meetingId: String?, spans: List<SpeechSpans.Span>?) {
        val current = item ?: return
        if (current.meetingId != meetingId) return
        current.spans = spans
        main.removeCallbacks(skipTick)
        if (spans != null && isPlaying()) main.post(skipTick)
    }

    /** The playback speed (the setting); applied at once while playing. */
    fun setSpeed(value: Float) {
        speed = value
        val current = item ?: return
        if (current.prepared && isPlaying(current.player)) applySpeed(current.player)
        PlaybackService.refresh()
    }

    /**
     * The volume boost in dB (the setting); 0 is off. Applied to the open
     * player at once. When the device cannot make the effect,
     * [boostUnavailable] turns true and the screen hides the control.
     */
    fun setBoost(db: Int) {
        boostDb = db
        val current = item ?: return
        val active = boost
        if (db <= 0) {
            releaseBoost()
        } else if (active != null) {
            try {
                active.setTargetGain(db * 100)
                active.enabled = true
            } catch (_: Throwable) {
                releaseBoost()
            }
        } else {
            attachBoost(current.player)
        }
    }

    // --- Transport ---------------------------------------------------------------

    /**
     * Starts the prepared player: asks for audio focus first, and refuses
     * while a recording runs. Starts the playback service, which keeps it
     * going away from the screen.
     */
    fun play(context: Context): PlayResult {
        val current = item ?: return PlayResult.NOT_READY
        if (!current.prepared) return PlayResult.NOT_READY
        if (RecordingService.isRunning) return PlayResult.RECORDING
        app = context.applicationContext
        if (!requestFocus(context)) return PlayResult.FOCUS_DENIED
        resumeOnGain = false
        startPlayer(current)
        PlaybackService.sync(context)
        return PlayResult.STARTED
    }

    /** Pauses at the user's request; a later focus regain does not resume it. */
    fun pause() {
        val current = item ?: return
        resumeOnGain = false
        current.playAfterSwitch = false
        pausePlayer(current)
        abandonFocus()
        PlaybackService.refresh()
    }

    /** Seeks the prepared player to [ms], kept inside the audio. */
    fun seekTo(ms: Long) {
        val current = item?.takeIf { it.prepared } ?: return
        val duration = safely { current.player.duration } ?: 0
        val target = ms.coerceIn(0L, if (duration > 0) duration.toLong() else Long.MAX_VALUE)
        try {
            current.player.seekTo(target.toInt())
        } catch (_: Exception) {
        }
    }

    /** Moves playback by [deltaMs] (the 15-second skips), kept inside the audio. Returns where it went. */
    fun skipBy(deltaMs: Long): Long? {
        val current = item?.takeIf { it.prepared } ?: return null
        val position = (safely { current.player.currentPosition } ?: 0).toLong()
        val duration = (safely { current.player.duration } ?: 0).toLong()
        val target = PlaybackSkip.target(position, deltaMs, duration)
        try {
            current.player.seekTo(target.toInt())
        } catch (_: Exception) {
            return null
        }
        return target
    }

    // --- From outside the screen (PlaybackService) ---------------------------------

    internal fun playFromOutside(context: Context) {
        val current = item ?: return
        if (play(context) == PlayResult.STARTED) listenerFor(current)?.onPlayingChanged(true)
    }

    internal fun pauseFromOutside() {
        val current = item ?: return
        pause()
        listenerFor(current)?.onPlayingChanged(false)
    }

    internal fun seekFromOutside(ms: Long) {
        val current = item ?: return
        seekTo(ms)
        listenerFor(current)?.onMoved()
    }

    internal fun skipFromOutside(deltaMs: Long) {
        val current = item ?: return
        if (skipBy(deltaMs) != null) listenerFor(current)?.onMoved()
    }

    /**
     * Stop in the notification or on a headset, or the app swiped away
     * while paused: playback stops and the service ends; the player stays
     * only for a meeting screen that still shows it.
     */
    internal fun stopFromOutside() {
        val current = item ?: return
        pause()
        listenerFor(current)?.onPlayingChanged(false)
        endSession(current)
    }

    // --- Stopping from anywhere --------------------------------------------------------

    /**
     * Stops and lets go of [meetingId]'s player, if it has the player: the
     * meeting is being deleted, moved out of the library, or its audio
     * rewritten. Any thread.
     */
    fun stopMeeting(meetingId: String) {
        onMain {
            if (item?.meetingId == meetingId) release()
        }
    }

    /** A recording is starting: whatever plays stops at once. Any thread. */
    fun stopForRecording() {
        onMain { release() }
    }

    /** AudioDeletions: [name] was deleted, on a worker thread. */
    private fun audioDeleted(name: String) {
        onMain {
            val current = item ?: return@onMain
            if (name == current.audioName || name == current.file.name) release()
        }
    }

    // --- Inside --------------------------------------------------------------------------

    private fun create(context: Context, meetingId: String, audioName: String?, file: File, copy: Boolean): Item? {
        AudioDeletions.listen(::audioDeleted)
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(attributes)
            // Keeps the CPU awake while playing (only then) with the screen off.
            p.setWakeMode(context.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(file.absolutePath)
            Item(meetingId, audioName, file, copy, p)
        } catch (_: Exception) {
            try {
                p.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    /**
     * Prepares asynchronously. Meeting audio is raw ADTS, which has no
     * index, so the platform walks every frame header to find its length:
     * seconds on a multi-hour meeting.
     */
    private fun prepare(opened: Item) {
        val p = opened.player
        p.setOnPreparedListener { onPrepared(opened) }
        p.setOnErrorListener { _, _, _ ->
            // Only a failure to prepare is handled here; once playing,
            // errors keep their old route through the completion listener.
            if (item === opened && !opened.prepared) {
                failed(opened)
                true
            } else {
                false
            }
        }
        p.setOnCompletionListener { onCompleted(opened) }
        p.setOnSeekCompleteListener { if (item === opened) PlaybackService.refresh() }
        try {
            p.prepareAsync()
        } catch (_: Exception) {
            failed(opened)
        }
    }

    private fun onPrepared(opened: Item) {
        if (item !== opened) return
        opened.prepared = true
        attachBoost(opened.player)
        val switchTo = opened.switchToMs
        if (switchTo >= 0) {
            opened.switchToMs = -1
            val duration = safely { opened.player.duration } ?: 0
            try {
                opened.player.seekTo(switchTo.coerceIn(0, duration.coerceAtLeast(0)))
            } catch (_: Exception) {
            }
            if (opened.playAfterSwitch) {
                opened.playAfterSwitch = false
                // Focus was kept through the reopen; a recording that started
                // meanwhile released the player, so this one is not reached.
                startPlayer(opened)
            }
        }
        listenerFor(opened)?.onPrepared(opened.player)
        PlaybackService.refresh()
    }

    private fun failed(opened: Item) {
        if (item !== opened) return
        item = null
        finishRelease(opened, notify = false)
        if (listenerMeetingId == opened.meetingId) listener?.onFailed()
    }

    private fun onCompleted(opened: Item) {
        if (item !== opened) return
        resumeOnGain = false
        opened.playAfterSwitch = false
        main.removeCallbacks(skipTick)
        unregisterNoisy()
        abandonFocus()
        listenerFor(opened)?.onPlayingChanged(false)
        // The end of the audio ends the service, as Stop does.
        endSession(opened)
    }

    /** The service ends; the player goes too unless a screen still shows its meeting. */
    private fun endSession(current: Item) {
        main.removeCallbacks(idleStop)
        current.active = false
        when (PlaybackLifecycle.onIdle(isOngoing, screens[current.meetingId] ?: 0)) {
            PlaybackLifecycle.Release.KEEP -> Unit
            PlaybackLifecycle.Release.END_SERVICE -> PlaybackService.refresh()
            PlaybackLifecycle.Release.RELEASE -> release()
        }
    }

    private val idleStop = Runnable {
        val current = item
        if (current != null && !isOngoing) endSession(current)
    }

    private fun startPlayer(current: Item) {
        try {
            current.player.start()
        } catch (_: Exception) {
            return
        }
        // After start: on some devices setting the speed of a paused player
        // starts it.
        applySpeed(current.player)
        current.active = true
        main.removeCallbacks(idleStop)
        registerNoisy()
        main.removeCallbacks(skipTick)
        if (current.spans != null) main.post(skipTick)
    }

    private fun pausePlayer(current: Item) {
        try {
            if (current.prepared && isPlaying(current.player)) current.player.pause()
        } catch (_: Exception) {
        }
        main.removeCallbacks(skipTick)
        unregisterNoisy()
        main.removeCallbacks(idleStop)
        if (current.active && !isOngoing) main.postDelayed(idleStop, PlaybackLifecycle.IDLE_STOP_MS)
    }

    /** Releases the player, whichever meeting it plays; the screen showing it hears [Listener.onReleased]. */
    private fun release(notify: Boolean = true) {
        val current = item ?: return
        item = null
        try {
            current.player.release()
        } catch (_: Exception) {
        }
        finishRelease(current, notify)
    }

    private fun finishRelease(current: Item, notify: Boolean) {
        main.removeCallbacks(skipTick)
        main.removeCallbacks(idleStop)
        resumeOnGain = false
        unregisterNoisy()
        abandonFocus()
        releaseBoost()
        OpenMeetings.release(current.meetingId)
        if (notify && listenerMeetingId == current.meetingId) listener?.onReleased()
        PlaybackService.refresh()
    }

    private fun listenerFor(current: Item): Listener? =
        listener.takeIf { listenerMeetingId == current.meetingId && item === current }

    private fun applySpeed(p: MediaPlayer) {
        try {
            p.playbackParams = p.playbackParams.setSpeed(speed)
        } catch (_: Exception) {
            // Some codecs refuse non-1x; playback continues at normal speed.
        }
    }

    /**
     * Raises a quiet meeting above the system's ceiling: MediaPlayer.setVolume
     * can only attenuate. Not every device provides the effect (construction
     * throws on some ROMs), so a failure turns the control off rather than
     * stopping playback.
     */
    private fun attachBoost(p: MediaPlayer) {
        releaseBoost()
        if (boostDb <= 0) return
        boost = try {
            LoudnessEnhancer(p.audioSessionId).apply {
                setTargetGain(boostDb * 100) // millibels
                enabled = true
            }
        } catch (_: Throwable) {
            boostUnavailable = true
            null
        }
    }

    private fun releaseBoost() {
        try {
            boost?.release()
        } catch (_: Exception) {
        }
        boost = null
    }

    private val skipTick = object : Runnable {
        override fun run() {
            val current = item ?: return
            val spans = current.spans ?: return
            if (!current.prepared || !isPlaying(current.player)) return
            val p = current.player
            val position = safely { p.currentPosition } ?: return
            val target = SpeechSpans.skipTarget(position.toLong(), spans)
            if (target != null && target < (safely { p.duration } ?: 0)) {
                try {
                    p.seekTo(target.toInt())
                } catch (_: Exception) {
                }
            }
            main.postDelayed(this, SKIP_TICK_MS)
        }
    }

    // --- Audio focus ------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }

    private fun requestFocus(context: Context): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // Speech under another sound loses words: asked, then paused (PlaybackFocus).
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        val manager = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            manager.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }

    private fun onFocusChange(change: Int) {
        val current = item ?: return
        // A player reopening on the other file to carry on counts as playing.
        val playing = isPlaying() || current.playAfterSwitch
        val decision = PlaybackFocus.onChange(change, playing, resumeOnGain)
        resumeOnGain = decision.resumeOnGain
        when (decision.action) {
            PlaybackFocus.Action.PAUSE -> {
                current.playAfterSwitch = false
                pausePlayer(current)
                listenerFor(current)?.onPlayingChanged(false)
            }
            PlaybackFocus.Action.RESUME -> if (current.prepared && !RecordingService.isRunning) {
                startPlayer(current)
                listenerFor(current)?.onPlayingChanged(true)
            }
            PlaybackFocus.Action.NONE -> Unit
        }
        if (decision.abandon) {
            abandonFocus()
            // Nothing will bring it back by itself now: the idle stop runs.
            pausePlayer(current)
        }
        PlaybackService.refresh()
    }

    // --- Headphones unplugged -----------------------------------------------------------

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            val current = item ?: return
            if (!isPlaying() && !current.playAfterSwitch) return
            // Before the sound moves to the loudspeaker: as if the user paused.
            pause()
            listenerFor(current)?.onPlayingChanged(false)
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        val context = app ?: return
        try {
            ContextCompat.registerReceiver(
                context,
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                // A protected system broadcast: nothing else can send it.
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        noisyRegistered = false
        try {
            app?.unregisterReceiver(noisy)
        } catch (_: Exception) {
        }
    }

    // --- Helpers ----------------------------------------------------------------------------

    private fun isPlaying(p: MediaPlayer): Boolean = safely { p.isPlaying } == true

    /** MediaPlayer throws for calls in the wrong state; those read as "unknown". */
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Skip-silence checks this often while playing; the screen's own tick follows the words. */
    private const val SKIP_TICK_MS = 200L
}
