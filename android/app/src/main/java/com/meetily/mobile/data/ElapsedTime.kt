package com.meetily.mobile.data

import java.util.Locale

/**
 * Where a transcript line sits INSIDE the recording, rather than what the
 * wall clock said at the time.
 *
 * Which clock to subtract is not a cosmetic choice. Two candidates exist and
 * they diverge the moment a recording is paused: wall-clock elapsed
 * (`timestampMs - meetingStartMs`) keeps counting through the pause, while
 * the audio file does not. Tapping a line seeks by [TranscriptSegment.audioMs],
 * so a label derived from the wall clock would read 12:30 on a line whose
 * seek lands at 10:05 — the label and the thing it labels would disagree.
 *
 * So: audioMs when the line has it, wall-clock difference only as a fallback
 * for lines that never had it (hand-typed lines, some system-recognizer
 * output). Imports are fine either way — AudioFileImporter re-anchors the
 * meeting's start so that the fallback yields the in-file offset too.
 */
object ElapsedTime {

    /**
     * Milliseconds into the recording for [segment].
     *
     * Never negative: a meeting recovered after a crash can hold segments
     * stamped before its own start, and "-0:00:03" is worse than "0:00:00".
     */
    fun offsetMs(segment: TranscriptSegment, meetingStartMs: Long): Long {
        segment.audioMs?.let { return it.coerceAtLeast(0L) }
        if (meetingStartMs <= 0L) return 0L
        return (segment.timestampMs - meetingStartMs).coerceAtLeast(0L)
    }

    /**
     * "0:04:11". The hours field is always present so the labels form an
     * even column down the transcript, and so a line is never mistaken for
     * a time of day.
     */
    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000L
        return String.format(
            Locale.US, "%d:%02d:%02d", total / 3600L, (total % 3600L) / 60L, total % 60L
        )
    }

    /** [offsetMs] rendered by [format] — what every transcript label uses. */
    fun label(segment: TranscriptSegment, meetingStartMs: Long): String =
        format(offsetMs(segment, meetingStartMs))
}
