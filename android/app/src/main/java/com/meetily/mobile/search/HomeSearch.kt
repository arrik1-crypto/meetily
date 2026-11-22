package com.meetily.mobile.search

import com.meetily.mobile.data.Meeting

/**
 * The home screen's search box: what a meeting is matched against, and how
 * a typed query is compared with it. Pure — unit-tested.
 *
 * Each meeting gets one string, lowercased once, built on the library-load
 * worker and kept for the life of that load. Deliberately one string rather
 * than a per-field scan: the fields are only ever tested with contains(), so
 * joining them costs one pass and saves an allocation per field per meeting
 * per keystroke.
 *
 * It covers what people remember a meeting by: title, notes, summary,
 * attendees, tags, the transcript with its speaker labels, the text read
 * from attached photos (whiteboards, slides), action items and their owners,
 * and the names of attached files. Every one of those is in the home list's
 * light listing (MeetingStore.listLight leaves out only word timings), so
 * none of this needs a full parse of the library.
 */
object HomeSearch {

    /** Everything [meeting] is matched against, lowercased. */
    fun blobFor(meeting: Meeting): String {
        val out = StringBuilder()
        line(out, meeting.title)
        line(out, meeting.notes)
        line(out, meeting.summary)
        line(out, meeting.attendeesText())
        line(out, meeting.tags.joinToString(" "))
        for (item in meeting.actionItems) {
            line(out, item.task)
            item.owner?.let { line(out, it) }
        }
        // Blank entries only record that OCR ran on a photo and found nothing.
        for (text in meeting.photoTexts.values) line(out, text)
        for (attachment in meeting.attachmentsList) line(out, attachment.name)
        // Appended segment by segment rather than through
        // transcriptTextWithSpeakers(), which would build the whole
        // transcript as a second string only to copy it in here.
        for (segment in meeting.segments) {
            if (segment.text.isBlank()) continue
            val speaker = segment.speaker
            if (!speaker.isNullOrBlank()) out.append(speaker).append(": ")
            out.append(segment.text).append('\n')
        }
        return out.toString().lowercase()
    }

    /** [raw] as typed in the search box, in the form [matches] compares. */
    fun normalizeQuery(raw: String): String = raw.trim().lowercase()

    /**
     * Whether a meeting whose [blobFor] is [blob] matches [query], already
     * passed through [normalizeQuery]. Fields sit on separate lines, so a
     * query never matches across the end of one and the start of the next.
     */
    fun matches(blob: String, query: String): Boolean = blob.contains(query)

    private fun line(out: StringBuilder, text: String) {
        if (text.isBlank()) return
        out.append(text).append('\n')
    }
}
