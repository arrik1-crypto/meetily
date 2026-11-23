package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.SubtitleImport
import com.meetily.mobile.data.SubtitleImport.Problem
import com.meetily.mobile.data.SubtitleImport.Result
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.export.SubtitleExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading WebVTT and SRT transcripts into meetings without audio: the forms
 * Zoom and Teams write, the parts of each format that are not captions, the
 * leniencies real files need, and clear failures for anything else.
 */
class SubtitleImportTest {

    private fun ok(text: String): Result.Ok {
        val result = SubtitleImport.parse(text)
        assertTrue("expected captions, got $result", result is Result.Ok)
        return result as Result.Ok
    }

    private fun problem(text: String): Problem? =
        (SubtitleImport.parse(text) as? Result.Failed)?.problem

    // --- Zoom and Teams ------------------------------------------------------

    /** A Zoom cloud recording's transcript (.transcript.vtt): numbered cues, "Name: text". */
    private val zoom = """
        WEBVTT

        1
        00:00:01.350 --> 00:00:04.290
        Jane Smith: Hi everyone, thanks for joining.

        2
        00:00:04.290 --> 00:00:09.120
        John Lee: Thanks Jane. Before we start: the agenda
        has three items.

        3
        00:01:02.000 --> 00:01:05.500
        Jane Smith: Great.
    """.trimIndent()

    @Test
    fun zoomTranscriptsGiveSpeakersFromTheirNamePrefix() {
        val cues = ok(zoom).cues
        assertEquals(3, cues.size)
        assertEquals("Jane Smith", cues[0].speaker)
        assertEquals("Hi everyone, thanks for joining.", cues[0].text)
        assertEquals(1_350L, cues[0].startMs)
        assertEquals(4_290L, cues[0].endMs)
        // Only the first ": " names the speaker; the lines of a cue join.
        assertEquals("John Lee", cues[1].speaker)
        assertEquals("Thanks Jane. Before we start: the agenda has three items.", cues[1].text)
        assertEquals(62_000L, cues[2].startMs)
    }

    /** A Teams meeting transcript (.vtt): GUID cue ids and <v Name> voice tags. */
    private val teams = """
        WEBVTT

        b6b0a31e-7d0f-4a2b-9b56-0c3d8f1a2e45/17-0
        00:00:03.120 --> 00:00:05.450
        <v Priya Patel>Good morning, everyone.</v>

        b6b0a31e-7d0f-4a2b-9b56-0c3d8f1a2e45/21-0
        00:00:05.900 --> 00:00:08.000
        <v Marco Rossi>Morning! Can you all hear me?</v>
    """.trimIndent()

    @Test
    fun teamsTranscriptsGiveSpeakersFromTheirVoiceTags() {
        val cues = ok(teams).cues
        assertEquals(listOf("Priya Patel", "Marco Rossi"), cues.map { it.speaker })
        assertEquals("Good morning, everyone.", cues[0].text)
        assertEquals("Morning! Can you all hear me?", cues[1].text)
        assertEquals(3_120L, cues[0].startMs)
    }

    @Test
    fun aCueWithTwoVoicesBecomesTwoLinesAtTheSameTime() {
        val cues = ok(
            "WEBVTT\n\n00:01.000 --> 00:03.000\n<v Alice>Ready?\n<v Bob>Yes.</v>\n"
        ).cues
        assertEquals(listOf("Alice", "Bob"), cues.map { it.speaker })
        assertEquals(listOf("Ready?", "Yes."), cues.map { it.text })
        assertTrue(cues.all { it.startMs == 1_000L })
    }

    @Test
    fun aVoiceCarriesOnToTheCuesNextLines() {
        val cue = ok("WEBVTT\n\n00:01.000 --> 00:03.000\n<v.loud Alice>Hello\nthere</v>\n").cues.single()
        assertEquals("Alice", cue.speaker)
        assertEquals("Hello there", cue.text)
    }

