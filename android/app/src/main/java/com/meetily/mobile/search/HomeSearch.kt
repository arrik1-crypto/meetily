package com.meetily.mobile.search

import com.meetily.mobile.data.ElapsedTime
import com.meetily.mobile.data.Meeting

/**
 * The home screen's search box: what a meeting is matched against, and how
 * a typed query is compared with it. Pure — unit-tested.
 *
 * Each meeting gets one string, lowercased as it is built on the
 * library-load worker and kept for the life of that load. Deliberately one
 * string rather than a per-field scan: the fields are only ever tested with
 * contains(), so joining them costs one pass and saves an allocation per
 * field per meeting per keystroke. The same string tells a matching row
 * which line to quote ([moment]).
 *
 * It covers what people remember a meeting by: title, notes, summary (and
 * the summaries kept from other templates), attendees, tags, the transcript with its speaker labels, the text read
 * from attached photos (whiteboards, slides), action items and their owners,
 * and the names of attached files. Every one of those is in the home list's
 * light listing (MeetingStore.listLight leaves out only word timings), so
 * none of this needs a full parse of the library.
 */
object HomeSearch {

    /**
     * [blob], plus where each transcript line sits in it, so a match can be
     * traced to its line (see [moment]) without scanning the transcript again.
     * Three ints a line on top of the blob.
     */
    class Indexed internal constructor(
        /** Everything the meeting is matched against, lowercased: see [blobFor]. */
        val blob: String,
        // Per non-blank transcript line, in order: where its line starts in
        // the blob, where its words start (after any "speaker: "), and which
        // segment it is.
        internal val lineStarts: IntArray,
        internal val textStarts: IntArray,
        internal val lineSegments: IntArray,
        /** How many segments the meeting had; a different count means it changed since. */
        internal val segmentCount: Int
    )

    /** Everything [meeting] is matched against, lowercased. */
    fun blobFor(meeting: Meeting): String = index(meeting).blob

    /** [blobFor], with its transcript lines located. Built once per library load. */
    fun index(meeting: Meeting): Indexed {
        val out = StringBuilder()
        line(out, meeting.title)
        line(out, meeting.notes)
        line(out, meeting.summary)
        // The summaries kept from other templates: a word remembered from
        // the sales-call summary still finds the meeting showing its standup.
        for (saved in meeting.summaries) line(out, saved.text)
        line(out, meeting.attendeesText())
        line(out, meeting.tags.joinToString(" "))
        for (item in meeting.actionItems) {
            line(out, item.task)
            item.owner?.let { line(out, it) }
        }
        // Blank entries only record that OCR ran on a photo and found nothing.
        // OCR puts a line break wherever the text wrapped on the whiteboard
        // or slide, not where the meaning breaks, so each photo's text is
        // flattened to one line: "roadmap milestone" still finds a heading
        // that wrapped between those words. Other fields keep their breaks,
        // which the user typed.
        for (text in meeting.photoTexts.values) line(out, text.replace('\n', ' '))
        for (attachment in meeting.attachmentsList) line(out, attachment.name)
        // Appended segment by segment rather than through
        // transcriptTextWithSpeakers(), which would build the whole
        // transcript as a second string only to copy it in here.
        val lines = meeting.segments.count { it.text.isNotBlank() }
        val lineStarts = IntArray(lines)
        val textStarts = IntArray(lines)
        val lineSegments = IntArray(lines)
        var n = 0
        for ((index, segment) in meeting.segments.withIndex()) {
            if (segment.text.isBlank()) continue
            lineStarts[n] = out.length
            val speaker = segment.speaker
            if (!speaker.isNullOrBlank()) out.append(speaker.lowercase()).append(": ")
            textStarts[n] = out.length
            lineSegments[n] = index
            n++
            out.append(segment.text.lowercase()).append('\n')
        }
        // Lowercased piece by piece as it is added, not as one string at the
        // end: lowercasing can change a string's length (a Turkish İ becomes
        // two characters), which would move every offset recorded above.
        return Indexed(out.toString(), lineStarts, textStarts, lineSegments, meeting.segments.size)
    }

    /** Where a meeting's best match is, for showing it under the meeting's row. */
    enum class Field { TRANSCRIPT, NOTES, SUMMARY, ACTION_ITEM, PHOTO_TEXT }

    /**
     * Why a meeting matched, to show under it: the transcript line with the
     * best match and the words around it, or failing that the notes,
     * summary, action item or photo text it was found in.
     */
    data class Moment(
        val field: Field,
        /** The transcript line (segment index), or -1 outside the transcript. */
        val segmentIndex: Int,
        /** How far into the recording that line is ("0:42:10"), for a transcript line. */
        val timeLabel: String?,
        val snippet: MatchSnippet.Snippet,
        /**
         * The match is in the line's words, not only in its speaker's name,
         * so Find in meeting (which searches the words) finds it there too.
         */
        val inLineText: Boolean
    )

