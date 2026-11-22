package com.meetily.mobile.summarize

import android.content.Context
import com.meetily.mobile.R
import com.meetily.mobile.data.Meeting

/**
 * Fixed disclaimers for summaries in regulated domains: clinical notes and
 * legal memos.
 *
 * The clinical and legal templates also ask the model to frame their output
 * as a draft, but that is only a request, and small on-device models drop it
 * routinely. These are the authoritative notices: fixed strings from
 * resources, never generated, decided by which template and model actually
 * wrote the summary ([Meeting.summaryTemplate], [Meeting.summaryModel]).
 * They are shown beside the summary and added to every output that carries
 * it (share, copy, Markdown and PDF export, the pre-meeting brief, the
 * digest and Ask Library answers built from it).
 *
 * The classification is pure Kotlin and unit-tested; only [text] needs a
 * Context.
 */
object AiDisclaimers {

    enum class Category { CLINICAL, LEGAL }

    /**
     * Every built-in template, decided explicitly: a category, or null for
     * none. A unit test fails when a template is added without an entry
     * here, so a new clinical or legal template cannot ship silently
     * without its notice.
     */
    val TEMPLATE_CATEGORIES: Map<String, Category?> = mapOf(
        "general" to null,
        "actions" to null,
        "decisions" to null,
        "standup" to null,
        "sales" to null,
        "one_on_one" to null,
        "interview" to null,
        "research" to null,
        "retro" to null,
        "status" to null,
        "email" to null,
        "soap" to Category.CLINICAL,
        "patient_recap" to Category.CLINICAL,
        "legal_consult" to Category.LEGAL,
        "exec_summary" to null,
        "note_to_self" to null,
        "matter" to Category.LEGAL
    )

    /** The category of a template key; custom and unknown keys have none. */
    fun forTemplate(templateKey: String?): Category? =
        templateKey?.let { TEMPLATE_CATEGORIES[it] }

    /**
     * A medical model's output is clinical whatever template asked for it.
     * Matched by name so an endpoint serving MedGemma counts as well as the
     * on-device one.
     */
    fun forModel(modelKey: String?): Category? =
        if (modelKey?.contains("medgemma", ignoreCase = true) == true) {
            Category.CLINICAL
        } else {
            null
        }

    /**
     * Best effort for summaries saved before the template was recorded: the
     * section headings each clinical and legal template asks for, or the
     * draft sentence the SOAP and legal consultation prompts ask the model
     * to include. A false positive costs one extra line of caution; a miss
     * leaves a legacy summary as it always was.
     */
    fun guessFromText(summary: String): Set<Category> {
        val out = LinkedHashSet<Category>()
        val lower = summary.lowercase()
        if (count(SOAP_HEADINGS, lower) >= 3 ||
            count(PATIENT_RECAP_HEADINGS, lower) >= 2 ||
            "not a medical record" in lower
        ) {
            out.add(Category.CLINICAL)
        }
        if (count(MATTER_HEADINGS, lower) >= 3 ||
            count(LEGAL_CONSULT_HEADINGS, lower) >= 2 ||
            "not legal advice" in lower
        ) {
            out.add(Category.LEGAL)
        }
        return out
    }

    private fun count(headings: List<Regex>, lower: String): Int =
        headings.count { it.containsMatchIn(lower) }

    /**
     * Section names opening a line, after any heading, bullet, bold or
     * numbering marks ("## Subjective", "**Objective**", "3. Assessment").
     * Each name is a regex over lowercased text.
     */
    private fun headings(vararg names: String): List<Regex> = names.map {
        Regex("(?m)^[\\s#*_\\-•\\d.)]*$it\\b")
    }

    /** The soap template's four sections. */
    private val SOAP_HEADINGS = headings("subjective", "objective", "assessment", "plan")

    /** The patient_recap template's most distinctive sections. */
    private val PATIENT_RECAP_HEADINGS =
        headings("medications?", "tests?\\s+ordered", "warning\\s+signs?")

