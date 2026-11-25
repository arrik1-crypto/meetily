package com.meetily.mobile.search

import com.meetily.mobile.data.Meeting
import java.text.DateFormat
import java.text.ParsePosition
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Detects recurring-meeting series by normalized title: "Team standup
 * 3/14", "Team Standup – Mar 21", and "team standup" all fold to one key.
 * Pure Kotlin — unit-tested.
 */
object MeetingGroups {

    data class Series(
        val key: String,
        val displayName: String,
        val meetings: List<Meeting>
    )

    /**
     * Lowercases, strips digits and punctuation (dates, "#12", "3/14"),
     * removes month names and weekday names, and collapses whitespace.
     */
    fun normalizeTitle(title: String): String {
        return title.lowercase(Locale.ROOT)
            .replace(NON_LETTERS, " ")
            .split(WHITESPACE)
            .filter { it.isNotBlank() && it !in NOISE }
            .joinToString(" ")
    }

    // Built once: normalizeTitle runs per meeting on every library refresh,
    // chip tap and series-filtered keystroke, and compiled both patterns and
    // rebuilt this set on every call.
    private val NOISE = setOf(
        "jan", "january", "feb", "february", "mar", "march", "apr",
        "april", "may", "jun", "june", "jul", "july", "aug", "august",
        "sep", "sept", "september", "oct", "october", "nov", "november",
        "dec", "december", "mon", "monday", "tue", "tuesday", "wed",
        "wednesday", "thu", "thursday", "fri", "friday", "sat",
        "saturday", "sun", "sunday", "am", "pm"
    )
    private val NON_LETTERS = Regex("[^\\p{L} ]+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Recognises untitled recordings, which are never a series.
     *
     * An untitled recording is called default_meeting_title with its own
     * start time in it (RecordingService), written the way the phone's
     * language writes dates. Taken as one series, a style picked by hand
     * for one of them became the automatic summary style of all of them,
     * and each was the others' "last time". Normalizing removes a date only
     * where it is digits and English month names, so one fixed key does not
     * catch them: "Meeting 25 de nov. de 2026 10:30" (Portuguese) folds to
     * "meeting de nov de", and a French February recording to "meeting
     * févr". So a meeting is untitled when its title normalizes as the
     * default title for its own start time does (normalized, as the
     * recording screen stamps the title when it opens, which can be a
     * minute or more before the recording starts), or when the title is
     * that default with any date and time that one of [locales] writes: a
     * screen left open across midnight, a time zone change, or a phone
     * language changed since while the old one is still in the phone's list.
     *
     * [titleFor] is default_meeting_title formatted with a date; it is
     * called once here and not kept. Holds DateFormats, so one thread at a
     * time: make one per use.
     */
    class Untitled(
        titleFor: (String) -> String,
        locales: List<Locale> = listOf(Locale.getDefault()),
        timeZone: TimeZone = TimeZone.getDefault()
    ) {
        /** default_meeting_title before and after where the date goes. */
        private val before: String
        private val after: String

        init {
            val marked = titleFor(MARK)
            val at = marked.indexOf(MARK)
            before = if (at >= 0) marked.substring(0, at) else marked
            after = if (at >= 0) marked.substring(at + MARK.length) else ""
        }

        private val keyBefore = normalizeTitle(before)
        private val keyAfter = normalizeTitle(after)

        /** As RecordingService formats the date, the phone's own language first. */
        private val formats: List<DateFormat> =
            locales.ifEmpty { listOf(Locale.getDefault()) }.distinct().map { locale ->
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                    .also { format ->
                        format.timeZone = timeZone
                        format.isLenient = false
                    }
            }

        /** What a recording started at [createdAtMs] is called until it is named. */
        fun defaultTitle(createdAtMs: Long): String =
            before + formats.first().format(Date(createdAtMs)) + after

        /** Whether the meeting called [title], started at [createdAtMs], is untitled. */
        fun isUntitled(title: String, createdAtMs: Long): Boolean {
            val key = normalizeTitle(title)
            // Cheap first, as this runs per meeting: an untitled key starts
            // and ends as the default title's does.
            if (!key.startsWith(keyBefore) || !key.endsWith(keyAfter)) return false
            return key == normalizeTitle(defaultTitle(createdAtMs)) || hasDefaultForm(title)
        }

        /**
         * Whether [title] is the default title with some date and time in
         * it, written as one of the locales writes them. Needs no meeting,
         * so it also serves a title on its own (a pre-meeting brief's).
         */
        fun hasDefaultForm(title: String): Boolean {
            if (title.length <= before.length + after.length) return false
            if (!title.startsWith(before) || !title.endsWith(after)) return false
            val date = title.substring(before.length, title.length - after.length).trim()
            if (date.isEmpty()) return false
            return formats.any { format ->
                val at = ParsePosition(0)
                val parsed = try {
                    format.parse(date, at)
                } catch (_: RuntimeException) {
                    null
                }
                parsed != null && at.index == date.length
            }
        }

        companion object {
            /** Stands for the date while default_meeting_title is split. */
            private const val MARK = ""

            /** Each one is tried per title that looks like a default one. */
            private const val MAX_LOCALES = 4

            /** For this phone: the app's default_meeting_title and [deviceLocales]. */
            fun of(context: android.content.Context): Untitled = Untitled(
                { context.getString(com.meetily.mobile.R.string.default_meeting_title, it) },
                deviceLocales()
            )

            /**
             * The phone's languages: the app's own first (it formatted
             * every title made since it was set), then the rest of the
             * user's list and the system's.
             */
            fun deviceLocales(): List<Locale> {
                val found = LinkedHashSet<Locale>()
                found += Locale.getDefault()
                try {
                    val app = android.os.LocaleList.getDefault()
                    for (i in 0 until app.size()) found += app.get(i)
                    val system = android.content.res.Resources.getSystem().configuration.locales
                    for (i in 0 until system.size()) found += system.get(i)
                } catch (_: Throwable) {
                    // The app's own language alone covers the common case.
                }
                return found.take(MAX_LOCALES)
            }
        }
    }

    /**
     * Groups meetings whose normalized titles match, keeping only groups
     * with at least [minSize] members, newest series first. Untitleable
     * meetings (normalized title blank) are ignored, and so are untitled
     * recordings when [untitled] is given.
     */
    fun series(
        meetings: List<Meeting>,
        minSize: Int = 2,
        untitled: Untitled? = null
    ): List<Series> {
        val groups = meetings
            .map { normalizeTitle(it.title) to it }
            .filter { (key, meeting) ->
                key.isNotBlank() &&
                    untitled?.isUntitled(meeting.title, meeting.createdAtMs) != true
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= minSize }
        return groups.map { (key, members) ->
            val sorted = members.sortedByDescending { it.createdAtMs }
            Series(
                key = key,
                // Shortest original title reads best as the series name
                // ("Team standup" beats "Team standup 3/14 notes").
                displayName = members.minByOrNull { it.title.length }?.title ?: key,
                meetings = sorted
            )
        }.sortedByDescending { it.meetings.first().createdAtMs }
    }
}
