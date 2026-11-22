package com.meetily.mobile

import com.meetily.mobile.data.BulkActions
import com.meetily.mobile.data.BulkActions.Action
import com.meetily.mobile.data.BulkActions.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which selected meetings a bulk action reaches, and the selection bookkeeping. */
class BulkActionsTest {

    private fun item(
        id: String,
        hasAudio: Boolean = true,
        recording: Boolean = false,
        busy: Boolean = false,
        queued: Boolean = false
    ) = Item(id, hasAudio, recording, busy, queued)

    @Test
    fun deleteSkipsOnlyMeetingsBeingWritten() {
        val split = BulkActions.split(
            Action.DELETE,
            listOf(item("a"), item("rec", recording = true), item("job", busy = true), item("q", queued = true))
        )
        // A queued job checks that its meeting still exists when it runs.
        assertEquals(listOf("a", "q"), split.eligible)
        assertEquals(listOf("rec", "job"), split.skipped)
    }

    @Test
    fun removeAudioNeedsAudioAndNoJobWaitingForIt() {
        val split = BulkActions.split(
            Action.REMOVE_AUDIO,
            listOf(
                item("a"),
                item("none", hasAudio = false),
                item("rec", recording = true),
                item("job", busy = true),
                item("q", queued = true),
                item("b")
            )
        )
        assertEquals(listOf("a", "b"), split.eligible)
        assertEquals(listOf("none", "rec", "job", "q"), split.skipped)
    }

    @Test
    fun tagAndExportSkipOnlyTheRecording() {
        for (action in listOf(Action.ADD_TAG, Action.EXPORT)) {
            assertTrue(BulkActions.eligible(action, item("a", hasAudio = false, busy = true, queued = true)))
            assertFalse(BulkActions.eligible(action, item("rec", recording = true)))
        }
    }

    @Test
    fun anEmptySelectionSplitsIntoNothing() {
        val split = BulkActions.split(Action.DELETE, emptyList())
        assertTrue(split.eligible.isEmpty())
        assertTrue(split.skipped.isEmpty())
    }

    @Test
    fun tagsAreNormalised() {
        assertEquals("client-x", BulkActions.normalizeTag("  #client-x "))
        assertEquals("q3 planning", BulkActions.normalizeTag("q3   planning"))
        assertNull(BulkActions.normalizeTag("   "))
        assertNull(BulkActions.normalizeTag("#"))
        // Would split into two tags wherever tags are edited as text.
        assertNull(BulkActions.normalizeTag("a, b"))
        assertNull(BulkActions.normalizeTag("a;b"))
        assertEquals(40, BulkActions.normalizeTag("x".repeat(100))!!.length)
    }

    @Test
    fun addingATagTheMeetingHasChangesNothing() {
        assertEquals(listOf("a", "b"), BulkActions.withTag(listOf("a"), "b"))
        assertNull(BulkActions.withTag(listOf("Client-X"), "client-x"))
        assertEquals(listOf("x"), BulkActions.withTag(emptyList(), "x"))
    }

    @Test
    fun zipEntryNamesNeverCollide() {
        assertEquals("a.md", BulkActions.uniqueName("a.md", emptySet()))
        assertEquals("a-2.md", BulkActions.uniqueName("a.md", setOf("a.md")))
        assertEquals("a-3.md", BulkActions.uniqueName("a.md", setOf("a.md", "a-2.md")))
        assertEquals("notes-2", BulkActions.uniqueName("notes", setOf("notes")))
        // A leading dot is not an extension.
        assertEquals(".md-2", BulkActions.uniqueName(".md", setOf(".md")))
    }

    @Test
    fun toggleKeepsOrderAndFlips() {
        var selected: Set<String> = emptySet()
        selected = BulkActions.toggled(selected, "a")
        selected = BulkActions.toggled(selected, "b")
        selected = BulkActions.toggled(selected, "c")
        assertEquals(listOf("a", "b", "c"), selected.toList())
        selected = BulkActions.toggled(selected, "b")
        assertEquals(listOf("a", "c"), selected.toList())
    }

    @Test
    fun selectionFollowsWhatTheListShows() {
        val selected = linkedSetOf("a", "b", "c")
        assertEquals(listOf("a", "c"), BulkActions.pruned(selected, listOf("c", "a", "z")).toList())
        assertTrue(BulkActions.pruned(selected, emptyList()).isEmpty())
    }

    @Test
    fun selectAllSelectsTheViewOrClearsIt() {
        val visible = listOf("a", "b", "c")
        assertFalse(BulkActions.allSelected(setOf("a"), visible))
        assertFalse(BulkActions.allSelected(emptySet(), emptyList()))
        val all = BulkActions.selectAllToggled(setOf("b"), visible)
        assertEquals(setOf("a", "b", "c"), all)
        assertTrue(BulkActions.allSelected(all, visible))
        assertTrue(BulkActions.selectAllToggled(all, visible).isEmpty())
        // Something selected outside the view is left alone either way.
        assertEquals(setOf("x"), BulkActions.selectAllToggled(setOf("x", "a", "b", "c"), visible))
    }
}
