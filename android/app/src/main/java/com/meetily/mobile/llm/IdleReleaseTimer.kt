package com.meetily.mobile.llm

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * One deferred action, pushed back on every use: [schedule] replaces any
 * pending run with a fresh one [delayMs] out, [cancel] drops it.
 *
 * Plain JVM (no Handler/Looper), so it works from any thread, needs no
 * Android runtime in unit tests, and never touches the main thread. The
 * worker thread is a daemon and is only created on the first [schedule].
 */
class IdleReleaseTimer(
    private val delayMs: Long,
    private val action: () -> Unit
) {
    private val guard = Any()
    private var executor: ScheduledThreadPoolExecutor? = null
    private var pending: ScheduledFuture<*>? = null

    /** True while a run is scheduled and has not finished or been cancelled. */
    val isScheduled: Boolean
        get() = synchronized(guard) { pending?.let { !it.isDone } ?: false }

    fun schedule() {
        synchronized(guard) {
            pending?.cancel(false)
            val exec = executor ?: ScheduledThreadPoolExecutor(
                1,
                ThreadFactory { r ->
                    Thread(r, "llm-idle-release").apply { isDaemon = true }
                }
            ).also {
                it.removeOnCancelPolicy = true
                // Let the thread go between uses instead of idling forever.
                it.setKeepAliveTime(5, TimeUnit.SECONDS)
                it.allowCoreThreadTimeOut(true)
                executor = it
            }
            // The task never clears [pending] itself: a schedule() racing a
            // run already in progress would have its fresh future
            // overwritten and become uncancellable. A finished future in
            // [pending] is harmless (cancel is a no-op, isScheduled false).
            pending = exec.schedule(Runnable {
                try {
                    action()
                } catch (_: Throwable) {
                    // A failed idle release must not kill the worker thread.
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    fun cancel() {
        synchronized(guard) {
            pending?.cancel(false)
            pending = null
        }
    }
}
