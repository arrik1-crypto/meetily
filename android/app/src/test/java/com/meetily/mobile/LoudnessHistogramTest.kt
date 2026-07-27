package com.meetily.mobile

import com.meetily.mobile.data.LoudnessHistogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The waveform's accumulator. A wrong answer here is a picture that looks
 * plausible and is not the audio, which nobody notices from a screenshot.
 */
class LoudnessHistogramTest {

    private val bars = 54
    private val rate = 16_000

    /** Feeds [seconds] of audio whose amplitude comes from [level] (0..1 of the way through). */
    private fun feed(hist: LoudnessHistogram, seconds: Int, level: (Double) -> Float) {
        val chunk = FloatArray(rate)
        for (s in 0 until seconds) {
            val v = level(s.toDouble() / seconds)
            java.util.Arrays.fill(chunk, v)
            hist.add(chunk)
        }
    }

    @Test
    fun anHourLongMeetingIsDrawnAcrossTheWholeWidth() {
        // The regression. At 4096 buckets of 4000 samples the histogram ran
        // out after ~17 minutes and every later sample piled into the last
        // bucket, so an hour-long meeting drew its first 17 minutes across 53
        // bars and crushed the remaining 43 minutes into bar 54.
        //
        // Silent for the first half, loud for the second: if the fold is
        // right, the quiet/loud boundary lands in the middle of the picture.
        val hist = LoudnessHistogram()
        feed(hist, 3600) { if (it < 0.5) 0.0f else 0.5f }
        val out = hist.bars(bars)

        for (i in 0 until bars / 2 - 1) {
            assertEquals("bar $i should be silent", 0f, out[i], 0.01f)
        }
        for (i in bars / 2 + 1 until bars) {
            assertEquals("bar $i should be loud", 1f, out[i], 0.01f)
        }
    }

    @Test
    fun aRampStaysMonotonicAtEveryLength() {
        // Doubling the bucket width mid-stream must not put a step or a dip
        // into a signal that only rises. 10 minutes is under the old ceiling,
        // 40 and 200 are one and several doublings past it.
        for (minutes in listOf(10, 40, 200)) {
            val hist = LoudnessHistogram()
            feed(hist, minutes * 60) { (0.05 + 0.9 * it).toFloat() }
            val out = hist.bars(bars)
            for (i in 1 until bars) {
                assertTrue(
                    "$minutes min: bar $i (${out[i]}) dipped below bar ${i - 1} (${out[i - 1]})",
                    out[i] >= out[i - 1] - 0.02f
                )
            }
            assertEquals("$minutes min: last bar is the peak", 1f, out[bars - 1], 0.001f)
        }
    }

    @Test
    fun loudnessIsRmsSoOneSpikeDoesNotFlattenEverythingElse() {
        // Peak would make the spike's bar 1.0 and scale every other bar to
        // near nothing. RMS keeps the quiet-but-present parts visible, which
        // is the entire point of drawing the waveform.
        val hist = LoudnessHistogram()
        feed(hist, 600) { 0.2f }
        hist.add(FloatArray(64) { 1.0f }) // a door slam
        val out = hist.bars(bars)
        assertTrue("body of the meeting stayed visible", out[10] > 0.8f)
    }

    @Test
    fun nothingAddedGivesFlatBarsRatherThanThrowing() {
        val out = LoudnessHistogram().bars(bars)
        assertEquals(bars, out.size)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun audioShorterThanOneBucketStillFillsTheBar() {
        val hist = LoudnessHistogram()
        hist.add(FloatArray(100) { 0.5f })
        val out = hist.bars(bars)
        assertEquals(1f, out[0], 0.001f)
    }

    @Test
    fun theLoudestBarIsAlwaysExactlyOne() {
        // The view scales bar heights straight off these values, so anything
        // above 1 draws outside the card.
        for (minutes in listOf(1, 17, 18, 90)) {
            val hist = LoudnessHistogram()
            feed(hist, minutes * 60) { (0.1 + 0.4 * kotlin.math.sin(it * 6.0)).toFloat() }
            val out = hist.bars(bars)
            assertEquals("$minutes min", 1f, out.max(), 0.001f)
            assertTrue("$minutes min: no negative bars", out.all { it >= 0f })
        }
    }
}
