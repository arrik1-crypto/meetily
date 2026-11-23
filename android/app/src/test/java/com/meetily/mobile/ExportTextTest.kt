package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.export.DocxExport
import com.meetily.mobile.export.ExportText
import com.meetily.mobile.export.LibraryExport
import com.meetily.mobile.export.MeetingExporter
import com.meetily.mobile.export.TaskExport
import java.io.File
import java.text.DateFormat
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * The words the exports write (ExportText): the English defaults reproduce
 * what the exports have always written, byte for byte, and the resources
 * the screens load them from say exactly the same.
 */
class ExportTextTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun source(path: String): File {
        val candidates = listOf("src/main/$path", "app/src/main/$path", "android/app/src/main/$path")
        return candidates.map(::File).firstOrNull { it.isFile }
            ?: run {
                fail("$path not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }
    }

    /** strings_exporttext.xml as ExportTextResources reads it. */
    private fun fromResources(): ExportText {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("res/values/strings_exporttext.xml"))
        val nodes = doc.getElementsByTagName("string")
        val s = (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals(16, s.size)
        return ExportText(
            summary = s.getValue("exporttext_summary"),
            actionItems = s.getValue("exporttext_action_items"),
            notes = s.getValue("exporttext_notes"),
            transcript = s.getValue("exporttext_transcript"),
            date = s.getValue("exporttext_date"),
            duration = s.getValue("exporttext_duration"),
            attendees = s.getValue("exporttext_attendees"),
            tags = s.getValue("exporttext_tags"),
            untitled = s.getValue("exporttext_untitled"),
            photosMarkdown = s.getValue("exporttext_photos_markdown"),
            photosWord = s.getValue("exporttext_photos_word"),
            fromMeeting = s.getValue("exporttext_from_meeting"),
            shareAttendees = s.getValue("exporttext_share_attendees"),
            shareSummary = s.getValue("exporttext_share_summary"),
            shareNotes = s.getValue("exporttext_share_notes"),
            shareTranscript = s.getValue("exporttext_share_transcript")
        )
    }

    private fun meeting(title: String = "Team Sync"): Meeting = Meeting(
        id = "m1",
        title = title,
        createdAtMs = 1_700_000_000_000L,
        segments = mutableListOf(
            TranscriptSegment(1_700_000_001_000L, "hello", speaker = "Al", audioMs = 1_000L),
            TranscriptSegment(1_700_000_002_000L, "noted", highlighted = true, audioMs = 2_000L)
        ),
        notes = "some notes",
        summary = "the summary",
        attendees = mutableListOf("Al", "Bo"),
        actionItems = mutableListOf(ActionItem("Do thing", owner = "Al", done = true), ActionItem("Next")),
        photos = mutableListOf("a.jpg", "b.jpg"),
        tags = mutableListOf("weekly", "team")
    )

    private fun docxTexts(meeting: Meeting, text: ExportText = ExportText()): List<String> {
        val xml = DocxExport.parts(meeting, listOf("A notice"), "DATE", text).getValue("word/document.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagNameNS(wNs, "t")
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    @Test
    fun resourcesSayExactlyTheDefaults() {
        assertEquals(ExportText(), fromResources())
    }

    @Test
    fun markdownIsByteIdenticalInEnglish() {
        val m = meeting()
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(m.createdAtMs))
        val expected = "# Team Sync\n\n" +
            "**Date:** $date\n\n" +
            "**Attendees:** Al, Bo\n\n" +
            "## Summary\n\n" +
            "> A notice\n\n" +
            "the summary\n\n" +
            "## Action items\n\n" +
            "- [x] Do thing — Al\n" +
            "- [ ] Next\n" +
            "\n" +
            "## Notes\n\nsome notes\n\n" +
            "## Transcript\n\n" +
            "- **0:00:01** *Al*: hello\n" +
            "- ★ **0:00:02**: noted\n" +
            "\n_2 photo attachment(s) stored in the Meetily app._\n"
        assertEquals(expected, MeetingExporter.markdown(m, listOf("A notice")))
        assertEquals(expected, MeetingExporter.markdown(m, listOf("A notice"), fromResources()))
    }

    @Test
    fun libraryMarkdownIsByteIdenticalInEnglish() {
        val m = meeting()
        val doc = LibraryExport.document(m, emptyList(), ZoneOffset.UTC)
        assertEquals(doc, LibraryExport.document(m, emptyList(), ZoneOffset.UTC, fromResources()))
        assertTrue(doc.endsWith(MeetingExporter.markdown(m, emptyList())))
        assertTrue(doc.startsWith("---\ntitle: \"Team Sync\"\n"))
    }

    @Test
    fun wordIsByteIdenticalInEnglish() {
        val m = meeting()
        assertEquals(
            listOf(
                "Team Sync", "Date: DATE", "Duration: 0:00:02",
                "Attendees: ", "Al, Bo", "Tags: ", "weekly, team",
                "Summary", "A notice", "the summary",
                "Action items", "☑ ", "Do thing", " — Al", "☐ ", "Next",
                "Notes", "some notes",
                "Transcript", "0:00:01", "  ", "Al", ": hello", "★ ", "0:00:02", ": noted",
                "2 photo attachment(s) stored in the Recap app."
            ),
            docxTexts(m)
        )
        // Every part, not only the body, is the same from the resources.
        assertEquals(
            DocxExport.parts(m, listOf("A notice"), "DATE"),
            DocxExport.parts(m, listOf("A notice"), "DATE", fromResources())
        )
        assertEquals("Meeting", docxTexts(meeting(title = "")).first())
    }

    /** What Share sent before the headings moved into ExportText. */
    @Test
    fun shareTextIsByteIdenticalInEnglish() {
        val m = meeting()
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(m.createdAtMs))
        val expected = "Team Sync\n" +
            date + "\n\n" +
            "ATTENDEES\nAl, Bo\n\n" +
            "SUMMARY\nA notice\n\nthe summary\n\n" +
            "NOTES\nsome notes\n\n" +
            "TRANSCRIPT\nAl: hello\nnoted"
        assertEquals(expected, MeetingExporter.shareText(m, listOf("A notice")))
        assertEquals(expected, MeetingExporter.shareText(m, listOf("A notice"), fromResources()))
        // A meeting with nothing but a transcript keeps only its heading.
        val bare = Meeting(
            id = "m2", title = "Solo", createdAtMs = 1_700_000_000_000L,
            segments = mutableListOf(TranscriptSegment(1_700_000_001_000L, "only line"))
        )
        val bareDate = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(bare.createdAtMs))
        assertEquals(
            "Solo\n$bareDate\n\nTRANSCRIPT\nonly line",
            MeetingExporter.shareText(bare, listOf("A notice"), fromResources())
        )
    }

