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

    /**
     * Identifies the transcript's wording, for "is this still the transcript
     * I started from". Timestamps and text only: a speaker tag or a highlight
     * does not make a summary out of date, a changed or re-split line does.
     */
    fun transcriptFingerprint(segments: List<TranscriptSegment>): Int {
        var hash = segments.size
        for (segment in segments) {
            hash = 31 * hash + segment.timestampMs.hashCode()
            hash = 31 * hash + segment.text.hashCode()
        }
        return hash
    }

    /**
     * A copy of [meeting] whose collections are its own, so a screen can keep
     * editing its live copy while a worker reads this one.
     *
     * The elements are immutable data classes and are shared. Every
     * collection field must be listed here — one left out would be shared
     * with the live copy.
     */
    fun snapshot(meeting: Meeting): Meeting = meeting.copy(
        segments = meeting.segments.toMutableList(),
        attendees = meeting.attendees.toMutableList(),
        qa = meeting.qa.toMutableList(),
        actionItems = meeting.actionItems.toMutableList(),
        photos = meeting.photos.toMutableList(),
        tags = meeting.tags.toMutableList(),
        chapters = meeting.chapters.toMutableList(),
        photoTexts = meeting.photoTexts.toMutableMap(),
        attachmentsList = meeting.attachmentsList.toMutableList()
    )

    /**
     * A three-way merge of a screen's copy onto the stored one, field by field.
     *
     * [base] is what the screen last knew to be on disk, [ours] its current
     * copy, [disk] what is stored now. A field the screen changed since
     * [base] is the user's edit and wins; every other field is taken from
     * [disk], so a summary, a Q&A answer, chapters or an accepted accuracy
     * check that a background writer saved meanwhile is never put back the
     * way the screen first loaded it.
     *
     * Two fields need more than that:
     * - The transcript. If the screen edited it, its lines win as a whole
     *   (it carries deletions and splits that cannot be merged line by line)
     *   and only segments stamped after [segmentsSeenThroughMs] are folded
     *   back in, exactly as [foldLateSegments] does. Otherwise the stored
     *   transcript is taken as it is.
     * - The summary. The screen never writes one, so a new summary on disk
     *   takes its action items and stale flag with it: a tick made on the
     *   old list must not bring back items the new summary dropped.
     */
    fun mergeScreenEdits(
        base: Meeting,
        ours: Meeting,
        disk: Meeting,
        segmentsSeenThroughMs: Long
    ): Meeting {
        fun <T> pick(b: T, o: T, d: T): T = if (o != b) o else d
        val segments = if (ours.segments != base.segments) {
            val out = ours.segments.toMutableList()
            val late = lateSegments(disk.segments, segmentsSeenThroughMs)
            if (late.isNotEmpty()) {
                out.addAll(late)
                out.sortBy { it.timestampMs }
            }
            out
        } else {
            disk.segments.toMutableList()
        }
        val newSummary = disk.summary != base.summary
        return Meeting(
            id = disk.id,
            title = pick(base.title, ours.title, disk.title),
            createdAtMs = disk.createdAtMs,
            segments = segments,
            notes = pick(base.notes, ours.notes, disk.notes),
            notesOriginal = pick(base.notesOriginal, ours.notesOriginal, disk.notesOriginal),
            summary = if (newSummary) disk.summary else pick(base.summary, ours.summary, disk.summary),
            attendees = pick(base.attendees, ours.attendees, disk.attendees).toMutableList(),
            qa = pick(base.qa, ours.qa, disk.qa).toMutableList(),
            actionItems = if (newSummary) {
                disk.actionItems.toMutableList()
            } else {
                pick(base.actionItems, ours.actionItems, disk.actionItems).toMutableList()
            },
            photos = pick(base.photos, ours.photos, disk.photos).toMutableList(),
            audioFile = pick(base.audioFile, ours.audioFile, disk.audioFile),
            tags = pick(base.tags, ours.tags, disk.tags).toMutableList(),
            chapters = pick(base.chapters, ours.chapters, disk.chapters).toMutableList(),
            photoTexts = pick(base.photoTexts, ours.photoTexts, disk.photoTexts).toMutableMap(),
            attachmentsList = pick(
                base.attachmentsList, ours.attachmentsList, disk.attachmentsList
            ).toMutableList(),
            starred = pick(base.starred, ours.starred, disk.starred),
            transcriptModel = pick(base.transcriptModel, ours.transcriptModel, disk.transcriptModel),
            summaryStale = if (newSummary) {
                disk.summaryStale
            } else {
                pick(base.summaryStale, ours.summaryStale, disk.summaryStale)
            },
            followsEvent = pick(base.followsEvent, ours.followsEvent, disk.followsEvent)
        )
    }
}
