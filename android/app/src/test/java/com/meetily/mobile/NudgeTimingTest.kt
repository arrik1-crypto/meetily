package com.meetily.mobile

import com.meetily.mobile.reminders.Reminders.NudgeTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the calendar-nudge timing gate.
 *
 * The original bug: the alarm was allowed to fire up to 10 minutes late
 * (inexact alarms are the default path on Android 14+, where the exact-alarm
 * permission is denied unless granted), but the receiver only posted the
 * notification when an event started within ±3 minutes of "now". Every late
 * delivery silently produced nothing.
 */
class NudgeTimingTest {

    private val minute = 60_000L
    private val start = 1_800_000_000_000L
    private val end = start + 30 * minute

    @Test
    fun acceptanceWindowCoversTheWorstCaseLateAlarm() {
        // Latest an inexact alarm armed at start-LEAD can plausibly land.
        val latest = start + NudgeTiming.INEXACT_WINDOW_MS - NudgeTiming.LEAD_MS
        assertTrue(
            "a late-but-legal alarm must still nudge",
            NudgeTiming.accepts(start, end, latest)
        )
        // The acceptance window must strictly exceed the alarm window, or the
        // exact class of bug this fixes comes straight back.
        assertTrue(NudgeTiming.LATE_TOLERANCE_MS > NudgeTiming.INEXACT_WINDOW_MS)
    }

    @Test
    fun acceptsOnTimeAndSlightlyEarly() {
        assertTrue(NudgeTiming.accepts(start, end, start))
        assertTrue(NudgeTiming.accepts(start, end, start - minute))
        assertTrue(NudgeTiming.accepts(start, end, start - NudgeTiming.EARLY_TOLERANCE_MS))
    }

    @Test
    fun rejectsTooEarlyAndTooLate() {
        assertFalse(NudgeTiming.accepts(start, end, start - NudgeTiming.EARLY_TOLERANCE_MS - 1))
        assertFalse(NudgeTiming.accepts(start, end, start + NudgeTiming.LATE_TOLERANCE_MS + 1))
    }

    @Test
    fun neverNudgesAMeetingThatIsAlreadyOver() {
        // A 5-minute meeting: 8 minutes in is inside the late tolerance but
        // the meeting has ended, so there is nothing to record.
        val shortEnd = start + 5 * minute
        assertTrue(NudgeTiming.accepts(start, shortEnd, start + 2 * minute))
        assertFalse(NudgeTiming.accepts(start, shortEnd, start + 8 * minute))
    }

    @Test
    fun armsBeforeTheMeetingStarts() {
        assertEquals(start - NudgeTiming.LEAD_MS, NudgeTiming.triggerFor(start))
        assertTrue(NudgeTiming.triggerFor(start) < start)
    }

    @Test
    fun nudgeKeyIsPerOccurrence() {
        // Same series, different occurrences -> different keys (so a weekly
        // meeting nudges every week).
        assertNotEquals(
            NudgeTiming.nudgeKey(42L, start),
            NudgeTiming.nudgeKey(42L, start + 7 * 24 * 60 * minute)
        )
        // Same occurrence -> stable key (so it nudges only once).
        assertEquals(NudgeTiming.nudgeKey(42L, start), NudgeTiming.nudgeKey(42L, start))
        // A rescheduled meeting counts as new.
        assertNotEquals(
            NudgeTiming.nudgeKey(42L, start),
            NudgeTiming.nudgeKey(42L, start + 30 * minute)
        )
    }

    @Test
    fun slotRequestCodesAreDistinct() {
        val codes = (0 until 8).map { NudgeTiming.requestCode(it) }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun recordingSuppressionOnlyCoversTheCurrentMeeting() {
        val now = start
        // Nothing recording: never suppressed.
        assertFalse(NudgeTiming.suppressedByRecording(null, now))
        // Started moments ago: that's this meeting, stay quiet.
        assertTrue(NudgeTiming.suppressedByRecording(now - minute, now))
        // Still running from the PREVIOUS meeting: the back-to-back case must
        // still nudge, which is the common work-calendar shape.
        assertFalse(NudgeTiming.suppressedByRecording(now - 40 * minute, now))
    }
}
