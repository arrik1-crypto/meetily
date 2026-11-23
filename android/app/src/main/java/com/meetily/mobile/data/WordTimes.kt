package com.meetily.mobile.data

/**
 * Where a transcript line's word timings ([TranscriptSegment.words]) came
 * from, when anyone knows. Stored on the line as "wordTimes"; a line without
 * it is "unknown": timed by the recogniser that wrote it, or saved before
 * this was recorded (when an edit may have estimated them already).
 *
 * Edits keep the timings they can (WordRealign) and estimate the rest, which
 * is what [ESTIMATED] marks; "Re-time words" (WordAlign, AlignService)
 * measures them against the recording again and marks the line [ALIGNED].
 * Pure Kotlin, unit-tested.
 */
enum class WordTimes(val json: String) {
    /** At least one word's time was estimated from the text by an edit. */
    ESTIMATED("estimated"),

    /** Measured against the recording by the forced aligner. */
    ALIGNED("aligned");

    companion object {
        /** The value stored as [raw]; null for absent and for anything unknown. */
        fun parse(raw: String?): WordTimes? = values().firstOrNull { it.json == raw }

        /**
         * The flag for a line whose words went from [before] to [after] in an
         * edit, given its flag [flag] before it. Words kept exactly as they
         * were (same time, same text) keep the flag: deleting words or
         * splitting a line between words measures nothing anew. Any word that
         * is new or re-timed makes the line [ESTIMATED]. A line left without
         * words has no flag: its timings are missing, which is its own state.
         */
        fun afterEdit(
            flag: WordTimes?,
            before: List<WordStamp>?,
            after: List<WordStamp>?
        ): WordTimes? {
            if (after.isNullOrEmpty()) return null
            val kept = before.orEmpty().toHashSet()
            return if (after.all { it in kept }) flag else ESTIMATED
        }
    }
}
