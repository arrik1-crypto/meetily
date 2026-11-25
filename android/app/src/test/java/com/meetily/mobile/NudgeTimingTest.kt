package com.meetily.mobile

import com.meetily.mobile.reminders.Reminders.Approach
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

    /**
     * Without exact alarms Android gives an alarm a window of three quarters
     * of how far ahead it was armed (up to an hour), and an idle phone fires
     * at the end of it. Armed once an hour ahead, a nudge landed ~45 minutes
     * late and was dropped. Walk every distance with each alarm firing as
     * late as Android allows: every step must fire before the time, and the
     * time itself must land inside the receiver's tolerance.
     */
    @Test
    fun inexactAlarmsReachTheirTimeInStepsAndLandOnIt() {
        val distances = listOf(
            30_000L, 3 * minute, 8 * minute, 9 * minute, 20 * minute, 60 * minute,
            3 * 60 * minute, 24 * 60 * minute, 7 * 24 * 60 * minute
        )
        for (ahead in distances) {
            for (latest in listOf(true, false)) {
                val target = start
                var now = target - ahead
                var steps = 0
                while (true) {
                    val armAt = Approach.nextArm(target, now, canExact = false)
                    val fires = if (latest) Approach.latestFire(armAt, now) else armAt
                    if (armAt == target) {
                        assertTrue(
                            "$ahead ahead lands ${fires - target} ms late",
                            fires - target <= Approach.INEXACT_WINDOW_MS
                        )
                        break
                    }
                    assertTrue("a step for $ahead fires before the time", fires < target)
                    assertTrue(Approach.isStep(target, fires))
                    now = fires
                    steps++
                    assertTrue("$ahead took $steps steps", steps < 40)
                }
            }
        }
        // The nudge's own tolerance covers that worst case.
        assertTrue(
            NudgeTiming.accepts(
                start, end, NudgeTiming.triggerFor(start) + Approach.INEXACT_WINDOW_MS
            )
        )
    }

    @Test
    fun exactAlarmsAreArmedForTheTimeItself() {
        assertEquals(start, Approach.nextArm(start, start - 24 * 60 * minute, canExact = true))
        assertEquals(null, Approach.nextStep(listOf(start), start - 60 * minute, canExact = true))
    }

    @Test
    fun theNudgeStepAimsHalfwayToTheNearestFarNudge() {
        val now = start - 60 * minute
        // Close enough to land on time by itself: no step needed.
        assertEquals(null, Approach.nextStep(listOf(now + 5 * minute), now, canExact = false))
        assertEquals(null, Approach.nextStep(emptyList(), now, canExact = false))
        // The nearest of those too far ahead decides.
        assertEquals(
            now + 15 * minute,
            Approach.nextStep(
                listOf(now + 5 * minute, now + 30 * minute, now + 90 * minute), now,
                canExact = false
            )
        )
    }

    @Test
    fun aFiringAtTheTimeIsNotAStep() {
        assertFalse(Approach.isStep(start, start))
        assertFalse(Approach.isStep(start, start + minute))
        assertTrue(Approach.isStep(start, start - 10 * minute))
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
