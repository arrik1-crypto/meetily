package com.meetily.mobile.export

import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.meetily.mobile.data.Meeting
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date

/** Renders a meeting as Markdown text or a paginated text PDF. */
object MeetingExporter {

    fun suggestedFileName(meeting: Meeting, extension: String): String {
        val safeTitle = meeting.title
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .take(48)
            .ifBlank { "meeting" }
        return "$safeTitle.$extension"
    }

    fun markdown(meeting: Meeting): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(meeting.createdAtMs))
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        return buildString {
            append("# ").append(meeting.title).append("\n\n")
            append("**Date:** ").append(date).append("\n\n")
            if (meeting.attendees.isNotEmpty()) {
                append("**Attendees:** ").append(meeting.attendeesText()).append("\n\n")
            }
            if (meeting.summary.isNotBlank()) {
                append("## Summary\n\n").append(meeting.summary).append("\n\n")
            }
            if (meeting.actionItems.isNotEmpty()) {
                append("## Action items\n\n")
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
                append("## Notes\n\n").append(meeting.notes).append("\n\n")
            }
            if (meeting.segments.isNotEmpty()) {
                append("## Transcript\n\n")
                for (seg in meeting.segments) {
                    append("- ")
                    if (seg.highlighted) append("★ ")
                    append("**").append(timeFormat.format(Date(seg.timestampMs))).append("**")
                    if (!seg.speaker.isNullOrBlank()) {
                        append(" *").append(seg.speaker).append("*")
                    }
                    append(": ").append(seg.text).append('\n')
                }
            }
            if (meeting.photos.isNotEmpty()) {
                append("\n_")
                append(meeting.photos.size)
                append(" photo attachment(s) stored in the Meetily app._\n")
            }
        }
    }

    fun writePdf(meeting: Meeting, out: OutputStream) {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(meeting.createdAtMs))
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
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
                writer.block("Summary", 13f, bold = true, spacingAfter = 4f)
                writer.block(meeting.summary, 10.5f, bold = false, spacingAfter = 14f)
            }
            if (meeting.actionItems.isNotEmpty()) {
                writer.block("Action items", 13f, bold = true, spacingAfter = 4f)
                val actions = meeting.actionItems.joinToString("\n") { item ->
                    val box = if (item.done) "[x]" else "[ ]"
                    val owner = if (item.owner.isNullOrBlank()) "" else " — ${item.owner}"
                    "$box ${item.task}$owner"
                }
                writer.block(actions, 10.5f, bold = false, spacingAfter = 14f)
            }
            if (meeting.notes.isNotBlank()) {
                writer.block("Notes", 13f, bold = true, spacingAfter = 4f)
                writer.block(meeting.notes, 10.5f, bold = false, spacingAfter = 14f)
            }
            if (meeting.segments.isNotEmpty()) {
                writer.block("Transcript", 13f, bold = true, spacingAfter = 4f)
                val transcript = meeting.segments.joinToString("\n") { seg ->
                    buildString {
                        if (seg.highlighted) append("★ ")
                        append(timeFormat.format(Date(seg.timestampMs)))
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
