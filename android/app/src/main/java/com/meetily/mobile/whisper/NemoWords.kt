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

    /**
     * Scripts written without spaces between words. SentencePiece marks a
     * word start only after real whitespace, so in unsegmented Japanese or
     * Chinese only the first piece carries "▁" and a whole chunk collapsed
     * into ONE word holding the first token's time — no tap-to-seek or
     * follow-along inside the line. Each piece in these scripts is its own
     * word instead, as the whisper JNI does for the same languages. Hangul
     * is not here: Korean uses spaces.
     */
    private val NO_SPACE_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.KHMER
    )

    fun assemble(
        tokens: List<String>,
        timestampsSec: FloatArray
    ): Pair<String, List<WordStamp>> {
        val words = mutableListOf<WordStamp>()
        // Whether each word followed a "▁" boundary: the text keeps a space
        // there and only there, so splitting no-space scripts into words
        // changes the timings but never the displayed text.
        val spaced = mutableListOf<Boolean>()
        val current = StringBuilder()
        var currentStartMs = 0L
        var currentSpaced = false
        var prevNoSpace = false
        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) {
                words.add(WordStamp(currentStartMs, text))
                spaced.add(currentSpaced)
            }
            current.setLength(0)
        }
        for ((index, raw) in tokens.withIndex()) {
            if (raw.isEmpty()) continue
            val marked = raw[0] == WORD_MARK || raw[0] == ' '
            val piece = raw.trimStart(WORD_MARK, ' ')
            // Punctuation, digits and byte-fallback pieces (<0x..>) are
            // COMMON: they stay on the word they follow.
            val script = piece.firstOrNull()?.let {
                Character.UnicodeScript.of(piece.codePointAt(0))
            }
            val neutral = script == null ||
                script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
            val noSpace = script in NO_SPACE_SCRIPTS
            val startsWord = marked || noSpace || (prevNoSpace && !neutral)
            if (startsWord && current.isNotEmpty()) flush()
            if (current.isEmpty()) {
                currentStartMs =
                    ((timestampsSec.getOrNull(index) ?: 0f) * 1000).toLong()
                        .coerceAtLeast(0)
                currentSpaced = marked
            }
            current.append(piece)
            if (!neutral) prevNoSpace = noSpace
        }
        flush()
        val text = StringBuilder()
        for (i in words.indices) {
            if (i > 0 && spaced[i]) text.append(' ')
            text.append(words[i].text)
        }
        return text.toString() to words
    }
}
