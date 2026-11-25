package com.meetily.mobile

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp

/**
 * What one recording session's late transcript chunks need from it once it
 * has finished: whether it kept its audio, and its speakers' names.
 *
 * A chunk still being transcribed when its session finished (the 15 s
 * watchdog, a pause, a swipe from Recents) is saved straight to that
 * meeting (RecordingService.appendLateSegment). It used to look both up on
 * the service as it stood when the chunk arrived, and a next session
 * started on the same service resets them: cluster ids start again at 0 in
 * every session, so the tail of one meeting could be named after the next
 * meeting's speakers, and keep or drop its audio times by whether the next
 * meeting kept its audio.
 *
 * One per on-device session, made when its recorder starts and [seal]ed as
 * the session finishes. Main thread only.
 */
class LateChunks(val session: String) {

    private var sealed = false
    private var withAudio = false
    private var clusterNames: Map<Int, String> = emptyMap()

    /** Takes the finishing session's state, as copies a next session cannot change. */
    fun seal(withAudio: Boolean, clusterNames: Map<Int, String>) {
        if (sealed) return
        sealed = true
        this.withAudio = withAudio
        this.clusterNames = clusterNames.toMap()
    }

    /**
     * The line a chunk that arrived after the finish becomes, or null when
     * it has none: an empty chunk, or a session that never finished (it was
     * discarded, so there is no meeting to add it to).
     */
    fun segment(
        text: String,
        speaker: String?,
        clusterId: Int?,
        audioMs: Long?,
        words: List<WordStamp>,
        nowMs: Long
    ): TranscriptSegment? {
        if (!sealed || session.isBlank() || text.isBlank()) return null
        return TranscriptSegment(
            timestampMs = nowMs,
            text = text,
            speaker = speaker ?: clusterId?.let { clusterNames[it] },
            highlighted = false,
            clusterId = clusterId,
            audioMs = if (withAudio) audioMs else null,
            words = if (withAudio && words.isNotEmpty()) words else null
        )
    }
}
