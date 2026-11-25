package com.meetily.mobile.data

/**
 * What MeetingStore's crash-recovery marker says: the meeting being
 * recorded ([id]), and whether its live words are known to stop short of
 * its saved audio ([liveShort]). That is so when live transcription fell
 * behind and stopped, when its backlog was skipped at Finish, and while
 * much of what was said still waits to be transcribed. Finish then has the
 * audio transcribed in full, and a recording the process died under has it
 * transcribed and staged as a check beside its lines once the app is next
 * opened or a recording next started (RecordingService.recoverInterrupted).
 * The flag only lives in memory otherwise, and the meeting looked complete
 * with the rest of it in the audio alone. Pure, so it is unit-tested.
 */
data class ActiveRecording(val id: String, val liveShort: Boolean = false) {

    /** The marker file's text: the id, then the flag on a line of its own. */
    fun format(): String = if (liveShort) "$id\n$LIVE_SHORT" else id

    companion object {
        private const val LIVE_SHORT = "live-short"

        /** The marker read back, or null for an empty one. A marker of an id alone reads as complete. */
        fun parse(text: String): ActiveRecording? {
            val lines = text.lines().map { it.trim() }
            val id = lines.firstOrNull().orEmpty()
            if (id.isEmpty()) return null
            return ActiveRecording(id, lines.drop(1).any { it == LIVE_SHORT })
        }
    }
}
