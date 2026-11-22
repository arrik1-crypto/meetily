package com.meetily.mobile.data

/**
 * Keeps a transcript line's word timings through a change to its wording.
 *
 * A line's [WordStamp]s are what make each word tappable (seek) and what
 * playback follows. They used to be dropped whenever the text was edited, so
 * correcting one misheard name cost that whole line its tap-to-seek. Here
 * only the words a change touches are re-timed:
 * - words outside every change keep their text and timing exactly;
 * - inside a change, words are diffed: words that survive keep their own
 *   timing, even when the change moved them along the line;
 * - between two survivors, a run of as many new words as old maps one to
 *   one, each new word taking the timing of the word it replaced; otherwise
 *   the replaced span's time is spread across its new words by their length.
 *
 * Words are found in the text the way TranscriptLinesAdapter finds them
 * (each one searched for after the previous), so a word that cannot be
 * found there was never shown as a word and is left out.
 * Pure Kotlin — unit-tested.
 */
object WordRealign {

    /** Rough speech pace, for timing words after the last one with a time. */
    const val MS_PER_CHAR = 60L

    /** Above this many cells a run is re-timed as a whole rather than diffed. */
    private const val MAX_DIFF_CELLS = 40_000

    /** [start, end) of the old text becomes [replacement]. */
    data class Edit(val start: Int, val end: Int, val replacement: String)

    /**
     * Where each word starts in [text], or -1 for a word that is not there:
     * the same sequential search the transcript list uses to place them.
     */
    fun locate(text: String, words: List<WordStamp>): IntArray {
        val out = IntArray(words.size) { -1 }
        var cursor = 0
        for ((index, word) in words.withIndex()) {
            if (word.text.isEmpty()) continue
            val at = text.indexOf(word.text, cursor)
            if (at < 0) continue
            out[index] = at
            cursor = at + word.text.length
        }
        return out
    }

