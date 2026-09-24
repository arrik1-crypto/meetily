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
        // Records keep whisper's leading space; the text is their concatenation.
        val raw = "\u001e0\u001f Hello,\u001e480\u001f world\u001e920\u001f again"
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
    fun unspacedScriptsAreNotJoinedWithSpaces() {
        // One record per token in Japanese, none with a leading space: the
        // text must read as written, not "今日 は 会議 です".
        val raw = "\u001e0\u001f今日\u001e300\u001fは\u001e450\u001f会議\u001e900\u001fです"
        val (text, words) = WhisperBridge.parseWords(raw)
        assertEquals("今日は会議です", text)
        assertEquals(4, words.size)
        assertEquals(WordStamp(450, "会議"), words[2])
    }

    @Test
    fun mixedScriptKeepsWhisperSpacing() {
        val raw = "\u001e0\u001f我们用\u001e400\u001f AI\u001e700\u001f 做\u001e800\u001f总结。"
        assertEquals("我们用 AI 做总结。", WhisperBridge.parseWords(raw).first)
    }

    @Test
    fun joinWordsSpacesOnlyBetweenSpacedScripts() {
        assertEquals(
            "Hello, world",
            WhisperBridge.joinWords(listOf(WordStamp(0, "Hello,"), WordStamp(5, "world")))
        )
        assertEquals(
            "今日は会議です",
            WhisperBridge.joinWords(
                listOf(WordStamp(0, "今日"), WordStamp(1, "は"), WordStamp(2, "会議"), WordStamp(3, "です"))
            )
        )
        assertEquals(
            "AIの会議。OK",
            WhisperBridge.joinWords(
                listOf(WordStamp(0, "AI"), WordStamp(1, "の"), WordStamp(2, "会議。"), WordStamp(3, "OK"))
            )
        )
        assertEquals(
            "สวัสดีครับ",
            WhisperBridge.joinWords(listOf(WordStamp(0, "สวัสดี"), WordStamp(1, "ครับ")))
        )
        assertEquals("", WhisperBridge.joinWords(emptyList()))
    }

    @Test
    fun liveAudioCtxCoversTheChunkWithAFloorAndFallsBackToFull() {
        // 1.2 s pad: far below the floor, so the floor applies.
        assertEquals(384, WhisperBridge.liveAudioCtx(19_200))
        // 10 s = 500 frames, + 64 margin = 564, rounded up to 576.
        assertEquals(576, WhisperBridge.liveAudioCtx(160_000))
        // 28 s = 1400 frames + 64 = 1464 -> 1472, still under the full 1500.
        assertEquals(1472, WhisperBridge.liveAudioCtx(448_000))
        // Anything that would reach the full window just uses it.
        assertEquals(0, WhisperBridge.liveAudioCtx(480_000))
        assertEquals(0, WhisperBridge.liveAudioCtx(0))
        for (n in listOf(16_000, 99_999, 300_000, 460_000)) {
            val ctx = WhisperBridge.liveAudioCtx(n)
            assertTrue(ctx == 0 || (ctx % 64 == 0 && ctx * 320 >= n))
        }
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
