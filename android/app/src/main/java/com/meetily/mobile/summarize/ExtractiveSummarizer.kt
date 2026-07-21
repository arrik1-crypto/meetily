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

    fun summarize(
        transcript: String,
        notes: String,
        highlights: List<String> = emptyList(),
        actionsOnly: Boolean = false
    ): String {
        val sentences = splitSentences(transcript)
        if (sentences.isEmpty() && notes.isBlank() && highlights.isEmpty()) {
            return "Nothing to summarize yet — the transcript is empty."
        }

        if (actionsOnly) {
            val actionItems = sentences.filter { ACTION_PATTERN.containsMatchIn(it) }
            val builder = StringBuilder()
            builder.append("ACTION ITEMS (generated on-device)\n\n")
            if (actionItems.isEmpty()) {
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

        val frequencies = HashMap<String, Int>()
        for (sentence in sentences) {
            for (word in tokenize(sentence)) {
                if (word !in STOPWORDS && word.length > 2) {
                    frequencies[word] = (frequencies[word] ?: 0) + 1
                }
            }
        }

        val scored = sentences.mapIndexed { index, sentence ->
            val tokens = tokenize(sentence).filter { it !in STOPWORDS && it.length > 2 }
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

        val actionItems = sentences.filter { ACTION_PATTERN.containsMatchIn(it) }.take(10)

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
        }
        if (notes.isNotBlank()) {
            builder.append("\nYour notes:\n").append(notes.trim()).append('\n')
        }
        return builder.toString().trim()
    }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.length > 15 }

    private fun tokenize(sentence: String): List<String> =
        sentence.lowercase(Locale.getDefault())
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.isNotBlank() }
}
