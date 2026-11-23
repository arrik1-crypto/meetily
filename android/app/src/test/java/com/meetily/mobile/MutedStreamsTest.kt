package com.meetily.mobile

import com.meetily.mobile.data.MutedStreams
import com.meetily.mobile.data.MutedStreams.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record of system sounds a recording muted, and what to put back. */
class MutedStreamsTest {

    // AudioManager.STREAM_SYSTEM, STREAM_MUSIC, STREAM_NOTIFICATION.
    private val system = 1
    private val music = 3
    private val notification = 5
    private val ringAliased: (Int) -> Boolean = { it != music }

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    @Test
    fun recordsRoundTrip() {
        val record = Record(now, listOf(system, notification, music))
        assertEquals("1800000000000:1,5,3", MutedStreams.encode(record))
        assertEquals(record, MutedStreams.decode(MutedStreams.encode(record)))
    }

    @Test
    fun anythingElseDecodesToNothing() {
        for (bad in listOf(null, "", " ", "3", ":3", "x:3", "1800000000000:", "1800000000000:a,3", "1800000000000")) {
            assertNull(bad, MutedStreams.decode(bad))
        }
        // Repeats collapse.
        assertEquals(listOf(music), MutedStreams.decode("5:3,3")?.streams)
    }

    @Test
    fun aRestoreKeepsOnlyWhatIsStillMuted() {
        val streams = listOf(system, notification, music)
        assertTrue(MutedStreams.remaining(streams) { false }.isEmpty())
        // Android 17 ignored the unmute: everything stays recorded.
        assertEquals(streams, MutedStreams.remaining(streams) { true })
        assertEquals(listOf(music), MutedStreams.remaining(streams) { it == music })
    }

    @Test
    fun theNextStartUnmutesWhatIsStillMuted() {
        val record = Record(now - hour, listOf(system, notification, music))
        assertEquals(
            listOf(system, notification, music),
            MutedStreams.toRestore(record, now, { true }, ringerNormal = true, ringAliased = ringAliased)
        )
        // Unmuted since, by the user or by a restore that landed late: left alone.
        assertEquals(
            listOf(music),
            MutedStreams.toRestore(record, now, { it == music }, true, ringAliased)
        )
        assertTrue(MutedStreams.toRestore(record, now, { false }, true, ringAliased).isEmpty())
    }

    @Test
    fun ringAliasedStreamsWaitForANormalRinger() {
        // In vibrate or silent, unmuting the system or notification stream
        // would switch the phone to ringing; only media goes back.
        val record = Record(now - hour, listOf(system, notification, music))
        assertEquals(
            listOf(music),
            MutedStreams.toRestore(record, now, { true }, ringerNormal = false, ringAliased = ringAliased)
        )
    }

    @Test
    fun aStaleOrFutureRecordIsDropped() {
        val streams = listOf(music)
        val within = Record(now - MutedStreams.MAX_AGE_MS, streams)
        assertEquals(streams, MutedStreams.toRestore(within, now, { true }, true, ringAliased))
        val stale = Record(now - MutedStreams.MAX_AGE_MS - 1, streams)
        assertTrue(MutedStreams.toRestore(stale, now, { true }, true, ringAliased).isEmpty())
        // The clock went back: no age to go by.
        val future = Record(now + 1, streams)
        assertTrue(MutedStreams.toRestore(future, now, { true }, true, ringAliased).isEmpty())
        assertTrue(MutedStreams.toRestore(null, now, { true }, true, ringAliased).isEmpty())
    }

    @Test
    fun aStreamTheUserHadMutedIsNeverInTheRecord() {
        // RecordingService skips a stream that was muted before it started,
        // so only what it muted itself is written down; a record never holds
        // more than it is given.
        val record = MutedStreams.decode(MutedStreams.encode(Record(now, listOf(music))))!!
        assertEquals(
            listOf(music),
            MutedStreams.toRestore(record, now, { true }, true, ringAliased)
        )
    }
}
