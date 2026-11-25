package com.meetily.mobile

import com.meetily.mobile.whisper.LateFrameSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a live recorder with "Keep meeting audio" off sends its frames:
 * held until the model's outcome is known, then to a file opened after
 * all when it fails (also during Finish's wait for it), or dropped once the
 * model loads.
 */
class LateFrameSinkTest {

    /** A reused capture buffer whose first [n] samples count from [start]. */
    private fun buffer(start: Int, n: Int) = FloatArray(n + 3) { (start + it).toFloat() }

    private fun expected(count: Int) = FloatArray(count) { it.toFloat() }

    private fun joined(out: List<FloatArray>) = out.flatMap { it.toList() }.toFloatArray()

    @Test
    fun aFailedLoadHandsOverTheWholeHoldThenLaterFrames() {
        val late = LateFrameSink(holdCapSamples = 100)
        late.offer(buffer(0, 4), 4)
        late.offer(buffer(4, 4), 4)
        assertTrue(late.holdsAudio)
        val out = mutableListOf<FloatArray>()
        late.attach { out.add(it) }
        assertFalse(late.holdsAudio)
        late.offer(buffer(8, 2), 2)
        assertArrayEquals(expected(10), joined(out), 0f)
    }

    @Test
    fun aModelThatLoadedDropsTheHold() {
        val late = LateFrameSink(holdCapSamples = 100)
        late.offer(buffer(0, 4), 4)
        late.dropHeld()
        assertFalse(late.holdsAudio)
        // A file opened after all gets only what is captured from then on.
        val out = mutableListOf<FloatArray>()
        late.attach { out.add(it) }
        late.offer(buffer(4, 2), 2)
        assertArrayEquals(floatArrayOf(4f, 5f), joined(out), 0f)
    }

    @Test
    fun aHoldPastItsCapKeepsTheOpening() {
        val late = LateFrameSink(holdCapSamples = 6)
        late.offer(buffer(0, 4), 4)
        late.offer(buffer(4, 4), 4)
        assertTrue(late.holdsAudio)
        val out = mutableListOf<FloatArray>()
        late.attach { out.add(it) }
        assertArrayEquals(expected(4), joined(out), 0f)
    }

    @Test
    fun aSessionThatIsNotLiveHoldsNothing() {
        val notLive = LateFrameSink(holdCapSamples = null)
        notLive.offer(buffer(0, 4), 4)
        assertFalse(notLive.holdsAudio)
    }

    @Test
    fun aFailedLoadHandsOverWhatWasHeldFirst() {
        val late = LateFrameSink(holdCapSamples = 100)
        late.offer(buffer(0, 3), 3)
        val out = mutableListOf<FloatArray>()
        late.attach { out.add(it) }
        late.offer(buffer(3, 3), 3)
        assertArrayEquals(expected(6), joined(out), 0f)
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
