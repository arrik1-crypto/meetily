package com.meetily.mobile

import com.meetily.mobile.llm.AbortScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stop for one on-device run must not land on another: the native llama
 * flag is process-wide, so [AbortScope] decides when it is up. The threads
 * here are never started; only their identity matters.
 */
class AbortScopeTest {

    private val summary = Thread()
    private val ask = Thread()

    /** The native flag as the scope last set it. */
    private var flag = false
    private val scope = AbortScope { flag = it }

    @Test
    fun stopForTheHolderRaisesTheFlagAtOnce() {
        scope.enter(summary)
        assertFalse(flag)
        scope.request(summary)
        assertTrue(flag)
        assertTrue(scope.requestedFor(summary))
        assertFalse(scope.requestedFor(ask))
    }

    @Test
    fun stopForAWaitingRunLeavesTheHolderAlone() {
        // An Ask holds the model; the summary the unplug is for waits.
        scope.enter(ask)
        scope.request(summary)
        assertFalse(flag)
        assertFalse(scope.requestedFor(ask))
        scope.exit()
        assertFalse(flag)
        // The summary takes the model and is stopped at its first decode.
        scope.enter(summary)
        assertTrue(flag)
    }

    @Test
    fun runQueuedBehindAStoppedOneStartsClean() {
        scope.enter(summary)
        scope.request(summary)
        assertTrue(flag)
        // The stopped summary lets go before its service clears the stop.
        scope.exit()
        assertFalse(flag)
        scope.enter(ask)
        assertFalse(flag)
        assertFalse(scope.requestedFor(ask))
        // Still pending for the summary's own thread until cleared.
        assertTrue(scope.requestedFor(summary))
    }

    @Test
    fun clearWithdrawsTheStop() {
        scope.request(summary)
        scope.clear()
        assertFalse(scope.requestedFor(summary))
        scope.enter(summary)
        assertFalse(flag)
    }

    @Test
    fun aLaterStopNamesOnlyItsOwnRun() {
        scope.request(summary)
        scope.clear()
        scope.request(ask)
        scope.enter(summary)
        assertFalse(flag)
        scope.exit()
        scope.enter(ask)
        assertEquals(true, flag)
    }
}
