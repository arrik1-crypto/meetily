package com.meetily.mobile

/**
 * A star tapped during a recording while the live transcript had no line
 * to put it on, which then goes on the line spoken when it was tapped.
 *
 * Capture starts before the live model has loaded, and the chunks spoken
 * during the load are transcribed once it has, oldest first. A star tapped
 * during the load used to go on the next line to arrive, which is the first
 * chunk of that backlog: the start of the recording, not the moment tapped.
 * One tapped once the backlog's first lines were on screen went on the
 * newest of them, from as far back.
 *
 * [tapMs] is where in the recording (the session clock, pauses left out,
 * as the lines' audio offsets count it) the star was tapped, or null when
 * the next line is the one: a partial line on screen is what was being
 * said. Lines arrive in recording order; the star goes on the last one that
 * starts at or before the tap, which is known once a line starting after
 * the tap arrives, or at the end of the session ([atEnd]). [newestLine] is
 * the newest line already there at the tap (-1 for none), which started
 * before it: the one the tap fell in unless a later one also did.
 */
class QueuedHighlight(private val tapMs: Long?, newestLine: Int = -1) {

    /** The latest line so far that starts at or before the tap, or -1. */
    private var fallsIn = if (tapMs != null) newestLine else -1

    /**
     * The line to star now that line [index], starting at [startMs] (null
     * when it has no time in the recording), has been appended, or null to
     * keep waiting for the next one.
     */
    fun onAppended(index: Int, startMs: Long?): Int? {
        if (tapMs == null || startMs == null) return index
        if (startMs <= tapMs) {
            fallsIn = index
            return null
        }
        return if (fallsIn >= 0) fallsIn else index
    }

    /**
     * The line to star when no more lines will come: the one the tap fell
     * in, or null when no line had started by the tap.
     */
    fun atEnd(): Int? = fallsIn.takeIf { it >= 0 }

    /** Where in the recording it was tapped, to keep as a mark when no line takes it. */
    val tapOffsetMs: Long? get() = tapMs
}
