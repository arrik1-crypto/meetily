package com.meetily.mobile

import com.meetily.mobile.data.Chapter
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.summarize.TopicChapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicChaptersTest {

    private fun segment(atSec: Long, text: String) =
        TranscriptSegment(timestampMs = atSec * 1000, text = text)

    @Test
    fun splitsAtLongGapsAndTitlesByKeywords() {
        val segments = buildList {
            // Section 1: budget talk, one line every ~20 s.
            for (i in 0 until 6) {
                add(segment(i * 20L, "the budget forecast needs another budget revision pass"))
            }
            // Two-minute silence, then section 2: hiring talk.
            for (i in 0 until 6) {
                add(segment(240 + i * 20L, "hiring pipeline and the interview loop for hiring"))
            }
        }
        val chapters = TopicChapters.buildLocal(segments)
        assertEquals(2, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(240_000L, chapters[1].startMs)
        assertTrue(
            "expected a budget-ish title, got ${chapters[0].title}",
            chapters[0].title.contains("budget", ignoreCase = true)
        )
        assertTrue(
            "expected a hiring-ish title, got ${chapters[1].title}",
            chapters[1].title.contains("hiring", ignoreCase = true)
        )
    }

    /** Two German sections, budget then travel, with the usual particles in both. */
    private fun germanMeeting(): List<TranscriptSegment> = buildList {
        listOf(
            "Aber das Budget ist noch nicht fertig, aber die Zahlen fehlen noch.",
            "Das Budget wurde noch nicht freigegeben, aber Anna prüft es.",
            "Nicht alles im Budget ist klar, aber wir sind noch dran.",
            "Aber die Kosten sind noch nicht vollständig, nicht einmal die Miete.",
            "Noch ist das Budget nicht abgeschlossen, aber bald.",
            "Wir müssen das Budget aber noch nicht heute beschließen."
        ).forEachIndexed { i, text -> add(segment(i * 20L, text)) }
        listOf(
            "Die Reise nach Berlin ist noch nicht gebucht, aber geplant.",
            "Aber die Reise kostet noch nicht so viel, nicht wahr?",
            "Für die Reise brauchen wir noch Hotels, aber nicht teure.",
            "Nicht jeder kommt mit auf die Reise, aber die meisten noch.",
            "Die Reise beginnt noch nicht im Mai, aber im Juni.",
            "Aber die Reise wird noch nicht bezahlt, nicht vor Juli."
        ).forEachIndexed { i, text -> add(segment(240 + i * 20L, text)) }
    }

    private fun titleWords(chapters: List<Chapter>): List<String> =
        chapters.flatMap { it.title.lowercase().split(" · ") }

    @Test
    fun anotherLanguagesParticlesDoNotTitleItsChapters() {
        // The English stopword list knows nothing of German: "aber", "noch"
        // and "nicht" are the commonest words in both sections.
        val unguarded = TopicChapters.buildLocal(germanMeeting())
        assertTrue(
            "expected a particle in ${unguarded.map { it.title }}",
            titleWords(unguarded).any { it in setOf("aber", "noch", "nicht") }
        )
        val chapters = TopicChapters.buildLocal(germanMeeting(), englishHeuristics = false)
        assertEquals(2, chapters.size)
        assertEquals(listOf(0L, 240_000L), chapters.map { it.startMs })
        for (particle in listOf("aber", "noch", "nicht")) {
            assertTrue(
                "$particle in ${chapters.map { it.title }}",
                particle !in titleWords(chapters)
            )
        }
        assertTrue(chapters[0].title.contains("budget", ignoreCase = true))
        assertTrue(chapters[1].title.contains("reise", ignoreCase = true))
    }

    @Test
    fun englishChaptersAreUnchangedByTheDefault() {
        val segments = (0 until 6).map { segment(it * 20L, "budget forecast and the revision pass") } +
            (0 until 6).map { segment(240 + it * 20L, "hiring pipeline and the interview loop") }
        assertEquals(
            TopicChapters.buildLocal(segments),
            TopicChapters.buildLocal(segments, englishHeuristics = true)
        )
    }

    @Test
    fun shortTranscriptsProduceNoChapters() {
        val segments = (0 until 5).map { segment(it * 10L, "quick chat line $it") }
        assertTrue(TopicChapters.buildLocal(segments).isEmpty())
    }

    @Test
    fun continuousTalkWithoutSeamsProducesNoChapters() {
        // 20 lines, 10 s apart — no gap ever crosses a boundary threshold.
        val segments = (0 until 20).map { segment(it * 10L, "steady discussion about the roadmap") }
        assertTrue(TopicChapters.buildLocal(segments).isEmpty())
    }

    @Test
    fun tinyTrailingSectionFoldsIntoPredecessor() {
        val segments = buildList {
            for (i in 0 until 8) add(segment(i * 20L, "design review of the mockups"))
            // Long gap then only two stray lines: a pause, not a topic.
            add(segment(500L, "ok"))
            add(segment(505L, "bye"))
        }
        assertTrue(TopicChapters.buildLocal(segments).isEmpty())
    }

    @Test
    fun chaptersSurviveMeetingJsonRoundTrip() {
        val meeting = Meeting(id = "m1", title = "T", createdAtMs = 1_000L)
        meeting.chapters.add(Chapter("Budget", 0L))
        meeting.chapters.add(Chapter("Hiring · Pipeline", 240_000L))
        val restored = Meeting.fromJson(meeting.toJson())
        assertEquals(meeting.chapters, restored.chapters)
    }
}
