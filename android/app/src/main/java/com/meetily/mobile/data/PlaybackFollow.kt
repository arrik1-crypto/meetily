package com.meetily.mobile.data

/**
 * Maps a playback position onto the transcript: which line is being spoken,
 * and which word inside it.
 *
 * Only lines carrying an [TranscriptSegment.audioMs] can be located at all —
 * lines typed by hand, or produced by the system recognizer, have no place on
 * the audio timeline. Those are skipped rather than guessed at, so following
 * degrades to "the last locatable line" instead of jumping somewhere wrong.
 */
object PlaybackFollow {

    /** (audio offset, segment index) for every locatable line, ascending. */
    fun timeline(segments: List<TranscriptSegment>): List<Pair<Long, Int>> =
        segments.asSequence()
            .mapIndexedNotNull { index, segment -> segment.audioMs?.let { it to index } }
            .sortedBy { it.first }
            .toList()

    /**
     * Segment index playing at [positionMs], or -1 before the first line.
     *
     * A line stays current until the next one starts, so the highlight rests
     * on the last thing said through a pause rather than blinking off in
     * every gap.
     */
    fun segmentAt(timeline: List<Pair<Long, Int>>, positionMs: Long): Int {
        val at = floorIndex(timeline.size, positionMs) { timeline[it].first }
        return if (at < 0) -1 else timeline[at].second
    }

    /**
     * Word index within [words] at [offsetMs] from the start of its line, or
     * -1 before the first word.
     */
    fun wordAt(words: List<WordStamp>, offsetMs: Long): Int =
        floorIndex(words.size, offsetMs) { words[it].ms }

    /**
     * Largest index whose key is <= [target], or -1 when every key is larger.
     * Binary search: this runs on every playback tick, over transcripts that
     * can hold thousands of lines.
     */
    private inline fun floorIndex(size: Int, target: Long, key: (Int) -> Long): Int {
        var low = 0
        var high = size - 1
        var best = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (key(mid) <= target) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}
