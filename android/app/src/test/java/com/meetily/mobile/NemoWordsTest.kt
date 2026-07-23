package com.meetily.mobile

import com.meetily.mobile.whisper.NemoWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SentencePiece token assembly for the NeMo (Parakeet/Nemotron) engines:
 * "▁" marks a word start; timestamps are per-token seconds within the chunk.
 */
class NemoWordsTest {

    @Test
    fun assemblesWordsAndTimings() {
        val (text, words) = NemoWords.assemble(
            listOf("▁hel", "lo", "▁wor", "ld", "!"),
            floatArrayOf(0.0f, 0.2f, 0.5f, 0.7f, 0.9f)
        )
        assertEquals("hello world!", text)
        assertEquals(2, words.size)
        assertEquals(0L, words[0].ms)
        assertEquals("hello", words[0].text)
        assertEquals(500L, words[1].ms)
        assertEquals("world!", words[1].text)
    }

    @Test
    fun handlesEmptyAndBlankTokens() {
        val (text, words) = NemoWords.assemble(emptyList(), floatArrayOf())
        assertEquals("", text)
        assertTrue(words.isEmpty())

        val (text2, words2) = NemoWords.assemble(
            listOf("", "▁", "▁hi"),
            floatArrayOf(0f, 0.1f, 0.4f)
        )
        assertEquals("hi", text2)
        assertEquals(1, words2.size)
        assertEquals(400L, words2[0].ms)
    }

    @Test
    fun firstTokenWithoutMarkStillStartsAWord() {
        val (text, words) = NemoWords.assemble(
            listOf("hey", "▁there"),
            floatArrayOf(0.3f, 1.0f)
        )
        assertEquals("hey there", text)
        assertEquals(300L, words[0].ms)
        assertEquals(1_000L, words[1].ms)
    }

    @Test
    fun missingTimestampsDefaultToZero() {
        val (_, words) = NemoWords.assemble(
            listOf("▁a", "▁b", "▁c"),
            floatArrayOf(0.1f) // shorter than tokens
        )
        assertEquals(3, words.size)
        assertEquals(100L, words[0].ms)
        assertEquals(0L, words[1].ms)
        assertEquals(0L, words[2].ms)
    }
}
