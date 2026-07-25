package com.meetily.mobile.data

/**
 * Reconciliation for the app's one genuinely concurrent write: a background
 * writer appending transcript segments while a screen holds an older copy of
 * the same meeting.
 *
 * Screens are whole-object writers — they load a Meeting, mutate it (edit a
 * line, tag a speaker, toggle a highlight) and write the whole thing back.
 * That is fine until something else appends a segment in between, because the
 * screen's save would erase it. Folding beats replacing here: the screen's
 * copy carries user edits that must survive, so we take only the segments it
 * has never seen and leave everything else alone.
 */
object MeetingMerge {

    /**
     * Segments in [disk] that [known] has never seen.
     *
     * Matching is by timestamp with multiplicity rather than by position or
     * count: a line edited locally keeps its timestamp, and a split line
     * produces two segments sharing one timestamp, so neither looks "new".
     * Only a timestamp appearing on disk more often than in memory counts.
     */
    fun lateSegments(
        known: List<TranscriptSegment>,
        disk: List<TranscriptSegment>
    ): List<TranscriptSegment> {
        if (disk.isEmpty()) return emptyList()
        val budget = HashMap<Long, Int>(known.size * 2)
        for (segment in known) {
            budget[segment.timestampMs] = (budget[segment.timestampMs] ?: 0) + 1
        }
        val late = mutableListOf<TranscriptSegment>()
        for (segment in disk) {
            val remaining = budget[segment.timestampMs] ?: 0
            if (remaining > 0) {
                budget[segment.timestampMs] = remaining - 1
            } else {
                late.add(segment)
            }
        }
        return late
    }

    /**
     * Folds segments that landed on disk after [meeting] was loaded back into
     * it, in timestamp order. Returns how many were recovered.
     *
     * The sort is stable, so split lines sharing a timestamp keep the order
     * the user put them in.
     */
    fun foldLateSegments(meeting: Meeting, disk: Meeting): Int {
        val late = lateSegments(meeting.segments, disk.segments)
        if (late.isEmpty()) return 0
        meeting.segments.addAll(late)
        meeting.segments.sortBy { it.timestampMs }
        return late.size
    }
}
