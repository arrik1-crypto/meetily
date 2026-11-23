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

    /**
     * RecordingService has just muted [streams], each one unmuted before.
     * [ringerBefore] and [ringerAfter] are the ringer mode before and right
     * after (-1 when no ring-aliased stream was muted), so a ringer the mute
     * moved can be put back too.
     */
    fun remember(context: Context, streams: List<Int>, ringerBefore: Int, ringerAfter: Int) {
        if (streams.isEmpty()) return
        synchronized(lock) {
            val prefs = prefs(context)
            val record = MutedStreams.adding(
                MutedStreams.decode(prefs.getString(KEY_RECORD, null)),
                MutedStreams.record(System.currentTimeMillis(), streams, ringerBefore, ringerAfter)
            )
            prefs.edit().putString(KEY_RECORD, MutedStreams.encode(record)).apply()
        }
    }

    /**
     * RecordingService has just unmuted [streams] and put the ringer back:
     * what is still muted (an unmute Android ignored) stays recorded, with
     * the ringer move while the ringer is still where the mute left it, and
     * the record goes once nothing is.
     */
    fun settle(context: Context, am: AudioManager, streams: List<Int>) {
        synchronized(lock) {
            val prefs = prefs(context)
            val ringerMode = try {
                am.ringerMode
            } catch (_: Exception) {
                -1
            }
            val record = MutedStreams.settled(
                MutedStreams.decode(prefs.getString(KEY_RECORD, null)),
                streams, { isMuted(am, it) }, ringerMode, System.currentTimeMillis()
            )
            if (record == null) {
                prefs.edit().remove(KEY_RECORD).apply()
            } else {
                prefs.edit().putString(KEY_RECORD, MutedStreams.encode(record)).apply()
            }
        }
    }

    /**
     * Unmutes what an earlier session left muted, once: the ringer first
     * when that session moved it and nobody has since, then the streams
     * (MutedStreams.toRestore). What has to wait for the ringer to be on
     * normal stays recorded; the rest is forgotten. [duringRecording] is
     * false when called from the app's own start: a session already running
     * in this process owns the record then, and it is left alone.
     */
    fun restoreLeftover(context: Context, duringRecording: Boolean) {
        synchronized(lock) {
            if (!duringRecording && RecordingService.isRunning) return
            val prefs = prefs(context)
            val record = MutedStreams.decode(prefs.getString(KEY_RECORD, null)) ?: return
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            var keep: MutedStreams.Record? = null
            if (am != null) {
                val ringerMode = try {
                    am.ringerMode
                } catch (_: Exception) {
                    -1
                }
                val restore = MutedStreams.toRestore(
                    record, System.currentTimeMillis(), { isMuted(am, it) }, ringerMode,
                    { it != AudioManager.STREAM_MUSIC }
                )
                restore.ringer?.let { mode ->
                    try {
                        am.ringerMode = mode
                    } catch (_: Exception) {
                    }
                }
                for (stream in restore.unmute) {
                    try {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: Exception) {
                    }
                }
                keep = restore.keep
            }
            if (keep == null) {
                prefs.edit().remove(KEY_RECORD).apply()
            } else {
                prefs.edit().putString(KEY_RECORD, MutedStreams.encode(keep)).apply()
            }
        }
    }

    /**
     * Once per process: the first activity to resume puts back what an
     * earlier process left muted. Off the main thread (a preferences read and
     * a few binder calls), while that activity is on screen.
     */
    fun install(app: Application) {
        // Starts reading the small preferences file on Android's own loader
        // thread now, so the recording service's restoreLeftover, on the
        // main thread at a session's start, finds it loaded instead of
        // waiting on the disk (a quick-tile cold start reaches it first).
        prefs(app)
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
