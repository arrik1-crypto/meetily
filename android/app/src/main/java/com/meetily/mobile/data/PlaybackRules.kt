package com.meetily.mobile.data

import android.media.AudioManager
import android.view.KeyEvent

// The decisions behind meeting playback (MeetingPlayback, PlaybackService),
// kept apart from the player and the service so they can be unit-tested.

/**
 * What a change of audio focus asks of the meeting player.
 *
 * Meeting audio is speech, so a "may duck" loss pauses like any short loss
 * rather than playing on quieter: words spoken under a navigation prompt or
 * a notification sound are lost, and the user would have to find and replay
 * them. The player says so to the system (setWillPauseWhenDucked), so it is
 * asked rather than ducked automatically.
 */
object PlaybackFocus {

    enum class Action { NONE, PAUSE, RESUME }

    /**
     * [action] for the player now, whether a later regain should start it
     * again ([resumeOnGain]), and whether the focus request is to be given
     * up ([abandon]): after a permanent loss another app has the audio for
     * good, and only the user starting playback again asks for it back.
     */
    data class Decision(val action: Action, val resumeOnGain: Boolean, val abandon: Boolean)

    /**
     * [change] is the AudioManager.AUDIOFOCUS_* value the listener received;
     * [playing] whether the player is playing now; [resumeOnGain] whether
     * an earlier short loss paused it.
     */
    fun onChange(change: Int, playing: Boolean, resumeOnGain: Boolean): Decision =
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS ->
                Decision(if (playing) Action.PAUSE else Action.NONE, resumeOnGain = false, abandon = true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                if (playing) {
                    Decision(Action.PAUSE, resumeOnGain = true, abandon = false)
                } else {
                    // Already paused: by the user (stays paused), or by an
                    // earlier short loss (still to resume).
                    Decision(Action.NONE, resumeOnGain, abandon = false)
                }
            AudioManager.AUDIOFOCUS_GAIN ->
                // Only playback this rule paused comes back by itself; a
                // pause the user chose meanwhile cleared the flag.
                Decision(if (resumeOnGain) Action.RESUME else Action.NONE, resumeOnGain = false, abandon = false)
            else -> Decision(Action.NONE, resumeOnGain, abandon = false)
        }
}

/** The player bar's, notification's and media keys' 15-second skips. */
object PlaybackSkip {

    const val STEP_MS = 15_000L

    /**
     * Where a skip of [deltaMs] from [positionMs] lands: never before the
     * start, and never past the end when [durationMs] is known (> 0). A
     * forward skip in the last 15 seconds goes to the end.
     */
    fun target(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val end = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        val from = positionMs.coerceIn(0L, end)
        // Compared rather than added first, so an unknown length cannot overflow.
        val to = if (deltaMs > 0L && from > end - deltaMs) end else from + deltaMs
        return to.coerceIn(0L, end)
    }
}

/**
 * The words the system shows for meeting playback: on the lock screen, in
 * the notification shade's media controls, on a car's display or a watch
 * over Bluetooth, and to any app with notification access.
 *
 * Never the meeting's title or a line of its transcript. From Android 13
 * those controls are drawn from the media session's metadata, not from the
 * notification, and that one set of metadata goes to every one of those
 * surfaces alike, locked or not: there is no per-surface or per-lock-state
 * copy for an app to fill. A meeting's title is often the most sensitive
 * string in the app (it is kept off the lock screen for recordings too), so
 * the session carries a fixed, neutral label for every meeting. The meeting
 * itself is one tap away (the notification opens it, behind the app lock
 * when that is on).
 */
object PlaybackSessionText {

    data class Text(val title: String, val subtitle: String)

    /** [neutralTitle] over [appName]; the app name when the label is blank. */
    fun forSession(appName: String, neutralTitle: String): Text =
        Text(title = neutralTitle.ifBlank { appName }, subtitle = appName)
}

/**
 * When the playback service runs and when the player is let go, after
 * Media3's MediaSessionService defaults: kept while playback is going on,
 * stopped when it is not and the app is swiped away, and ended after a
 * pause nobody came back to.
 */
object PlaybackLifecycle {

    /**
     * Paused this long with nothing touched, the service and its
     * notification end. Media3 leaves foreground after the same 10 minutes.
     */
    const val IDLE_STOP_MS = 10 * 60_000L

    /**
     * Playback is going on: playing, paused only while a call or another
     * app briefly has the audio ([resumeOnGain]), or reopening on the other
     * recording (original or clear voice) to carry on playing
     * ([resumingAfterSwitch]).
     */
    fun ongoing(playing: Boolean, resumeOnGain: Boolean, resumingAfterSwitch: Boolean = false): Boolean =
        playing || resumeOnGain || resumingAfterSwitch

    /**
     * Whether the service (its notification and media session) should be
     * up: only for a player that has been started ([active]: playing, or
     * paused within [IDLE_STOP_MS] and not run to its end or stopped), or
     * whose playback is going on.
     */
    fun serviceWanted(hasPlayer: Boolean, active: Boolean, ongoing: Boolean): Boolean =
        hasPlayer && (active || ongoing)

    enum class Release { KEEP, END_SERVICE, RELEASE }

    /**
     * The last meeting screen showing the player's meeting is going away.
     * A rotation keeps everything; leaving the screen while playback goes
     * on keeps it playing in the background (the service shows it); a
     * player left paused or stopped is let go.
     */
    fun onScreenClosed(changingConfigurations: Boolean, screensLeft: Int, ongoing: Boolean): Release =
        when {
            changingConfigurations || screensLeft > 0 || ongoing -> Release.KEEP
            else -> Release.RELEASE
        }

    /**
     * Paused for [IDLE_STOP_MS], or stopped from the notification, or run
     * to the end: the service ends, and the player goes too unless a
     * meeting screen still shows it (that screen plays it again in place).
     */
    fun onIdle(ongoing: Boolean, screensOpen: Int): Release = when {
        ongoing -> Release.KEEP
        screensOpen > 0 -> Release.END_SERVICE
        else -> Release.RELEASE
    }

    /** The app swiped out of Recents: playing goes on, anything else stops. */
    fun stopOnTaskRemoved(ongoing: Boolean): Boolean = !ongoing
}

/** Media keys (headset, car, keyboard) the platform session would drop. */
object PlaybackKeys {

    /**
     * How far a key moves playback: next and previous (and the skip keys)
     * are the 15-second skips, as in podcast players, since a meeting has
     * no next track. 0 for every other key, which the session handles.
     */
    fun skipFor(keyCode: Int): Long = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> PlaybackSkip.STEP_MS
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> -PlaybackSkip.STEP_MS
        else -> 0L
    }
}
