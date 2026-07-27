package com.meetily.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.meetily.mobile.data.CalendarHelper
import com.meetily.mobile.data.FollowsEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Does this follow on from an earlier meeting?" — picks one past calendar
 * occurrence and hands back a snapshot of it.
 *
 * The calendar is read here and only here. What the caller stores is plain
 * text and two numbers, so nothing downstream ever needs the permission
 * again, and the link survives the event being edited, deleted, or the
 * whole account being removed.
 */
object CalendarFollowSheet {

    private const val DEFAULT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    private const val WIDER_WINDOW_MS = 60L * 24 * 60 * 60 * 1000

    /**
     * @param beforeMs the recording's own start — the events worth offering
     *   are the ones that preceded it, which is not the same as preceding now
     *   when the link is made days later.
     * @param onPick receives the chosen snapshot, or null to clear the link.
     */
    fun show(
        activity: Activity,
        beforeMs: Long,
        hasExistingLink: Boolean,
        onPick: (FollowsEvent?) -> Unit
    ) {
        // Checked here rather than inferred from an empty result: at every
        // CalendarHelper call site a revoked permission and an empty calendar
        // look exactly the same, so "no events" must never be reported as
        // "you denied access" or the reverse.
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            explainPermission(activity)
            return
        }
        load(activity, beforeMs, DEFAULT_WINDOW_MS, hasExistingLink, onPick)
    }

    private fun load(
        activity: Activity,
        beforeMs: Long,
        windowMs: Long,
        hasExistingLink: Boolean,
        onPick: (FollowsEvent?) -> Unit
    ) {
        // Off the main thread: this is a ContentResolver query over a
        // provider that can be slow on a phone with several synced accounts.
        Thread {
            val events = CalendarHelper.pastEvents(activity, beforeMs, windowMs)
            Handler(Looper.getMainLooper()).post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                present(activity, beforeMs, windowMs, events, hasExistingLink, onPick)
            }
        }.start()
    }

    private fun present(
        activity: Activity,
        beforeMs: Long,
        windowMs: Long,
        events: List<CalendarHelper.CalendarEvent>,
        hasExistingLink: Boolean,
        onPick: (FollowsEvent?) -> Unit
    ) {
        val stamp = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
        val items = mutableListOf<ActionSheet.Item>()
        for (event in events) {
            items.add(
                ActionSheet.Item(
                    id = "e:${event.eventId}:${event.beginMs}",
                    title = event.title,
                    subtitle = stamp.format(Date(event.beginMs))
                )
            )
        }
        if (events.isEmpty()) {
            items.add(
                ActionSheet.Item(
                    id = "none",
                    title = activity.getString(R.string.follow_no_events),
                    subtitle = activity.getString(R.string.follow_no_events_sub),
                    enabled = false
                )
            )
        }
        if (windowMs < WIDER_WINDOW_MS) {
            items.add(
                ActionSheet.Item(
                    id = "wider",
                    title = activity.getString(R.string.follow_look_further),
                    separated = true
                )
            )
        }
        if (hasExistingLink) {
            items.add(
                ActionSheet.Item(
                    id = "clear",
                    title = activity.getString(R.string.follow_remove_link),
                    destructive = true,
                    separated = true
                )
            )
        }
        ActionSheet.show(
            activity, activity.getString(R.string.follow_sheet_title), items
        ) { id ->
            when {
                id == "wider" ->
                    load(activity, beforeMs, WIDER_WINDOW_MS, hasExistingLink, onPick)
                id == "clear" -> onPick(null)
                id.startsWith("e:") -> {
                    val parts = id.split(":")
                    val eventId = parts.getOrNull(1)?.toLongOrNull() ?: return@show
                    val begin = parts.getOrNull(2)?.toLongOrNull() ?: return@show
                    val event = events.firstOrNull {
                        it.eventId == eventId && it.beginMs == begin
                    } ?: return@show
                    // The snapshot is taken here. Nothing after this point
                    // reads the calendar again for this link.
                    onPick(FollowsEvent(event.title, event.beginMs, event.eventId))
                }
            }
        }
    }

    private fun explainPermission(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.follow_needs_calendar_title)
            .setMessage(R.string.follow_needs_calendar_body)
            .setPositiveButton(R.string.cal_diag_open_settings) { _, _ ->
                try {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null)
                        )
                    )
                } catch (_: Exception) {
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
