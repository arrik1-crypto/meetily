package com.meetily.mobile.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
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
            queryMeetings(
                context, uriBuilder.build(), projection,
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
                    out.add(
                        CalendarInfo(
                            id = cursor.getLong(0),
                            displayName = cursor.getString(1)?.trim().orEmpty(),
                            accountName = cursor.getString(2)?.trim().orEmpty(),
                            accountType = cursor.getString(3)?.trim().orEmpty(),
                            visible = cursor.getInt(4) != 0,
                            syncEvents = cursor.getInt(5) != 0,
                            upcomingCount = 0
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) return out
        // Counted after the Calendars cursor is closed, in one query for all
        // calendars: one Instances query per calendar was 1+N provider round
        // trips, each nested inside the still-open outer cursor.
        val counts = countUpcoming(context)
        return out.map { it.copy(upcomingCount = counts[it.id] ?: 0) }
    }

    /** Events per calendar over the next 7 days, keyed by calendar id. */
    private fun countUpcoming(context: Context): Map<Long, Int> {
        val now = System.currentTimeMillis()
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, now)
        ContentUris.appendId(uriBuilder, now + 7L * 24 * 60 * 60 * 1000)
        val counts = HashMap<Long, Int>()
        try {
            context.contentResolver.query(
                uriBuilder.build(),
                arrayOf(CalendarContract.Instances.CALENDAR_ID),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    counts[id] = (counts[id] ?: 0) + 1
                }
            }
        } catch (_: Exception) {
        }
        return counts
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
            queryMeetings(
                context, uriBuilder.build(), projection,
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

    /**
     * Events that finished before [beforeMs], newest first — the candidates
     * for "this conversation follows on from…".
     *
     * [beforeMs] is the meeting's own start, not now: a recording may be
     * linked days after it happened, and the events worth offering are the
     * ones that preceded the RECORDING.
     *
     * The projection is deliberately identical to [findCurrentEvents]. Every
     * query in this file swallows its exception and returns an empty list, so
     * a column that one OEM's Instances view rejects is indistinguishable
     * from "no events" — and adding one here could silently kill calendar
     * prefill and the nudges along with it.
     */
    fun pastEvents(
        context: Context,
        beforeMs: Long,
        windowMs: Long = 14L * 24 * 60 * 60 * 1000,
        limit: Int = 40
    ): List<CalendarEvent> {
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, beforeMs - windowMs)
        ContentUris.appendId(uriBuilder, beforeMs)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        val events = mutableListOf<CalendarEvent>()
        try {
            queryMeetings(
                context, uriBuilder.build(), projection,
                CalendarContract.Instances.BEGIN + " DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < limit) {
                    if (cursor.getInt(4) != 0) continue // all-day: not a meeting
                    val begin = cursor.getLong(2)
                    // The Instances window is inclusive at both ends, so the
                    // recording's own event would otherwise offer itself.
                    if (begin >= beforeMs) continue
                    val title = cursor.getString(1)?.trim().orEmpty().ifBlank { UNTITLED }
                    events.add(
                        CalendarEvent(cursor.getLong(0), title, begin, cursor.getLong(3))
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return events
    }

    /**
     * Rows that are the user's own meetings: on a calendar they display, not
     * declined by them, not cancelled. Instances otherwise returns every
     * synced calendar — a manager's calendar synced but hidden, shared team
     * calendars, invitations turned down — and those were taking nudge slots
     * from the user's own meetings and lending their guest lists to
     * recordings they had nothing to do with.
     */
    private val MEETING_SELECTION =
        "${CalendarContract.Instances.VISIBLE} = 1" +
            " AND (${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL" +
            " OR ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != " +
            "${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})" +
            " AND (${CalendarContract.Instances.STATUS} IS NULL" +
            " OR ${CalendarContract.Instances.STATUS} != " +
            "${CalendarContract.Events.STATUS_CANCELED})"

    /**
     * An Instances query filtered by [MEETING_SELECTION], falling back to the
     * unfiltered query if the provider rejects it. Every caller here turns an
     * exception into "no events", so a column one OEM's view lacks must not
     * be allowed to switch off prefill and nudges altogether — seeing a
     * hidden calendar's event beats seeing none.
     */
    private fun queryMeetings(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        sortOrder: String
    ): Cursor? = try {
        context.contentResolver.query(uri, projection, MEETING_SELECTION, null, sortOrder)
    } catch (_: Exception) {
        context.contentResolver.query(uri, projection, null, null, sortOrder)
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
