package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.LightJson
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.summarize.WeeklyDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weekly digest reads dates first, then only the week's meetings in full. */
class DigestWeekLoadTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun meeting(id: String, ageDays: Long): Meeting =
        Meeting(id = id, title = "Meeting $id", createdAtMs = now - ageDays * day).apply {
            summary = "Summary of $id"
            actionItems.add(ActionItem("Task for $id", "Ana"))
            segments.add(
                TranscriptSegment(
                    timestampMs = createdAtMs,
                    text = "hello from $id",
                    words = listOf(WordStamp(0L, "hello"))
                )
            )
        }

    private val library = listOf(
        meeting("old", 30),
        meeting("tue", 2),
        meeting("edge", 8),
        meeting("mon", 3),
        meeting("today", 0)
    )

    /** As MeetingStore.listPartial(setOf("createdAtMs")) reads a meeting file. */
    private fun dated(meeting: Meeting): Meeting =
        Meeting.fromJson(
            LightJson.parse(meeting.toJson().toString(), setOf("createdAtMs", "id"), setOf("words"))
        )

    @Test
    fun datedCopiesCarryOnlyIdAndDate() {
        val copy = dated(library[1])
        assertEquals("tue", copy.id)
        assertEquals(library[1].createdAtMs, copy.createdAtMs)
        assertTrue(copy.segments.isEmpty())
        assertTrue(copy.actionItems.isEmpty())
        assertEquals("", copy.summary)
    }

    @Test
    fun loadsOnlyTheWeekAndMatchesAFullListing() {
        val byId = library.associateBy { it.id }
        val loaded = mutableListOf<String>()
        val week = WeeklyDigest.loadWeek(library.map { dated(it) }, now) { id ->
            loaded.add(id)
            byId[id]
        }
        assertEquals(listOf("mon", "tue", "today"), loaded)
        assertEquals(WeeklyDigest.weekMeetings(library, now), week)
        // Full copies, not the dated ones: the digest reads all of these.
        assertEquals("Summary of mon", week.first().summary)
        assertEquals("Task for mon", week.first().actionItems.single().task)
        assertEquals("hello from mon", week.first().segments.single().text)
    }

    @Test
    fun meetingDeletedBetweenTheReadsIsLeftOut() {
        val byId = library.associateBy { it.id } - "tue"
        val week = WeeklyDigest.loadWeek(library.map { dated(it) }, now) { byId[it] }
        assertEquals(listOf("mon", "today"), week.map { it.id })
    }

    @Test
    fun windowIsCheckedAgainOnTheFullCopy() {
        // Replaced (a restore) between the two reads with an older meeting.
        val week = WeeklyDigest.loadWeek(library.map { dated(it) }, now) { id ->
            if (id == "mon") meeting("mon", 40) else library.first { it.id == id }
        }
        assertEquals(listOf("tue", "today"), week.map { it.id })
    }

    @Test
    fun emptyLibraryLoadsNothing() {
        var calls = 0
        val week = WeeklyDigest.loadWeek(emptyList(), now) { calls++; null }
        assertTrue(week.isEmpty())
        assertEquals(0, calls)
    }
}
