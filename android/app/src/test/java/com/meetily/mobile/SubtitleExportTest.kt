package com.meetily.mobile

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.export.SubtitleExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleExportTest {

    private fun line(
        audioMs: Long?,
        text: String,
        speaker: String? = null,
        words: List<WordStamp>? = null
    ) = TranscriptSegment(
        timestampMs = 1_000L,
        text = text,
        speaker = speaker,
        audioMs = audioMs,
        words = words
    )

    /** Word stamps for [text], one every [stepMs] from [firstMs], relative to the line. */
    private fun timedWords(text: String, firstMs: Long = 0L, stepMs: Long = 300L): List<WordStamp> =
        text.split(" ").mapIndexed { i, word -> WordStamp(firstMs + i * stepMs, word) }

    private fun assertOrdered(cues: List<SubtitleExport.Cue>) {
        var previousEnd = Long.MIN_VALUE
        for (cue in cues) {
            assertTrue("cue ends after it starts: $cue", cue.endMs > cue.startMs)
            assertTrue("cue starts at or after the previous end: $cue", cue.startMs >= previousEnd)
            previousEnd = cue.endMs
        }
    }

    // --- Timestamps -------------------------------------------------------

    @Test
    fun timestamps_srtUsesCommaAndVttUsesDot() {
        assertEquals("00:01:02,345", SubtitleExport.srtTimestamp(62_345L))
        assertEquals("00:01:02.345", SubtitleExport.vttTimestamp(62_345L))
        assertEquals("00:00:00,000", SubtitleExport.srtTimestamp(0L))
        assertEquals("01:00:00.000", SubtitleExport.vttTimestamp(3_600_000L))
    }

    @Test
    fun timestamps_hoursGrowPastTwoDigits() {
        assertEquals("100:00:00,001", SubtitleExport.srtTimestamp(360_000_001L))
        assertEquals("123:04:05.006", SubtitleExport.vttTimestamp(((123L * 60 + 4) * 60 + 5) * 1000 + 6))
    }

    @Test
    fun timestamps_negativeIsZero() {
        assertEquals("00:00:00,000", SubtitleExport.srtTimestamp(-5L))
    }

    // --- Which lines --------------------------------------------------------

    @Test
    fun untimedAndBlankLinesAreLeftOut() {
        val segments = listOf(
            line(null, "typed by hand"),
            line(1_000L, "   "),
            line(2_000L, "spoken")
        )
        assertTrue(SubtitleExport.hasTimedLines(segments))
        assertEquals(1, SubtitleExport.untimedLines(segments))
        val cues = SubtitleExport.cues(segments)
        assertEquals(1, cues.size)
        assertEquals("spoken", cues[0].text)
    }

    @Test
    fun noTimedLines_meansNoSubtitles() {
        val segments = listOf(line(null, "a"), line(null, "b"))
        assertFalse(SubtitleExport.hasTimedLines(segments))
        assertTrue(SubtitleExport.cues(segments).isEmpty())
        assertEquals("", SubtitleExport.srt(emptyList()))
        assertEquals("WEBVTT\n\n", SubtitleExport.vtt(emptyList()))
    }

    // --- Timing ---------------------------------------------------------------

    @Test
    fun shortLine_startsAtFirstWordAndEndsAfterLastWord() {
        val words = listOf(WordStamp(200L, "Hello"), WordStamp(500L, "there"))
        val cues = SubtitleExport.cues(listOf(line(10_000L, "Hello there", words = words)))
        assertEquals(1, cues.size)
        assertEquals(10_200L, cues[0].startMs)
        // Last word at 10 500 plus its tail; at least the minimum display time.
        assertTrue(cues[0].endMs >= 11_100L)
        assertTrue(cues[0].endMs >= cues[0].startMs + SubtitleExport.MIN_CUE_MS)
    }

    @Test
    fun estimatedEnd_isCutAtTheNextLine() {
        val long = "a fairly long sentence that would take a good few seconds to say out loud"
        val cues = SubtitleExport.cues(listOf(line(0L, long), line(1_500L, "next")))
        assertOrdered(cues)
        val firstLineEnd = cues.filter { it.startMs < 1_500L }.maxOf { it.endMs }
        assertTrue(firstLineEnd <= 1_500L)
        assertEquals(1_500L, cues.last().startMs)
    }

    @Test
    fun linesStartingTogether_neverOverlapOrRunBackwards() {
        val cues = SubtitleExport.cues(
            listOf(line(5_000L, "first"), line(5_000L, "second"), line(4_000L, "earlier"))
        )
        assertEquals(listOf("earlier", "first", "second"), cues.map { it.text })
        assertOrdered(cues)
    }

    @Test
    fun cuesAreSortedByAudioTimeNotTranscriptOrder() {
        val cues = SubtitleExport.cues(listOf(line(9_000L, "later"), line(1_000L, "sooner")))
        assertEquals(listOf("sooner", "later"), cues.map { it.text })
    }

    // --- Splitting ------------------------------------------------------------

    @Test
    fun longLineWithWordTimings_splitsAtWordsUsingTheirTimes() {
        val text = (1..40).joinToString(" ") { "word$it" }
        val words = timedWords(text, firstMs = 100L, stepMs = 400L)
        val cues = SubtitleExport.cues(listOf(line(60_000L, text, words = words)))
        assertTrue(cues.size > 1)
        assertOrdered(cues)
        // Nothing lost, nothing repeated, nothing cut inside a word.
        assertEquals(text, cues.joinToString(" ") { it.text })
        for (cue in cues) {
            assertTrue(cue.text.length <= SubtitleExport.MAX_CUE_CHARS)
            assertTrue(cue.endMs - cue.startMs <= SubtitleExport.MAX_CUE_MS)
            // Each cue starts exactly when its first word does.
            val firstWord = cue.text.substringBefore(' ')
            val stamp = words.first { it.text == firstWord }
            assertEquals(60_000L + stamp.ms, cue.startMs)
        }
        assertEquals(60_100L, cues.first().startMs)
    }

    @Test
    fun longLineWithoutWordTimings_sharesTimeByLength() {
        val text = "This is the first sentence of a long line. " +
            "It keeps going with a second sentence that adds more words. " +
            "And a third one to make sure it has to be cut more than once."
        val cues = SubtitleExport.cues(listOf(line(0L, text)))
        assertTrue(cues.size >= 2)
        assertOrdered(cues)
        assertEquals(text.trim(), cues.joinToString(" ") { it.text })
        for (cue in cues) assertTrue(cue.text.length <= SubtitleExport.MAX_CUE_CHARS)
        // Proportional: later pieces start later, from the line's own start.
        assertEquals(0L, cues.first().startMs)
        assertTrue(cues.zipWithNext().all { (a, b) -> b.startMs > a.startMs })
    }

    @Test
    fun longLine_prefersSentenceEnds() {
        val text = "We agreed to ship the update next week. " +
            "Maria will write the release notes and send them round by Friday."
        val cues = SubtitleExport.cues(listOf(line(0L, text)))
        assertEquals(2, cues.size)
        assertEquals("We agreed to ship the update next week.", cues[0].text)
    }

    @Test
    fun speakerName_shrinksTheRoomLeftForText() {
        val text = (1..30).joinToString(" ") { "w$it" }
        val speaker = "Alexandra Featherstonehaugh"
        val cues = SubtitleExport.cues(listOf(line(0L, text, speaker = speaker)))
        for (cue in cues) {
            assertEquals(speaker, cue.speaker)
            assertTrue("$speaker: ${cue.text}".length <= SubtitleExport.MAX_CUE_CHARS)
        }
    }

    @Test
    fun editedLineWithWordsMissingFromText_stillTimesEveryWord() {
        // "Jon" was corrected to "John"; its stamp no longer matches.
        val words = listOf(WordStamp(0L, "Ask"), WordStamp(300L, "Jon"), WordStamp(600L, "today"))
        val cues = SubtitleExport.cues(listOf(line(1_000L, "Ask John today", words = words)))
        assertEquals(1, cues.size)
        assertEquals("Ask John today", cues[0].text)
        assertEquals(1_000L, cues[0].startMs)
    }

    // --- Writing ----------------------------------------------------------------

    @Test
    fun srt_usesCrlfNumbersAndSpeakerPrefix() {
        val cues = listOf(
            SubtitleExport.Cue(1_000L, 2_500L, "Hello", "Ana"),
            SubtitleExport.Cue(3_000L, 4_000L, "Hi", null)
        )
        val srt = SubtitleExport.srt(cues)
        assertEquals(
            "1\r\n00:00:01,000 --> 00:00:02,500\r\nAna: Hello\r\n\r\n" +
                "2\r\n00:00:03,000 --> 00:00:04,000\r\nHi\r\n\r\n",
            srt
        )
        assertFalse(srt.replace("\r\n", "").contains('\n'))
    }

    @Test
    fun srt_wrapsIntoTwoRowsAndDefusesArrows() {
        val text = "this cue is long enough that it has to go over two rows --> see"
        val srt = SubtitleExport.srt(listOf(SubtitleExport.Cue(0L, 5_000L, text, null)))
        val rows = srt.split("\r\n")
        assertEquals("00:00:00,000 --> 00:00:05,000", rows[1])
        assertTrue(rows[2].length <= SubtitleExport.MAX_LINE_CHARS)
        assertTrue(rows[3].length <= SubtitleExport.MAX_LINE_CHARS)
        assertEquals("", rows[4])
        assertFalse(rows[2].contains("-->") || rows[3].contains("-->"))
    }

    @Test
    fun vtt_hasHeaderVoiceTagsAndEscapes() {
        val cues = listOf(
            SubtitleExport.Cue(61_000L, 62_000L, "Tom & Jerry <3", "R&D <lead>"),
            SubtitleExport.Cue(62_000L, 63_000L, "no speaker", null)
        )
        val vtt = SubtitleExport.vtt(cues)
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:01:01.000 --> 00:01:02.000\n<v R&amp;D &lt;lead&gt;>Tom &amp; Jerry &lt;3\n\n"))
        assertTrue(vtt.contains("00:01:02.000 --> 00:01:03.000\nno speaker\n\n"))
        assertFalse(vtt.contains("\r"))
    }

    @Test
    fun vtt_voiceTagOnFirstRowOnly() {
        val text = "a cue that is long enough to need a second row of text here"
        val vtt = SubtitleExport.vtt(listOf(SubtitleExport.Cue(0L, 4_000L, text, "Bo")))
        val rows = vtt.split("\n")
        assertTrue(rows[3].startsWith("<v Bo>"))
        assertFalse(rows[4].contains("<v"))
    }

    @Test
    fun wrap_balancesRowsAndNeverBreaksWords() {
        assertEquals(listOf("short"), SubtitleExport.wrap("short"))
        val rows = SubtitleExport.wrap("one two three four five six seven eight nine ten eleven")
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.length <= SubtitleExport.MAX_LINE_CHARS })
        assertEquals("one two three four five six seven eight nine ten eleven", rows.joinToString(" "))
        val solid = "x".repeat(60)
        assertEquals(listOf(solid), SubtitleExport.wrap(solid))
    }

    @Test
    fun endToEnd_fromSegmentsToBothFormats() {
        val segments = listOf(
            line(0L, "Good morning everyone", speaker = "Ana", words = timedWords("Good morning everyone")),
            line(2_000L, "Morning", speaker = "Ben")
        )
        val cues = SubtitleExport.cues(segments)
        val srt = SubtitleExport.srt(cues)
        val vtt = SubtitleExport.vtt(cues)
        assertTrue(srt.startsWith("1\r\n00:00:00,000 --> "))
        assertTrue(srt.contains("Ana: Good morning everyone\r\n"))
        assertTrue(srt.contains("2\r\n00:00:02,000 --> "))
        assertTrue(vtt.contains("<v Ana>Good morning everyone\n"))
        assertTrue(vtt.contains("<v Ben>Morning\n"))
    }
}
