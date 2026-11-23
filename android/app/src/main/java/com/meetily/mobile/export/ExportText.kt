package com.meetily.mobile.export

import java.util.Locale

/**
 * The words the exports write around a meeting's own content: section
 * headings, field labels and the photo line.
 *
 * The defaults are the English every export has always written, byte for
 * byte (ExportTextTest). The screens that export build one from string
 * resources instead (ExportTextResources, strings_exporttext.xml), so the
 * files follow the app's language once there is more than one; the
 * exporters themselves stay pure Kotlin and testable.
 *
 * Labels carry their own colon, so a language that spaces or replaces it
 * can. The two photo lines are templates with %1$d for the count; they
 * differ only because the Markdown and Word exports have always worded
 * them differently.
 */
data class ExportText(
    val summary: String = "Summary",
    val actionItems: String = "Action items",
    val notes: String = "Notes",
    val transcript: String = "Transcript",
    /** "Date:", before the meeting's date. */
    val date: String = "Date:",
    /** "Duration:", before the length (Word only). */
    val duration: String = "Duration:",
    val attendees: String = "Attendees:",
    /** "Tags:", before the meeting's tags (Word only). */
    val tags: String = "Tags:",
    /** The Word document's title when the meeting has none. */
    val untitled: String = "Meeting",
    /** The Markdown export's last line; %1$d is how many photos. */
    val photosMarkdown: String = "%1\$d photo attachment(s) stored in the Meetily app.",
    /** The Word export's last line; %1$d is how many photos. */
    val photosWord: String = "%1\$d photo attachment(s) stored in the Recap app.",
    /** A task's description in the calendar file, before the meeting's title. */
    val fromMeeting: String = "From meeting:"
) {
    /**
     * [template] with [count] in it. ASCII digits whatever the phone's
     * locale, as the exports have always written the count.
     */
    fun count(template: String, count: Int): String =
        String.format(Locale.ROOT, template, count)
}
