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
     * Groups meetings whose normalized titles match, keeping only groups
     * with at least [minSize] members, newest series first. Untitleable
     * meetings (normalized title blank) are ignored.
     */
    fun series(meetings: List<Meeting>, minSize: Int = 2): List<Series> {
        val groups = meetings
            .map { normalizeTitle(it.title) to it }
            .filter { it.first.isNotBlank() }
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
