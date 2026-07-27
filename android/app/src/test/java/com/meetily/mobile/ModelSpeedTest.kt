package com.meetily.mobile

import com.meetily.mobile.whisper.TranscriptionModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing which model runs an unattended second pass.
 *
 * The bug this pins: ranking by file size put Parakeet (632 MB of files)
 * above large-v3-turbo-q5_0 (547 MB), and the "never re-check with the same
 * model" rule then removed Parakeet precisely because it produced the
 * transcript — handing hours of unattended work to the slowest model
 * installed. Size measures capacity, not throughput.
 */
class ModelSpeedTest {

    @Test
    fun qualityRankingDoesNotFollowFileSize() {
        // The two the ranking used to get backwards.
        assertTrue(TranscriptionModels.sizeMb("parakeet-tdt-v2") > 0)
        assertTrue(
            "turbo must outrank parakeet on QUALITY",
            TranscriptionModels.qualityRank("large-v3-turbo-q5_0") >
                TranscriptionModels.qualityRank("parakeet-tdt-v2")
        )
        assertTrue(
            "a tiny model must never outrank a large one",
            TranscriptionModels.qualityRank("tiny.en") <
                TranscriptionModels.qualityRank("small.en")
        )
    }

    @Test
    fun everyKnownModelHasAnExplicitRank() {
        // An unranked key falls to the middle default and would sort
        // arbitrarily against its neighbours.
        val ranks = TranscriptionModels.allKeys().map { TranscriptionModels.qualityRank(it) }
        assertTrue(ranks.all { it in 1..7 })
        assertTrue("ranks must actually discriminate", ranks.distinct().size >= 4)
    }

    // --- What the MANUAL check offers --------------------------------------

    @Test
    fun aManualCheckOffersEveryDownloadedModel() {
        // The regression this pins: the manual picker was fed the
        // cross-family list, so a Whisper transcript could not be re-run with
        // a different Whisper model even though one was installed — and when
        // exactly one cross-family model survived, no picker appeared at all
        // and the run just started on it.
        val downloaded = listOf(
            "large-v3-turbo-q5_0", "small.en", "nemotron-3.5", "parakeet-tdt-v2"
        )
        val offered = TranscriptionModels.orderForCheck(downloaded, "large-v3-turbo-q5_0")
        assertEquals(downloaded.size, offered.size)
        assertTrue(offered.containsAll(downloaded))
    }

    @Test
    fun theCrossFamilyModelsAreStillRecommendedFirst() {
        val offered = TranscriptionModels.orderForCheck(
            listOf("large-v3-turbo-q5_0", "small.en", "parakeet-tdt-v2"),
            "large-v3-turbo-q5_0"
        )
        // Parakeet is the independent second opinion, so it leads.
        assertEquals("parakeet-tdt-v2", offered.first())
    }

    @Test
    fun theModelThatMadeTheTranscriptComesLast() {
        // Offered, because re-running it is legitimate after a settings
        // change — but it is the least useful second opinion.
        val offered = TranscriptionModels.orderForCheck(
            listOf("small.en", "large-v3-turbo-q5_0", "parakeet-tdt-v2"),
            "small.en"
        )
        assertEquals("small.en", offered.last())
    }

    @Test
    fun anUnknownOriginStillOffersEverything() {
        // Meetings recorded before the model key was stored have none.
        val downloaded = listOf("small.en", "parakeet-tdt-v2")
        val offered = TranscriptionModels.orderForCheck(downloaded, null)
        assertEquals(2, offered.size)
        assertTrue(offered.containsAll(downloaded))
    }

    @Test
    fun theOnlyInstalledModelIsStillOffered() {
        assertEquals(
            listOf("small.en"),
            TranscriptionModels.orderForCheck(listOf("small.en"), "small.en")
        )
    }

    @Test
    fun whisperAndNemoAreDifferentFamilies() {
        assertNotEquals(
            TranscriptionModels.family("large-v3-turbo-q5_0"),
            TranscriptionModels.family("parakeet-tdt-v2")
        )
        assertEquals(
            TranscriptionModels.family("tiny.en"),
            TranscriptionModels.family("large-v3-turbo-q5_0")
        )
        assertEquals(
            TranscriptionModels.family("parakeet-tdt-v2"),
            TranscriptionModels.family("nemotron-en")
        )
    }
}
