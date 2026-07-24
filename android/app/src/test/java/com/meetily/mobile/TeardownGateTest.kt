package com.meetily.mobile

import com.meetily.mobile.whisper.TeardownGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the teardown ordering that caused a crash at the end of every
 * on-device-engine recording:
 *
 *   RejectedExecutionException: task rejected from ThreadPoolExecutor
 *   [Shutting down] — WhisperRecorder.destroy -> teardownEngines ->
 *   completeFinish, on the main thread.
 *
 * finish() shut the transcription executor down; destroy() then submitted to
 * it. These tests drive [TeardownGate] exactly the way WhisperRecorder does,
 * against a fake executor that rejects after shutdown, so the regression
 * cannot come back unnoticed.
 */
class TeardownGateTest {

    /** Stands in for the single-thread transcriber executor. */
    private class FakeExecutor {
        var shutdown = false
            private set
        val ran = AtomicInteger(0)

        fun execute(task: () -> Unit) {
            if (shutdown) throw RejectedExecutionException("shut down")
            ran.incrementAndGet()
            task()
        }

        fun shutdown() {
            shutdown = true
        }
    }

    /** Mirrors WhisperRecorder.finish(): returns true if onComplete ran. */
    private fun finish(gate: TeardownGate, exec: FakeExecutor, onComplete: () -> Unit) {
        if (!gate.claim()) {
            onComplete() // loser must still complete, or the flow hangs
            return
        }
        val submitted = try {
            exec.execute {
                onComplete()
                gate.releaseOnce { }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
        exec.shutdown()
        if (!submitted) {
            onComplete()
            gate.releaseOnce { }
        }
    }

    /** Mirrors WhisperRecorder.destroy(). */
    private fun destroy(gate: TeardownGate, exec: FakeExecutor) {
        if (!gate.claim()) return
        try {
            exec.execute { gate.releaseOnce { } }
        } catch (_: RejectedExecutionException) {
            gate.releaseOnce { }
        }
        exec.shutdown()
    }

    @Test
    fun finishThenDestroyDoesNotSubmitToAShutDownExecutor() {
        // This is the reported crash, as a regression test.
        val gate = TeardownGate()
        val exec = FakeExecutor()
        var completed = 0
        finish(gate, exec) { completed++ }
        destroy(gate, exec) // must not throw
        assertEquals(1, completed)
        assertTrue(gate.isReleased)
    }

    @Test
    fun destroyThenFinishStillCompletes() {
        val gate = TeardownGate()
        val exec = FakeExecutor()
        destroy(gate, exec)
        var completed = 0
        finish(gate, exec) { completed++ }
        // The finish flow must not hang just because destroy got there first.
        assertEquals(1, completed)
        assertTrue(gate.isReleased)
    }

    @Test
    fun repeatedTeardownIsIdempotent() {
        val gate = TeardownGate()
        val exec = FakeExecutor()
        destroy(gate, exec)
        destroy(gate, exec)
        destroy(gate, exec)
        assertTrue(gate.isReleased)
    }

    @Test
    fun onlyOneCallerEverClaims() {
        val gate = TeardownGate()
        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertFalse(gate.claim())
    }

    @Test
    fun isClaimedGatesNewWork() {
        val gate = TeardownGate()
        assertFalse(gate.isClaimed) // chunks may be submitted
        gate.claim()
        assertTrue(gate.isClaimed) // submitChunk must now bail out
    }

    @Test
    fun releaseRunsExactlyOnceEvenUnderConcurrency() {
        val gate = TeardownGate()
        val frees = AtomicInteger(0)
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                start.await()
                gate.releaseOnce { frees.incrementAndGet() }
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()
        // A double free of the native context would be a hard crash.
        assertEquals(1, frees.get())
    }

    @Test
    fun exactlyOneOfTwoRacingClaimantsWins() {
        val gate = TeardownGate()
        val wins = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) {
            Thread {
                start.await()
                if (gate.claim()) wins.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()
        assertEquals(1, wins.get())
    }
}
