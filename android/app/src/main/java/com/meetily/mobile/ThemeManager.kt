package com.meetily.mobile

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import com.meetily.mobile.data.AppSettings

/**
 * Applies the user's chosen accent as a theme overlay before setContentView.
 * All accent usage in the app flows through ?attr/colorPrimary and the
 * primary-container roles, so one overlay retints every screen consistently
 * in light and dark mode.
 */
object ThemeManager {

    data class Accent(val key: String, val overlayRes: Int, val swatchColorRes: Int)

    val ACCENTS: List<Accent> = listOf(
        Accent("indigo", R.style.ThemeOverlay_Meetily_Indigo, R.color.accent_indigo),
        Accent("teal", R.style.ThemeOverlay_Meetily_Teal, R.color.accent_teal),
        Accent("emerald", R.style.ThemeOverlay_Meetily_Emerald, R.color.accent_emerald),
        Accent("amber", R.style.ThemeOverlay_Meetily_Amber, R.color.accent_amber),
        Accent("rose", R.style.ThemeOverlay_Meetily_Rose, R.color.accent_rose),
        Accent("graphite", R.style.ThemeOverlay_Meetily_Graphite, R.color.accent_graphite)
    )

    fun byKey(key: String): Accent = ACCENTS.firstOrNull { it.key == key } ?: ACCENTS.first()

    /** Call in every Activity.onCreate BEFORE setContentView. */
    fun apply(activity: Activity): String {
        val key = AppSettings(activity).accentColor
        activity.theme.applyStyle(byKey(key).overlayRes, true)
        return key
    }

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
