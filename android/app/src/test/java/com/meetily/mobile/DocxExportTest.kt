package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.export.DocxExport
import com.meetily.mobile.export.ExportFacts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document

class DocxExportTest {

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    /** Every w:t of word/document.xml, in order. */
    private fun texts(document: String): List<String> {
        val nodes = parse(document).getElementsByTagNameNS(W_NS, "t")
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    private fun meeting(): Meeting = Meeting(
        id = "m1",
        title = "Q3 <Planning> & \"Budget\"",
        createdAtMs = 1_700_000_000_000L,
        segments = mutableListOf(
            TranscriptSegment(
                1_700_000_001_000L, "Let's start", speaker = "Ana", audioMs = 1_000L,
                words = listOf(WordStamp(0L, "Let's"), WordStamp(400L, "start"))
            ),
            TranscriptSegment(1_700_000_005_000L, "Agreed", highlighted = true, audioMs = 65_000L)
        ),
        notes = "- remember the deck\n**bold** note",
        summary = "# Decisions\n- Ship it\n1. First\n2. Second\n\nText between\n3. Third\n- [x] done thing",
        attendees = mutableListOf("Ana", "Ben"),
        actionItems = mutableListOf(ActionItem("Send notes", owner = "Ben"), ActionItem("Book room", done = true)),
        tags = mutableListOf("planning", "q3")
    )

    @Test
    fun parts_areTheMinimalPackageAndAllWellFormed() {
        val parts = DocxExport.parts(meeting(), emptyList(), "date")
        assertEquals(
            listOf(
                "[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels",
                "word/document.xml", "word/styles.xml", "word/numbering.xml", "docProps/core.xml"
            ),
            parts.keys.toList()
        )
        for ((name, xml) in parts) {
            assertTrue("$name declares UTF-8", xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\""))
            parse(xml)
        }
    }

    @Test
    fun contentTypesCoverEveryPart() {
        val parts = DocxExport.parts(meeting(), emptyList(), "date")
        val types = parts.getValue("[Content_Types].xml")
        for (name in parts.keys) {
            if (name.endsWith(".rels") || name == "[Content_Types].xml") continue
            assertTrue(name, types.contains("PartName=\"/$name\""))
        }
        val rels = parts.getValue("word/_rels/document.xml.rels")
        assertTrue(rels.contains("Target=\"styles.xml\""))
        assertTrue(rels.contains("Target=\"numbering.xml\""))
    }

    @Test
    fun stylesDefineEveryStyleTheDocumentUses() {
        val parts = DocxExport.parts(meeting(), listOf("Not a medical record."), "date")
        val styles = parse(parts.getValue("word/styles.xml")).getElementsByTagNameNS(W_NS, "style")
        val defined = (0 until styles.length).map {
            (styles.item(it) as org.w3c.dom.Element).getAttributeNS(W_NS, "styleId")
        }.toSet()
        for (required in listOf("Title", "Heading1", "Heading2", "ListBullet", "ListNumber")) {
            assertTrue(required, required in defined)
        }
        val used = Regex("<w:pStyle w:val=\"([A-Za-z0-9]+)\"/>")
            .findAll(parts.getValue("word/document.xml")).map { it.groupValues[1] }.toSet()
        assertTrue(used.isNotEmpty())
        for (style in used) assertTrue(style, style in defined)
    }

    @Test
    fun document_carriesEverySectionInOrder() {
        val parts = DocxExport.parts(meeting(), emptyList(), "22 Nov 2026")
        val all = texts(parts.getValue("word/document.xml")).joinToString("|")
        val order = listOf(
            "Q3 <Planning> & \"Budget\"", "Date: 22 Nov 2026", "Duration: 0:01:05",
            "Attendees: ", "Ana, Ben", "Tags: ", "planning, q3",
            "Summary", "Decisions", "Ship it", "First", "Action items", "Send notes",
            "Notes", "remember the deck", "Transcript", "0:00:01", "Ana", ": Let's start",
            "★ ", "0:01:05", ": Agreed"
        )
        var at = 0
        for (piece in order) {
            val found = all.indexOf(piece, at)
            assertTrue("'$piece' after position $at in $all", found >= 0)
            at = found + piece.length
        }
    }

    @Test
    fun noticesGoUnderSummaryOnceAndNotWhenAlreadyCarried() {
        val notice = "Draft for review. Not a medical record."
        val parts = DocxExport.parts(meeting(), listOf(notice, notice), "d")
        val document = parts.getValue("word/document.xml")
        val texts = texts(document)
        assertEquals(1, texts.count { it == notice })
        assertTrue(texts.indexOf(notice) > texts.indexOf("Summary"))
        assertTrue(texts.indexOf(notice) < texts.indexOf("Decisions"))

        val carried = meeting().apply { summary = "$notice\n\nPlan: rest." }
        val again = texts(DocxExport.parts(carried, listOf(notice), "d").getValue("word/document.xml"))
        assertEquals(1, again.count { it.contains(notice) })
        // No summary, no notice.
        val none = meeting().apply { summary = "" }
        assertFalse(DocxExport.parts(none, listOf(notice), "d").getValue("word/document.xml").contains(notice))
    }

