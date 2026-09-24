package com.meetily.mobile

import com.meetily.mobile.reminders.Reminders
import com.meetily.mobile.reminders.Reminders.ReArm
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the start-up / boot sweep does with one action-item reminder.
 *
 * The original bug: every reminder whose time had passed was skipped, so one
 * due while the phone was off (or the app force-stopped, or the backup being
 * restored) was never delivered at all.
 */
class ReminderReArmTest {

    private val hour = 60 * 60_000L
    private val now = 1_800_000_000_000L

    @Test
    fun futureReminderIsArmed() {
        assertEquals(ReArm.ARM, Reminders.reArmDecision(now + hour, false, false, now))
    }

    @Test
    fun reminderMissedWhileTheAlarmWasGoneIsDeliveredLate() {
        assertEquals(
            ReArm.DELIVER_NOW,
            Reminders.reArmDecision(now - 20 * 60_000L, false, false, now)
        )
    }

    @Test
    fun alreadyDeliveredReminderIsNotRepostedOnEveryStart() {
        assertEquals(ReArm.SKIP, Reminders.reArmDecision(now - hour, false, true, now))
    }

    @Test
    fun doneItemNeverFires() {
        assertEquals(ReArm.SKIP, Reminders.reArmDecision(now + hour, true, false, now))
        assertEquals(ReArm.SKIP, Reminders.reArmDecision(now - hour, true, false, now))
    }

    @Test
    fun staleReminderBeyondTheGraceWindowIsDropped() {
        val tooOld = now - Reminders.OVERDUE_GRACE_MS - 1
        assertEquals(ReArm.SKIP, Reminders.reArmDecision(tooOld, false, false, now))
        val justInside = now - Reminders.OVERDUE_GRACE_MS
        assertEquals(ReArm.DELIVER_NOW, Reminders.reArmDecision(justInside, false, false, now))
    }
}
