package com.meetily.mobile.data

/**
 * Splits one transcript segment into two at a character position — used to
 * separate overlapping speakers after the fact. Timestamps and audio
 * offsets for the second part are interpolated proportionally between this
 * segment and the next; without a next anchor, speech pace is estimated.
 * Word timings are divided at the split, and then also place the second
 * part's audio offset exactly: at its first word.
 * Pure Kotlin — unit-tested.
 */
object TranscriptSplitter {

    /** Rough speech pace for interpolation when no next-segment anchor exists. */
    const val MS_PER_CHAR = 60L

    /**
     * Returns (first, second) or null when the position doesn't leave real
     * text on both sides. [editedText] lets the user fix wording in the same
     * dialog that splits. The first part keeps the segment's speaker, cluster,
     * and highlight; the second starts untagged (mixed audio — the caller
     * should prompt for its speaker).
     */
    fun split(
        segment: TranscriptSegment,
        next: TranscriptSegment?,
        charPos: Int,
        editedText: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<TranscriptSegment, TranscriptSegment>? {
        val text = editedText ?: segment.text
        if (charPos <= 0 || charPos >= text.length) return null
        val first = text.substring(0, charPos).trim()
        val second = text.substring(charPos).trim()
        if (first.isBlank() || second.isBlank()) return null

        val fraction = charPos.toDouble() / text.length
        val estimate = first.length * MS_PER_CHAR

        val tsSpan = next?.timestampMs?.minus(segment.timestampMs)?.takeIf { it > 0 }
        // Never past the next segment, and never past now.
        //
        // With no next anchor the estimate is pure speech-pace guesswork, and
        // for a long first half it lands seconds into the FUTURE. Splitting
        // the last line of a just-stopped recording therefore wrote a
        // timestamp ahead of wall clock, which persist() then adopted as the
        // segments-seen high-water mark — so a genuine late chunk, stamped
        // with the real current time, read as already-seen, was never folded
        // back, and was erased by the next whole-object save. The last words
        // of the meeting, deleted by an edit made two seconds earlier.
        val ceiling = next?.timestampMs?.minus(1L) ?: nowMs
        val secondTs = (
            segment.timestampMs + (tsSpan?.let { (it * fraction).toLong() } ?: estimate)
            ).coerceIn(segment.timestampMs, maxOf(segment.timestampMs, ceiling))

        // Word timings follow the wording into the split (re-aligned first
        // when the dialog's text was edited), each word to the part it falls
        // in. They used to be dropped on both halves, which cost both lines
        // their tap-to-seek and playback follow.
        val words = if (editedText == null || editedText == segment.text) {
            segment.words
        } else {
            WordRealign.realign(segment.text, segment.words, editedText)
        }
        val (firstWords, secondWords) = WordRealign.partition(text, words, charPos)

        // The second part starts where its first word does. Its words are
        // timed from the start of the line, and now start from its own.
        // Kept short of the next line's audio, as the interpolation is.
        val wordOffset = secondWords.firstOrNull()?.ms
        val start = segment.audioMs
        val shift = if (wordOffset == null || start == null) {
            wordOffset
        } else {
            val limit = next?.audioMs?.takeIf { it > start }?.minus(1L)
            val at = start + wordOffset
            (if (limit != null) at.coerceIn(start, limit) else at) - start
        }
        val secondAudioMs = start?.let {
            if (shift != null) {
                it + shift
            } else {
                val span = next?.audioMs?.minus(it)?.takeIf { s -> s > 0 }
                it + (span?.let { s -> (s * fraction).toLong() } ?: estimate)
            }
        }
        val rebase = shift ?: 0L

        // Each part keeps the line's word-timing flag while its words are the
        // line's own; re-aligned wording, or a word cut in two (its second
        // piece borrows the word's start), makes a part's timings estimated.
        // The second part's words are compared before they are rebased.
        return segment.copy(
            text = first,
            words = firstWords.ifEmpty { null },
            wordTimes = WordTimes.afterEdit(segment.wordTimes, segment.words, firstWords)
        ) to TranscriptSegment(
            timestampMs = secondTs,
            text = second,
            speaker = null,
            highlighted = false,
            clusterId = null,
            audioMs = secondAudioMs,
            words = secondWords
                .map { WordStamp((it.ms - rebase).coerceAtLeast(0L), it.text) }
                .ifEmpty { null },
            wordTimes = WordTimes.afterEdit(segment.wordTimes, segment.words, secondWords)
        )
    }
}
