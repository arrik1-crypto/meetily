package com.meetily.mobile.data

/**
 * Reconciliation for the app's one genuinely concurrent write: a background
 * writer appending transcript segments while a screen holds an older copy of
 * the same meeting.
 *
 * Screens are whole-object writers — they load a Meeting, mutate it (edit a
 * line, tag a speaker, delete a line) and write the whole thing back. That is
 * fine until something else appends a segment in between, because the
 * screen's save would erase it.
 *
 * The rule is deliberately append-only, anchored on a high-water mark rather
 * than on set difference. "On disk but not in memory" cannot distinguish a
 * line that just arrived from a line the user just deleted, and guessing
 * wrong in that direction resurrects deleted lines forever. Only a segment
 * stamped later than anything the screen has ever seen counts as new — which
 * is exactly the shape of the late chunk RecordingService delivers after a
 * session closes.
 */
object MeetingMerge {

    /** The newest timestamp in [segments], or [Long.MIN_VALUE] if empty. */
    fun highWaterMs(segments: List<TranscriptSegment>): Long =
        segments.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE

    /** Segments in [disk] stamped after [afterMs]. */
    fun lateSegments(disk: List<TranscriptSegment>, afterMs: Long): List<TranscriptSegment> =
        disk.filter { it.timestampMs > afterMs }

    /**
     * Folds segments that landed on disk after [afterMs] into [meeting], in
     * timestamp order. Returns how many were recovered.
     *
     * The sort is stable, so split lines sharing a timestamp keep the order
     * the user put them in.
     */
    fun foldLateSegments(meeting: Meeting, disk: Meeting, afterMs: Long): Int {
        val late = lateSegments(disk.segments, afterMs)
        if (late.isEmpty()) return 0
        meeting.segments.addAll(late)
        meeting.segments.sortBy { it.timestampMs }
        return late.size
    }
}
