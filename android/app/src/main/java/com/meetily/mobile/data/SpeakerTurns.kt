package com.meetily.mobile.data

/**
 * Collapses transcript segments into speaker turns for the Audio tab's strip.
 *
 * Pure, so the awkward parts are testable: consecutive lines from one person
 * are one turn rather than fifty, speakers get stable colour slots in the
 * order they first speak, and lines with no audio offset are skipped instead
 * of being drawn at zero.
 */
object SpeakerTurns {

    data class Turn(val startMs: Long, val endMs: Long, val slot: Int)

    data class Result(
        val turns: List<Turn>,
        /** Display name per slot, in slot order — the legend. */
        val names: List<String>
    )

    /**
     * @param totalMs the recording's length, used to close the final turn.
     * @param nameOf renders a segment's speaker; null when unattributed.
     */
    fun build(
        segments: List<TranscriptSegment>,
        totalMs: Long,
        nameOf: (TranscriptSegment) -> String?
    ): Result {
        val slots = LinkedHashMap<String, Int>()
        val turns = mutableListOf<Turn>()
        var current: String? = null
        var currentStart = 0L
        var lastEnd = 0L

        for (segment in segments) {
            // No offset means nothing can be said about WHEN this was said.
            // Drawing it at zero would put every hand-typed line at the start
            // of the recording, which is worse than leaving a gap.
            val at = segment.audioMs ?: continue
            val name = nameOf(segment) ?: continue
            if (name != current) {
                if (current != null) {
                    turns.add(Turn(currentStart, maxOf(lastEnd, currentStart + 1), slots[current]!!))
                }
                current = name
                currentStart = at
                slots.getOrPut(name) { slots.size }
            }
            // A turn runs until the next one starts; the segment's own end is
            // unknown, so the following line's start is the best available
            // boundary. The last turn runs to the end of the recording.
            lastEnd = at
        }
        if (current != null) {
            turns.add(
                Turn(
                    currentStart,
                    maxOf(totalMs, currentStart + 1),
                    slots[current]!!
                )
            )
        }
        // Close each turn at the next one's start rather than at its own last
        // line, so the strip has no gaps between consecutive speakers.
        val closed = turns.mapIndexed { i, turn ->
            val end = turns.getOrNull(i + 1)?.startMs ?: turn.endMs
            Turn(turn.startMs, maxOf(end, turn.startMs + 1), turn.slot)
        }
        return Result(closed, slots.keys.toList())
    }

    /** The turn covering [positionMs], or null in a gap. */
    fun turnAt(turns: List<Turn>, positionMs: Long): Turn? =
        turns.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
}
