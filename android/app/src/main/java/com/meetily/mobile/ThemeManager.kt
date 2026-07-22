package com.meetily.mobile

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Broadsheet is the single app identity — paper/ink/cyan in the day
 * edition, deep ink ground at night — so there is no per-user accent any
 * more. This object remains the one place activities touch theming:
 * [apply] is the pre-setContentView hook (currently the base theme needs
 * no per-activity work) and [applyNightMode] switches editions.
 */
object ThemeManager {

    /** Call in every Activity.onCreate BEFORE setContentView. */
    @Suppress("UNUSED_PARAMETER")
    fun apply(activity: Activity): String = ""

    /**
     * Applies the light/dark preference process-wide. Started activities are
     * recreated automatically when the effective mode changes.
     */
    fun applyNightMode(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
