package com.meetily.mobile.data

/**
 * What "Remove passage" does to a meeting's transcript and the things timed
 * against its audio, worked out before anything is touched. Pure, so every
 * rule is unit-tested; PassageService applies the same plan to the stored
 * meeting and the meeting screen to its own copy.
 *
 * The audio in the span becomes silence rather than being cut out, so the
 * recording keeps its length and every time outside the span (a line's
 * audioMs, its word timings, a highlight mark, a resume point) stays exactly
 * where it was. Only what lies inside the span goes:
 * - a line inside the span is deleted;
 * - a line across one of its edges keeps the words outside it, cut at word
 *   boundaries, when it has word timings that can be placed in its text;
 *   otherwise the edge moves outward to the line's own start or end, so the
 *   whole line goes (the screen shows the span after that move);
 * - a highlight mark inside the span is dropped, so a later transcription
 *   pass cannot star the line before the span with it;
 * - a chapter whose first lines went now starts at its first kept line,
 *   which is the first line after the span; one left with no lines goes.
 *
 * A line's extent on the audio runs from its audioMs to the next later
 * line's audioMs (the last one's to the end of the audio), and a word's from
 * its start to the next word's: the pause after a word belongs to it, which
 * is what lets a span end exactly where the next kept word starts. Lines
 * with no audio position are not on the timeline and are always kept.
 */
object PassagePlan {

    /** [startMs, endMs) of the audio; [endMs] may be [END]: to the end of the recording. */
    data class Span(val startMs: Long, val endMs: Long) {
        val isEmpty: Boolean get() = endMs <= startMs

        fun contains(ms: Long): Boolean = ms >= startMs && ms < endMs

        /** Its length, or null when it runs to an end that is not known. */
        fun lengthMs(): Long? = if (endMs == END) null else (endMs - startMs).coerceAtLeast(0L)
    }

    /** A span end that means "to the end of the recording", whatever its length. */
    const val END = Long.MAX_VALUE

    data class Plan(
        /** The span as asked for. */
        val asked: Span,
        /** The span that is removed: [asked], with edges moved out over lines that could not be cut. */
        val span: Span,
        /** The transcript afterwards, in the original order. */
        val segments: List<TranscriptSegment>,
        /** Indices into the original transcript of the lines deleted whole. */
        val removedLines: List<Int>,
        /** Indices of the lines kept with words cut off at an edge. */
        val trimmedLines: List<Int>,
        /** How many of [removedLines] were highlighted. */
        val highlightedRemoved: Int,
        /** The highlight marks afterwards, and how many were dropped. */
        val marks: List<Long>,
        val marksDropped: Int,
        /** The chapters afterwards, how many now start later, and how many went. */
        val chapters: List<Chapter>,
        val chaptersMoved: Int,
        val chaptersDropped: Int,
        /**
         * The wall-clock time the removed lines were spoken over, for the
         * photos taken meanwhile ([photosTakenDuring]); null when no line
         * with a time was touched.
         */
        val wallStartMs: Long?,
        val wallEndMs: Long?
    ) {
        /** The span's edges were moved outward to whole lines. */
        val snapped: Boolean get() = span != asked

        /** Lines were deleted or shortened. */
        val changesTranscript: Boolean get() = removedLines.isNotEmpty() || trimmedLines.isNotEmpty()
    }

    /** A line on the audio timeline: its index in the transcript and its extent. */
    data class Line(val index: Int, val startMs: Long, val endMs: Long)

    /**
     * The timed lines of [segments], ordered by their audio position (lines
     * sharing a position keep their transcript order), each running to the
     * next later position or to [durationMs] ([END] when it is not known).
     * A line before an unfinished transcript's [resumeMs] runs no further
     * than that: the audio after it has not been transcribed, so no line
     * can stand for it.
     */
    fun lines(segments: List<TranscriptSegment>, durationMs: Long?, resumeMs: Long? = null): List<Line> {
        val timed = segments.indices
            .filter { segments[it].audioMs != null }
            .sortedBy { segments[it].audioMs }
        val total = durationMs?.takeIf { it > 0L } ?: END
        val out = ArrayList<Line>(timed.size)
        for ((k, index) in timed.withIndex()) {
            val start = segments[index].audioMs ?: continue
            var next = END
            for (j in k + 1 until timed.size) {
                val later = segments[timed[j]].audioMs ?: continue
                if (later > start) {
                    next = later
                    break
                }
            }
            val limit = if (resumeMs != null && start < resumeMs) resumeMs else total
            val end = minOf(next, limit)
            out.add(Line(index, start, if (end > start) end else start + 1))
        }
        return out
    }

