package com.meetily.mobile.data

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.util.Locale
import kotlin.math.abs

/**
 * Read-only calendar lookup: finds the event happening around "now" so a
 * recording can be pre-filled with its title and guest list.
 */
object CalendarHelper {

    /** Stand-in for events whose sync adapter left the title empty. */
    const val UNTITLED = "Untitled meeting"

    data class CalendarEvent(
        val eventId: Long,
        val title: String,
        val beginMs: Long,
        val endMs: Long
    )

    /** One row of the Calendars table, for the in-app calendar diagnostics. */
    data class CalendarInfo(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val accountType: String,
        val visible: Boolean,
        val syncEvents: Boolean,
        val upcomingCount: Int
    ) {
        /** "Google", "Outlook/Exchange", "On this device"… from the raw type. */
        val providerLabel: String
            get() = when {
                accountType.contains("google", true) -> "Google"
                accountType.contains("exchange", true) ||
                    accountType.contains("microsoft", true) ||
                    accountType.contains("outlook", true) -> "Outlook / Exchange"
                accountType.contains("LOCAL", true) -> "On this device"
                accountType.isBlank() -> "Unknown"
                else -> accountType
            }
    }

    /**
     * The next event starting more than a minute from now (within 24 h), for
     * the "meeting is starting — record?" nudge chain. Requires READ_CALENDAR.
     */
    fun nextUpcomingEvent(context: Context): CalendarEvent? =
        upcomingEvents(context, limit = 1).firstOrNull()

    /**
     * Up to [limit] events starting between a minute from now and [windowMs]
     * ahead, earliest first. The nudge chain arms one alarm per event rather
     * than a single "next event" alarm, so simultaneous and back-to-back
     * meetings each get their own nudge and the chain cannot go stale.
     */
    fun upcomingEvents(
        context: Context,
        limit: Int = 8,
        windowMs: Long = 24L * 60 * 60 * 1000
    ): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val windowStart = now + 60_000L
        val windowEnd = now + windowMs

        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, windowStart)
        ContentUris.appendId(uriBuilder, windowEnd)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        val out = mutableListOf<CalendarEvent>()
        try {
            context.contentResolver.query(
                uriBuilder.build(), projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && out.size < limit) {
                    if (cursor.getInt(4) != 0) continue // all-day
                    // Exchange/Outlook leaves TITLE null on some private or
                    // subject-less holds; skipping them meant no nudge at all
                    // for meetings that are common on a work calendar.
                    val title = cursor.getString(1)?.trim().orEmpty()
                        .ifBlank { UNTITLED }
                    val begin = cursor.getLong(2)
                    if (begin <= now) continue
                    out.add(CalendarEvent(cursor.getLong(0), title, begin, cursor.getLong(3)))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Every calendar this app can see, with a count of timed events in the
     * next 7 days. Drives the diagnostics screen: a work calendar that never
     * reaches Android's calendar database simply will not appear here, which
     * is the difference between "the app is broken" and "the account isn't
     * syncing to the device".
     */
    fun calendars(context: Context): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS
        )
        val out = mutableListOf<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null,
                CalendarContract.Calendars.ACCOUNT_NAME + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    out.add(
                        CalendarInfo(
                            id = id,
                            displayName = cursor.getString(1)?.trim().orEmpty(),
                            accountName = cursor.getString(2)?.trim().orEmpty(),
                            accountType = cursor.getString(3)?.trim().orEmpty(),
                            visible = cursor.getInt(4) != 0,
                            syncEvents = cursor.getInt(5) != 0,
                            upcomingCount = countUpcoming(context, id)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Timed events on one calendar over the next 7 days. */
    private fun countUpcoming(context: Context, calendarId: Long): Int {
        val now = System.currentTimeMillis()
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, now)
        ContentUris.appendId(uriBuilder, now + 7L * 24 * 60 * 60 * 1000)
        return try {
            context.contentResolver.query(
                uriBuilder.build(),
                arrayOf(CalendarContract.Instances.EVENT_ID),
                CalendarContract.Instances.CALENDAR_ID + " = ?",
                arrayOf(calendarId.toString()),
                null
            )?.use { it.count } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Events overlapping now (already running, or starting within 15 min),
     * sorted by how close their start is to now. Requires READ_CALENDAR.
     */
    fun findCurrentEvents(context: Context): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val windowStart = now - 6L * 60 * 60 * 1000
        val windowEnd = now + 30L * 60 * 1000

        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, windowStart)
        ContentUris.appendId(uriBuilder, windowEnd)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        val events = mutableListOf<CalendarEvent>()
        try {
            context.contentResolver.query(
                uriBuilder.build(), projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val allDay = cursor.getInt(4) != 0
                    if (allDay) continue
                    val title = cursor.getString(1)?.trim().orEmpty()
                        .ifBlank { UNTITLED }
                    val begin = cursor.getLong(2)
                    val end = cursor.getLong(3)
                    val startsSoon = begin <= now + 15L * 60 * 1000
                    val notLongOver = end >= now - 5L * 60 * 1000
                    if (startsSoon && notLongOver) {
                        events.add(CalendarEvent(cursor.getLong(0), title, begin, end))
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return events.sortedBy { abs(it.beginMs - now) }
    }

    /** Non-declined attendee display names (falls back to prettified email). */
    fun attendeesFor(context: Context, eventId: Long): List<String> {
        val names = mutableListOf<String>()
        try {
            CalendarContract.Attendees.query(
                context.contentResolver,
                eventId,
                arrayOf(
                    CalendarContract.Attendees.ATTENDEE_NAME,
                    CalendarContract.Attendees.ATTENDEE_EMAIL,
                    CalendarContract.Attendees.ATTENDEE_STATUS
                )
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val status = cursor.getInt(2)
                    if (status == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val name = cursor.getString(0)?.trim().orEmpty()
                    val display = name.ifBlank { prettifyEmail(cursor.getString(1)) }
                    if (display.isNotBlank() &&
                        names.none { it.equals(display, ignoreCase = true) }
                    ) {
                        names.add(display)
                    }
                }
            }
        } catch (_: Exception) {
            return names
        }
        return names.take(16)
    }

    private fun prettifyEmail(email: String?): String {
        val local = email?.substringBefore('@')?.trim().orEmpty()
        if (local.isBlank()) return ""
        return local.split(Regex("[._-]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercase(Locale.getDefault()) }
            }
    }
}
