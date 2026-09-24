package com.meetily.mobile

import com.meetily.mobile.whisper.TranscriptionModels
import com.meetily.mobile.whisper.VoiceProfile
import com.meetily.mobile.whisper.VoiceProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which model an accuracy check picks: quality first, but never a model that
 * cannot handle the transcript's language while a suitable one is installed.
 */
class CheckRankingTest {

    @Test
    fun multilingualSourceSkipsEnglishOnlyCandidates() {
        // A German meeting from multilingual Whisper, both Parakeets installed:
        // v2 (English-only) used to win the tie on list order.
        val ranked = TranscriptionModels.rankForCheck(
            listOf("small-q5_1", "parakeet-tdt-v2", "parakeet-tdt-v3"), "small-q5_1"
        )
        assertEquals("parakeet-tdt-v3", ranked.first())
        assertFalse("parakeet-tdt-v2" in ranked)
    }

    @Test
    fun englishOnlyStillRunsWhenNothingElseIsInstalled() {
        val ranked = TranscriptionModels.rankForCheck(
            listOf("small-q5_1", "parakeet-tdt-v2"), "small-q5_1"
        )
        assertEquals(listOf("parakeet-tdt-v2"), ranked)
    }

    @Test
    fun englishSourceKeepsTheOldOrder() {
        val ranked = TranscriptionModels.rankForCheck(
            listOf("small.en", "parakeet-tdt-v2", "parakeet-tdt-v3"), "small.en"
        )
        assertEquals(listOf("parakeet-tdt-v2", "parakeet-tdt-v3"), ranked)
    }

    @Test
    fun japaneseTextAvoidsTheEuropeanOnlyModel() {
        val ranked = TranscriptionModels.rankForCheck(
            listOf("large-v3-turbo-q5_0", "parakeet-tdt-v3", "nemotron-3.5"),
            "large-v3-turbo-q5_0",
            transcriptSample = "今日は会議です。来週の予定について話しましょう。"
        )
        assertEquals("nemotron-3.5", ranked.first())
        assertFalse("parakeet-tdt-v3" in ranked)
    }

    @Test
    fun unknownOriginPrefersMultilingualOnATie() {
        val ranked = TranscriptionModels.rankForCheck(
            listOf("parakeet-tdt-v2", "parakeet-tdt-v3"), null
        )
        assertEquals("parakeet-tdt-v3", ranked.first())
        assertEquals(2, ranked.size)
    }

    @Test
    fun scriptDetection() {
        assertEquals(true, TranscriptionModels.europeanScript("Guten Morgen zusammen"))
        assertEquals(true, TranscriptionModels.europeanScript("Доброе утро всем"))
        assertEquals(false, TranscriptionModels.europeanScript("今日は会議です、よろしく"))
        assertNull(TranscriptionModels.europeanScript("ok 1 2"))
    }

    // --- Voice profiles: when a new sample refines rather than restarts ----

    private fun unit(axis: Int, dim: Int = 8) = FloatArray(dim) { if (it == axis) 1f else 0f }

    @Test
    fun sameModelProfileIsRefined() {
        val old = VoiceProfile("A", unit(0), 5, model = "m1")
        assertTrue(VoiceProfileStore.continuesProfile(old, unit(1), "m1"))
    }

    @Test
    fun otherModelProfileRestarts() {
        val old = VoiceProfile("A", unit(0), 5, model = "m1")
        assertFalse(VoiceProfileStore.continuesProfile(old, unit(0), "m2"))
    }

    @Test
    fun untaggedProfileIsRefinedOnlyWhenTheVoicePlausiblyMatches() {
        val legacy = VoiceProfile("A", unit(0), 10)
        // Same space, same voice: keep the history.
        assertTrue(VoiceProfileStore.continuesProfile(legacy, unit(0), "m2"))
        // Same dimensions, unrelated vector: another model's space.
        assertFalse(VoiceProfileStore.continuesProfile(legacy, unit(3), "m2"))
        assertFalse(VoiceProfileStore.continuesProfile(legacy, unit(0, dim = 4), "m2"))
    }
}