    /** [text] with [edits] applied. The edits are sorted and do not overlap. */
    fun applyText(text: String, edits: List<Edit>): String {
        if (edits.isEmpty()) return text
        val out = StringBuilder(text.length + 16)
        var cursor = 0
        for (edit in edits) {
            out.append(text, cursor, edit.start)
            out.append(edit.replacement)
            cursor = edit.end
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /**
     * Timings for the text [edits] make of [text], from [words] for [text].
     * Null when there were no timings, or none of them could be placed.
     */
    fun applyEdits(text: String, words: List<WordStamp>?, edits: List<Edit>): List<WordStamp>? {
        if (words.isNullOrEmpty()) return null
        val starts = locate(text, words)
        val placed = ArrayList<Placed>(words.size)
        for (i in words.indices) {
            if (starts[i] >= 0) {
                placed.add(Placed(starts[i], starts[i] + words[i].text.length, words[i]))
            }
        }
        if (placed.isEmpty()) return null
        if (edits.isEmpty()) return placed.map { it.word }

        val clusters = clusters(placed, edits)
        val out = ArrayList<WordStamp>(placed.size + 4)
        var next = 0
        for (cluster in clusters) {
            while (next < cluster.from) out.add(placed[next++].word)
            val shifted = cluster.edits.map {
                Edit(it.start - cluster.spanStart, it.end - cluster.spanStart, it.replacement)
            }
            val newSpan = applyText(text.substring(cluster.spanStart, cluster.spanEnd), shifted)
            out.addAll(
                alignRun(
                    old = placed.subList(cluster.from, cluster.to).map { it.word },
                    tokens = tokens(newSpan),
                    before = placed.getOrNull(cluster.from - 1)?.word?.ms,
                    after = placed.getOrNull(cluster.to)?.word?.ms
                )
            )
            next = cluster.to
        }
        while (next < placed.size) out.add(placed[next++].word)
        return out.ifEmpty { null }
    }

    /**
     * Timings for [newText], a free edit of [oldText] (the line edit, the
     * split dialog). The change is taken as everything between the longest
     * common start and end, widened to whole words, and then treated as one
     * edit: words outside it keep their timings, and words inside it that
     * survive keep theirs too.
     */
    fun realign(oldText: String, words: List<WordStamp>?, newText: String): List<WordStamp>? {
        if (words.isNullOrEmpty()) return null
        if (oldText == newText) return words
        val limit = minOf(oldText.length, newText.length)
        var prefix = 0
        while (prefix < limit && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }
        // Widen to whole words, so "cat" -> "cats" is one word changed
        // rather than a new word "s". Not across characters of scripts
        // written without spaces, where every character can be a boundary.
        while (prefix > 0 && joinsWord(oldText, prefix - 1)) prefix--
        while (suffix > 0 && joinsWord(oldText, oldText.length - suffix)) suffix--
        val edit = Edit(
            prefix, oldText.length - suffix,
            newText.substring(prefix, newText.length - suffix)
        )
        return applyEdits(oldText, words, listOf(edit))
    }

    /**
     * Splits [words] for [text] at [charPos]: words before it go first,
     * words at or after it second. A word the position cuts through is cut
     * too, each piece keeping the word's start time. Times are unchanged
     * (still relative to the line's start).
     */
    fun partition(
        text: String,
        words: List<WordStamp>?,
        charPos: Int
    ): Pair<List<WordStamp>, List<WordStamp>> {
        if (words.isNullOrEmpty()) return emptyList<WordStamp>() to emptyList()
        val starts = locate(text, words)
        val first = mutableListOf<WordStamp>()
        val second = mutableListOf<WordStamp>()
        for (i in words.indices) {
            val start = starts[i]
            if (start < 0) continue
            val word = words[i]
            val end = start + word.text.length
            when {
                end <= charPos -> first.add(word)
                start >= charPos -> second.add(word)
                else -> {
                    val cut = charPos - start
                    val head = word.text.substring(0, cut).trim()
                    val tail = word.text.substring(cut).trim()
                    if (head.isNotEmpty()) first.add(WordStamp(word.ms, head))
                    if (tail.isNotEmpty()) second.add(WordStamp(word.ms, tail))
                }
            }
        }
        return first to second
    }

    private class Placed(val start: Int, val end: Int, val word: WordStamp)

    /**
     * Words [from, to) and the edits that touch them, spanning
     * [spanStart, spanEnd) of the old text. An edit that touches no word
     * (in the spaces between two) makes a cluster with no words, at the
     * place it falls.
     */
    private class Cluster(
        var from: Int,
        var to: Int,
        var spanStart: Int,
        var spanEnd: Int,
        val edits: MutableList<Edit>
    )

    private fun clusters(placed: List<Placed>, edits: List<Edit>): List<Cluster> {
        val out = mutableListOf<Cluster>()
        for (edit in edits) {
            var from = -1
            var to = -1
            for (i in placed.indices) {
                val word = placed[i]
                val touches = if (edit.start == edit.end) {
                    word.start < edit.start && word.end > edit.start
                } else {
                    word.start < edit.end && word.end > edit.start
                }
                if (touches) {
                    if (from < 0) from = i
                    to = i + 1
                }
            }
            val cluster = if (from < 0) {
                val at = placed.count { it.start < edit.end }
                Cluster(at, at, edit.start, edit.end, mutableListOf(edit))
            } else {
                Cluster(
                    from, to,
                    minOf(placed[from].start, edit.start),
                    maxOf(placed[to - 1].end, edit.end),
                    mutableListOf(edit)
                )
            }
            val last = out.lastOrNull()
            val joins = last != null && (
                cluster.from < last.to ||
                    (cluster.from == last.to && (last.from == last.to || cluster.from == cluster.to))
                )
            if (last != null && joins) {
                last.to = maxOf(last.to, cluster.to)
                last.spanStart = minOf(last.spanStart, cluster.spanStart)
                last.spanEnd = maxOf(last.spanEnd, cluster.spanEnd)
                last.edits.add(edit)
            } else {
                out.add(cluster)
            }
        }
        return out
    }

    /** The whitespace-separated pieces of [text]. */
    private fun tokens(text: String): List<String> =
        text.split(WHITESPACE).filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")

    /**
     * New words for [old], timed from [old] and from the neighbours' start
     * times [before] and [after] (null at either end of the line).
     */
    private fun alignRun(
        old: List<WordStamp>,
        tokens: List<String>,
        before: Long?,
        after: Long?
    ): List<WordStamp> {
        if (tokens.isEmpty()) return emptyList()
        if (old.isEmpty()) return between(tokens, before, after)
        // Equal word counts are diffed too: a free edit that drops one word
        // and adds another elsewhere keeps the count, and mapping it by
        // position would give every word in between its neighbour's time.
        // A true one-for-one change still maps by position below.
        val pairs = if (old.size.toLong() * tokens.size <= MAX_DIFF_CELLS) {
            commonWords(old.map { it.text }, tokens)
        } else {
            emptyList()
        }
        val out = ArrayList<WordStamp>(tokens.size)
        var oldAt = 0
        var newAt = 0
        var lastMs = before
        // Each kept word closes the changed run before it; a sentinel
        // past both ends closes the last one.
        for ((keptOld, keptNew) in pairs + (old.size to tokens.size)) {
            val oldRun = old.subList(oldAt, keptOld)
            val newRun = tokens.subList(newAt, keptNew)
            val nextMs = old.getOrNull(keptOld)?.ms ?: after
            when {
                newRun.isEmpty() -> Unit
                oldRun.size == newRun.size ->
                    newRun.indices.mapTo(out) { WordStamp(oldRun[it].ms, newRun[it]) }
                oldRun.isEmpty() -> out.addAll(between(newRun, lastMs, nextMs))
                else -> {
                    val end = nextMs
                        ?: (oldRun.last().ms + oldRun.last().text.length * MS_PER_CHAR)
                    out.addAll(spread(newRun, oldRun.first().ms, end))
                }
            }
            if (keptOld < old.size) {
                out.add(WordStamp(old[keptOld].ms, tokens[keptNew]))
                lastMs = old[keptOld].ms
            }
            oldAt = keptOld + 1
            newAt = keptNew + 1
        }
        return out
    }

    /** Index pairs of a longest common run of equal words, in order. */
    private fun commonWords(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        // lengths[i * (m + 1) + j]: longest common run of a[i..] and b[j..].
        val lengths = IntArray((n + 1) * (m + 1))
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lengths[i * (m + 1) + j] = if (a[i] == b[j]) {
                    lengths[(i + 1) * (m + 1) + j + 1] + 1
                } else {
                    maxOf(lengths[(i + 1) * (m + 1) + j], lengths[i * (m + 1) + j + 1])
                }
            }
        }
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(i to j)
                    i++
                    j++
                }
                lengths[(i + 1) * (m + 1) + j] >= lengths[i * (m + 1) + j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    /** [tokens] spread over [start, end) by where each begins in the run. */
    private fun spread(tokens: List<String>, start: Long, end: Long): List<WordStamp> {
        val span = (end - start).coerceAtLeast(0L)
        val total = tokens.sumOf { it.length + 1 }.toLong()
        var offset = 0L
        return tokens.map { token ->
            val ms = start + span * offset / total
            offset += token.length + 1
            WordStamp(ms, token)
        }
    }

    /** [tokens] placed evenly between two neighbours' start times. */
    private fun between(tokens: List<String>, low: Long?, high: Long?): List<WordStamp> {
        val n = tokens.size
        return tokens.mapIndexed { k, token ->
            val ms = when {
                low == null && high == null -> 0L
                low == null -> high!!
                high == null || high <= low -> low
                else -> low + (high - low) * (k + 1) / (n + 1)
            }
            WordStamp(ms, token)
        }
    }

    /**
     * True when the character at [index] belongs to a word that runs on
     * past it: not a space, and not from a script written without spaces.
     */
    private fun joinsWord(text: String, index: Int): Boolean {
        val c = text[index]
        if (c.isWhitespace()) return false
        if (c.isSurrogate()) return true
        return Character.UnicodeScript.of(c.code) !in UNSPACED
    }

    private val UNSPACED = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.KHMER
    )
}
