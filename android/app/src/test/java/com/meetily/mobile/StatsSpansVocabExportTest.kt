package com.meetily.mobile

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.export.TaskExport
import com.meetily.mobile.summarize.MeetingStats
import com.meetily.mobile.whisper.SpeechSpans
import com.meetily.mobile.whisper.Vocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsSpansVocabExportTest {

    private fun seg(
        text: String,
        speaker: String? = null,
        audioMs: Long? = null,
        words: List<WordStamp>? = null
    ) = TranscriptSegment(
        timestampMs = 0L, text = text, speaker = speaker,
        audioMs = audioMs, words = words
    )

    // --- MeetingStats -------------------------------------------------------

    @Test
    fun statsNullForTinyMeetings() {
        assertNull(MeetingStats.compute(listOf(seg("hi", "Ana"))))
    }

    @Test
    fun statsSharesSumAndOrder() {
        val long = "word ".repeat(120).trim() // ~10 min budget-capped chunks
        val segments = mutableListOf<TranscriptSegment>()
        repeat(6) { segments.add(seg(long, "Ana")) }
        repeat(3) { segments.add(seg(long, "Ben")) }
        val stats = MeetingStats.compute(segments, minSegments = 3, minTotalMs = 1_000L)
        assertNotNull(stats)
        stats!!
        assertEquals("Ana", stats.shares.first().name)
        assertTrue(stats.shares.first().percent > stats.shares.last().percent)
        assertTrue(stats.shares.sumOf { it.percent } in 90..100)
        assertEquals(2, stats.shares.size)
    }

    @Test
    fun statsTurnsAndMonologue() {
        val text = "word ".repeat(60).trim()
        val segments = listOf(
            seg(text, "Ana"), seg(text, "Ana"),
            seg(text, "Ben"),
            seg(text, "Ana")
        )
        val stats = MeetingStats.compute(segments, minSegments = 3, minTotalMs = 1_000L)!!
        val ana = stats.shares.first { it.name == "Ana" }
        assertEquals(2, ana.turns) // two runs of Ana
        assertEquals("Ana", stats.longestMonologueSpeaker)
        assertTrue(stats.longestMonologueMs > 0)
    }

    @Test
    fun statsQuestionsAndPace() {
        val text = "are we done? maybe not? " + "word ".repeat(40)
        val stats = MeetingStats.compute(
            List(6) { seg(text, "Ana") }, minSegments = 3, minTotalMs = 1_000L
        )!!
        assertEquals(12, stats.questionCount)
        assertTrue(stats.wordsPerMinute in 60..260)
    }

    @Test
    fun statsUnattributedSortsLast() {
        val text = "word ".repeat(60).trim()
        val segments = listOf(
            seg(text), seg(text), seg(text), seg(text),
            seg(text, "Ana"), seg(text, "Ana")
        )
        val stats = MeetingStats.compute(segments, minSegments = 3, minTotalMs = 1_000L)!!
        assertEquals(MeetingStats.UNATTRIBUTED, stats.shares.last().name)
        assertNull(stats.longestMonologueSpeaker) // longest run is unattributed
    }

    // --- SpeechSpans --------------------------------------------------------

    @Test
    fun spansMergeCloseSegments() {
        val words1 = listOf(WordStamp(0, "a"), WordStamp(900, "b"))
        val words2 = listOf(WordStamp(0, "c"), WordStamp(700, "d"))
        val spans = SpeechSpans.build(
            listOf(
                seg("a b", audioMs = 0, words = words1),
                seg("c d", audioMs = 1_900, words = words2) // 200ms gap: merges
            )
        )
        assertEquals(1, spans.size)
        assertEquals(0L, spans[0].startMs)
        assertTrue(spans[0].endMs >= 3_400)
    }

    @Test
    fun spansIgnoreSegmentsWithoutAudio() {
        assertTrue(SpeechSpans.build(listOf(seg("no audio"))).isEmpty())
    }

    @Test
    fun skipTargetJumpsLongGapsOnly() {
        val spans = listOf(
            SpeechSpans.Span(0, 5_000),
            SpeechSpans.Span(15_000, 20_000),
            SpeechSpans.Span(21_000, 30_000)
        )
        // Inside speech: no skip.
        assertNull(SpeechSpans.skipTarget(2_000, spans))
        // In the 10s gap: jump near the next span.
        assertEquals(14_750L, SpeechSpans.skipTarget(6_000, spans))
        // In the 1s gap (under threshold): no skip.
        assertNull(SpeechSpans.skipTarget(20_500, spans))
        // Past the end: no skip.
        assertNull(SpeechSpans.skipTarget(31_000, spans))
        // Almost at the target already: no pointless 100ms jump.
        assertNull(SpeechSpans.skipTarget(14_800, spans))
    }

    @Test
    fun skipTargetHandlesLeadingSilence() {
        val spans = listOf(SpeechSpans.Span(8_000, 12_000))
        assertEquals(7_750L, SpeechSpans.skipTarget(0, spans))
    }

    // --- Vocab --------------------------------------------------------------

    @Test
    fun vocabBuildsGlossaryPrompt() {
        assertEquals(
            "Glossary: Kubernetes, Anthropic, OKR.",
            Vocab.promptFor("Kubernetes, Anthropic\nOKR")
        )
        assertNull(Vocab.promptFor("   "))
        assertNull(Vocab.promptFor(",,;\n"))
    }

    @Test
    fun vocabDedupesAndCaps() {
        assertEquals(
            "Glossary: Miro.",
            Vocab.promptFor("Miro, miro, MIRO")
        )
        val long = (1..300).joinToString(", ") { "term$it" }
        val prompt = Vocab.promptFor(long)!!
        assertTrue(prompt.length <= 600)
        assertTrue(prompt.endsWith("."))
    }

    @Test
    fun vocabPresetMergeKeepsUserTermsFirstAndDedupes() {
        val merged = Vocab.withPreset(
            "Dr. Okafor, metformin", listOf("metformin", "lisinopril")
        )
        assertEquals("Dr. Okafor, metformin, lisinopril", merged)
        // Case-insensitive: user's casing wins, preset duplicate dropped.
        assertEquals(
            "METFORMIN, lisinopril",
            Vocab.withPreset("METFORMIN", listOf("metformin", "lisinopril"))
        )
    }

    @Test
    fun vocabPresetMergeIsIdempotent() {
        val once = Vocab.withPreset("", Vocab.LEGAL_PRESET)
        assertEquals(once, Vocab.withPreset(once, Vocab.LEGAL_PRESET))
    }

    @Test
    fun vocabPresetsAreCompactAndUnique() {
        for (preset in listOf(Vocab.MEDICAL_PRESET, Vocab.LEGAL_PRESET)) {
            assertEquals(preset.size, preset.map { it.lowercase() }.toSet().size)
            // Leave prompt-budget room for the user's own names/jargon.
            assertTrue(preset.joinToString(", ").length < 450)
            assertTrue(Vocab.promptFor(preset.joinToString(", "))!!.length <= 600)
        }
    }

    // --- TaskExport ---------------------------------------------------------

    @Test
    fun textChecklistFormat() {
        val out = TaskExport.text(
            listOf(
                TaskExport.Item("Send deck", "Ana", null, "Weekly sync"),
                TaskExport.Item("Book room", null, null, null)
            )
        )
        assertEquals("- [ ] Send deck — Ana (Weekly sync)\n- [ ] Book room", out)
    }

    @Test
    fun icsStructureAndEscaping() {
        val ics = TaskExport.ics(
            listOf(
                TaskExport.Item("Fix a;b, c\nnewline", "Ana", 1_750_000_000_000L, "Sync")
            ),
            nowMs = 1_700_000_000_000L
        )
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
        assertTrue(ics.contains("BEGIN:VTODO"))
        assertTrue(ics.contains("SUMMARY:Fix a\\;b\\, c\\nnewline (Ana)"))
        assertTrue(ics.contains("DUE:2025"))
        assertTrue(ics.contains("DTSTAMP:20231114T"))
        assertTrue(ics.contains("STATUS:NEEDS-ACTION"))
        assertFalse(ics.contains("\n\n"))
    }

    @Test
    fun icsFoldsLongLines() {
        val longTask = "t".repeat(300)
        val ics = TaskExport.ics(
            listOf(TaskExport.Item(longTask, null, null, null)), 0L
        )
        for (line in ics.split("\r\n")) {
            assertTrue("line too long: ${line.length}", line.length <= 74)
        }
        // Folded content reassembles to the original.
        val unfolded = ics.replace("\r\n ", "")
        assertTrue(unfolded.contains("SUMMARY:$longTask"))
    }

    @Test
    fun icsFoldCountsOctetsAndNeverSplitsSurrogates() {
        val emoji = "😀" // 😀: 2 UTF-16 chars, 4 UTF-8 octets
        val line = "SUMMARY:" + emoji.repeat(60)
        val folded = TaskExport.fold(line)
        for (part in folded.split("\r\n")) {
            assertTrue(
                "part is ${part.toByteArray(Charsets.UTF_8).size} octets",
                part.toByteArray(Charsets.UTF_8).size <= 74
            )
        }
        // Unfolding restores the original exactly — no corruption.
        assertEquals(line, folded.replace("\r\n ", ""))
        val chars = folded.toCharArray()
        for (i in chars.indices) {
            if (Character.isHighSurrogate(chars[i])) {
                assertTrue(
                    "lone high surrogate at $i",
                    i + 1 < chars.size && Character.isLowSurrogate(chars[i + 1])
                )
            }
        }
    }

    @Test
    fun icsOmitsDueWhenAbsent() {
        val ics = TaskExport.ics(
            listOf(TaskExport.Item("No due", null, null, null)), 0L
        )
        assertFalse(ics.contains("DUE:"))
    }
}
