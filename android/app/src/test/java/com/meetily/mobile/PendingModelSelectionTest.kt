package com.meetily.mobile

import com.meetily.mobile.PendingModelSelection.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A model picked while it still has to download must become the live
 * selection only once it is on disk, and a failed or cancelled download must
 * leave the old one in charge.
 */
class PendingModelSelectionTest {

    @Test
    fun successfulDownloadOfTheParkedModelApplies() {
        assertEquals(
            Outcome.APPLY,
            PendingModelSelection.decide("small.en", "small.en", false, null, true)
        )
    }

    @Test
    fun failedOrCancelledDownloadDropsThePick() {
        assertEquals(
            Outcome.DROP,
            PendingModelSelection.decide("small.en", "small.en", false, "timeout", false)
        )
        assertEquals(
            Outcome.DROP,
            PendingModelSelection.decide("small.en", "small.en", true, null, false)
        )
        // Cancelled right as the last byte landed: the user said stop.
        assertEquals(
            Outcome.DROP,
            PendingModelSelection.decide("small.en", "small.en", true, null, true)
        )
        // Reported success but the file is not there: never claim ready.
        assertEquals(
            Outcome.DROP,
            PendingModelSelection.decide("small.en", "small.en", false, null, false)
        )
    }

    @Test
    fun anotherModelFinishingLeavesThePickAlone() {
        assertEquals(
            Outcome.IGNORE,
            PendingModelSelection.decide("small.en", "base.en", false, null, true)
        )
        assertEquals(
            Outcome.IGNORE,
            PendingModelSelection.decide("small.en", "base.en", false, "boom", false)
        )
        assertEquals(
            Outcome.IGNORE,
            PendingModelSelection.decide(null, "base.en", false, null, true)
        )
    }

    @Test
    fun deleteFallsBackToAnotherDownloadedModel() {
        assertEquals(
            "small.en",
            PendingModelSelection.replacementAfterDelete(
                "base.en", listOf("base.en", "small.en", "medium")
            )
        )
        assertEquals(
            "tiny.en",
            PendingModelSelection.replacementAfterDelete("base.en", listOf("tiny.en"))
        )
    }

    @Test
    fun deleteWithNothingElseDownloadedKeepsTheSetting() {
        assertNull(PendingModelSelection.replacementAfterDelete("base.en", emptyList()))
        assertNull(PendingModelSelection.replacementAfterDelete("base.en", listOf("base.en")))
    }
}
