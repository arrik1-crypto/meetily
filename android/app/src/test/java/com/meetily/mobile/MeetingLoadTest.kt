package com.meetily.mobile

import com.meetily.mobile.data.LightJson
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The meeting screen's off-main-thread open: the queue that holds work until
 * the meeting has been read (LoadGate), the read kept across a rotation
 * (RetainedLoad), and the header read that puts the title and date up first.
 */
class MeetingLoadTest {

    // --- LoadGate ---------------------------------------------------------

    @Test
    fun gate_runsQueuedWorkOnceInOrder_whenLoaded() {
        val gate = LoadGate<String>()
        val ran = mutableListOf<String>()
        gate.whenLoaded({ ran.add("a:$it") })
        gate.whenLoaded({ ran.add("b:$it") })
        assertTrue(ran.isEmpty())
        assertEquals(2, gate.waitingCount)

        assertTrue(gate.loaded("m1"))
        assertEquals(listOf("a:m1", "b:m1"), ran)
        assertEquals(0, gate.waitingCount)
        assertEquals(LoadGate.State.LOADED, gate.state)
        assertEquals("m1", gate.value)

        // A second delivery is refused and runs nothing again.
        assertFalse(gate.loaded("m2"))
        assertEquals(listOf("a:m1", "b:m1"), ran)
        assertEquals("m1", gate.value)
    }

    @Test
    fun gate_runsAtOnce_afterLoad() {
        val gate = LoadGate<String>()
        gate.loaded("m1")
        val ran = mutableListOf<String>()
        gate.whenLoaded({ ran.add(it) })
        assertEquals(listOf("m1"), ran)
        assertEquals(0, gate.waitingCount)
    }

    @Test
    fun gate_workAddedWhileRunningTheQueue_goesToItsEnd() {
        val gate = LoadGate<String>()
        val ran = mutableListOf<String>()
        gate.whenLoaded({
            ran.add("first")
            // Asked for by queued work (a deep link opening a find bar, say):
            // it must not jump ahead of work queued before it.
            gate.whenLoaded({ ran.add("nested") })
        })
        gate.whenLoaded({ ran.add("second") })
        gate.loaded("m1")
        assertEquals(listOf("first", "second", "nested"), ran)

        // After the drain, work runs at once again.
        gate.whenLoaded({ ran.add("later") })
        assertEquals("later", ran.last())
    }

    @Test
    fun gate_failure_runsOnlyTheFailureCallbacks() {
        val gate = LoadGate<String>()
        val ran = mutableListOf<String>()
        gate.whenLoaded({ ran.add("loaded") }, onFailed = { ran.add("photo deleted") })
        gate.whenLoaded({ ran.add("scroll") })
        assertTrue(gate.failed())
        assertEquals(listOf("photo deleted"), ran)
        assertEquals(LoadGate.State.FAILED, gate.state)
        assertNull(gate.value)

        // Nothing loads after a failure, and later work fails at once.
        assertFalse(gate.loaded("m1"))
        gate.whenLoaded({ ran.add("too late") }, onFailed = { ran.add("failed at once") })
        gate.whenLoaded({ ran.add("dropped") })
        assertEquals(listOf("photo deleted", "failed at once"), ran)
        assertFalse(gate.failed())
    }

    @Test
    fun gate_failedAfterLoad_isIgnored() {
        val gate = LoadGate<String>()
        gate.loaded("m1")
        assertFalse(gate.failed())
        assertEquals(LoadGate.State.LOADED, gate.state)
    }

    @Test
    fun gate_aThrowingAction_leavesTheGateUsable() {
        val gate = LoadGate<String>()
        gate.whenLoaded({ throw IllegalStateException("boom") })
        gate.whenLoaded({})
        try {
            gate.loaded("m1")
        } catch (_: IllegalStateException) {
        }
        // Not stuck in "draining": new work still runs at once.
        val ran = mutableListOf<String>()
        gate.whenLoaded({ ran.add(it) })
        assertTrue(ran.contains("m1"))
    }

    // --- RetainedLoad -------------------------------------------------------

    private class Screen : RetainedLoad.Listener<String, String> {
        val headers = mutableListOf<String>()
        val results = mutableListOf<String?>()
        override fun onHeader(header: String) {
            headers.add(header)
        }

        override fun onResult(result: String?) {
            results.add(result)
        }
    }

    @Test
    fun retained_firstAttach_startsARead_andDeliversToTheScreen() {
        val load = RetainedLoad<String, String>()
        val screen = Screen()
        val ticket = load.attach("m1", screen)
        assertNotNull(ticket)
        assertTrue(load.isRunning)
        load.header(ticket!!, "Standup")
        load.finish(ticket, "meeting")
        assertEquals(listOf("Standup"), screen.headers)
        assertEquals(listOf<String?>("meeting"), screen.results)
        assertFalse(load.isRunning)
    }

    @Test
    fun retained_rotationDuringTheRead_attachesInsteadOfReadingTwice() {
        val load = RetainedLoad<String, String>()
        val first = Screen()
        val ticket = load.attach("m1", first)!!
        load.header(ticket, "Standup")
        // Rotation: the old screen goes, the new one attaches to the same read.
        load.detach(first)
        val second = Screen()
        assertNull(load.attach("m1", second))
        // The header it missed is replayed; the result reaches only it.
        assertEquals(listOf("Standup"), second.headers)
        load.finish(ticket, "meeting")
        assertEquals(listOf<String?>("meeting"), second.results)
        assertTrue(first.results.isEmpty())
    }

