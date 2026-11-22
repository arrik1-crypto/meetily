package com.meetily.mobile

import com.meetily.mobile.data.Retention
import com.meetily.mobile.data.Retention.Candidate
import com.meetily.mobile.data.Retention.Mode
import com.meetily.mobile.data.Retention.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which meetings an automatic clean-up rule reaches, and what it never touches. */
class RetentionTest {

    private val day = Retention.DAY_MS
    private val now = 1_800_000_000_000L

    private fun meeting(
        id: String,
        ageDays: Long,
        modifiedDaysAgo: Long = ageDays,
        starred: Boolean = false,
        audioBytes: Long = 1_000L,
        reminder: Boolean = false
    ) = Candidate(
        id = id,
        createdAtMs = now - ageDays * day,
        modifiedMs = now - modifiedDaysAgo * day,
        starred = starred,
        audioBytes = audioBytes,
        hasFutureReminder = reminder
    )

    @Test
    fun offOrZeroDaysReachesNothing() {
        val old = listOf(meeting("a", 400))
        assertTrue(Retention.select(Retention.OFF, old, emptySet(), now).isEmpty())
        assertTrue(Retention.select(Rule(Mode.AUDIO, 0), old, emptySet(), now).isEmpty())
        assertFalse(Rule(Mode.MEETINGS, 0).enabled)
        assertTrue(Rule(Mode.MEETINGS, 30).enabled)
    }

    @Test
    fun onlyMeetingsOlderThanTheRuleAreReachedOldestFirst() {
        val list = listOf(
            meeting("new", 10),
            meeting("edge", 90),
            meeting("old", 91),
            meeting("older", 200)
        )
        val picked = Retention.select(Rule(Mode.AUDIO, 90), list, emptySet(), now)
        // Exactly 90 days old is not older than 90 days.
        assertEquals(listOf("older", "old"), picked.map { it.id })
    }

    @Test
    fun audioRuleSkipsMeetingsWithNoAudioButTheMeetingRuleDoesNot() {
        val list = listOf(meeting("notes", 120, audioBytes = 0), meeting("rec", 120))
        assertEquals(listOf("rec"), Retention.select(Rule(Mode.AUDIO, 30), list, emptySet(), now).map { it.id })
        assertEquals(
            setOf("notes", "rec"),
            Retention.select(Rule(Mode.MEETINGS, 30), list, emptySet(), now).map { it.id }.toSet()
        )
    }

    @Test
    fun starredBusyRemindedAndRecentlyChangedMeetingsAreKept() {
        val list = listOf(
            meeting("starred", 300, starred = true),
            meeting("busy", 300),
            meeting("reminder", 300, reminder = true),
            meeting("edited", 300, modifiedDaysAgo = 2),
            meeting("plain", 300)
        )
        for (mode in listOf(Mode.AUDIO, Mode.MEETINGS)) {
            val picked = Retention.select(Rule(mode, 30), list, setOf("busy"), now)
            assertEquals(listOf("plain"), picked.map { it.id })
        }
    }

    @Test
    fun theRecentWindowIsOneWeek() {
        val justSettled = meeting("a", 300, modifiedDaysAgo = 7)
        val stillRecent = Candidate("b", now - 300 * day, now - 7 * day + 1, false, 10, false)
        val picked = Retention.select(Rule(Mode.AUDIO, 30), listOf(justSettled, stillRecent), emptySet(), now)
        assertEquals(listOf("a"), picked.map { it.id })
    }

    @Test
    fun unknownOrFutureDatesAreNeverTreatedAsOld() {
        val list = listOf(
            // Saved without a creation date.
            Candidate("undated", 0L, now - 400 * day, false, 10, false),
            // File time unknown (0) or ahead of the clock.
            Candidate("nomtime", now - 400 * day, 0L, false, 10, false),
            Candidate("future", now - 400 * day, now + day, false, 10, false),
            Candidate("negative", -5L, now - 400 * day, false, 10, false)
        )
        assertTrue(Retention.select(Rule(Mode.MEETINGS, 30), list, emptySet(), now).isEmpty())
    }

    @Test
    fun largeAutomaticMeetingDeletionsAskFirst() {
        val many = (1..Retention.AUTO_MAX_MEETINGS + 1).map { meeting("m$it", 400) }
        val few = many.take(Retention.AUTO_MAX_MEETINGS)
        assertTrue(Retention.needsReview(Rule(Mode.MEETINGS, 30), many))
        assertFalse(Retention.needsReview(Rule(Mode.MEETINGS, 30), few))
        // Removing audio keeps every transcript, so it never needs the stop.
        assertFalse(Retention.needsReview(Rule(Mode.AUDIO, 30), many))
    }

    @Test
    fun audioBytesAddUp() {
        assertEquals(
            3_500L,
            Retention.audioBytes(listOf(meeting("a", 1, audioBytes = 1_500), meeting("b", 1, audioBytes = 2_000)))
        )
        assertEquals(0L, Retention.audioBytes(emptyList()))
    }

    @Test
    fun rulesRoundTripThroughStorage() {
        for (rule in listOf(Rule(Mode.AUDIO, 90), Rule(Mode.MEETINGS, 30), Rule(Mode.AUDIO, 365))) {
            assertEquals(rule, Retention.decode(Retention.encode(rule)))
        }
        assertEquals("off", Retention.encode(Retention.OFF))
        assertEquals("off", Retention.encode(Rule(Mode.AUDIO, 0)))
        assertEquals("audio:90", Retention.encode(Rule(Mode.AUDIO, 90)))
    }

    @Test
    fun anythingMalformedDecodesAsOff() {
        for (stored in listOf(
            null, "", "off", "off:30", "audio", "audio:", "audio:x", "audio:0", "audio:-3",
            "audio:99999", "shred:30", "meetings:30:5"
        )) {
            assertEquals("for $stored", Retention.OFF, Retention.decode(stored))
        }
    }

    @Test
    fun theOfferedAgesAreSensible() {
        assertTrue(Retention.DEFAULT_DAYS in Retention.DAY_CHOICES)
        assertTrue(Retention.DAY_CHOICES.all { it >= 30 })
        assertEquals(Retention.DAY_CHOICES.sorted(), Retention.DAY_CHOICES)
    }
}
