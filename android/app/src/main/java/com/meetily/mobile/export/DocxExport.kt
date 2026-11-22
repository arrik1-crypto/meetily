package com.meetily.mobile.export

import com.meetily.mobile.data.ElapsedTime
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.notes.NotesMarkdown
import com.meetily.mobile.summarize.AiDisclaimers
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A meeting as a Word document (.docx): a minimal Office Open XML package
 * written with java.util.zip, nothing else.
 *
 * Seven parts: [Content_Types].xml, the package and document relationships,
 * word/document.xml, word/styles.xml (Title, Heading1 to Heading3, list,
 * notice and metadata styles), word/numbering.xml (bullets and numbered
 * lists) and docProps/core.xml (title, keywords, language, creation time;
 * no author). The body holds what the Markdown and PDF exports hold, in
 * the same order: title, date and duration, attendees and tags, the
 * summary under its fixed notices (AiDisclaimers), action items, notes, and
 * the transcript with each line's time and speaker.
 *
 * The summary and notes are Markdown as the app writes them (NotesMarkdown):
 * headings become Word headings one level below the section's, "- ", "* ",
 * "+ " and "• " lines become a bulleted list, "1." or "1)" lines a numbered
 * list that keeps its first number, "- [ ]" and "- [x]" lines checkboxes,
 * and **bold**, *italic* and `code` runs keep their style.
 *
 * Everything written is XML-escaped, with characters XML 1.0 does not allow
 * (most control characters, unpaired surrogates, U+FFFE and U+FFFF)
 * removed, so no meeting text can break the file. Pure Kotlin; the parts
 * are unit-tested.
 */
object DocxExport {

    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** The fixed (built-in) numbering definition every bulleted list uses. */
    private const val BULLET_NUM_ID = 1

