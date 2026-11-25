package com.meetily.mobile.whisper

/**
 * Frames a live session captures before it knows whether it keeps them
 * (WhisperRecorder's held audio): with "Keep meeting audio" off no file is
 * open, and only a live model that fails to load makes the session open
 * one. Kept in order up to [capSamples]; past that the opening of the
 * meeting is what stays, not its latest seconds.
 *
 * Not thread-safe: the recorder guards it. Pure, so it is unit-tested.
 */
internal class HeldAudio(private val capSamples: Long) {

    private val frames = ArrayList<FloatArray>()

    /** Samples held. */
    var samples = 0L
        private set

    val isEmpty: Boolean get() = frames.isEmpty()

    /** Holds [frame] (not copied), unless that would pass the cap. */
    fun add(frame: FloatArray): Boolean {
        if (frame.isEmpty()) return true
        if (samples + frame.size > capSamples) return false
        frames.add(frame)
        samples += frame.size
        return true
    }

    /**
     * Hands everything held to [sink] in order, joined into pieces of at
     * most [pieceSamples], and forgets it. One array per 100 ms frame would
     * overflow a sink that counts entries: the meeting audio writer drops
     * whatever comes past its 300-entry queue.
     */
    fun drainInto(sink: (FloatArray) -> Unit, pieceSamples: Int = PIECE_SAMPLES) {
        require(pieceSamples > 0)
        var remaining = samples
        var piece: FloatArray? = null
        var filled = 0
        for (frame in frames) {
            var offset = 0
            while (offset < frame.size) {
                var target = piece
                if (target == null) {
                    target = FloatArray(minOf(pieceSamples.toLong(), remaining).toInt())
                    piece = target
                }
                val count = minOf(frame.size - offset, target.size - filled)
                System.arraycopy(frame, offset, target, filled, count)
                offset += count
                filled += count
                remaining -= count
                if (filled == target.size) {
                    sink(target)
                    piece = null
                    filled = 0
                }
            }
        }
        clear()
    }

    fun clear() {
        frames.clear()
        samples = 0L
    }

    companion object {
        /** Five seconds at 16 kHz: a 90-second hold is 18 pieces. */
        const val PIECE_SAMPLES = 16_000 * 5
    }
}
