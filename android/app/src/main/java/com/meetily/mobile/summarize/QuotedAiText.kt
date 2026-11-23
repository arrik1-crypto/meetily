package com.meetily.mobile.summarize

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.SummaryAlternatives

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

    /**
     * True when [m]'s action items are a model's words that another run
     * wrote than the summary shown (a switch since), so a report of them
     * names that run rather than the summary's.
     */
    fun itemsFromAnotherModelRun(m: Meeting): Boolean =
        SummaryAlternatives.actionItemsFromAnotherRun(m) &&
            SummaryAlternatives.actionItemsRun(m).model?.trim() != AiProvenance.EXTRACTIVE

    /**
     * The runs whose words a listing of [meetings] quotes (each summary's
     * first line and the open action items): the summary shown, when a
     * model wrote it, and the run that wrote the open items, when that was
     * another one and a model. For the template and model lines of a report.
     */
    fun quotedRuns(meetings: List<Meeting>): List<SummaryAlternatives.Run> {
        val out = ArrayList<SummaryAlternatives.Run>()
        for (m in meetings) {
            if (modelWrote(m.summary, m.summaryModel)) {
                out.add(SummaryAlternatives.Run(m.summaryTemplate, m.summaryModel))
            }
            if (m.actionItems.any { !it.done } && itemsFromAnotherModelRun(m)) {
                out.add(SummaryAlternatives.actionItemsRun(m))
            }
        }
        return out
    }
}
