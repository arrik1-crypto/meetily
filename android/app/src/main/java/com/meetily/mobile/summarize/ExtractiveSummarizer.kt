package com.meetily.mobile.summarize

import java.util.Locale

/**
 * Fully offline extractive summarizer. No network, no model download:
 * frequency-scored sentence extraction plus simple action-item detection.
 * Used as the default so summaries work with zero configuration.
 */
object ExtractiveSummarizer {

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "so", "of", "to", "in",
        "on", "at", "for", "with", "about", "as", "by", "from", "up", "out", "is",
        "am", "are", "was", "were", "be", "been", "being", "have", "has", "had",
        "do", "does", "did", "will", "would", "can", "could", "should", "shall",
        "may", "might", "must", "that", "this", "these", "those", "it", "its",
        "i", "you", "he", "she", "we", "they", "them", "his", "her", "our", "your",
        "my", "me", "us", "not", "no", "yes", "just", "like", "get", "got", "gonna",
        "yeah", "okay", "ok", "um", "uh", "know", "right", "well", "really", "there",
        "what", "which", "who", "when", "where", "how", "why", "all", "any", "some"
    )

    private val ACTION_PATTERN = Regex(
        "\\b(will|going to|need(s)? to|has to|have to|should|must|todo|to-do|" +
            "action item|follow(s)? up|follow-up|by (monday|tuesday|wednesday|thursday|" +
            "friday|saturday|sunday|tomorrow|next week|end of|eod|eow)|deadline|assign(ed)?|" +
            "take care of|send (out|over)|schedule|set up|prepare|review|deliver)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isActionSentence(text: String): Boolean = ACTION_PATTERN.containsMatchIn(text)

    /**
     * Offline auto-title: first few words of the highest-scoring sentence.
     *
     * [englishHeuristics] false (a transcript known to be in another
     * language; see MeetingLanguage.englishHeuristics) swaps the English
     * stopword list for [commonWords].
     */
    fun titleFor(transcript: String, englishHeuristics: Boolean = true): String {
        val sentences = splitSentences(transcript)
        if (sentences.isEmpty()) return ""
        val stopwords = if (englishHeuristics) STOPWORDS else commonWords(sentences)
        // Each sentence is tokenized once and reused for scoring.
        val scored = sentences.map { sentence ->
            sentence to tokenize(sentence).filter { it !in stopwords && it.length > 2 }
        }
        val frequencies = HashMap<String, Int>()
        for ((_, tokens) in scored) {
            for (word in tokens) {
                frequencies[word] = (frequencies[word] ?: 0) + 1
            }
        }
        val best = scored.maxByOrNull { (_, tokens) ->
            if (tokens.isEmpty()) 0.0
            else tokens.sumOf { (frequencies[it] ?: 0).toDouble() } / tokens.size
        }?.first ?: return ""
        val words = best.trim().trimEnd('.', '!', '?', ',').split(WHITESPACE).take(7)
        if (words.isEmpty()) return ""
        val title = words.joinToString(" ")
        return title.replaceFirstChar { it.uppercase() }
    }

    /**
     * [englishHeuristics] false means the transcript is known to be in
     * another language. The action phrases are English ("will", "need to",
     * "by Friday") and in other languages match the wrong things — German
     * "will" is "wants" — so action items are left out and said to be left
     * out, rather than listed from false matches. Key points still come
     * from frequency scoring, with [commonWords] standing in for the
     * English stopword list.
     */
    fun summarize(
        transcript: String,
        notes: String,
        highlights: List<String> = emptyList(),
        actionsOnly: Boolean = false,
        englishHeuristics: Boolean = true
    ): String {
        val sentences = splitSentences(transcript)
        if (sentences.isEmpty() && notes.isBlank() && highlights.isEmpty()) {
            return "Nothing to summarize yet — the transcript is empty."
        }

        if (actionsOnly) {
            val actionItems = if (englishHeuristics) {
                sentences.filter { ACTION_PATTERN.containsMatchIn(it) }
            } else {
                emptyList()
            }
            val builder = StringBuilder()
            builder.append("ACTION ITEMS (generated on-device)\n\n")
            if (!englishHeuristics) {
                builder.append(NO_ACTIONS_IN_LANGUAGE).append('\n')
            } else if (actionItems.isEmpty()) {
                builder.append("No action items detected in the transcript.\n")
            } else {
                for (item in actionItems) {
                    builder.append("☐ ").append(item.trim()).append('\n')
                }
            }
            if (highlights.isNotEmpty()) {
                builder.append("\nHighlighted moments:\n")
                for (h in highlights) {
                    builder.append("★ ").append(h.trim()).append('\n')
                }
            }
            if (notes.isNotBlank()) {
                builder.append("\nYour notes:\n").append(notes.trim()).append('\n')
            }
            return builder.toString().trim()
        }

        val stopwords = if (englishHeuristics) STOPWORDS else commonWords(sentences)
        val frequencies = HashMap<String, Int>()
        for (sentence in sentences) {
            for (word in tokenize(sentence)) {
                if (word !in stopwords && word.length > 2) {
                    frequencies[word] = (frequencies[word] ?: 0) + 1
                }
            }
        }

        val scored = sentences.mapIndexed { index, sentence ->
            val tokens = tokenize(sentence).filter { it !in stopwords && it.length > 2 }
            val score = if (tokens.isEmpty()) 0.0
            else tokens.sumOf { (frequencies[it] ?: 0).toDouble() } / tokens.size
            Triple(index, sentence, score)
        }

        val keepCount = when {
            sentences.size <= 5 -> sentences.size
            else -> (sentences.size / 5).coerceIn(5, 10)
        }

        val keyPoints = scored
            .sortedByDescending { it.third }
            .take(keepCount)
            .sortedBy { it.first }
            .map { it.second }

        val actionItems = if (englishHeuristics) {
            sentences.filter { ACTION_PATTERN.containsMatchIn(it) }.take(10)
        } else {
            emptyList()
        }

        val builder = StringBuilder()
        builder.append("MEETING SUMMARY (generated on-device)\n\n")
        if (highlights.isNotEmpty()) {
            builder.append("Highlighted moments:\n")
            for (h in highlights) {
                builder.append("★ ").append(h.trim()).append('\n')
            }
            builder.append('\n')
        }
        if (keyPoints.isNotEmpty()) {
            builder.append("Key points:\n")
            for (point in keyPoints) {
                builder.append("• ").append(point.trim()).append('\n')
            }
        }
        if (actionItems.isNotEmpty()) {
            builder.append("\nPossible action items:\n")
            for (item in actionItems) {
                builder.append("• ").append(item.trim()).append('\n')
            }
        } else if (!englishHeuristics && sentences.isNotEmpty()) {
            builder.append('\n').append(NO_ACTIONS_IN_LANGUAGE).append('\n')
        }
        if (notes.isNotBlank()) {
            builder.append("\nYour notes:\n").append(notes.trim()).append('\n')
        }
        return builder.toString().trim()
    }

    private const val NO_ACTIONS_IN_LANGUAGE = "Action items are only picked out " +
        "automatically from English transcripts. Use an AI summary to list them."

    /**
     * A stand-in stopword list for a language without one: the words found
     * in at least a third of the sentences (and in three or more). In any
     * language those are mostly the articles, pronouns and particles the
     * English list removes, and leaving them in makes the most ordinary
     * sentences score highest. A third rather than less, so the meeting's
     * own subject, which recurs but not in every other sentence, stays in.
     * Pure — unit-tested.
     */
    internal fun commonWords(sentences: List<String>): Set<String> {
        if (sentences.size < 4) return emptySet()
        val documentFrequency = HashMap<String, Int>()
        for (sentence in sentences) {
            for (word in tokenize(sentence).toSet()) {
                documentFrequency[word] = (documentFrequency[word] ?: 0) + 1
            }
        }
        val floor = maxOf(3, sentences.size / 3)
        return documentFrequency.filterValues { it >= floor }.keys
    }

    // Compiled once rather than per call (tokenize runs per sentence).
    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+|\\n+")
    private val NON_TOKEN = Regex("[^\\p{L}\\p{N}']+")
    private val WHITESPACE = Regex("\\s+")

    private fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_BREAK)
            .map { it.trim() }
            .filter { it.length > 15 }

    private fun tokenize(sentence: String): List<String> =
        sentence.lowercase(Locale.getDefault())
            .split(NON_TOKEN)
            .filter { it.isNotBlank() }
}
