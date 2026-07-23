package com.meetily.mobile

import com.meetily.mobile.data.Attachment
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.notes.NotesMarkdown
import com.meetily.mobile.notes.NotesMarkdown.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesMarkdownTest {

    @Test
    fun parsesHeadingsBulletsChecksAndParagraphs() {
        val source = """
            # Agenda
            Regular line
            - bullet one
            * bullet two
            - [ ] open task
            - [x] done task
        """.trimIndent()
        val blocks = NotesMarkdown.parse(source)
        assertEquals(Block.Heading("Agenda", 1), blocks[0])
        assertEquals(Block.Para("Regular line"), blocks[1])
        assertEquals(Block.Bullet("bullet one"), blocks[2])
        assertEquals(Block.Bullet("bullet two"), blocks[3])
        assertEquals(Block.Check("open task", false, 4), blocks[4])
        assertEquals(Block.Check("done task", true, 5), blocks[5])
    }

    @Test
    fun toggleCheckFlipsOnlyTheTargetLine() {
        val source = "- [ ] first\n- [ ] second\n- [x] third"
        val toggled = NotesMarkdown.toggleCheck(source, 1)
        assertEquals("- [ ] first\n- [x] second\n- [x] third", toggled)
        val untoggled = NotesMarkdown.toggleCheck(toggled, 2)
        assertEquals("- [ ] first\n- [x] second\n- [ ] third", untoggled)
    }

    @Test
    fun toggleCheckIgnoresNonChecklistLines() {
        val source = "# Heading\nplain"
        assertEquals(source, NotesMarkdown.toggleCheck(source, 0))
        assertEquals(source, NotesMarkdown.toggleCheck(source, 99))
    }

    @Test
    fun inlineSegmentsSplitBoldItalicCode() {
        val segments = NotesMarkdown.inlineSegments("mix **bold** and *slant* plus `mono` end")
        assertEquals(
            listOf("mix ", "bold", " and ", "slant", " plus ", "mono", " end"),
            segments.map { it.text }
        )
        assertTrue(segments[1].bold)
        assertTrue(segments[3].italic)
        assertTrue(segments[5].code)
        assertTrue(segments[0].run { !bold && !italic && !code })
    }

    @Test
    fun plainTextStaysPlain() {
        val segments = NotesMarkdown.inlineSegments("no markers here")
        assertEquals(1, segments.size)
        assertEquals("no markers here", segments[0].text)
    }

    @Test
    fun attachmentsSurviveJsonRoundTrip() {
        val meeting = Meeting(id = "m1", title = "T", createdAtMs = 1_000L)
        meeting.attachmentsList.add(Attachment("m1_123_deck.pdf", "Q3 deck.pdf"))
        val restored = Meeting.fromJson(meeting.toJson())
        assertEquals(meeting.attachmentsList, restored.attachmentsList)
    }
}
