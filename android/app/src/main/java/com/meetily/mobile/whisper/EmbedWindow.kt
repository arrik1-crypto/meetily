package com.meetily.mobile.whisper

/**
 * The slice of a transcription chunk that goes to the speaker embedder.
 *
 * Embedding cost is linear in audio length, and a chunk runs up to 28 s of
 * continuous speech; a voice vector is stable after a few seconds, and a long
 * chunk is also the likeliest to straddle two speakers. The centre is the
 * part least likely to carry the previous or next speaker's words.
 *
 * Only the per-chunk labellers use this. Enrolment and rollover embed audio
 * collected deliberately for the voiceprint and keep all of it.
 */
object EmbedWindow {
    /** 10 s at 16 kHz. */
    const val MAX_CHUNK_SAMPLES = 160_000

    /** [samples] itself when short enough, else its centred [max] samples. */
    fun centre(samples: FloatArray, max: Int): FloatArray {
        if (max <= 0 || samples.size <= max) return samples
        val from = (samples.size - max) / 2
        return samples.copyOfRange(from, from + max)
    }
}
