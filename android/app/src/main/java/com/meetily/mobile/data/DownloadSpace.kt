package com.meetily.mobile.data

/**
 * The free-space arithmetic every model download is checked against before
 * it starts (ModelSpaceCheck asks Android for the numbers).
 *
 * A download used to find out it had no room only when a write failed,
 * possibly an hour into a 5 GB model. Pure, so it is unit-tested.
 */
object DownloadSpace {

    /** The catalogues' "MB" are binary megabytes (see WhisperModels.isDownloaded). */
    private const val MIB = 1024L * 1024L

    /**
     * Bytes to ask for before downloading a model of [sizeMb]: its size plus
     * 10%. The partial file is renamed in place, so a model needs its own
     * size once; the margin covers catalogue sizes that run below what the
     * server actually sends (see NemoModels.fileComplete) and leaves the
     * phone a little room to work in.
     */
    fun requiredBytes(sizeMb: Int): Long = (sizeMb.coerceAtLeast(0) * MIB * 11 + 9) / 10

    /** [requiredBytes] for several downloads queued together. */
    fun requiredBytes(sizesMb: Iterable<Int>): Long = sizesMb.sumOf { requiredBytes(it) }

    /**
     * MB a download of [sizeMb] that is [percentDone] through still has to
     * write: the part already written is on disk and counted as used.
     * Rounded up, so the check never asks for less than is left.
     */
    fun remainingMb(sizeMb: Int, percentDone: Int): Int =
        (sizeMb.coerceAtLeast(0) * (100 - percentDone.coerceIn(0, 100)) + 99) / 100

    /**
     * Download sizes, MB, the free-space check for a new download of [item]
     * has to make room for: [ownMb], what the new download itself needs,
     * plus what the download service still has to write first when the new
     * one would wait behind it. That is the rest of [current] (the download
     * running now, [currentPercent] through; null when none runs) unless it
     * is [item] itself, and every download in [queued] other than [item].
     * Checking the new model alone let it pass and then run out of room
     * behind a large one. Items are (kind, key) pairs; [sizeMb] gives an
     * item's full download size.
     */
    fun sizesWithQueueMb(
        ownMb: Int,
        item: Pair<String, String>,
        current: Pair<String, String>?,
        currentPercent: Int,
        queued: List<Pair<String, String>>,
        sizeMb: (Pair<String, String>) -> Int
    ): List<Int> {
        val sizes = mutableListOf(ownMb)
        if (current != null && current != item) {
            sizes += remainingMb(sizeMb(current), currentPercent)
        }
        for (waiting in queued) {
            if (waiting != item) sizes += sizeMb(waiting)
        }
        return sizes
    }

    /** How much more room is needed; 0 when [availableBytes] is enough. */
    fun shortfallBytes(requiredBytes: Long, availableBytes: Long): Long =
        (requiredBytes - availableBytes.coerceAtLeast(0L)).coerceAtLeast(0L)

    /**
     * True when the room is there only once Android clears other apps'
     * caches: allocatable space covers the download but plain free space
     * does not. StorageManager.allocateBytes then clears them up front, so
     * the download does not run out of space halfway.
     */
    fun shouldClearCaches(requiredBytes: Long, usableBytes: Long, allocatableBytes: Long): Boolean =
        usableBytes < requiredBytes && allocatableBytes >= requiredBytes
}
