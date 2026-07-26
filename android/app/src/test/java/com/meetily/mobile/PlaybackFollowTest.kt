package com.meetily.mobile

import com.meetily.mobile.data.PlaybackFollow
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mapping a playback position onto the line, and word, being spoken. */
class PlaybackFollowTest {

    private fun segment(audioMs: Long?, text: String = "line", words: List<WordStamp>? = null) =
        TranscriptSegment(
            timestampMs = 0L,
            text = text,
            speaker = null,
            audioMs = audioMs,
            words = words
        )

    @Test
    fun linesWithoutAnAudioOffsetAreSkippedRatherThanGuessedAt() {
        // Hand-typed and system-recognizer lines have no place on the audio
        // timeline; inventing one would highlight the wrong line.
        val segments = listOf(
            segment(0L),
            segment(null),
            segment(10_000L)
        )
        val timeline = PlaybackFollow.timeline(segments)
        assertEquals(2, timeline.size)
        assertEquals(listOf(0, 2), timeline.map { it.second })
    }

    @Test
    fun nothingIsActiveBeforeTheFirstLine() {
        val timeline = PlaybackFollow.timeline(listOf(segment(5_000L)))
        assertEquals(-1, PlaybackFollow.segmentAt(timeline, 0L))
        assertEquals(-1, PlaybackFollow.segmentAt(timeline, 4_999L))
        assertEquals(0, PlaybackFollow.segmentAt(timeline, 5_000L))
    }

    @Test
    fun aLineStaysCurrentUntilTheNextOneStarts() {
        // Through a pause the highlight rests on the last thing said rather
        // than blinking off in every gap.
        val timeline = PlaybackFollow.timeline(
            listOf(segment(0L), segment(10_000L), segment(20_000L))
        )
        assertEquals(0, PlaybackFollow.segmentAt(timeline, 9_999L))
        assertEquals(1, PlaybackFollow.segmentAt(timeline, 10_000L))
        assertEquals(1, PlaybackFollow.segmentAt(timeline, 19_999L))
        assertEquals(2, PlaybackFollow.segmentAt(timeline, 999_999L))
    }

    @Test
    fun outOfOrderSegmentsStillResolveByTime() {
        // Accepting a second-pass check can fold late lines back in; the
        // timeline sorts rather than trusting list order.
        val timeline = PlaybackFollow.timeline(
            listOf(segment(20_000L), segment(0L), segment(10_000L))
        )
        assertEquals(1, PlaybackFollow.segmentAt(timeline, 500L))
        assertEquals(2, PlaybackFollow.segmentAt(timeline, 12_000L))
        assertEquals(0, PlaybackFollow.segmentAt(timeline, 25_000L))
    }

    @Test
    fun anEmptyTranscriptHasNoActiveLine() {
        assertEquals(-1, PlaybackFollow.segmentAt(emptyList(), 1_000L))
    }

    @Test
    fun theWordHighlightAdvancesWithinTheLine() {
        val words = listOf(
            WordStamp(0, "the"),
            WordStamp(300, "quick"),
            WordStamp(700, "fox")
        )
        assertEquals(-1, PlaybackFollow.wordAt(words, -1L))
        assertEquals(0, PlaybackFollow.wordAt(words, 0L))
        assertEquals(0, PlaybackFollow.wordAt(words, 299L))
        assertEquals(1, PlaybackFollow.wordAt(words, 300L))
        assertEquals(2, PlaybackFollow.wordAt(words, 5_000L))
    }

    @Test
    fun aLineWithNoWordsSimplyHasNoWordHighlight() {
        assertEquals(-1, PlaybackFollow.wordAt(emptyList(), 1_000L))
    }

    @Test
    fun resolutionHoldsAcrossALongTranscript() {
        // Binary search: this runs several times a second during playback.
        val segments = (0 until 5_000).map { segment(it * 4_000L) }
        val timeline = PlaybackFollow.timeline(segments)
        assertEquals(0, PlaybackFollow.segmentAt(timeline, 0L))
        assertEquals(2_500, PlaybackFollow.segmentAt(timeline, 2_500 * 4_000L))
        assertEquals(4_999, PlaybackFollow.segmentAt(timeline, 999_999_999L))
    }
}
