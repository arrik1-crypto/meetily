package com.meetily.mobile.search

import com.meetily.mobile.data.Meeting
import kotlin.math.ln

/**
 * On-device retrieval over the whole meeting library: tokenizes a question,
 * scores every meeting by weighted term hits (title > summary/actions >
 * notes/attendees > transcript) with a recency boost, and extracts matching
 * excerpts for display and for LLM context. Pure Kotlin — unit-testable.
 */
object LibrarySearch {

    data class Hit(
        val meeting: Meeting,
        val score: Double,
        val excerpts: List<String>
    )

    private val STOPWORDS = setOf(
        "the", "and", "for", "are", "was", "were", "did", "does", "with",
        "that", "this", "what", "when", "where", "who", "how", "why", "our",
        "you", "your", "have", "has", "had", "about", "did", "not", "any",
        "last", "there", "they", "them", "then", "than", "will", "would",
        "should", "could", "can", "get", "got", "into", "from", "meeting",
        "meetings", "discuss", "discussed", "talk", "talked", "say", "said"
    )

    /** Common question words that would otherwise match nearly every meeting. */
    private val CJK_STOPWORDS = setOf(
        "什么", "关于", "上次", "会议", "我们", "他们", "怎么", "这个", "那个", "时候",
        "会議", "何を", "前回"
    )

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Search terms in [question].
     *
     * Chinese, Japanese and Thai are written without spaces, so splitting on
     * non-letters left a whole question as one long token that no meeting
     * contains word for word — and dropped two-character words like 预算
     * (budget) outright as "short". Runs in those scripts become overlapping
     * character bigrams instead, which substring-match the way words do.
     * Hangul is spaced, but its words are often two syllables, so they keep
     * a two-character minimum.
     */
    fun tokenize(question: String): List<String> {
        val out = mutableListOf<String>()
        for (token in question.lowercase().split(NON_WORD)) {
            var i = 0
            while (i < token.length) {
                val unspaced = isUnspaced(token.codePointAt(i))
                var j = i
                while (j < token.length && isUnspaced(token.codePointAt(j)) == unspaced) {
                    j += Character.charCount(token.codePointAt(j))
                }
                val run = token.substring(i, j)
                if (unspaced) {
                    addBigrams(run, out)
                } else {
                    val min = if (isHangul(run.codePointAt(0))) 2 else 3
                    if (run.length >= min && run !in STOPWORDS) out.add(run)
                }
                i = j
            }
        }
        return out.distinct()
    }

    private fun addBigrams(run: String, out: MutableList<String>) {
        val points = run.codePoints().toArray()
        if (points.size == 1) {
            out.add(run)
            return
        }
        // Pure-hiragana pairs inside mixed Japanese are mostly grammar
        // (particles, verb endings) and would match every Japanese meeting.
        val mixed = points.any { !isHiragana(it) }
        for (k in 0 until points.size - 1) {
            if (mixed && isHiragana(points[k]) && isHiragana(points[k + 1])) continue
            val gram = String(points, k, 2)
            if (gram !in CJK_STOPWORDS) out.add(gram)
        }
    }

    private fun isUnspaced(codePoint: Int): Boolean =
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI -> true
            else -> false
        }

    private fun isHiragana(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA

    private fun isHangul(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL

    fun search(
        meetings: List<Meeting>,
        question: String,
        limit: Int = 4,
        nowMs: Long = System.currentTimeMillis()
    ): List<Hit> {
        val terms = tokenize(question)
        if (terms.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        for (meeting in meetings) {
            var score = 0.0
            score += 5.0 * damped(countHits(meeting.title, terms))
            score += 3.0 * damped(countHits(meeting.summary, terms))
            score += 3.0 * damped(
                countHits(meeting.actionItems.joinToString(" ") { it.task }, terms)
            )
            score += 4.0 * damped(countHits(meeting.tags.joinToString(" "), terms))
            score += 2.0 * damped(countHits(meeting.notes, terms))
            score += 2.0 * damped(
                countHits(meeting.photoTexts.values.joinToString(" "), terms)
            )
            score += 2.0 * damped(countHits(meeting.attendeesText(), terms))
            score += 1.0 * damped(countHits(meeting.transcriptText(), terms))
            if (score <= 0.0) continue
            // Gentle recency boost: same-week ~1.3x, half-year-old ~1.0x.
            val ageDays = ((nowMs - meeting.createdAtMs) / 86_400_000L)
                .coerceAtLeast(0)
            score *= 1.0 + 0.3 / (1.0 + ageDays / 30.0)
            hits.add(Hit(meeting, score, excerpts(meeting, terms)))
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    /** Log-damped so one term repeated 50 times doesn't drown everything. */
    private fun damped(count: Int): Double =
        if (count <= 0) 0.0 else 1.0 + ln(count.toDouble())

    private fun countHits(text: String, terms: List<String>): Int {
        if (text.isBlank()) return 0
        val lower = text.lowercase()
        var count = 0
        for (term in terms) {
            var index = lower.indexOf(term)
            while (index >= 0) {
                count++
                index = lower.indexOf(term, index + term.length)
            }
        }
        return count
    }

    /** Up to three matching moments: transcript windows, then summary lines. */
    private fun excerpts(meeting: Meeting, terms: List<String>): List<String> {
        val out = mutableListOf<String>()
        for ((i, segment) in meeting.segments.withIndex()) {
            if (out.size >= 2) break
            val lower = segment.text.lowercase()
            if (terms.any { lower.contains(it) }) {
                val speaker = segment.speaker
                val line = if (speaker.isNullOrBlank()) {
                    segment.text
                } else {
                    "$speaker: ${segment.text}"
                }
                // A little following context helps the line stand alone.
                val next = meeting.segments.getOrNull(i + 1)?.text.orEmpty()
                out.add((line + if (next.isBlank()) "" else " … $next").take(300))
            }
        }
        if (out.size < 3 && meeting.summary.isNotBlank()) {
            for (line in meeting.summary.lineSequence()) {
                if (out.size >= 3) break
                val lower = line.lowercase()
                if (line.isNotBlank() && terms.any { lower.contains(it) }) {
                    out.add(line.trim().take(300))
                }
            }
        }
        return out
    }
}
