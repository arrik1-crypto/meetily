package com.meetily.mobile.whisper

import kotlin.math.sqrt

/**
 * Online speaker clustering over voice-embedding vectors.
 *
 * Each speech chunk's embedding is assigned to the nearest existing cluster
 * (cosine similarity >= [joinThreshold]) or starts a new cluster, up to
 * [maxClusters]. Chunks too short to embed inherit the previous chunk's
 * cluster (turn continuity). [mergePass] runs once at the end of a session
 * to fuse clusters that early, sparse centroids kept apart.
 *
 * Pure Kotlin, no Android deps — covered by unit tests.
 */
class SpeakerClusterer(
    private val joinThreshold: Float = 0.60f,
    private val mergeThreshold: Float = 0.68f,
    private val maxClusters: Int = 8
) {
    private class Cluster(val id: Int, dim: Int) {
        val sum = FloatArray(dim)
        var count = 0

        fun add(normalized: FloatArray) {
            for (i in sum.indices) sum[i] += normalized[i]
            count++
        }

        fun centroid(): FloatArray? = normalizedOrNull(sum.copyOf())
    }

    private val clusters = mutableListOf<Cluster>()
    private var nextId = 1
    private var lastId: Int? = null

    /**
     * Assigns an embedding to a cluster and returns its id. A null embedding
     * (chunk too short / embedder unavailable) inherits the previous chunk's
     * cluster.
     */
    @Synchronized
    fun assign(embedding: FloatArray?): Int? {
        val normalized = embedding?.let { normalizedOrNull(it.copyOf()) } ?: return lastId

        var best: Cluster? = null
        var bestSim = -1f
        for (cluster in clusters) {
            if (cluster.sum.size != normalized.size) continue
            val centroid = cluster.centroid() ?: continue
            val sim = dot(centroid, normalized)
            if (sim > bestSim) {
                bestSim = sim
                best = cluster
            }
        }

        val target = when {
            best != null && bestSim >= joinThreshold -> best
            clusters.size < maxClusters -> Cluster(nextId++, normalized.size)
                .also { clusters.add(it) }
            best != null -> best // cap reached: closest cluster absorbs it
            else -> return lastId
        }
        target.add(normalized)
        lastId = target.id
        return target.id
    }

    /**
     * End-of-session agglomerative pass: repeatedly merges the most similar
     * cluster pair while similarity >= [mergeThreshold]. Returns a map of
     * merged-away id -> surviving id (identity mappings omitted).
     */
    @Synchronized
    fun mergePass(): Map<Int, Int> {
        val remap = mutableMapOf<Int, Int>()
        while (true) {
            var bestSim = -1f
            var bestA = -1
            var bestB = -1
            for (a in clusters.indices) {
                for (b in a + 1 until clusters.size) {
                    if (clusters[a].sum.size != clusters[b].sum.size) continue
                    val ca = clusters[a].centroid() ?: continue
                    val cb = clusters[b].centroid() ?: continue
                    val sim = dot(ca, cb)
                    if (sim > bestSim) {
                        bestSim = sim
                        bestA = a
                        bestB = b
                    }
                }
            }
            if (bestA < 0 || bestSim < mergeThreshold) break
            // Merge the smaller cluster into the larger (stable ids for the
            // cluster the user most likely already renamed).
            val (keep, drop) = if (clusters[bestA].count >= clusters[bestB].count) {
                clusters[bestA] to clusters[bestB]
            } else {
                clusters[bestB] to clusters[bestA]
            }
            for (i in keep.sum.indices) keep.sum[i] += drop.sum[i]
            keep.count += drop.count
            clusters.remove(drop)
            remap[drop.id] = keep.id
            // Chase chains: anything previously remapped to drop.id follows.
            for ((from, to) in remap.entries) {
                if (to == drop.id) remap[from] = keep.id
            }
        }
        if (lastId in remap.keys) lastId = remap[lastId]
        return remap
    }

    @Synchronized
    fun clusterCount(): Int = clusters.size

    /** Ids of all live clusters (for profile matching at session end). */
    @Synchronized
    fun clusterIds(): List<Int> = clusters.map { it.id }

    /** Normalized centroid of one cluster, or null if unknown/empty. */
    @Synchronized
    fun centroidOf(id: Int): FloatArray? =
        clusters.firstOrNull { it.id == id }?.centroid()

    companion object {
        private fun dot(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }

        private fun normalizedOrNull(v: FloatArray): FloatArray? {
            if (v.isEmpty()) return null
            var norm = 0.0
            for (x in v) norm += x.toDouble() * x
            val length = sqrt(norm)
            if (length < 1e-9) return null
            for (i in v.indices) v[i] = (v[i] / length).toFloat()
            return v
        }
    }
}
