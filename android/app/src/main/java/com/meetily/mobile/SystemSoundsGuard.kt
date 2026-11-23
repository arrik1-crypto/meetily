package com.meetily.mobile

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import com.meetily.mobile.data.MutedStreams

/**
 * Keeps the record of the system sounds RecordingService muted, and puts
 * back what a session left muted (MutedStreams has the why).
 *
 * Every unmute here runs while Android 17 lets an app change volume: from
 * the recording service while it is in the foreground, or from the first
 * activity of a process to reach the screen.
 */
object SystemSoundsGuard {

    private const val PREFS = "system_sounds"
    private const val KEY_RECORD = "muted_by_recap"

    /** Serialises the record's writers: a session's mute, its restore, a start's clean-up. */
    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isMuted(am: AudioManager, stream: Int): Boolean = try {
        am.isStreamMute(stream)
    } catch (_: Exception) {
        false
    }

    /** RecordingService has just muted [streams], each one unmuted before. */
    fun remember(context: Context, streams: List<Int>) {
        if (streams.isEmpty()) return
        synchronized(lock) {
            prefs(context).edit()
                .putString(
                    KEY_RECORD,
                    MutedStreams.encode(MutedStreams.Record(System.currentTimeMillis(), streams))
                )
                .apply()
        }
    }

    /**
     * RecordingService has just unmuted [streams]: what is still muted (an
     * unmute Android ignored) stays recorded, and the record goes once
     * nothing is.
     */
    fun settle(context: Context, am: AudioManager, streams: List<Int>) {
        synchronized(lock) {
            val prefs = prefs(context)
            val left = MutedStreams.remaining(streams) { isMuted(am, it) }
            if (left.isEmpty()) {
                prefs.edit().remove(KEY_RECORD).apply()
            } else {
                val at = MutedStreams.decode(prefs.getString(KEY_RECORD, null))?.mutedAtMs
                    ?: System.currentTimeMillis()
                prefs.edit()
                    .putString(KEY_RECORD, MutedStreams.encode(MutedStreams.Record(at, left)))
                    .apply()
            }
        }
    }

    /**
     * Unmutes what an earlier session left muted, once, and forgets it.
     * [duringRecording] is false when called from the app's own start: a
     * session already running in this process owns the record then, and it
     * is left alone.
     */
    fun restoreLeftover(context: Context, duringRecording: Boolean) {
        synchronized(lock) {
            if (!duringRecording && RecordingService.isRunning) return
            val prefs = prefs(context)
            val record = MutedStreams.decode(prefs.getString(KEY_RECORD, null)) ?: return
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val ringerNormal = try {
                    am.ringerMode == AudioManager.RINGER_MODE_NORMAL
                } catch (_: Exception) {
                    false
                }
                val streams = MutedStreams.toRestore(
                    record, System.currentTimeMillis(), { isMuted(am, it) }, ringerNormal,
                    { it != AudioManager.STREAM_MUSIC }
                )
                for (stream in streams) {
                    try {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: Exception) {
                    }
                }
            }
            prefs.edit().remove(KEY_RECORD).apply()
        }
    }

    /**
     * Once per process: the first activity to resume puts back what an
     * earlier process left muted. Off the main thread (a preferences read and
     * a few binder calls), while that activity is on screen.
     */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                app.unregisterActivityLifecycleCallbacks(this)
                if (RecordingService.isRunning) return
                Thread {
                    runCatching { restoreLeftover(app, duringRecording = false) }
                }.start()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