    /**
     * The span from line [first] (from its word [firstWord] when given) to
     * the end of line [last] (to the end of its word [lastWord] when given,
     * that is to the start of the word after it). Null when either line has
     * no audio position or the span would be empty.
     */
    fun spanFor(
        segments: List<TranscriptSegment>,
        first: Int,
        last: Int,
        durationMs: Long?,
        firstWord: Int? = null,
        lastWord: Int? = null,
        resumeMs: Long? = null
    ): Span? {
        val from = segments.getOrNull(first) ?: return null
        val to = segments.getOrNull(last) ?: return null
        val fromAt = from.audioMs ?: return null
        val toAt = to.audioMs ?: return null
        val start = from.words?.getOrNull(firstWord ?: -1)?.let { fromAt + it.ms } ?: fromAt
        val toWords = to.words
        val nextWord = if (lastWord != null && toWords != null) toWords.getOrNull(lastWord + 1) else null
        val end = if (nextWord != null) {
            toAt + nextWord.ms
        } else {
            lines(segments, durationMs, resumeMs).firstOrNull { it.index == last }?.endMs ?: return null
        }
        val span = Span(start.coerceAtLeast(0L), end)
        return span.takeUnless { it.isEmpty }
    }

    /**
     * Works out removing [asked] from a meeting with [segments], highlight
     * [marksMs] and [chapters], whose audio lasts [durationMs] (null when
     * not known, or when there is no audio) and whose unfinished transcript
     * stops at [resumeMs] (see [lines]).
     */
    fun plan(
        segments: List<TranscriptSegment>,
        asked: Span,
        durationMs: Long? = null,
        marksMs: List<Long> = emptyList(),
        chapters: List<Chapter> = emptyList(),
        resumeMs: Long? = null
    ): Plan {
        val total = durationMs?.takeIf { it > 0L }
        val clamped = Span(
            asked.startMs.coerceAtLeast(0L),
            if (total != null && asked.endMs >= total) END else asked.endMs
        )
        val lines = lines(segments, durationMs, resumeMs)
        if (clamped.isEmpty) {
            return unchanged(asked, clamped, segments, marksMs, chapters)
        }

        // Edges that fall inside a line which cannot be cut there move out
        // to that line's edges. Moving to a line edge never lands inside
        // another line, so two rounds always settle it.
        var span = clamped
        repeat(2) {
            for (line in lines) {
                val crossesStart = line.startMs < span.startMs && span.startMs < line.endMs
                val crossesEnd = span.endMs != END && line.startMs < span.endMs && span.endMs < line.endMs
                if (!crossesStart && !crossesEnd) continue
                if (cut(segments[line.index], line, span) != null) continue
                span = Span(
                    if (crossesStart) line.startMs else span.startMs,
                    if (crossesEnd) line.endMs else span.endMs
                )
            }
        }

        val outcome = arrayOfNulls<Outcome>(segments.size)
        for (line in lines) {
            if (line.endMs <= span.startMs || line.startMs >= span.endMs) continue
            val whole = line.startMs >= span.startMs && (span.endMs == END || line.endMs <= span.endMs)
            outcome[line.index] = if (whole) Outcome.Removed else cut(segments[line.index], line, span)
        }

        val kept = ArrayList<TranscriptSegment>(segments.size)
        val removed = ArrayList<Int>()
        val trimmed = ArrayList<Int>()
        var highlighted = 0
        for ((index, segment) in segments.withIndex()) {
            when (val result = outcome[index]) {
                null, Outcome.Unchanged -> kept.add(segment)
                Outcome.Removed -> {
                    removed.add(index)
                    if (segment.highlighted) highlighted++
                }
                is Outcome.Trimmed -> {
                    trimmed.add(index)
                    kept.add(result.segment)
                }
            }
        }

        val keptMarks = marksMs.filterNot { span.contains(it) }
        val gone = HashSet(removed)
        val movedChapters = chaptersAfter(segments, chapters, gone)

        // The wall-clock window, from the first and last touched lines in
        // audio order, offset within each by where the span cuts it.
        val touched = lines.filter { gone.contains(it.index) || it.index in trimmed }
        val wallStart = touched.firstOrNull()?.let { line ->
            segments[line.index].timestampMs + (span.startMs - line.startMs).coerceAtLeast(0L)
        }
        val wallEnd = touched.lastOrNull()?.let { line ->
            val until = if (span.endMs == END) line.endMs else minOf(span.endMs, line.endMs)
            if (until == END) END else segments[line.index].timestampMs + (until - line.startMs).coerceAtLeast(0L)
        }

        return Plan(
            asked = asked,
            span = span,
            segments = kept,
            removedLines = removed,
            trimmedLines = trimmed,
            highlightedRemoved = highlighted,
            marks = keptMarks,
            marksDropped = marksMs.size - keptMarks.size,
            chapters = movedChapters.chapters,
            chaptersMoved = movedChapters.moved,
            chaptersDropped = movedChapters.dropped,
            wallStartMs = wallStart,
            wallEndMs = wallEnd
        )
    }

