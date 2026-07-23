package com.meetily.mobile.notes

/**
 * Line-based Markdown subset for meeting notes. Storage stays a plain
 * String (Meeting.notes), so old notes, exports, and LLM context are
 * untouched — this parser only drives the rendered view mode.
 *
 * Supported per line: "# / ## / ###" headings, "- " or "* " bullets,
 * "- [ ] " / "- [x] " checklists, everything else a paragraph line.
 * Inline **bold**, *italic*, and `code` are tokenized by [inlineSegments].
 */
object NotesMarkdown {

    sealed class Block {
        data class Heading(val text: String, val level: Int) : Block()
        data class Bullet(val text: String) : Block()
        data class Check(val text: String, val done: Boolean, val line: Int) : Block()
        data class Para(val text: String) : Block()
        object Gap : Block()
    }

    /** One inline run: plain text with bold/italic/code flags. */
    data class Segment(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false
    )

    private val CHECK = Regex("^- \\[( |x|X)] ?(.*)$")
    private val INLINE = Regex("(\\*\\*(.+?)\\*\\*|\\*([^*\\n]+?)\\*|`([^`\\n]+?)`)")

    fun parse(source: String): List<Block> {
        val blocks = mutableListOf<Block>()
        for ((index, raw) in source.lines().withIndex()) {
            val line = raw.trim()
            val check = CHECK.matchEntire(line)
            when {
                line.isEmpty() ->
                    if (blocks.isNotEmpty() && blocks.last() != Block.Gap) {
                        blocks.add(Block.Gap)
                    }
                check != null -> blocks.add(
                    Block.Check(
                        check.groupValues[2],
                        check.groupValues[1].equals("x", ignoreCase = true),
                        index
                    )
                )
                line.startsWith("### ") -> blocks.add(Block.Heading(line.drop(4).trim(), 3))
                line.startsWith("## ") -> blocks.add(Block.Heading(line.drop(3).trim(), 2))
                line.startsWith("# ") -> blocks.add(Block.Heading(line.drop(2).trim(), 1))
                line.startsWith("- ") -> blocks.add(Block.Bullet(line.drop(2).trim()))
                line.startsWith("* ") -> blocks.add(Block.Bullet(line.drop(2).trim()))
                else -> blocks.add(Block.Para(line))
            }
        }
        while (blocks.isNotEmpty() && blocks.last() == Block.Gap) {
            blocks.removeAt(blocks.size - 1)
        }
        return blocks
    }

    /** Flips the "- [ ]" / "- [x]" marker on [lineIndex] of [source]. */
    fun toggleCheck(source: String, lineIndex: Int): String {
        val lines = source.lines().toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return source
        val match = CHECK.matchEntire(line.trim()) ?: return source
        val done = match.groupValues[1].equals("x", ignoreCase = true)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        lines[lineIndex] = indent +
            (if (done) "- [ ] " else "- [x] ") +
            match.groupValues[2]
        return lines.joinToString("\n")
    }

    /** Splits [text] into styled runs for rendering. */
    fun inlineSegments(text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > cursor) {
                segments.add(Segment(text.substring(cursor, match.range.first)))
            }
            when {
                match.groupValues[2].isNotEmpty() ->
                    segments.add(Segment(match.groupValues[2], bold = true))
                match.groupValues[3].isNotEmpty() ->
                    segments.add(Segment(match.groupValues[3], italic = true))
                match.groupValues[4].isNotEmpty() ->
                    segments.add(Segment(match.groupValues[4], code = true))
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) segments.add(Segment(text.substring(cursor)))
        return segments
    }
}
