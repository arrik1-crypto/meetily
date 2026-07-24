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

    /**
     * Output discipline appended to EVERY template, custom ones included:
     * the difference between a usable summary and a rambling one is mostly
     * these rules, so they live here instead of being repeated per template.
     * Phrased to coexist with [ActionItems.LLM_INSTRUCTIONS]' JSON tail.
     */
    const val OUTPUT_RULES = "Rules: use clear section headings. Quote names, dates, " +
        "amounts, and deadlines exactly as spoken. Report only what was actually said — " +
        "if something is unclear or was not discussed, say so rather than guessing, and " +
        "mark uncertain speaker attributions with '(?)'. Skip sections that had no " +
        "content instead of padding them. Start directly with the summary: no preamble, " +
        "no meta-commentary."

    /** Depth knob applied on top of any template ("brief"/"standard"/"detailed"). */
    fun depthInstructions(depth: String): String = when (depth) {
        "brief" -> "Length: be extremely concise — at most six bullets total, one line " +
            "each, essentials only."
        "detailed" -> "Length: be thorough — cover every substantive topic, keep the " +
            "section structure, and include short supporting quotes where they add " +
            "precision."
        else -> "Length: scale to the meeting — short meetings get short summaries."
    }

    /** The template as actually sent to the model: instructions + depth + rules. */
    fun effective(template: SummaryTemplate, depth: String): SummaryTemplate =
        template.copy(
            llmInstructions = template.llmInstructions + " " +
                depthInstructions(depth) + " " + OUTPUT_RULES
        )

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
                "the owner (use attendee names when identifiable; write 'Unassigned' when no " +
                "owner was stated), and the deadline exactly as spoken. " +
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
        ),
        // Domain templates (pair well with the MedGemma / SaulLM models,
        // but work with any engine). Both are explicitly framed as drafts.
        SummaryTemplate(
            key = "soap",
            labelRes = R.string.template_soap,
            llmInstructions = "Structure this clinical encounter as a SOAP note: Subjective " +
                "(patient-reported symptoms, history, and concerns as stated), Objective " +
                "(findings, measurements, and observations explicitly mentioned), Assessment " +
                "(the clinician's stated impressions or differential), Plan (treatments, " +
                "prescriptions with exact names and doses as said, referrals, follow-ups). " +
                "Never infer clinical findings; mark gaps as 'not discussed'. This is a " +
                "draft note for clinician review, not a medical record."
        ),
        SummaryTemplate(
            key = "patient_recap",
            labelRes = R.string.template_patient_recap,
            llmInstructions = "Write a plain-language recap of this medical visit for the " +
                "patient: what was discussed, any diagnoses or impressions the clinician " +
                "stated, medication changes (exact names and doses as said), tests ordered " +
                "and why, warning signs to watch for, and follow-up steps with dates. Avoid " +
                "jargon; where a medical term is unavoidable, explain it in parentheses."
        ),
        SummaryTemplate(
            key = "legal_consult",
            labelRes = R.string.template_legal_consult,
            llmInstructions = "Summarize this legal consultation as a memo: the client's " +
                "situation and objectives, key facts stated (dates and amounts as said), " +
                "legal issues identified, advice and options discussed with their stated " +
                "risks, action items for lawyer and client, and information still needed. " +
                "Mark statements of law as the speaker's position, not established fact. " +
                "This is a draft for attorney review, not legal advice."
        ),
        SummaryTemplate(
            key = "matter",
            labelRes = R.string.template_matter,
            llmInstructions = "Organize this discussion as case/matter working notes: parties " +
                "and their roles, chronology of events discussed (dates verbatim), evidence " +
                "and documents referenced, arguments and counterarguments raised, strategy " +
                "decisions made, open questions, and next steps with owners and deadlines."
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
