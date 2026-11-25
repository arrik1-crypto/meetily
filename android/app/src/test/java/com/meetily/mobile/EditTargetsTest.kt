package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.EditTargets
import com.meetily.mobile.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The meeting screen's dialogs on a line or an action item act on the entry
 * they opened on, found again when the user confirms, never on whatever has
 * moved into its position meanwhile.
 */
class EditTargetsTest {

    private fun line(ms: Long, text: String, speaker: String? = null) =
        TranscriptSegment(timestampMs = ms, text = text, speaker = speaker, audioMs = ms)

    @Test
    fun aLineStillInPlaceIsFoundWhereItWas() {
        val lines = mutableListOf(line(0, "a"), line(1_000, "b"), line(2_000, "c"))
        val target = lines[1]
        assertEquals(1, EditTargets.line(lines, 1, target))
    }

    /** Remove a passage deletes lines before the one a dialog is open on. */
    @Test
    fun aLineMovedUpByADeletionIsFollowed() {
        val lines = mutableListOf(
            line(0, "a"), line(1_000, "b"), line(2_000, "c"), line(3_000, "d"), line(4_000, "e")
        )
        val target = lines[4]
        val kept = listOf(lines[0], lines[3], lines[4])
        lines.clear()
        lines.addAll(kept)
        // Position 4 does not exist any more and position 2 is the line.
        assertEquals(2, EditTargets.line(lines, 4, target))
    }

    @Test
    fun theLineNowAtTheOldPositionIsNeverTaken() {
        val lines = mutableListOf(line(0, "a"), line(1_000, "b"), line(2_000, "c"), line(3_000, "d"))
        val target = lines[1]
        lines.removeAt(1)
        // "c" now sits at 1; the line the dialog opened on is gone.
        assertEquals(-1, EditTargets.line(lines, 1, target))
    }

    /** A sync re-reads the stored lines: equal, but new objects. */
    @Test
    fun anEqualLineReadAgainIsFound() {
        val target = line(1_000, "b")
        val reread = listOf(line(0, "a"), line(1_000, "b"), line(2_000, "c"))
        assertEquals(1, EditTargets.line(reread, 1, target))
    }

    @Test
    fun aLineChangedMeanwhileIsNotTaken() {
        val target = line(1_000, "b")
        val trimmed = listOf(line(0, "a"), line(1_000, "b, trimmed"), line(2_000, "c"))
        assertEquals(-1, EditTargets.line(trimmed, 1, target))
        val tagged = listOf(line(0, "a"), line(1_000, "b", speaker = "Ana"), line(2_000, "c"))
        assertEquals(-1, EditTargets.line(tagged, 1, target))
    }

    @Test
    fun ofTwoEqualLinesTheNearerOneIsTaken() {
        val repeated = line(5_000, "Yes.")
        val lines = listOf(repeated.copy(), line(6_000, "x"), line(7_000, "y"), repeated.copy())
        assertEquals(3, EditTargets.line(lines, 2, repeated))
        assertEquals(0, EditTargets.line(lines, 1, repeated))
    }

    @Test
    fun anEmptiedTranscriptFindsNothing() {
        assertEquals(-1, EditTargets.line(emptyList(), 0, line(0, "a")))
    }

    /** A summary run lands and replaces the action items under an open dialog. */
    @Test
    fun anActionItemIsFoundInTheNewListByItsTask() {
        val target = ActionItem(task = "Send the deck", owner = "Ada")
        val replaced = listOf(
            ActionItem(task = "Book the room"),
            ActionItem(task = "Draft the memo"),
            ActionItem(task = "Send the deck", owner = "Ada", done = true)
        )
        assertEquals(2, EditTargets.actionItem(replaced, 0, target))
    }

    @Test
    fun anActionItemNoLongerListedIsNotReplacedByTheOneInItsPlace() {
        val target = ActionItem(task = "Send the deck")
        val replaced = listOf(ActionItem(task = "Book the room"), ActionItem(task = "Draft the memo"))
        assertEquals(-1, EditTargets.actionItem(replaced, 0, target))
    }

    @Test
    fun anUnchangedActionItemListKeepsTheIndex() {
        val items = listOf(ActionItem(task = "A"), ActionItem(task = "B"))
        assertEquals(1, EditTargets.actionItem(items, 1, items[1]))
    }

    @Test
    fun theRecordedMeetingsLinesAreTheRecordingsOnly() {
        assertTrue(owned(recording = true, recordingId = "m1"))
        assertFalse(owned(recording = true, recordingId = "other"))
        // A marker left without a running recording does not count.
        assertFalse(owned(recording = false, recordingId = "m1"))
    }

    @Test
    fun aTranscriptionFillingTheMeetingOwnsItsLines() {
        assertTrue(owned(importing = true, importId = "m1"))
        assertFalse(owned(importing = true, importId = "other"))
        assertFalse(owned(importing = false, importId = "m1"))
    }

    /** An accuracy check stages its words for review; the lines stay the user's. */
    @Test
    fun aStagedAccuracyCheckDoesNotOwnTheLines() {
        assertFalse(owned(importing = true, importId = "m1", stagesOnly = true))
    }

    private fun owned(
        recording: Boolean = false,
        recordingId: String? = null,
        importing: Boolean = false,
        importId: String? = null,
        stagesOnly: Boolean = false
    ) = EditTargets.linesOwnedByJob("m1", recording, recordingId, importing, importId, stagesOnly)
}
