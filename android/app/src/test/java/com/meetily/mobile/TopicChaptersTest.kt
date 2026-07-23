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
