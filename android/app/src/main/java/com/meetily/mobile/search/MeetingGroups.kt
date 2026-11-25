package com.meetily.mobile.search

import com.meetily.mobile.data.Meeting
import java.util.Locale

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
     * Whether the normalized title [key] can name a series.
     *
     * Not when blank, and not the key every untitled recording folds to:
     * they are all called [defaultTitle] ("Meeting %1$s", default_meeting_title)
     * with their date and time in it, which normalizing removes, so "Meeting
     * Nov 25, 2026, 10:30 AM" and every other one become "meeting". Taken as
     * one series, a style picked by hand for one of them became the
     * automatic summary style of all of them, and each was the others' "last
     * time". [defaultTitle] is that title with the date left empty.
     */
    fun isSeriesKey(key: String, defaultTitle: String): Boolean =
        key.isNotBlank() && key != normalizeTitle(defaultTitle)

    /**
     * Groups meetings whose normalized titles match, keeping only groups
     * with at least [minSize] members, newest series first. Untitleable
     * meetings (normalized title blank) are ignored, and so are untitled
     * recordings when [defaultTitle] is given (see [isSeriesKey]).
     */
    fun series(
        meetings: List<Meeting>,
        minSize: Int = 2,
        defaultTitle: String = ""
    ): List<Series> {
        val untitledKey = normalizeTitle(defaultTitle)
        val groups = meetings
            .map { normalizeTitle(it.title) to it }
            .filter { it.first.isNotBlank() && it.first != untitledKey }
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
