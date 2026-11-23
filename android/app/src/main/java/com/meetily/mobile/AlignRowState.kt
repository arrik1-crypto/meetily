package com.meetily.mobile

/**
 * What Settings' word re-timing row (AlignModelRow) shows, and when it works
 * that out again after a download of its model ends. Kept apart from the
 * row's views so the order of events against ModelDownloadService is tested
 * on the JVM (AlignRowStateTest).
 *
 * ModelDownloadService.finishRun tells its observer that a download is done
 * before it clears isRunning and currentKind, or installs the next queued
 * download, all within one main-thread message. Worked out inside that
 * callback, the row still read the finished download as running: it showed
 * "Downloading… 0%" with its button off, and nothing drew it again, so the
 * "Downloaded, not unpacked yet" state a stopped or full-disk unpack leaves
 * never appeared while Settings was open. [onDone] therefore works it out
 * only once that message has finished ([later]).
 */
internal class AlignRowState(
    /** Whether ModelDownloadService is downloading this model right now. */
    private val aligning: () -> Boolean,
    /** Whether the model is unpacked and ready. */
    private val downloaded: () -> Boolean,
    /** Whether its download is on the phone but not unpacked. */
    private val kept: () -> Boolean,
    /** Runs a block after the main-thread message running now has finished. */
    private val later: (() -> Unit) -> Unit,
    /** Draws what the row shows. */
    private val draw: (Shown) -> Unit
) {
    /** The row's states, in the order they are checked. */
    enum class Shown { DOWNLOADING, DOWNLOADED, KEPT, MISSING }

    /** What the row shows now: the download first, then what is on disk. */
    fun shown(): Shown = when {
        aligning() -> Shown.DOWNLOADING
        downloaded() -> Shown.DOWNLOADED
        kept() -> Shown.KEPT
        else -> Shown.MISSING
    }

    /** Draws what the row shows now. */
    fun render() = draw(shown())

    /**
     * A download of the model ended (finished, failed or cancelled), as the
     * service's observer hears it: draws once the service has settled, so
     * the row shows what is on disk, or the next queued download of the
     * model, never the download that just ended.
     */
    fun onDone() = later { render() }
}