    /**
     * Applies [plan] to [meeting]: the transcript, the highlight marks and
     * the chapters, and marks a summary out of date when the transcript
     * changed under it. The audio, the clear-voice copy and the resume
     * point are the caller's (see PassageService).
     */
    fun applyTo(meeting: Meeting, plan: Plan) {
        meeting.segments.clear()
        meeting.segments.addAll(plan.segments)
        meeting.highlightMarksMs.clear()
        meeting.highlightMarksMs.addAll(plan.marks)
        meeting.chapters.clear()
        meeting.chapters.addAll(plan.chapters)
        if (staleSummary(plan, meeting.summary)) meeting.summaryStale = true
    }

    /** True when a summary written before [plan] no longer matches the transcript. */
    fun staleSummary(plan: Plan, summary: String): Boolean =
        plan.changesTranscript && summary.isNotBlank()

    /**
     * Where an unfinished transcript carries on after [plan], from where it
     * stopped at [resumeMs]. The same place, even when the span covers it:
     * the importer only ever stops at a pause (TranscriptResume), and a
     * resume point inside the span is now in silence, which the next pass's
     * speech gate passes over until the first kept word after the span.
     * Moving it to the span's end would start that pass at a word instead
     * of at a pause. Lines from the resume point on are made again by that
     * pass, and the ones inside the span are gone already. The one change:
     * a span that starts at or before the resume point and runs to the end
     * of the recording leaves nothing to transcribe, so the transcript is
     * complete (null).
     */
    fun resumeAfter(plan: Plan, resumeMs: Long?): Long? {
        if (resumeMs == null) return null
        val span = plan.span
        return if (!span.isEmpty && span.endMs == END && span.startMs <= resumeMs) null else resumeMs
    }

    /**
     * Photos among [names] taken while the removed lines were spoken. Photos
     * are named "<meeting>_<capture time>.jpg" (PhotoStore); others, and
     * plans that touched no timed line, count none. Photos stay: they are
     * only named in the confirmation.
     */
    fun photosTakenDuring(names: List<String>, plan: Plan): Int {
        val from = plan.wallStartMs ?: return 0
        val to = plan.wallEndMs ?: return 0
        return names.count { name ->
            val at = captureTimeOf(name)
            at != null && at >= from && at < to
        }
    }

    /** The capture time in a photo's name, or null when it has none. */
    fun captureTimeOf(name: String): Long? {
        val stem = name.substringBeforeLast('.')
        val digits = stem.substringAfterLast('_', "")
        if (digits.isEmpty() || digits.length > 15 || !digits.all { it in '0'..'9' }) return null
        return digits.toLongOrNull()
    }

    private sealed class Outcome {
        object Unchanged : Outcome()
        object Removed : Outcome()
        class Trimmed(val segment: TranscriptSegment) : Outcome()
    }

    private fun unchanged(
        asked: Span,
        span: Span,
        segments: List<TranscriptSegment>,
        marksMs: List<Long>,
        chapters: List<Chapter>
    ) = Plan(
        asked, span, segments.toList(), emptyList(), emptyList(), 0,
        marksMs.toList(), 0, chapters.toList(), 0, 0, null, null
    )

