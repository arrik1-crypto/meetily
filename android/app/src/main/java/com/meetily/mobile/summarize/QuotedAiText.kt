package com.meetily.mobile.summarize

/**
 * Whether a saved summary counts as AI output for "Report AI output": on
 * the meeting screen (its summary button and the ⋮ menu), the pre-meeting
 * brief's last-time card, and the offline weekly digest, which quotes each
 * meeting's first summary line and open action items. Pure Kotlin,
 * unit-tested.
 *
 * A summary counts unless its recorded model says no model wrote it
 * ([AiProvenance.EXTRACTIVE]). One saved before the model was recorded
 * (null) may have been written by a model, so it counts too. Key points
 * worked out on the spot by ExtractiveSummarizer never count: they are
 * sentences picked from the transcript, not generated text.
 */
object QuotedAiText {

    /**
     * Name for a summary whose model or template was not recorded, as the
     * meeting screen's report names a missing template.
     */
    const val UNRECORDED = "unknown"

    /** True when a model wrote [summary], or may have. */
    fun modelWrote(summary: String, summaryModel: String?): Boolean =
        summary.isNotBlank() && summaryModel?.trim() != AiProvenance.EXTRACTIVE

    /**
     * The text a report of the last-time card carries: the saved summary,
     * as the model wrote it (without the fixed notices the card puts in
     * front). Null when there is nothing generated to report: no saved
     * summary (the card shows extractive key points or a placeholder), or
     * one no model wrote.
     */
    fun lastCardText(summary: String, summaryModel: String?): String? =
        if (modelWrote(summary, summaryModel)) summary.trim() else null

    /**
     * The recorded keys (models or templates) of the summaries a listing
     * quotes, for one line of a report: distinct, in order, with a missing
     * key named [UNRECORDED]. Null when none of them was recorded, so the
     * report falls back to saying so in its own way.
     */
    fun joinKeys(keys: List<String?>): String? {
        val named = keys.map { it?.trim()?.ifBlank { null } }
        if (named.all { it == null }) return null
        return named.map { it ?: UNRECORDED }.distinct().joinToString(", ")
    }
}
