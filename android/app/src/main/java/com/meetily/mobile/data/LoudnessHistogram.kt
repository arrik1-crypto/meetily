package com.meetily.mobile.data

/**
 * Streaming RMS-per-bucket accumulator, folded down to a fixed bar count once
 * the total length is known.
 *
 * The waveform needs N bars across the whole recording, but which bar a sample
 * belongs to depends on the length, and the length is only known after
 * decoding. Decoding twice is far too slow. So samples go into a histogram
 * much finer than the bar count, and that is folded down at the end.
 *
 * The bucket width doubles whenever the buckets run out, which is what makes
 * this correct at any length. The previous version pinned to the last bucket
 * instead, so everything past 4096 x 4000 samples — seventeen minutes at
 * 16 kHz, not the seventeen hours its comment claimed — piled into a single
 * bucket and the drawn waveform bore no relation to the audio.
 *
 * Split out of Waveform so it can be tested without a decoder or a Context.
 */
internal class LoudnessHistogram(
    private val buckets: Int = 4096,
    initialSamplesPerBucket: Long = 4_000L
) {
    private val sums = DoubleArray(buckets)
    private val counts = LongArray(buckets)
    private var index = 0
    private var samplesPerBucket = initialSamplesPerBucket
    private var sinceStep = 0L

    /** Samples accumulated so far. */
    var total = 0L
        private set

    /** Adds the first [n] samples of [pcm]. */
    fun add(pcm: FloatArray, n: Int = pcm.size) {
        val count = minOf(n, pcm.size)
        for (i in 0 until count) {
            val v = pcm[i].toDouble()
            sums[index] += v * v
            counts[index]++
            total++
            if (++sinceStep >= samplesPerBucket) {
                sinceStep = 0
                if (++index >= buckets) halve()
            }
        }
    }

    /**
     * Folds adjacent pairs together and doubles the bucket width, freeing the
     * top half for more audio. Sums and counts are both additive, so the RMS
     * that comes out at the end is unchanged by this — only the time
     * resolution drops, and never below half the bucket count.
     */
    private fun halve() {
        val half = buckets / 2
        for (i in 0 until half) {
            sums[i] = sums[2 * i] + sums[2 * i + 1]
            counts[i] = counts[2 * i] + counts[2 * i + 1]
        }
        java.util.Arrays.fill(sums, half, buckets, 0.0)
        java.util.Arrays.fill(counts, half, buckets, 0L)
        index = half
        samplesPerBucket *= 2
    }

    /**
     * RMS per bar, scaled so the loudest bar is 1. Returns all-zero bars when
     * nothing was added, which draws as a flat line rather than throwing.
     */
    fun bars(bars: Int): FloatArray {
        val out = FloatArray(bars)
        if (total <= 0L || bars <= 0) return out

        val barSums = DoubleArray(bars)
        val barCounts = LongArray(bars)
        val used = index + 1
        for (i in 0 until used) {
            val bar = (i.toLong() * bars / used).toInt().coerceIn(0, bars - 1)
            barSums[bar] += sums[i]
            barCounts[bar] += counts[i]
        }
        for (i in 0 until bars) {
            out[i] = if (barCounts[i] <= 0L) 0f else Math.sqrt(barSums[i] / barCounts[i]).toFloat()
        }
        val loudest = out.max()
        if (loudest <= 0f) return out
        return FloatArray(bars) { out[it] / loudest }
    }
}
