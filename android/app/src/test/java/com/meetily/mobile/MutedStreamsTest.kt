package com.meetily.mobile

import android.media.AudioManager
import com.meetily.mobile.data.MutedStreams
import com.meetily.mobile.data.MutedStreams.Record
import com.meetily.mobile.data.MutedStreams.Restore
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

    // AudioManager.RINGER_MODE_SILENT, _VIBRATE, _NORMAL.
    private val silent = 0
    private val vibrate = 1
    private val normal = 2

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    private val nothing = Restore(null, emptyList(), null)

    @Test
    fun theConstantsAreAndroids() {
        assertEquals(AudioManager.STREAM_SYSTEM, system)
        assertEquals(AudioManager.STREAM_MUSIC, music)
        assertEquals(AudioManager.STREAM_NOTIFICATION, notification)
        assertEquals(AudioManager.RINGER_MODE_SILENT, silent)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, vibrate)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, normal)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, MutedStreams.RINGER_NORMAL)
    }

    @Test
    fun recordsRoundTrip() {
        val record = Record(now, listOf(system, notification, music))
        assertEquals("1800000000000:1,5,3", MutedStreams.encode(record))
        assertEquals(record, MutedStreams.decode(MutedStreams.encode(record)))
        val moved = Record(now, listOf(system, music), normal, vibrate)
        assertEquals("1800000000000:1,3:2>1", MutedStreams.encode(moved))
        assertEquals(moved, MutedStreams.decode(MutedStreams.encode(moved)))
    }

    @Test
    fun aRingerMoveIsKeptOnlyWhenItMoved() {
        assertEquals(
            Record(now, listOf(system, music), normal, vibrate),
            MutedStreams.record(now, listOf(system, music, music), normal, vibrate)
        )
        // Only media muted (no ring-aliased stream, so no ringer to watch).
        assertEquals(Record(now, listOf(music)), MutedStreams.record(now, listOf(music), -1, -1))
        // A phone whose notification stream is not the ringer's: it stayed.
        assertEquals(Record(now, listOf(system)), MutedStreams.record(now, listOf(system), normal, normal))
        // The mode after could not be read.
        assertEquals(Record(now, listOf(system)), MutedStreams.record(now, listOf(system), normal, -1))
    }

    @Test
    fun anythingElseDecodesToNothing() {
        for (bad in listOf(
            null, "", " ", "3", ":3", "x:3", "1800000000000:", "1800000000000:a,3", "1800000000000",
            "1800000000000:3:", "1800000000000:3:2", "1800000000000:3:2>", "1800000000000:3:x>1",
            "1800000000000:3:2>2", "1800000000000:3:-1>1", "1800000000000:3:2>1:0"
        )) {
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
            Restore(null, listOf(system, notification, music), null),
            MutedStreams.toRestore(record, now, { true }, ringerMode = normal, ringAliased = ringAliased)
        )
        // Unmuted since, by the user or by a restore that landed late: left alone.
        assertEquals(
            Restore(null, listOf(music), null),
            MutedStreams.toRestore(record, now, { it == music }, normal, ringAliased)
        )
        assertEquals(nothing, MutedStreams.toRestore(record, now, { false }, normal, ringAliased))
    }

    /**
     * The case the ringer is recorded for: the mute moved the ringer to
     * vibrate and the process died. At the next start the ringer is still
     * there, so it goes back to normal, and the streams with it.
     */
    @Test
    fun aRingerTheMuteMovedGoesBackFirst() {
        val record = Record(now - hour, listOf(system, notification, music), normal, vibrate)
        assertEquals(
            Restore(normal, listOf(system, notification, music), null),
            MutedStreams.toRestore(record, now, { true }, vibrate, ringAliased)
        )
        // Moved to silent on a phone without a vibrator: the same.
        val toSilent = Record(now - hour, listOf(system, music), normal, silent)
        assertEquals(
            Restore(normal, listOf(system, music), null),
            MutedStreams.toRestore(toSilent, now, { true }, silent, ringAliased)
        )
        // Already back on normal (a restore that landed, or the user): no ringer change.
        assertEquals(
            Restore(null, listOf(system, notification, music), null),
            MutedStreams.toRestore(record, now, { true }, normal, ringAliased)
        )
    }

    /**
     * A ringer the user moved since is theirs. In vibrate or silent,
     * unmuting the system or notification stream would switch the phone to
     * ringing, so only media goes back, and the rest waits in the record for
     * a start that finds the ringer on normal, without the ringer move.
     */
    @Test
    fun ringAliasedStreamsWaitForANormalRinger() {
        val record = Record(now - hour, listOf(system, notification, music), normal, vibrate)
        val waiting = Record(now - hour, listOf(system, notification))
        assertEquals(
            Restore(null, listOf(music), waiting),
            MutedStreams.toRestore(record, now, { true }, silent, ringAliased)
        )
        // A record without a ringer move, the ringer on vibrate.
        val plain = Record(now - hour, listOf(system, notification, music))
        assertEquals(
            Restore(null, listOf(music), waiting),
            MutedStreams.toRestore(plain, now, { true }, vibrate, ringAliased)
        )
        // The ringer could not be read: nothing that rings goes back.
        assertEquals(
            Restore(null, listOf(music), waiting),
            MutedStreams.toRestore(record, now, { true }, -1, ringAliased)
        )
        // Only what is still muted waits.
        assertEquals(
            Restore(null, emptyList(), Record(now - hour, listOf(notification))),
            MutedStreams.toRestore(record, now, { it == notification }, silent, ringAliased)
        )
        // The waiting record goes back at a later start with the ringer on normal.
        assertEquals(
            Restore(null, listOf(system, notification), null),
            MutedStreams.toRestore(waiting, now, { true }, normal, ringAliased)
        )
    }

    @Test
    fun aRingerMoveWithOnlyMediaLeftChangesNoRinger() {
        // The ring-aliased streams are no longer in the record (unmuted and
        // settled away): the ringer is not Recap's business any more.
        val record = Record(now - hour, listOf(music), normal, vibrate)
        assertEquals(
            Restore(null, listOf(music), null),
            MutedStreams.toRestore(record, now, { true }, vibrate, ringAliased)
        )
    }

    @Test
    fun aStaleOrFutureRecordIsDropped() {
        val streams = listOf(music)
        val within = Record(now - MutedStreams.MAX_AGE_MS, streams)
        assertEquals(Restore(null, streams, null), MutedStreams.toRestore(within, now, { true }, normal, ringAliased))
        val stale = Record(now - MutedStreams.MAX_AGE_MS - 1, listOf(system, music), normal, vibrate)
        assertEquals(nothing, MutedStreams.toRestore(stale, now, { true }, vibrate, ringAliased))
        // The clock went back: no age to go by.
        val future = Record(now + 1, streams)
        assertEquals(nothing, MutedStreams.toRestore(future, now, { true }, normal, ringAliased))
        assertEquals(nothing, MutedStreams.toRestore(null, now, { true }, normal, ringAliased))
    }

    @Test
    fun aStreamTheUserHadMutedIsNeverInTheRecord() {
        // RecordingService skips a stream that was muted before it started,
        // so only what it muted itself is written down; a record never holds
        // more than it is given.
        val record = MutedStreams.decode(MutedStreams.encode(Record(now, listOf(music))))!!
        assertEquals(
            Restore(null, listOf(music), null),
            MutedStreams.toRestore(record, now, { true }, normal, ringAliased)
        )
    }

    @Test
    fun aNewMuteKeepsWhatWasWaiting() {
        val waiting = Record(now - hour, listOf(notification))
        val fresh = Record(now, listOf(music))
        assertEquals(Record(now, listOf(music, notification)), MutedStreams.adding(waiting, fresh))
        assertEquals(fresh, MutedStreams.adding(null, fresh))
        // The new mute's ringer move wins; an old one stays when there is no new one.
        val moved = Record(now, listOf(system), normal, vibrate)
        assertEquals(
            Record(now, listOf(system, notification), normal, vibrate),
            MutedStreams.adding(waiting, moved)
        )
        assertEquals(
            Record(now, listOf(music, system), normal, silent),
            MutedStreams.adding(Record(now - hour, listOf(system), normal, silent), fresh)
        )
    }

    @Test
    fun aSessionsOwnRestoreSettlesTheRecord() {
        val record = Record(now - hour, listOf(system, notification, music), normal, vibrate)
        val handled = listOf(system, notification, music)
        // Everything back, ringer back on normal: nothing left.
        assertNull(MutedStreams.settled(record, handled, { false }, normal, now))
        // Android 17 ignored all of it, ringer still on vibrate: all of it stays.
        assertEquals(record, MutedStreams.settled(record, handled, { true }, vibrate, now))
        // The streams stuck but the ringer is not where the mute left it:
        // the ringer move is dropped, the streams stay.
        assertEquals(
            Record(now - hour, handled),
            MutedStreams.settled(record, handled, { true }, silent, now)
        )
        // Streams an earlier session left waiting stay beside this one's.
        val merged = Record(now - hour, listOf(music, notification))
        assertEquals(
            Record(now - hour, listOf(notification)),
            MutedStreams.settled(merged, listOf(music), { false }, normal, now)
        )
        // No record found (cleared meanwhile): what is still muted starts one.
        assertEquals(
            Record(now, listOf(music)),
            MutedStreams.settled(null, listOf(music), { true }, normal, now)
        )
    }
}
