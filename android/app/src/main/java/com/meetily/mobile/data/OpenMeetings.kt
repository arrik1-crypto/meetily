package com.meetily.mobile.data

/**
 * Meetings open on a meeting screen right now, in this process.
 *
 * The automatic clean-up (RetentionRunner) leaves these alone. A meeting
 * screen holds its own copy of the meeting while it is read and edited:
 * notes being typed, a find & replace whose undo is still offered, the
 * player on the audio. Its saves merge onto the stored meeting, so a
 * clean-up run underneath it would either delete a meeting the user is
 * looking at (the screen's later saves are then dropped) or take its audio
 * from under the player. The stored file's age alone cannot tell, since a
 * meeting opened today may not have been saved since last year.
 *
 * Counted rather than a set: two screens can show the same meeting (one
 * opened from a notification over another), and the first to close must
 * not let go for both. Pure and thread-safe; the screen holds an id for as
 * long as it lives across rotations (MeetingLoadModel).
 */
object OpenMeetings {

    private val counts = HashMap<String, Int>()

    /** A screen has [id] open. Each call needs its own [release]. */
    @Synchronized
    fun hold(id: String) {
        counts[id] = (counts[id] ?: 0) + 1
    }

    /** A screen that held [id] has closed for good. Extra releases are ignored. */
    @Synchronized
    fun release(id: String) {
        val left = (counts[id] ?: return) - 1
        if (left <= 0) counts.remove(id) else counts[id] = left
    }

    @Synchronized
    fun isOpen(id: String): Boolean = counts.containsKey(id)

    /** Every meeting open now; a copy. */
    @Synchronized
    fun ids(): Set<String> = HashSet(counts.keys)
}
