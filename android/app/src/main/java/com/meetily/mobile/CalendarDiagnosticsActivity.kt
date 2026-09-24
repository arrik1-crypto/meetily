package com.meetily.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.CalendarHelper
import com.meetily.mobile.reminders.NudgeState
import com.meetily.mobile.reminders.Reminders
import java.text.DateFormat
import java.util.Date

/**
 * "Why didn't my meeting nudge fire?" — answered from inside the app.
 *
 * Lists every calendar Android actually exposes to this app, how many timed
 * events each holds in the next week, and which meetings currently have an
 * alarm armed. A work calendar that never reaches Android's calendar
 * database (Outlook with calendar sync off, an Intune policy blocking
 * native sync, or a managed work profile) simply does not appear here —
 * which distinguishes an app bug from an account that is not syncing.
 */
class CalendarDiagnosticsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    /** Bumped per render, so a slow earlier gather cannot paint over a newer one. */
    private var renderGeneration = 0
    private val timeFormat: DateFormat by lazy {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_calendar_diag)
        findViewById<MaterialToolbar>(R.id.calDiagToolbar).setNavigationOnClickListener {
            finish()
        }
        container = findViewById(R.id.calDiagContent)
        findViewById<android.view.View>(R.id.calDiagRecheck).setOnClickListener {
            Thread {
                try {
                    Reminders.scheduleNextCalendarNudge(applicationContext)
                } catch (_: Exception) {
                }
                runOnUiThread { if (!isFinishing && !isDestroyed) render() }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Everything the screen shows, gathered off the main thread. */
    private class Snapshot(
        val nudgesOn: Boolean,
        val hasPermission: Boolean,
        /** Null below Android 12, where exact alarms need no permission. */
        val exactAlarms: Boolean?,
        val calendars: List<CalendarHelper.CalendarInfo>,
        val armed: List<NudgeState.Armed>,
        val updatedMs: Long
    )

    /**
     * The calendar queries run on a worker thread: they are cross-process
     * calls that can expand recurring events, and this runs on every resume,
     * including each return from the settings screens this page links to.
     */
    private fun render() {
        val generation = ++renderGeneration
        val app = applicationContext
        Thread {
            val hasPermission = ContextCompat.checkSelfPermission(
                app, Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
            val exact = if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(android.content.Context.ALARM_SERVICE)
                    as android.app.AlarmManager).canScheduleExactAlarms()
            } else {
                null
            }
            val snapshot = Snapshot(
                nudgesOn = AppSettings(app).meetingNudges,
                hasPermission = hasPermission,
                exactAlarms = exact,
                calendars = if (hasPermission) CalendarHelper.calendars(app) else emptyList(),
                armed = NudgeState.armed(app),
                updatedMs = NudgeState.lastUpdatedMs(app)
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != renderGeneration) {
                    return@runOnUiThread
                }
                show(snapshot)
            }
        }.start()
    }

    private fun show(snapshot: Snapshot) {
        container.removeAllViews()
        val hasPermission = snapshot.hasPermission

        // 1. The two switches that gate everything.
        section(getString(R.string.cal_diag_setup))
        row(
            getString(R.string.cal_diag_nudges_setting),
            if (snapshot.nudgesOn) getString(R.string.cal_diag_on)
            else getString(R.string.cal_diag_off),
            good = snapshot.nudgesOn
        )
        row(
            getString(R.string.cal_diag_permission),
            if (hasPermission) getString(R.string.cal_diag_granted)
            else getString(R.string.cal_diag_denied),
            good = hasPermission
        )
        val exact = snapshot.exactAlarms
        // The SDK check is redundant with the null but keeps lint's NewApi
        // analysis able to see the guard around the API-31 intent below.
        if (Build.VERSION.SDK_INT >= 31 && exact != null) {
            row(
                getString(R.string.cal_diag_exact_alarms),
                if (exact) getString(R.string.cal_diag_granted)
                else getString(R.string.cal_diag_exact_denied),
                good = exact
            )
            if (!exact) {
                note(getString(R.string.cal_diag_exact_help))
                actionButton(getString(R.string.cal_diag_exact_open)) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(android.net.Uri.parse("package:$packageName"))
                        )
                    } catch (_: Exception) {
                        openAppSettings()
                    }
                }
            }
        }

        if (!hasPermission) {
            note(getString(R.string.cal_diag_permission_help))
            actionButton(getString(R.string.cal_diag_open_settings)) { openAppSettings() }
            return
        }

        // 2. What Android actually exposes to this app.
        val calendars = snapshot.calendars
        section(getString(R.string.cal_diag_calendars, calendars.size))
        if (calendars.isEmpty()) {
            note(getString(R.string.cal_diag_none))
        } else {
            for (cal in calendars) {
                val name = cal.displayName.ifBlank { cal.accountName }
                val flags = buildString {
                    append(cal.providerLabel)
                    if (!cal.syncEvents) append(getString(R.string.cal_diag_flag_nosync))
                    if (!cal.visible) append(getString(R.string.cal_diag_flag_hidden))
                }
                row(
                    "$name\n$flags",
                    getString(R.string.cal_diag_event_count, cal.upcomingCount),
                    good = cal.syncEvents && cal.upcomingCount > 0
                )
            }
        }
        val hasWork = calendars.any {
            it.providerLabel.startsWith("Outlook", ignoreCase = true)
        }
        if (!hasWork) note(getString(R.string.cal_diag_no_outlook))

        // 3. What is actually armed right now.
        val armed = snapshot.armed
        section(getString(R.string.cal_diag_armed))
        if (armed.isEmpty()) {
            note(getString(R.string.cal_diag_armed_none))
        } else {
            for (item in armed) {
                row(item.title, timeFormat.format(Date(item.beginMs)), good = true)
            }
        }
        val updated = snapshot.updatedMs
        if (updated > 0) {
            note(getString(R.string.cal_diag_checked_at, timeFormat.format(Date(updated))))
        }
        note(getString(R.string.cal_diag_footer))
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    // --- tiny view builders (keeps the screen a single file) ----------------

    private val density: Float get() = resources.displayMetrics.density

    private fun section(title: String) {
        container.addView(
            TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorPrimary
                    )
                )
                setPadding(0, (18 * density).toInt(), 0, (6 * density).toInt())
            }
        )
    }

    private fun row(label: String, value: String, good: Boolean) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }
        line.addView(
            TextView(this).apply {
                text = label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnSurface
                    )
                )
            }
        )
        line.addView(
            TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this,
                        if (good) com.google.android.material.R.attr.colorPrimary
                        else com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
            }
        )
        container.addView(line)
    }

    private fun note(text: String) {
        container.addView(
            TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
        )
    }

    private fun actionButton(text: String, onClick: () -> Unit) {
        container.addView(
            com.google.android.material.button.MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                this.text = text
                setOnClickListener { onClick() }
            }
        )
    }
}
