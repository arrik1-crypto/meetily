package com.meetily.mobile.whisper

/**
 * Wall-clock estimate for an in-flight import.
 *
 * A percentage alone cannot tell a working import from a dead one: on a long
 * file with a heavy model, 1% is over half a minute of audio and many
 * minutes of real time, so the bar sits still long enough to look broken.
 * A remaining-time figure that moves is what makes the difference visible.
 */
object ImportEta {

    /**
     * Ignore the first stretch: a rate measured over a couple of chunks
     * swings wildly and an ETA that lurches is worse than none.
     */
    const val MIN_SAMPLE_MS = 20_000L

    /**
     * Estimated milliseconds left, or null when there is not yet enough
     * signal to say anything honest.
     */
    fun remainingMs(processedAudioMs: Long, totalAudioMs: Long, elapsedMs: Long): Long? {
        if (processedAudioMs < MIN_SAMPLE_MS) return null
        if (totalAudioMs <= processedAudioMs) return null
        if (elapsedMs <= 0L) return null
        val msPerAudioMs = elapsedMs.toDouble() / processedAudioMs.toDouble()
        return ((totalAudioMs - processedAudioMs) * msPerAudioMs).toLong()
    }

    /** Rounded to whole minutes, floored at 1 so it never reads "0 min left". */
    fun remainingMinutes(
        processedAudioMs: Long,
        totalAudioMs: Long,
        elapsedMs: Long
    ): Long? {
        val ms = remainingMs(processedAudioMs, totalAudioMs, elapsedMs) ?: return null
        return ((ms + 30_000L) / 60_000L).coerceAtLeast(1L)
    }
}
