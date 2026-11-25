package com.meetily.mobile.data

/**
 * The meeting screen's rules for when, and on what, an edit confirmed in a
 * dialog may land. Pure, unit-tested (EditTargetsTest).
 *
 * A dialog on a transcript line or an action item opens on a position in a
 * list that can be renumbered while it is up: a passage removed in the
 * background deletes lines, and a summary run that lands replaces the action
 * items. The position alone then names another line or task, and OK would
 * edit, delete, split, tag or remind the wrong one. So a dialog keeps the
 * entry it opened on, and its OK finds that entry again ([line],
 * [actionItem]); when it is gone, nothing is changed.
 */
object EditTargets {

    /**
     * Where [target], the line a dialog opened on at [index], is in
     * [segments] now, or -1 when it is no longer there unchanged. The same
     * object first (the list was only renumbered around it), then an equal
     * line nearest [index] (a sync re-read the stored lines as new objects).
     * A line whose text, speaker or timing changed meanwhile is not taken.
     */
    fun line(segments: List<TranscriptSegment>, index: Int, target: TranscriptSegment): Int =
        same(segments, index, target)

    /**
     * Where [target], the action item a dialog opened on at [index], is in
     * [items] now, or -1 when it is gone. As [line], then the nearest item
     * with the same task: ticking it done or a run that kept the task (it
     * matches items by their task) leaves it the same task.
     */
    fun actionItem(items: List<ActionItem>, index: Int, target: ActionItem): Int {
        val found = same(items, index, target)
        if (found >= 0) return found
        return nearest(items, index) { it.task == target.task }
    }

    /**
     * True while a job writes meeting [meetingId]'s transcript lines back
     * whole at each of its saves, so a line edited on the meeting screen
     * meanwhile is put back the way the job has it: the recording
     * ([recordingId] is the meeting it records), or a transcription filling
     * the meeting ([importId]: an import, an audio-only recording's first
     * transcript, a continuation). An accuracy check that only stages its
     * words for review ([importStagesOnly]) does not write the lines.
     */
    fun linesOwnedByJob(
        meetingId: String,
        recording: Boolean,
        recordingId: String?,
        importing: Boolean,
        importId: String?,
        importStagesOnly: Boolean
    ): Boolean =
        (recording && recordingId == meetingId) ||
            (importing && importId == meetingId && !importStagesOnly)

    private fun <T> same(list: List<T>, index: Int, target: T): Int {
        if (list.getOrNull(index) === target) return index
        val moved = list.indexOfFirst { it === target }
        if (moved >= 0) return moved
        return nearest(list, index) { it == target }
    }

    /** The index nearest [index] whose entry matches, the earlier one on a tie; -1 for none. */
    private fun <T> nearest(list: List<T>, index: Int, matches: (T) -> Boolean): Int {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for ((i, entry) in list.withIndex()) {
            if (!matches(entry)) continue
            val distance = if (i >= index) i - index else index - i
            if (distance < bestDistance) {
                best = i
                bestDistance = distance
            }
        }
        return best
    }
}
