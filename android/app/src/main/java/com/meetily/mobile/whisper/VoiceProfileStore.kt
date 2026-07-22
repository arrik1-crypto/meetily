package com.meetily.mobile.whisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** A named voiceprint: the running average of a speaker's embeddings. */
data class VoiceProfile(
    val name: String,
    val embedding: FloatArray,
    val samples: Int
)

/**
 * On-device library of named voiceprints. Profiles are learned when the user
 * saves a renamed cluster's voice, tags transcript lines after the fact, or
 * enrolls directly in Settings — and are matched against cluster centroids
 * to auto-name speakers in future meetings. Everything stays local
 * (filesDir/voice_profiles.json, included in backups).
 *
 * Matching/averaging math is in pure companion functions (unit-testable).
 */
object VoiceProfileStore {

    const val FILE_NAME = "voice_profiles.json"

    /** Cosine threshold for calling a centroid "this known person". */
    const val MATCH_THRESHOLD = 0.55f

    /** New samples always keep at least 1/CAP influence on the average. */
    private const val SAMPLE_CAP = 10

    @Synchronized
    fun load(context: Context): List<VoiceProfile> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("profiles") ?: JSONArray()
            val out = mutableListOf<VoiceProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "")
                val embArr = obj.optJSONArray("emb") ?: continue
                if (name.isBlank() || embArr.length() == 0) continue
                val emb = FloatArray(embArr.length()) { embArr.getDouble(it).toFloat() }
                out.add(VoiceProfile(name, emb, obj.optInt("samples", 1).coerceAtLeast(1)))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Adds a sample for [name] (case-insensitive), creating or refining. */
    @Synchronized
    fun addSample(context: Context, name: String, embedding: FloatArray): Boolean {
        val normalized = normalizedOrNull(embedding.copyOf()) ?: return false
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val profiles = load(context).toMutableList()
        val index = profiles.indexOfFirst { it.name.equals(trimmed, ignoreCase = true) }
        if (index >= 0) {
            val old = profiles[index]
            if (old.embedding.size != normalized.size) {
                // Different embedding model: start the profile over.
                profiles[index] = VoiceProfile(old.name, normalized, 1)
            } else {
                profiles[index] = VoiceProfile(
                    old.name,
                    merged(old.embedding, old.samples.coerceAtMost(SAMPLE_CAP), normalized),
                    old.samples + 1
                )
            }
        } else {
            profiles.add(VoiceProfile(trimmed, normalized, 1))
        }
        return save(context, profiles)
    }

    @Synchronized
    fun delete(context: Context, name: String): Boolean {
        val profiles = load(context).filterNot { it.name.equals(name, ignoreCase = true) }
        return save(context, profiles)
    }

    private fun save(context: Context, profiles: List<VoiceProfile>): Boolean = try {
        val arr = JSONArray()
        for (profile in profiles) {
            val embArr = JSONArray()
            for (v in profile.embedding) embArr.put(v.toDouble())
            arr.put(
                JSONObject()
                    .put("name", profile.name)
                    .put("emb", embArr)
                    .put("samples", profile.samples)
            )
        }
        File(context.filesDir, FILE_NAME)
            .writeText(JSONObject().put("profiles", arr).toString())
        true
    } catch (_: Exception) {
        false
    }

    // --- Pure math (no Android/JSON deps; unit-tested) ---------------------

    /** Best-matching profile name for a centroid, or null below threshold. */
    fun match(
        profiles: List<VoiceProfile>,
        embedding: FloatArray?,
        threshold: Float = MATCH_THRESHOLD
    ): String? {
        if (embedding == null) return null
        val normalized = normalizedOrNull(embedding.copyOf()) ?: return null
        var bestName: String? = null
        var bestSim = threshold
        for (profile in profiles) {
            if (profile.embedding.size != normalized.size) continue
            var sim = 0f
            for (i in normalized.indices) sim += profile.embedding[i] * normalized[i]
            if (sim >= bestSim) {
                bestSim = sim
                bestName = profile.name
            }
        }
        return bestName
    }

    /** Weighted average of an existing (normalized) profile and one sample. */
    fun merged(old: FloatArray, oldWeight: Int, sample: FloatArray): FloatArray {
        val out = FloatArray(old.size)
        for (i in old.indices) {
            out[i] = old[i] * oldWeight + sample[i]
        }
        return normalizedOrNull(out) ?: sample
    }

    fun normalizedOrNull(v: FloatArray): FloatArray? {
        if (v.isEmpty()) return null
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        val length = sqrt(norm)
        if (length < 1e-9) return null
        for (i in v.indices) v[i] = (v[i] / length).toFloat()
        return v
    }
}
