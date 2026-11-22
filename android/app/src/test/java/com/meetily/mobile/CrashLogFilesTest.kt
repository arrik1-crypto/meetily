package com.meetily.mobile

import com.meetily.mobile.diag.CrashLog
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashLogFilesTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("crashlogs").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun touch(name: String) = File(dir, name).apply { writeText(name) }

    @Test
    fun onlyTextReportsCountAndTheyListOldestFirst() {
        touch("crash-1732000000300-exit.tombstone1.pb")
        touch("crash-1732000000300-exit.txt")
        touch("crash-1732000000100.txt")
        touch("crash-1732000000200.txt")
        touch("notes.tmp")
        assertEquals(
            listOf(
                "crash-1732000000100.txt",
                "crash-1732000000200.txt",
                "crash-1732000000300-exit.txt"
            ),
            CrashLog.reports(dir).map { it.name }
        )
    }

    @Test
    fun pruneKeepsRoomForOneMoreAndTakesAttachmentsAlong() {
        touch("crash-1732000000100-exit.txt")
        touch("crash-1732000000100-exit.tombstone1.pb")
        touch("crash-1732000000200.txt")
        touch("crash-1732000000300-exit.txt")
        touch("crash-1732000000300-exit.tombstone1.pb")
        touch("crash-1732000000300-exit.tombstone2.pb")
        touch("stray.bin")
        CrashLog.prune(dir)
        assertEquals(
            setOf(
                "crash-1732000000200.txt",
                "crash-1732000000300-exit.txt",
                "crash-1732000000300-exit.tombstone1.pb",
                "crash-1732000000300-exit.tombstone2.pb"
            ),
            dir.list()!!.toSet()
        )
    }

    @Test
    fun exitReportsAreToldApartByName() {
        assertTrue(CrashLog.isExitReport(File("crash-1732000000300-exit.txt")))
        assertFalse(CrashLog.isExitReport(File("crash-1732000000300.txt")))
        assertFalse(CrashLog.isExitReport(File("crash-1732000000300-exit.tombstone1.pb")))
        val base = CrashLog.newExitReportBase()
        assertTrue(base, base.matches(Regex("crash-\\d{13}-exit")))
        assertFalse(base.contains('.'))
    }
}
