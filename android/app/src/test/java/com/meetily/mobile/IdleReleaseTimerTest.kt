package com.meetily.mobile

import com.meetily.mobile.llm.IdleReleaseTimer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device model's idle release: one run, pushed back on every use. */
class IdleReleaseTimerTest {

    @Test
    fun firesOnceAfterTheDelay() {
        val latch = CountDownLatch(1)
        val runs = AtomicInteger()
        val timer = IdleReleaseTimer(50L) {
            runs.incrementAndGet()
            latch.countDown()
        }
        timer.schedule()
        assertTrue(timer.isScheduled)
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, runs.get())
        assertFalse(timer.isScheduled)
    }

    @Test
    fun cancelPreventsTheRun() {
        val runs = AtomicInteger()
        val timer = IdleReleaseTimer(100L) { runs.incrementAndGet() }
        timer.schedule()
        timer.cancel()
        assertFalse(timer.isScheduled)
        Thread.sleep(250)
        assertEquals(0, runs.get())
    }

    @Test
    fun rescheduleReplacesThePendingRun() {
        val runs = AtomicInteger()
        val timer = IdleReleaseTimer(150L) { runs.incrementAndGet() }
        timer.schedule()
        Thread.sleep(80)
        timer.schedule() // used again: the clock restarts
        Thread.sleep(100) // 180 ms after the first schedule, 100 after the second
        assertEquals(0, runs.get())
        Thread.sleep(300)
        assertEquals(1, runs.get())
    }

    @Test
    fun aThrowingActionDoesNotStopLaterRuns() {
        val runs = AtomicInteger()
        val timer = IdleReleaseTimer(20L) {
            runs.incrementAndGet()
            throw IllegalStateException("native free failed")
        }
        timer.schedule()
        Thread.sleep(150)
        timer.schedule()
        Thread.sleep(150)
        assertEquals(2, runs.get())
    }
}
