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

    data class CalendarEvent(
        val eventId: Long,
        val title: String,
        val beginMs: Long,
        val endMs: Long
    )

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
                    if (title.isBlank()) continue
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
