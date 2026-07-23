package com.meetily.mobile.summarize

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp

/**
 * Conversation analytics computed from the transcript alone — talk-time
 * share per speaker, turn counts, longest monologue, questions asked, and
 * overall pace. Durations come from word timings when the segment has them,
 * else from a speaking-rate estimate over the text, so the numbers are
 * honest estimates rather than measurements. Pure Kotlin — unit-tested.
 */
object MeetingStats {

    /** Chars per second of a typical speaking pace (~150 wpm). */
    private const val CHARS_PER_SECOND = 14.0
    private const val MIN_SEGMENT_MS = 900L
    private const val MAX_SEGMENT_MS = 28_000L

    /** Tail after the last word's start: the word itself takes time to say. */
    private const val WORD_TAIL_MS = 600L

    const val UNATTRIBUTED = " unattributed"

    data class SpeakerShare(
        val name: String,
        val ms: Long,
        val percent: Int,
        val turns: Int
    )

    data class Stats(
        val totalSpeechMs: Long,
        val shares: List<SpeakerShare>,
        val longestMonologueMs: Long,
        val longestMonologueSpeaker: String?,
        val questionCount: Int,
        val wordsPerMinute: Int
    )

    fun estimateDurationMs(text: String, words: List<WordStamp>?): Long {
        if (!words.isNullOrEmpty()) {
            val spanned = words.last().ms - words.first().ms + WORD_TAIL_MS
            return spanned.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
        }
        val est = (text.trim().length / CHARS_PER_SECOND * 1000).toLong()
        return est.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
    }

    /** Speaker key for a segment: name, else diarization cluster, else unattributed. */
    fun speakerKey(segment: TranscriptSegment): String {
        val speaker = segment.speaker
        if (!speaker.isNullOrBlank()) return speaker
        val cluster = segment.clusterId
        if (cluster != null) return "Speaker $cluster"
        return UNATTRIBUTED
    }

    /**
     * Returns null when there isn't enough conversation to be worth showing:
     * fewer than [minSegments] lines, or under [minTotalMs] of speech.
     */
    fun compute(
        segments: List<TranscriptSegment>,
        minSegments: Int = 6,
        minTotalMs: Long = 90_000L
    ): Stats? {
        val spoken = segments.filter { it.text.isNotBlank() }
        if (spoken.size < minSegments) return null

        var totalMs = 0L
        var totalWords = 0
        var questions = 0
        val msBySpeaker = LinkedHashMap<String, Long>()
        val turnsBySpeaker = LinkedHashMap<String, Int>()
        var previousKey: String? = null

        var runMs = 0L
        var runKey: String? = null
        var longestMs = 0L
        var longestKey: String? = null

        for (segment in spoken) {
            val key = speakerKey(segment)
            val ms = estimateDurationMs(segment.text, segment.words)
            totalMs += ms
            totalWords += segment.text.split(Regex("\\s+")).count { it.isNotBlank() }
            questions += segment.text.count { it == '?' }
            msBySpeaker[key] = (msBySpeaker[key] ?: 0L) + ms
            if (key != previousKey) {
                turnsBySpeaker[key] = (turnsBySpeaker[key] ?: 0) + 1
                previousKey = key
                runKey = key
                runMs = ms
            } else {
                runMs += ms
            }
            if (runMs > longestMs) {
                longestMs = runMs
                longestKey = runKey
            }
        }
        if (totalMs < minTotalMs) return null

        val shares = msBySpeaker.entries
            .map { (key, ms) ->
                SpeakerShare(
                    name = key,
                    ms = ms,
                    percent = (ms * 100 / totalMs).toInt().coerceIn(0, 100),
                    turns = turnsBySpeaker[key] ?: 0
                )
            }
            // Named speakers by share first; the unattributed bucket last.
            .sortedWith(
                compareBy<SpeakerShare> { it.name == UNATTRIBUTED }
                    .thenByDescending { it.ms }
            )

        val wpm = if (totalMs > 0) {
            (totalWords * 60_000L / totalMs).toInt()
        } else 0

        return Stats(
            totalSpeechMs = totalMs,
            shares = shares,
            longestMonologueMs = longestMs,
            longestMonologueSpeaker =
                longestKey?.takeIf { it != UNATTRIBUTED },
            questionCount = questions,
            wordsPerMinute = wpm
        )
    }
}
