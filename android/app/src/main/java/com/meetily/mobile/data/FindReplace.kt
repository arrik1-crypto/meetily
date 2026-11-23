package com.meetily.mobile.data

import java.util.regex.Pattern

/**
 * Find & replace across one meeting: its transcript lines, notes, summary
 * (with the summaries kept from other templates) and action items.
 *
 * Whole word is on and match case off by default, so fixing a misheard name
 * ("Jon" to "John") reaches "jon" and "Jon's" but not "Jonas". A match that
 * already reads as the replacement is not a change and is not counted.
 * Transcript lines keep their word timings (WordRealign), so tap-to-seek and
 * playback follow survive the fix. A replace that changes the transcript
 * marks an existing summary stale, as an accepted accuracy check does.
 *
 * [apply] returns a [Snapshot] of exactly what it changed; [undo] puts back
 * only what still reads as the replace left it, so an edit made since is
 * never reverted with it.
 * Pure Kotlin — unit-tested.
 */
object FindReplace {

    data class Options(val wholeWord: Boolean = true, val matchCase: Boolean = false)

    /** What to find and what to put instead; both trimmed (see [of]). */
    data class Query(val find: String, val replace: String, val options: Options) {
        companion object {
            /**
             * Trimmed at both ends: a keyboard's suggestion strip adds a
             * space after the word it completes, and "Jon " would then miss
             * every "Jon," and "Jon." in the meeting.
             */
            fun of(find: String, replace: String, options: Options) =
                Query(find.trim(), replace.trim(), options)
        }
    }

    enum class Area { TRANSCRIPT, NOTES, SUMMARY, ACTION_ITEMS }

    /** Changes a replace would make (or made), per area. */
    data class Counts(
        val transcriptMatches: Int = 0,
        val transcriptLines: Int = 0,
        val notes: Int = 0,
        val summary: Int = 0,
        val actionItems: Int = 0,
        /** Of [summary], the changes in summaries kept for other styles, which the summary card does not show. */
        val keptSummaries: Int = 0
    ) {
        fun of(area: Area): Int = when (area) {
            Area.TRANSCRIPT -> transcriptMatches
            Area.NOTES -> notes
            Area.SUMMARY -> summary
            Area.ACTION_ITEMS -> actionItems
        }

        fun total(areas: Set<Area> = Area.values().toSet()): Int = areas.sumOf { of(it) }
    }

    /**
     * The texts a preview counts over, copied from the meeting so a worker
     * can count while the screen keeps editing its own copy.
     */
    class Source(
        val lines: List<String>,
        val notes: String,
        val summary: String,
        val actionItems: List<ActionItem>,
        /** The texts of the summaries kept from other templates; the summary area covers them too. */
        val keptSummaries: List<String> = emptyList()
    ) {
        companion object {
            fun of(meeting: Meeting) = Source(
                meeting.segments.map { it.text },
                meeting.notes,
                meeting.summary,
                meeting.actionItems.toList(),
                meeting.summaries.map { it.text }
            )
        }
    }

    /** One transcript line as it was before a replace, and as the replace left it. */
    data class LineChange(val index: Int, val before: TranscriptSegment, val after: TranscriptSegment)

    data class TextChange(val before: String, val after: String)

    data class ItemChange(val index: Int, val before: ActionItem, val after: ActionItem)

    /**
     * A summary kept from another template (Meeting.summaries) before and
     * after a replace, found again by its template and the time it was
     * written, wherever it is when Undo runs: still kept, or shown since.
     */
    data class KeptChange(
        val template: String?,
        val createdAtMs: Long,
        val before: String,
        val after: String
    )

    /**
     * Which summary: its template key (SummaryAlternatives.key) and the time
     * it was written. One entry per template, so this names one summary
     * whether it is shown or kept, and a switch does not change it.
     */
    data class SummaryId(val template: String?, val createdAtMs: Long)

