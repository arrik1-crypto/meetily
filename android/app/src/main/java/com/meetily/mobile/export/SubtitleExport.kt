package com.meetily.mobile.export

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordRealign
import com.meetily.mobile.summarize.MeetingStats
import java.text.BreakIterator
import java.util.Locale

/**
 * Subtitles for a meeting's recording: SubRip (.srt) and WebVTT (.vtt).
 *
 * Only lines that carry [TranscriptSegment.audioMs] have a place on the
 * audio timeline; lines recorded without keeping the audio, or from
 * Android's speech service, do not, and are left out rather than guessed
 * at (see PlaybackFollow). Each line becomes one or more cues: a line
 * longer than about [MAX_CUE_MS], or than fits in two rows of
 * [MAX_LINE_CHARS], is cut at word boundaries into pieces of similar size.
 * Where the line has word timings the cuts take the time of the word they
 * fall on; without them, time is shared out in proportion to the text.
 * In scripts written without spaces (Chinese,
 * Japanese, Thai) a word boundary is where a timed word starts, and a run
 * longer than a row with none is cut between characters; such a piece is
 * taken from the line as it is, so it gains no spaces, and it is not cut
 * before closing punctuation or inside a character with its marks.
 *
 * Cues never overlap and never run backwards: each starts at or after the
 * previous one's end, and a line's estimated end is cut short where the
 * next line starts. The speaker is a WebVTT voice tag (`<v Name>`) and a
 * "Name: " prefix in SubRip, on every cue of that speaker, so a cue still
 * says who is talking when a player is started in the middle.
 *
 * SubRip is written with CRLF line endings and comma milliseconds
 * (00:01:02,345), WebVTT with a WEBVTT header and dot milliseconds. Both
 * give hours at least two digits and as many more as needed. Pure Kotlin,
 * unit-tested.
 */
object SubtitleExport {

    /** One subtitle, shown from [startMs] until [endMs] on the audio timeline. */
    data class Cue(
        val startMs: Long,
        val endMs: Long,
        /** Words of the cue on one line, single-spaced; wrapped when written. */
        val text: String,
        val speaker: String?
    )

    /** Longest stretch of speech one cue covers before it is cut. */
    const val MAX_CUE_MS = 7_000L

    /** One subtitle row, the common broadcast limit. */
    const val MAX_LINE_CHARS = 42

    /** Two rows. */
    const val MAX_CUE_CHARS = 2 * MAX_LINE_CHARS

    /** A cue is shown at least this long when the next one leaves room. */
    const val MIN_CUE_MS = 1_000L

    /** Reading pace a cue is given time for, when the next one leaves room. */
    private const val READING_CHARS_PER_SECOND = 17

    /** The last word takes time to say after it starts (as MeetingStats). */
    private const val WORD_TAIL_MS = 600L

    /** True when [segment] can be placed on the audio timeline and says something. */
    fun isTimed(segment: TranscriptSegment): Boolean =
        segment.audioMs != null && segment.text.isNotBlank()

    /** True when at least one line of [segments] can become a subtitle. */
    fun hasTimedLines(segments: List<TranscriptSegment>): Boolean = segments.any(::isTimed)

    /** Lines with text that subtitles leave out because they have no audio timing. */
    fun untimedLines(segments: List<TranscriptSegment>): Int =
        segments.count { it.audioMs == null && it.text.isNotBlank() }

    /** The cues for [segments], in time order. Empty when no line is timed. */
    fun cues(segments: List<TranscriptSegment>): List<Cue> {
        // Stable: two lines starting together keep their transcript order.
        val timed = segments.filter(::isTimed).sortedBy { startOf(it) }
        val out = ArrayList<Cue>()
        for ((index, segment) in timed.withIndex()) {
            val start = startOf(segment)
            var end = ownEndOf(segment, start)
            val next = timed.getOrNull(index + 1)?.let { startOf(it) }
            // An estimate never runs into the next line; only an equal or
            // earlier start (two lines in one chunk) is left to settle().
            if (next != null && next > start) end = minOf(end, next)
            if (end <= start) end = start + MIN_CUE_MS
            out.addAll(split(segment, start, end))
        }
        return settle(out)
    }

