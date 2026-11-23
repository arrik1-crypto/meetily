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
        attachmentsList = meeting.attachmentsList.toMutableList(),
        summaries = meeting.summaries.toMutableList()
    )

    /**
     * A re-timing run (AlignService) saves measured word timings into the
     * stored lines pass by pass, while a screen's copy of those lines may
     * still hold the estimated ones; written back whole, it would put them
     * back. So a line of [out] without measured timings of its own
     * (WordTimes.ALIGNED) that reads exactly as a stored line whose timings
     * were measured (same stamp, place in the recording and text: the
     * run's WordAlign.Target) takes the stored timings, which were measured
     * for these very words. Everything else the screen set on the line (a
     * highlight, a speaker) stays; a line whose wording the screen changed
     * no longer matches and keeps its edit. Returns how many lines took
     * the stored timings.
     */
    fun keepMeasuredTimings(out: MutableList<TranscriptSegment>, disk: List<TranscriptSegment>): Int {
        val measured = HashMap<WordAlign.Target, List<WordStamp>>()
        for (stored in disk) {
            if (stored.wordTimes != WordTimes.ALIGNED) continue
            val words = stored.words?.takeIf { it.isNotEmpty() } ?: continue
            val key = WordAlign.target(stored) ?: continue
            measured.putIfAbsent(key, words)
        }
        if (measured.isEmpty()) return 0
        var kept = 0
        for (i in out.indices) {
            val line = out[i]
            if (line.wordTimes == WordTimes.ALIGNED) continue
            val key = WordAlign.target(line) ?: continue
            val words = measured[key] ?: continue
            out[i] = line.copy(words = words, wordTimes = WordTimes.ALIGNED)
            kept++
        }
        return kept
    }

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
     *   back in, exactly as [foldLateSegments] does; word timings measured
     *   in the background for a line the screen still reads the same are
     *   kept ([keepMeasuredTimings]). Otherwise the stored transcript is
     *   taken as it is.
     * - The summary. The screen never generates one, so a new summary on disk
     *   takes its action items (with the record of the run that wrote them),
     *   stale flag, template and model with it: a tick made on the old list
     *   must not bring back items the new summary dropped. The summaries
     *   kept beside it are merged per template, with
     *   the active one (SummaryAlternatives.merge), so a switch or deletion
     *   made here and a summary a run added meanwhile both survive.
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
            keepMeasuredTimings(out, disk.segments)
            val late = lateSegments(disk.segments, segmentsSeenThroughMs)
            if (late.isNotEmpty()) {
                out.addAll(late)
                out.sortBy { it.timestampMs }
            }
            out
        } else {
            disk.segments.toMutableList()
        }
        val summaryMerge = SummaryAlternatives.merge(
            SummaryAlternatives.Fields.of(base),
            SummaryAlternatives.Fields.of(ours),
            SummaryAlternatives.Fields.of(disk)
        )
        val summaryFields = summaryMerge.fields
        val newSummary = summaryMerge.newSummary
        return Meeting(
            id = disk.id,
            title = pick(base.title, ours.title, disk.title),
            createdAtMs = disk.createdAtMs,
            segments = segments,
            notes = pick(base.notes, ours.notes, disk.notes),
            notesOriginal = pick(base.notesOriginal, ours.notesOriginal, disk.notesOriginal),
            summary = summaryFields.summary,
            attendees = pick(base.attendees, ours.attendees, disk.attendees).toMutableList(),
            qa = pick(base.qa, ours.qa, disk.qa).toMutableList(),
            actionItems = if (newSummary) {
                disk.actionItems.toMutableList()
            } else {
                pick(base.actionItems, ours.actionItems, disk.actionItems).toMutableList()
            },
            photos = pick(base.photos, ours.photos, disk.photos).toMutableList(),
            audioFile = pick(base.audioFile, ours.audioFile, disk.audioFile),
            // Made in the background (ClearVoiceService), deleted from the
            // meeting screen: whichever side changed it since base wins.
            clearVoiceFile = pick(base.clearVoiceFile, ours.clearVoiceFile, disk.clearVoiceFile),
            tags = pick(base.tags, ours.tags, disk.tags).toMutableList(),
            chapters = pick(base.chapters, ours.chapters, disk.chapters).toMutableList(),
            photoTexts = pick(base.photoTexts, ours.photoTexts, disk.photoTexts).toMutableMap(),
            attachmentsList = pick(
                base.attachmentsList, ours.attachmentsList, disk.attachmentsList
            ).toMutableList(),
            starred = pick(base.starred, ours.starred, disk.starred),
            transcriptModel = pick(base.transcriptModel, ours.transcriptModel, disk.transcriptModel),
            summaryStale = summaryFields.stale,
            // Which template and model wrote the summary travel with it.
            summaryTemplate = summaryFields.template,
            summaryModel = summaryFields.model,
            followsEvent = pick(base.followsEvent, ours.followsEvent, disk.followsEvent),
            // Written by the transcription pass that made the words, and
            // by the screen only through a re-transcription it started.
            language = pick(base.language, ours.language, disk.language),
            translated = pick(base.translated, ours.translated, disk.translated),
            // Recorded with the words by the transcription pass. A screen
            // changes it only by removing the audio, which ends it.
            transcriptResumeMs = pick(
                base.transcriptResumeMs, ours.transcriptResumeMs, disk.transcriptResumeMs
            ),
            // No screen edits these. Left out, a screen save dropped them, and
            // the marks a stopped transcription had not reached yet were lost
            // before the pass that continues it could place them.
            highlightMarksMs = disk.highlightMarksMs.toMutableList(),
            summaryAtMs = summaryFields.atMs,
            summaryLanguage = summaryFields.language,
            summaries = summaryFields.alternatives.toMutableList(),
            // Which run wrote the items travels with them.
            actionItemsTemplate = if (newSummary) {
                disk.actionItemsTemplate
            } else {
                pick(base.actionItemsTemplate, ours.actionItemsTemplate, disk.actionItemsTemplate)
            },
            actionItemsModel = if (newSummary) {
                disk.actionItemsModel
            } else {
                pick(base.actionItemsModel, ours.actionItemsModel, disk.actionItemsModel)
            }
        )
    }
}
