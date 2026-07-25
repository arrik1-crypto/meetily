package com.meetily.mobile.data

/**
 * Compares two transcripts of the SAME audio and merges the user's choices
 * back into one segment list.
 *
 * The accuracy check runs a second, usually heavier model over the saved
 * recording. Two models chunk audio differently, so the transcripts do not
 * line up one segment to one segment — they line up in *blocks* of
 * overlapping audio. Everything here works from the audio offset, which is
 * the one thing both passes agree on.
 *
 * Nothing in this file mutates its inputs: the caller decides when a merged
 * list replaces a stored transcript.
 */
object TranscriptReconcile {

    private const val MIN_SPAN_MS = 800L
    private const val MAX_SPAN_MS = 30_000L

    /** Roughly 15 characters of speech a second. */
    private const val MS_PER_CHAR = 66L

    /**
     * One stretch of audio and the segments each pass produced for it. Either
     * side can be empty: a model that heard nothing where the other heard
     * speech is exactly the kind of disagreement worth showing.
     */
    data class Block(
        val ordinal: Int,
        val currentIndices: List<Int>,
        val freshIndices: List<Int>,
        val currentText: String,
        val freshText: String,
        val startMs: Long
    ) {
        /**
         * Punctuation and casing differ constantly between engines and say
         * nothing about accuracy, so only the words count.
         */
        val agrees: Boolean get() = normalize(currentText) == normalize(freshText)
    }

    /**
     * Offset into the meeting audio.
     *
     * Segments transcribed by Whisper/NeMo carry a real one. Those from the
     * system recognizer do not, so they fall back to their distance from the
     * meeting's start — which is also when the recording began. Anchoring on
     * the first *segment* instead would shift the whole transcript by however
     * long the room was quiet at the top.
     */
    private fun offsets(segments: List<TranscriptSegment>, baseMs: Long?): LongArray {
        if (segments.isEmpty()) return LongArray(0)
        val base = baseMs ?: segments.minOf { it.timestampMs }
        return LongArray(segments.size) { i ->
            segments[i].audioMs ?: (segments[i].timestampMs - base).coerceAtLeast(0L)
        }
    }

    /**
     * How long a segment is likely to run for, from its word timings where we
     * have them and from its length otherwise.
     *
     * This has to be the segment's own span, not "up to wherever the next one
     * starts". Treating segments as contiguous leaves no gaps anywhere, so a
     * single millisecond of misalignment chains one block into the next and
     * the whole meeting collapses into one undifferentiated block. Pauses are
     * what separate blocks, and both engines cut at pauses.
     */
    private fun spanOf(segment: TranscriptSegment): Long {
        val words = segment.words
        if (!words.isNullOrEmpty()) {
            return (words.last().ms + 400L).coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
        }
        return (segment.text.length * MS_PER_CHAR).coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
    }

    private fun ends(segments: List<TranscriptSegment>, starts: LongArray): LongArray =
        LongArray(starts.size) { i ->
            val own = starts[i] + spanOf(segments[i])
            // Never let a generous estimate manufacture an overlap with the
            // next segment; only a real pause is allowed to break a block.
            if (i + 1 < starts.size) {
                minOf(own, maxOf(starts[i + 1], starts[i] + 1))
            } else {
                own
            }
        }

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var lastWasSpace = true
        for (ch in text) {
            when {
                ch.isLetterOrDigit() -> {
                    out.append(ch.lowercaseChar())
                    lastWasSpace = false
                }
                // Keep intra-word marks that carry meaning ("don't", "3.5").
                ch == '\'' || ch == '’' -> Unit
                !lastWasSpace -> {
                    out.append(' ')
                    lastWasSpace = true
                }
            }
        }
        return out.toString().trim()
    }

    /**
     * Groups both transcripts into blocks of overlapping audio, in order.
     * Every segment on both sides lands in exactly one block.
     */
    fun align(
        current: List<TranscriptSegment>,
        fresh: List<TranscriptSegment>,
        /** The meeting's start, used only for segments with no audio offset. */
        baseMs: Long? = null
    ): List<Block> {
        val currentStart = offsets(current, baseMs)
        val freshStart = offsets(fresh, baseMs)
        val currentEnd = ends(current, currentStart)
        val freshEnd = ends(fresh, freshStart)
        val blocks = mutableListOf<Block>()
        var i = 0
        var j = 0
        while (i < current.size || j < fresh.size) {
            val ci = mutableListOf<Int>()
            val fi = mutableListOf<Int>()
            val start: Long
            var end: Long
            val takeCurrent = i < current.size &&
                (j >= fresh.size || currentStart[i] <= freshStart[j])
            if (takeCurrent) {
                start = currentStart[i]
                end = currentEnd[i]
                ci.add(i)
                i++
            } else {
                start = freshStart[j]
                end = freshEnd[j]
                fi.add(j)
                j++
            }
            // Absorb everything on either side that starts before this block
            // ends, re-checking after each absorption because a swallowed
            // segment can push the end out further.
            var grew = true
            while (grew) {
                grew = false
                while (i < current.size && currentStart[i] < end) {
                    end = maxOf(end, currentEnd[i])
                    ci.add(i)
                    i++
                    grew = true
                }
                while (j < fresh.size && freshStart[j] < end) {
                    end = maxOf(end, freshEnd[j])
                    fi.add(j)
                    j++
                    grew = true
                }
            }
            blocks.add(
                Block(
                    ordinal = blocks.size,
                    currentIndices = ci,
                    freshIndices = fi,
                    currentText = ci.joinToString(" ") { current[it].text.trim() }.trim(),
                    freshText = fi.joinToString(" ") { fresh[it].text.trim() }.trim(),
                    // The second pass always has a real audio offset, so
                    // prefer it: this is what the play button seeks to.
                    startMs = fi.firstOrNull()?.let { freshStart[it] } ?: start
                )
            )
        }
        return blocks
    }

    /** The blocks where the two passes disagree, in transcript order. */
    fun differences(blocks: List<Block>): List<Block> = blocks.filter { !it.agrees }

    /**
     * Builds the transcript the user chose: blocks in [acceptFresh] take the
     * new pass's segments, everything else keeps what is already stored.
     *
     * Accepted blocks inherit the speaker tags and highlights that were on
     * the lines they replace — those are the user's work, not the model's,
     * and a better transcript is not worth losing them. Where the old block
     * carried no tag, the new pass's own diarization stands.
     */
    fun merge(
        current: List<TranscriptSegment>,
        fresh: List<TranscriptSegment>,
        blocks: List<Block>,
        acceptFresh: Set<Int>,
        baseMs: Long? = null
    ): List<TranscriptSegment> {
        val out = mutableListOf<TranscriptSegment>()
        val currentStart = offsets(current, baseMs)
        val freshStart = offsets(fresh, baseMs)
        for (block in blocks) {
            if (block.ordinal !in acceptFresh) {
                for (index in block.currentIndices) out.add(current[index])
                continue
            }
            val donors = block.currentIndices
            for (index in block.freshIndices) {
                val segment = fresh[index]
                val donor = donors.minByOrNull {
                    kotlin.math.abs(currentStart[it] - freshStart[index])
                }?.let { current[it] }
                out.add(
                    segment.copy(
                        speaker = donor?.speaker?.takeIf { it.isNotBlank() }
                            ?: segment.speaker?.takeIf { it.isNotBlank() },
                        highlighted = segment.highlighted ||
                            (donor?.highlighted ?: false)
                    )
                )
            }
        }
        return out
    }
}
