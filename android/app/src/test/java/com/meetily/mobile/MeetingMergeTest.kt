package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen holding a meeting open must not erase a transcript line the
 * recording service appended underneath it — and must not resurrect one the
 * user deleted. "On disk but not in memory" cannot tell those apart, which is
 * why the rule is anchored on a high-water mark instead.
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
    fun highWaterMs_isTheNewestTimestamp() {
        assertEquals(30L, MeetingMerge.highWaterMs(listOf(seg(10, "a"), seg(30, "b"))))
        assertEquals(Long.MIN_VALUE, MeetingMerge.highWaterMs(emptyList()))
    }

    @Test
    fun aSegmentStampedLaterThanAnythingSeenIsLate() {
        val disk = listOf(seg(1, "one"), seg(2, "two"), seg(3, "three"))
        assertEquals(
            listOf("three"),
            MeetingMerge.lateSegments(disk, afterMs = 2L).map { it.text }
        )
    }

    @Test
    fun nothingIsLateWhenDiskHasNotMovedOn() {
        val disk = listOf(seg(1, "one"), seg(2, "two"))
        assertTrue(MeetingMerge.lateSegments(disk, afterMs = 2L).isEmpty())
    }

    @Test
    fun aDeletedLineStaysDeleted() {
        // The screen removed the middle line; disk still has it. It is older
        // than the high-water mark, so it must not come back.
        val screen = meeting(seg(1, "one"), seg(3, "three"))
        val disk = meeting(seg(1, "one"), seg(2, "two"), seg(3, "three"))
        assertEquals(0, MeetingMerge.foldLateSegments(screen, disk, afterMs = 3L))
        assertEquals(listOf("one", "three"), screen.segments.map { it.text })
    }

    @Test
    fun deletingTheLastLineAlsoSticks() {
        val screen = meeting(seg(1, "one"))
        val disk = meeting(seg(1, "one"), seg(2, "two"))
        // The high-water mark is what the screen had when it last read disk,
        // not what it holds now — so the deleted tail is not "new".
        assertEquals(0, MeetingMerge.foldLateSegments(screen, disk, afterMs = 2L))
        assertEquals(listOf("one"), screen.segments.map { it.text })
    }

    @Test
    fun foldKeepsLocalEditsAndAppendsTheLateLine() {
        val screen = meeting(seg(1, "corrected text"))
        val disk = meeting(seg(1, "original text"), seg(2, "arrived late"))
        assertEquals(1, MeetingMerge.foldLateSegments(screen, disk, afterMs = 1L))
        assertEquals(
            listOf("corrected text", "arrived late"),
            screen.segments.map { it.text }
        )
    }

    @Test
    fun foldPutsLateLinesInTimeOrder() {
        val screen = meeting(seg(10, "ten"), seg(30, "thirty"))
        val disk = meeting(seg(10, "ten"), seg(30, "thirty"), seg(20, "twenty"))
        MeetingMerge.foldLateSegments(screen, disk, afterMs = 15L)
        assertEquals(listOf(10L, 20L, 30L), screen.segments.map { it.timestampMs })
    }

    @Test
    fun aSplitLineIsNotDuplicated() {
        // Splitting produces two segments sharing one timestamp; the single
        // disk segment is at the mark, not past it.
        val screen = meeting(seg(1, "first half"), seg(1, "second half"))
        val disk = meeting(seg(1, "first half second half"))
        assertEquals(0, MeetingMerge.foldLateSegments(screen, disk, afterMs = 1L))
        assertEquals(2, screen.segments.size)
    }

    @Test
    fun anEmptyDiskCopyNeverRemovesAnything() {
        val screen = meeting(seg(1, "one"), seg(2, "two"))
        assertEquals(0, MeetingMerge.foldLateSegments(screen, meeting(), afterMs = 2L))
        assertEquals(2, screen.segments.size)
    }
}
