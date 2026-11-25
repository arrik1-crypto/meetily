package com.meetily.mobile.whisper

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One recorder's share of [count], the live models loading or loaded in
 * the process (WhisperRecorder.liveModelsHeld), which keeps batch work out
 * until they are freed (JobGate.canStartBatch). Taken once, as the load is
 * queued, and given back once, by whichever comes first of the release, a
 * load that failed or ran after the release, and a load that was never
 * queued. Pure, so it is unit-tested.
 */
internal class LiveModelSlot(private val count: AtomicInteger) {

    private val holding = AtomicBoolean(false)

    /** True from [take] until [giveBack]. */
    val isHeld: Boolean get() = holding.get()

    fun take() {
        if (holding.compareAndSet(false, true)) count.incrementAndGet()
    }

    /** True for the call that gave it back; later calls change nothing. */
    fun giveBack(): Boolean {
        if (!holding.compareAndSet(true, false)) return false
        count.decrementAndGet()
        return true
    }
}