    /**
     * [segment] (extent [line]) with the words inside [span] taken out, or
     * null when that cannot be done at word boundaries: no word timings, a
     * word at a cut that cannot be found in the text, or words inside the
     * span that are not one run.
     */
    private fun cut(segment: TranscriptSegment, line: Line, span: Span): Outcome? {
        val words = segment.words
        if (words.isNullOrEmpty()) return null
        val inside = BooleanArray(words.size) { span.contains(line.startMs + words[it].ms) }
        val first = inside.indexOfFirst { it }
        if (first < 0) return Outcome.Unchanged
        val last = inside.indexOfLast { it }
        for (k in first..last) if (!inside[k]) return null
        if (first == 0 && last == words.size - 1) return Outcome.Removed
        val at = WordRealign.locate(segment.text, words)
        val head = if (first == 0) {
            ""
        } else {
            val cutAt = at[first]
            if (cutAt < 0) return null
            segment.text.substring(0, cutAt).trim()
        }
        val tail = if (last == words.size - 1) {
            ""
        } else {
            val from = at[last + 1]
            if (from < 0) return null
            segment.text.substring(from).trim()
        }
        val text = listOf(head, tail).filter { it.isNotEmpty() }.joinToString(" ")
        if (text.isBlank()) return Outcome.Removed
        val trimmed = if (first == 0) {
            // The line now starts at its first kept word; its words are
            // timed from there. The wall-clock stamp stays, which keeps the
            // transcript's order and what the meeting screen has seen.
            val shift = words[last + 1].ms
            segment.copy(
                text = text,
                audioMs = line.startMs + shift,
                words = words.subList(last + 1, words.size).map { WordStamp(it.ms - shift, it.text) }
            )
        } else {
            segment.copy(
                text = text,
                words = (words.subList(0, first) + words.subList(last + 1, words.size)).ifEmpty { null }
            )
        }
        return Outcome.Trimmed(trimmed)
    }

    private class ChapterResult(val chapters: List<Chapter>, val moved: Int, val dropped: Int)

    /**
     * The chapters once the lines in [gone] are deleted. Lines belong to a
     * chapter as the transcript list draws them (TranscriptLinesAdapter):
     * walking the lines in order, each belongs to the last chapter that
     * starts at or before its wall-clock stamp. A chapter that owned lines
     * and keeps none goes; one whose first lines went starts at its first
     * kept line; one that owned none is left as it was.
     */
    private fun chaptersAfter(
        segments: List<TranscriptSegment>,
        chapters: List<Chapter>,
        gone: Set<Int>
    ): ChapterResult {
        if (chapters.isEmpty() || gone.isEmpty()) return ChapterResult(chapters.toList(), 0, 0)
        val sorted = chapters.sortedBy { it.startMs }
        val firstOwned = IntArray(sorted.size) { -1 }
        val firstKept = IntArray(sorted.size) { -1 }
        var owner = -1
        var next = 0
        for ((index, segment) in segments.withIndex()) {
            while (next < sorted.size && sorted[next].startMs <= segment.timestampMs) {
                owner = next
                next++
            }
            if (owner < 0) continue
            if (firstOwned[owner] < 0) firstOwned[owner] = index
            if (firstKept[owner] < 0 && index !in gone) firstKept[owner] = index
        }
        val out = ArrayList<Chapter>(sorted.size)
        var moved = 0
        var dropped = 0
        for ((c, chapter) in sorted.withIndex()) {
            when {
                firstOwned[c] < 0 -> out.add(chapter)
                firstKept[c] < 0 -> dropped++
                firstKept[c] != firstOwned[c] -> {
                    val startMs = maxOf(chapter.startMs, segments[firstKept[c]].timestampMs)
                    if (startMs != chapter.startMs) moved++
                    out.add(chapter.copy(startMs = startMs))
                }
                else -> out.add(chapter)
            }
        }
        // Nothing moved or went: the list as it was, in its own order.
        if (moved == 0 && dropped == 0) return ChapterResult(chapters.toList(), 0, 0)
        return ChapterResult(out, moved, dropped)
    }
}
