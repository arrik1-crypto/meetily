package com.meetily.mobile

import com.meetily.mobile.data.AtomicJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * The meeting store's write path. A meeting that no longer parses is dropped
 * from the library with no error, so a torn write is silent data loss.
 */
class AtomicJsonTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun dir(): File = folder.root

    @Test
    fun writesTheContentAndLeavesNoTempFileBehind() {
        assertTrue(AtomicJson.write(dir(), "m1.json", """{"a":1}"""))
        assertEquals("""{"a":1}""", File(dir(), "m1.json").readText())
        assertTrue(!File(dir(), "m1.json.tmp").exists())
    }

    @Test
    fun aLaterWriteReplacesAnEarlierOneEntirely() {
        // Not append, and not a partial overwrite: a shorter meeting must not
        // leave the tail of a longer one behind.
        AtomicJson.write(dir(), "m1.json", """{"segments":[1,2,3,4,5,6,7,8,9]}""")
        AtomicJson.write(dir(), "m1.json", """{"a":1}""")
        assertEquals("""{"a":1}""", File(dir(), "m1.json").readText())
    }

    @Test
    fun concurrentWritersToOneMeetingNeverLeaveATornFile() {
        // The real collision: RecordingService's save executor writes the
        // whole meeting every 2.5s while appendLateSegment writes the same
        // meeting from its own thread, and SummaryService and the meeting
        // screen make two more. They all derived the temp path from the
        // meeting id, so they shared one temp file and writeText truncates.
        val writers = 8
        val rounds = 150
        // Wildly different lengths, so a mix of two writes cannot accidentally
        // look like a valid one.
        val candidates = (0 until writers).map { i ->
            """{"w":$i,"pad":"${"x".repeat(i * 400)}"}"""
        }.toSet()

        val start = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val threads = (0 until writers).map { i ->
            Thread {
                start.await()
                val body = candidates.elementAt(i)
                repeat(rounds) {
                    AtomicJson.write(dir(), "m1.json", body)
                    val seen = try {
                        File(dir(), "m1.json").readText()
                    } catch (_: Exception) {
                        return@repeat
                    }
                    if (seen !in candidates) {
                        failure.compareAndSet(null, "torn file, ${seen.length} bytes")
                    }
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(30_000) }

        assertEquals(null, failure.get())
        assertTrue(File(dir(), "m1.json").readText() in candidates)
    }

    @Test
    fun exclusiveSerializesReadModifyWriteCycles() {
        // saveMerging loads, folds in late segments, then saves. If another
        // save lands between the load and the save it is folded into nothing
        // and then overwritten — the exact loss saveMerging exists to prevent.
        val threads = 6
        val bumps = 200
        val start = CountDownLatch(1)
        val workers = (0 until threads).map {
            Thread {
                start.await()
                repeat(bumps) {
                    AtomicJson.exclusive {
                        val file = File(dir(), "counter.json")
                        val current = if (file.exists()) file.readText().toInt() else 0
                        AtomicJson.write(dir(), "counter.json", (current + 1).toString())
                    }
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(30_000) }

        assertEquals(threads * bumps, File(dir(), "counter.json").readText().toInt())
    }
}