    // --- WebVTT --------------------------------------------------------------

    @Test
    fun theHeaderNotesStylesAndRegionsAreNotCaptions() {
        val text = "\uFEFFWEBVTT - Weekly sync\nKind: captions\nLanguage: en\n" +
            "X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0\n\n" +
            "REGION\nid:fred\nwidth:40%\n\n" +
            "STYLE\n::cue { color: yellow }\n\n" +
            "NOTE this is a comment\nthat runs on\n\n" +
            "NOTE\n\n" +
            "intro\n00:00.500 --> 00:02.000 align:start position:10% line:0\nWelcome.\n"
        val result = ok(text)
        assertEquals(0, result.skipped)
        val cue = result.cues.single()
        assertEquals("Welcome.", cue.text)
        assertEquals(500L, cue.startMs)
        assertEquals(2_000L, cue.endMs)
    }

    @Test
    fun timestampsMayLeaveOutTheHourOrHaveMoreThanTwoDigits() {
        assertEquals(83_456L, SubtitleImport.timestampMs("01:23.456"))
        assertEquals(3_723_456L, SubtitleImport.timestampMs("01:02:03.456"))
        assertEquals(3_723_456L, SubtitleImport.timestampMs("1:02:03,456"))
        assertEquals(360_000_000L, SubtitleImport.timestampMs("100:00:00.000"))
        assertEquals(62_500L, SubtitleImport.timestampMs("00:01:02,5"))
        assertEquals(62_000L, SubtitleImport.timestampMs("00:01:02"))
        assertNull(SubtitleImport.timestampMs("00:61:00.000"))
        assertNull(SubtitleImport.timestampMs("00:00:75.000"))
        assertNull(SubtitleImport.timestampMs("12.5"))
        assertNull(SubtitleImport.timestampMs("soon"))
        assertNull(SubtitleImport.timestampMs(""))
    }

    @Test
    fun inlineTagsAndKaraokeTimesAreStrippedAndEntitiesDecoded() {
        val cue = ok(
            "WEBVTT\n\n00:00.000 --> 00:04.000\n" +
                "<c.yellow>Tom</c> &amp; <b>Jerry</b> &lt;3 <i>it</i>&nbsp;<u>all</u> " +
                "<00:00:01.500><lang fr>vraiment</lang> <ruby>漢<rt>kan</rt></ruby> " +
                "it&#39;s &#x2019;fine&#8217;&lrm;\n"
        ).cues.single()
        assertEquals("Tom & Jerry <3 it all vraiment 漢kan it's ’fine’", cue.text)
    }

    @Test
    fun aHeaderStraightAboveTheFirstCueStillLetsItThrough() {
        val cue = ok("WEBVTT\n00:00:01.000 --> 00:00:02.000\nNo blank line above.\n").cues.single()
        assertEquals("No blank line above.", cue.text)
    }

    @Test
    fun anEmptyCaptionFileSaysSo() {
        assertEquals(Problem.EMPTY, problem("WEBVTT\n\nNOTE nothing here\n"))
        assertEquals(Problem.EMPTY, problem(""))
        assertEquals(Problem.EMPTY, problem(" \n\t\n"))
        assertEquals(Problem.EMPTY, problem("WEBVTT\n\n00:00.000 --> 00:01.000\n<v Bob></v>\n"))
    }

    // --- SRT -----------------------------------------------------------------

    @Test
    fun srtWithCrlfBomCommaMillisAndIndexLines() {
        val text = "\uFEFF1\r\n00:00:01,000 --> 00:00:04,200\r\n<i>Hello</i> there,\r\n" +
            "{\\an8}general.\r\n\r\n2\r\n00:00:05,500 --> 00:00:07,000 X1:40 X2:600 Y1:20 Y2:50\r\n" +
            "<font color=\"#ffffff\">Second</font> line\r\n"
        val cues = ok(text).cues
        assertEquals(2, cues.size)
        assertEquals("Hello there, general.", cues[0].text)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(4_200L, cues[0].endMs)
        assertEquals("Second line", cues[1].text)
        assertEquals(7_000L, cues[1].endMs)
    }

