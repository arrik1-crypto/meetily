package com.meetily.mobile

import com.meetily.mobile.data.SpeakerSuggestions
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speaker attribution now finishes while the user is somewhere else, so the
 * proposals are re-checked against the transcript as it stands when they get
 * back — a tag they applied themselves in the meantime must survive.
 */
class SpeakerSuggestionsTest {

    private fun seg(text: String, speaker: String? = null) =
        TranscriptSegment(timestampMs = 0L, text = text, speaker = speaker)

    @Test
    fun keepsSuggestionsForUntaggedLines() {
        val segments = listOf(seg("one"), seg("two"))
        val out = SpeakerSuggestions.applicable(listOf(0 to "Ada", 1 to "Bo"), segments)
        assertEquals(listOf(0 to "Ada", 1 to "Bo"), out)
    }

    @Test
    fun aTagAppliedWhileTheRunWasGoingWins() {
        val segments = listOf(seg("one", speaker = "Chen"), seg("two"))
        val out = SpeakerSuggestions.applicable(listOf(0 to "Ada", 1 to "Bo"), segments)
        assertEquals(listOf(1 to "Bo"), out)
    }

    @Test
    fun dropsLinesThatNoLongerExist() {
        // The transcript can shrink between the run and the review.
        val out = SpeakerSuggestions.applicable(listOf(0 to "Ada", 9 to "Bo"), listOf(seg("one")))
        assertEquals(listOf(0 to "Ada"), out)
    }

    @Test
    fun dropsNegativeLineNumbersFromAMisbehavingModel() {
        val out = SpeakerSuggestions.applicable(listOf(-1 to "Ada"), listOf(seg("one")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun oneSuggestionPerLine() {
        val out = SpeakerSuggestions.applicable(
            listOf(0 to "Ada", 0 to "Bo"), listOf(seg("one"))
        )
        assertEquals(listOf(0 to "Ada"), out)
    }

    @Test
    fun everythingAlreadyTaggedLeavesNothingToReview() {
        val segments = listOf(seg("one", speaker = "Chen"))
        assertTrue(SpeakerSuggestions.applicable(listOf(0 to "Ada"), segments).isEmpty())
    }
}
