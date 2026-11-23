package com.meetily.mobile.data

import java.text.Normalizer

/**
 * "Re-time words": the decisions behind measuring a transcript line's word
 * timings against its recording with a forced aligner (sherpa-onnx's
 * ForcedAligner with an English MFA model; AlignService runs it). Pure
 * Kotlin, unit-tested.
 *
 * - Which lines: those whose timings an edit estimated (WordTimes.ESTIMATED)
 *   or that have none, with a place in the recording and something to say
 *   ([needsRetime]: "Re-time edited lines" takes them all). One line at a
 *   time, also a line whose timings nobody recorded the origin of
 *   ([offersLine]): an edit saved before lines carried the flag looks
 *   exactly like the recogniser's timings.
 * - What to decode: the line from its own start to the next line's (or an
 *   estimate of its end), with [MARGIN_MS] either side, clamped to the
 *   recording; nearby lines share one pass through the file ([runs]).
 * - What the aligner reads: the aligner splits its text on whitespace,
 *   drops punctuation at the edges of each piece and looks pieces up
 *   lower-cased. [tokens] does the same to each transcript word first,
 *   and goes further where the dictionary is known to help: it splits a
 *   word at inner punctuation other than an apostrophe ("state-of-the-art",
 *   "and/or", "e.g."), writes every apostrophe as the dictionary's "'",
 *   and decomposes accents (NFKD) as the dictionary's keys are. So the
 *   aligner's word list is exactly the tokens sent, in order.
 * - How the result maps back ([mapWords]): a word takes the start of its
 *   first token when that token was found in the dictionary. A word the
 *   dictionary does not know is aligned by the model as unknown speech,
 *   which says roughly where the unknown stretch lies but not where each
 *   unknown word in it starts; such words, and words with nothing to align
 *   (punctuation only), are spread over the stretch between their aligned
 *   neighbours by length. Times are clamped into the line and never run
 *   backwards.
 * - The line's own start (TranscriptSegment.audioMs) is kept, whatever the
 *   alignment says. Everything else is placed relative to it (other lines'
 *   ends, Remove a passage, highlight marks, resume points, subtitles), the
 *   margin means a first word heard slightly early belongs to the line
 *   before as much as to this one, and a line's start is the user's to
 *   move by splitting. A word aligned before the line's start starts at it.
 */
object WordAlign {

    /** Audio decoded on each side of a line, so a word starting a little early is heard whole. */
    const val MARGIN_MS = 300L

    /** A line longer than this is left as it is (an untimed import's one long line, say). */
    const val MAX_LINE_MS = 3 * 60_000L

    /** Lines whose clips are closer than this share one pass through the recording. */
    const val RUN_GAP_MS = 30_000L

    /** Spoken after a line's last known word, when nothing follows to say where it ends. */
    const val TAIL_MS = 1_500L

    /** Beyond an estimated end: room for a slow speaker before the next line's start. */
    const val SLACK_MS = 10_000L

    /** Shorter than this, a line has no audio worth aligning. */
    const val MIN_CLIP_MS = 200L

    /** The model's pronunciation of an unknown word (meta.json "oov_phone"). */
    const val OOV_PHONE = "spn"

    /** Speech pace, as WordRealign estimates it. */
    private const val MS_PER_CHAR = WordRealign.MS_PER_CHAR

    /** Above this many cells the token match is not attempted. */
    private const val MAX_MATCH_CELLS = 250_000L

    private val WHITESPACE = Regex("\\s+")

    // --- Tokens ---------------------------------------------------------------

    /** The words of [text] as a line shows them: its whitespace-separated pieces. */
    fun words(text: String): List<String> = text.split(WHITESPACE).filter { it.isNotEmpty() }

