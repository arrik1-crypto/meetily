package com.meetily.mobile

import com.meetily.mobile.data.ElapsedTime
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Transcript line labels: where a line sits in the recording, not what the
 * wall clock said.
 */
class ElapsedTimeTest {

    private val start = 1_700_000_000_000L

    private fun seg(timestampMs: Long, audioMs: Long? = null) =
        TranscriptSegment(timestampMs = timestampMs, text = "x", audioMs = audioMs)

    @Test
    fun audioOffsetWinsOverWallClock() {
        // The case that makes this more than cosmetic: the recording was
        // paused for five minutes, so wall-clock elapsed is 11 minutes while
        // the audio is only 6 in. Tapping the line seeks by audioMs, so the
        // label has to be the audio figure or it contradicts the seek.
        val s = seg(timestampMs = start + 11 * 60_000L, audioMs = 6 * 60_000L)
        assertEquals(6 * 60_000L, ElapsedTime.offsetMs(s, start))
        assertEquals("0:06:00", ElapsedTime.label(s, start))
    }

    @Test
    fun wallClockIsTheFallbackWhenALineHasNoAudioOffset() {
        // Hand-typed lines and some system-recognizer output never get one.
        val s = seg(timestampMs = start + 90_000L)
        assertEquals("0:01:30", ElapsedTime.label(s, start))
    }

    @Test
    fun aLineStampedBeforeItsMeetingClampsToZero() {
        // Meetings recovered after a crash can hold these. "-0:00:03" would
        // be worse than saying zero.
        val s = seg(timestampMs = start - 3_000L)
        assertEquals(0L, ElapsedTime.offsetMs(s, start))
        assertEquals("0:00:00", ElapsedTime.label(s, start))
    }

    @Test
    fun aNegativeAudioOffsetClampsToo() {
        assertEquals(0L, ElapsedTime.offsetMs(seg(start, audioMs = -1L), start))
    }

    @Test
    fun anUnknownMeetingStartReadsAsZeroRatherThanNineteenSeventy() {
        // Without the guard this would subtract from the epoch and render a
        // number in the tens of thousands of hours.
        assertEquals(0L, ElapsedTime.offsetMs(seg(start), 0L))
    }

    @Test
    fun hoursRollOverAndTheFieldIsAlwaysPresent() {
        assertEquals("0:00:00", ElapsedTime.format(0L))
        assertEquals("0:00:09", ElapsedTime.format(9_000L))
        assertEquals("0:59:59", ElapsedTime.format(59 * 60_000L + 59_000L))
        assertEquals("1:00:00", ElapsedTime.format(60 * 60_000L))
        assertEquals("2:05:07", ElapsedTime.format(2 * 3_600_000L + 5 * 60_000L + 7_000L))
        assertEquals("12:00:00", ElapsedTime.format(12 * 3_600_000L))
    }

    @Test
    fun subSecondRemaindersTruncateRatherThanRound() {
        // Matches how the player reports position, so a label and the
        // playhead never disagree by a second at the boundary.
        assertEquals("0:00:01", ElapsedTime.format(1_999L))
    }

    @Test
    fun labelsSortInTheSameOrderTheLinesDo() {
        // Fixed-width output for the first ten hours, so the column reads
        // straight down the transcript.
        val widths = listOf(0L, 61_000L, 3_600_000L, 35_999_000L)
            .map { ElapsedTime.format(it).length }
            .distinct()
        assertEquals(listOf(7), widths)
    }
}
