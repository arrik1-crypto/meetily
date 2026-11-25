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
 * application context only. Nor may whatever waits for it: a screen hears
 * of the result through [Run.whenDone], and lets go in onDestroy.
 */
class RetainedRun<K, V> {

    /**
     * One run. A screen waits for it with [whenDone] rather than a thread
     * blocked on get(): such a thread held the screen that started it, and
     * that screen's whole view tree, until the run ended, which for an
     * on-device answer or brief is minutes after a rotation replaced it.
     */
    class Run<V>(compute: () -> V) : FutureTask<V>(Callable { compute() }) {

        private val lock = Any()
        private val listeners = mutableListOf<(Run<V>) -> Unit>()

        /**
         * Calls [listener] once this run is done: on the run's own thread
         * as it ends, or at once on the calling thread when it already has.
         * Returns what takes the listener off again, for a screen going
         * away before the run ends, so the run no longer holds it.
         */
        fun whenDone(listener: (Run<V>) -> Unit): () -> Unit {
            synchronized(lock) {
                // done() runs after isDone turns true and takes the list
                // under this lock, so a listener added here is never missed.
                if (!isDone) {
                    listeners.add(listener)
                    return { synchronized(lock) { listeners.remove(listener) } }
                }
            }
            listener(this)
            return {}
        }

        override fun done() {
            val waiting = synchronized(lock) {
                listeners.toList().also { listeners.clear() }
            }
            for (listener in waiting) {
                try {
                    listener(this)
                } catch (_: Throwable) {
                    // One screen's failure must not keep the others waiting.
                }
            }
        }
    }

    private val lock = Any()
    private var key: K? = null
    private var task: Run<V>? = null

    /** The run for [key], in flight or finished, or null. */
    fun existing(key: K): Run<V>? = synchronized(lock) {
        task?.takeIf { this.key == key }
    }

    /**
     * The run for [key] that is still in flight, or a new one running
     * [compute] on a thread named [threadName]. A finished run is not
     * reused: asking again is asking for a fresh answer.
     */
    fun start(key: K, threadName: String, compute: () -> V): Run<V> =
        synchronized(lock) {
            val current = task
            if (current != null && this.key == key && !current.isDone) return current
            val fresh = Run(compute)
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