    /** "00:01:02,345": SubRip's form. Hours grow past two digits as needed. */
    fun srtTimestamp(ms: Long): String = timestamp(ms, ',')

    /** "00:01:02.345": WebVTT's form, hours always given. */
    fun vttTimestamp(ms: Long): String = timestamp(ms, '.')

    private fun timestamp(ms: Long, separator: Char): String {
        val value = ms.coerceAtLeast(0L)
        val hours = value / 3_600_000L
        val minutes = (value / 60_000L) % 60L
        val seconds = (value / 1_000L) % 60L
        val millis = value % 1_000L
        return String.format(
            Locale.US, "%02d:%02d:%02d%c%03d", hours, minutes, seconds, separator, millis
        )
    }

    /** SubRip with CRLF line endings; empty when there are no cues. */
    fun srt(cues: List<Cue>): String {
        val out = StringBuilder()
        var number = 0
        for (cue in cues) {
            val body = srtText(cue)
            if (body.isEmpty()) continue
            number++
            out.append(number).append(CRLF)
            out.append(srtTimestamp(cue.startMs)).append(" --> ")
                .append(srtTimestamp(cue.endMs)).append(CRLF)
            for (line in wrap(body)) out.append(line).append(CRLF)
            out.append(CRLF)
        }
        return out.toString()
    }

