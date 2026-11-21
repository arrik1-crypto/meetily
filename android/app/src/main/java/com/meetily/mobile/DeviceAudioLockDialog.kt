package com.meetily.mobile

import android.annotation.SuppressLint
import android.os.Build
import android.view.LayoutInflater
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.meetily.mobile.data.AppSettings

/**
 * The warning shown before the screen-capture consent for device audio, on
 * the Android versions where a locked screen ends that capture (see
 * [DeviceAudioLock]). Said up front because afterwards is too late: the
 * phone that locked is usually face down, and the meeting is already gone.
 */
object DeviceAudioLockDialog {

    /**
     * Runs [onContinue] straight away when there is nothing to say (an older
     * Android, or "Don't show this again" was ticked before). Otherwise
     * shows the warning, which ends in [onContinue] or, when the user would
     * rather not depend on the screen staying unlocked, [onMicrophone].
     *
     * Not cancelable: Back would leave the screen with no session started
     * and no way to start one, exactly like the recording-consent dialog.
     */
    @SuppressLint("InflateParams") // AlertDialog supplies the parent.
    fun showIfNeeded(
        activity: AppCompatActivity,
        settings: AppSettings,
        onContinue: () -> Unit,
        onMicrophone: () -> Unit
    ) {
        if (!DeviceAudioLock.shouldWarnBeforeConsent(
                Build.VERSION.SDK_INT, settings.devaudioLockWarningDismissed
            )
        ) {
            onContinue()
            return
        }
        val builder = AlertDialog.Builder(activity)
        val panel = LayoutInflater.from(builder.context)
            .inflate(R.layout.devaudio_lock_warning, null)
        val dontShow = panel.findViewById<CheckBox>(R.id.devaudioDontShowAgain)
        fun remember() {
            if (dontShow?.isChecked == true) settings.devaudioLockWarningDismissed = true
        }
        builder
            .setTitle(R.string.devaudio_lock_title)
            .setMessage(R.string.devaudio_lock_message)
            .setView(panel)
            .setCancelable(false)
            .setPositiveButton(R.string.devaudio_lock_continue) { _, _ ->
                remember()
                onContinue()
            }
            .setNegativeButton(R.string.devaudio_lock_use_mic) { _, _ ->
                remember()
                onMicrophone()
            }
            .showSecure()
    }
}
