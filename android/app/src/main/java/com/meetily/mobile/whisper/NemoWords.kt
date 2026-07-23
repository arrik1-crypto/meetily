package com.meetily.mobile.whisper

import com.meetily.mobile.data.WordStamp

/**
 * Assembles sherpa-onnx recognizer output (SentencePiece tokens + per-token
 * start times in seconds) into display text and word-level timings matching
 * the whisper path's semantics: WordStamp.ms is the word's start offset
 * within the decoded chunk. Pure Kotlin — unit-tested.
 */
object NemoWords {

    private const val WORD_MARK = '▁' // "▁", SentencePiece word boundary

    fun assemble(
        tokens: List<String>,
        timestampsSec: FloatArray
    ): Pair<String, List<WordStamp>> {
        val words = mutableListOf<WordStamp>()
        val current = StringBuilder()
        var currentStartMs = 0L
        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) {
                words.add(WordStamp(currentStartMs, text))
            }
            current.setLength(0)
        }
        for ((index, raw) in tokens.withIndex()) {
            if (raw.isEmpty()) continue
            val startsWord = raw[0] == WORD_MARK || raw[0] == ' '
            val piece = raw.trimStart(WORD_MARK, ' ')
            if (startsWord && current.isNotEmpty()) flush()
            if (current.isEmpty()) {
                currentStartMs =
                    ((timestampsSec.getOrNull(index) ?: 0f) * 1000).toLong()
                        .coerceAtLeast(0)
            }
            current.append(piece)
        }
        flush()
        return words.joinToString(" ") { it.text } to words
    }
}
