package com.meetily.mobile.export

import com.meetily.mobile.data.ElapsedTime
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.summarize.MeetingStats
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Facts about a meeting that the Word document and the library's Markdown
 * front matter both state, worked out once so they agree. Pure Kotlin,
 * unit-tested.
 */
object ExportFacts {

    /** The last word takes time to say after it starts (as MeetingStats). */
    private const val WORD_TAIL_MS = 600L

    /**
     * How far into the recording the last transcript line ends, or null
     * when no line says anything. Measured from the transcript, as the
     * transcript labels are (ElapsedTime), so silence after the last line
     * is not counted: an estimate of the length, not the file's duration.
     */
    fun durationMs(meeting: Meeting): Long? {
        var latest: Long? = null
        for (segment in meeting.segments) {
            if (segment.text.isBlank()) continue
            val at = ElapsedTime.offsetMs(segment, meeting.createdAtMs)
            val words = segment.words
            val end = if (segment.audioMs != null && !words.isNullOrEmpty()) {
                at + words.last().ms.coerceAtLeast(0L) + WORD_TAIL_MS
            } else {
                at + MeetingStats.estimateDurationMs(segment.text, words)
            }
            if (latest == null || end > latest) latest = end
        }
        return latest
    }

    /** "0:42:10", the form the transcript's own labels use. */
    fun durationLabel(ms: Long): String = ElapsedTime.format(ms)

    /**
     * [ms] as ISO 8601 with the offset of [zone], to the second:
     * "2026-11-22T14:03:05+01:00". The offset keeps the moment exact for a
     * reader in another time zone.
     */
    fun isoDateTime(ms: Long, zone: ZoneId): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** [ms] in UTC to the second, "2026-11-22T13:03:05Z" (document metadata). */
    fun isoUtc(ms: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS))
}
