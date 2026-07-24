package com.meetily.mobile

/**
 * Coalesces download-progress values before they reach the main thread.
 *
 * A value is posted only when the percentage actually changed AND the
 * minimum interval has elapsed since the last post — with two deliberate
 * exceptions: the very first value always posts (so a bar leaves 0
 * immediately), and 100 always posts (so a run never ends on a suppressed
 * tick). This is defence in depth behind each downloader's own dedupe:
 * whatever a downloader does, the UI and the notification see at most a
 * couple of updates per second.
 *
 * Construct ONE PER DOWNLOAD RUN. A shared instance would carry the
 * previous item's final percent into the next queued item and swallow its
 * early progress.
 *
 * Pure (clock is injected) — unit-tested.
 */
class ProgressThrottle(private val minIntervalMs: Long = 500L) {

    private var lastPosted = -1
    private var lastPostMs = 0L

    /** True when [percent] should be published at [nowMs]. */
    fun shouldPost(percent: Int, nowMs: Long): Boolean {
        if (percent == lastPosted) return false
        val first = lastPosted < 0
        if (!first && percent < 100 && nowMs - lastPostMs < minIntervalMs) return false
        lastPosted = percent
        lastPostMs = nowMs
        return true
    }
}
