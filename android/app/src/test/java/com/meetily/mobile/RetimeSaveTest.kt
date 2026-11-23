package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordAlign
import com.meetily.mobile.data.WordRealign
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.data.WordTimes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A re-timing run (AlignService) saves each pass's measured timings into the
 * stored meeting while the meeting screen holds its own copy and saves it
 * through its merge (MeetingMerge.mergeScreenEdits). An edit on the screen
 * after a pass was saved, but before the screen took that pass in, used to
 * write the screen's lines back whole and put the pass's lines back to
 * their estimated timings; a screen closed before the run ended never
 * repaired them. These drive the real merge, WordAlign.apply, the edit
 * rules and the JSON round trip the way MeetingStore.mutateIf and
 * syncScreenCopy, and MeetingDetailActivity's sync, onRetimed and edits do.
 */
class RetimeSaveTest {

    /** MeetingStore: one JSON file, with its load, merge and save steps. */
    private class Store(initial: Meeting) {
        private var json: String = initial.toJson().toString()

        fun load(): Meeting = Meeting.fromJson(JSONObject(json))

        fun save(m: Meeting) {
            json = m.toJson().toString()
        }

        /** MeetingStore.mutateIf. */
        fun mutateIf(block: (Meeting) -> Boolean) {
            val stored = load()
            if (block(stored)) save(stored)
        }

        /** MeetingStore.syncScreenCopy. */
        fun syncScreenCopy(base: Meeting, ours: Meeting, mark: Long): Meeting {
            val onDisk = load()
            val merged = MeetingMerge.mergeScreenEdits(base, ours, onDisk, mark)
            if (merged != onDisk) save(merged)
            return merged
        }
    }

    /** The meeting screen's copy and its sync (the transcript part of adoptSynced). */
    private class Screen(private val store: Store) {
        val m: Meeting = store.load()
        private var base: Meeting = MeetingMerge.snapshot(m)
        private var seen: Long = MeetingMerge.highWaterMs(m.segments)
        var alive = true

        fun sync() {
            val ours = MeetingMerge.snapshot(m)
            val merged = store.syncScreenCopy(base, ours, seen)
            if (m.segments == ours.segments && m.segments != merged.segments) {
                m.segments.clear()
                m.segments.addAll(merged.segments)
            }
            if (m.segments == merged.segments) seen = maxOf(seen, MeetingMerge.highWaterMs(m.segments))
            base = merged
        }

        /** Edit text's OK, then its save. */
        fun edit(index: Int, newText: String) {
            val old = m.segments[index]
            val words = WordRealign.realign(old.text, old.words, newText)
            m.segments[index] = old.copy(
                text = newText,
                words = words,
                wordTimes = WordTimes.afterEdit(old.wordTimes, old.words, words)
            )
            sync()
        }

        /** A long press, then its save. */
        fun highlight(index: Int) {
            m.segments[index] = m.segments[index].copy(highlighted = !m.segments[index].highlighted)
            sync()
        }

        /** onRetimed, from AlignUi.onAlignSaved or onAlignDone. */
        fun retimed(results: List<WordAlign.Retimed>) {
            if (!alive) return
            if (WordAlign.apply(m.segments, results) == 0) return
            sync()
        }
    }

    private fun estimated(stamp: Long, text: String) = TranscriptSegment(
        stamp, text, audioMs = stamp * 40,
        words = text.split(' ').mapIndexed { i, w -> WordStamp(i * 300L, w) },
        wordTimes = WordTimes.ESTIMATED
    )

    private fun meeting() = Meeting(
        id = "m1", title = "Standup", createdAtMs = 0L,
        segments = mutableListOf(
            estimated(1_000L, "alpha one line"),
            estimated(2_000L, "bravo two line"),
            estimated(3_000L, "charlie three line")
        )
    )

    /** What a run measures for a line, as AlignService.align hands it back. */
    private fun measured(segment: TranscriptSegment) = WordAlign.Retimed(
        WordAlign.target(segment)!!,
        segment.text.split(' ').mapIndexed { i, w -> WordStamp(120L + i * 210L, w) }
    )

    /** AlignService.save. */
    private fun savePass(store: Store, results: List<WordAlign.Retimed>): Int {
        var count = 0
        store.mutateIf { stored ->
            count = WordAlign.apply(stored.segments, results)
            count > 0
        }
        return count
    }

    @Test
    fun aPassSavedBeforeAnEditElsewhereOutlivesTheScreensSaveAndTheScreenClosing() {
        val store = Store(meeting())
        val screen = Screen(store)
        // AlignUi.start: the screen's edits are flushed before the run reads.
        screen.sync()
        val read = store.load()
        val first = measured(read.segments[0])
        val second = measured(read.segments[1])

        // Pass 1 is saved; the screen has not taken it in yet.
        assertEquals(1, savePass(store, listOf(first)))
        // A typo fixed on another line: the screen's lines are saved whole.
        screen.edit(2, "charlie three lines")
        val afterEdit = store.load().segments
        assertEquals(WordTimes.ALIGNED, afterEdit[0].wordTimes)
        assertEquals(first.words, afterEdit[0].words)
        assertEquals("charlie three lines", afterEdit[2].text)
        // The screen's copy took the stored timings with that save.
        assertEquals(WordTimes.ALIGNED, screen.m.segments[0].wordTimes)

        // Back to the home list before the run ends: nobody takes onAlignDone.
        screen.alive = false
        assertEquals(1, savePass(store, listOf(second)))

        val end = store.load().segments
        assertEquals(WordTimes.ALIGNED, end[0].wordTimes)
        assertEquals(first.words, end[0].words)
        assertEquals(WordTimes.ALIGNED, end[1].wordTimes)
        assertEquals("charlie three lines", end[2].text)
        assertEquals(WordTimes.ESTIMATED, end[2].wordTimes)
        // Only the edited line is offered again.
        assertEquals(listOf(2), WordAlign.linesToRetime(end))
    }

