package com.meetily.mobile

import com.meetily.mobile.reminders.Reminders
import com.meetily.mobile.reminders.Reminders.ReArm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun revokingOrAllowingExactAlarmsReArmsEverything() {
        // Revoking clears the exact reminders and leaves the inexact
        // sentinel: the stored exactness is what notices.
        assertTrue(Reminders.needsFullReArm(sentinelArmed = true, armedExact = true, exactNow = false))
        // Allowing them turns reminders armed inexact into exact ones.
        assertTrue(Reminders.needsFullReArm(sentinelArmed = true, armedExact = false, exactNow = true))
        // Never recorded (an update from before it was): once.
        assertTrue(Reminders.needsFullReArm(sentinelArmed = true, armedExact = null, exactNow = true))
        // Reboot or force-stop.
        assertTrue(Reminders.needsFullReArm(sentinelArmed = false, armedExact = true, exactNow = true))
        // Nothing changed: the pass over every meeting is skipped.
        assertFalse(Reminders.needsFullReArm(sentinelArmed = true, armedExact = true, exactNow = true))
        assertFalse(Reminders.needsFullReArm(sentinelArmed = true, armedExact = false, exactNow = false))
    }

    @Test
    fun theDeliveryRecordNeverHoldsATasksText() {
        val task = "Tell HR about the dismissal"
        val key = Reminders.deliveryKey("m1", task, now)
        assertFalse(key.contains("HR"))
        assertFalse(key.contains(task))
        // Stable, and different for a different task or time.
        assertEquals(key, Reminders.deliveryKey("m1", task, now))
        assertNotEquals(key, Reminders.deliveryKey("m1", "$task.", now))
        assertNotEquals(key, Reminders.deliveryKey("m1", task, now + 1))
        assertEquals(16, Reminders.taskDigest(task).length)
    }

    @Test
    fun anOldRecordIsRewrittenWithoutTheTextAndKeepsMeaning() {
        val task = "Send the deck | v2"
        val legacy = mapOf("m1|${now - hour}|$task" to now - hour)
        val tidy = Reminders.tidyDeliveries(legacy, now)
        // Still recognised as delivered, so it is not re-posted...
        assertEquals(setOf(Reminders.deliveryKey("m1", task, now - hour)), tidy.keys)
        // ...and the text is gone.
        assertTrue(tidy.keys.none { it.contains("deck") })
    }

    @Test
    fun recordsPastTheGraceWindowAreDropped() {
        val fresh = Reminders.deliveryKey("m1", "a", now - hour)
        val stale = Reminders.deliveryKey("m1", "b", now - 2 * Reminders.OVERDUE_GRACE_MS - 1)
        val tidy = Reminders.tidyDeliveries(
            mapOf(fresh to now - hour, stale to now - 2 * Reminders.OVERDUE_GRACE_MS - 1), now
        )
        assertEquals(setOf(fresh), tidy.keys)
        // Unreadable leftovers go too.
        assertTrue(Reminders.tidyDeliveries(mapOf("junk" to now), now).isEmpty())
    }
}
