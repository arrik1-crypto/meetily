package com.meetily.mobile

import com.meetily.mobile.whisper.NemoModels
import com.meetily.mobile.whisper.NemoProgressAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the NeMo download UI stall: the aggregate progress
 * of a ~632 MB multi-file model must cost at most 101 callbacks, not one
 * per socket read, and the service-side throttle must coalesce whatever
 * still gets through without ever going stale or eating the final tick.
 */
class ModelDownloadProgressTest {

    private val mb = 1024L * 1024L

    /** The real Parakeet/Nemotron manifest: 622 + 7 + 2 + 1 MB. */
    private val files = NemoModels.ALL.first().files
    private val totalBytes = NemoModels.ALL.first().totalMb * mb

    /** Drives a whole download at [readSize] increments, collecting emissions. */
    private fun runDownload(readSize: Long): List<Int> {
        val out = mutableListOf<Int>()
        val agg = NemoProgressAggregator(totalBytes)
        for (file in files) {
            val nominal = file.sizeMb * mb
            var read = 0L
            while (read < nominal) {
                read = (read + readSize).coerceAtMost(nominal)
                agg.onBytes(read, nominal)?.let { out.add(it) }
            }
            agg.onFileDone(nominal)?.let { out.add(it) }
        }
        return out
    }

    @Test
    fun wholeDownloadCostsAtMost101Callbacks() {
        // 8 KB reads (what OkHttp-backed HttpURLConnection actually returns)
        // would be ~80,000 raw callbacks before the dedupe.
        val small = runDownload(8 * 1024L)
        assertTrue("emitted ${small.size}", small.size <= 101)
        // ...and the 256 KB buffer case (~2,500 raw callbacks).
        val large = runDownload(256 * 1024L)
        assertTrue("emitted ${large.size}", large.size <= 101)
    }

    @Test
    fun progressIsMonotonicInRangeAndEndsAt100() {
        val emissions = runDownload(8 * 1024L)
        assertTrue(emissions.isNotEmpty())
        var previous = -1
        for (p in emissions) {
            assertTrue("out of range: $p", p in 0..100)
            assertTrue("went backwards: $previous -> $p", p >= previous)
            previous = p
        }
        assertEquals(100, emissions.last())
    }

    @Test
    fun firstTinyReadEmitsZeroExactlyOnce() {
        val agg = NemoProgressAggregator(totalBytes)
        val nominal = files.first().sizeMb * mb
        // 0% drives the indeterminate state in the UI, so it must arrive...
        assertEquals(0, agg.onBytes(4096L, nominal))
        // ...and must not be repeated.
        assertNull(agg.onBytes(8192L, nominal))
    }

    @Test
    fun resumePathDoesNotEmitRedundantly() {
        // Every file already complete: file-granular resume, no downloads.
        val agg = NemoProgressAggregator(totalBytes)
        val emissions = files.mapNotNull { agg.onFileDone(it.sizeMb * mb) }
        assertTrue("emitted ${emissions.size}", emissions.size <= files.size)
        assertEquals(100, emissions.last())
        assertEquals(emissions.size, emissions.toSet().size)
    }

    @Test
    fun progressIsByteWeightedNotFileCounted() {
        val agg = NemoProgressAggregator(totalBytes)
        // The 622 MB encoder is ~98% of the download, not 25% (1 of 4 files).
        assertEquals(98, agg.onFileDone(files.first().sizeMb * mb))
    }

    @Test
    fun zeroTotalDoesNotDivideByZero() {
        val agg = NemoProgressAggregator(0L)
        assertEquals(100, agg.onFileDone(1L))
    }

    // --- ProgressThrottle ---------------------------------------------------

    @Test
    fun throttlePostsFirstValueImmediately() {
        val throttle = ProgressThrottle(500L)
        assertTrue(throttle.shouldPost(0, 1_000L))
    }

    @Test
    fun throttleSuppressesRepeatsForever() {
        val throttle = ProgressThrottle(500L)
        assertTrue(throttle.shouldPost(7, 1_000L))
        assertFalse(throttle.shouldPost(7, 1_200L)) // inside the window
        assertFalse(throttle.shouldPost(7, 9_000L)) // dedupe outlives it
    }

    @Test
    fun throttleHoldsChangesInsideTheWindowAndReleasesAfter() {
        val throttle = ProgressThrottle(500L)
        assertTrue(throttle.shouldPost(1, 1_000L))
        assertFalse(throttle.shouldPost(2, 1_400L))
        assertTrue(throttle.shouldPost(3, 1_500L))
    }

    @Test
    fun throttleAlwaysLetsCompletionThrough() {
        val throttle = ProgressThrottle(500L)
        assertTrue(throttle.shouldPost(4, 1_000L))
        // 100 must never be swallowed by a window, or the bar sticks.
        assertTrue(throttle.shouldPost(100, 1_001L))
    }

    @Test
    fun freshThrottlePerRunLetsTheNextQueuedItemStartAtZero() {
        val first = ProgressThrottle(500L)
        assertTrue(first.shouldPost(98, 1_000L))
        assertTrue(first.shouldPost(100, 1_010L))
        // A new run constructs a new throttle: 0 posts despite being lower.
        val second = ProgressThrottle(500L)
        assertTrue(second.shouldPost(0, 1_020L))
    }

    @Test
    fun throttleBoundsPostsOverARealisticDownload() {
        // 8 KB reads through the aggregator, then the throttle, with a clock
        // advancing as a 632 MB download would over ~60s.
        val throttle = ProgressThrottle(500L)
        val emissions = runDownload(8 * 1024L)
        val elapsedMs = 60_000L
        val step = elapsedMs / emissions.size.coerceAtLeast(1)
        var now = 0L
        var posted = 0
        for (p in emissions) {
            now += step
            if (throttle.shouldPost(p, now)) posted++
        }
        assertTrue("posted $posted", posted <= elapsedMs / 500L + 2)
    }
}