    @Test
    fun retained_aResultWithNobodyAttached_waitsForTheNextScreen_once() {
        val load = RetainedLoad<String, String>()
        val first = Screen()
        val ticket = load.attach("m1", first)!!
        load.detach(first)
        load.finish(ticket, "meeting")
        assertTrue(first.results.isEmpty())

        val second = Screen()
        assertNull(load.attach("m1", second))
        assertEquals(listOf<String?>("meeting"), second.results)

        // Handed over once: the next screen (a later rotation) reads again.
        val third = Screen()
        assertNotNull(load.attach("m1", third))
        assertTrue(third.results.isEmpty())
    }

    @Test
    fun retained_afterDelivery_aNewScreenReadsAgain() {
        val load = RetainedLoad<String, String>()
        val first = Screen()
        val ticket = load.attach("m1", first)!!
        load.finish(ticket, "meeting")
        load.detach(first)
        val second = Screen()
        val again = load.attach("m1", second)
        assertNotNull(again)
        assertTrue(again!! > ticket)
        // A late report from the first read is not taken for the second.
        load.finish(ticket, "stale")
        assertTrue(second.results.isEmpty())
        load.finish(again, "fresh")
        assertEquals(listOf<String?>("fresh"), second.results)
    }

    @Test
    fun retained_aMissingMeeting_isDeliveredAsNull() {
        val load = RetainedLoad<String, String>()
        val screen = Screen()
        val ticket = load.attach("gone", screen)!!
        load.finish(ticket, null)
        assertEquals(listOf<String?>(null), screen.results)
    }

    @Test
    fun retained_detachOfAnotherScreen_keepsTheCurrentOne() {
        val load = RetainedLoad<String, String>()
        val old = Screen()
        val current = Screen()
        val ticket = load.attach("m1", old)!!
        load.attach("m1", current)
        // The old screen's onDestroy can run after the new one attached.
        load.detach(old)
        load.finish(ticket, "meeting")
        assertEquals(listOf<String?>("meeting"), current.results)
    }

    @Test
    fun retained_clear_dropsAReadStillRunning() {
        val load = RetainedLoad<String, String>()
        val screen = Screen()
        val ticket = load.attach("m1", screen)!!
        load.clear()
        load.header(ticket, "Standup")
        load.finish(ticket, "meeting")
        assertTrue(screen.headers.isEmpty())
        assertTrue(screen.results.isEmpty())
        assertFalse(load.isRunning)
    }

    @Test
    fun retained_anotherKey_replacesTheRead() {
        val load = RetainedLoad<String, String>()
        val screen = Screen()
        val first = load.attach("m1", screen)!!
        val second = load.attach("m2", screen)!!
        load.finish(first, "m1 meeting")
        assertTrue(screen.results.isEmpty())
        load.finish(second, "m2 meeting")
        assertEquals(listOf<String?>("m2 meeting"), screen.results)
    }

    // --- Header read ---------------------------------------------------------

    private val headerKeys = setOf("id", "title", "createdAtMs")

    @Test
    fun parseLeading_stopsOnceTheHeaderIsRead() {
        // The tail is not even valid JSON: a header read must never get there.
        val text = "{\"id\":\"m1\",\"title\":\"Q3 \\\"plan\\\" {x}\",\"createdAtMs\":42," +
            "\"segments\":[{\"t\":1,\"x\":"
        val header = Meeting.fromJson(LightJson.parseLeading(text, headerKeys))
        assertEquals("m1", header.id)
        assertEquals("Q3 \"plan\" {x}", header.title)
        assertEquals(42L, header.createdAtMs)
        assertTrue(header.segments.isEmpty())
    }

    @Test
    fun parseLeading_skipsWhateverComesFirst() {
        val meeting = Meeting(id = "m1", title = "Standup", createdAtMs = 7L).apply {
            segments.add(
                TranscriptSegment(
                    timestampMs = 1L,
                    text = "hello {there}",
                    words = listOf(WordStamp(0L, "hello"), WordStamp(5L, "\"there\""))
                )
            )
            notes = "a [note]"
        }
        // Whatever order the members come in, only the header is taken.
        val text = meeting.toJson().toString()
        val parsed = LightJson.parseLeading(text, headerKeys)
        assertEquals(headerKeys, parsed.keySet())
        val header = Meeting.fromJson(parsed)
        assertEquals("Standup", header.title)
        assertEquals(7L, header.createdAtMs)
    }

    @Test
    fun parseLeading_aMissingKey_readsToTheEnd() {
        val parsed = LightJson.parseLeading("{\"id\":\"m1\",\"notes\":\"n\"}", headerKeys)
        assertEquals(setOf("id"), parsed.keySet())
    }

    @Test(expected = JSONException::class)
    fun parseLeading_malformedBeforeTheHeader_throws() {
        LightJson.parseLeading("{\"id\" \"m1\"}", headerKeys)
    }
}
