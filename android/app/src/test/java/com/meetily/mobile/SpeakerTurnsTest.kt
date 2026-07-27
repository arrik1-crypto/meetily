package com.meetily.mobile

import com.meetily.mobile.data.SpeakerTurns
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning transcript lines into the Audio tab's speaker strip.
 */
class SpeakerTurnsTest {

    private fun seg(audioMs: Long?, speaker: String?) =
        TranscriptSegment(timestampMs = 0L, text = "t", speaker = speaker, audioMs = audioMs)

    private fun build(segments: List<TranscriptSegment>, totalMs: Long = 100_000L) =
        SpeakerTurns.build(segments, totalMs) { it.speaker }

    @Test
    fun consecutiveLinesFromOnePersonAreOneTurn() {
        // Fifty one-line turns would draw as a solid smear; the strip is only
        // readable if a continuous stretch of one voice is one block.
        val result = build(
            listOf(
                seg(0L, "Ana"), seg(1_000L, "Ana"), seg(2_000L, "Ana"),
                seg(3_000L, "Ben"), seg(4_000L, "Ben")
            )
        )
        assertEquals(2, result.turns.size)
        assertEquals(listOf("Ana", "Ben"), result.names)
    }

    @Test
    fun turnsAreContiguousSoTheStripHasNoGaps() {
        // Each turn runs to the NEXT one's start, not to its own last line —
        // otherwise there is dead space wherever someone paused before
        // handing over.
        val result = build(listOf(seg(0L, "Ana"), seg(5_000L, "Ben")), totalMs = 20_000L)
        assertEquals(5_000L, result.turns[0].endMs)
        assertEquals(5_000L, result.turns[1].startMs)
        assertEquals(20_000L, result.turns[1].endMs)
    }

    @Test
    fun colourSlotsFollowFirstAppearanceAndAreStable() {
        val result = build(
            listOf(seg(0L, "Ana"), seg(1L, "Ben"), seg(2L, "Ana"), seg(3L, "Cat"))
        )
        assertEquals(listOf("Ana", "Ben", "Cat"), result.names)
        // Four turns — Ana speaks twice — but only three colour slots, because
        // Ana returning must not take a fourth colour.
        assertEquals(4, result.turns.size)
        assertEquals(listOf(0, 1, 0, 2), result.turns.map { it.slot })
    }

    @Test
    fun linesWithNoAudioOffsetAreSkippedRatherThanDrawnAtZero() {
        // Hand-typed lines have no offset. Treating that as 0 would pile
        // every one of them at the very start of the recording.
        val result = build(listOf(seg(null, "Ana"), seg(10_000L, "Ben")))
        assertEquals(listOf("Ben"), result.names)
        assertEquals(10_000L, result.turns.single().startMs)
    }

    @Test
    fun unattributedLinesDoNotCreateATurn() {
        val result = build(listOf(seg(0L, null), seg(1_000L, null)))
        assertTrue(result.turns.isEmpty())
        assertTrue(result.names.isEmpty())
    }

    @Test
    fun everyTurnHasPositiveWidth() {
        // Two lines at the same offset must not produce a zero-width turn,
        // which would be invisible and could divide by zero downstream.
        val result = build(listOf(seg(5_000L, "Ana"), seg(5_000L, "Ben")), totalMs = 5_000L)
        assertTrue(result.turns.all { it.endMs > it.startMs })
    }

    @Test
    fun turnAtFindsTheSpeakerUnderThePlayhead() {
        val result = build(listOf(seg(0L, "Ana"), seg(5_000L, "Ben")), totalMs = 10_000L)
        assertEquals(0, SpeakerTurns.turnAt(result.turns, 2_000L)?.slot)
        assertEquals(1, SpeakerTurns.turnAt(result.turns, 7_000L)?.slot)
        assertNull(SpeakerTurns.turnAt(result.turns, 99_000L))
    }

    @Test
    fun anEmptyTranscriptProducesNothingRatherThanThrowing() {
        val result = build(emptyList())
        assertTrue(result.turns.isEmpty())
    }
}
