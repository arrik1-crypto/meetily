package com.meetily.mobile.data

/**
 * Tells the meeting player that an audio file is gone.
 *
 * AudioStore.delete is the one path every deletion of a meeting or of its
 * audio comes through (Remove audio on any screen, Delete, multi-select, the
 * automatic clean-up), and it is called on worker threads. The player
 * (MeetingPlayback) listens while it holds a file and stops when that file,
 * or the recording its clear-voice copy was made from, is deleted. Kept as a
 * plain hook so the data layer does not depend on the player.
 */
object AudioDeletions {

    @Volatile
    private var listener: ((String) -> Unit)? = null

    /** Sets the one listener; called on whatever thread deleted the file. */
    fun listen(onDeleted: (String) -> Unit) {
        listener = onDeleted
    }

    /** The audio file [name] has just been deleted. */
    fun deleted(name: String) {
        listener?.invoke(name)
    }
}
