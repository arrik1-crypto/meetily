package com.meetily.mobile.data

import android.content.Context
import com.meetily.mobile.JobGate
import com.meetily.mobile.Power
import com.meetily.mobile.search.MeetingGroups

/**
 * Starts the unattended summary once a meeting actually has words.
 *
 * Shared because there are now two moments that can be "the transcript just
 * arrived", and they are not the same moment:
 *
 *  - Live transcription on: the recording ends holding its own transcript, so
 *    the summary can start the instant the meeting finishes.
 *  - Live transcription off (the default): the recording ends holding nothing
 *    but audio. Starting a summary there would hand the LLM an empty
 *    transcript — and queueing one alongside the transcription is no better,
 *    because the queue runs a single job at a time and would reach the summary
 *    first. It has to wait for ImportService to finish the words.
 *
 * Keeping the decision in one place is what stops those two paths drifting
 * apart, which is exactly how a feature ends up silently never firing.
 */
object AutoSummary {

    /** Below this a "meeting" is a stray tap, not something to spend an LLM on. */
    const val MIN_SEGMENTS = 3

    fun maybeStart(
        context: Context,
        meetingId: String,
        title: String,
        createdAtMs: Long,
        segmentCount: Int
    ) {
        try {
            val settings = AppSettings(context)
            if (!settings.autoSummaryAllowed) return
            if (segmentCount < MIN_SEGMENTS) return
            val chargingOnly = waitForCharger(
                settings.autoSummaryWhen, Power.allowsHeavyWork(context)
            )
            // Series memory still wins, so a standup keeps summarising as a
            // standup; otherwise the explicit automatic-summary style.
            // Untitled recordings are not a series (MeetingGroups.Untitled).
            val seriesKey = MeetingGroups.normalizeTitle(title).takeIf {
                it.isNotBlank() &&
                    !MeetingGroups.Untitled.of(context).isUntitled(title, createdAtMs)
            }
            val template = seriesKey?.let { settings.seriesTemplate(it) }
                ?: settings.autoSummaryTemplate
            // Marked automatic, so a drain hours later re-checks the consent
            // checked above instead of inheriting it.
            JobGate.requestSummary(context, meetingId, template, chargingOnly, auto = true)
        } catch (_: Throwable) {
            // A bonus pass must never break finishing a meeting or an import.
        }
    }

    /**
     * Whether the summary waits for a charger: when the setting says so
     * ([autoSummaryWhen] "charging"), and also when the battery is low off
     * the charger (![heavyWorkAllowed], Power.allowsHeavyWork). That case
     * used to drop the run, and as this is the only moment a meeting is ever
     * offered one, it then never got its summary, with nothing said.
     */
    fun waitForCharger(autoSummaryWhen: String, heavyWorkAllowed: Boolean): Boolean =
        autoSummaryWhen == "charging" || !heavyWorkAllowed
}
