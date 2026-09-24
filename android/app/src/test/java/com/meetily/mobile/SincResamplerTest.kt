package com.meetily.mobile

import com.meetily.mobile.whisper.EmbedWindow
import com.meetily.mobile.whisper.SincResampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The import resampler: speech-band content passes, content above the new
 * Nyquist rate is removed instead of folded back into the speech band.
 */
class SincResamplerTest {

    private fun tone(hz: Double, rate: Int, seconds: Double): FloatArray =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    /** RMS away from the edges, where the kernel sees leading silence. */
    private fun rms(x: FloatArray): Double {
        val from = x.size / 10
        val to = x.size - x.size / 10
        var sum = 0.0
        for (i in from until to) sum += x[i].toDouble() * x[i]
        return sqrt(sum / (to - from))
    }

    private fun resample(input: FloatArray, src: Int, chunk: Int = 4096): FloatArray {
        val r = SincResampler(src, 16_000)
        val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val end = minOf(i + chunk, input.size)
            r.process(input.copyOfRange(i, end)).forEach { out.add(it) }
            i = end
        }
        return out.toFloatArray()
    }

    @Test
    fun speechBandTonePassesAtFullLevel() {
        for (src in listOf(48_000, 44_100, 22_050)) {
            val out = resample(tone(1_000.0, src, 1.0), src)
            // 0.5 amplitude sine: RMS 0.354.
            assertEquals("rate $src", 0.354, rms(out), 0.02)
        }
    }

    @Test
    fun toneAboveTheNewNyquistIsRemovedNotAliased() {
        // 12 kHz would fold to 4 kHz, right in the speech band.
        for (src in listOf(48_000, 44_100)) {
            val out = resample(tone(12_000.0, src, 1.0), src)
            assertTrue("rate $src leaked ${rms(out)}", rms(out) < 0.005)
        }
    }

    @Test
    fun outputLengthMatchesTheRateRatio() {
        val out = resample(FloatArray(48_000) { 0.1f }, 48_000)
        // A kernel's worth of tail is held back, never more.
        assertTrue(out.size in 15_950..16_000)
        // DC passes at unity gain.
        assertEquals(0.1, out[out.size / 2].toDouble(), 0.002)
    }

    @Test
    fun chunkingDoesNotChangeTheOutput() {
        val input = tone(3_000.0, 44_100, 0.5)
        val whole = resample(input, 44_100, chunk = input.size)
        val pieces = resample(input, 44_100, chunk = 997)
        assertEquals(whole.size, pieces.size)
        for (i in whole.indices) assertTrue(abs(whole[i] - pieces[i]) < 1e-5f)
    }

    @Test
    fun sameRateIsAPassThrough() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertSame(input, SincResampler(16_000, 16_000).process(input))
    }

    @Test
    fun upsamplingKeepsTheTone() {
        val out = resample(tone(1_000.0, 8_000, 1.0), 8_000)
        assertTrue(out.size in 15_900..16_000)
        assertEquals(0.354, rms(out), 0.02)
    }

    @Test
    fun embedWindowKeepsTheCentre() {
        val short = FloatArray(10) { it.toFloat() }
        assertSame(short, EmbedWindow.centre(short, 20))
        val long = FloatArray(10) { it.toFloat() }
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), EmbedWindow.centre(long, 4), 0f)
    }
}
