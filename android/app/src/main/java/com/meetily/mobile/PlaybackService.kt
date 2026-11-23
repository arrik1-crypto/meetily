package com.meetily.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.meetily.mobile.data.PlaybackKeys
import com.meetily.mobile.data.PlaybackLifecycle
import com.meetily.mobile.data.PlaybackSessionText
import com.meetily.mobile.data.PlaybackSkip

/**
 * Keeps meeting playback (MeetingPlayback) going with the screen off or the
 * app left, as a mediaPlayback foreground service, and gives it system media
 * controls: the notification, the lock screen and the shade's media player,
 * Bluetooth and wired headset buttons, a car or a watch.
 *
 * Built on the platform's MediaSession and Notification.MediaStyle rather
 * than Media3. Media3's session needs a Media3 Player; its ExoPlayer seeks
 * raw ADTS only by an average-bitrate estimate, so the player stays
 * MediaPlayer either way, and wrapping MediaPlayer in a Media3 Player
 * (SimpleBasePlayer, an unstable API) to get its notification would add
 * Media3, Guava and androidx.media to the APK for the same controls the
 * platform draws from this session on every supported Android version.
 *
 * The session never carries the meeting's title or words
 * (PlaybackSessionText): only a neutral label, the app name and the length.
 *
 * Runs while playback is going on, and for up to ten minutes after a pause
 * (PlaybackLifecycle), as Media3's service does. It ends at the end of the
 * audio, on Stop, when the app is swiped away while paused, when a
 * recording starts, and when the meeting or its audio is deleted (each of
 * which releases or ends the player). It never starts itself: the player
 * asks for it ([sync]) when the user starts playback.
 */
class PlaybackService : Service() {

    private var session: MediaSession? = null
    private var foreground = false
    private var lastStartId = 0

    /** The length last put in the session's metadata; -1 before any. */
    private var metadataDurationMs = -1L

    /**
     * What the posted notification shows: playing or paused, and the
     * meeting it opens. Null while none is up. It is posted again only when
     * one of them changes: a seek or a speed change moves nothing on it.
     */
    private var posted: Pair<Boolean, String?>? = null

    /** The notification's and the session's open-the-meeting intent, and that meeting. */
    private var openIntent: PendingIntent? = null
    private var openIntentFor: String? = null
    private var sessionActivity: PendingIntent? = null