    /**
     * Where [query] (from [normalizeQuery]) best matches [meeting], whose
     * [index] is [indexed]. Null when it matched only by title, attendees,
     * tags or a file name, which the row already shows or which says
     * nothing more: such a row stays as it was.
     *
     * In the transcript, a whole-word match beats one inside a longer word
     * ("plan", not "planet"), and a match in what was said beats one in a
     * speaker's name; the earliest wins among equals. The blob is searched
     * rather than the transcript, so this costs no copy of any line but the
     * one shown.
     */
    fun moment(meeting: Meeting, indexed: Indexed, query: String): Moment? {
        if (query.isBlank()) return null
        return transcriptMoment(meeting, indexed, query) ?: fieldMoment(meeting, query)
    }

    private fun transcriptMoment(meeting: Meeting, indexed: Indexed, query: String): Moment? {
        val lineStarts = indexed.lineStarts
        if (lineStarts.isEmpty() || indexed.segmentCount != meeting.segments.size) return null
        // A pasted line break could make a match run from one line into the next.
        if (query.contains('\n')) return null
        val blob = indexed.blob
        var bestAt = -1
        var bestLine = -1
        var bestRank = Int.MAX_VALUE
        var at = blob.indexOf(query, lineStarts[0])
        while (at >= 0) {
            val line = lineAt(lineStarts, at)
            val inText = at >= indexed.textStarts[line]
            val wholeWord = isBoundary(blob, at - 1) && isBoundary(blob, at + query.length)
            val rank = (if (wholeWord) 0 else 2) + (if (inText) 0 else 1)
            if (rank < bestRank) {
                bestRank = rank
                bestAt = at
                bestLine = line
                if (rank == 0) break
            }
            at = blob.indexOf(query, at + 1)
        }
        if (bestLine < 0) return null
        val segmentIndex = indexed.lineSegments[bestLine]
        val segment = meeting.segments.getOrNull(segmentIndex) ?: return null
        val speaker = segment.speaker
        val shown = if (speaker.isNullOrBlank()) segment.text else "$speaker: ${segment.text}"
        val (start, end) = locate(shown, query, bestAt - lineStarts[bestLine])
        return Moment(
            field = Field.TRANSCRIPT,
            segmentIndex = segmentIndex,
            timeLabel = ElapsedTime.label(segment, meeting.createdAtMs),
            snippet = MatchSnippet.around(shown, start, end),
            inLineText = bestRank % 2 == 0
        )
    }

    /** The other fields worth showing, in the order the blob holds them. */
    private fun fieldMoment(meeting: Meeting, query: String): Moment? {
        fieldSnippet(meeting.notes, query)?.let { return Moment(Field.NOTES, -1, null, it, false) }
        fieldSnippet(meeting.summary, query)?.let { return Moment(Field.SUMMARY, -1, null, it, false) }
        for (saved in meeting.summaries) {
            fieldSnippet(saved.text, query)?.let { return Moment(Field.SUMMARY, -1, null, it, false) }
        }
        for (item in meeting.actionItems) {
            val owner = item.owner
            val text = if (owner.isNullOrBlank()) item.task else "${item.task} — $owner"
            fieldSnippet(text, query)?.let { return Moment(Field.ACTION_ITEM, -1, null, it, false) }
        }
        for (text in meeting.photoTexts.values) {
            // Flattened as in the blob, so a match across a wrapped line is found.
            fieldSnippet(text.replace('\n', ' '), query)
                ?.let { return Moment(Field.PHOTO_TEXT, -1, null, it, false) }
        }
        return null
    }

    private fun fieldSnippet(text: String, query: String): MatchSnippet.Snippet? {
        if (text.isBlank()) return null
        val at = text.indexOf(query, ignoreCase = true)
        if (at < 0) return null
        return MatchSnippet.around(text, at, at + query.length)
    }

    /**
     * The match in [shown] (the line as displayed) that sits at [offset] in
     * its lowercased copy. Where lowercasing changed the line's length the
     * offset no longer lines up, so it is looked for again; failing that the
     * snippet goes without a highlight rather than a wrong one.
     */
    private fun locate(shown: String, query: String, offset: Int): Pair<Int, Int> {
        if (offset >= 0 && offset + query.length <= shown.length &&
            shown.regionMatches(offset, query, 0, query.length, ignoreCase = true)
        ) {
            return offset to offset + query.length
        }
        val at = shown.indexOf(query, ignoreCase = true)
        return if (at >= 0) at to at + query.length else 0 to 0
    }

    /** The transcript line holding blob offset [at]: the last line starting at or before it. */
    private fun lineAt(lineStarts: IntArray, at: Int): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= at) low = mid else high = mid - 1
        }
        return low
    }

    private fun isBoundary(text: String, index: Int): Boolean =
        index < 0 || index >= text.length || !text[index].isLetterOrDigit()

    /** [raw] as typed in the search box, in the form [matches] compares. */
    fun normalizeQuery(raw: String): String = raw.trim().lowercase()

    /**
     * Whether a meeting whose [blobFor] is [blob] matches [query], already
     * passed through [normalizeQuery]. Fields sit on separate lines, so a
     * query never matches across the end of one and the start of the next.
     */
    fun matches(blob: String, query: String): Boolean = blob.contains(query)

    private fun line(out: StringBuilder, text: String) {
        if (text.isBlank()) return
        out.append(text.lowercase()).append('\n')
    }
}
