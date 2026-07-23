package com.meetily.mobile.whisper

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.summarize.MeetingStats

/**
 * Speech spans over a meeting's audio timeline, derived from the transcript
 * itself (segment audio offsets + word timings) — no audio analysis needed.
 * Powers skip-silence playback: anything between spans is non-speech the
 * transcriber already decided to ignore. Pure Kotlin — unit-tested.
 */
object SpeechSpans {

    data class Span(val startMs: Long, val endMs: Long)

    /** Two spans closer than this merge into one (breathing gaps aren't silence). */
    private const val JOIN_MS = 400L

    /**
     * Sorted, merged speech spans from segments that carry an audio offset.
     * Segments without [TranscriptSegment.audioMs] (edited lines, system
     * recognizer output) contribute nothing.
     */
    fun build(segments: List<TranscriptSegment>): List<Span> {
        val raw = segments.mapNotNull { segment ->
            val start = segment.audioMs ?: return@mapNotNull null
            val words = segment.words
            val begin = if (!words.isNullOrEmpty()) start + words.first().ms else start
            val duration = MeetingStats.estimateDurationMs(segment.text, words)
            val end = if (!words.isNullOrEmpty()) {
                start + words.last().ms + 800L
            } else {
                start + duration
            }
            if (end <= begin) null else Span(begin, end)
        }.sortedBy { it.startMs }
        if (raw.isEmpty()) return emptyList()

        val merged = mutableListOf(raw.first())
        for (span in raw.drop(1)) {
            val last = merged.last()
            if (span.startMs <= last.endMs + JOIN_MS) {
                if (span.endMs > last.endMs) {
                    merged[merged.size - 1] = last.copy(endMs = span.endMs)
                }
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    /**
     * Where playback should jump to from [posMs], or null to keep playing.
     * Only gaps of at least [minGapMs] are skipped, and the jump lands
     * [leadMs] before the next span so the first word isn't clipped.
     */
    fun skipTarget(
        posMs: Long,
        spans: List<Span>,
        minGapMs: Long = 1500L,
        leadMs: Long = 250L
    ): Long? {
        if (spans.isEmpty()) return null
        var previousEnd = 0L
        for (span in spans) {
            if (posMs < span.startMs) {
                val gap = span.startMs - previousEnd
                val target = (span.startMs - leadMs).coerceAtLeast(0)
                return if (gap >= minGapMs && posMs < target) target else null
            }
            if (posMs < span.endMs) return null // inside speech
            previousEnd = span.endMs
        }
        return null // past the last span: let the player run out
    }
}
