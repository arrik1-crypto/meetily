package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.whisper.WhisperBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingsTest {

    @Test
    fun parseWordsDecodesWireFormat() {
        val raw = "\u001e0\u001fHello,\u001e480\u001fworld\u001e920\u001fagain"
        val (text, words) = WhisperBridge.parseWords(raw)
        assertEquals("Hello, world again", text)
        assertEquals(
            listOf(
                WordStamp(0, "Hello,"),
                WordStamp(480, "world"),
                WordStamp(920, "again")
            ),
            words
        )
    }

    @Test
    fun parseWordsHandlesEmptyAndMalformedInput() {
        assertEquals("" to emptyList<WordStamp>(), WhisperBridge.parseWords(null))
        assertEquals("" to emptyList<WordStamp>(), WhisperBridge.parseWords(""))
        // Entries without a separator or timestamp are skipped, not fatal.
        val (text, words) = WhisperBridge.parseWords("\u001egarbage\u001e10\u001fok")
        assertEquals("ok", text)
        assertEquals(listOf(WordStamp(10, "ok")), words)
    }

    @Test
    fun wordsSurviveMeetingJsonRoundTrip() {
        val meeting = Meeting(id = "m1", title = "T", createdAtMs = 1_000L)
        meeting.segments.add(
            TranscriptSegment(
                timestampMs = 1_000L,
                text = "Hello, world",
                audioMs = 5_000L,
                words = listOf(WordStamp(0, "Hello,"), WordStamp(480, "world"))
            )
        )
        meeting.segments.add(
            TranscriptSegment(timestampMs = 2_000L, text = "no words here")
        )
        val restored = Meeting.fromJson(meeting.toJson())
        assertEquals(meeting.segments[0].words, restored.segments[0].words)
        assertNull(restored.segments[1].words)
    }

    @Test
    fun splitDropsWordTimings() {
        val segment = TranscriptSegment(
            timestampMs = 0L,
            text = "alpha beta gamma delta",
            audioMs = 0L,
            words = listOf(WordStamp(0, "alpha"), WordStamp(400, "beta"))
        )
        val result = com.meetily.mobile.data.TranscriptSplitter.split(
            segment, null, charPos = 11
        )
        assertTrue(result != null)
        assertNull(result!!.first.words)
        assertNull(result.second.words)
    }
}
