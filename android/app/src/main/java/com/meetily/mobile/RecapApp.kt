package com.meetily.mobile

import android.app.Application
import com.meetily.mobile.data.AppSettings

class RecapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Night-mode preference is process-wide state; reapply on every start.
        ThemeManager.applyNightMode(AppSettings(this).themeMode)
    }
}
