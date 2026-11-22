package com.meetily.mobile

/**
 * Holds work until a value has loaded, then runs it in order.
 *
 * The meeting screen reads its meeting off the main thread, so anything that
 * arrives first (an activity result replayed into a re-created screen, a
 * deep link to a segment, the action the screen was opened for) waits here
 * instead of finding no meeting and quietly doing nothing, or, for the
 * camera, deleting the photo just taken.
 *
 * Once loaded, new work runs at once. Work added while the queue is being
 * run goes to its end, so everything runs in the order it was asked for. A
 * failed load runs each waiter's onFailed instead and drops the rest; so
 * does anything asked for after the failure.
 *
 * Main thread only. Pure, so it is unit-tested.
 */
class LoadGate<T : Any> {

    enum class State { LOADING, LOADED, FAILED }

    var state: State = State.LOADING
        private set

    /** The loaded value, once [state] is LOADED. */
    var value: T? = null
        private set

    val isLoading: Boolean get() = state == State.LOADING

    private class Waiter<T>(val onLoaded: (T) -> Unit, val onFailed: (() -> Unit)?)

    private val waiting = ArrayDeque<Waiter<T>>()
    private var draining = false

    /** How much is waiting for the load. */
    val waitingCount: Int get() = waiting.size

    /**
     * Runs [onLoaded] with the value once it has loaded, at once when it
     * already has. [onFailed] runs instead if the load fails.
     */
    fun whenLoaded(onLoaded: (T) -> Unit, onFailed: (() -> Unit)? = null) {
        when (state) {
            State.LOADING -> waiting.addLast(Waiter(onLoaded, onFailed))
            State.LOADED -> {
                val loaded = value ?: return
                if (draining) waiting.addLast(Waiter(onLoaded, onFailed)) else onLoaded(loaded)
            }
            State.FAILED -> onFailed?.invoke()
        }
    }

    /**
     * Records [loaded] and runs everything waiting for it. False, and nothing
     * run, when the load had already ended: a late or second delivery.
     */
    fun loaded(loaded: T): Boolean {
        if (state != State.LOADING) return false
        state = State.LOADED
        value = loaded
        draining = true
        try {
            while (true) {
                val next = waiting.removeFirstOrNull() ?: break
                next.onLoaded(loaded)
            }
        } finally {
            draining = false
        }
        return true
    }

    /** Ends the load as failed, running each waiter's onFailed. False when it had already ended. */
    fun failed(): Boolean {
        if (state != State.LOADING) return false
        state = State.FAILED
        val dropped = waiting.toList()
        waiting.clear()
        for (waiter in dropped) waiter.onFailed?.invoke()
        return true
    }
}

/**
 * One screen's read of one item, kept across a re-creation (see
 * MeetingLoadModel, which holds it in a ViewModel).
 *
 * A screen re-created while the read is running attaches to that read
 * instead of starting a second one, and a result that lands while no screen
 * is attached waits for the next. Every read has a ticket; whatever is
 * reported under any other ticket is dropped, so a read that was replaced
 * or cleared can never land on a screen. The listener is the only reference
 * to a screen, and [detach] drops it.
 *
 * Once a result has been handed over nothing is kept, so the next screen to
 * attach (after a rotation, say) reads again and sees everything the last
 * one saved.
 *
 * Main thread only. Pure, so it is unit-tested.
 */
class RetainedLoad<H : Any, T : Any> {

    interface Listener<H, T> {
        /** What can be shown before the result: a meeting's title and date. */
        fun onHeader(header: H)

        /** The read's result; null when there was nothing usable to read. */
        fun onResult(result: T?)
    }

    private var key: String? = null
    private var ticket = 0L
    private var running = false
    private var header: H? = null

    /** A result that landed while nobody was attached. */
    private var held = false
    private var heldResult: T? = null

    private var listener: Listener<H, T>? = null

    /** True while a read is running and nothing has been handed over yet. */
    val isRunning: Boolean get() = running

    /**
     * True when the owner went away ([ownerLeft]) after the current read
     * began, so what it read may be older than a save made while the owner
     * was away. Kept across a re-creation, like the read itself, and
     * readable from [Listener.onResult]; reset when a new read starts.
     */
    var ownerLeftDuringRead: Boolean = false
        private set

    /**
     * The owner has gone away for a while (stopped, not just re-created)
     * while the read is running or its result is waiting.
     */
    fun ownerLeft() {
        if (running || held) ownerLeftDuringRead = true
    }

    /**
     * Attaches [listener] to the read of [key]. Returns the ticket of a read
     * the caller must now start, or null when one for [key] is already
     * running (its header, if any, is replayed) or has finished and was
     * waiting (it is handed over now, before this returns).
     */
    fun attach(key: String, listener: Listener<H, T>): Long? {
        this.listener = listener
        if (key == this.key && (running || held)) {
            header?.let { listener.onHeader(it) }
            if (held) {
                val result = heldResult
                held = false
                heldResult = null
                listener.onResult(result)
            }
            return null
        }
        this.key = key
        ticket++
        running = true
        header = null
        held = false
        heldResult = null
        ownerLeftDuringRead = false
        return ticket
    }

    /** Drops [listener], if it is the one attached; a running read then waits for the next. */
    fun detach(listener: Listener<H, T>) {
        if (this.listener === listener) this.listener = null
    }

    /** The read under [ticket] has its header. */
    fun header(ticket: Long, header: H) {
        if (ticket != this.ticket || !running) return
        this.header = header
        listener?.onHeader(header)
    }

    /** The read under [ticket] has finished with [result]. */
    fun finish(ticket: Long, result: T?) {
        if (ticket != this.ticket || !running) return
        running = false
        val current = listener
        if (current == null) {
            held = true
            heldResult = result
        } else {
            current.onResult(result)
        }
    }

    /** Forgets everything, including any read still running; for an owner going away for good. */
    fun clear() {
        listener = null
        key = null
        ticket++
        running = false
        header = null
        held = false
        heldResult = null
        ownerLeftDuringRead = false
    }
}
