package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.search.MeetingGroups
import com.meetily.mobile.summarize.WeeklyDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingGroupsTest {

    private val now = 1_800_000_000_000L

    private fun meeting(id: String, title: String, ageDays: Long = 1): Meeting =
        Meeting(id = id, title = title, createdAtMs = now - ageDays * 86_400_000L)

    @Test
    fun normalizeStripsDatesNumbersAndWeekdays() {
        assertEquals("team standup", MeetingGroups.normalizeTitle("Team Standup 3/14"))
        assertEquals("team standup", MeetingGroups.normalizeTitle("team standup — Mar 21, 10am"))
        assertEquals("team standup", MeetingGroups.normalizeTitle("TEAM STANDUP #12 (Friday)"))
    }

    @Test
    fun seriesGroupsMatchingTitlesAndIgnoresSingles() {
        val meetings = listOf(
            meeting("a", "Team Standup 3/14", ageDays = 8),
            meeting("b", "Team standup — Mar 21", ageDays = 1),
            meeting("c", "Board review", ageDays = 3)
        )
        val series = MeetingGroups.series(meetings)
        assertEquals(1, series.size)
        assertEquals("team standup", series.first().key)
        assertEquals(2, series.first().meetings.size)
        // Newest member first within the series.
        assertEquals("b", series.first().meetings.first().id)
    }

    @Test
    fun seriesDisplayNameIsTheShortestVariant() {
        val meetings = listOf(
            meeting("a", "Design crit 5/2 extended session"),
            meeting("b", "Design crit")
        )
        assertEquals("Design crit", MeetingGroups.series(meetings).first().displayName)
    }

    // --- Weekly digest selection & action-item grouping ---------------------

    @Test
    fun weekMeetingsKeepsOnlyTheWindow() {
        val meetings = listOf(
            meeting("in", "Recent", ageDays = 2),
            meeting("edge", "Old", ageDays = 8),
            meeting("in2", "Today", ageDays = 0)
        )
        val week = WeeklyDigest.weekMeetings(meetings, now)
        assertEquals(listOf("in", "in2"), week.map { it.id })
    }

    @Test
    fun openActionItemsGroupByOwnerAndSkipDone() {
        val m1 = meeting("a", "Sync").apply {
            actionItems.add(ActionItem("send deck", "Priya", done = false))
            actionItems.add(ActionItem("book room", null, done = false))
            actionItems.add(ActionItem("done thing", "Priya", done = true))
        }
        val m2 = meeting("b", "Planning").apply {
            actionItems.add(ActionItem("draft budget", "priya ", done = false))
        }
        val grouped = WeeklyDigest.openActionItems(listOf(m1, m2))
        assertTrue(grouped.keys.contains(null))
        // Owner keys are trimmed but case-preserving; assert on totals.
        val allTasks = grouped.values.flatten().map { it.first }
        assertEquals(3, allTasks.size)
        assertTrue("done thing" !in allTasks)
        assertTrue(allTasks.containsAll(listOf("send deck", "book room", "draft budget")))
    }
}
