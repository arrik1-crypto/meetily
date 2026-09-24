package com.meetily.mobile.whisper

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming band-limited resampler (Kaiser-windowed sinc), for imported audio
 * on its way to 16 kHz.
 *
 * It replaced a two-tap linear interpolator with no low-pass in front of it.
 * Taking 44.1/48 kHz audio down to 16 kHz that way folds everything between
 * 8 kHz and the source's top (sibilants, hiss, music beds, the 11-16 kHz an
 * AAC or MP3 encode keeps) back into the 0-8 kHz band the speech models
 * analyse. The kernel here is the anti-alias low-pass and the interpolator in
 * one: each output sample is a weighted sum of the source samples around its
 * position, cut off a little below the lower of the two Nyquist rates.
 *
 * Cost is only paid per OUTPUT sample: about 70 multiply-adds at 48 kHz in,
 * from a precomputed kernel table stepped by whole source samples, so there
 * is no per-tap trigonometry. State carries across [process] calls, so any
 * chunking of the input gives the same output.
 *
 * Pure Kotlin (no Android types) so it is unit-tested directly.
 */
class SincResampler(private val srcRate: Int, private val dstRate: Int) {

    private val passthrough = srcRate == dstRate || srcRate <= 0 || dstRate <= 0

    /** Source samples advanced per output sample. */
    private val step = srcRate.toDouble() / dstRate

    /** Kernel half-width, in source samples. */
    private val halfWidth: Double

    /** Kernel sampled every 1/[PHASES] source sample over [-halfWidth, halfWidth]. */
    private val table: FloatArray

    /** Source samples kept from earlier calls (plus leading silence at the start). */
    private var history = FloatArray(0)

    /** Position of the next output, in source samples from history[0]. */
    private var t = 0.0

    init {
        if (passthrough) {
            halfWidth = 0.0
            table = FloatArray(0)
        } else {
            // Cut-off as a fraction of the source rate: a little under the
            // lower Nyquist so the transition band ends near it.
            val fc = CUTOFF * minOf(srcRate, dstRate) / 2.0 / srcRate
            halfWidth = ZERO_CROSSINGS / (2.0 * fc)
            val size = (2 * halfWidth * PHASES).toInt() + 2
            val i0Beta = besselI0(KAISER_BETA)
            table = FloatArray(size) { i ->
                val d = i.toDouble() / PHASES - halfWidth
                val x = d / halfWidth
                if (x <= -1.0 || x >= 1.0) {
                    0f
                } else {
                    val arg = 2.0 * fc * d
                    val sinc = if (arg == 0.0) 1.0 else sin(PI * arg) / (PI * arg)
                    val window = besselI0(KAISER_BETA * sqrt(1.0 - x * x)) / i0Beta
                    (2.0 * fc * sinc * window).toFloat()
                }
            }
            // Leading silence so the first output has a full left side.
            val lead = ceil(halfWidth).toInt()
            history = FloatArray(lead)
            t = lead.toDouble()
        }
    }

    /**
     * Resamples the next [input] chunk. The output lags the input by about
     * half a kernel (well under a millisecond), which is never flushed — a
     * negligible tail for audio headed to a speech model.
     */
    fun process(input: FloatArray): FloatArray {
        if (passthrough || input.isEmpty()) return input
        val src = FloatArray(history.size + input.size)
        System.arraycopy(history, 0, src, 0, history.size)
        System.arraycopy(input, 0, src, history.size, input.size)
        val last = src.size - 1

        val maxOut = ((last - halfWidth - t) / step).toInt() + 2
        val out = FloatArray(maxOut.coerceAtLeast(0))
        var count = 0
        var pos = t
        while (pos + halfWidth <= last && count < out.size) {
            val kStart = ceil(pos - halfWidth).toInt().coerceAtLeast(0)
            val kEnd = floor(pos + halfWidth).toInt()
            // Table index of tap kStart; each following tap is one source
            // sample further on, i.e. PHASES table entries back.
            var idx = ((pos - kStart + halfWidth) * PHASES + 0.5).toInt()
            var acc = 0f
            var k = kStart
            while (k <= kEnd) {
                if (idx in table.indices) acc += src[k] * table[idx]
                idx -= PHASES
                k++
            }
            out[count++] = acc
            pos += step
        }

        // Keep only what the next output's left side still needs.
        val keepFrom = floor(pos - halfWidth).toInt().coerceIn(0, src.size)
        history = src.copyOfRange(keepFrom, src.size)
        t = pos - keepFrom
        return if (count == out.size) out else out.copyOf(count)
    }

    private companion object {
        /** Cut-off as a fraction of the lower Nyquist rate. */
        const val CUTOFF = 0.9

        /** Sinc zero crossings on each side: sets the kernel length. */
        const val ZERO_CROSSINGS = 10.0

        /** Around 70 dB of stop-band rejection. */
        const val KAISER_BETA = 7.0

        /** Kernel table resolution per source sample. */
        const val PHASES = 256

        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2.0
            var k = 1
            while (k < 50) {
                term *= (half / k) * (half / k)
                sum += term
                if (term < sum * 1e-12) break
                k++
            }
            return sum
        }
    }
}
