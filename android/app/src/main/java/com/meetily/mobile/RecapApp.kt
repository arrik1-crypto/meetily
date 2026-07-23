package com.meetily.mobile

import android.app.Application
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.security.AppLock

class RecapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Night-mode preference is process-wide state; reapply on every start.
        ThemeManager.applyNightMode(AppSettings(this).themeMode)
        // App lock + FLAG_SECURE are enforced from lifecycle callbacks so
        // every activity is covered, including quick-tile entry points.
        AppLock.install(this)
        // Re-arm reminder/nudge alarms from persisted state (alarms are lost
        // on process death and app updates).
        Thread { com.meetily.mobile.reminders.Reminders.rescheduleAll(this) }.start()
    }
}
