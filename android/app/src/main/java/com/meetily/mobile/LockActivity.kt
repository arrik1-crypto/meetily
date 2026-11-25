package com.meetily.mobile

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.meetily.mobile.security.AppLock

/**
 * Full-screen gate shown over the app when the app lock engages. Fires the
 * system credential prompt (biometric or device PIN/pattern) on entry; a
 * cancelled prompt leaves an Unlock button, and back never reveals the screen
 * underneath: in Recap's own task it sends the task to the background; in
 * another app's task (a share, an Open with, Android's Manage space) it closes
 * Recap's screens there and returns to that app.
 */
class LockActivity : AppCompatActivity() {

    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_lock)
        EdgeToEdge.setUp(this) { root() }

        findViewById<MaterialButton>(R.id.unlockButton).setOnClickListener { showPrompt() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (AppLock.isOwnTask(taskId, ownTaskIds())) {
                        moveTaskToBack(true)
                    } else {
                        // Moving this task back would send the other app
                        // away and leave it covered by this lock until Recap
                        // is unlocked. Finishing Recap's screens in it (this
                        // one and the import or storage screen below) goes
                        // back to that app and shows nothing of Recap's.
                        finishAffinity()
                    }
                }
            }
        )
    }

    /**
     * Ids of the tasks whose root is a Recap activity, or null when they
     * cannot be read. A task that has gone in the meantime is left out.
     */
    private fun ownTaskIds(): List<Int>? {
        val tasks = try {
            getSystemService(ActivityManager::class.java)?.appTasks
        } catch (_: Exception) {
            null
        } ?: return null
        return tasks.mapNotNull { task ->
            try {
                val info = task.taskInfo
                if (Build.VERSION.SDK_INT >= 29) {
                    info.taskId
                } else {
                    // Before API 29 the same id, under its older name.
                    @Suppress("DEPRECATION")
                    info.persistentId
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Stands down by the gate's own rule. A stale instance can be left
        // underneath after an unlock (the gate launches one per
        // unauthenticated start, and only the top one is finished by the
        // prompt), and one left on top would otherwise outlive a removed
        // screen lock: with no credential every prompt fails at once, and
        // the library could not be reached.
        if (!AppLock.shouldLock(this)) {
            finish()
            return
        }
        if (!prompting) showPrompt()
    }

    private fun showPrompt() {
        prompting = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    AppLock.noteUnlocked()
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled or errored: stay locked, let the button retry.
                    // Unless the screen lock was removed while this was up:
                    // then there is nothing to check against, and the gate
                    // no longer locks either.
                    prompting = false
                    if (!AppLock.shouldLock(this@LockActivity)) finish()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_title))
            .setSubtitle(getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(AppLock.authenticators())
            .build()
        prompt.authenticate(info)
    }
}
