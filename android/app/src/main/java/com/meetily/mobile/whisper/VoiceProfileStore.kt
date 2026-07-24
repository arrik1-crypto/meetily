package com.meetily.mobile.whisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * A named voiceprint: the running average of a speaker's embeddings, tagged
 * with the speaker model that produced it ("" for profiles saved before
 * model tagging existed).
 */
data class VoiceProfile(
    val name: String,
    val embedding: FloatArray,
    val samples: Int,
    val model: String = ""
)

/**
 * On-device library of named voiceprints. Profiles are learned when the user
 * saves a renamed cluster's voice, tags transcript lines after the fact, or
 * enrolls directly in Settings — and are matched against cluster centroids
 * to auto-name speakers in future meetings. Everything stays local
 * (filesDir/voice_profiles.json, included in backups).
 *
 * Embeddings from different speaker models live in different vector spaces
 * and never cross-match, so alongside each profile we keep the raw audio it
 * was learned from (filesDir/voiceprint-audio/, 16 kHz mono PCM16, capped).
 * When the user switches speaker models, [reembedForModel] replays that
 * audio through the new model so profiles carry over automatically instead
 * of demanding re-enrollment.
 *
 * Matching/averaging math is in pure companion functions (unit-testable).
 */
object VoiceProfileStore {

    const val FILE_NAME = "voice_profiles.json"

    const val AUDIO_DIR = "voiceprint-audio"

    /** Cosine threshold for calling a centroid "this known person". */
    const val MATCH_THRESHOLD = 0.55f

    /** New samples always keep at least 1/CAP influence on the average. */
    private const val SAMPLE_CAP = 10

    /** Stored audio per profile is capped at 60 s of 16 kHz mono. */
    private const val AUDIO_CAP_SAMPLES = 16_000 * 60

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
                out.add(
                    VoiceProfile(
                        name, emb,
                        obj.optInt("samples", 1).coerceAtLeast(1),
                        obj.optString("model", "")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Adds a sample for [name] (case-insensitive), creating or refining.
     * [model] is the speaker-model key the embedding came from; [audio]
     * (16 kHz mono float PCM, optional) is banked so the profile can be
     * re-embedded when the user switches speaker models.
     */
    @Synchronized
    fun addSample(
        context: Context,
        name: String,
        embedding: FloatArray,
        model: String,
        audio: FloatArray? = null
    ): Boolean {
        val normalized = normalizedOrNull(embedding.copyOf()) ?: return false
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val profiles = load(context).toMutableList()
        val index = profiles.indexOfFirst { it.name.equals(trimmed, ignoreCase = true) }
        if (index >= 0) {
            val old = profiles[index]
            val sameSpace = old.embedding.size == normalized.size &&
                (old.model.isBlank() || old.model == model)
            profiles[index] = if (sameSpace) {
                VoiceProfile(
                    old.name,
                    merged(old.embedding, old.samples.coerceAtMost(SAMPLE_CAP), normalized),
                    old.samples + 1,
                    model
                )
            } else {
                // Different embedding model: start the profile over.
                VoiceProfile(old.name, normalized, 1, model)
            }
        } else {
            profiles.add(VoiceProfile(trimmed, normalized, 1, model))
        }
        if (audio != null && audio.isNotEmpty()) appendAudio(context, trimmed, audio)
        return save(context, profiles)
    }

    @Synchronized
    fun delete(context: Context, name: String): Boolean {
        val profiles = load(context).filterNot { it.name.equals(name, ignoreCase = true) }
        audioFileFor(context, name).delete()
        return save(context, profiles)
    }

    // --- Audio bank (enables rollover across speaker models) ---------------

    fun hasStoredAudio(context: Context, name: String): Boolean =
        audioFileFor(context, name).length() >= 16_000L * 2 * 3 // >= 3 s

    private fun audioFileFor(context: Context, name: String): File {
        val dir = File(context.filesDir, AUDIO_DIR).apply { mkdirs() }
        // Case-insensitive identity, filesystem-safe, collision-proofed by a
        // hash of the exact (lowercased) name.
        val base = name.trim().lowercase()
        val safe = base.replace(Regex("[^a-z0-9]+"), "_").take(40).trim('_')
        val tag = Integer.toHexString(base.hashCode())
        return File(dir, "${safe}_$tag.pcm")
    }

    private fun appendAudio(context: Context, name: String, audio: FloatArray) {
        try {
            val file = audioFileFor(context, name)
            val haveSamples = file.length() / 2
            if (haveSamples >= AUDIO_CAP_SAMPLES) return
            val room = (AUDIO_CAP_SAMPLES - haveSamples).toInt()
            val chunk = if (audio.size > room) audio.copyOf(room) else audio
            file.appendBytes(encodePcm16(chunk))
        } catch (_: Exception) {
            // Audio banking is best-effort; the profile itself still saved.
        }
    }

    private fun loadAudio(context: Context, name: String): FloatArray? = try {
        val file = audioFileFor(context, name)
        if (file.length() < 16_000L * 2 * 3) null else decodePcm16(file.readBytes())
    } catch (_: Exception) {
        null
    }

    /**
     * Rolls profiles over to [model]: every profile tagged with a different
     * (or unknown) model that has banked audio is re-embedded through
     * [embed] (the extractor for [model], already loaded by the caller).
     * Profiles without audio are left untouched — they simply won't match
     * under the new model until re-enrolled. Returns the updated list.
     */
    @Synchronized
    fun reembedForModel(
        context: Context,
        model: String,
        embed: (FloatArray) -> FloatArray?
    ): List<VoiceProfile> {
        val profiles = load(context).toMutableList()
        var changed = false
        for (i in profiles.indices) {
            val profile = profiles[i]
            if (profile.model == model) continue
            val audio = loadAudio(context, profile.name) ?: continue
            val normalized = embed(audio)?.let { normalizedOrNull(it.copyOf()) } ?: continue
            profiles[i] = VoiceProfile(profile.name, normalized, 1, model)
            changed = true
        }
        if (changed) save(context, profiles)
        return profiles
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
                    .put("model", profile.model)
            )
        }
        File(context.filesDir, FILE_NAME)
            .writeText(JSONObject().put("profiles", arr).toString())
        true
    } catch (_: Exception) {
        false
    }

    // --- Pure math (no Android/JSON deps; unit-tested) ---------------------

    /**
     * Best-matching profile name for a centroid, or null below threshold.
     * When [model] is given, profiles tagged with a DIFFERENT model are
     * skipped even if their dimensions happen to agree — embeddings from
     * different models share no vector space, so a same-dimension
     * comparison would be a coin flip, not a match. Untagged (legacy)
     * profiles still match on dimensions alone.
     */
    fun match(
        profiles: List<VoiceProfile>,
        embedding: FloatArray?,
        threshold: Float = MATCH_THRESHOLD,
        model: String? = null
    ): String? {
        if (embedding == null) return null
        val normalized = normalizedOrNull(embedding.copyOf()) ?: return null
        var bestName: String? = null
        var bestSim = threshold
        for (profile in profiles) {
            if (profile.embedding.size != normalized.size) continue
            if (model != null && profile.model.isNotBlank() && profile.model != model) {
                continue
            }
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

    /** Float [-1,1] mono PCM → little-endian PCM16 bytes. */
    fun encodePcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            val v = (clamped * 32767f).toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Little-endian PCM16 bytes → float [-1,1] mono PCM. */
    fun decodePcm16(bytes: ByteArray): FloatArray {
        val out = FloatArray(bytes.size / 2)
        for (i in out.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo) / 32768f
        }
        return out
    }
}
