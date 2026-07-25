package com.meetily.mobile.whisper

import com.meetily.mobile.data.WordStamp

/**
 * Splits one Whisper result back into the chunks it was batched from.
 *
 * Whisper encodes a full 30-second window on every call regardless of how
 * much audio it was actually given, so handing it a 6-second chunk costs the
 * same as handing it 28 seconds. The importer therefore batches consecutive
 * chunks up to one window — but the transcript still needs one segment per
 * chunk, for timestamps, playback seek and speaker attribution. Word
 * timings are what makes that possible after the fact.
 */
object BatchSplit {

    /** One chunk's place inside the concatenated batch buffer. */
    data class Part(val offsetMs: Long, val durationMs: Long)

    /**
     * Distributes [words] across [parts] by time, rebasing each word onto the
     * start of the part it belongs to. Returns one list per part, in order.
     */
    fun split(parts: List<Part>, words: List<WordStamp>): List<List<WordStamp>> {
        val out = List(parts.size) { mutableListOf<WordStamp>() }
        if (parts.isEmpty()) return out
        for (word in words) {
            val index = indexFor(parts, word.ms)
            if (index < 0) continue
            out[index].add(
                WordStamp(
                    (word.ms - parts[index].offsetMs).coerceAtLeast(0L),
                    word.text
                )
            )
        }
        return out
    }

    /**
     * The part covering [ms], or the nearest one.
     *
     * Whisper's word times drift a little from the samples it was fed, so a
     * word can land in the seam between two parts or just past the end.
     * Snapping to the closest part keeps the text; dropping it would silently
     * lose words at every chunk boundary.
     */
    fun indexFor(parts: List<Part>, ms: Long): Int {
        if (parts.isEmpty()) return -1
        for (i in parts.indices) {
            val part = parts[i]
            if (ms >= part.offsetMs && ms < part.offsetMs + part.durationMs) return i
        }
        var best = 0
        var bestDistance = Long.MAX_VALUE
        for (i in parts.indices) {
            val part = parts[i]
            val distance = if (ms < part.offsetMs) {
                part.offsetMs - ms
            } else {
                ms - (part.offsetMs + part.durationMs)
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }
}
