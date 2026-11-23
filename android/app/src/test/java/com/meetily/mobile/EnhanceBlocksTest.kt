package com.meetily.mobile

import com.meetily.mobile.whisper.EnhanceBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The block arithmetic behind clear voice: overlapping blocks cut from a
 * stream, a thrown-away warm-up and run-out, a cross-fade at every seam,
 * and an output exactly as long as the input. Small blocks keep the tests
 * fast; the shipped sizes are checked for their own invariants.
 */
class EnhanceBlocksTest {

    private val hop = 1_000
    private val warmUp = 200
    private val fade = 100
    private val tail = 64

    /** A ramp with no repeats, so a misplaced sample cannot pass unnoticed. */
    private fun signal(n: Int) = FloatArray(n) { i -> ((i * 7919) % 1000) / 1000f - 0.5f }

    private class Run(
        val output: FloatArray,
        val inputs: List<Int>
    )

    private fun run(
        input: FloatArray,
        chunk: Int,
        denoise: (Int, FloatArray) -> FloatArray = { _, x -> x.copyOf() }
    ): Run {
        val out = ArrayList<Float>()
        val inputs = ArrayList<Int>()
        var call = 0
        val blocks = EnhanceBlocks(
            denoise = { x ->
                inputs.add(x.size)
                denoise(call++, x)
            },
            emit = { samples, count -> for (i in 0 until count) out.add(samples[i]) },
            hop = hop, warmUp = warmUp, fade = fade, tail = tail, minRun = 128
        )
        var at = 0
        while (at < input.size) {
            val n = minOf(chunk, input.size - at)
            blocks.push(input, at, n)
            at += n
        }
        blocks.finish()
        assertEquals(input.size.toLong(), blocks.received)
        assertEquals(input.size.toLong(), blocks.emitted)
        return Run(out.toFloatArray(), inputs)
    }

