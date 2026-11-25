package com.meetily.mobile

import com.meetily.mobile.whisper.HeldAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a live session captured while its model loaded, with no audio file
 * open, reaches the file a failed load opens: all of it, in order, and in
 * few enough pieces for the writer's queue.
 */
class HeldAudioTest {

    private fun frame(start: Int, size: Int) = FloatArray(size) { (start + it).toFloat() }

    private fun drained(held: HeldAudio, piece: Int): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        held.drainInto({ out.add(it) }, piece)
        return out
    }

    @Test
    fun everySampleComesOutInOrderJoinedIntoPieces() {
        val held = HeldAudio(capSamples = 1_000)
        assertTrue(held.add(frame(0, 4)))
        assertTrue(held.add(frame(4, 4)))
        assertTrue(held.add(frame(8, 3)))
        val pieces = drained(held, piece = 5)
        assertEquals(listOf(5, 5, 1), pieces.map { it.size })
        assertArrayEquals(frame(0, 11), pieces.flatMap { it.toList() }.toFloatArray(), 0f)
    }

    @Test
    fun aPieceLargerThanTheHoldIsCutToFit() {
        val held = HeldAudio(capSamples = 1_000)
        held.add(frame(0, 3))
        held.add(frame(3, 2))
        val pieces = drained(held, piece = HeldAudio.PIECE_SAMPLES)
        assertEquals(1, pieces.size)
        assertArrayEquals(frame(0, 5), pieces[0], 0f)
    }

    @Test
    fun theCapKeepsTheOpeningNotTheLatestFrames() {
        val held = HeldAudio(capSamples = 8)
        assertTrue(held.add(frame(0, 4)))
        assertTrue(held.add(frame(4, 4)))
        assertFalse(held.overflowed)
        assertFalse(held.add(frame(8, 1)))
        assertTrue(held.overflowed)
        assertEquals(8L, held.samples)
        assertArrayEquals(frame(0, 8), drained(held, piece = 100)[0], 0f)
        // Drained, it holds nothing and so has refused nothing.
        assertFalse(held.overflowed)
    }

    @Test
    fun drainingForgetsWhatWasHeld() {
        val held = HeldAudio(capSamples = 100)
        held.add(frame(0, 10))
        drained(held, piece = 4)
        assertTrue(held.isEmpty)
        assertEquals(0L, held.samples)
        assertTrue(drained(held, piece = 4).isEmpty())
    }

    @Test
    fun ninetySecondsFitTheWritersQueue() {
        val held = HeldAudio(capSamples = 90L * 16_000)
        var start = 0
        while (held.add(frame(start, 1_600))) start += 1_600
        assertEquals(90L * 16_000, held.samples)
        // MeetingAudioWriter queues at most 300 entries.
        assertTrue(drained(held, piece = HeldAudio.PIECE_SAMPLES).size <= 20)
    }

    @Test
    fun clearDropsEverything() {
        val held = HeldAudio(capSamples = 100)
        held.add(frame(0, 10))
        held.clear()
        assertTrue(held.isEmpty)
        assertTrue(drained(held, piece = 4).isEmpty())
    }
}
