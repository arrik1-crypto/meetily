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

    /**
     * Starter glossaries for domain-heavy meetings: terms Whisper commonly
     * fumbles without a prompt. Deliberately compact (the prompt budget is
     * shared with the user's own names and jargon — the highest-value terms
     * are always the user's, so presets leave room for them).
     */
    val MEDICAL_PRESET: List<String> = listOf(
        "hypertension", "hyperlipidemia", "tachycardia", "dyspnea", "edema",
        "ischemia", "myocardial infarction", "atrial fibrillation", "COPD",
        "GERD", "A1C", "metformin", "lisinopril", "atorvastatin",
        "amlodipine", "omeprazole", "gabapentin", "prednisone", "warfarin",
        "apixaban", "titrate", "contraindicated", "prophylaxis",
        "differential diagnosis", "palliative", "biopsy", "metastasis",
        "remission", "CBC", "MRI"
    )

    val LEGAL_PRESET: List<String> = listOf(
        "plaintiff", "defendant", "appellant", "tort", "negligence",
        "liability", "indemnification", "injunction", "subpoena",
        "deposition", "affidavit", "discovery", "voir dire", "habeas corpus",
        "prima facie", "res judicata", "estoppel", "fiduciary",
        "arbitration", "statute of limitations", "due diligence",
        "force majeure", "easement", "probate", "escrow", "lien",
        "summary judgment", "class action", "amicus brief", "retainer"
    )

    /**
     * Merges [preset] into the user's existing vocabulary, skipping terms
     * already present (case-insensitive) and preserving the user's order.
     */
    fun withPreset(existing: String, preset: List<String>): String {
        val current = existing.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val seen = current.map { it.lowercase() }.toHashSet()
        val merged = current + preset.filter { seen.add(it.lowercase()) }
        return merged.joinToString(", ")
    }

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