    /** Writes the .docx for [meeting] to [out]. [notices] are the summary's, as for Markdown. */
    fun write(
        meeting: Meeting,
        out: OutputStream,
        notices: List<String> = emptyList(),
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ) {
        val parts = parts(meeting, notices, dateText(meeting, locale, timeZone))
        // Closing the zip closes [out] too, once everything is written.
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            for ((name, xml) in parts) {
                val entry = ZipEntry(name)
                // Stable stamps, so the same meeting gives the same bytes.
                if (meeting.createdAtMs > 0L) entry.time = meeting.createdAtMs
                zip.putNextEntry(entry)
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** The date line, in the reader's locale, as the Markdown export writes it. */
    fun dateText(meeting: Meeting, locale: Locale, timeZone: TimeZone): String {
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        format.timeZone = timeZone
        return format.format(Date(meeting.createdAtMs))
    }

    /**
     * Every part of the package, by its path inside the zip, in writing
     * order ([Content_Types].xml first, as Office writes it).
     */
    fun parts(meeting: Meeting, notices: List<String>, dateText: String): LinkedHashMap<String, String> {
        val body = Body()
        writeBody(body, meeting, notices, dateText)
        val parts = LinkedHashMap<String, String>()
        parts["[Content_Types].xml"] = CONTENT_TYPES
        parts["_rels/.rels"] = PACKAGE_RELS
        parts["word/_rels/document.xml.rels"] = DOCUMENT_RELS
        parts["word/document.xml"] = body.document()
        parts["word/styles.xml"] = STYLES
        parts["word/numbering.xml"] = body.numbering()
        parts["docProps/core.xml"] = coreProperties(meeting)
        return parts
    }

    // --- Text safety ------------------------------------------------------

    /**
     * [text] without what XML 1.0 forbids: control characters other than
     * tab, line feed and carriage return; unpaired surrogates; U+FFFE and
     * U+FFFF. C1 controls (U+007F to U+009F) go too: XML allows them but
     * discourages them, and they carry nothing in a transcript.
     */
    fun stripInvalid(text: String): String {
        var clean = true
        for (ch in text) {
            if (!allowedChar(ch)) {
                clean = false
                break
            }
        }
        if (clean && !hasUnpairedSurrogate(text)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch) && i + 1 < text.length &&
                Character.isLowSurrogate(text[i + 1])
            ) {
                out.append(ch).append(text[i + 1])
                i += 2
                continue
            }
            if (!Character.isSurrogate(ch) && allowedChar(ch)) out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun allowedChar(ch: Char): Boolean {
        val c = ch.code
        return c == 0x9 || c == 0xA || c == 0xD ||
            (c in 0x20..0x7E) || (c in 0xA0..0xFFFD)
    }

    private fun hasUnpairedSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(ch)) return true
            i++
        }
        return false
    }

    /** [text] made safe for XML character data and attribute values. */
    fun escape(text: String): String {
        val safe = stripInvalid(text)
        val out = StringBuilder(safe.length + 16)
        for (ch in safe) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    // --- Body -------------------------------------------------------------

    /** One styled stretch of a paragraph's text. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false
    )

    /** A paragraph of the summary or notes, as mapped from their Markdown. */
    sealed class Block {
        /** [level] 1 is the section's own sub-heading. */
        data class Heading(val text: String, val level: Int) : Block()
        data class Bullet(val text: String, val depth: Int) : Block()
        data class Numbered(val text: String, val number: Int, val depth: Int) : Block()
        data class Check(val text: String, val done: Boolean) : Block()
        data class Para(val text: String) : Block()
    }

    private val CHECK = Regex("^[-*+]\\s+\\[([ xX])]\\s?(.*)$")
    private val BULLET = Regex("^[-*+•]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\d{1,4})[.)]\\s+(.*)$")
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")

    /**
     * The app's Markdown subset, line by line. Blank lines only separate;
     * every other line is its own paragraph, as the app shows it.
     */
    fun blocks(markdown: String): List<Block> {
        val out = ArrayList<Block>()
        for (raw in markdown.lines()) {
            if (raw.isBlank()) continue
            val lead = raw.takeWhile { it == ' ' || it == '\t' }
            // A tab counts as four spaces.
            val indent = lead.length + 3 * lead.count { it == '\t' }
            val depth = if (indent >= 2) 1 else 0
            val line = raw.trim()
            val check = CHECK.matchEntire(line)
            val heading = HEADING.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            when {
                check != null -> out.add(
                    Block.Check(check.groupValues[2], check.groupValues[1].isNotBlank())
                )
                heading != null && heading.groupValues[2].isNotBlank() -> out.add(
                    Block.Heading(heading.groupValues[2], heading.groupValues[1].length)
                )
                numbered != null -> out.add(
                    Block.Numbered(
                        numbered.groupValues[2],
                        numbered.groupValues[1].toIntOrNull() ?: 1,
                        depth
                    )
                )
                bullet != null -> out.add(Block.Bullet(bullet.groupValues[1], depth))
                else -> out.add(Block.Para(line))
            }
        }
        return out
    }

    /** Inline **bold**, *italic* and `code`, as the app renders them. */
    fun runs(text: String): List<Run> =
        NotesMarkdown.inlineSegments(text).map { Run(it.text, it.bold, it.italic, it.code) }

    /** Collects paragraphs and the numbered lists they start. */
    private class Body {
        val xml = StringBuilder()

        /** First numbers of the numbered lists, one per list, in numId order from 2. */
        val numberedStarts = ArrayList<Int>()

        fun paragraph(
            style: String?,
            runs: List<Run>,
            numId: Int? = null,
            depth: Int = 0
        ) {
            xml.append("<w:p>")
            if (style != null || numId != null) {
                xml.append("<w:pPr>")
                if (style != null) xml.append("<w:pStyle w:val=\"").append(style).append("\"/>")
                if (numId != null) {
                    xml.append("<w:numPr><w:ilvl w:val=\"").append(depth)
                        .append("\"/><w:numId w:val=\"").append(numId).append("\"/></w:numPr>")
                }
                xml.append("</w:pPr>")
            }
            for (run in runs) appendRun(run)
            xml.append("</w:p>")
        }

        fun text(style: String?, text: String) = paragraph(style, listOf(Run(text)))

        /** A new numbered list starting at [first]; its numId. */
        fun newNumberedList(first: Int): Int {
            numberedStarts.add(first)
            return BULLET_NUM_ID + numberedStarts.size
        }

        private fun appendRun(run: Run) {
            val clean = stripInvalid(run.text)
            if (clean.isEmpty()) return
            xml.append("<w:r>")
            if (run.bold || run.italic || run.code) {
                // Children in the schema's order: rFonts, b, i.
                xml.append("<w:rPr>")
                if (run.code) {
                    xml.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
                }
                if (run.bold) xml.append("<w:b/>")
                if (run.italic) xml.append("<w:i/>")
                xml.append("</w:rPr>")
            }
            // Line breaks and tabs inside a run are elements, not characters.
            val pieces = clean.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            for ((p, piece) in pieces.withIndex()) {
                if (p > 0) xml.append("<w:br/>")
                val tabs = piece.split('\t')
                for ((t, part) in tabs.withIndex()) {
                    if (t > 0) xml.append("<w:tab/>")
                    if (part.isNotEmpty()) {
                        xml.append("<w:t xml:space=\"preserve\">").append(escape(part)).append("</w:t>")
                    }
                }
            }
            xml.append("</w:r>")
        }

        fun document(): String = buildString {
            append(XML_HEAD)
            append("<w:document xmlns:w=\"").append(W_NS).append("\"><w:body>")
            append(xml)
            // A4 with 2.5 cm margins, as the PDF export lays out its pages.
            append(
                "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
                    "<w:pgMar w:top=\"1418\" w:right=\"1418\" w:bottom=\"1418\" w:left=\"1418\" " +
                    "w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr>"
            )
            append("</w:body></w:document>")
        }

        fun numbering(): String = buildString {
            append(XML_HEAD)
            append("<w:numbering xmlns:w=\"").append(W_NS).append("\">")
            append(abstractNum(0, "bullet", listOf("•", "–")))
            append(abstractNum(1, "decimal", listOf("%1.", "%2.")))
            append("<w:num w:numId=\"").append(BULLET_NUM_ID)
                .append("\"><w:abstractNumId w:val=\"0\"/></w:num>")
            // One instance per numbered list, so each starts again at its own first number.
            for ((index, first) in numberedStarts.withIndex()) {
                append("<w:num w:numId=\"").append(BULLET_NUM_ID + 1 + index).append("\">")
                append("<w:abstractNumId w:val=\"1\"/>")
                append("<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"")
                    .append(first.coerceAtLeast(0)).append("\"/></w:lvlOverride>")
                append("</w:num>")
            }
            append("</w:numbering>")
        }

        private fun abstractNum(id: Int, format: String, texts: List<String>): String = buildString {
            append("<w:abstractNum w:abstractNumId=\"").append(id).append("\">")
            append("<w:multiLevelType w:val=\"hybridMultilevel\"/>")
            for ((level, text) in texts.withIndex()) {
                val left = 720 * (level + 1)
                append("<w:lvl w:ilvl=\"").append(level).append("\">")
                append("<w:start w:val=\"1\"/>")
                append("<w:numFmt w:val=\"").append(format).append("\"/>")
                append("<w:lvlText w:val=\"").append(escape(text)).append("\"/>")
                append("<w:lvlJc w:val=\"left\"/>")
                append("<w:pPr><w:ind w:left=\"").append(left).append("\" w:hanging=\"360\"/></w:pPr>")
                append("</w:lvl>")
            }
            append("</w:abstractNum>")
        }
    }

    private fun writeBody(body: Body, meeting: Meeting, notices: List<String>, dateText: String) {
        body.text("Title", meeting.title.ifBlank { "Meeting" })
        val meta = ArrayList<String>()
        meta.add("Date: $dateText")
        ExportFacts.durationMs(meeting)?.let { meta.add("Duration: " + ExportFacts.durationLabel(it)) }
        for (line in meta) body.text("Meta", line)
        if (meeting.attendees.isNotEmpty()) {
            body.paragraph("Meta", listOf(Run("Attendees: ", bold = true), Run(meeting.attendeesText())))
        }
        if (meeting.tags.isNotEmpty()) {
            body.paragraph("Meta", listOf(Run("Tags: ", bold = true), Run(meeting.tags.joinToString(", "))))
        }

        if (meeting.summary.isNotBlank()) {
            body.text("Heading1", "Summary")
            // The fixed notices go first, as on screen and in every other
            // export, except one the summary already carries word for word.
            val missing = notices.filter {
                it.isNotBlank() && !AiDisclaimers.carries(meeting.summary, it)
            }.distinct()
            for (notice in missing) body.text("Notice", notice)
            markdown(body, meeting.summary)
        }
        if (meeting.actionItems.isNotEmpty()) {
            body.text("Heading1", "Action items")
            for (item in meeting.actionItems) {
                val runs = ArrayList<Run>()
                runs.add(Run(if (item.done) "☑ " else "☐ "))
                runs.add(Run(item.task))
                if (!item.owner.isNullOrBlank()) runs.add(Run(" — " + item.owner, italic = true))
                body.paragraph("ListCheck", runs)
            }
        }
        if (meeting.notes.isNotBlank()) {
            body.text("Heading1", "Notes")
            markdown(body, meeting.notes)
        }
        if (meeting.segments.isNotEmpty()) {
            body.text("Heading1", "Transcript")
            for (segment in meeting.segments) {
                val runs = ArrayList<Run>()
                if (segment.highlighted) runs.add(Run("★ "))
                runs.add(Run(ElapsedTime.label(segment, meeting.createdAtMs), bold = true))
                val speaker = segment.speaker
                if (!speaker.isNullOrBlank()) {
                    runs.add(Run("  "))
                    runs.add(Run(speaker, bold = true, italic = true))
                }
                runs.add(Run(": " + segment.text))
                body.paragraph("Transcript", runs)
            }
        }
        if (meeting.photos.isNotEmpty()) {
            body.paragraph(
                "Meta",
                listOf(Run("${meeting.photos.size} photo attachment(s) stored in the Recap app.", italic = true))
            )
        }
    }

    /** The summary or notes, mapped through [blocks] into the styles below. */
    private fun markdown(body: Body, source: String) {
        // The numbered list being written, until something other than one of
        // its items or a bullet nested under them ends it.
        var numbered: Int? = null
        for (block in blocks(source)) {
            val keepsList = block is Block.Numbered || (block is Block.Bullet && block.depth > 0)
            if (!keepsList) numbered = null
            when (block) {
                is Block.Heading -> body.paragraph(
                    if (block.level <= 1) "Heading2" else "Heading3", runs(block.text)
                )
                is Block.Bullet -> body.paragraph("ListBullet", runs(block.text), BULLET_NUM_ID, block.depth)
                is Block.Numbered -> {
                    val list = numbered
                        ?: body.newNumberedList(if (block.depth == 0) block.number else 1)
                            .also { numbered = it }
                    body.paragraph("ListNumber", runs(block.text), list, block.depth)
                }
                is Block.Check -> body.paragraph(
                    "ListCheck",
                    listOf(Run(if (block.done) "☑ " else "☐ ")) + runs(block.text)
                )
                is Block.Para -> body.paragraph(null, runs(block.text))
            }
        }
    }

    private fun coreProperties(meeting: Meeting): String = buildString {
        append(XML_HEAD)
        append(
            "<cp:coreProperties " +
                "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
        )
        append("<dc:title>").append(escape(meeting.title)).append("</dc:title>")
        if (meeting.tags.isNotEmpty()) {
            append("<cp:keywords>").append(escape(meeting.tags.joinToString(", "))).append("</cp:keywords>")
        }
        meeting.transcriptLanguage()?.let {
            append("<dc:language>").append(escape(it)).append("</dc:language>")
        }
        if (meeting.createdAtMs > 0L) {
            val created = ExportFacts.isoUtc(meeting.createdAtMs)
            append("<dcterms:created xsi:type=\"dcterms:W3CDTF\">").append(created).append("</dcterms:created>")
            append("<dcterms:modified xsi:type=\"dcterms:W3CDTF\">").append(created).append("</dcterms:modified>")
        }
        append("</cp:coreProperties>")
    }

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val CONTENT_TYPES = XML_HEAD +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" " +
        "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" " +
        "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/numbering.xml\" " +
        "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" " +
        "ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "</Types>"

    private const val PACKAGE_RELS = XML_HEAD +
        "<Relationships xmlns=\"" + REL_NS + "\">" +
        "<Relationship Id=\"rId1\" Type=\"" + OFFICE_REL + "/officeDocument\" Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" " +
        "Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" " +
        "Target=\"docProps/core.xml\"/>" +
        "</Relationships>"

    private const val DOCUMENT_RELS = XML_HEAD +
        "<Relationships xmlns=\"" + REL_NS + "\">" +
        "<Relationship Id=\"rId1\" Type=\"" + OFFICE_REL + "/styles\" Target=\"styles.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"" + OFFICE_REL + "/numbering\" Target=\"numbering.xml\"/>" +
        "</Relationships>"

    /**
     * Paragraph styles by id. Children of each pPr and rPr are in the
     * schema's sequence order, which Word enforces and other readers do not.
     */
    private const val STYLES = XML_HEAD +
        "<w:styles xmlns:w=\"" + W_NS + "\">" +
        "<w:docDefaults>" +
        "<w:rPrDefault><w:rPr>" +
        "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
        "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/>" +
        "</w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>" +
        "</w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/>" +
        "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:after=\"80\"/></w:pPr>" +
        "<w:rPr><w:b/><w:sz w:val=\"44\"/><w:szCs w:val=\"44\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/>" +
        "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr>" +
        "<w:rPr><w:b/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/>" +
        "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr>" +
        "<w:rPr><w:b/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/>" +
        "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"60\"/><w:outlineLvl w:val=\"2\"/></w:pPr>" +
        "<w:rPr><w:b/><w:sz w:val=\"23\"/><w:szCs w:val=\"23\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"ListBullet\"><w:name w:val=\"List Bullet\"/>" +
        "<w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\" w:hanging=\"360\"/><w:contextualSpacing/></w:pPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"ListNumber\"><w:name w:val=\"List Number\"/>" +
        "<w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\" w:hanging=\"360\"/><w:contextualSpacing/></w:pPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"ListCheck\"><w:name w:val=\"List Check\"/>" +
        "<w:basedOn w:val=\"Normal\"/>" +
        "<w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"360\"/></w:pPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Meta\"><w:name w:val=\"Meeting Details\"/>" +
        "<w:basedOn w:val=\"Normal\"/>" +
        "<w:pPr><w:spacing w:after=\"40\"/></w:pPr>" +
        "<w:rPr><w:color w:val=\"595959\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Notice\"><w:name w:val=\"AI Notice\"/>" +
        "<w:basedOn w:val=\"Normal\"/>" +
        "<w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"6\" w:color=\"A6A6A6\"/></w:pBdr>" +
        "<w:spacing w:after=\"160\"/><w:ind w:left=\"144\"/></w:pPr>" +
        "<w:rPr><w:b/><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Transcript\"><w:name w:val=\"Transcript Line\"/>" +
        "<w:basedOn w:val=\"Normal\"/>" +
        "<w:pPr><w:spacing w:after=\"80\"/></w:pPr></w:style>" +
        "</w:styles>"
}
