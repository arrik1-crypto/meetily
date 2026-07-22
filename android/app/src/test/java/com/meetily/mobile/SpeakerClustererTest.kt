package com.meetily.mobile

import com.meetily.mobile.whisper.SpeakerClusterer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerClustererTest {

    // Deterministic "voices": orthogonal basis vectors are maximally distinct;
    // small perturbations of one stay similar to it.
    private fun voice(axis: Int, dim: Int = 8): FloatArray =
        FloatArray(dim) { if (it == axis) 1f else 0f }

    private fun near(base: FloatArray, wobble: Float, wobbleAxis: Int): FloatArray {
        val v = base.copyOf()
        v[wobbleAxis] += wobble
        return v
    }

    @Test
    fun distinctVoicesGetDistinctClusters() {
        val c = SpeakerClusterer()
        val a = c.assign(voice(0))
        val b = c.assign(voice(1))
        val third = c.assign(voice(2))
        assertNotEquals(a, b)
        assertNotEquals(b, third)
        assertNotEquals(a, third)
        assertEquals(3, c.clusterCount())
    }

    @Test
    fun similarVoicesJoinTheSameCluster() {
        val c = SpeakerClusterer()
        val a = c.assign(voice(0))
        val again = c.assign(near(voice(0), 0.2f, 3)) // cosine ~0.98
        assertEquals(a, again)
        assertEquals(1, c.clusterCount())
    }

    @Test
    fun nullEmbeddingInheritsPreviousCluster() {
        val c = SpeakerClusterer()
        assertNull(c.assign(null)) // nothing spoken yet
        val a = c.assign(voice(0))
        assertEquals(a, c.assign(null)) // short chunk continues the turn
    }

    @Test
    fun zeroVectorBehavesLikeNull() {
        val c = SpeakerClusterer()
        val a = c.assign(voice(0))
        assertEquals(a, c.assign(FloatArray(8))) // unnormalizable -> inherit
        assertEquals(1, c.clusterCount())
    }

    @Test
    fun clusterCapAbsorbsIntoClosest() {
        val c = SpeakerClusterer(maxClusters = 2)
        val a = c.assign(voice(0))
        val b = c.assign(voice(1))
        // A third distinct voice can't open a cluster; it lands on one of the two.
        val third = c.assign(voice(2))
        assertTrue(third == a || third == b)
        assertEquals(2, c.clusterCount())
    }

    @Test
    fun mergePassFusesNearIdenticalClusters() {
        // High join threshold forces two clusters for the same voice, then the
        // merge pass (lower threshold) fuses them back together.
        val c = SpeakerClusterer(joinThreshold = 0.995f, mergeThreshold = 0.9f)
        val a = c.assign(voice(0))!!
        c.assign(voice(0)) // bulk up cluster A: 2 members
        val b = c.assign(near(voice(0), 0.15f, 1))!! // cosine ~0.989 < 0.995
        assertNotEquals(a, b)
        assertEquals(2, c.clusterCount())

        val remap = c.mergePass()
        assertEquals(1, c.clusterCount())
        // Larger cluster's id survives.
        assertEquals(mapOf(b to a), remap)
    }

    @Test
    fun mergePassLeavesDistinctVoicesAlone() {
        val c = SpeakerClusterer()
        c.assign(voice(0))
        c.assign(voice(1))
        assertTrue(c.mergePass().isEmpty())
        assertEquals(2, c.clusterCount())
    }

    @Test
    fun mergePassResolvesChains() {
        // Three clusters of the same underlying voice, forced apart by an
        // impossible join threshold; merging happens pairwise, so the remap
        // must chase intermediate ids to the final survivor.
        val c = SpeakerClusterer(joinThreshold = 1.1f, mergeThreshold = 0.9f)
        val ids = listOf(
            c.assign(voice(0))!!,
            c.assign(near(voice(0), 0.1f, 1))!!,
            c.assign(near(voice(0), 0.1f, 2))!!
        )
        assertEquals(3, c.clusterCount())
        val remap = c.mergePass()
        assertEquals(1, c.clusterCount())
        val survivor = (ids.toSet() - remap.keys).single()
        for (to in remap.values) {
            assertEquals(survivor, to)
        }
    }

    @Test
    fun assignAfterMergeContinuesWithSurvivingId() {
        val c = SpeakerClusterer(joinThreshold = 0.995f, mergeThreshold = 0.9f)
        val a = c.assign(voice(0))!!
        c.assign(voice(0))
        c.assign(near(voice(0), 0.15f, 1))
        c.mergePass()
        // lastId was remapped, so a follow-up null chunk inherits the survivor.
        assertEquals(a, c.assign(null))
    }
}
