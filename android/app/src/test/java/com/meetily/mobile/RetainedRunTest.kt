package com.meetily.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * RetainedRun holds Ask your library's answer and the pre-meeting brief
 * across a screen's recreation, so a rotation mid-run re-attaches to the run
 * instead of dropping its result and starting another beside it.
 */
class RetainedRunTest {

    @Test
    fun askingAgainWhileInFlightAttachesToTheSameRun() {
        val runs = RetainedRun<String, String>()
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        val compute = {
            started.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            "answer"
        }
        val first = runs.start("q", "test-run", compute)
        val second = runs.start("q", "test-run", compute)
        assertSame(first, second)
        release.countDown()
        assertEquals("answer", first.get(5, TimeUnit.SECONDS))
        assertEquals(1, started.get())
    }

    @Test
    fun aRecreatedScreenFindsTheFinishedRun() {
        val runs = RetainedRun<String, String>()
        val run = runs.start("q", "test-run") { "answer" }
        run.get(5, TimeUnit.SECONDS)
        assertSame(run, runs.existing("q"))
        assertNull(runs.existing("other question"))
    }

    @Test
    fun askingAgainAfterItFinishedStartsAFreshRun() {
        val runs = RetainedRun<String, Int>()
        val count = AtomicInteger()
        val first = runs.start("q", "test-run") { count.incrementAndGet() }
        assertEquals(1, first.get(5, TimeUnit.SECONDS))
        val second = runs.start("q", "test-run") { count.incrementAndGet() }
        assertNotSame(first, second)
        assertEquals(2, second.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun aNewQuestionReplacesTheOldRun() {
        val runs = RetainedRun<String, String>()
        runs.start("a", "test-run") { "first" }.get(5, TimeUnit.SECONDS)
        runs.start("b", "test-run") { "second" }.get(5, TimeUnit.SECONDS)
        assertNull(runs.existing("a"))
        assertEquals("second", runs.existing("b")?.get())
    }

    @Test
    fun forgetLetsGoOnlyOfItsOwnKey() {
        val runs = RetainedRun<String, String>()
        runs.start("a", "test-run") { "first" }.get(5, TimeUnit.SECONDS)
        runs.forget("b")
        assertEquals("first", runs.existing("a")?.get())
        runs.forget("a")
        assertNull(runs.existing("a"))
    }
}
