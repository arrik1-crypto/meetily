package com.meetily.mobile

import com.meetily.mobile.whisper.VoiceProfile
import com.meetily.mobile.whisper.VoiceProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VoiceProfilesTest {

    private fun unit(axis: Int, dim: Int = 8): FloatArray =
        FloatArray(dim) { if (it == axis) 1f else 0f }

    private fun profile(name: String, axis: Int) =
        VoiceProfile(name, unit(axis), samples = 1)

    @Test
    fun matchFindsTheClosestProfileAboveThreshold() {
        val profiles = listOf(profile("Priya", 0), profile("Sam", 1))
        // Near Priya's axis: cosine ~0.98.
        val embedding = floatArrayOf(1f, 0.2f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals("Priya", VoiceProfileStore.match(profiles, embedding))
    }

    @Test
    fun matchRejectsBelowThreshold() {
        val profiles = listOf(profile("Priya", 0))
        // Orthogonal voice: cosine 0.
        assertNull(VoiceProfileStore.match(profiles, unit(2)))
    }

    @Test
    fun matchHandlesNullAndZeroVectors() {
        val profiles = listOf(profile("Priya", 0))
        assertNull(VoiceProfileStore.match(profiles, null))
        assertNull(VoiceProfileStore.match(profiles, FloatArray(8)))
    }

    @Test
    fun matchSkipsMismatchedDimensions() {
        val profiles = listOf(VoiceProfile("Old", unit(0, dim = 4), 1))
        assertNull(VoiceProfileStore.match(profiles, unit(0, dim = 8)))
    }

    @Test
    fun mergedStaysNormalizedAndLeansTowardHistory() {
        val old = unit(0)
        val sample = unit(1)
        val merged = VoiceProfileStore.merged(old, oldWeight = 3, sample = sample)
        // Unit length.
        var norm = 0.0
        for (v in merged) norm += v.toDouble() * v
        assertEquals(1.0, sqrt(norm), 1e-4)
        // History (axis 0) outweighs the single new sample (axis 1).
        assertTrue(merged[0] > merged[1])
        assertTrue(merged[1] > 0f)
    }

    @Test
    fun mergedWithEqualWeightSitsBetween() {
        val merged = VoiceProfileStore.merged(unit(0), oldWeight = 1, sample = unit(1))
        assertEquals(merged[0], merged[1], 1e-5f)
    }

    @Test
    fun matchSkipsProfilesFromAnotherModelEvenWithMatchingDimensions() {
        // Same 8-dim space, but tagged with a different speaker model:
        // cross-model cosine similarity is meaningless, never a match.
        val profiles = listOf(VoiceProfile("Priya", unit(0), 1, model = "resnet34-en"))
        assertNull(
            VoiceProfileStore.match(profiles, unit(0), model = "resnet293-en")
        )
        assertEquals(
            "Priya",
            VoiceProfileStore.match(profiles, unit(0), model = "resnet34-en")
        )
    }

    @Test
    fun matchAllowsLegacyUntaggedProfilesOnDimensionsAlone() {
        val profiles = listOf(VoiceProfile("Legacy", unit(0), 1, model = ""))
        assertEquals(
            "Legacy",
            VoiceProfileStore.match(profiles, unit(0), model = "resnet293-en")
        )
    }

    @Test
    fun matchWithoutModelKeepsOldBehaviour() {
        val profiles = listOf(VoiceProfile("Priya", unit(0), 1, model = "resnet34-en"))
        assertEquals("Priya", VoiceProfileStore.match(profiles, unit(0)))
    }

    @Test
    fun pcm16RoundTripsWithinQuantizationError() {
        val original = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 0.123f, -0.987f)
        val decoded = VoiceProfileStore.decodePcm16(
            VoiceProfileStore.encodePcm16(original)
        )
        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertEquals(original[i], decoded[i], 1.5f / 32768f)
        }
    }

    @Test
    fun pcm16ClampsOutOfRangeSamples() {
        val decoded = VoiceProfileStore.decodePcm16(
            VoiceProfileStore.encodePcm16(floatArrayOf(2f, -2f))
        )
        assertEquals(1f, decoded[0], 1.5f / 32768f)
        assertEquals(-1f, decoded[1], 1.5f / 32768f)
    }
}
