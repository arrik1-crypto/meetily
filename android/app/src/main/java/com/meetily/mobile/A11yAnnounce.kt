package com.meetily.mobile

/**
 * Which progress updates a screen reader should speak.
 *
 * A polite live region on a percentage would speak every tick, and an
 * import ticks every second or two for an hour; with no announcement at
 * all, a blind user has no way to tell a long job is still moving. This
 * passes the first update of a run, each step of [stepPercent], the switch
 * to or from an indeterminate stage, and 100 — a handful per run.
 *
 * Construct one per screen and [reset] it when a new run starts, so the
 * first update of that run is spoken. Pure, so it is unit-tested.
 */
class ProgressAnnouncer(private val stepPercent: Int = 25) {

    private var lastKey = NONE

    /** True when [percent] (negative for indeterminate) should be spoken. */
    fun shouldAnnounce(percent: Int): Boolean {
        val key = if (percent < 0) INDETERMINATE else percent.coerceIn(0, 100) / stepPercent
        if (key == lastKey) return false
        lastKey = key
        return true
    }

    fun reset() {
        lastKey = NONE
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
        const val INDETERMINATE = -1
    }
}

/**
 * Which recording-status changes a screen reader should hear.
 *
 * The status line flips between Listening, Hearing speech and Processing
 * with every utterance, which as a live region would talk over the meeting
 * being recorded. What a user who cannot see the screen does need to hear
 * is the state they would otherwise have to look for: recording has begun,
 * paused or resumed, saving, and anything that has gone wrong — including
 * the dead-microphone warning, which keeps the current status and changes
 * only the text.
 *
 * One per recording screen. Pure, so it is unit-tested.
 */
class RecordingStatusSpeech {

    private var previous: RecordingService.Status? = null
    private var previousText: String? = null

    /** Whether capture going live has been spoken (or was already live). */
    private var live = false

    /**
     * Takes the status already on screen without speaking it: re-attaching
     * to a session that is running must not announce it again.
     *
     * Processing is not counted as live: it is also the status while a model
     * is still loading, and a screen recreated then (night mode, font scale,
     * a resize) must still hear capture go live. Re-attaching mid-chunk
     * costs at most one extra "Listening".
     */
    fun seed(status: RecordingService.Status, text: String) {
        previous = status
        previousText = text
        live = status != RecordingService.Status.STARTING &&
            status != RecordingService.Status.PROCESSING
    }

    /** True when the change to [next] / [text] should be spoken. */
    fun shouldAnnounce(next: RecordingService.Status, text: String): Boolean {
        val prev = previous
        val prevText = previousText
        previous = next
        previousText = text
        if (prev == next && prevText == text) return false
        // Same state, new words: a warning such as "no signal from the
        // microphone", or the recovery from one.
        if (prev == next) return next != RecordingService.Status.STARTING
        return when (next) {
            RecordingService.Status.PAUSED,
            RecordingService.Status.ERROR,
            RecordingService.Status.FINISHING,
            RecordingService.Status.UNAVAILABLE -> true
            // The first time capture is live (after Start, or after a model
            // load), and on resuming after a pause or a fault. The routine
            // Processing -> Listening flip after each chunk stays quiet.
            RecordingService.Status.LISTENING,
            RecordingService.Status.HEARING -> {
                val first = !live
                live = true
                first || prev == RecordingService.Status.PAUSED ||
                    prev == RecordingService.Status.ERROR ||
                    prev == RecordingService.Status.UNAVAILABLE
            }
            // A model still loading right after Start is worth a word; the
            // per-chunk Processing churn is not.
            RecordingService.Status.PROCESSING -> !live
            RecordingService.Status.STARTING -> false
        }
    }
}

/**
 * Next / previous speaker turn for the Audio tab's speaker strip, which a
 * screen-reader user cannot tap at a position.
 *
 * [starts] are turn start offsets in ms, in any order.
 */
object SpeakerTurnNav {

    /**
     * Within this much of a turn's start, "previous" goes to the turn before
     * rather than back to the start of the one playing (as media players do).
     */
    const val REPLAY_GRACE_MS = 2_000L

    /**
     * A jump lands exactly on a turn start, and playback can report a few
     * ms either side of it; "next" must not return the same turn again.
     */
    const val LANDING_SLACK_MS = 250L

    fun next(starts: List<Long>, positionMs: Long): Long? =
        starts.sorted().firstOrNull { it > positionMs + LANDING_SLACK_MS }

    fun previous(starts: List<Long>, positionMs: Long): Long? =
        starts.sorted().lastOrNull { it < positionMs - REPLAY_GRACE_MS }
            ?: starts.minOrNull()?.takeIf { it < positionMs - LANDING_SLACK_MS }
}

/**
 * How far to inset a pill drawn [visualPx] tall inside a [touchPx] touch
 * target, top and bottom, so the target grows while the pill looks the same.
 */
internal fun pillInsetPx(touchPx: Int, visualPx: Int): Int =
    ((touchPx - visualPx) / 2).coerceAtLeast(0)
