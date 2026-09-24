package com.meetily.mobile.data

/**
 * Turns highlight taps recorded as audio positions into starred lines.
 *
 * An audio-only recording has no transcript while it runs, so a Highlight
 * tap cannot star a line; it is kept as an offset into the audio instead.
 * Once a transcript exists, each mark stars the line being spoken at that
 * moment: the last line starting at or before it, or the first line when the
 * mark comes before any speech. Lines with no audio position are skipped.
 *
 * Idempotent, so it can run on every batch a transcription pass writes.
 * Pure Kotlin, so it is unit-tested.
 */
object HighlightMarks {

    fun apply(segments: MutableList<TranscriptSegment>, marksMs: List<Long>) {
        if (marksMs.isEmpty() || segments.isEmpty()) return
        val timed = segments.indices
            .filter { segments[it].audioMs != null }
            .sortedBy { segments[it].audioMs }
        if (timed.isEmpty()) return
        for (mark in marksMs) {
            var target = timed.first()
            for (index in timed) {
                val start = segments[index].audioMs ?: continue
                if (start <= mark) target = index else break
            }
            val segment = segments[target]
            if (!segment.highlighted) segments[target] = segment.copy(highlighted = true)
        }
    }
}
