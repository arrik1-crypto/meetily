package com.meetily.mobile

import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

/**
 * One background run, keyed by its input, that outlives the screen which
 * started it. Held in a screen's companion object, as DigestActivity holds
 * its digest run.
 *
 * A screen is recreated on a rotation, a fold, a theme or dark-mode switch.
 * A run on a plain thread captured the old instance and dropped its result
 * with it, while the run itself went on: minutes of on-device inference that
 * nobody saw, holding the model's lock so the next question waited behind it,
 * or the transcripts sent to an endpoint a second time. The recreated screen
 * now finds the run by its key and waits on the same one.
 *
 * The work passed to [start] must not hold the screen: plain values and the
 * application context only.
 */
class RetainedRun<K, V> {

    private val lock = Any()
    private var key: K? = null
    private var task: FutureTask<V>? = null

    /** The run for [key], in flight or finished, or null. */
    fun existing(key: K): FutureTask<V>? = synchronized(lock) {
        task?.takeIf { this.key == key }
    }

    /**
     * The run for [key] that is still in flight, or a new one running
     * [compute] on a thread named [threadName]. A finished run is not
     * reused: asking again is asking for a fresh answer.
     */
    fun start(key: K, threadName: String, compute: () -> V): FutureTask<V> =
        synchronized(lock) {
            val current = task
            if (current != null && this.key == key && !current.isDone) return current
            val fresh = FutureTask(Callable { compute() })
            task = fresh
            this.key = key
            Thread(fresh, threadName).start()
            fresh
        }

    /**
     * Lets go of the run for [key], once no screen will come back for it.
     * A run still in flight finishes on its own and its result is dropped.
     */
    fun forget(key: K) {
        synchronized(lock) {
            if (this.key == key) {
                task = null
                this.key = null
            }
        }
    }
}