    /** The notification's action and delete intents: fixed, so built once each. */
    private val serviceIntents = HashMap<String, PendingIntent>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        session = MediaSession(this, SESSION_TAG).apply {
            setCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_PLAY -> MeetingPlayback.playFromOutside(this)
            ACTION_PAUSE -> MeetingPlayback.pauseFromOutside()
            ACTION_BACK -> MeetingPlayback.skipFromOutside(-PlaybackSkip.STEP_MS)
            ACTION_FORWARD -> MeetingPlayback.skipFromOutside(PlaybackSkip.STEP_MS)
            ACTION_STOP -> MeetingPlayback.stopFromOutside()
            // Swiped away (possible once the service is out of the
            // foreground, and on Android 14 and later): a paused player is
            // done with; a playing one carries on, and its notification is
            // posted again below.
            ACTION_DISMISSED -> {
                posted = null
                if (!MeetingPlayback.isOngoing) MeetingPlayback.stopFromOutside()
            }
        }
        // Started with startForegroundService (ACTION_SYNC): startForeground
        // is owed whatever the player wants by now.
        reconcile(mustStartForeground = intent?.action == ACTION_SYNC)
        // The player lives in this process; after a restart there is nothing to play.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PlaybackLifecycle.stopOnTaskRemoved(MeetingPlayback.isOngoing)) {
            MeetingPlayback.stopFromOutside()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        foreground = false
        super.onDestroy()
    }

    /** Brings the notification and session up to date, or ends the service when it is not wanted. */
    private fun reconcile(mustStartForeground: Boolean = false) {
        val wanted = MeetingPlayback.serviceWanted()
        if (wanted || mustStartForeground) {
            updateSession()
            val shown = Pair(MeetingPlayback.isPlaying(), MeetingPlayback.meetingId)
            if (!foreground || mustStartForeground) {
                foreground = startForegroundCompat(buildNotification()) || foreground
                if (foreground) posted = shown
            } else if (shown != posted) {
                try {
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIF_ID, buildNotification())
                    posted = shown
                } catch (_: Exception) {
                }
            }
        }
        if (!wanted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            posted = null
            session?.isActive = false
            // Only when no newer start is waiting: that one decides again.
            // Once stopping, a play pressed before onDestroy starts a new
            // service rather than leaning on this one.
            if (stopSelfResult(lastStartId) && instance === this) instance = null
        }
    }

    private fun startForegroundCompat(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        true
    } catch (_: Exception) {
        // Refused from the background (Android 12 and later): playback goes
        // on while the app is in front, without the notification.
        false
    }

    // --- Media session --------------------------------------------------------------

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = MeetingPlayback.playFromOutside(this@PlaybackService)

        override fun onPause() = MeetingPlayback.pauseFromOutside()

        override fun onStop() = MeetingPlayback.stopFromOutside()

        override fun onSeekTo(pos: Long) = MeetingPlayback.seekFromOutside(pos)

        override fun onFastForward() = MeetingPlayback.skipFromOutside(PlaybackSkip.STEP_MS)

        override fun onRewind() = MeetingPlayback.skipFromOutside(-PlaybackSkip.STEP_MS)

        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                CUSTOM_BACK -> MeetingPlayback.skipFromOutside(-PlaybackSkip.STEP_MS)
                CUSTOM_FORWARD -> MeetingPlayback.skipFromOutside(PlaybackSkip.STEP_MS)
                CUSTOM_STOP -> MeetingPlayback.stopFromOutside()
            }
        }

        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = keyEvent(mediaButtonIntent)
            val delta = event?.let { PlaybackKeys.skipFor(it.keyCode) } ?: 0L
            if (event != null && delta != 0L) {
                // Next and previous have no track to go to; they skip
                // instead. Down only, and not repeated while held.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    MeetingPlayback.skipFromOutside(delta)
                }
                return true
            }
            return super.onMediaButtonEvent(mediaButtonIntent)
        }
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private fun updateSession() {
        val s = session ?: return
        s.setPlaybackState(playbackState())
        val duration = MeetingPlayback.durationMs()
        if (duration != metadataDurationMs) {
            metadataDurationMs = duration
            val text = sessionText()
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, text.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, text.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, text.subtitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build()
            s.setMetadata(metadata)
        }
        val open = openMeeting()
        if (open !== sessionActivity) {
            sessionActivity = open
            s.setSessionActivity(open)
        }
        s.isActive = true
    }

    /** A seek finished: the controls' position moves, nothing else does. */
    private fun updatePosition() {
        val s = session ?: return
        if (s.isActive) s.setPlaybackState(playbackState())
    }

    private fun playbackState(): PlaybackState {
        val playing = MeetingPlayback.isPlaying()
        return PlaybackState.Builder()
            .setActions(SESSION_ACTIONS)
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                MeetingPlayback.positionMs(),
                if (playing) MeetingPlayback.speed() else 0f,
                SystemClock.elapsedRealtime()
            )
            // Android 13 and later put the first two custom actions in the
            // previous and next slots of the media controls.
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CUSTOM_BACK, getString(R.string.playback_back15), R.drawable.ic_playback_back15
                ).build()
            )
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CUSTOM_FORWARD, getString(R.string.playback_forward15), R.drawable.ic_playback_forward15
                ).build()
            )
            // Their third and fourth buttons are custom actions too, in the
            // expanded controls: Stop, which they never draw for
            // ACTION_STOP (the notification's own Stop is ignored there).
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CUSTOM_STOP, getString(R.string.playback_stop), R.drawable.ic_close
                ).build()
            )
            .build()
    }

    private fun sessionText(): PlaybackSessionText.Text =
        PlaybackSessionText.forSession(getString(R.string.app_name), getString(R.string.playback_session_title))

    // --- Notification ----------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val playing = MeetingPlayback.isPlaying()
        val text = sessionText()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(text.title)
            .setContentText(text.subtitle)
            // Nothing in it names the meeting (PlaybackSessionText), so the
            // lock screen may show it, and its controls, in full.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openMeeting())
            .setDeleteIntent(serviceIntent(ACTION_DISMISSED, REQUEST_DISMISSED))
            // Up to Android 12 the media notification draws these; from 13
            // the controls come from the session's actions instead.
            .addAction(action(R.drawable.ic_playback_back15, R.string.playback_back15, ACTION_BACK, REQUEST_BACK))
            .addAction(
                if (playing) {
                    action(R.drawable.ic_pause, R.string.pause, ACTION_PAUSE, REQUEST_PAUSE)
                } else {
                    action(R.drawable.ic_play, R.string.play_audio, ACTION_PLAY, REQUEST_PLAY)
                }
            )
            .addAction(action(R.drawable.ic_playback_forward15, R.string.playback_forward15, ACTION_FORWARD, REQUEST_FORWARD))
            .addAction(action(R.drawable.ic_close, R.string.playback_stop, ACTION_STOP, REQUEST_STOP))
        // Never asks to be an Android 16 Live Update (LiveUpdates.Job.MEDIA_PLAYBACK):
        // MediaStyle is not a style one may have; the media controls show it instead.
        session?.let {
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(it.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun action(icon: Int, label: Int, intentAction: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            getString(label),
            serviceIntent(intentAction, requestCode)
        ).build()

    private fun serviceIntent(intentAction: String, requestCode: Int): PendingIntent =
        serviceIntents.getOrPut(intentAction) {
            PendingIntent.getService(
                this, requestCode,
                Intent(this, PlaybackService::class.java).setAction(intentAction),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

    /**
     * Opens the meeting being played (behind the app lock when that is on).
     * Built again only when another meeting plays.
     */
    private fun openMeeting(): PendingIntent {
        val meetingId = MeetingPlayback.meetingId
        openIntent?.let { if (openIntentFor == meetingId) return it }
        val intent = if (meetingId != null) {
            Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
                // That meeting's screen already on top is shown as it is
                // (MeetingDetailActivity.onNewIntent), not opened again over it.
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(this, MainActivity::class.java)
        }
        val pending = PendingIntent.getActivity(
            this, REQUEST_OPEN, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        openIntent = pending
        openIntentFor = meetingId
        return pending
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1201
        private const val SESSION_TAG = "RecapMeetingPlayback"

        private const val ACTION_SYNC = "com.meetily.mobile.playback.SYNC"
        private const val ACTION_PLAY = "com.meetily.mobile.playback.PLAY"
        private const val ACTION_PAUSE = "com.meetily.mobile.playback.PAUSE"
        private const val ACTION_BACK = "com.meetily.mobile.playback.BACK"
        private const val ACTION_FORWARD = "com.meetily.mobile.playback.FORWARD"
        private const val ACTION_STOP = "com.meetily.mobile.playback.STOP"
        private const val ACTION_DISMISSED = "com.meetily.mobile.playback.DISMISSED"

        private const val CUSTOM_BACK = "com.meetily.mobile.playback.custom.BACK15"
        private const val CUSTOM_FORWARD = "com.meetily.mobile.playback.custom.FORWARD15"
        private const val CUSTOM_STOP = "com.meetily.mobile.playback.custom.STOP"

        private const val REQUEST_OPEN = 0
        private const val REQUEST_PLAY = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_BACK = 3
        private const val REQUEST_FORWARD = 4
        private const val REQUEST_STOP = 5
        private const val REQUEST_DISMISSED = 6

        /**
         * Play, pause and stop for buttons and keys; seek for the controls'
         * position bar; fast-forward and rewind are the 15-second skips. No
         * skip to next or previous: the controls would draw track arrows for
         * them, and a meeting has no next track (the keys still skip, see
         * PlaybackKeys).
         */
        private const val SESSION_ACTIONS = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND

        /** The running service; main thread only. */
        private var instance: PlaybackService? = null

        /**
         * Brings the service in line with the player: starts it in the
         * foreground when playback has started and it is not running yet,
         * otherwise updates or ends it. Main thread; called by the player
         * when the user starts playback, which is what lets a foreground
         * service start.
         */
        fun sync(context: Context) {
            val running = instance
            if (running != null) {
                running.reconcile()
                return
            }
            if (!MeetingPlayback.serviceWanted()) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackService::class.java).setAction(ACTION_SYNC)
                )
            } catch (_: Exception) {
                // Not allowed from the background: playback goes on while
                // the app is in front, without background controls.
            }
        }

        /** The player's state changed: updates a running service, or ends it. Main thread. */
        fun refresh() {
            instance?.reconcile()
        }

        /** Only the position changed (a seek finished): updates the session alone. Main thread. */
        fun refreshPosition() {
            instance?.updatePosition()
        }
    }
}
