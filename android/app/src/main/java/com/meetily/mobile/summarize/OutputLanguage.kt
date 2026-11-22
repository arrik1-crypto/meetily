package com.meetily.mobile.summarize

import com.meetily.mobile.data.MeetingLanguage

/**
 * Which language AI output about a meeting is written in, and the sentence
 * that asks for it. Pure — unit-tested.
 *
 * No prompt used to name a language, so small models answered a German
 * meeting in English. The clause is added only where it changes something:
 * for an English (or unknown-language) meeting with no other choice made,
 * every prompt stays byte for byte what it was.
 */
object OutputLanguage {

    /** "Summary language" setting value: follow the meeting. */
    const val SAME = "same"

    /** The kind of output, which decides the wording of the clause. */
    enum class Kind { SUMMARY, ANSWER, NOTES, TITLE }

    /**
     * The language to write in, or null to leave it to the model.
     *
     * An explicit [setting] wins. Otherwise a [translated] transcript is
     * English text, and its summary stays English as it always has; failing
     * that, the meeting's spoken language when it is known.
     */
    fun target(setting: String?, meetingLanguage: String?, translated: Boolean): String? {
        if (setting != null && setting != SAME) {
            MeetingLanguage.normalize(setting)?.let { return it }
        }
        return MeetingLanguage.transcriptLanguage(meetingLanguage, translated)
    }

    /**
     * The clause for [kind], with a leading space so it can be appended to
     * a sentence; "" when there is nothing to ask for.
     *
     * Nothing is asked for when [target] is unknown, or when it is English
     * and the transcript is English or of unknown language: models answer
     * those in English already, and leaving the prompt alone keeps them
     * exactly as they were. A language with no English name is skipped too:
     * a bare code in a sentence is more likely to confuse a small model
     * than to steer it.
     */
    fun clause(kind: Kind, target: String?, transcriptLanguage: String?): String {
        val code = MeetingLanguage.normalize(target) ?: return ""
        val source = MeetingLanguage.normalize(transcriptLanguage)
        if (code == "en" && (source == null || source == "en")) return ""
        val name = MeetingLanguage.englishName(code) ?: return ""
        return when (kind) {
            // The JSON tail (ActionItems.LLM_INSTRUCTIONS) is parsed by its
            // marker and keys, so those must survive a model that takes
            // "write everything in German" too literally; the task text
            // inside it should not stay English.
            Kind.SUMMARY -> " Write the summary in $name, headings and action items " +
                "included, even though these instructions are in English. Keep names " +
                "and quoted terms as spoken. Keep the final line's marker and its " +
                "JSON keys exactly as specified."
            Kind.ANSWER -> " Answer in $name."
            Kind.NOTES -> " Write the notes in $name."
            Kind.TITLE -> " Write the title in $name."
        }
    }

    /** [clause] for a meeting, from the "Summary language" [setting]. */
    fun clauseFor(
        kind: Kind,
        setting: String?,
        meetingLanguage: String?,
        translated: Boolean
    ): String = clause(
        kind,
        target(setting, meetingLanguage, translated),
        MeetingLanguage.transcriptLanguage(meetingLanguage, translated)
    )
}
