package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen holding a meeting open must not erase a transcript line the
 * recording service appended underneath it — nor lose the user's edits by
 * taking the disk copy wholesale.
 */
class MeetingMergeTest {

    private fun seg(t: Long, text: String) = TranscriptSegment(timestampMs = t, text = text)

    private fun meeting(vararg segments: TranscriptSegment) = Meeting(
        id = "m1",
        title = "Meeting",
        createdAtMs = 0L,
        segments = segments.toMutableList()
    )

    @Test
    fun aSegmentOnlyOnDiskIsLate() {
        val known = listOf(seg(1, "one"), seg(2, "two"))
        val disk = listOf(seg(1, "one"), seg(2, "two"), seg(3, "three"))
        assertEquals(listOf("three"), MeetingMerge.lateSegments(known, disk).map { it.text })
    }

    @Test
    fun nothingIsLateWhenBothSidesMatch() {
        val known = listOf(seg(1, "one"), seg(2, "two"))
        assertTrue(MeetingMerge.lateSegments(known, known).isEmpty())
    }

    @Test
    fun anEditedLineIsNotMistakenForANewOne() {
        val known = listOf(seg(1, "corrected text"))
        val disk = listOf(seg(1, "original text"))
        assertTrue(MeetingMerge.lateSegments(known, disk).isEmpty())
    }

    @Test
    fun aSplitLineIsNotMistakenForALostOne() {
        // Splitting produces two segments sharing one timestamp; the single
        // disk segment must still count as known.
        val known = listOf(seg(1, "first half"), seg(1, "second half"), seg(2, "next"))
        val disk = listOf(seg(1, "first half second half"), seg(2, "next"))
        assertTrue(MeetingMerge.lateSegments(known, disk).isEmpty())
    }

    @Test
    fun foldKeepsLocalEditsAndAppendsTheLateLine() {
        val screen = meeting(seg(1, "corrected text"))
        val disk = meeting(seg(1, "original text"), seg(2, "arrived late"))
        assertEquals(1, MeetingMerge.foldLateSegments(screen, disk))
        assertEquals(
            listOf("corrected text", "arrived late"),
            screen.segments.map { it.text }
        )
    }

    @Test
    fun foldPutsLateLinesInTimeOrder() {
        val screen = meeting(seg(10, "ten"), seg(30, "thirty"))
        val disk = meeting(seg(10, "ten"), seg(20, "twenty"), seg(30, "thirty"))
        MeetingMerge.foldLateSegments(screen, disk)
        assertEquals(listOf(10L, 20L, 30L), screen.segments.map { it.timestampMs })
    }

    @Test
    fun foldIsANoOpWhenDiskHasNothingNew() {
        val screen = meeting(seg(1, "one"))
        val disk = meeting(seg(1, "one"))
        assertEquals(0, MeetingMerge.foldLateSegments(screen, disk))
        assertEquals(1, screen.segments.size)
    }

    @Test
    fun anEmptyDiskCopyNeverRemovesAnything() {
        val screen = meeting(seg(1, "one"), seg(2, "two"))
        assertEquals(0, MeetingMerge.foldLateSegments(screen, meeting()))
        assertEquals(2, screen.segments.size)
    }
}