    /** WebVTT, starting with its WEBVTT header. */
    fun vtt(cues: List<Cue>): String {
        val out = StringBuilder("WEBVTT\n\n")
        for (cue in cues) {
            val text = clean(cue.text)
            if (text.isEmpty()) continue
            out.append(vttTimestamp(cue.startMs)).append(" --> ")
                .append(vttTimestamp(cue.endMs)).append('\n')
            val lines = wrap(text).map(::vttEscape)
            val speaker = cue.speaker?.let(::clean).orEmpty()
            for ((index, line) in lines.withIndex()) {
                // The voice span runs to the end of the cue; its end tag may
                // be left out there, and some players print it when present.
                if (index == 0 && speaker.isNotEmpty()) {
                    out.append("<v ").append(vttEscape(speaker)).append('>')
                }
                out.append(line).append('\n')
            }
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * [text] in at most two rows of [maxLine], cut where the longer row is
     * shortest. A space is the first choice (and is dropped). When no space
     * leaves both rows within [maxLine], a cut between two characters of a
     * script written without spaces is allowed too, and after that a cut
     * inside a run of more than [maxLine] characters with no space (a long
     * link, say); those cuts drop nothing. Never inside a character with
     * its marks, nor before closing punctuation. Text that is too long for
     * two rows still gets two, the longer one over [maxLine], rather than a
     * third; text with no place to cut stays as it is.
     */
    fun wrap(text: String, maxLine: Int = MAX_LINE_CHARS): List<String> {
        if (text.length <= maxLine) return listOf(text)
        var best = -1
        var bestSkip = 0
        var bestLonger = Int.MAX_VALUE
        for (i in 1 until text.length - 1) {
            if (text[i] != ' ') continue
            val longer = maxOf(i, text.length - i - 1)
            if (longer < bestLonger) {
                best = i
                bestSkip = 1
                bestLonger = longer
            }
        }
        if (best < 0 || bestLonger > maxLine) {
            val graphemes = graphemes(text)
            // Pass 1: between characters of a script without spaces.
            // Pass 2: anywhere in a run too long for a row.
            for (pass in 1..2) {
                var runStart = 0
                for (i in 1 until text.length) {
                    if (text[i - 1] == ' ') runStart = i
                    if (text[i] == ' ' || text[i - 1] == ' ') continue
                    if (!canCut(text, i, graphemes)) continue
                    val allowed = if (pass == 1) {
                        joinsUnspaced(text, i)
                    } else {
                        var runEnd = text.indexOf(' ', i)
                        if (runEnd < 0) runEnd = text.length
                        runEnd - runStart > maxLine
                    }
                    if (!allowed) continue
                    val longer = maxOf(i, text.length - i)
                    if (longer < bestLonger) {
                        best = i
                        bestSkip = 0
                        bestLonger = longer
                    }
                }
                if (best >= 0 && bestLonger <= maxLine) break
            }
        }
        if (best <= 0) return listOf(text)
        return listOf(text.substring(0, best), text.substring(best + bestSkip))
    }

    /** True when [text] can be written as one row, or as two, of at most [MAX_LINE_CHARS]. */
    fun fitsTwoRows(text: String): Boolean =
        text.length <= MAX_LINE_CHARS ||
            wrap(text).let { rows -> rows.size == 2 && rows.all { it.length <= MAX_LINE_CHARS } }

    private fun srtText(cue: Cue): String {
        val text = clean(cue.text)
        if (text.isEmpty()) return ""
        val speaker = cue.speaker?.let(::clean).orEmpty()
        // A row reading "-->" can be taken for a timing line by a loose parser.
        val body = if (speaker.isEmpty()) text else "$speaker: $text"
        return body.replace("-->", "->")
    }

    private fun vttEscape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Whitespace runs to single spaces; a blank row would end the cue early. */
    private fun clean(text: String): String = text.trim().replace(WHITESPACE, " ")

    // --- Timing -----------------------------------------------------------

    /** Where the speech of [segment] starts: its chunk, plus its first word's offset. */
    private fun startOf(segment: TranscriptSegment): Long {
        val base = segment.audioMs ?: 0L
        val first = segment.words?.firstOrNull()?.ms ?: 0L
        return (base + first.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    /** Where it ends: its last word plus a tail, or an estimate from its length. */
    private fun ownEndOf(segment: TranscriptSegment, start: Long): Long {
        val words = segment.words
        if (!words.isNullOrEmpty()) {
            return (segment.audioMs ?: 0L) + words.last().ms + WORD_TAIL_MS
        }
        return start + MeetingStats.estimateDurationMs(segment.text, null)
    }

    /**
     * A piece of a line a cue may end after: [start, end) in its text, with
     * no whitespace in it. Usually a whitespace-separated word; see [tokens].
     */
    private class Token(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /**
     * The pieces of [segment]'s text a cue may be cut between: its
     * whitespace-separated words, each cut again where a timed word starts
     * inside it in a script written without spaces (Whisper gives such text
     * one timed word per few characters, with no spaces between), and any
     * piece still longer than a row cut between every two characters
     * [canCut] allows.
     */
    private fun tokens(segment: TranscriptSegment): List<Token> {
        val text = segment.text
        val words = segment.words
        val wordStarts = if (words.isNullOrEmpty()) IntArray(0) else WordRealign.locate(text, words)
        val graphemes = graphemes(text)
        val out = ArrayList<Token>()
        fun addPiece(from: Int, to: Int) {
            if (to - from <= MAX_LINE_CHARS) {
                out.add(Token(from, to))
                return
            }
            var pieceStart = from
            for (i in from + 1 until to) {
                if (canCut(text, i, graphemes)) {
                    out.add(Token(pieceStart, i))
                    pieceStart = i
                }
            }
            out.add(Token(pieceStart, to))
        }
        var i = 0
        // Located starts only increase (WordRealign searches in order); -1 is a word not found.
        var nextWord = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            var from = start
            while (nextWord < wordStarts.size && wordStarts[nextWord] < i) {
                val at = wordStarts[nextWord++]
                if (at > from && joinsUnspaced(text, at) && canCut(text, at, graphemes)) {
                    addPiece(from, at)
                    from = at
                }
            }
            addPiece(from, i)
        }
        return out
    }

    /**
     * True when a row or cue may start at [at] of [text]: a character
     * boundary (not between a letter and its marks, nor inside a surrogate
     * pair or emoji sequence), not before punctuation that closes what
     * comes before it, and not after one that opens what follows.
     */
    private fun canCut(text: String, at: Int, graphemes: BreakIterator): Boolean {
        if (at <= 0 || at >= text.length) return false
        if (!graphemes.isBoundary(at)) return false
        return text[at] !in NO_BREAK_BEFORE && text[at - 1] !in NO_BREAK_AFTER
    }

    /** True when either side of [at] is a character of a script written without spaces. */
    private fun joinsUnspaced(text: String, at: Int): Boolean =
        isUnspaced(Character.codePointAt(text, at)) || isUnspaced(Character.codePointBefore(text, at))

    /** As WhisperBridge decides where Whisper's words need no space between them. */
    private fun isUnspaced(codePoint: Int): Boolean {
        if (Character.UnicodeScript.of(codePoint) in UNSPACED_SCRIPTS) return true
        return when (Character.UnicodeBlock.of(codePoint)) {
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
            Character.UnicodeBlock.KATAKANA -> true
            else -> false
        }
    }

    private fun graphemes(text: String): BreakIterator =
        BreakIterator.getCharacterInstance(Locale.ROOT).also { it.setText(text) }

    /**
     * When each token of [text] starts, between [start] and [end]. A token a
     * timed word starts in takes that word's time; the others are placed in
     * proportion to their position between the nearest timed neighbours
     * (the line's ends when there are none). Never decreasing.
     */
    private fun tokenTimes(
        segment: TranscriptSegment,
        text: String,
        tokens: List<Token>,
        start: Long,
        end: Long
    ): LongArray {
        val anchorAt = ArrayList<Int>()
        val anchorMs = ArrayList<Long>()
        val words = segment.words
        val base = segment.audioMs ?: 0L
        if (!words.isNullOrEmpty()) {
            val found = WordRealign.locate(text, words)
            for (i in words.indices) {
                if (found[i] >= 0) {
                    anchorAt.add(found[i])
                    anchorMs.add(base + words[i].ms)
                }
            }
        }
        val firstAt = tokens.first().start
        val lastAt = tokens.last().end
        val times = LongArray(tokens.size)
        for ((k, token) in tokens.withIndex()) {
            var exact: Long? = null
            var beforeAt = firstAt
            var beforeMs = start
            var afterAt = lastAt
            var afterMs = end
            for (a in anchorAt.indices) {
                val at = anchorAt[a]
                if (at >= token.start && at < token.end) {
                    exact = anchorMs[a]
                    break
                }
                if (at < token.start && at >= beforeAt) {
                    beforeAt = at
                    beforeMs = anchorMs[a]
                }
                if (at >= token.end && at < afterAt) {
                    afterAt = at
                    afterMs = anchorMs[a]
                }
            }
            times[k] = exact ?: if (afterAt > beforeAt) {
                beforeMs + (afterMs - beforeMs) * (token.start - beforeAt) / (afterAt - beforeAt)
            } else {
                beforeMs
            }
        }
        var floor = start
        for (k in times.indices) {
            times[k] = times[k].coerceIn(floor, maxOf(floor, end))
            floor = times[k]
        }
        return times
    }

    /**
     * Cuts one line, spoken over [start, end), into cues. Each piece is
     * sized toward an even share of what is left, and ends early at a
     * sentence end when it is already a fair size, so a long line does not
     * leave a one-word cue at its tail. A piece never grows past what
     * SubRip can write in two rows of [MAX_LINE_CHARS], "Name: " included
     * (WebVTT, with the name in a voice tag, then fits too).
     */
    private fun split(segment: TranscriptSegment, start: Long, end: Long): List<Cue> {
        val text = segment.text
        val tokens = tokens(segment)
        if (tokens.isEmpty()) return emptyList()
        val speaker = segment.speaker?.let(::clean)?.takeIf { it.isNotEmpty() }
        val prefix = if (speaker == null) "" else "$speaker: "
        // Roughly how much text a cue holds, for sizing pieces toward an
        // even share; SubRip spends part of the two rows on "Name: ".
        val budget = if (speaker == null) {
            MAX_CUE_CHARS
        } else {
            maxOf(MAX_LINE_CHARS, MAX_CUE_CHARS - speaker.length - 2)
        }
        val times = tokenTimes(segment, text, tokens, start, end)
        fun timeAfter(last: Int): Long = if (last + 1 < tokens.size) times[last + 1] else end
        // Where each token starts in the line with its whitespace runs made
        // single spaces, so a piece's length is one subtraction.
        val at = IntArray(tokens.size)
        for (k in 1 until tokens.size) {
            val gap = if (tokens[k].start > tokens[k - 1].end) 1 else 0
            at[k] = at[k - 1] + tokens[k - 1].length + gap
        }
        fun width(first: Int, last: Int): Int = at[last] + tokens[last].length - at[first]
        // The piece as the line has it: tokens cut from one word stay joined.
        fun pieceText(first: Int, last: Int): String =
            text.substring(tokens[first].start, tokens[last].end).replace(WHITESPACE, " ")
        fun fits(first: Int, last: Int): Boolean = fitsTwoRows(prefix + pieceText(first, last))

        val cues = ArrayList<Cue>()
        var first = 0
        while (first < tokens.size) {
            val remainingChars = width(first, tokens.size - 1)
            val remainingMs = end - times[first]
            var pieces = maxOf(
                1L,
                ceilDiv(remainingChars.toLong(), budget.toLong()),
                ceilDiv(remainingMs, MAX_CUE_MS)
            )
            // Short enough in all, but with no cut that leaves two rows of 42.
            if (pieces == 1L && !fits(first, tokens.size - 1)) pieces = 2L
            var last = tokens.size - 1
            if (pieces > 1) {
                val target = remainingChars.toDouble() / pieces
                last = first
                var length = tokens[first].length
                while (last + 1 < tokens.size) {
                    if (length >= target) break
                    if (endsSentence(text, tokens[last]) && length >= target * SENTENCE_SHARE) break
                    val grown = width(first, last + 1)
                    if (timeAfter(last + 1) - times[first] > MAX_CUE_MS) break
                    if (!fits(first, last + 1)) break
                    // Stop short when that lands nearer the even share.
                    if (grown > target && grown - target > target - length) break
                    length = grown
                    last++
                }
            }
            val pieceText = pieceText(first, last)
            val pieceStart = times[first]
            val pieceEnd = minOf(timeAfter(last), pieceStart + MAX_CUE_MS)
            cues.add(Cue(pieceStart, pieceEnd, pieceText, speaker))
            first = last + 1
        }
        return cues
    }

    /**
     * The final pass over every cue in order: each starts no earlier than
     * the previous one ends, gets time to be read where the next one leaves
     * room for it, and ends after it starts.
     */
    private fun settle(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>(cues.size)
        var previousEnd = 0L
        for ((index, cue) in cues.withIndex()) {
            val start = maxOf(cue.startMs, previousEnd)
            val reading = maxOf(
                MIN_CUE_MS,
                cue.text.length * 1_000L / READING_CHARS_PER_SECOND
            )
            var end = maxOf(cue.endMs, minOf(start + reading, start + MAX_CUE_MS))
            val next = cues.getOrNull(index + 1)?.startMs
            if (next != null && next > start) end = minOf(end, next)
            if (end <= start) end = start + MIN_CUE_MS
            out.add(Cue(start, end, cue.text, cue.speaker))
            previousEnd = end
        }
        return out
    }

    private fun endsSentence(text: String, token: Token): Boolean {
        var i = token.end - 1
        // Past closing quotes and brackets: «done.» and (yes.) end sentences too.
        while (i > token.start && text[i] in CLOSERS) i--
        return text[i] in SENTENCE_ENDS
    }

    private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0L) 0L else (a + b - 1) / b

    /** How much of an even share a piece needs before a sentence end may close it. */
    private const val SENTENCE_SHARE = 0.6

    private const val SENTENCE_ENDS = ".?!…。？！"
    private const val CLOSERS = "\"')]»”’」"

    /**
     * Characters a row or cue must not start with, where there is no space
     * before them: punctuation that closes what precedes it, and the
     * Japanese marks that lean on the character before (long vowel,
     * iteration marks, small kana), as in Japanese line breaking.
     */
    private const val NO_BREAK_BEFORE = SENTENCE_ENDS + CLOSERS +
        ",;:、，；：．・）］｝〕〉》」』】〙〗〟｠" +
        "ー々ゝゞヽヾぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ"

    /** Characters a row or cue must not end with: opening punctuation and Thai and Lao vowels written first. */
    private const val NO_BREAK_AFTER = "([{«“‘（［｛〔〈《「『【〘〖〝｟เแโใไເແໂໃໄ"

    private val UNSPACED_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.KHMER
    )

    private const val CRLF = "\r\n"
    private val WHITESPACE = Regex("\\s+")
}
