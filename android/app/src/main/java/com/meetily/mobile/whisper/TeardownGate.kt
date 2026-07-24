package com.meetily.mobile.whisper

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ordering guard for a resource two callers may tear down concurrently.
 *
 * Written for [WhisperRecorder], where `finish()` (flush and transcribe the
 * tail) and `destroy()` (immediate teardown) both run on a normal stop:
 * `finish()` shut the transcription executor down, then `destroy()` tried to
 * submit to it and the resulting RejectedExecutionException crashed the app
 * on the main thread at the end of every recording.
 *
 * The contract:
 *  - exactly one caller wins [claim] and owns the shutdown + the native free;
 *  - the loser must not touch the executor at all — hence [isClaimed], which
 *    also stops new work being submitted mid-teardown;
 *  - [releaseOnce] runs the native free exactly once no matter who calls it
 *    or how often, so a double free is impossible.
 *
 * Pure Kotlin (no Android, no I/O) so the ordering is unit-tested.
 */
class TeardownGate {

    private val claimed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    /** True exactly once: for the caller that owns teardown. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** True once teardown has started; new work must not be submitted. */
    val isClaimed: Boolean get() = claimed.get()

    /** True once [releaseOnce] has run its action. */
    val isReleased: Boolean get() = released.get()

    /** Runs [action] the first time only. */
    fun releaseOnce(action: () -> Unit) {
        if (released.compareAndSet(false, true)) action()
    }
}
