package com.meetily.mobile.notes

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Renders the [NotesMarkdown] subset into one styled CharSequence, for
 * read-only surfaces that are a single TextView rather than a container of
 * views (the meeting summary).
 *
 * Only the display changes: the source String stays exactly as written, so
 * sharing, export, and anything feeding text back to a model keep the raw
 * Markdown they have always had.
 */
object MarkdownSpans {

    fun render(source: String): CharSequence {
        val out = SpannableStringBuilder()
        var previousWasGap = false
        for (block in NotesMarkdown.parse(source)) {
            if (out.isNotEmpty()) {
                out.append('\n')
                // A heading wants air above it, unless a blank line already
                // put some there.
                if (block is NotesMarkdown.Block.Heading && !previousWasGap) {
                    out.append('\n')
                }
            }
            when (block) {
                is NotesMarkdown.Block.Heading -> {
                    val start = out.length
                    out.append(inline(block.text))
                    out.setSpan(
                        StyleSpan(Typeface.BOLD), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    out.setSpan(
                        RelativeSizeSpan(
                            when (block.level) {
                                1 -> 1.25f
                                2 -> 1.13f
                                else -> 1.05f
                            }
                        ),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                is NotesMarkdown.Block.Bullet ->
                    out.append("•  ").append(inline(block.text))
                is NotesMarkdown.Block.Check ->
                    out.append(if (block.done) "☑  " else "☐  ")
                        .append(inline(block.text))
                is NotesMarkdown.Block.Para -> out.append(inline(block.text))
                // The separator newline above already is the blank line.
                NotesMarkdown.Block.Gap -> Unit
            }
            previousWasGap = block == NotesMarkdown.Block.Gap
        }
        return out
    }

    /** Splits [text] into bold / italic / code runs as one CharSequence. */
    fun inline(text: String): CharSequence {
        val out = SpannableStringBuilder()
        for (segment in NotesMarkdown.inlineSegments(text)) {
            val start = out.length
            out.append(segment.text)
            val end = out.length
            if (segment.bold) {
                out.setSpan(
                    StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (segment.italic) {
                out.setSpan(
                    StyleSpan(Typeface.ITALIC), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (segment.code) {
                out.setSpan(
                    TypefaceSpan("monospace"), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return out
    }
}
