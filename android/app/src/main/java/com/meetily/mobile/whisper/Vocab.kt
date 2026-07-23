package com.meetily.mobile.whisper

/**
 * Builds Whisper's initial prompt from the user's custom vocabulary — names,
 * acronyms, and jargon the model should prefer when the audio is ambiguous.
 * Whisper conditions its decoder on this text, so listing the terms in a
 * plain "glossary" sentence is enough to bias transcription toward them.
 * Pure Kotlin — unit-tested.
 */
object Vocab {

    /** Keep the prompt well under whisper's ~224-token prompt budget. */
    private const val MAX_CHARS = 600

    fun promptFor(raw: String): String? {
        val terms = raw.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (terms.isEmpty()) return null
        val sb = StringBuilder("Glossary: ")
        var added = 0
        for (term in terms) {
            val piece = if (added == 0) term else ", $term"
            if (sb.length + piece.length > MAX_CHARS - 1) break
            sb.append(piece)
            added++
        }
        if (added == 0) return null
        sb.append('.')
        return sb.toString()
    }
}
