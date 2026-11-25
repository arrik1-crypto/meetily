package com.meetily.mobile

import com.meetily.mobile.whisper.LateFrameSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a live recorder with "Keep meeting audio" off sends its frames:
 * held until the model's outcome is known, then to a file opened after
 * all, or dropped once the model loads. A Finish that cannot wait for the
 * outcome hands the hold to a file only while it has the whole meeting.
 */
class LateFrameSinkTest {

    /** A reused capture buffer whose first [n] samples count from [start]. */
    private fun buffer(start: Int, n: Int) = FloatArray(n + 3) { (start + it).toFloat() }

    private fun expected(count: Int) = FloatArray(count) { it.toFloat() }

    private fun joined(out: List<FloatArray>) = out.flatMap { it.toList() }.toFloatArray()

    @Test
    fun aFileOpenedAtFinishGetsTheWholeMeetingThenLaterFrames() {
        val late = LateFrameSink(holdCapSamples = 100)
        late.offer(buffer(0, 4), 4)
        late.offer(buffer(4, 4), 4)
        assertTrue(late.holdsAudio)
        val out = mutableListOf<FloatArray>()
        var opened = 0
        assertTrue(late.attachIfHeldInFull { opened++; { out.add(it) } })
        assertEquals(1, opened)
        assertFalse(late.holdsAudio)
        late.offer(buffer(8, 2), 2)
        assertArrayEquals(expected(10), joined(out), 0f)
    }

    @Test
    fun aModelThatLoadedFirstLeavesNoFileToOpen() {
        val late = LateFrameSink(holdCapSamples = 100)
        late.offer(buffer(0, 4), 4)
        late.dropHeld()
        var opened = false
        assertFalse(late.attachIfHeldInFull { opened = true; { } })
        assertFalse(opened)
        assertFalse(late.holdsAudio)
    }

    @Test
    fun aHoldPastItsCapIsNotTheWholeMeeting() {
        val late = LateFrameSink(holdCapSamples = 6)
        late.offer(buffer(0, 4), 4)
        late.offer(buffer(4, 4), 4)
        var opened = false
        assertFalse(late.attachIfHeldInFull { opened = true; { } })
        assertFalse(opened)
        // Still held for a load that fails: liveModelFailed attaches it.
        assertTrue(late.holdsAudio)
        val out = mutableListOf<FloatArray>()
        late.attach { out.add(it) }
        assertArrayEquals(expected(4), joined(out), 0f)
    }

    @Test
    fun nothingHeldOpensNoFile() {
        val late = LateFrameSink(holdCapSamples = 100)
        var opened = false
        assertFalse(late.attachIfHeldInFull { opened = true; { } })
        assertFalse(opened)
        // Not a live session: nothing is held for.
        val notLive = LateFrameSink(holdCapSamples = null)
        notLive.offer(buffer(0, 4), 4)
        assertFalse(notLive.holdsAudio)
        assertFalse(notLive.attachIfHeldInFull { opened = true; { } })
        assertFalse(opened)
    }

    @Test
    fun aFileThatCouldNotBeOpenedKeepsTheHold() {
        val late = LateFrameSink(holdCapSamples = 100)
        late.offer(buffer(0, 4), 4)
        assertFalse(late.attachIfHeldInFull { null })
        assertTrue(late.holdsAudio)
    }

    @Test
    fun aFailedLoadHandsOverWhatWasHeldFirst() {
        val late = LateFrameSink(holdCapSamples = 100)
        late.offer(buffer(0, 3), 3)
        val out = mutableListOf<FloatArray>()
        late.attach { out.add(it) }
        late.offer(buffer(3, 3), 3)
        assertArrayEquals(expected(6), joined(out), 0f)
        // Once attached, a later Finish has nothing more to hand over.
        assertFalse(late.attachIfHeldInFull { { } })
    }

    @Test
    fun framesAreCopiedFromTheReusedBuffer() {
        val late = LateFrameSink(holdCapSamples = 100)
        val reused = buffer(0, 4)
        late.offer(reused, 4)
        reused.fill(-1f)
        val out = mutableListOf<FloatArray>()
        late.attach { out.add(it) }
        val live = buffer(4, 2)
        late.offer(live, 2)
        live.fill(-1f)
        assertArrayEquals(expected(6), joined(out), 0f)
    }
}
