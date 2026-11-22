package com.meetily.mobile.search

/**
 * A short stretch of text around one match, for showing why a search found
 * something: about a hundred characters, cut between words, an ellipsis
 * wherever text was left out, and where the match sits in it so it can be
 * highlighted. Pure — unit-tested.
 */
object MatchSnippet {

    /** [text] with the match at [matchStart] until [matchEnd]; empty when it could not be placed. */
    data class Snippet(val text: String, val matchStart: Int, val matchEnd: Int) {

        /** For a screen reader: the words alone, without the ellipses that only show a cut. */
        fun spoken(): String = text.removePrefix(ELLIPSIS).removeSuffix(ELLIPSIS).trim()

        val hasMatch: Boolean get() = matchEnd > matchStart
    }

    const val ELLIPSIS = "…"

    /** Characters of text, not counting the ellipses. */
    const val LENGTH = 100

    /**
     * How much of [LENGTH] comes before the match. Less than half: the words
     * after a match say more about it, and a home-list row is narrow enough
     * that the match must sit early to stay inside its lines.
     */
    const val LEAD = 32

    /**
     * How far a cut moves to fall between words. Chinese and Japanese have
     * no spaces to find, so past this the cut stays where it was.
     */
    private const val WORD_REACH = 16

    /** The snippet of [text] around the match from [start] until [end]. */
    fun around(text: String, start: Int, end: Int): Snippet {
        val matchStart = start.coerceIn(0, text.length)
        val matchEnd = end.coerceIn(matchStart, text.length)
        val matchLength = matchEnd - matchStart
        var from: Int
        var to: Int
        if (matchLength >= LENGTH) {
            from = matchStart
            to = matchStart + LENGTH
        } else {
            val room = LENGTH - matchLength
            var before = minOf(LEAD, room)
            var after = room - before
            // Room one side cannot use goes to the other.
            val spareBefore = before - matchStart
            if (spareBefore > 0) {
                before = matchStart
                after += spareBefore
            }
            val spareAfter = after - (text.length - matchEnd)
            if (spareAfter > 0) {
                after = text.length - matchEnd
                before = minOf(matchStart, before + spareAfter)
            }
            from = matchStart - before
            to = matchEnd + after
            from = startBetweenWords(text, from, matchStart)
            to = endBetweenWords(text, to, matchEnd)
        }
        // Never between the two halves of a surrogate pair.
        if (from in 1 until text.length && Character.isLowSurrogate(text[from])) from--
        if (to in 1 until text.length && Character.isHighSurrogate(text[to - 1])) to++
        return build(text, from, to, matchStart, matchEnd)
    }

    /** [from] moved forward to the start of a word, unless that is beyond [limit] or [WORD_REACH]. */
    private fun startBetweenWords(text: String, from: Int, limit: Int): Int {
        if (from <= 0 || from >= text.length) return from.coerceIn(0, text.length)
        if (!isWordChar(text[from - 1]) || !isWordChar(text[from])) return from
        val stop = minOf(limit, from + WORD_REACH)
        for (k in from until stop) {
            if (!isWordChar(text[k])) return k + 1
        }
        return from
    }

    /** [to] moved back to the end of a word, unless that is before [limit] or past [WORD_REACH]. */
    private fun endBetweenWords(text: String, to: Int, limit: Int): Int {
        if (to <= 0 || to >= text.length) return to.coerceIn(0, text.length)
        if (!isWordChar(text[to - 1]) || !isWordChar(text[to])) return to
        val stop = maxOf(limit, to - WORD_REACH)
        for (k in to - 1 downTo stop) {
            if (!isWordChar(text[k])) return k
        }
        return to
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’'

    /**
     * [text] from [from] until [to] with each run of spaces and line breaks
     * made one space, trimmed, with the ellipses, and the match's place in it.
     */
    private fun build(text: String, from: Int, to: Int, start: Int, end: Int): Snippet {
        val out = StringBuilder(to - from + 2)
        var cutBefore = false
        for (k in 0 until from) {
            if (!text[k].isWhitespace()) {
                cutBefore = true
                break
            }
        }
        if (cutBefore) out.append(ELLIPSIS)
        val contentFrom = out.length
        var space = false
        var matchStart = -1
        var matchEnd = -1
        for (k in from until to) {
            if (k == start || k == end) {
                if (space && out.length > contentFrom) out.append(' ')
                space = false
                if (k == start) matchStart = out.length
                if (k == end) matchEnd = out.length
            }
            val c = text[k]
            if (c.isWhitespace()) {
                space = true
                continue
            }
            if (space && out.length > contentFrom) out.append(' ')
            space = false
            out.append(c)
        }
        if (matchStart < 0) matchStart = out.length
        if (matchEnd < 0) matchEnd = out.length
        var cutAfter = false
        for (k in to until text.length) {
            if (!text[k].isWhitespace()) {
                cutAfter = true
                break
            }
        }
        if (cutAfter) out.append(ELLIPSIS)
        return Snippet(out.toString(), matchStart, maxOf(matchStart, matchEnd))
    }
}
