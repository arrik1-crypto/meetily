package com.meetily.mobile.security

import android.app.Activity
import android.app.Application
import android.app.Dialog
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

    /** True once the user has authenticated this session (see GRACE_MS). */
    fun isUnlocked(): Boolean = unlocked

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

    /**
     * True when a screen must stay behind the lock now. The gate asks it for
     * every starting screen, and LockActivity asks it on each start and after
     * a failed prompt, so a lock screen already up stands down by the same
     * rule as the gate.
     */
    fun shouldLock(context: Context): Boolean =
        mustLock(unlocked, AppSettings(context).appLock) { canUseLock(context) }

    /**
     * The lock rule itself. If the device credential was removed after the
     * setting was turned on, there is nothing to authenticate against —
     * don't dead-bolt the user out of their own data. [credentialUsable] is
     * asked last, and only when the answer still depends on it.
     */
    fun mustLock(
        unlocked: Boolean,
        appLockOn: Boolean,
        credentialUsable: () -> Boolean
    ): Boolean = appLockOn && !unlocked && credentialUsable()

    /**
     * Whether the task [taskId] is one of Recap's own. [ownTaskIds] are the
     * ids of the tasks ActivityManager.getAppTasks lists (those whose root is
     * a Recap activity), or null when they could not be read, which counts as
     * Recap's own: that keeps the lock screen's long-standing back behaviour.
     * A share, an Open with or Android's Manage space can put Recap's screens
     * in another app's task, which is not listed.
     */
    fun isOwnTask(taskId: Int, ownTaskIds: Collection<Int>?): Boolean =
        ownTaskIds == null || taskId in ownTaskIds

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

    /**
     * Applies the same FLAG_SECURE decision to a dialog or bottom sheet.
     *
     * Window flags are not inherited: a Dialog gets its own window, so with
     * only the activity secured a screenshot or a shared screen blacks out
     * the screen underneath yet shows the sheet on top of it, meeting text
     * and all. Call before show(); see showSecure for AlertDialog builders.
     */
    fun secure(dialog: Dialog) {
        val window = dialog.window ?: return
        if (AppSettings(dialog.context).secureScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Every activity currently alive, so the flag can be pushed to all of
     * them when the setting changes.
     *
     * Weak, so a leaked reference here can never keep an Activity alive.
     */
    private val liveActivities =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())

    /**
     * Re-applies FLAG_SECURE to every live window.
     *
     * Applying it at creation time alone was not enough: turning on "block
     * screenshots" in Settings only ever protected the Settings window, which
     * was then finished. MainActivity and any open meeting were created
     * earlier and kept their unprotected windows — still screenshottable,
     * still in the recents thumbnail — while the UI said the protection was
     * on. It only became true after those screens happened to be recreated,
     * which the user has no way to know.
     *
     * This is called when the setting changes, not on every start, so the
     * recreate-storm hazard the tracker's comment describes is untouched.
     */
    fun applySecureFlagToAll() {
        for (activity in liveActivities.toList()) {
            try {
                if (!activity.isFinishing && !activity.isDestroyed) applySecureFlag(activity)
            } catch (_: Exception) {
            }
        }
    }

    private class Tracker : Application.ActivityLifecycleCallbacks {

        // FLAG_SECURE is decided once per window, before first draw. It is
        // deliberately NOT reapplied in onActivityStarted: mutating window
        // flags during a night-mode recreate storm churns the Surface at the
        // worst possible moment and has been seen to wedge the window.
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            liveActivities.add(activity)
            applySecureFlag(activity)
        }

        override fun onActivityStarted(activity: Activity) {
            if (startedCount == 0 && lastAllStoppedAt != 0L &&
                SystemClock.elapsedRealtime() - lastAllStoppedAt > GRACE_MS
            ) {
                unlocked = false
            }
            startedCount++
            // Checked on every start, with no "lock screen already up" flag:
            // a cancelled prompt leaves LockActivity alive, and an earlier
            // flag that skipped the check then let anything started in that
            // window — a notification tap, the widget, a share — draw meeting
            // content over the unauthenticated lock screen. Duplicates are
            // left to FLAG_ACTIVITY_SINGLE_TOP below.
            if (activity !is LockActivity && shouldLock(activity)) {
                // Posted so the launch happens after the current lifecycle
                // transaction, never from inside onStart dispatch (which can
                // interleave with an AppCompat recreate). If the activity is
                // being torn down by the time the post runs, stand down — the
                // next activity start re-runs the gate.
                activity.window.decorView.post {
                    if (!activity.isFinishing && !activity.isDestroyed && !unlocked) {
                        activity.startActivity(
                            Intent(activity, LockActivity::class.java)
                                // No duplicate when it is already on top;
                                // when it is not, a new one goes on top of
                                // whatever just started, which is the point.
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                // Covers the screen on its first frame rather
                                // than sliding in over it, so the screen it
                                // guards is not shown during an open animation.
                                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        )
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
        override fun onActivityDestroyed(activity: Activity) {
            liveActivities.remove(activity)
        }
    }
}
