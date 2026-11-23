package com.meetily.mobile.data

/**
 * Automatic clean-up rules: remove the audio of meetings older than N days,
 * or delete whole meetings older than N days. Off unless the user turns one
 * on, and the first run is always shown to them before anything goes (see
 * RetentionUi); later runs are the daily RetentionJobService.
 *
 * This decides WHICH meetings a rule reaches. It errs towards keeping: a
 * starred meeting, one with a job running, queued or waiting for review, one
 * open on a meeting screen, one with a reminder still to come, one changed
 * in the last week, and one whose age cannot be told are never touched, and
 * the audio rule never takes a recording that was not transcribed, or whose
 * transcription is unfinished. Pure, so the selection is unit-tested.
 */
object Retention {

    enum class Mode(val key: String) {
        OFF("off"),

        /** Remove the audio and keep everything else (AudioRemoval). */
        AUDIO("audio"),

        /** Delete the meeting and everything it left on disk (MeetingAssets). */
        MEETINGS("meetings")
    }

    data class Rule(val mode: Mode, val days: Int) {
        val enabled: Boolean get() = mode != Mode.OFF && days > 0
    }

    val OFF = Rule(Mode.OFF, 0)

    /** The ages the settings offer, in days. */
    val DAY_CHOICES = listOf(30, 60, 90, 180, 365)

    const val DEFAULT_DAYS = 90

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** A meeting changed this recently is left alone, whatever its age. */
    const val RECENT_MS = 7 * DAY_MS

    /**
     * An automatic run that would delete more whole meetings than this asks
     * first instead (a notification that opens the review). A daily run
     * normally reaches a meeting or two; dozens at once means something
     * changed — a backup of old meetings restored, the clock moved — and
     * that is not something to act on unseen.
     */
    const val AUTO_MAX_MEETINGS = 10

    /** Longest rule accepted from storage; anything else reads as off. */
    private const val MAX_DAYS = 3650

    /** What the rule needs to know about one meeting. */
    data class Candidate(
        val id: String,
        val createdAtMs: Long,
        /** When the meeting's file last changed; 0 when unknown. */
        val modifiedMs: Long,
        val starred: Boolean,
        /** Bytes of kept audio on disk; 0 when none. */
        val audioBytes: Long,
        /** An action item with a reminder still to fire. */
        val hasFutureReminder: Boolean,
        /**
         * The meeting has transcript lines. One without is a recording not
         * transcribed yet (no model was downloaded when it was made, or the
         * transcription failed): its audio is all it holds.
         */
        val hasTranscript: Boolean,
        /**
         * Its transcription stopped part-way and is waiting to be continued
         * from the audio (TranscriptResume). The audio rule leaves it: an
         * automatic run cannot ask, and without the audio the rest of the
         * transcript could never be made.
         */
        val unfinished: Boolean = false
    )

    /**
     * The meetings [rule] reaches at [nowMs], oldest first. [protectedIds]
     * are meetings something is working on or waiting with: the recording,
     * a running import, accuracy check or summary, a queued or interrupted
     * job, a staged accuracy check not yet reviewed, a meeting open on a
     * meeting screen (OpenMeetings).
     */
    fun select(
        rule: Rule,
        candidates: List<Candidate>,
        protectedIds: Set<String>,
        nowMs: Long
    ): List<Candidate> {
        if (!rule.enabled) return emptyList()
        val cutoff = nowMs - rule.days * DAY_MS
        val settled = nowMs - RECENT_MS
        return candidates.filter { c ->
            // createdAtMs 0 is a meeting saved without a date: unknown age.
            c.createdAtMs in 1 until cutoff &&
                // Unknown, recent or in the future (a clock that moved): kept.
                c.modifiedMs in 1..settled &&
                !c.starred &&
                !c.hasFutureReminder &&
                c.id !in protectedIds &&
                // "Remove audio, keep transcript" needs a transcript to keep:
                // without one it would empty the meeting, and an unfinished
                // one could never be completed.
                (rule.mode != Mode.AUDIO || (c.audioBytes > 0L && c.hasTranscript && !c.unfinished))
        }.sortedWith(compareBy<Candidate> { it.createdAtMs }.thenBy { it.id })
    }

    /** True when an automatic run of [rule] over [selected] must ask first. */
    fun needsReview(rule: Rule, selected: List<Candidate>): Boolean =
        rule.mode == Mode.MEETINGS && selected.size > AUTO_MAX_MEETINGS

    /** Audio bytes [selected] holds: what removing it frees, at least. */
    fun audioBytes(selected: List<Candidate>): Long = selected.sumOf { it.audioBytes.coerceAtLeast(0L) }

    /** "audio:90", "meetings:30" or "off", for the settings file. */
    fun encode(rule: Rule): String =
        if (!rule.enabled) Mode.OFF.key else "${rule.mode.key}:${rule.days}"

    /** The rule [stored] names; off for anything missing or malformed. */
    fun decode(stored: String?): Rule {
        if (stored.isNullOrBlank()) return OFF
        val mode = Mode.values().firstOrNull { it.key == stored.substringBefore(':') } ?: return OFF
        if (mode == Mode.OFF) return OFF
        val days = stored.substringAfter(':', "").toIntOrNull() ?: return OFF
        if (days < 1 || days > MAX_DAYS) return OFF
        return Rule(mode, days)
    }
}
