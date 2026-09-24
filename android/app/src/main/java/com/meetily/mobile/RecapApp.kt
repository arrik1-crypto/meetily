package com.meetily.mobile

import android.app.Application
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.security.AppLock

class RecapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, before anything that could crash: local-only crash capture
        // (writes a file, chains to the system handler — no telemetry).
        com.meetily.mobile.diag.CrashLog.install(this)

        // Night-mode preference is process-wide state; reapply on every start.
        ThemeManager.applyNightMode(AppSettings(this).themeMode)
        // App lock + FLAG_SECURE are enforced from lifecycle callbacks so
        // every activity is covered, including quick-tile entry points.
        AppLock.install(this)
        // Re-arm reminder alarms when a force-stop has cleared them (a reboot
        // is BootReceiver's). Not unconditionally: this runs on every cold
        // start, most of them from background broadcasts, and the full re-arm
        // reads every meeting file to find a handful of reminders.
        Thread { com.meetily.mobile.reminders.Reminders.rescheduleAllIfCleared(this) }.start()
        com.meetily.mobile.llm.LocalLlm.init(this)
        Thread { dropRemovedLiteRtModels() }.start()
    }

    /**
     * Reclaims the model directory left behind by the withdrawn LiteRT-LM
     * runtime.
     *
     * An imported `.litertlm` file is measured in gigabytes, and removing the
     * runtime also removed the only screen that could delete it — so without
     * this an upgrade silently strands more disk than the whole app uses. The
     * directory's existence IS the flag: it is only ever created by a build
     * that shipped the runtime, so no preference is needed to make this
     * one-shot, and the stat costs nothing on installs that never had it.
     *
     * The stored `local_llm_runtime` / `litert_*` preferences are left alone
     * deliberately. Nothing reads them any more, so they are inert, and
     * clearing them would mean an edit on every launch to no effect.
     */
    private fun dropRemovedLiteRtModels() {
        runCatching {
            val dir = java.io.File(filesDir, "litert-models")
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // A loaded model is the biggest thing we hold; let it go first.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            com.meetily.mobile.llm.LocalLlm.release()
        }
    }
}
