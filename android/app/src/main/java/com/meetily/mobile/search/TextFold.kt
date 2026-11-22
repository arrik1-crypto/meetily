package com.meetily.mobile.search

import java.text.Normalizer
import java.util.Locale

/**
 * Text folded for matching: lowercased, and with the accents taken off
 * Latin, Greek and Cyrillic letters, so "cafe" finds "Café" and "resume"
 * finds "résumé". Pure — unit-tested.
 *
 * Accents come off only in those scripts, where they are marks on a letter
 * a reader still recognises without them. Elsewhere a mark can make a
 * different letter (Japanese か and が), so other text is only lowercased.
 *
 * Folding can change a line's length (a decomposed "é" is two characters
 * and folds to one), so [Folded] keeps where each folded character came
 * from: a match is reported, and marked on screen, in the line's own text.
 */
object TextFold {

    /**
     * [text] folded. [originalStart] and [originalEnd] map a range of it back
     * to the text it was folded from.
     */
    class Folded internal constructor(
        val text: String,
        // Original start and end of each folded character; null when folding
        // changed nothing but case (plain ASCII), where offsets are the same.
        private val starts: IntArray?,
        private val ends: IntArray?
    ) {
        /** Where folded character [index] starts in the original text. */
        fun originalStart(index: Int): Int = starts?.get(index) ?: index

        /** Where a folded range ending (exclusive) at [index] ends in the original. */
        fun originalEnd(index: Int): Int {
            if (index <= 0) return originalStart(0)
            return ends?.get(index - 1) ?: index
        }
    }

    fun fold(text: String): Folded {
        if (text.all { it.code < 0x80 }) {
            return Folded(text.lowercase(Locale.ROOT), null, null)
        }
        val out = StringBuilder(text.length)
        val starts = IntArray(text.length * 2)
        val ends = IntArray(text.length * 2)
        var count = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val next = i + Character.charCount(codePoint)
            val folded = foldCodePoint(codePoint)
            if (folded.isEmpty()) {
                // A combining mark on its own: it belongs to the character
                // before, so a match on that character covers it too.
                if (count > 0) ends[count - 1] = next
            } else {
                for (c in folded) {
                    if (count == starts.size) break
                    out.append(c)
                    starts[count] = i
                    ends[count] = next
                    count++
                }
            }
            i = next
        }
        return Folded(out.toString(), starts.copyOf(count), ends.copyOf(count))
    }

    /** A typed query in the form [fold] puts text in. */
    fun query(raw: String): String = fold(raw).text

    private fun foldCodePoint(codePoint: Int): String {
        if (isCombiningAccent(codePoint)) return ""
        val single = String(Character.toChars(codePoint))
        val base = if (hasFoldableAccents(codePoint)) {
            val decomposed = Normalizer.normalize(single, Normalizer.Form.NFD)
            val kept = StringBuilder(decomposed.length)
            var k = 0
            while (k < decomposed.length) {
                val part = decomposed.codePointAt(k)
                if (!isCombiningAccent(part)) kept.appendCodePoint(part)
                k += Character.charCount(part)
            }
            kept.toString()
        } else {
            single
        }
        // Greek final sigma is the same letter as σ; String.lowercase picks
        // one or the other by its place in a word, which a single letter
        // folded on its own cannot see.
        return base.lowercase(Locale.ROOT).replace('ς', 'σ')
    }

    /** Latin-1 to Cyrillic, Latin Extended Additional (Vietnamese) and Greek Extended. */
    private fun hasFoldableAccents(codePoint: Int): Boolean =
        codePoint in 0x00C0..0x052F || codePoint in 0x1E00..0x1FFF

    /** The Combining Diacritical Marks block: acute, grave, umlaut, cedilla, tilde... */
    private fun isCombiningAccent(codePoint: Int): Boolean = codePoint in 0x0300..0x036F
}
