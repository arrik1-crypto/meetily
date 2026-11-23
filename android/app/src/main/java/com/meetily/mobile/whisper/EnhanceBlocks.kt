package com.meetily.mobile.whisper

/**
 * Runs an offline denoiser over a stream in overlapping blocks, so a
 * two-hour meeting never has to be in memory whole.
 *
 * sherpa-onnx's offline GTCRN takes a whole signal and keeps its whole
 * spectrogram while it runs (about 0.5 MB a second of audio), and its
 * output is a quarter-second short of its input. So each block is cut from
 * the stream with a lead-in and a run-out it does not keep:
 *
 *  - Block k owns [k*hop, (k+1)*hop) of the output.
 *  - It starts [fade] + [warmUp] samples early. GTCRN is recurrent, and a
 *    run that starts cold differs sharply from a warm one for about half a
 *    second (measured on host against a whole-file run: 1 to 15 dB apart in
 *    the first 0.5 s, settling to the 20 to 24 dB a run started anywhere
 *    differs by). The warm-up is thrown away.
 *  - Its first [fade] samples are cross-faded with the previous block's
 *    last [fade], under a raised-cosine pair of weights that sum to one:
 *    both are estimates of the same speech, so the level holds and there is
 *    no step for the ear to catch.
 *  - It runs [tail] samples past its end, which are also thrown away, so the
 *    samples it keeps are never the denoiser's last frames.
 *
 * The last block is padded with [tail] zeros instead and cut back to the
 * stream's length, so the output is exactly as long as the input. Pure: the
 * denoiser is passed in, which is what EnhanceBlocksTest drives.
 */
class EnhanceBlocks(
    private val denoise: (FloatArray) -> FloatArray,
    /** Receives the output in order: `count` samples from the array's start. */
    private val emit: (FloatArray, Int) -> Unit,
    val hop: Int = HOP,
    val warmUp: Int = WARM_UP,
    val fade: Int = FADE,
    val tail: Int = TAIL,
    /** The shortest input the denoiser is ever given (see [MIN_RUN]). */
    val minRun: Int = MIN_RUN
) {
    init {
        require(fade >= 1 && warmUp >= 0 && tail >= 0) { "fade, warm-up and tail" }
        require(hop > fade + warmUp) { "a block must own more than its lead-in" }
        require(minRun >= 1) { "minRun" }
    }

    /** Input samples received so far. */
    var received = 0L
        private set

    /** Output samples emitted so far; equals [received] after [finish]. */
    var emitted = 0L
        private set

    /** Blocks run so far. */
    var blocks = 0
        private set

    private var finished = false

    /** Input not yet consumed: [bufStart, bufStart + bufLen) of the stream. */
    private val buf = FloatArray(hop + fade + warmUp + tail)
    private var bufStart = 0L
    private var bufLen = 0

    /** The previous block's output over the next block's fade-in. */
    private val pending = FloatArray(fade)
    private var hasPending = false

    /** Feeds [count] samples of [samples] from [offset]. Runs blocks as they fill. */
    fun push(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset) {
        check(!finished) { "push after finish" }
        require(offset >= 0 && count >= 0 && offset + count <= samples.size) { "range" }
        var at = offset
        var left = count
        while (left > 0) {
            val take = minOf(left, buf.size - bufLen)
            System.arraycopy(samples, at, buf, bufLen, take)
            bufLen += take
            received += take
            at += take
            left -= take
            // Full exactly when the next block's input has all arrived.
            if (bufLen == buf.size) runBlock(last = false)
        }
    }

    /** Runs what is left as the last block. Call once, after the last [push]. */
    fun finish() {
        if (finished) return
        finished = true
        if (received == 0L) return
        runBlock(last = true)
    }

    /** Where block [k]'s input starts in the stream. */
    private fun inputStart(k: Int): Long =
        if (k == 0) 0L else k.toLong() * hop - fade - warmUp

    private fun runBlock(last: Boolean) {
        val k = blocks
        val start = inputStart(k)
        check(start == bufStart) { "block $k starts at $start, buffer at $bufStart" }
        val ownFrom = if (k == 0) 0L else k.toLong() * hop - fade
        val boundary = (k + 1).toLong() * hop
        val ownTo = if (last) received else boundary
        val input = if (last) {
            // Zeros after the end, so the samples kept are not the
            // denoiser's final frames, and never less than it can take.
            FloatArray(maxOf(bufLen + tail, minRun)).also { System.arraycopy(buf, 0, it, 0, bufLen) }
        } else {
            buf.copyOf(bufLen)
        }
        val out = fit(denoise(input), input.size)
        blocks++

        val from = (ownFrom - start).toInt()
        val count = (ownTo - ownFrom).toInt()
        val kept = out.copyOfRange(from, from + count)
        if (hasPending) {
            // The fade can be cut short only by a stream that ends inside it,
            // which a last block reaching back over [fade] samples cannot.
            val n = minOf(fade, count)
            for (i in 0 until n) {
                val w = fadeIn(i, fade)
                kept[i] = pending[i] * (1f - w) + kept[i] * w
            }
        }
        if (!last) {
            // This block's output over the next one's fade-in, which ends at
            // the boundary and so lies inside what it owns.
            val tailFrom = (boundary - fade - start).toInt()
            System.arraycopy(out, tailFrom, pending, 0, fade)
            hasPending = true
            // Emit up to where the next block takes over (its fade-in).
            emit(kept, count - fade)
            emitted += count - fade
            // Keep the input the next block starts from.
            val nextStart = inputStart(k + 1)
            val drop = (nextStart - bufStart).toInt()
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart = nextStart
        } else {
            emit(kept, count)
            emitted += count
            bufLen = 0
            hasPending = false
        }
    }

    companion object {
        /** 30 s a block: about 2 MB of input, about 15 MB inside the denoiser. */
        const val HOP = 30 * 16_000

        /** 2 s thrown away at the start of each block after the first. */
        const val WARM_UP = 2 * 16_000

        /** A quarter-second cross-fade at every seam. */
        const val FADE = 4_000

        /** 64 ms past each block's end: two of GTCRN's 512-sample frames. */
        const val TAIL = 1_024

        /**
         * sherpa-onnx v2.0.3's offline denoiser crashes the process on an
         * empty input (measured: SIGSEGV), and returns nothing for under one
         * 256-sample hop. Nothing shorter than a frame is ever passed.
         */
        const val MIN_RUN = 512

        /**
         * The weight of the new block at sample [i] of an [n]-sample fade:
         * a raised cosine from 0 to 1. The old block gets 1 minus it.
         */
        fun fadeIn(i: Int, n: Int): Float =
            (0.5 - 0.5 * kotlin.math.cos(Math.PI * (i + 0.5) / n)).toFloat()

        /** [out] cut or zero-padded to [n] samples (the denoiser returns fewer). */
        fun fit(out: FloatArray, n: Int): FloatArray =
            if (out.size == n) out else out.copyOf(n)

        /**
         * How much extra the blocks cost over one whole-file run, as a
         * fraction: each later block repeats its lead-in and run-out.
         */
        fun overhead(hop: Int = HOP, warmUp: Int = WARM_UP, fade: Int = FADE, tail: Int = TAIL): Double =
            (warmUp + fade + tail).toDouble() / hop
    }
}
