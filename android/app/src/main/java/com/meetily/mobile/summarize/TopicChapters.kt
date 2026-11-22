package com.meetily.mobile.summarize

import com.meetily.mobile.data.Chapter
import com.meetily.mobile.data.TranscriptSegment
import java.util.Locale

/**
 * Offline topic separation: splits a transcript into chapters at
 * conversational seams (long pauses, or soft pauses once a section has run
 * a while), then titles each section with its most distinctive keywords —
 * the words that are frequent inside the section but rare outside it.
 * Used directly when no LLM is configured, and as the fallback when the
 * LLM chapter pass fails.
 */
object TopicChapters {

    private const val HARD_GAP_MS = 90_000L
    private const val SOFT_GAP_MS = 25_000L
    private const val SOFT_GAP_AFTER_MS = 6 * 60_000L
    private const val HARD_SECTION_MS = 12 * 60_000L
    private const val MIN_SEGMENTS = 8
    private const val MIN_SECTION_SEGMENTS = 3

    /**
     * [englishHeuristics] false (a transcript known to be in another
     * language; see MeetingLanguage.englishHeuristics) swaps the English
     * stopword list for [commonWords], so titles are not built from that
     * language's "nicht" and "aber".
     */
    fun buildLocal(
        segments: List<TranscriptSegment>,
        englishHeuristics: Boolean = true
    ): List<Chapter> {
        if (segments.size < MIN_SEGMENTS) return emptyList()

        val sections = mutableListOf<MutableList<TranscriptSegment>>()
        var current = mutableListOf(segments.first())
        for (i in 1 until segments.size) {
            val seg = segments[i]
            val gap = seg.timestampMs - segments[i - 1].timestampMs
            val sectionLen = seg.timestampMs - current.first().timestampMs
            val boundary = gap >= HARD_GAP_MS ||
                (sectionLen >= SOFT_GAP_AFTER_MS && gap >= SOFT_GAP_MS) ||
                sectionLen >= HARD_SECTION_MS
            if (boundary) {
                sections.add(current)
                current = mutableListOf(seg)
            } else {
                current.add(seg)
            }
        }
        sections.add(current)

        // Fold undersized sections into their predecessor: a two-line stray
        // is a pause, not a topic.
        val merged = mutableListOf<MutableList<TranscriptSegment>>()
        for (section in sections) {
            if (section.size < MIN_SECTION_SEGMENTS && merged.isNotEmpty()) {
                merged.last().addAll(section)
            } else {
                merged.add(section)
            }
        }
        if (merged.size < 2) return emptyList()

        val counts = if (englishHeuristics) {
            merged.map { wordCounts(it, STOPWORDS) }
        } else {
            val raw = merged.map { wordCounts(it, emptySet()) }
            val common = commonWords(raw, segments)
            raw.map { section -> section.filterKeys { it !in common } }
        }
        return merged.mapIndexed { index, section ->
            Chapter(
                title = titleFor(index, counts),
                startMs = section.first().timestampMs
            )
        }
    }

    /** Top distinctive keywords of section [index], as a "Alpha · Beta" title. */
    private fun titleFor(index: Int, counts: List<Map<String, Int>>): String {
        val inside = counts[index]
        val scored = inside.entries
            .map { (word, count) ->
                var elsewhere = 0
                for (i in counts.indices) {
                    if (i != index) elsewhere += counts[i][word] ?: 0
                }
                word to count * (count.toDouble() / (1.0 + elsewhere))
            }
            .sortedByDescending { it.second }
        val words = scored.take(2).filter { it.second > 0.5 }.map { it.first }
        if (words.isEmpty()) return "Part ${index + 1}"
        return words.joinToString(" · ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    private val NON_TOKEN = Regex("[^\\p{L}\\p{N}']+")

    private fun wordCounts(section: List<TranscriptSegment>, stopwords: Set<String>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (seg in section) {
            for (raw in seg.text.split(NON_TOKEN)) {
                val word = raw.lowercase(Locale.getDefault()).trim('\'')
                if (word.length < 4 || word in stopwords) continue
                counts[word] = (counts[word] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * A stand-in stopword list for a language without one: the words found
     * in more than half of the [sections], and those in a third or more of
     * all the [segments] (ExtractiveSummarizer.commonWords) that occur in
     * more than one section.
     *
     * The distinctiveness score does not do this on its own. It divides by
     * how often a word occurs elsewhere, so a function word spread evenly
     * over two sections still scores about its own count, and the commonest
     * words of a language have the highest counts there are. A word that
     * stays inside one section is never dropped: in a short meeting that
     * section's own subject can fill a third of all the lines.
     */
    internal fun commonWords(
        sections: List<Map<String, Int>>,
        segments: List<TranscriptSegment>
    ): Set<String> {
        val sectionFrequency = HashMap<String, Int>()
        for (section in sections) {
            for (word in section.keys) {
                sectionFrequency[word] = (sectionFrequency[word] ?: 0) + 1
            }
        }
        val dense = ExtractiveSummarizer.commonWords(segments.map { it.text })
        return sectionFrequency.filter { (word, inSections) ->
            inSections * 2 > sections.size || (inSections > 1 && word in dense)
        }.keys
    }

    private val STOPWORDS = setOf(
        "that", "this", "with", "from", "have", "there", "their", "they're",
        "what", "when", "where", "which", "will", "would", "could", "should",
        "about", "because", "been", "being", "just", "like", "them", "then",
        "than", "were", "your", "you're", "yeah", "okay", "right", "really",
        "going", "gonna", "want", "wanna", "think", "know", "well", "good",
        "some", "something", "things", "thing", "sure", "also", "into",
        "over", "here", "these", "those", "very", "much", "more", "most",
        "make", "made", "need", "needs", "kind", "sort", "actually", "maybe",
        "mean", "means", "said", "says", "does", "doesn't", "don't", "didn't",
        "can't", "cannot", "we're", "we'll", "it's", "that's", "let's",
        "i'll", "i've", "you've", "they", "she's", "he's", "who's", "how's",
        "little", "still", "even", "back", "take", "look", "come", "time",
        "people", "everyone", "everybody", "anything", "everything", "other",
        "another", "again", "getting", "doing", "done", "have", "haven't"
    )
}