    /** Everything one replace changed: what Undo puts back. */
    data class Snapshot(
        val query: Query,
        val lines: List<LineChange>,
        val notes: TextChange?,
        /** The summary shown when the replace ran, its text before and after. */
        val summary: TextChange?,
        val actionItems: List<ItemChange>,
        val counts: Counts,
        val kept: List<KeptChange> = emptyList(),
        /** Which summary [summary] is, for Undo to find it again after a switch. */
        val summaryId: SummaryId? = null,
        /**
         * Every summary this replace marked out of date, shown or kept. Undo
         * clears the mark on these only, wherever they are by then: a pill
         * switched since must neither lose a mark the replace did not add
         * nor keep one it did.
         */
        val markedStale: List<SummaryId> = emptyList()
    )

    /** Places (lines, notes, summary, items) put back, and those edited since and left. */
    data class UndoResult(val restored: Int, val skipped: Int)

    /** Characters that make up a word, for whole-word matching. */
    private const val WORD_CHAR = "[\\p{L}\\p{M}\\p{N}_]"

    /**
     * The pattern for [query], or null when there is nothing to find.
     * Whole word only bounds an end of the search text that is itself a
     * word character, so "C++" or "Dr." can still be found as whole words.
     */
    fun pattern(query: Query): Pattern? {
        val find = query.find
        if (find.isEmpty()) return null
        val body = StringBuilder()
        if (query.options.wholeWord && isWordChar(find.codePointAt(0))) {
            body.append("(?<!").append(WORD_CHAR).append(')')
        }
        body.append(Pattern.quote(find))
        if (query.options.wholeWord && isWordChar(find.codePointBefore(find.length))) {
            body.append("(?!").append(WORD_CHAR).append(')')
        }
        val flags = if (query.options.matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(body.toString(), flags)
    }

    private val WORD_CHAR_PATTERN: Pattern = Pattern.compile(WORD_CHAR)

    /** The same test the pattern's boundaries make. */
    private fun isWordChar(codePoint: Int): Boolean =
        WORD_CHAR_PATTERN.matcher(String(Character.toChars(codePoint))).matches()

    /**
     * The edits replacing every match of [pattern] in [text] with
     * [replacement] makes, in order. A match already reading as the
     * replacement is skipped. Replacing with nothing also takes one space
     * beside the match, so "we um should" becomes "we should" rather than
     * "we  should".
     */
    fun edits(text: String, pattern: Pattern, replacement: String): List<WordRealign.Edit> {
        val out = mutableListOf<WordRealign.Edit>()
        val matcher = pattern.matcher(text)
        var lastEnd = 0
        while (matcher.find()) {
            var start = matcher.start()
            var end = matcher.end()
            if (end == start) continue
            if (text.regionMatches(start, replacement, 0, replacement.length) &&
                end - start == replacement.length
            ) {
                continue
            }
            if (replacement.isEmpty()) {
                val spaceAfter = end < text.length && text[end] == ' '
                val spaceBefore = start > lastEnd && text[start - 1] == ' '
                val atLineStart = start == 0 || text[start - 1] == '\n'
                if (spaceAfter && (spaceBefore || atLineStart)) {
                    end++
                } else if (spaceBefore) {
                    start--
                }
            }
            out.add(WordRealign.Edit(start, end, replacement))
            lastEnd = end
        }
        return out
    }

    /** [text] with every match replaced, or [text] itself when nothing changes. */
    fun replaceIn(text: String, pattern: Pattern, replacement: String): String =
        WordRealign.applyText(text, edits(text, pattern, replacement))

    /** What [apply] would change in [source]. Safe on any thread. */
    fun count(source: Source, query: Query): Counts {
        val pattern = pattern(query) ?: return Counts()
        var matches = 0
        var lines = 0
        for (line in source.lines) {
            val edits = edits(line, pattern, query.replace)
            if (edits.isEmpty() || !leavesText(line, edits)) continue
            matches += edits.size
            lines++
        }
        var items = 0
        for (item in source.actionItems) {
            val taskEdits = edits(item.task, pattern, query.replace)
            if (taskEdits.isNotEmpty() && leavesText(item.task, taskEdits)) items += taskEdits.size
            item.owner?.let { items += edits(it, pattern, query.replace).size }
        }
        val kept = source.keptSummaries.sumOf { text ->
            val keptEdits = edits(text, pattern, query.replace)
            if (keptEdits.isNotEmpty() && leavesText(text, keptEdits)) keptEdits.size else 0
        }
        return Counts(
            transcriptMatches = matches,
            transcriptLines = lines,
            notes = edits(source.notes, pattern, query.replace).size,
            summary = edits(source.summary, pattern, query.replace).size + kept,
            actionItems = items,
            keptSummaries = kept
        )
    }

    /** A replace never empties a transcript line or an action item: those are left as they were. */
    private fun leavesText(text: String, edits: List<WordRealign.Edit>): Boolean =
        WordRealign.applyText(text, edits).isNotBlank()

    /**
     * Replaces [query] in [areas] of [meeting], in place. Returns what changed,
     * for [undo], or null when nothing did.
     */
    fun apply(meeting: Meeting, query: Query, areas: Set<Area>): Snapshot? {
        val pattern = pattern(query) ?: return null
        val replacement = query.replace

        val lines = mutableListOf<LineChange>()
        var lineMatches = 0
        if (Area.TRANSCRIPT in areas) {
            for (i in meeting.segments.indices) {
                val segment = meeting.segments[i]
                val edits = edits(segment.text, pattern, replacement)
                if (edits.isEmpty()) continue
                val text = WordRealign.applyText(segment.text, edits).trim()
                if (text.isBlank()) continue
                val words = WordRealign.applyEdits(segment.text, segment.words, edits)
                val after = segment.copy(
                    text = text,
                    words = words,
                    wordTimes = WordTimes.afterEdit(segment.wordTimes, segment.words, words)
                )
                meeting.segments[i] = after
                lines.add(LineChange(i, segment, after))
                lineMatches += edits.size
            }
        }

        var notes: TextChange? = null
        var notesMatches = 0
        if (Area.NOTES in areas) {
            val edits = edits(meeting.notes, pattern, replacement)
            if (edits.isNotEmpty()) {
                val after = WordRealign.applyText(meeting.notes, edits)
                notes = TextChange(meeting.notes, after)
                meeting.notes = after
                notesMatches = edits.size
            }
        }

        var summary: TextChange? = null
        var summaryMatches = 0
        val kept = mutableListOf<KeptChange>()
        // Taken first: replacing with nothing can leave the summary blank.
        val summaryId = shownId(meeting)
        if (Area.SUMMARY in areas) {
            val edits = edits(meeting.summary, pattern, replacement)
            if (edits.isNotEmpty()) {
                val after = WordRealign.applyText(meeting.summary, edits)
                summary = TextChange(meeting.summary, after)
                meeting.summary = after
                summaryMatches = edits.size
            }
            // The summaries kept from other templates read the same name
            // the same wrong way; switching to one must not bring it back.
            // One that would be left blank is left as it was, like a line.
            val keptAfter = meeting.summaries.map { saved ->
                val keptEdits = edits(saved.text, pattern, replacement)
                val text = WordRealign.applyText(saved.text, keptEdits)
                if (keptEdits.isEmpty() || text.isBlank()) {
                    saved
                } else {
                    kept.add(KeptChange(saved.template, saved.createdAtMs, saved.text, text))
                    summaryMatches += keptEdits.size
                    saved.copy(text = text)
                }
            }
            if (kept.isNotEmpty()) meeting.summaries = keptAfter.toMutableList()
        }

        val items = mutableListOf<ItemChange>()
        var itemMatches = 0
        if (Area.ACTION_ITEMS in areas) {
            for (i in meeting.actionItems.indices) {
                val item = meeting.actionItems[i]
                val taskEdits = edits(item.task, pattern, replacement)
                val task = WordRealign.applyText(item.task, taskEdits).trim()
                val keepTask = taskEdits.isNotEmpty() && task.isNotBlank()
                val owner = item.owner
                val ownerEdits = if (owner != null) edits(owner, pattern, replacement) else emptyList()
                if (!keepTask && ownerEdits.isEmpty()) continue
                val after = item.copy(
                    task = if (keepTask) task else item.task,
                    owner = if (owner != null && ownerEdits.isNotEmpty()) {
                        WordRealign.applyText(owner, ownerEdits).trim().ifBlank { null }
                    } else {
                        owner
                    }
                )
                meeting.actionItems[i] = after
                items.add(ItemChange(i, item, after))
                itemMatches += (if (keepTask) taskEdits.size else 0) + ownerEdits.size
            }
        }

        if (lines.isEmpty() && notes == null && summary == null && kept.isEmpty() && items.isEmpty()) {
            return null
        }
        // The summary was written from the old wording; fixing its own text
        // too does not cover what it paraphrased. The same goes for every
        // summary kept from another template. Recorded by which summary, not
        // by place: the one shown can change before Undo.
        val markedStale = if (lines.isNotEmpty()) {
            val fresh = (listOfNotNull(SummaryAlternatives.active(meeting)) + meeting.summaries)
                .filter { !it.stale }
                .map { idOf(it) }
            SummaryAlternatives.markStale(meeting)
            fresh
        } else {
            emptyList()
        }
        return Snapshot(
            query = query,
            lines = lines,
            notes = notes,
            summary = summary,
            actionItems = items,
            counts = Counts(lineMatches, lines.size, notesMatches, summaryMatches, itemMatches),
            kept = kept,
            summaryId = if (summary != null) summaryId else null,
            markedStale = markedStale
        )
    }

    private fun idOf(saved: SavedSummary) =
        SummaryId(SummaryAlternatives.key(saved.template), saved.createdAtMs)

    /** Which summary [meeting]'s top-level summary fields hold, even while its text is blank. */
    private fun shownId(meeting: Meeting) =
        SummaryId(SummaryAlternatives.key(meeting.summaryTemplate), meeting.summaryAtMs)

    /**
     * Puts [before] back as the text of summary [id], wherever it is now
     * (shown or kept), if it still reads [after]. Returns false when it is
     * gone, was written again, or was edited since.
     */
    private fun restoreSummaryText(meeting: Meeting, id: SummaryId, before: String, after: String): Boolean {
        if (shownId(meeting) == id && meeting.summary == after) {
            meeting.summary = before
            return true
        }
        val at = meeting.summaries.indexOfFirst { idOf(it) == id && it.text == after }
        if (at < 0) return false
        meeting.summaries[at] = meeting.summaries[at].copy(text = before)
        return true
    }

    /**
     * Puts back what [snapshot] changed in [meeting], in place, wherever it
     * still reads as the replace left it. A line, note, summary or item
     * edited since is left as it is now and counted as skipped. The stale
     * marks the replace added go back only when every line did, and only on
     * the summaries it marked.
     */
    fun undo(meeting: Meeting, snapshot: Snapshot): UndoResult {
        var restored = 0
        var skipped = 0

        val taken = HashSet<Int>()
        var allLinesBack = true
        for (change in snapshot.lines) {
            // By wording only, as for action items below: a highlight or a
            // speaker tag set since does not change how the line reads, and
            // stays.
            val at = find(meeting.segments, change.index, taken) {
                it.timestampMs == change.after.timestampMs &&
                    it.text == change.after.text &&
                    it.words == change.after.words
            }
            if (at < 0) {
                skipped++
                allLinesBack = false
                continue
            }
            meeting.segments[at] = meeting.segments[at].copy(
                text = change.before.text,
                words = change.before.words,
                wordTimes = change.before.wordTimes
            )
            taken.add(at)
            restored++
        }

        snapshot.notes?.let { change ->
            if (meeting.notes == change.after) {
                meeting.notes = change.before
                restored++
            } else {
                skipped++
            }
        }
        // Each summary by which one it is, shown or kept: a switch since
        // moves it, and is not an edit. One written again since, deleted, or
        // edited counts as edited and stays.
        snapshot.summary?.let { change ->
            val id = snapshot.summaryId
            if (id != null && restoreSummaryText(meeting, id, change.before, change.after)) {
                restored++
            } else {
                skipped++
            }
        }
        for (change in snapshot.kept) {
            val id = SummaryId(SummaryAlternatives.key(change.template), change.createdAtMs)
            if (restoreSummaryText(meeting, id, change.before, change.after)) {
                restored++
            } else {
                skipped++
            }
        }

        val takenItems = HashSet<Int>()
        for (change in snapshot.actionItems) {
            // By wording only: a tick or reminder set since stays.
            val at = find(meeting.actionItems, change.index, takenItems) {
                it.task == change.after.task && it.owner == change.after.owner
            }
            if (at < 0) {
                skipped++
                continue
            }
            meeting.actionItems[at] = meeting.actionItems[at].copy(
                task = change.before.task,
                owner = change.before.owner
            )
            takenItems.add(at)
            restored++
        }

        // Every line went back, so the summaries this replace marked out of
        // date are up to date again: those, by which summary they are,
        // wherever they sit now. One written since is another summary and
        // keeps its own mark.
        if (snapshot.lines.isNotEmpty() && allLinesBack && snapshot.markedStale.isNotEmpty()) {
            val marked = snapshot.markedStale.toSet()
            if (meeting.summaryStale && shownId(meeting) in marked) meeting.summaryStale = false
            if (meeting.summaries.any { it.stale && idOf(it) in marked }) {
                meeting.summaries = meeting.summaries.map {
                    if (it.stale && idOf(it) in marked) it.copy(stale = false) else it
                }.toMutableList()
            }
        }
        return UndoResult(restored, skipped)
    }

    /** [hint] when it matches and is free, else the first free match, else -1. */
    private inline fun <T> find(
        list: List<T>,
        hint: Int,
        taken: Set<Int>,
        matches: (T) -> Boolean
    ): Int {
        if (hint in list.indices && hint !in taken && matches(list[hint])) return hint
        for (i in list.indices) {
            if (i !in taken && matches(list[i])) return i
        }
        return -1
    }

    /** Longest replacement offered for custom vocabulary. */
    private const val MAX_TERM_CHARS = 40
    private const val MAX_TERM_WORDS = 4

    /**
     * The replacement, when it looks like a corrected term worth adding to
     * custom vocabulary [existingVocab]: the transcript was changed, it is
     * short, it carries a capital, a digit or a letter from a script without
     * case (a name, an acronym, jargon rather than "there" for "their"), and
     * the list does not already hold it. Null otherwise.
     */
    fun vocabTerm(query: Query, counts: Counts, existingVocab: String): String? {
        if (counts.transcriptMatches == 0) return null
        val term = query.replace.trim()
        if (term.isEmpty() || term.length > MAX_TERM_CHARS) return null
        if (term.split(Regex("\\s+")).size > MAX_TERM_WORDS) return null
        if (term.any { it in ",;\n" }) return null
        val distinctive = term.any {
            it.isDigit() || (it.isLetter() && !it.isLowerCase())
        }
        if (!distinctive) return null
        val known = existingVocab.split(',', ';', '\n').map { it.trim() }
        if (known.any { it.equals(term, ignoreCase = true) }) return null
        return term
    }

    /**
     * The words of a transcript line a "Fix this word everywhere" offers,
     * in order, once each, without the punctuation around them. Taken from
     * the line's word timings when it has them, which also splits scripts
     * written without spaces into words; from its spaces otherwise.
     */
    fun wordsOf(text: String, words: List<WordStamp>? = null): List<String> {
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        val pieces = if (words.isNullOrEmpty()) {
            text.split(Regex("\\s+"))
        } else {
            words.map { it.text }
        }
        for (raw in pieces) {
            val word = raw.trim { !Character.isLetterOrDigit(it) }
            if (word.isEmpty()) continue
            if (seen.add(word.lowercase())) out.add(word)
        }
        return out
    }
}
