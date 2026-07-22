package com.meetily.mobile.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import com.meetily.mobile.LockActivity
import com.meetily.mobile.data.AppSettings

/**
 * Process-wide app-lock state, enforced from Application-level lifecycle
 * callbacks so every screen is covered without per-activity code. The app
 * locks on process start and again after sitting in the background beyond a
 * short grace window; LockActivity is then pushed over whatever screen is
 * resuming. The same callbacks apply FLAG_SECURE (screenshot/recents
 * blocking) when that setting is on.
 */
object AppLock {

    /** Background time after which the app re-locks. */
    private const val GRACE_MS = 30_000L

    private const val AUTHENTICATORS =
        Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL

    @Volatile private var unlocked = false
    private var startedCount = 0
    private var lastAllStoppedAt = 0L

    /** Set while LockActivity is alive, so it's only launched once. */
    @Volatile var lockScreenShowing = false

    /** True when the device has any credential BiometricPrompt can use. */
    fun canUseLock(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticators(): Int = AUTHENTICATORS

    fun noteUnlocked() {
        unlocked = true
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(Tracker())
    }

    private fun shouldLock(activity: Activity): Boolean {
        val settings = AppSettings(activity)
        if (!settings.appLock) return false
        // If the device credential was removed after the setting was turned
        // on, there is nothing to authenticate against — don't dead-bolt the
        // user out of their own data.
        if (!canUseLock(activity)) return false
        return !unlocked
    }

    /**
     * Set/clear FLAG_SECURE on a live window. Used by the tracker at creation
     * time and by Settings when the toggle changes on-screen.
     */
    fun applySecureFlag(activity: Activity) {
        if (AppSettings(activity).secureScreen) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private class Tracker : Application.ActivityLifecycleCallbacks {

        // FLAG_SECURE is decided once per window, before first draw. It is
        // deliberately NOT reapplied in onActivityStarted: mutating window
        // flags during a night-mode recreate storm churns the Surface at the
        // worst possible moment and has been seen to wedge the window.
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            applySecureFlag(activity)
        }

        override fun onActivityStarted(activity: Activity) {
            if (startedCount == 0 && lastAllStoppedAt != 0L &&
                SystemClock.elapsedRealtime() - lastAllStoppedAt > GRACE_MS
            ) {
                unlocked = false
            }
            startedCount++
            if (activity !is LockActivity && !lockScreenShowing && shouldLock(activity)) {
                lockScreenShowing = true
                // Posted so the launch happens after the current lifecycle
                // transaction, never from inside onStart dispatch (which can
                // interleave with an AppCompat recreate). If the activity is
                // being torn down by the time the post runs, stand down — the
                // next activity start re-runs the gate.
                activity.window.decorView.post {
                    if (!activity.isFinishing && !activity.isDestroyed && !unlocked) {
                        activity.startActivity(Intent(activity, LockActivity::class.java))
                    } else {
                        lockScreenShowing = false
                    }
                }
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedCount--
            if (startedCount <= 0) {
                startedCount = 0
                lastAllStoppedAt = SystemClock.elapsedRealtime()
            }
        }

        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
