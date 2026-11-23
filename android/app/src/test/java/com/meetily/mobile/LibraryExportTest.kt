package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.export.ExportFacts
import com.meetily.mobile.export.LibraryExport
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryExportTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    /** 2023-11-14T22:13:20Z: 23:13:20 in Berlin, the 15th in Tokyo. */
    private val at = 1_700_000_000_000L

    // --- YAML -----------------------------------------------------------------

    @Test
    fun yamlString_quotesAndEscapes() {
        assertEquals("\"plain\"", LibraryExport.yamlString("plain"))
        assertEquals("\"say \\\"hi\\\" \\\\ bye\"", LibraryExport.yamlString("say \"hi\" \\ bye"))
        assertEquals("\"a\\nb\\r\\tc\"", LibraryExport.yamlString("a\nb\r\tc"))
        assertEquals("\"x\\u0001\\u007F\\u0085y\"", LibraryExport.yamlString("x\u0001\u007F\u0085y"))
        assertEquals("\"\\u2028\\u2029\\uFEFF\\uFFFE\"", LibraryExport.yamlString("  ﻿￾"))
        // Kept as they are: anything YAML would otherwise read as syntax is
        // harmless inside double quotes.
        assertEquals("\"key: value # not a comment - [x] {y} & *z !t |\"",
            LibraryExport.yamlString("key: value # not a comment - [x] {y} & *z !t |"))
        assertEquals("\"中文 é 😀\"", LibraryExport.yamlString("中文 é 😀"))
        assertEquals("\"ab\"", LibraryExport.yamlString("a\uD800b"))
    }

    @Test
    fun frontMatter_hasEveryFieldInOrderAndCannotBeBroken() {
        val meeting = Meeting(
            id = "abc-123",
            title = "Q3: \"Plan\"\n---\nevil: true",
            createdAtMs = at,
            attendees = mutableListOf("Ana", "O'Brien: Ben"),
            tags = mutableListOf("client x"),
            segments = mutableListOf(TranscriptSegment(at, "hello", audioMs = 61_000L))
        ).apply { language = "de" }
        val yaml = LibraryExport.frontMatter(meeting, berlin)
        assertEquals(
            "---\n" +
                "title: \"Q3: \\\"Plan\\\"\\n---\\nevil: true\"\n" +
                "date: \"2023-11-14T23:13:20+01:00\"\n" +
                "duration: \"0:01:01\"\n" +
                "attendees:\n  - \"Ana\"\n  - \"O'Brien: Ben\"\n" +
                "tags:\n  - \"client x\"\n" +
                "language: \"de\"\n" +
                "id: \"abc-123\"\n" +
                "---\n",
            yaml
        )
        // Exactly the fences and one line per scalar or list item: nothing
        // in the values opened a new key.
        val lines = yaml.trimEnd('\n').split('\n')
        assertEquals("---", lines.first())
        assertEquals("---", lines.last())
        for (line in lines.drop(1).dropLast(1)) {
            assertTrue(line, Regex("^[a-z_]+: .+$|^[a-z_]+:$|^  - \".*\"$").matches(line))
        }
    }

    @Test
    fun frontMatter_emptyAndUnknownValues() {
        val meeting = Meeting(id = "e", title = "", createdAtMs = at)
        val yaml = LibraryExport.frontMatter(meeting, ZoneOffset.UTC)
        assertTrue(yaml.contains("title: \"\"\n"))
        assertTrue(yaml.contains("date: \"2023-11-14T22:13:20Z\"\n"))
        assertTrue(yaml.contains("duration: null\n"))
        assertTrue(yaml.contains("attendees: []\n"))
        assertTrue(yaml.contains("tags: []\n"))
        assertTrue(yaml.contains("language: null\n"))
        assertFalse(yaml.contains("translated_from"))
    }

    @Test
    fun frontMatter_translatedTranscriptSaysBothLanguages() {
        val meeting = Meeting(id = "t", title = "T", createdAtMs = at).apply {
            language = "fr"
            translated = true
        }
        val yaml = LibraryExport.frontMatter(meeting, berlin)
        assertTrue(yaml.contains("language: \"en\"\ntranslated_from: \"fr\"\n"))
    }

    @Test
    fun document_isFrontMatterThenTheMeetingsMarkdown() {
        val meeting = Meeting(id = "d", title = "Standup", createdAtMs = at, summary = "All good")
        val doc = LibraryExport.document(meeting, listOf("Notice."), berlin)
        assertTrue(doc.startsWith("---\ntitle: \"Standup\"\n"))
        val body = doc.substringAfter("\n---\n")
        assertTrue(body.startsWith("\n# Standup\n"))
        assertTrue(body.contains("## Summary\n\n> Notice.\n\nAll good"))
    }

    // --- File names -------------------------------------------------------------

    @Test
    fun sanitize_removesWhatFileSystemsAndNotesAppsReject() {
        assertEquals("Q3 Review Budget Plan", LibraryExport.sanitize("Q3 Review: Budget/Plan"))
        assertEquals("a b c d e f g h i j k l m", LibraryExport.sanitize("a\\b*c?d\"e<f>g|h#i^j[k]l\u0007m"))
        assertEquals("hidden", LibraryExport.sanitize("..hidden.. "))
        assertEquals("one two", LibraryExport.sanitize("  one \n\t two  "))
        assertEquals("Réunion 会議 🚀", LibraryExport.sanitize("Réunion 会議 🚀"))
        assertEquals("", LibraryExport.sanitize("///***"))
        assertEquals("ab", LibraryExport.sanitize("a\uDC00b"))
    }

    @Test
    fun sanitize_capsLengthInCharactersAndBytes() {
        val long = "x".repeat(200)
        assertEquals(LibraryExport.MAX_TITLE_CHARS, LibraryExport.sanitize(long).length)
        // Three bytes a character in UTF-8: capped by bytes first.
        val cjk = LibraryExport.sanitize("会".repeat(200))
        assertTrue(cjk.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(cjk.isNotEmpty())
        // Never half of a surrogate pair at the cut.
        val emoji = LibraryExport.sanitize("😀".repeat(100))
        assertFalse(Character.isHighSurrogate(emoji.last()))
        assertEquals(emoji.length, emoji.codePointCount(0, emoji.length) * 2)
    }

    @Test
    fun fileName_datesInTheZoneAndNeverCollidesWhateverTheCase() {
        val taken = HashSet<String>()
        val a = Meeting(id = "1", title = "Standup", createdAtMs = at)
        val b = Meeting(id = "2", title = "STANDUP", createdAtMs = at)
        val c = Meeting(id = "3", title = "standup", createdAtMs = at)
        assertEquals("2023-11-14 Standup.md", LibraryExport.fileName(a, berlin, taken))
        assertEquals("2023-11-14 STANDUP (2).md", LibraryExport.fileName(b, berlin, taken))
        assertEquals("2023-11-14 standup (3).md", LibraryExport.fileName(c, berlin, taken))
        assertEquals("2023-11-15 Standup.md", LibraryExport.fileName(a, ZoneId.of("Asia/Tokyo"), taken))
    }

    @Test
    fun fileName_avoidsFilesAlreadyInTheFolder_andBlankTitlesUseTheDate() {
        val taken = hashSetOf("2023-11-14 review.md", "2023-11-14.md")
        val review = Meeting(id = "1", title = "Review", createdAtMs = at)
        assertEquals("2023-11-14 Review (2).md", LibraryExport.fileName(review, berlin, taken))
        val untitled = Meeting(id = "2", title = "???", createdAtMs = at)
        assertEquals("2023-11-14 (2).md", LibraryExport.fileName(untitled, berlin, taken))
    }

    @Test
    fun uniqueName_countsUp() {
        val taken = setOf("a.md", "a (2).md")
        assertEquals("a (3).md", LibraryExport.uniqueName("a", ".md", taken))
        assertEquals("b.md", LibraryExport.uniqueName("b", ".md", taken))
    }

    @Test
    fun isoDateTime_keepsTheOffset() {
        assertEquals("2023-11-14T23:13:20+01:00", ExportFacts.isoDateTime(at, berlin))
        assertEquals("2023-11-14T22:13:20Z", ExportFacts.isoDateTime(at + 999L, ZoneOffset.UTC))
    }
}
