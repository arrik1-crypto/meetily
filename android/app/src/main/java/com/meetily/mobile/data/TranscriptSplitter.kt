package com.meetily.mobile.data

/**
 * Splits one transcript segment into two at a character position — used to
 * separate overlapping speakers after the fact. Timestamps and audio
 * offsets for the second part are interpolated proportionally between this
 * segment and the next; without a next anchor, speech pace is estimated.
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
        editedText: String? = null
    ): Pair<TranscriptSegment, TranscriptSegment>? {
        val text = editedText ?: segment.text
        if (charPos <= 0 || charPos >= text.length) return null
        val first = text.substring(0, charPos).trim()
        val second = text.substring(charPos).trim()
        if (first.isBlank() || second.isBlank()) return null

        val fraction = charPos.toDouble() / text.length
        val estimate = first.length * MS_PER_CHAR

        val tsSpan = next?.timestampMs?.minus(segment.timestampMs)?.takeIf { it > 0 }
        val secondTs = segment.timestampMs +
            (tsSpan?.let { (it * fraction).toLong() } ?: estimate)

        val secondAudioMs = segment.audioMs?.let { start ->
            val span = next?.audioMs?.minus(start)?.takeIf { it > 0 }
            start + (span?.let { (it * fraction).toLong() } ?: estimate)
        }

        // Word timings can't be split reliably; drop them on both halves.
        return segment.copy(text = first, words = null) to TranscriptSegment(
            timestampMs = secondTs,
            text = second,
            speaker = null,
            highlighted = false,
            clusterId = null,
            audioMs = secondAudioMs
        )
    }
}
