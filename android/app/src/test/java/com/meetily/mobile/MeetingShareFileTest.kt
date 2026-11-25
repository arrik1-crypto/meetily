package com.meetily.mobile

import com.meetily.mobile.export.MeetingShareFile
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long meeting shared as a file is the whole meeting in plain text; it
 * must not stay in the cache after the share, Delete permanently or Remove
 * a passage.
 */
class MeetingShareFileTest {

    private val root: File = Files.createTempDirectory("shares").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun theFileKeepsItsTitleBasedNameInTheMeetingsFolder() {
        val file = MeetingShareFile.write(root, "m1", "Q3 planning: budget", "text")
        assertEquals("Q3_planning_budget.txt", file.name)
        assertEquals("m1", file.parentFile?.name)
        assertEquals("text", file.readText())
        assertEquals("meeting.txt", MeetingShareFile.fileName(""))
    }

    @Test
    fun aNewShareDeletesEveryEarlierOne() {
        val first = MeetingShareFile.write(root, "m1", "First", "one")
        // A share written by an earlier version, straight in the folder.
        val legacy = File(root, "Old_title.txt").apply { writeText("old") }
        val second = MeetingShareFile.write(root, "m2", "Second", "two")
        assertFalse(first.exists())
        assertFalse(legacy.exists())
        assertTrue(second.exists())
    }

    @Test
    fun deletingAMeetingsShareLeavesOthersAlone() {
        val mine = MeetingShareFile.write(root, "m1", "Mine", "one")
        val other = File(File(root, "m2").apply { mkdirs() }, "Other.txt").apply { writeText("two") }
        MeetingShareFile.deleteFor(root, "m1")
        assertFalse(mine.exists())
        assertFalse(File(root, "m1").exists())
        assertTrue(other.exists())
    }

    @Test
    fun anIdThatIsNotAPlainNameDeletesNothingOutsideIt() {
        val other = File(File(root, "m2").apply { mkdirs() }, "Other.txt").apply { writeText("two") }
        MeetingShareFile.deleteFor(File(root, "m2"), "..")
        MeetingShareFile.deleteFor(root, "../m2")
        assertTrue(other.exists())
    }

    @Test
    fun theSweepDeletesOnlySharesPastTheirTime() {
        val now = 10L * MeetingShareFile.MAX_AGE_MS
        val old = MeetingShareFile.write(root, "m1", "Old", "one")
        val oldFolder = old.parentFile!!
        val fresh = File(File(root, "m2").apply { mkdirs() }, "Fresh.txt").apply { writeText("two") }
        val legacy = File(root, "Legacy.txt").apply { writeText("three") }
        assertTrue(oldFolder.setLastModified(now - MeetingShareFile.MAX_AGE_MS - 1_000L))
        assertTrue(File(root, "m2").setLastModified(now - 60_000L))
        assertTrue(legacy.setLastModified(now - 2 * MeetingShareFile.MAX_AGE_MS))
        MeetingShareFile.sweep(root, now)
        assertFalse(old.exists())
        assertFalse(legacy.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun aMissingFolderIsNothingToSweep() {
        MeetingShareFile.sweep(File(root, "absent"), 1_000L)
        MeetingShareFile.deleteFor(File(root, "absent"), "m1")
    }

    @Test
    fun staleness() {
        val now = 100L * MeetingShareFile.MAX_AGE_MS
        assertFalse(MeetingShareFile.isStale(now - 1_000L, now))
        assertFalse(MeetingShareFile.isStale(now, now))
        assertTrue(MeetingShareFile.isStale(now - MeetingShareFile.MAX_AGE_MS, now))
        // Unknown, and a time only a clock set back since can give.
        assertTrue(MeetingShareFile.isStale(0L, now))
        assertTrue(MeetingShareFile.isStale(now + MeetingShareFile.MAX_AGE_MS, now))
        assertFalse(MeetingShareFile.isStale(now + 1_000L, now))
    }
}
