package com.meetily.mobile

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
 * cancelled prompt leaves an Unlock button, and back sends the whole task to
 * the background instead of revealing the screen underneath.
 */
class LockActivity : AppCompatActivity() {

    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_lock)

        findViewById<MaterialButton>(R.id.unlockButton).setOnClickListener { showPrompt() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        // A stale instance can be left underneath after an unlock — the gate
        // now launches one per unauthenticated start, and only the top one is
        // finished by the prompt. Standing down here keeps the back stack
        // from re-prompting for an already-unlocked session.
        if (AppLock.isUnlocked()) {
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
                    prompting = false
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

    override fun onDestroy() {
        AppLock.lockScreenShowing = false
        super.onDestroy()
    }
}
