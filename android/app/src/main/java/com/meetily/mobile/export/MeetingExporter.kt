package com.meetily.mobile.export

import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.meetily.mobile.data.ElapsedTime
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.summarize.AiDisclaimers
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date

/** Renders a meeting as Markdown text or a paginated text PDF. */
object MeetingExporter {

    /**
     * The title as a file name: letters (with their accents and vowel
     * signs) and digits of any script, spaces turned to dashes, at most 48
     * UTF-16 units without splitting a character; "meeting" when nothing is
     * left. Punctuation, symbols and emoji go.
     */
    fun suggestedFileName(meeting: Meeting, extension: String): String {
        val safeTitle = meeting.title
            .replace(Regex("[^\\p{L}\\p{M}\\p{N} _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .let { name ->
                if (name.length <= 48) {
                    name
                } else {
                    name.take(if (Character.isHighSurrogate(name[47])) 47 else 48)
                }
            }
            .ifBlank { "meeting" }
        return "$safeTitle.$extension"
    }

    /**
     * [notices] are the summary's fixed disclaimers (AiDisclaimers), written
     * directly under the Summary heading, ahead of the summary itself. One
     * the summary already carries word for word is not repeated. [text] is
     * the headings and labels (ExportText: English unless the caller loads
     * them from resources).
     */
    fun markdown(
        meeting: Meeting,
        notices: List<String> = emptyList(),
        text: ExportText = ExportText()
    ): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(meeting.createdAtMs))
        return buildString {
            append("# ").append(meeting.title).append("\n\n")
            append("**").append(text.date).append("** ").append(date).append("\n\n")
            if (meeting.attendees.isNotEmpty()) {
                append("**").append(text.attendees).append("** ")
                    .append(meeting.attendeesText()).append("\n\n")
            }
            if (meeting.summary.isNotBlank()) {
                append("## ").append(text.summary).append("\n\n")
                for (notice in newNotices(meeting.summary, notices)) {
                    append("> ").append(notice).append("\n\n")
                }
                append(meeting.summary).append("\n\n")
            }
            if (meeting.actionItems.isNotEmpty()) {
                append("## ").append(text.actionItems).append("\n\n")
                for (item in meeting.actionItems) {
                    append(if (item.done) "- [x] " else "- [ ] ")
                    append(item.task)
                    if (!item.owner.isNullOrBlank()) {
                        append(" — ").append(item.owner)
                    }
                    append('\n')
                }
                append('\n')
            }
            if (meeting.notes.isNotBlank()) {
                append("## ").append(text.notes).append("\n\n").append(meeting.notes).append("\n\n")
            }
            if (meeting.segments.isNotEmpty()) {
                append("## ").append(text.transcript).append("\n\n")
                for (seg in meeting.segments) {
                    append("- ")
                    if (seg.highlighted) append("★ ")
                    append("**").append(ElapsedTime.label(seg, meeting.createdAtMs)).append("**")
                    if (!seg.speaker.isNullOrBlank()) {
                        append(" *").append(seg.speaker).append("*")
                    }
                    append(": ").append(seg.text).append('\n')
                }
            }
            if (meeting.photos.isNotEmpty()) {
                append("\n_")
                append(text.count(text.photosMarkdown, meeting.photos.size))
                append("_\n")
            }
        }
    }

    /**
     * The meeting as the plain text Share sends: title, date, then each part
     * the meeting has under a capitalised heading, the transcript last.
     * [notices] and [text] as in [markdown], [text]'s share headings here.
     */
    fun shareText(
        meeting: Meeting,
        notices: List<String> = emptyList(),
        text: ExportText = ExportText()
    ): String = buildString {
        append(meeting.title).append('\n')
        append(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(meeting.createdAtMs))
        ).append("\n\n")
        if (meeting.attendees.isNotEmpty()) {
            append(text.shareAttendees).append('\n').append(meeting.attendeesText()).append("\n\n")
        }
        if (meeting.summary.isNotBlank()) {
            append(text.shareSummary).append('\n')
            append(AiDisclaimers.prependTo(meeting.summary, notices)).append("\n\n")
        }
        if (meeting.notes.isNotBlank()) {
            append(text.shareNotes).append('\n').append(meeting.notes).append("\n\n")
        }
        append(text.shareTranscript).append('\n').append(meeting.transcriptTextWithSpeakers())
    }

    /** [notices] and [text] as in [markdown]. */
    fun writePdf(
        meeting: Meeting,
        out: OutputStream,
        notices: List<String> = emptyList(),
        text: ExportText = ExportText()
    ) {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(meeting.createdAtMs))
        val doc = PdfDocument()
        val writer = PdfWriter(doc)
        try {
            writer.block(meeting.title, 18f, bold = true, spacingAfter = 8f)
            val metaLine = buildString {
                append(date)
                if (meeting.attendees.isNotEmpty()) {
                    append("  ·  ").append(meeting.attendeesText())
                }
            }
            writer.block(metaLine, 10f, bold = false, spacingAfter = 16f)

            if (meeting.summary.isNotBlank()) {
                writer.block(text.summary, 13f, bold = true, spacingAfter = 4f)
                for (notice in newNotices(meeting.summary, notices)) {
                    writer.block(notice, 9.5f, bold = true, spacingAfter = 8f)
                }
                writer.block(meeting.summary, 10.5f, bold = false, spacingAfter = 14f)
            }
            if (meeting.actionItems.isNotEmpty()) {
                writer.block(text.actionItems, 13f, bold = true, spacingAfter = 4f)
                val actions = meeting.actionItems.joinToString("\n") { item ->
                    val box = if (item.done) "[x]" else "[ ]"
                    val owner = if (item.owner.isNullOrBlank()) "" else " — ${item.owner}"
                    "$box ${item.task}$owner"
                }
                writer.block(actions, 10.5f, bold = false, spacingAfter = 14f)
            }
            if (meeting.notes.isNotBlank()) {
                writer.block(text.notes, 13f, bold = true, spacingAfter = 4f)
                writer.block(meeting.notes, 10.5f, bold = false, spacingAfter = 14f)
            }
            if (meeting.segments.isNotEmpty()) {
                writer.block(text.transcript, 13f, bold = true, spacingAfter = 4f)
                val transcript = meeting.segments.joinToString("\n") { seg ->
                    buildString {
                        if (seg.highlighted) append("★ ")
                        append(ElapsedTime.label(seg, meeting.createdAtMs))
                        if (!seg.speaker.isNullOrBlank()) {
                            append("  ").append(seg.speaker)
                        }
                        append(" — ").append(seg.text)
                    }
                }
                writer.block(transcript, 10.5f, bold = false, spacingAfter = 0f)
            }
            writer.finishPage()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** The [notices] [summary] does not already carry, each once. */
    private fun newNotices(summary: String, notices: List<String>): List<String> =
        notices.filter { it.isNotBlank() && !AiDisclaimers.carries(summary, it) }.distinct()

    /** A4 pages (595x842 pt), simple top-down text flow with pagination. */
    private class PdfWriter(private val doc: PdfDocument) {
        private val pageWidth = 595
        private val pageHeight = 842
        private val margin = 48f
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var y = margin

        private fun ensurePage(): PdfDocument.Page {
            val current = page
            if (current != null) return current
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            val newPage = doc.startPage(info)
            page = newPage
            y = margin
            return newPage
        }

        fun finishPage() {
            page?.let { doc.finishPage(it) }
            page = null
        }

        private fun newPage() {
            finishPage()
            ensurePage()
        }

        fun block(text: String, sizePt: Float, bold: Boolean, spacingAfter: Float) {
            if (text.isBlank()) return
            val paint = TextPaint().apply {
                isAntiAlias = true
                textSize = sizePt
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            val width = (pageWidth - 2 * margin).toInt()
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            ensurePage()
            for (line in 0 until layout.lineCount) {
                val top = layout.getLineTop(line)
                val bottom = layout.getLineBottom(line)
                val height = (bottom - top).toFloat()
                if (y + height > pageHeight - margin) {
                    newPage()
                }
                val canvas = ensurePage().canvas
                canvas.save()
                canvas.translate(margin, y - top)
                canvas.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += height
            }
            y += spacingAfter
        }
    }
}
