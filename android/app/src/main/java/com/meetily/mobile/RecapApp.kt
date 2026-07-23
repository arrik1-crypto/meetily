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
        // Re-arm reminder/nudge alarms from persisted state (alarms are lost
        // on process death and app updates).
        Thread { com.meetily.mobile.reminders.Reminders.rescheduleAll(this) }.start()
        com.meetily.mobile.llm.LocalLlm.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // A loaded GGUF model is the biggest thing we hold; let it go first.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            com.meetily.mobile.llm.LocalLlm.release()
        }
    }
}
