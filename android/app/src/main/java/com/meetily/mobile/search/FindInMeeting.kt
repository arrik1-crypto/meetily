package com.meetily.mobile.search

/**
 * Find in meeting: every place a typed phrase occurs in a transcript, and
 * which of them "next" and "previous" go to. Case- and accent-insensitive
 * ([TextFold]); matches are offsets into each line's own text, so they can be
 * marked where they are shown. Pure — unit-tested.
 *
 * Only finds. Find & replace is a separate feature with its own matching.
 */
object FindInMeeting {

    /** One occurrence: [start] until [end] in line [segment]'s text. */
    data class Match(val segment: Int, val start: Int, val end: Int)

    /**
     * Every match in transcript order. [capped] when there were more than
     * [MAX_MATCHES] and the rest were left out.
     */
    class Result(val matches: List<Match>, val capped: Boolean) {
        val isEmpty: Boolean get() = matches.isEmpty()
    }

    /** Enough for any phrase worth stepping through; a single letter stops here. */
    const val MAX_MATCHES = 20_000

    /** Where the find bar goes once a search has run. */
    sealed class Anchor {
        /** The first match at or after a place: a new query, from the line on screen. */
        data class AtOrAfter(val segment: Int, val start: Int) : Anchor()

        /** The first match after a place: "next". */
        data class After(val segment: Int, val start: Int) : Anchor()

        /** The last match before a place: "previous". */
        data class Before(val segment: Int, val start: Int) : Anchor()

        /** A match in [segment], else the closest line that has one: opening at a search hit. */
        data class Nearest(val segment: Int) : Anchor()
    }

    /** Each line folded once, so typing searches it again without folding it again. */
    fun index(texts: List<String>): List<TextFold.Folded> = texts.map { TextFold.fold(it) }

    /** Every match of [query] (as typed) in [lines], from [index]. */
    fun search(lines: List<TextFold.Folded>, query: String): Result {
        val folded = TextFold.query(query)
        if (folded.isBlank()) return Result(emptyList(), capped = false)
        val out = ArrayList<Match>()
        for ((segment, line) in lines.withIndex()) {
            var at = line.text.indexOf(folded)
            while (at >= 0) {
                if (out.size == MAX_MATCHES) return Result(out, capped = true)
                out.add(Match(segment, line.originalStart(at), line.originalEnd(at + folded.length)))
                at = line.text.indexOf(folded, at + folded.length)
            }
        }
        return Result(out, capped = false)
    }

    /**
     * Where [foldedQuery] (from TextFold.query) occurs in [text], as start and
     * end pairs into [text]. For marking one line as it is drawn, which is
     * always its current text even if the search ran before an edit.
     */
    fun rangesIn(text: String, foldedQuery: String): IntArray {
        if (foldedQuery.isBlank() || text.isEmpty()) return EMPTY
        val line = TextFold.fold(text)
        var at = line.text.indexOf(foldedQuery)
        if (at < 0) return EMPTY
        val out = ArrayList<Int>()
        while (at >= 0) {
            out.add(line.originalStart(at))
            out.add(line.originalEnd(at + foldedQuery.length))
            at = line.text.indexOf(foldedQuery, at + foldedQuery.length)
        }
        return out.toIntArray()
    }

    /** The match [anchor] picks out of [matches], or -1 when there are none. */
    fun pick(matches: List<Match>, anchor: Anchor): Int {
        if (matches.isEmpty()) return -1
        // Past the last match goes round to the first, and before the first
        // to the last, as next and previous do.
        return when (anchor) {
            is Anchor.AtOrAfter ->
                firstFrom(matches, anchor.segment, anchor.start).let { if (it == matches.size) 0 else it }
            is Anchor.After ->
                firstFrom(matches, anchor.segment, anchor.start + 1).let { if (it == matches.size) 0 else it }
            is Anchor.Before ->
                (firstFrom(matches, anchor.segment, anchor.start) - 1).let { if (it < 0) matches.size - 1 else it }
            is Anchor.Nearest -> nearest(matches, anchor.segment)
        }
    }

    /** One step from [index] through [size] matches, wrapping round at either end. */
    fun step(index: Int, size: Int, forward: Boolean): Int {
        if (size <= 0) return -1
        if (index !in 0 until size) return if (forward) 0 else size - 1
        return if (forward) (index + 1) % size else (index - 1 + size) % size
    }

    /** Index of the first match at or after ([segment], [start]); matches.size when none is. */
    private fun firstFrom(matches: List<Match>, segment: Int, start: Int): Int {
        var low = 0
        var high = matches.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val m = matches[mid]
            val before = m.segment < segment || (m.segment == segment && m.start < start)
            if (before) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * The first match in [segment]; failing that, whichever of the matches
     * either side is fewer lines away (the later one on a tie, since that
     * is where reading goes on).
     */
    private fun nearest(matches: List<Match>, segment: Int): Int {
        val after = firstFrom(matches, segment, 0)
        if (after < matches.size && matches[after].segment == segment) return after
        val before = after - 1
        if (after >= matches.size) return before
        if (before < 0) return after
        val gapBefore = segment - matches[before].segment
        val gapAfter = matches[after].segment - segment
        return if (gapBefore < gapAfter) before else after
    }

    private val EMPTY = IntArray(0)
}