    private fun assertSame(expected: FloatArray, actual: FloatArray, tolerance: Float = 1e-6f) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) {
            if (kotlin.math.abs(expected[i] - actual[i]) > tolerance) {
                fail("sample $i: expected ${expected[i]}, got ${actual[i]}")
            }
        }
    }

    @Test
    fun anIdentityDenoiserGivesTheInputBackAtEveryLength() {
        val capacity = hop + fade + warmUp + tail
        for (n in listOf(1, 50, 127, 128, 999, 1_000, 1_001, capacity - 1, capacity, capacity + 1,
            2 * hop, 2 * hop + fade, 3 * hop - 1, 5 * hop + 37, 12_345)) {
            val x = signal(n)
            assertSame(x, run(x, chunk = 333).output)
        }
    }

    @Test
    fun theOutputDoesNotDependOnHowTheInputArrives() {
        val x = signal(7_777)
        val whole = run(x, chunk = x.size).output
        for (chunk in listOf(1, 17, 256, 1_000, 1_364, 4_096)) {
            assertSame(whole, run(x, chunk).output, tolerance = 0f)
        }
    }

    @Test
    fun anEmptyStreamRunsNothing() {
        val r = run(FloatArray(0), chunk = 10)
        assertEquals(0, r.output.size)
        assertTrue(r.inputs.isEmpty())
    }

    @Test
    fun aShortDenoiserOutputIsPaddedAndNeverReachesWhatIsKept() {
        // GTCRN through sherpa-onnx returns whole 256-sample hops only.
        val x = signal(4_321)
        val r = run(x, chunk = 500) { _, input -> input.copyOf(input.size / 64 * 64) }
        assertSame(x, r.output)
    }

    @Test
    fun theDenoiserIsNeverGivenLessThanAFrameOrMoreThanABlock() {
        for (n in listOf(1, 3, 200, 1_363, 1_364, 1_365, 9_999)) {
            val r = run(signal(n), chunk = 77)
            assertFalse(r.inputs.isEmpty())
            for (size in r.inputs) {
                assertTrue("input of $size for a stream of $n", size >= 128)
                assertTrue("input of $size for a stream of $n", size <= hop + fade + warmUp + 2 * tail)
            }
        }
    }

    @Test
    fun warmUpAndRunOutAreThrownAway() {
        // Garbage where a cold start and the end of a run would be: the
        // first warm-up of every block after the first, the last run-out of
        // every block. None of it may reach the output.
        val x = signal(6_500)
        val r = run(x, chunk = 640) { call, input ->
            val out = input.copyOf()
            if (call > 0) for (i in 0 until warmUp) out[i] = Float.NaN
            for (i in out.size - tail until out.size) out[i] = Float.NaN
            out
        }
        assertSame(x, r.output)
    }

    @Test
    fun seamsCrossFadeFromOneBlockToTheNext() {
        // Each block adds its own offset, so the output shows exactly which
        // block (or blend) each sample came from.
        val n = 4 * hop + 300
        val x = FloatArray(n)
        val r = run(x, chunk = 999) { call, input -> FloatArray(input.size) { call.toFloat() } }
        val y = r.output
        for (k in 1..4) {
            val seam = k * hop
            // Block k owns from its fade-in on; block k-1 up to it.
            assertEquals((k - 1).toFloat(), y[seam - fade - 1], 1e-6f)
            for (i in 0 until fade) {
                val w = EnhanceBlocks.fadeIn(i, fade)
                assertEquals("seam $k, fade $i", (k - 1) * (1 - w) + k * w, y[seam - fade + i], 1e-5f)
            }
            assertEquals(k.toFloat(), y[seam], 1e-6f)
        }
        assertEquals(4f, y[n - 1], 1e-6f)
    }

    @Test
    fun fadeWeightsSumToOneAndRiseSmoothly() {
        for (n in listOf(1, 2, 100, EnhanceBlocks.FADE)) {
            var previous = -1f
            for (i in 0 until n) {
                val w = EnhanceBlocks.fadeIn(i, n)
                assertTrue(w > 0f && w < 1f || n == 1)
                assertTrue(w > previous)
                previous = w
                // Mirror image: the old block's weight at i is the new one's at n-1-i.
                assertEquals(1f, w + EnhanceBlocks.fadeIn(n - 1 - i, n), 1e-6f)
            }
        }
    }

    @Test
    fun pushAfterFinishIsRefusedAndFinishTwiceIsHarmless() {
        val blocks = EnhanceBlocks(denoise = { it }, emit = { _, _ -> },
            hop = hop, warmUp = warmUp, fade = fade, tail = tail, minRun = 128)
        blocks.push(signal(10))
        blocks.finish()
        blocks.finish()
        assertEquals(10L, blocks.emitted)
        try {
            blocks.push(signal(1))
            fail("push after finish")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun theShippedSizesHoldTheirOwnRules() {
        // A block owns more than its lead-in, the run-out covers two GTCRN
        // frames, nothing shorter than a frame reaches the denoiser, and the
        // repeated lead-in and run-out cost under a tenth more work.
        assertTrue(EnhanceBlocks.HOP > EnhanceBlocks.FADE + EnhanceBlocks.WARM_UP)
        assertTrue(EnhanceBlocks.TAIL >= 2 * 512)
        assertTrue(EnhanceBlocks.MIN_RUN >= 512)
        assertTrue(EnhanceBlocks.WARM_UP >= 16_000)
        val overhead = EnhanceBlocks.overhead()
        assertTrue("overhead $overhead", overhead > 0.0 && overhead < 0.1)
        // 30 s blocks: what a block's input holds, in samples.
        assertEquals(480_000, EnhanceBlocks.HOP)
    }

    @Test
    fun theShippedSizesGiveTheInputBack() {
        val x = signal(EnhanceBlocks.HOP * 2 + 12_345)
        val out = ArrayList<Float>()
        val blocks = EnhanceBlocks(
            denoise = { it.copyOf(it.size / 256 * 256) },
            emit = { samples, count -> for (i in 0 until count) out.add(samples[i]) }
        )
        var at = 0
        while (at < x.size) {
            val n = minOf(4_096, x.size - at)
            blocks.push(x, at, n)
            at += n
        }
        blocks.finish()
        assertEquals(3, blocks.blocks)
        assertSame(x, out.toFloatArray())
    }
}
