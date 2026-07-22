package com.meetily.mobile.summarize

import android.content.Context
import com.meetily.mobile.R

/**
 * Summary templates, à la Plaud/Granola: each is a different set of
 * instructions for the LLM. The offline extractive summarizer supports the
 * general and action-items shapes; other templates fall back to general
 * when no LLM endpoint is configured. User-defined templates (see
 * [CustomTemplates]) are appended to the built-ins at pick time.
 */
data class SummaryTemplate(
    val key: String,
    val labelRes: Int = 0,
    val labelText: String? = null,
    val llmInstructions: String,
    val extractiveActionsOnly: Boolean = false
) {
    fun label(context: Context): String = labelText ?: context.getString(labelRes)
}

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
        ),
        SummaryTemplate(
            key = "one_on_one",
            labelRes = R.string.template_one_on_one,
            llmInstructions = "Summarize this one-on-one conversation: 1) topics each person " +
                "raised, 2) feedback exchanged in both directions, 3) decisions and " +
                "commitments each person made, 4) topics to revisit next time. Use the " +
                "speaker names where available."
        ),
        SummaryTemplate(
            key = "interview",
            labelRes = R.string.template_interview,
            llmInstructions = "Summarize this job interview: candidate background highlights, " +
                "strengths observed, concerns or gaps, notable verbatim quotes, and suggested " +
                "follow-up questions for later rounds. Stay factual and balanced; report only " +
                "what was actually said."
        ),
        SummaryTemplate(
            key = "research",
            labelRes = R.string.template_research,
            llmInstructions = "Summarize this user research session: participant context, pain " +
                "points with short verbatim quotes, feature requests, current workarounds, and " +
                "overall sentiment. Rank pain points by apparent severity."
        ),
        SummaryTemplate(
            key = "retro",
            labelRes = R.string.template_retro,
            llmInstructions = "Summarize this retrospective: 1) what went well, 2) what did not " +
                "go well, 3) key learnings, 4) experiments or changes to try next. Attribute " +
                "points to speakers where useful."
        ),
        SummaryTemplate(
            key = "status",
            labelRes = R.string.template_status,
            llmInstructions = "Summarize this project status meeting: progress since the last " +
                "update, current risks and blockers with owners, changes to scope or timeline, " +
                "and upcoming milestones with dates where mentioned."
        ),
        SummaryTemplate(
            key = "email",
            labelRes = R.string.template_email,
            llmInstructions = "Write a follow-up email to the attendees recapping this meeting. " +
                "Structure: a 'Subject:' line, a one-line opener, key decisions, action items " +
                "with owners and deadlines, and next steps. Professional, concise tone. Output " +
                "only the email itself, ready to send."
        )
    )

    /** Built-ins plus the user's saved custom templates. */
    fun allWithCustom(context: Context): List<SummaryTemplate> =
        ALL + CustomTemplates.load(context).map {
            SummaryTemplate(
                key = it.key,
                labelText = it.name,
                llmInstructions = it.instructions
            )
        }

    fun byKey(context: Context, key: String): SummaryTemplate =
        allWithCustom(context).firstOrNull { it.key == key } ?: ALL.first()
}