    @Test
    fun calendarFileIsByteIdenticalInEnglish() {
        val items = listOf(TaskExport.Item("Send deck", "Ana", null, "Weekly sync"))
        val ics = TaskExport.ics(items, 1_700_000_000_000L)
        assertTrue(ics.contains("\r\nDESCRIPTION:From meeting: Weekly sync\r\n"))
        assertEquals(ics, TaskExport.ics(items, 1_700_000_000_000L, fromResources()))
    }

    @Test
    fun theWordsAreTakenFromTheTextNotTheCode() {
        val other = ExportText(
            summary = "Zusammenfassung", actionItems = "Aufgaben", notes = "Notizen",
            transcript = "Mitschrift", date = "Datum:", duration = "Dauer:",
            attendees = "Teilnehmende:", tags = "Schlagwörter:", untitled = "Besprechung",
            photosMarkdown = "%1\$d Fotos (Markdown)", photosWord = "%1\$d Fotos (Word)",
            fromMeeting = "Aus der Besprechung:", shareAttendees = "TEILNEHMENDE",
            shareSummary = "ZUSAMMENFASSUNG", shareNotes = "NOTIZEN", shareTranscript = "MITSCHRIFT"
        )
        val md = MeetingExporter.markdown(meeting(), emptyList(), other)
        for (word in listOf(
            "## Zusammenfassung", "## Aufgaben", "## Notizen", "## Mitschrift",
            "**Datum:** ", "**Teilnehmende:** ", "_2 Fotos (Markdown)_"
        )) {
            assertTrue(word, md.contains(word))
        }
        for (english in listOf("Summary", "Action items", "Notes", "Transcript", "Date:", "Attendees:", "photo")) {
            assertFalse(english, md.contains(english))
        }
        val word = docxTexts(meeting(), other)
        for (text in listOf(
            "Zusammenfassung", "Aufgaben", "Notizen", "Mitschrift", "Datum: DATE",
            "Dauer: 0:00:02", "Teilnehmende: ", "Schlagwörter: ", "2 Fotos (Word)"
        )) {
            assertTrue(text, text in word)
        }
        assertEquals("Besprechung", docxTexts(meeting(title = ""), other).first())
        val ics = TaskExport.ics(listOf(TaskExport.Item("T", null, null, "M")), 0L, other)
        assertTrue(ics.contains("DESCRIPTION:Aus der Besprechung: M"))
        val shared = MeetingExporter.shareText(meeting(), emptyList(), other)
        for (heading in listOf("TEILNEHMENDE\n", "ZUSAMMENFASSUNG\n", "NOTIZEN\n", "MITSCHRIFT\n")) {
            assertTrue(heading, shared.contains(heading))
        }
        for (english in listOf("ATTENDEES", "SUMMARY", "NOTES", "TRANSCRIPT")) {
            assertFalse(english, shared.contains(english))
        }
    }

    @Test
    fun countsStayAsciiWhateverThePhonesLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals(
                "12 photo attachment(s) stored in the Recap app.",
                ExportText().count(ExportText().photosWord, 12)
            )
        } finally {
            Locale.setDefault(before)
        }
    }
}
