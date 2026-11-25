package com.meetily.mobile

/**
 * The two things a finished recording's last step waits for
 * (RecordingService's WorkThenStop): its audio file closing, and, when the
 * live model was still loading or not yet freed at Finish
 * ([waitsForLiveModel]), the recorder freeing it. Either can come first.
 *
 * The work itself (the first transcript, a check, a summary) is issued as
 * the file closes, since it reads the file. While the model is held JobGate
 * only queues it, on disk, so a process that dies before the model is freed
 * still has it. Draining that queue and leaving the foreground wait for
 * both. Pure, so it is unit-tested.
 */
class FinishSignals(waitsForLiveModel: Boolean) {

    private var closed = false
    private var released = !waitsForLiveModel

    /** The audio file is closed. True when that completes the pair: drain and stop now. */
    fun audioClosed(): Boolean {
        if (closed) return false
        closed = true
        return released
    }

    /** The live model is freed. True when that completes the pair. */
    fun modelReleased(): Boolean {
        if (released) return false
        released = true
        return closed
    }
}
