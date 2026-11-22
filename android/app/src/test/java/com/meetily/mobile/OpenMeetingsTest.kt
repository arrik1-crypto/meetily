package com.meetily.mobile

import com.meetily.mobile.data.OpenMeetings
import com.meetily.mobile.data.Retention
import com.meetily.mobile.data.Retention.Candidate
import com.meetily.mobile.data.Retention.Mode
import com.meetily.mobile.data.Retention.Rule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The meetings open on a meeting screen, which the automatic clean-up leaves
 * alone however old they are (RetentionRunner passes them as protected).
 */
class OpenMeetingsTest {

    private val ids = listOf("open-a", "open-b")

    @After
    fun releaseAll() {
        // The registry is process-wide: leave nothing held for other tests.
        for (id in ids) repeat(4) { OpenMeetings.release(id) }
    }

    @Test
    fun heldUntilEveryHolderReleases() {
        OpenMeetings.hold("open-a")
        OpenMeetings.hold("open-a")
        assertTrue(OpenMeetings.isOpen("open-a"))
        OpenMeetings.release("open-a")
        // A second screen on the same meeting still has it open.
        assertTrue(OpenMeetings.isOpen("open-a"))
        OpenMeetings.release("open-a")
        assertFalse(OpenMeetings.isOpen("open-a"))
    }

    @Test
    fun extraReleasesAreIgnored() {
        OpenMeetings.release("open-b")
        assertFalse(OpenMeetings.isOpen("open-b"))
        OpenMeetings.hold("open-b")
        assertTrue(OpenMeetings.isOpen("open-b"))
        assertTrue("open-b" in OpenMeetings.ids())
    }

    @Test
    fun idsIsACopy() {
        OpenMeetings.hold("open-a")
        val before = OpenMeetings.ids()
        OpenMeetings.release("open-a")
        assertTrue("open-a" in before)
        assertFalse("open-a" in OpenMeetings.ids())
    }

    @Test
    fun anOpenMeetingIsNotCleanedUp() {
        val now = 1_800_000_000_000L
        val day = Retention.DAY_MS
        fun old(id: String) = Candidate(
            id = id,
            createdAtMs = now - 400 * day,
            modifiedMs = now - 400 * day,
            starred = false,
            audioBytes = 1_000L,
            hasFutureReminder = false,
            hasTranscript = true
        )
        OpenMeetings.hold("open-a")
        val reached = Retention.select(
            Rule(Mode.MEETINGS, 90),
            listOf(old("open-a"), old("closed")),
            OpenMeetings.ids(),
            now
        )
        assertEquals(listOf("closed"), reached.map { it.id })
    }
}