    @Test
    fun escape_handlesMarkupCharacters() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; 'e'", DocxExport.escape("a & b <c> \"d\" 'e'"))
    }

    @Test
    fun stripInvalid_removesWhatXmlForbidsAndKeepsTheRest() {
        val input = "ok\u0000\u0001\u0008\u000B\u000C\u001F\u007F\u0085￾￿ tab\tnl\n" +
            "lone\uD800high lone\uDC00low emoji😀 é 中"
        val out = DocxExport.stripInvalid(input)
        assertEquals("ok tab\tnl\nlonehigh lonelow emoji😀 é 中", out)
        assertEquals("plain", DocxExport.stripInvalid("plain"))
    }

    @Test
    fun hostileTextStillGivesWellFormedParts() {
        val nasty = "</w:t></w:r><w:p>\u0001\uD800]]><!-- & \u0000"
        val m = meeting().apply {
            title = nasty
            summary = nasty
            notes = nasty
            attendees = mutableListOf(nasty)
            tags = mutableListOf(nasty)
            segments.add(TranscriptSegment(0L, nasty, speaker = nasty))
        }
        for ((_, xml) in DocxExport.parts(m, listOf(nasty), nasty)) parse(xml)
        val texts = texts(DocxExport.parts(m, emptyList(), "d").getValue("word/document.xml"))
        assertTrue(texts.contains("</w:t></w:r><w:p>]]><!-- & "))
    }

    @Test
    fun blocks_mapTheMarkdownSubset() {
        val blocks = DocxExport.blocks(
            "# Head\n## Sub\n- dash\n* star\n• dot\n+ plus\n  - nested\n1. one\n2) two\n" +
                "- [ ] open\n- [x] done\nplain **bold**\n\n#notaheading"
        )
        assertEquals(
            listOf(
                DocxExport.Block.Heading("Head", 1),
                DocxExport.Block.Heading("Sub", 2),
                DocxExport.Block.Bullet("dash", 0),
                DocxExport.Block.Bullet("star", 0),
                DocxExport.Block.Bullet("dot", 0),
                DocxExport.Block.Bullet("plus", 0),
                DocxExport.Block.Bullet("nested", 1),
                DocxExport.Block.Numbered("one", 1, 0),
                DocxExport.Block.Numbered("two", 2, 0),
                DocxExport.Block.Check("open", false),
                DocxExport.Block.Check("done", true),
                DocxExport.Block.Para("plain **bold**"),
                DocxExport.Block.Para("#notaheading")
            ),
            blocks
        )
    }

    @Test
    fun runs_keepBoldItalicAndCode() {
        assertEquals(
            listOf(
                DocxExport.Run("a "),
                DocxExport.Run("b", bold = true),
                DocxExport.Run(" "),
                DocxExport.Run("i", italic = true),
                DocxExport.Run(" "),
                DocxExport.Run("c", code = true)
            ),
            DocxExport.runs("a **b** *i* `c`")
        )
        val document = DocxExport.parts(meeting(), emptyList(), "d").getValue("word/document.xml")
        assertTrue(document.contains("<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">bold</w:t></w:r>"))
    }

    @Test
    fun numberedLists_eachRestartAtTheirOwnFirstNumber() {
        val parts = DocxExport.parts(meeting(), emptyList(), "d")
        val numbering = parts.getValue("word/numbering.xml")
        // Bullets share numId 1; the two numbered lists (split by a
        // paragraph) get 2 and 3, starting at 1 and at 3.
        assertTrue(numbering.contains("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>"))
        assertTrue(numbering.contains("<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/>"))
        assertTrue(numbering.contains("<w:num w:numId=\"3\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"3\"/>"))
        assertFalse(numbering.contains("w:numId=\"4\""))
        val document = parts.getValue("word/document.xml")
        assertEquals(2, Regex("<w:numId w:val=\"2\"/>").findAll(document).count())
        assertEquals(1, Regex("<w:numId w:val=\"3\"/>").findAll(document).count())
        // Every abstractNum comes before the first num, as the schema requires.
        assertTrue(numbering.lastIndexOf("<w:abstractNum ") < numbering.indexOf("<w:num "))
    }

    @Test
    fun coreProperties_titleKeywordsAndUtcCreation() {
        val core = DocxExport.parts(meeting(), emptyList(), "d").getValue("docProps/core.xml")
        assertTrue(core.contains("<dc:title>Q3 &lt;Planning&gt; &amp; &quot;Budget&quot;</dc:title>"))
        assertTrue(core.contains("<cp:keywords>planning, q3</cp:keywords>"))
        assertTrue(core.contains("<dcterms:created xsi:type=\"dcterms:W3CDTF\">2023-11-14T22:13:20Z</dcterms:created>"))
        assertFalse(core.contains("creator"))
    }

    @Test
    fun write_producesAZipWithEveryPart() {
        val out = ByteArrayOutputStream()
        DocxExport.write(meeting(), out, emptyList(), Locale.US, TimeZone.getTimeZone("UTC"))
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                parse(String(zip.readBytes(), Charsets.UTF_8))
            }
        }
        assertEquals("[Content_Types].xml", names.first())
        assertEquals(7, names.size)
    }

    @Test
    fun durationFromTheLastLinesEnd() {
        assertEquals(65_000L + 900L, ExportFacts.durationMs(meeting()))
        assertEquals(null, ExportFacts.durationMs(Meeting("e", "Empty", 1L)))
        assertEquals("0:01:05", ExportFacts.durationLabel(65_900L))
    }

    private companion object {
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    }
}
