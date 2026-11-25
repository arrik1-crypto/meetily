package com.meetily.mobile.whisper

/**
 * Where a recorder started without a frame sink sends what it captures
 * (WhisperRecorder). With "Keep meeting audio" off a live session keeps no
 * file unless its model fails to load, and capture runs before that is
 * known, so the frames are held ([HeldAudio]) until it is: a model that
 * loads drops them ([dropHeld]), and a file opened after all ([attach],
 * when the model fails, also during Finish's wait for it) gets them first
 * and then every frame after them.
 * Without the hold, everything said until a failure was in neither the
 * file nor any transcript (the chunks queued for the model are dropped
 * once it fails), and the file started later than the session's clock, so
 * its marks were out of step.
 *
 * The audio thread offers frames while the main thread attaches and the
 * transcriber drops. One lock keeps the held frames ahead of the ones
 * captured after them, and makes each hand-over a single step against the
 * model's outcome. Pure, so it is unit-tested.
 */
internal class LateFrameSink(holdCapSamples: Long?) {

    private val lock = Any()

    /** Null when nothing is held for: no live model, or handed over or dropped. */
    private var held: HeldAudio? = holdCapSamples?.let { HeldAudio(it) }

    @Volatile private var sink: ((FloatArray) -> Unit)? = null

    /** True while frames are held for a sink not attached yet. */
    val holdsAudio: Boolean
        get() = synchronized(lock) { held?.isEmpty == false }

    /**
     * The audio thread's hand-off of the first [n] samples of [frame], a
     * buffer it reuses (so copied): to the sink once one is attached,
     * otherwise held, or dropped when nothing is held for.
     */
    fun offer(frame: FloatArray, n: Int) {
        val target = sink ?: synchronized(lock) {
            sink ?: run {
                held?.add(frame.copyOf(n))
                null
            }
        }
        target?.invoke(frame.copyOf(n))
    }

    /**
     * Starts feeding [target]: first what is held, then every frame offered
     * from now on. For a live model that failed to load.
     */
    fun attach(target: (FloatArray) -> Unit) {
        synchronized(lock) {
            held?.drainInto(target)
            held = null
            sink = target
        }
    }

    /** The live model loaded: live has the words, and no file was asked for. */
    fun dropHeld() {
        synchronized(lock) { held = null }
    }
}