    @Test
    fun aHighlightElsewhereKeepsALinesMeasuredTimingsAndTheHighlight() {
        val base = meeting()
        val disk = MeetingMerge.snapshot(base)
        val result = measured(base.segments[0])
        assertEquals(1, WordAlign.apply(disk.segments, listOf(result)))
        val ours = MeetingMerge.snapshot(base).apply {
            segments[1] = segments[1].copy(highlighted = true)
        }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, MeetingMerge.highWaterMs(base.segments))
        assertEquals(WordTimes.ALIGNED, merged.segments[0].wordTimes)
        assertEquals(result.words, merged.segments[0].words)
        assertTrue(merged.segments[1].highlighted)
        assertEquals(WordTimes.ESTIMATED, merged.segments[1].wordTimes)
    }

    @Test
    fun aStaleScreenLineKeepsMeasuredTimingsWhenTheBaseAlreadyHasThem() {
        // The screen edited during a sync, so it kept its own (stale) lines
        // while its base became the merged copy with the measured line.
        val stale = meeting()
        val disk = MeetingMerge.snapshot(stale)
        val result = measured(stale.segments[0])
        WordAlign.apply(disk.segments, listOf(result))
        val base = MeetingMerge.snapshot(disk)
        val ours = MeetingMerge.snapshot(stale).apply {
            segments[1] = segments[1].copy(speaker = "Dana")
        }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, MeetingMerge.highWaterMs(base.segments))
        assertEquals(WordTimes.ALIGNED, merged.segments[0].wordTimes)
        assertEquals(result.words, merged.segments[0].words)
        assertEquals("Dana", merged.segments[1].speaker)
    }

    @Test
    fun aLineWhoseWordingTheScreenChangedKeepsItsEdit() {
        val base = meeting()
        val disk = MeetingMerge.snapshot(base)
        WordAlign.apply(disk.segments, listOf(measured(base.segments[0])))
        val ours = MeetingMerge.snapshot(base).apply {
            segments[0] = segments[0].copy(text = "alpha one lime")
        }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, MeetingMerge.highWaterMs(base.segments))
        assertEquals("alpha one lime", merged.segments[0].text)
        assertEquals(WordTimes.ESTIMATED, merged.segments[0].wordTimes)
        assertEquals(base.segments[0].words, merged.segments[0].words)
    }

    @Test
    fun aLineWithNoTimingsTakesTheMeasuredOnesAndOthersAreUntouched() {
        val base = Meeting(
            id = "m2", title = "T", createdAtMs = 0L,
            segments = mutableListOf(
                TranscriptSegment(1_000L, "no timings here", audioMs = 0L),
                TranscriptSegment(2_000L, "recogniser timed", audioMs = 5_000L, words = listOf(WordStamp(0, "recogniser"), WordStamp(400, "timed")))
            )
        )
        val disk = MeetingMerge.snapshot(base)
        val result = measured(base.segments[0])
        WordAlign.apply(disk.segments, listOf(result))
        val out = MeetingMerge.snapshot(base).segments
        assertEquals(1, MeetingMerge.keepMeasuredTimings(out, disk.segments))
        assertEquals(result.words, out[0].words)
        assertEquals(WordTimes.ALIGNED, out[0].wordTimes)
        assertEquals(base.segments[1], out[1])
        // Nothing measured on disk: nothing changes.
        val untouched = MeetingMerge.snapshot(base).segments
        assertEquals(0, MeetingMerge.keepMeasuredTimings(untouched, base.segments))
        assertEquals(base.segments, untouched)
    }

    @Test
    fun aScreenThatTookAPassAtOnceShowsItAndKeepsItThroughAHighlight() {
        val store = Store(meeting())
        val screen = Screen(store)
        screen.sync()
        val first = measured(store.load().segments[0])
        savePass(store, listOf(first))
        // AlignUi.onAlignSaved -> MeetingDetailActivity.onRetimed.
        screen.retimed(listOf(first))
        assertEquals(WordTimes.ALIGNED, screen.m.segments[0].wordTimes)
        screen.highlight(2)
        val stored = store.load().segments
        assertEquals(first.words, stored[0].words)
        assertEquals(WordTimes.ALIGNED, stored[0].wordTimes)
        assertTrue(stored[2].highlighted)
        // The end-of-run hand-off applies them again: nothing changes.
        assertEquals(0, WordAlign.apply(screen.m.segments, listOf(first)))
    }

    @Test
    fun theRunsLastSaveMeasuresAgainALineAWriterPutBackAndWritesNothingOtherwise() {
        val store = Store(meeting())
        val read = store.load()
        val results = listOf(measured(read.segments[0]), measured(read.segments[1]))
        assertEquals(2, savePass(store, results))
        // Any whole-copy writer that put line 0's old timings back.
        store.save(read.also { it.segments[1] = store.load().segments[1] })
        assertEquals(WordTimes.ESTIMATED, store.load().segments[0].wordTimes)
        assertEquals(1, savePass(store, results))
        assertEquals(WordTimes.ALIGNED, store.load().segments[0].wordTimes)
        assertEquals(0, savePass(store, results))
        assertFalse(WordAlign.needsRetime(store.load().segments[0]))
    }
}
