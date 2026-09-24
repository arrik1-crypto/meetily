package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.Chapter
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.data.QaEntry
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
        // Two lines landed while the screen was away, and the disk copy does
        // not necessarily list them in order.
        val screen = meeting(seg(10, "ten"))
        val disk = meeting(seg(10, "ten"), seg(30, "thirty"), seg(20, "twenty"))
        assertEquals(2, MeetingMerge.foldLateSegments(screen, disk, afterMs = 10L))
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

    // --- mergeScreenEdits: a screen writes only what it changed -------------

    @Test
    fun aScreenEditKeepsASummaryThatLandedMeanwhile() {
        val base = meeting(seg(1, "one"))
        val ours = MeetingMerge.snapshot(base).apply { starred = true }
        val disk = MeetingMerge.snapshot(base).apply {
            summary = "S2"
            actionItems.add(ActionItem("send notes"))
        }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, 1L)
        assertTrue(merged.starred)
        assertEquals("S2", merged.summary)
        assertEquals(listOf("send notes"), merged.actionItems.map { it.task })
    }

    @Test
    fun aNewSummaryTakesItsOwnActionItemsOverATickOnTheOldList() {
        val base = meeting().apply {
            summary = "S1"
            actionItems.add(ActionItem("old item"))
        }
        val ours = MeetingMerge.snapshot(base).apply {
            actionItems[0] = actionItems[0].copy(done = true)
        }
        val disk = MeetingMerge.snapshot(base).apply {
            summary = "S2"
            actionItems.clear()
            actionItems.add(ActionItem("new item"))
        }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("S2", merged.summary)
        assertEquals(listOf("new item"), merged.actionItems.map { it.task })
    }

    @Test
    fun anUntouchedTranscriptTakesTheStoredOneAsItIs() {
        // An accepted accuracy check rewrote the lines under an open screen.
        val base = meeting(seg(1, "one"), seg(2, "too"))
        val ours = MeetingMerge.snapshot(base).apply { title = "Renamed" }
        val disk = meeting(seg(1, "one"), seg(2, "two"))
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, 2L)
        assertEquals("Renamed", merged.title)
        assertEquals(listOf("one", "two"), merged.segments.map { it.text })
    }

    @Test
    fun anEditedTranscriptWinsButKeepsALateLine() {
        val base = meeting(seg(1, "one"), seg(2, "two"))
        val ours = MeetingMerge.snapshot(base).apply { segments.removeAt(0) }
        val disk = meeting(seg(1, "one"), seg(2, "two"), seg(3, "late"))
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, 2L)
        assertEquals(listOf("two", "late"), merged.segments.map { it.text })
    }

    @Test
    fun answersAndChaptersFromElsewhereSurviveButAClearHereSticks() {
        val base = meeting(seg(1, "one")).apply { chapters.add(Chapter("Intro", 1L)) }
        val ours = MeetingMerge.snapshot(base).apply { chapters.clear() }
        val disk = MeetingMerge.snapshot(base).apply { qa.add(QaEntry("q", "a")) }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, 1L)
        assertTrue(merged.chapters.isEmpty())
        assertEquals(listOf("q"), merged.qa.map { it.question })
    }

    @Test
    fun aSnapshotDoesNotShareCollectionsWithTheLiveCopy() {
        val live = meeting(seg(1, "one"))
        val copy = MeetingMerge.snapshot(live)
        live.segments.add(seg(2, "two"))
        live.photoTexts["p.jpg"] = "text"
        assertEquals(1, copy.segments.size)
        assertTrue(copy.photoTexts.isEmpty())
    }

    @Test
    fun theFingerprintMovesWithWordsNotWithTags() {
        val lines = listOf(seg(1, "one"), seg(2, "two"))
        val tagged = lines.map { it.copy(speaker = "Ana", highlighted = true) }
        val edited = listOf(seg(1, "one"), seg(2, "too"))
        assertEquals(
            MeetingMerge.transcriptFingerprint(lines),
            MeetingMerge.transcriptFingerprint(tagged)
        )
        assertTrue(
            MeetingMerge.transcriptFingerprint(lines) !=
                MeetingMerge.transcriptFingerprint(edited)
        )
    }
}