    @Test
    fun oldMacLineEndingsWork() {
        val cues = ok("1\r00:00:01,000 --> 00:00:02,000\rOne\r\r2\r00:00:03,000 --> 00:00:04,000\rTwo\r").cues
        assertEquals(listOf("One", "Two"), cues.map { it.text })
    }

    @Test
    fun srtNamePrefixesAreSpeakersWhenMostCuesHaveThem() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nAlice: Shall we?\n\n" +
            "2\n00:00:02,000 --> 00:00:03,000\nBob: Let's.\n\n" +
            "3\n00:00:03,000 --> 00:00:04,000\n(laughter)\n"
        val cues = ok(text).cues
        assertEquals(listOf("Alice", "Bob", null), cues.map { it.speaker })
        assertEquals("Let's.", cues[1].text)
        assertEquals("(laughter)", cues[2].text)
    }

    @Test
    fun aStrayColonInAFewCuesIsNotTakenForASpeaker() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nNote: bring the slides\n\n" +
            "2\n00:00:02,000 --> 00:00:03,000\nWe start at ten.\n\n" +
            "3\n00:00:03,000 --> 00:00:04,000\nThen lunch.\n"
        val cues = ok(text).cues
        assertTrue(cues.all { it.speaker == null })
        assertEquals("Note: bring the slides", cues[0].text)
    }

    @Test
    fun aNameIsShortAndReadsAsOne() {
        assertEquals("Dr. Ada Lovelace" to "Hi", SubtitleImport.speakerPrefix("Dr. Ada Lovelace: Hi"))
        assertEquals("Speaker 2" to "Yes", SubtitleImport.speakerPrefix("Speaker 2: Yes"))
        assertNull(SubtitleImport.speakerPrefix("Well. Then: what?"))
        assertNull(SubtitleImport.speakerPrefix("Why? Because: reasons"))
        assertNull(SubtitleImport.speakerPrefix("one two three four five six: text"))
        assertNull(SubtitleImport.speakerPrefix("10: ten"))
        assertNull(SubtitleImport.speakerPrefix("Alice:"))
        assertNull(SubtitleImport.speakerPrefix("At 10:30 we meet"))
        assertNull(SubtitleImport.speakerPrefix(": nobody"))
    }

    // --- Malformed input -----------------------------------------------------

    @Test
    fun textWithoutCaptionTimesIsNotACaptionFile() {
        assertEquals(Problem.NOT_CAPTIONS, problem("Meeting notes\n\n- buy milk\n- call Bob\n"))
        assertEquals(Problem.NOT_CAPTIONS, problem("{\"segments\": []}"))
    }

    @Test
    fun timesThatCannotBeReadAreSkippedAndCounted() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nGood\n\n" +
            "2\n00:00:xx,000 --> 00:00:03,000\nBad start\n\n" +
            "3\n00:00:04,000 --> later\nBad end\n"
        val result = ok(text)
        assertEquals(listOf("Good"), result.cues.map { it.text })
        assertEquals(2, result.skipped)
        assertEquals(
            Problem.NO_CUES,
            problem("1\nbroken --> 00:00:02,000\nText\n\n2\n00:99:00,000 --> 00:00:03,000\nText\n")
        )
    }

    @Test
    fun anEndBeforeItsStartIsHeldAtTheStartAndCuesComeInTimeOrder() {
        val cues = ok(
            "1\n00:00:09,000 --> 00:00:10,000\nLater\n\n2\n00:00:05,000 --> 00:00:04,000\nEarlier\n"
        ).cues
        assertEquals(listOf("Earlier", "Later"), cues.map { it.text })
        assertEquals(5_000L, cues[0].endMs)
    }

    @Test
    fun anArrowInsideCaptionTextIsText() {
        val cue = ok("1\n00:00:01,000 --> 00:00:02,000\nLine one\nthen --> this\n").cues.single()
        assertEquals("Line one then --> this", cue.text)
    }

    @Test
    fun cuesWithoutBlankLinesBetweenThemAreStillSplit() {
        val srt = ok(
            "1\n00:00:01,000 --> 00:00:02,000\nHello\n2\n00:00:03,000 --> 00:00:04,000\nWorld\n" +
                "again\n00:00:05,000 --> 00:00:06,000\nNo index\n"
        )
        assertEquals(listOf("Hello", "World again", "No index"), srt.cues.map { it.text })
        assertEquals(listOf(1_000L, 3_000L, 5_000L), srt.cues.map { it.startMs })
        assertEquals(listOf(2_000L, 4_000L, 6_000L), srt.cues.map { it.endMs })
        assertEquals(0, srt.skipped)
        val vtt = ok("WEBVTT\n\n00:01.000 --> 00:02.000\n<v Ann>Hi\n00:02.000 --> 00:03.000\n<v Ben>Hello\n")
        assertEquals(listOf("Ann" to "Hi", "Ben" to "Hello"), vtt.cues.map { it.speaker to it.text })
    }

    @Test
    fun anUnreadableTimeInARunOfCuesSkipsOnlyThatCue() {
        val result = ok(
            "1\n00:00:xx,000 --> 00:00:02,000\nLost\n2\n00:00:03,000 --> 00:00:04,000\nKept\n"
        )
        assertEquals(listOf("Kept"), result.cues.map { it.text })
        assertEquals(1, result.skipped)
    }

    @Test
    fun aTimingTooFarDownItsBlockIsATimeThatCouldNotBeRead() {
        // Two identifier lines: the WebVTT parser drops such a block, and the
        // file is then one whose times could not be read, not an empty one.
        assertEquals(
            Problem.NO_CUES,
            problem("WEBVTT\n\nid1\nid2\n00:00:01.000 --> 00:00:02.000\nHi\n")
        )
        assertEquals(Problem.NO_CUES, problem("intro\nmore\n00:00:01,000 --> 00:00:02,000\nHi\n"))
        val result = ok(
            "WEBVTT\n\nid1\nid2\n00:00:01.000 --> 00:00:02.000\nLost\n\n00:00:03.000 --> 00:00:04.000\nKept\n"
        )
        assertEquals(listOf("Kept"), result.cues.map { it.text })
        assertEquals(1, result.skipped)
    }

    @Test
    fun aFileOverTheLimitIsRefusedUnread() {
        val result = SubtitleImport.parse(ByteArray(SubtitleImport.MAX_BYTES + 1))
        assertEquals(Problem.TOO_LARGE, (result as Result.Failed).problem)
    }

    @Test
    fun garbageNeverThrows() {
        val random = java.util.Random(7)
        val alphabet = "WEBVTT0123456789:.,->< \n\r\t&#;vNOTE\uFEFFabc"
        repeat(2_000) {
            val text = buildString {
                repeat(random.nextInt(200)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            SubtitleImport.parse(text)
            SubtitleImport.parse(text.toByteArray())
        }
        val bytes = ByteArray(4096).also { random.nextBytes(it) }
        SubtitleImport.parse(bytes)
    }

    // --- Text encodings ------------------------------------------------------

    @Test
    fun byteOrderMarksSayTheEncoding() {
        val text = "WEBVTT\n\n00:01.000 --> 00:02.000\nCafé ☕\n"
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        for (bytes in listOf(utf8, le, be, text.toByteArray(Charsets.UTF_16LE))) {
            assertEquals(text, SubtitleImport.decode(bytes))
        }
        assertEquals("Café ☕", (SubtitleImport.parse(le) as Result.Ok).cues.single().text)
    }

    @Test
    fun anOldSrtThatIsNotUtf8IsReadAsWindows1252() {
        // "Café – fin" in Windows-1252: é is 0xE9, the en dash 0x96.
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nCaf".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0xE9.toByte(), 0x20, 0x96.toByte()) + " fin\n".toByteArray(Charsets.US_ASCII)
        val cue = (SubtitleImport.parse(bytes) as Result.Ok).cues.single()
        assertEquals("Café – fin", cue.text)
    }

    // --- The meeting ---------------------------------------------------------

    @Test
    fun theMeetingLastsUntilTheLastCueAndHasNoAudio() {
        val now = 1_700_000_000_000L
        val cues = ok(zoom).cues
        val m = SubtitleImport.toMeeting("id1", "Weekly sync", cues, now)
        assertEquals(65_500L, SubtitleImport.durationMs(cues))
        assertEquals(now - 65_500L, m.createdAtMs)
        assertNull(m.audioFile)
        assertNull(m.transcriptResumeMs)
        assertEquals(listOf(1_350L, 4_290L, 62_000L), m.segments.map { it.audioMs })
        assertEquals(m.createdAtMs + 62_000L, m.segments[2].timestampMs)
        assertEquals(listOf("Jane Smith", "John Lee"), m.attendees)
        assertTrue(m.segments.all { it.words == null })
    }

    @Test
    fun genericAndUnknownSpeakersMapOntoTheAppsOwnLabels() {
        val cues = listOf(
            SubtitleImport.Cue(0, 1_000, "One", "Speaker 2"),
            SubtitleImport.Cue(1_000, 2_000, "Two", "speaker_10"),
            SubtitleImport.Cue(2_000, 3_000, "Three", "Unknown Speaker"),
            SubtitleImport.Cue(3_000, 4_000, "Four", "ALICE"),
            SubtitleImport.Cue(4_000, 5_000, "Five", "alice")
        )
        val m = SubtitleImport.toMeeting("id2", "t", cues, 10_000L)
        assertEquals(listOf(2, 10, null, null, null), m.segments.map { it.clusterId })
        assertEquals(listOf(null, null, null, "ALICE", "alice"), m.segments.map { it.speaker })
        // One attendee per name, whatever its case.
        assertEquals(listOf("ALICE"), m.attendees)
    }

    @Test
    fun theMeetingSurvivesBeingSavedAndReadBack() {
        val m = SubtitleImport.toMeeting("id3", "Teams call", ok(teams).cues, 1_000_000L)
        val back = Meeting.fromJson(m.toJson())
        assertEquals(m.segments, back.segments)
        assertEquals(m.attendees, back.attendees)
        assertNull(back.audioFile)
        assertEquals(m.createdAtMs, back.createdAtMs)
    }

    // --- Recap's own subtitle export -----------------------------------------

    /**
     * What the subtitle export writes (SubtitleExport) reads back as the same
     * lines, in both formats: where each starts, who said it and its words,
     * a cue wrapped onto two rows joined again, and escaped characters.
     */
    @Test
    fun ourOwnSubtitleExportReadsBackAsTheSameLines() {
        val lines = listOf(
            TranscriptSegment(1_000L, "Morning, everyone.", speaker = "Jane Smith", audioMs = 1_000L),
            TranscriptSegment(
                4_000L, "The budget review moved to Thursday afternoon.",
                speaker = "John Lee", audioMs = 4_000L
            ),
            TranscriptSegment(9_500L, "Noted: Q&A after lunch.", speaker = "Jane Smith", audioMs = 9_500L)
        )
        val cues = SubtitleExport.cues(lines)
        for (text in listOf(SubtitleExport.srt(cues), SubtitleExport.vtt(cues))) {
            val back = SubtitleImport.toMeeting("m", "t", ok(text).cues, 60_000L).segments
            assertEquals(lines.map { it.audioMs }, back.map { it.audioMs })
            assertEquals(lines.map { it.speaker }, back.map { it.speaker })
            assertEquals(lines.map { it.text }, back.map { it.text })
        }
    }
}
