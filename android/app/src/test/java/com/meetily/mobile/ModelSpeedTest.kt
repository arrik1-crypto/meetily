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