    /**
     * Letters, marks and numbers: what the aligner keeps inside a word
     * (IsLexiconWordChar in sherpa-onnx). Everything else is punctuation.
     */
    fun isWordChar(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    /** Apostrophes, typed or typeset, which the dictionary writes as "'". */
    private fun isApostrophe(codePoint: Int): Boolean =
        codePoint == '\''.code || codePoint == 0x2019 || codePoint == 0x2018 ||
            codePoint == 0x02BC || codePoint == 0xFF07

    /**
     * What the aligner is given for one transcript [word]: lower-cased
     * pieces of letters, marks and numbers with their inner apostrophes,
     * split at any other punctuation. Empty for a word of punctuation only.
     */
    fun tokens(word: String): List<String> {
        val text = Normalizer.normalize(word, Normalizer.Form.NFKD)
        val out = ArrayList<String>(1)
        val piece = StringBuilder()
        fun flush() {
            var end = piece.length
            while (end > 0 && piece[end - 1] == '\'') end--
            if (end > 0) out.add(piece.substring(0, end))
            piece.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                // Before the letter test: U+02BC is a modifier letter.
                isApostrophe(cp) -> if (piece.isNotEmpty()) piece.append('\'')
                isWordChar(cp) -> piece.appendCodePoint(Character.toLowerCase(cp))
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** The aligner's text for a line of [words]: every token, space-separated. */
    fun alignerText(words: List<String>): String =
        words.flatMap { tokens(it) }.joinToString(" ")

    // --- Which lines ----------------------------------------------------------

    /** True when [segment] has a place in the recording and something to align. */
    fun alignable(segment: TranscriptSegment): Boolean =
        segment.audioMs != null && words(segment.text).any { tokens(it).isNotEmpty() }

    /**
     * True when [segment]'s timings are known to be worth measuring again:
     * an edit estimated some of them, or it has none. These are the lines
     * "Re-time edited lines" takes. A line whose timings came from the
     * recogniser, or whose origin nobody recorded, is not taken; see
     * [offersLine] for the one-line action.
     */
    fun needsRetime(segment: TranscriptSegment): Boolean =
        // The flag first: it is asked for every line the screen binds.
        (segment.words.isNullOrEmpty() || segment.wordTimes == WordTimes.ESTIMATED) &&
            alignable(segment)

    /** The indices of the lines "Re-time edited lines" takes, in order. */
    fun linesToRetime(segments: List<TranscriptSegment>): List<Int> =
        segments.indices.filter { needsRetime(segments[it]) }

    /**
     * True when a meeting's lines of unknown origin get "Re-time words"
     * ([offersLine]): it keeps a recording ([audioKept], the field, not a
     * stat of the file) and its speech is English or not recorded (the
     * user is asked). Another language, or a translation, would only be
     * told it cannot run, on every line.
     */
    fun mayRetimeUnknown(audioKept: Boolean, speech: Speech): Boolean =
        audioKept && (speech == Speech.ENGLISH || speech == Speech.UNKNOWN)

    /**
     * True when line [segment] gets the one-line "Re-time words" action:
     *
     * - a line that [needsRetime], always (a tap explains anything that
     *   stops it: no recording, another language, no model);
     * - a line with timings of unknown origin (words, no
     *   TranscriptSegment.wordTimes) when [unknownOffered]
     *   ([mayRetimeUnknown]). Those are the recogniser's timings, or an
     *   edit's estimate saved before lines recorded which they were (a
     *   Find & replace before this feature, say); only the user can tell
     *   them apart, so they are offered line by line and never taken in
     *   bulk;
     * - not a line re-timed already, until an edit estimates it again.
     */
    fun offersLine(segment: TranscriptSegment, unknownOffered: Boolean): Boolean = when {
        segment.audioMs == null -> false
        segment.words.isNullOrEmpty() || segment.wordTimes == WordTimes.ESTIMATED -> alignable(segment)
        segment.wordTimes == null -> unknownOffered && alignable(segment)
        else -> false
    }

    // --- Language -------------------------------------------------------------

    enum class Speech {
        /** Spoken English, detected or pinned: the model's language. */
        ENGLISH,

        /** Nobody recorded the language: the user is asked. */
        UNKNOWN,

        /** The transcript is an English translation of other speech. */
        TRANSLATED,

        /** Another language. */
        OTHER
    }

    /** What the meeting's speech is, for an English-only aligner; see MeetingLanguage. */
    fun speech(language: String?, translated: Boolean): Speech {
        val code = MeetingLanguage.normalize(language)
        return when {
            code == "en" -> Speech.ENGLISH
            translated -> Speech.TRANSLATED
            code == null -> Speech.UNKNOWN
            else -> Speech.OTHER
        }
    }

    // --- What to decode -------------------------------------------------------

    /**
     * Line [index]'s place in the recording: [startMs] to [endMs], and the
     * clip decoded for it, [clipStartMs] to [clipEndMs], all in ms from the
     * start of the recording.
     */
    data class Span(val startMs: Long, val endMs: Long, val clipStartMs: Long, val clipEndMs: Long) {
        val lengthMs: Long get() = endMs - startMs

        /** Where the clip starts relative to the line: 0 or less. */
        val clipOffsetMs: Long get() = clipStartMs - startMs
    }

    /**
     * The span of line [index] of [segments] in a recording of [durationMs]
     * (null or 0 when unknown). It ends where the next line with a later
     * start begins, but no later than its own estimated end plus
     * [SLACK_MS]; a line followed by none ends at that estimate, from its
     * last word's time or its length. Null when it has no place in the
     * recording, starts at or past its end, or leaves under [MIN_CLIP_MS].
     */
    fun span(segments: List<TranscriptSegment>, index: Int, durationMs: Long?): Span? {
        val segment = segments.getOrNull(index) ?: return null
        val start = segment.audioMs ?: return null
        if (start < 0L) return null
        val duration = durationMs?.takeIf { it > 0L }
        if (duration != null && start >= duration) return null
        val lastWord = segment.words?.maxOfOrNull { it.ms }?.coerceAtLeast(0L)
        val estimate = start + maxOf(
            (lastWord ?: 0L) + TAIL_MS,
            segment.text.length * MS_PER_CHAR,
            TAIL_MS
        )
        var next: Long? = null
        for (i in index + 1 until segments.size) {
            val at = segments[i].audioMs ?: continue
            if (at > start) {
                next = at
                break
            }
        }
        var end = if (next != null) minOf(next, estimate + SLACK_MS) else estimate
        if (duration != null) end = minOf(end, duration)
        if (end - start < MIN_CLIP_MS) return null
        val clipStart = (start - MARGIN_MS).coerceAtLeast(0L)
        val clipEnd = if (duration != null) minOf(end + MARGIN_MS, duration) else end + MARGIN_MS
        return Span(start, end, clipStart, clipEnd)
    }

    /** Clips decoded in one pass through the recording: [items] index the caller's list. */
    data class Run(val startMs: Long, val endMs: Long, val items: List<Int>)

    /**
     * Groups [clips] (start, end in ms) into passes through the recording:
     * in order of start, a clip that begins within [maxGapMs] of the end of
     * the pass so far joins it, so neighbouring lines are decoded once
     * rather than each from a seek of its own.
     */
    fun runs(clips: List<Pair<Long, Long>>, maxGapMs: Long = RUN_GAP_MS): List<Run> {
        val order = clips.indices.sortedWith(compareBy({ clips[it].first }, { it }))
        val out = mutableListOf<Run>()
        var items = mutableListOf<Int>()
        var runStart = 0L
        var runEnd = 0L
        for (i in order) {
            val (start, end) = clips[i]
            if (items.isNotEmpty() && start - runEnd > maxGapMs) {
                out.add(Run(runStart, runEnd, items))
                items = mutableListOf()
            }
            if (items.isEmpty()) {
                runStart = start
                runEnd = end
            }
            items.add(i)
            runEnd = maxOf(runEnd, end)
        }
        if (items.isNotEmpty()) out.add(Run(runStart, runEnd, items))
        return out
    }

    // --- Mapping the alignment back ---------------------------------------------

    /** One interval of an aligner tier, in seconds from the start of the decoded clip. */
    data class Interval(val start: Float, val end: Float, val text: String)

    /**
     * A line's new timings: [words] for every word of the line, of which
     * [aligned] took their start from the dictionary match of their own
     * first token; the rest were placed between their neighbours.
     */
    data class Mapped(val words: List<WordStamp>, val aligned: Int)

    /**
     * Times every one of a line's [words] (as [words] splits its text) from
     * an alignment of its clip: the aligner's "words" tier [wordTier] and
     * "phones" tier [phoneTier]. [clipOffsetMs] is where the clip starts
     * relative to the line (Span.clipOffsetMs), [lineLengthMs] the line's
     * length (Span.lengthMs): starts are kept within [0, lineLengthMs).
     *
     * Null when the alignment gives nothing to go on: no tiers, or no word
     * of the line found in the dictionary. The caller then keeps the line's
     * timings as they were.
     */
    fun mapWords(
        words: List<String>,
        wordTier: List<Interval>,
        phoneTier: List<Interval>,
        clipOffsetMs: Long,
        lineLengthMs: Long
    ): Mapped? {
        if (words.isEmpty()) return null
        val perWord = words.map { tokens(it) }
        val owner = ArrayList<Int>()
        val flat = ArrayList<String>()
        for ((w, list) in perWord.withIndex()) {
            for (token in list) {
                owner.add(w)
                flat.add(token)
            }
        }
        val labels = wordTier.filter { it.text.isNotEmpty() }
        if (flat.isEmpty() || labels.isEmpty()) return null
        val match = matchTokens(flat, labels.map { it.text }) ?: return null

        fun ms(seconds: Float): Long = clipOffsetMs + Math.round(seconds.toDouble() * 1000.0)

        // Per token: its interval in ms from the line's start, and whether
        // the model aligned it as unknown speech.
        val tokenStart = arrayOfNulls<Long>(flat.size)
        val tokenEnd = arrayOfNulls<Long>(flat.size)
        val unknown = BooleanArray(flat.size)
        for ((t, l) in match) {
            val interval = labels[l]
            tokenStart[t] = ms(interval.start)
            tokenEnd[t] = maxOf(ms(interval.end), ms(interval.start))
            unknown[t] = isUnknown(interval, phoneTier)
        }

        // A word is anchored by its first token, found in the dictionary.
        val firstToken = IntArray(words.size) { -1 }
        val lastToken = IntArray(words.size) { -1 }
        for (t in flat.indices) {
            val w = owner[t]
            if (firstToken[w] < 0) firstToken[w] = t
            lastToken[w] = t
        }
        val start = LongArray(words.size)
        val end = LongArray(words.size)
        val anchored = BooleanArray(words.size)
        for (w in words.indices) {
            val first = firstToken[w]
            if (first < 0 || tokenStart[first] == null || unknown[first]) continue
            anchored[w] = true
            start[w] = tokenStart[first]!!
            var e = start[w]
            for (t in first..lastToken[w]) tokenEnd[t]?.let { e = maxOf(e, it) }
            end[w] = e
        }
        val alignedCount = anchored.count { it }
        if (alignedCount == 0) return null

        // Runs of unanchored words go between their anchored neighbours.
        var w = 0
        while (w < words.size) {
            if (anchored[w]) {
                w++
                continue
            }
            val from = w
            while (w < words.size && !anchored[w]) w++
            val to = w
            val prevEnd = (from - 1).takeIf { it >= 0 }?.let { end[it] }
            val nextStart = to.takeIf { it < words.size }?.let { start[it] }
            // The stretch the model gave these words as unknown speech, if any.
            var ownStart: Long? = null
            var ownEnd: Long? = null
            for (k in from until to) {
                val first = firstToken[k]
                if (first < 0) continue
                for (t in first..lastToken[k]) {
                    val s = tokenStart[t] ?: continue
                    ownStart = minOf(ownStart ?: s, s)
                    ownEnd = maxOf(ownEnd ?: tokenEnd[t]!!, tokenEnd[t]!!)
                }
            }
            val run = words.subList(from, to)
            val needed = run.sumOf { it.length + 1 } * MS_PER_CHAR
            var low = when {
                ownStart != null && prevEnd != null -> maxOf(ownStart, prevEnd)
                ownStart != null -> ownStart
                prevEnd != null -> prevEnd
                else -> null
            }
            var high = when {
                ownEnd != null && nextStart != null -> minOf(ownEnd, nextStart)
                ownEnd != null -> ownEnd
                nextStart != null -> nextStart
                else -> null
            }
            if (low == null) low = maxOf(0L, (high ?: 0L) - needed)
            if (high == null) high = minOf(lineLengthMs, low + needed)
            if (high < low) high = low
            val placed = spread(run, low, high)
            for (k in from until to) {
                start[k] = placed[k - from]
                end[k] = start[k]
            }
        }

        // Within the line, and never backwards.
        val last = (lineLengthMs - 1).coerceAtLeast(0L)
        var floor = 0L
        val out = ArrayList<WordStamp>(words.size)
        for (k in words.indices) {
            val ms = start[k].coerceIn(0L, last).coerceAtLeast(floor)
            floor = ms
            out.add(WordStamp(ms, words[k]))
        }
        return Mapped(out, alignedCount)
    }

    /**
     * Pairs of (token index, label index) of [tokens] found in [labels], in
     * order. The aligner reads exactly the tokens it was given, so the two
     * lists normally pair one to one; a longest common run copes with
     * anything else. Null when the lists are too long to compare.
     */
    internal fun matchTokens(tokens: List<String>, labels: List<String>): List<Pair<Int, Int>>? {
        fun same(a: String, b: String) = a == b || a.equals(b, ignoreCase = true)
        if (tokens.size == labels.size && tokens.indices.all { same(tokens[it], labels[it]) }) {
            return tokens.indices.map { it to it }
        }
        val n = tokens.size
        val m = labels.size
        if (n.toLong() * m > MAX_MATCH_CELLS) return null
        val lengths = IntArray((n + 1) * (m + 1))
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lengths[i * (m + 1) + j] = if (same(tokens[i], labels[j])) {
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
                same(tokens[i], labels[j]) -> {
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

    /**
     * True when the model aligned [word] as unknown speech: every phone
     * inside it is [OOV_PHONE]. A word with no phones inside reads the same.
     */
    private fun isUnknown(word: Interval, phones: List<Interval>): Boolean {
        val eps = 1e-4f
        var any = false
        for (phone in phones) {
            if (phone.end <= word.start + eps || phone.start >= word.end - eps) continue
            any = true
            if (phone.text != OOV_PHONE) return false
        }
        return any || phones.isNotEmpty()
    }

    /** [words] spread over [start, end] by where each begins in the run. */
    private fun spread(words: List<String>, start: Long, end: Long): LongArray {
        val span = (end - start).coerceAtLeast(0L)
        val total = words.sumOf { it.length + 1 }.toLong().coerceAtLeast(1L)
        var offset = 0L
        return LongArray(words.size) { k ->
            val ms = start + span * offset / total
            offset += words[k].length + 1
            ms
        }
    }

    // --- Saving ---------------------------------------------------------------

    /** A line to re-time, as it read when the run began: how its result finds it again. */
    data class Target(val timestampMs: Long, val audioMs: Long, val text: String) {
        fun matches(segment: TranscriptSegment): Boolean =
            segment.timestampMs == timestampMs && segment.audioMs == audioMs && segment.text == text
    }

    fun target(segment: TranscriptSegment): Target? =
        segment.audioMs?.let { Target(segment.timestampMs, it, segment.text) }

    /** New timings for the line [target] names. */
    data class Retimed(val target: Target, val words: List<WordStamp>)

    /**
     * Puts each result into [segments], in place, marked ALIGNED. A line
     * that no longer reads as it did when the run began (edited, split,
     * deleted or re-transcribed meanwhile) is left exactly as it is: its
     * result was measured for words it no longer has. Returns how many
     * lines took their result.
     */
    fun apply(segments: MutableList<TranscriptSegment>, results: List<Retimed>): Int {
        val taken = HashSet<Int>()
        var applied = 0
        for (result in results) {
            val at = segments.indices.firstOrNull { it !in taken && result.target.matches(segments[it]) }
                ?: continue
            taken.add(at)
            val segment = segments[at]
            val words = result.words.ifEmpty { null } ?: continue
            if (segment.words == words && segment.wordTimes == WordTimes.ALIGNED) continue
            segments[at] = segment.copy(words = words, wordTimes = WordTimes.ALIGNED)
            applied++
        }
        return applied
    }
}