    /** The matter template's sections. */
    private val MATTER_HEADINGS =
        headings("parties", "chronology", "evidence", "arguments", "strategy")

    /**
     * The legal_consult template's sections. "Advice" and "options" count
     * once between them: both open ordinary bullets in any meeting's notes.
     */
    private val LEGAL_CONSULT_HEADINGS = headings(
        "legal\\s+issues?", "(?:advice|options)", "information\\s+still\\s+needed"
    )

    /**
     * The notices a summary needs, in a stable order (clinical first). None
     * for a blank summary. With no recorded template, the text is examined
     * instead ([guessFromText]).
     */
    fun categories(summary: String, templateKey: String?, modelKey: String?): Set<Category> {
        if (summary.isBlank()) return emptySet()
        val found = HashSet<Category>()
        if (templateKey.isNullOrBlank()) {
            found.addAll(guessFromText(summary))
        } else {
            forTemplate(templateKey)?.let { found.add(it) }
        }
        forModel(modelKey)?.let { found.add(it) }
        return Category.values().filter { it in found }.toCollection(LinkedHashSet())
    }

    fun categories(meeting: Meeting): Set<Category> =
        categories(meeting.summary, meeting.summaryTemplate, meeting.summaryModel)

    /** Every notice any of [meetings]' summaries needs, for multi-meeting outputs. */
    fun union(meetings: Iterable<Meeting>): Set<Category> {
        val found = HashSet<Category>()
        for (meeting in meetings) found.addAll(categories(meeting))
        return Category.values().filter { it in found }.toCollection(LinkedHashSet())
    }

    /**
     * [text] with each of [notices] appended as a closing paragraph, except
     * a notice the text already carries word for word (whitespace and case
     * aside): an output assembled from parts that were each given the notice
     * must not print it twice. A paraphrase the model wrote itself cannot be
     * told apart reliably, so it stays, and the fixed notice is added anyway.
     */
    fun appendTo(text: String, notices: List<String>): String {
        val missing = notices.filter { it.isNotBlank() && !carries(text, it) }.distinct()
        if (missing.isEmpty()) return text
        return buildString {
            append(text.trimEnd())
            for (notice in missing) {
                if (isNotEmpty()) append("\n\n")
                append(notice)
            }
        }
    }

    /**
     * [text] with the [notices] it does not already carry placed in front,
     * as an opening paragraph: for a single summary, where the notice goes
     * first as it does on screen, and survives any truncation of the end.
     */
    fun prependTo(text: String, notices: List<String>): String {
        val missing = notices.filter { it.isNotBlank() && !carries(text, it) }.distinct()
        if (missing.isEmpty()) return text
        val head = missing.joinToString("\n\n")
        return if (text.isBlank()) head else head + "\n\n" + text.trimStart()
    }

    /** True when [text] already contains [notice], ignoring whitespace runs and case. */
    fun carries(text: String, notice: String): Boolean =
        normalise(text).contains(normalise(notice))

    private fun normalise(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ").trim()

    fun text(context: Context, category: Category): String = context.getString(
        when (category) {
            Category.CLINICAL -> R.string.aisafety_disclaimer_clinical
            Category.LEGAL -> R.string.aisafety_disclaimer_legal
        }
    )

    fun texts(context: Context, categories: Collection<Category>): List<String> =
        categories.map { text(context, it) }
}

/**
 * How a generated text's engine and model are recorded: "local:<model key>",
 * "endpoint:<model name>", or [EXTRACTIVE] when no model wrote it. Stored as
 * [Meeting.summaryModel] and sent in AI output reports. Never carries the
 * endpoint address or key.
 */
object AiProvenance {

    const val EXTRACTIVE = "extractive"

    fun modelKey(
        useLlm: Boolean,
        onDevice: Boolean,
        localModel: String,
        endpointModel: String
    ): String = when {
        !useLlm -> EXTRACTIVE
        onDevice -> "local:" + localModel.ifBlank { "?" }
        else -> "endpoint:" + endpointModel.ifBlank { "?" }
    }
}
