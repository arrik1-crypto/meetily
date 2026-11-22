package com.meetily.mobile

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import com.meetily.mobile.diag.ActiveNativeJobs
import com.meetily.mobile.diag.ExitInfoLogic
import com.meetily.mobile.diag.NativeJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitInfoLogicTest {

    private fun exit(
        ts: Long,
        reason: Int = ExitInfoLogic.REASON_CRASH_NATIVE,
        importance: Int = ExitInfoLogic.IMPORTANCE_FOREGROUND,
        description: String? = null,
        summary: ByteArray? = null,
        id: Int = 0
    ) = ExitInfoLogic.Exit(
        id = id,
        timestampMs = ts,
        reason = reason,
        status = 11,
        importance = importance,
        pssKb = 2_048,
        rssKb = 4_096,
        description = description,
        stateSummary = summary
    )

    // --- Reason mapping ------------------------------------------------

    @Test
    fun mirroredConstantsMatchThePlatform() {
        // Compile-time constants, so these read the SDK's values, not stubs.
        assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY, ExitInfoLogic.REASON_LOW_MEMORY)
        assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, ExitInfoLogic.REASON_CRASH_NATIVE)
        assertEquals(ApplicationExitInfo.REASON_ANR, ExitInfoLogic.REASON_ANR)
        assertEquals(
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ExitInfoLogic.REASON_EXCESSIVE_RESOURCE_USAGE
        )
        assertEquals(ApplicationExitInfo.REASON_OTHER, ExitInfoLogic.REASON_OTHER)
        assertEquals(ApplicationExitInfo.REASON_FREEZER, ExitInfoLogic.REASON_FREEZER)
        // The newest named reason at compileSdk 36; the limiter and anomaly
        // values come after it, which is why they are literals.
        assertEquals(16, ApplicationExitInfo.REASON_PACKAGE_UPDATED)
        assertEquals(17, ExitInfoLogic.REASON_MEMORY_LIMITER)
        assertEquals(18, ExitInfoLogic.REASON_ANOMALY)

        assertEquals(RunningAppProcessInfo.IMPORTANCE_FOREGROUND, ExitInfoLogic.IMPORTANCE_FOREGROUND)
        assertEquals(
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            ExitInfoLogic.IMPORTANCE_FOREGROUND_SERVICE
        )
        assertEquals(RunningAppProcessInfo.IMPORTANCE_VISIBLE, ExitInfoLogic.IMPORTANCE_VISIBLE)
        assertEquals(RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE, ExitInfoLogic.IMPORTANCE_PERCEPTIBLE)
        assertEquals(RunningAppProcessInfo.IMPORTANCE_SERVICE, ExitInfoLogic.IMPORTANCE_SERVICE)
        assertEquals(RunningAppProcessInfo.IMPORTANCE_CACHED, ExitInfoLogic.IMPORTANCE_CACHED)
        assertEquals(RunningAppProcessInfo.IMPORTANCE_GONE, ExitInfoLogic.IMPORTANCE_GONE)
    }

    @Test
    fun reasonNamesCoverTheRecordedReasons() {
        assertEquals("CRASH_NATIVE", ExitInfoLogic.reasonName(5))
        assertEquals("ANR", ExitInfoLogic.reasonName(6))
        assertEquals("LOW_MEMORY", ExitInfoLogic.reasonName(3))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", ExitInfoLogic.reasonName(9))
        assertEquals("FREEZER", ExitInfoLogic.reasonName(14))
        assertEquals("MEMORY_LIMITER", ExitInfoLogic.reasonName(17))
        assertEquals("ANOMALY", ExitInfoLogic.reasonName(18))
        assertEquals("REASON_99", ExitInfoLogic.reasonName(99))
        assertEquals(
            "Killed by Android's app memory limit",
            ExitInfoLogic.reasonPhrase(13, "MemoryLimiter:AnonSwap limit=512")
        )
        assertEquals("Process exit", ExitInfoLogic.reasonPhrase(13, "system update"))
    }

    @Test
    fun recordsNativeCrashesAnrsAndResourceKillsAtAnyImportance() {
        val always = listOf(5, 6, 9, 14, 17, 18)
        for (reason in always) {
            assertTrue(
                "reason $reason",
                ExitInfoLogic.isRecorded(reason, ExitInfoLogic.IMPORTANCE_CACHED, null, false)
            )
        }
    }

    @Test
    fun skipsJavaCrashesAndOrdinaryExits() {
        // 4 = Java crash, already written by CrashLog's handler.
        for (reason in listOf(0, 1, 2, 4, 7, 8, 10, 11, 12, 15, 16)) {
            assertFalse(
                "reason $reason",
                ExitInfoLogic.isRecorded(reason, ExitInfoLogic.IMPORTANCE_FOREGROUND, null, true)
            )
        }
    }

    @Test
    fun otherCountsOnlyForTheMemoryLimiter() {
        assertTrue(ExitInfoLogic.isRecorded(13, 100, "MemoryLimiter:AnonSwap pss=900MB", false))
        assertFalse(ExitInfoLogic.isRecorded(13, 100, "system update done", false))
        assertFalse(ExitInfoLogic.isRecorded(13, 100, null, false))
    }

    @Test
    fun lowMemoryCountsWhenNoticeableOrDuringNativeWork() {
        val lmk = ExitInfoLogic.REASON_LOW_MEMORY
        assertTrue(ExitInfoLogic.isRecorded(lmk, ExitInfoLogic.IMPORTANCE_FOREGROUND_SERVICE, null, false))
        assertTrue(ExitInfoLogic.isRecorded(lmk, ExitInfoLogic.IMPORTANCE_VISIBLE, null, false))
        assertTrue(ExitInfoLogic.isRecorded(lmk, ExitInfoLogic.IMPORTANCE_PERCEPTIBLE, null, false))
        // Cached and idle: Android reclaiming memory as usual.
        assertFalse(ExitInfoLogic.isRecorded(lmk, ExitInfoLogic.IMPORTANCE_CACHED, null, false))
        assertFalse(ExitInfoLogic.isRecorded(lmk, ExitInfoLogic.IMPORTANCE_SERVICE, null, false))
        // A model was loading in the background: worth a report.
        assertTrue(ExitInfoLogic.isRecorded(lmk, ExitInfoLogic.IMPORTANCE_SERVICE, null, true))
    }

    // --- Dedupe by timestamp -----------------------------------------

    @Test
    fun selectKeepsOnlyExitsNewerThanTheMarkerNewestFirst() {
        val exits = listOf(exit(300, id = 0), exit(100, id = 1), exit(200, id = 2))
        val chosen = ExitInfoLogic.select(exits, consumedTs = 150)
        assertEquals(listOf(300L, 200L), chosen.map { it.timestampMs })
        assertEquals(listOf(0, 2), chosen.map { it.id })
        // The marker itself is already consumed.
        assertEquals(listOf(300L), ExitInfoLogic.select(exits, 200).map { it.timestampMs })
    }

    @Test
    fun selectDropsUnrecordedReasonsAndCaps() {
        val exits = listOf(
            exit(500, reason = 10), // user swiped it away
            exit(400),
            exit(300, reason = ExitInfoLogic.REASON_ANR),
            exit(200, reason = 17),
            exit(100)
        )
        val chosen = ExitInfoLogic.select(exits, 0, max = 3)
        assertEquals(listOf(400L, 300L, 200L), chosen.map { it.timestampMs })
    }

    @Test
    fun eachExitIsRecordedOnce() {
        val exits = listOf(exit(900), exit(800, reason = 10), exit(700))
        val floor = ExitInfoLogic.consumedFloor(500, nowMs = 1_000)
        assertEquals(2, ExitInfoLogic.select(exits, floor).size)
        val stored = ExitInfoLogic.newestTimestamp(exits, floor)
        assertEquals(900L, stored)
        // Next launch: Android returns the same records, plus nothing new.
        assertTrue(ExitInfoLogic.select(exits, ExitInfoLogic.consumedFloor(stored, 2_000)).isEmpty())
        // A later exit is new again.
        val later = listOf(exit(1_500)) + exits
        assertEquals(listOf(1_500L), ExitInfoLogic.select(later, stored).map { it.timestampMs })
    }

    @Test
    fun markerCountsUnrecordedExitsAndNeverMovesBack() {
        assertEquals(800L, ExitInfoLogic.newestTimestamp(listOf(exit(800, reason = 10)), 100))
        assertEquals(900L, ExitInfoLogic.newestTimestamp(listOf(exit(800)), 900))
        assertEquals(900L, ExitInfoLogic.newestTimestamp(emptyList(), 900))
    }

    @Test
    fun firstScanLooksBackOneDayOnly() {
        val now = 10L * ExitInfoLogic.FIRST_RUN_WINDOW_MS
        val floor = ExitInfoLogic.consumedFloor(ExitInfoLogic.NO_MARKER, now)
        assertEquals(now - ExitInfoLogic.FIRST_RUN_WINDOW_MS, floor)
        val exits = listOf(exit(now - 1_000), exit(now - 3 * ExitInfoLogic.FIRST_RUN_WINDOW_MS))
        assertEquals(listOf(now - 1_000), ExitInfoLogic.select(exits, floor).map { it.timestampMs })
        assertEquals(1_234L, ExitInfoLogic.consumedFloor(1_234, now))
    }

    // --- Process state summary ----------------------------------------

    @Test
    fun summaryCarriesJobKeyAndRam() {
        val bytes = ExitInfoLogic.encodeSummary(
            listOf(NativeJob.TRANSCRIBE to "parakeet-tdt-v3", NativeJob.DIARISE to "eres2net"),
            8
        )
        assertEquals("ram=8;transcribe:parakeet-tdt-v3;diarise:eres2net", String(bytes, Charsets.US_ASCII))
    }

    @Test
    fun summaryNeverExceeds128Bytes() {
        val many = (1..20).map { NativeJob.SUMMARISE to "model-key-number-$it-with-a-long-tail" }
        val bytes = ExitInfoLogic.encodeSummary(many, 16)
        assertTrue("was ${bytes.size}", bytes.size <= ExitInfoLogic.SUMMARY_MAX_BYTES)
        val text = String(bytes, Charsets.US_ASCII)
        assertTrue(text, text.startsWith("ram=16;summarise:model-key-number-1-"))
        // What did not fit is counted, not silently lost.
        val dropped = text.substringAfterLast(";+").toInt()
        val kept = text.split(';').count { it.startsWith("summarise:") }
        assertEquals(20, kept + dropped)
    }

    @Test
    fun summaryCapHoldsForEveryLength() {
        for (count in 0..30) {
            for (keyLength in listOf(1, 10, 39, 40, 41, 200)) {
                val jobs = (0 until count).map { NativeJob.OCR to "k$it" + "x".repeat(keyLength) }
                val bytes = ExitInfoLogic.encodeSummary(jobs, 9_999)
                assertTrue("$count x $keyLength: ${bytes.size}", bytes.size <= 128)
                assertTrue(bytes.all { it.toInt() in 0x20..0x7e })
            }
        }
    }

    @Test
    fun summaryKeysAreReducedToNameCharacters() {
        val text = String(
            ExitInfoLogic.encodeSummary(listOf(NativeJob.OCR to "a b/c;d:é"), 4),
            Charsets.US_ASCII
        )
        assertEquals("ram=4;ocr:a_b_c_d__", text)
        assertEquals("?", ExitInfoLogic.sanitiseKey(""))
        assertEquals(ExitInfoLogic.SUMMARY_KEY_MAX, ExitInfoLogic.sanitiseKey("x".repeat(90)).length)
    }

    @Test
    fun repeatedJobsAppearOnce() {
        val text = String(
            ExitInfoLogic.encodeSummary(
                listOf(NativeJob.DIARISE to "eres2net", NativeJob.DIARISE to "eres2net"), 6
            ),
            Charsets.US_ASCII
        )
        assertEquals("ram=6;diarise:eres2net", text)
    }

    @Test
    fun activeJobsTrackBeginAndEnd() {
        val jobs = ActiveNativeJobs()
        assertNull(jobs.summary(8))
        val a = jobs.begin(NativeJob.TRANSCRIBE, "base.en")
        val b = jobs.begin(NativeJob.SUMMARISE, "qwen2.5-1.5b")
        assertTrue(a != 0 && b != 0 && a != b)
        assertEquals(
            "ram=8;transcribe:base.en;summarise:qwen2.5-1.5b",
            String(jobs.summary(8)!!, Charsets.US_ASCII)
        )
        assertTrue(jobs.end(a))
        assertFalse(jobs.end(a))
        assertEquals("ram=8;summarise:qwen2.5-1.5b", String(jobs.summary(8)!!, Charsets.US_ASCII))
        assertTrue(jobs.end(b))
        assertTrue(jobs.isEmpty)
        // Cleared, not left saying "idle".
        assertNull(jobs.summary(8))
    }

    @Test
    fun decodeSummaryShowsOnlyPrintableText() {
        assertNull(ExitInfoLogic.decodeSummary(null))
        assertNull(ExitInfoLogic.decodeSummary(ByteArray(0)))
        assertEquals("ram=8;ocr:x", ExitInfoLogic.decodeSummary("ram=8;ocr:x".toByteArray()))
        assertEquals("a?b", ExitInfoLogic.decodeSummary(byteArrayOf(0x61, 0x0a, 0x62)))
        assertEquals(128, ExitInfoLogic.decodeSummary(ByteArray(400) { 0x41 })!!.length)
    }

    @Test
    fun ramRoundsUpToWholeGigabytes() {
        val gib = 1L shl 30
        // MemTotal sits a little under the label: 8 GB phones report ~7.5 GiB.
        assertEquals(8, ExitInfoLogic.ramGb((7.5 * gib).toLong()))
        assertEquals(12, ExitInfoLogic.ramGb(12 * gib))
        assertEquals(12, ExitInfoLogic.ramGb(11 * gib + 300_000_000L))
        assertEquals(0, ExitInfoLogic.ramGb(0))
    }

    // --- Report text ----------------------------------------------------

    @Test
    fun traceHeadCapsLinesAndCharacters() {
        val lines = (1..250).asSequence().map { "line $it" }
        val head = ExitInfoLogic.traceHead(lines)
        assertEquals(200, head.lines().count { it.startsWith("line ") })
        assertTrue(head.contains("[trace cut at 200 lines]"))

        val wide = (1..50).asSequence().map { "y".repeat(900) }
        val capped = ExitInfoLogic.traceHead(wide, maxLines = 200, maxChars = 5_000)
        assertTrue(capped.length < 5_100)

        val short = ExitInfoLogic.traceHead(sequenceOf("a", "b"))
        assertEquals("a\nb\n", short)
    }

    /** An ART ANR dump shaped like a real one: a long runtime section first. */
    private fun anrFixture(statsLines: Int = 300, withMain: Boolean = true): List<String> {
        val out = mutableListOf(
            "Subject: Input dispatching timed out (com.meetily.mobile/.MainActivity)",
            "",
            "----- pid 4242 at 2026-11-01 10:00:00.000 -----",
            "Cmd line: com.meetily.mobile",
            "Build fingerprint: 'google/panther/panther:16/BP2A/1:user/release-keys'",
            "ABI: 'arm64'",
            "Build type: optimized",
            "Zygote loaded classes=21000 post zygote classes=900",
            "Dumping registered class loaders"
        )
        repeat(statsLines) { out += "Dumping cumulative Gc timings $it" }
        out += "suspend all histogram:\tSum: 1.2ms"
        out += "DALVIK THREADS (3):"
        out += "\"Signal Catcher\" daemon prio=10 tid=6 Runnable"
        repeat(20) { out += "  native: #$it pc 0000 /apex/com.android.art/lib64/libart.so" }
        out += ""
        if (withMain) {
            out += "\"main\" prio=5 tid=1 Native"
            out += "  | group=\"main\" sCount=1"
            out += "  at com.meetily.mobile.whisper.WhisperBridge.transcribe(Native method)"
            out += ""
        }
        out += "\"worker\" prio=5 tid=12 Waiting"
        out += "  at java.lang.Object.wait(Native method)"
        return out
    }

    @Test
    fun anrFocusKeepsTheMainThreadPastALongRuntimeSection() {
        val fixture = anrFixture()
        // The plain head of this dump ends before the main thread.
        val plain = ExitInfoLogic.traceHead(fixture.asSequence())
        assertFalse(plain.contains("\"main\" prio=5"))

        val focused = ExitInfoLogic.traceHead(ExitInfoLogic.anrFocus(fixture.asSequence()))
        assertTrue(focused, focused.contains("Subject: Input dispatching timed out"))
        assertTrue(focused, focused.contains("Cmd line: com.meetily.mobile"))
        assertTrue(focused, focused.contains("Build type: optimized"))
        assertTrue(focused, focused.contains("[303 lines of runtime statistics left out]"))
        assertTrue(focused, focused.contains("DALVIK THREADS (3):"))
        assertTrue(focused, focused.contains("[22 lines of other threads left out]"))
        assertTrue(focused, focused.contains("\"main\" prio=5 tid=1 Native"))
        assertTrue(focused, focused.contains("WhisperBridge.transcribe"))
        assertTrue(focused, focused.contains("\"worker\" prio=5"))
        assertFalse(focused, focused.contains("Signal Catcher"))
        assertFalse(focused, focused.contains("Gc timings"))
        assertFalse(focused, focused.contains("[trace cut"))
        // Still under the same cap.
        assertTrue(focused.lines().size <= ExitInfoLogic.ANR_TRACE_LINES + 2)
    }

    @Test
    fun anrFocusFallsBackWhenTheLayoutIsNotArts() {
        // No thread dump at all: the same as the plain head.
        val plainText = (1..300).map { "line $it" }
        assertEquals(
            ExitInfoLogic.traceHead(plainText.asSequence()),
            ExitInfoLogic.traceHead(ExitInfoLogic.anrFocus(plainText.asSequence()))
        )
        val short = listOf("Subject: x", "Build type: optimized", "Heap: 10% free")
        assertEquals(short, ExitInfoLogic.anrFocus(short.asSequence()).toList())

        // A dump with no main thread: the dump from its start.
        val noMain = ExitInfoLogic.traceHead(
            ExitInfoLogic.anrFocus(anrFixture(statsLines = 10, withMain = false).asSequence())
        )
        assertTrue(noMain, noMain.contains("[13 lines of runtime statistics left out]"))
        assertTrue(noMain, noMain.contains("DALVIK THREADS (3):\n\"Signal Catcher\""))
        assertTrue(noMain, noMain.contains("\"worker\" prio=5"))
    }

    @Test
    fun renderedExitNamesWhatHappenedAndWhatWasRunning() {
        val e = exit(
            ts = 0,
            reason = ExitInfoLogic.REASON_LOW_MEMORY,
            importance = ExitInfoLogic.IMPORTANCE_FOREGROUND_SERVICE,
            description = "lmk",
            summary = "ram=8;summarise:gemma3-4b".toByteArray()
        )
        val text = ExitInfoLogic.renderExit(e, updatedSince = true, trace = "--- trace ---\nframe")
        assertTrue(text, text.contains("Reason: Killed by Android for low memory (LOW_MEMORY, 3)"))
        assertTrue(text, text.contains("When: 1970-01-01 00:00:00 UTC"))
        assertTrue(text, text.contains("updated after this"))
        assertTrue(text, text.contains("Importance: foreground service (125)"))
        assertTrue(text, text.contains("PSS 2 MB, RSS 4 MB"))
        assertTrue(text, text.contains("Description: lmk"))
        assertTrue(text, text.contains("Native work: ram=8;summarise:gemma3-4b"))
        assertTrue(text, text.endsWith("frame\n"))

        val idle = ExitInfoLogic.renderExit(exit(0), updatedSince = false, trace = null)
        assertTrue(idle.contains("Native work: none recorded"))
        assertFalse(idle.contains("updated after"))
    }

    @Test
    fun headlineListsTheReasons() {
        assertEquals(
            "Exit: CRASH_NATIVE, ANR (recorded by Android after Recap closed)",
            ExitInfoLogic.headline(listOf(exit(2), exit(1, reason = ExitInfoLogic.REASON_ANR)))
        )
    }
}
