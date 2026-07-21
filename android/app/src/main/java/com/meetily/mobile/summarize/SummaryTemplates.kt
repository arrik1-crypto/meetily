package com.meetily.mobile.summarize

import com.meetily.mobile.R

/**
 * Summary templates, à la Plaud/Granola: each is a different set of
 * instructions for the LLM. The offline extractive summarizer supports the
 * general and action-items shapes; other templates fall back to general
 * when no LLM endpoint is configured.
 */
data class SummaryTemplate(
    val key: String,
    val labelRes: Int,
    val llmInstructions: String,
    val extractiveActionsOnly: Boolean = false
)

object SummaryTemplates {

    val ALL: List<SummaryTemplate> = listOf(
        SummaryTemplate(
            key = "general",
            labelRes = R.string.template_general,
            llmInstructions = "Summarize the meeting into: 1) a short overview paragraph, " +
                "2) key discussion points as bullets, 3) decisions made, " +
                "4) action items with owners if mentioned."
        ),
        SummaryTemplate(
            key = "actions",
            labelRes = R.string.template_actions,
            llmInstructions = "Extract every action item from the meeting. For each: the task, " +
                "the owner (use attendee names when identifiable), and any deadline mentioned. " +
                "Group by owner as a checklist. Finish with a short list of decisions, if any. " +
                "Do not include a general summary.",
            extractiveActionsOnly = true
        ),
        SummaryTemplate(
            key = "decisions",
            labelRes = R.string.template_decisions,
            llmInstructions = "List every decision made in the meeting with one line of context " +
                "each and who made or approved it. Then list open questions that were raised " +
                "but not resolved. Do not include a general summary."
        ),
        SummaryTemplate(
            key = "standup",
            labelRes = R.string.template_standup,
            llmInstructions = "Organize the meeting as a standup report, grouped by person: " +
                "what they have done, what they will do next, and any blockers. Use attendee " +
                "and speaker names where available."
        ),
        SummaryTemplate(
            key = "sales",
            labelRes = R.string.template_sales,
            llmInstructions = "Structure the meeting as a sales call report: prospect needs and " +
                "pain points, objections raised, buying signals, competitors mentioned, and " +
                "next steps with owners and dates."
        )
    )

    fun byKey(key: String): SummaryTemplate =
        ALL.firstOrNull { it.key == key } ?: ALL.first()
}
