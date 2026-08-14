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

    // --- the anchor -------------------------------------------------------
    // Suggestions are stored as raw indices, so they are only meaningful
    // against the exact transcript they were computed from. The anchor is
    // what lets a stale set be recognised and dropped instead of silently
    // tagging the wrong lines.

    @Test
    fun theSameTranscriptAnchorsTheSameWay() {
        val a = listOf(seg("one"), seg("two"))
        val b = listOf(seg("one"), seg("two"))
        assertEquals(SpeakerSuggestions.anchorFor(a), SpeakerSuggestions.anchorFor(b))
    }

    @Test
    fun taggingALineDoesNotInvalidateTheAnchor() {
        // Speaker tags are exactly what suggestions are FOR, so applying one
        // must not throw the rest of the set away. The anchor tracks the
        // text and the count, not the attribution.
        val before = listOf(seg("one"), seg("two"))
        val after = listOf(seg("one", speaker = "Chen"), seg("two"))
        assertEquals(
            SpeakerSuggestions.anchorFor(before),
            SpeakerSuggestions.anchorFor(after)
        )
    }

    @Test
    fun everyRenumberingChangesTheAnchor() {
        val base = listOf(seg("one"), seg("two"), seg("three"))
        val anchor = SpeakerSuggestions.anchorFor(base)
        // A deletion, a split, an edit, and a reorder: each shifts what an
        // index refers to, and each must be caught.
        val deleted = listOf(seg("one"), seg("three"))
        val split = listOf(seg("one"), seg("t"), seg("wo"), seg("three"))
        val edited = listOf(seg("one"), seg("two!"), seg("three"))
        val reordered = listOf(seg("two"), seg("one"), seg("three"))
        for (changed in listOf(deleted, split, edited, reordered)) {
            assertTrue(
                "a renumbered transcript kept its anchor",
                SpeakerSuggestions.anchorFor(changed) != anchor
            )
        }
    }
}
