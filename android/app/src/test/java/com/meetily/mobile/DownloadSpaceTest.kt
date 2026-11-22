package com.meetily.mobile

import com.meetily.mobile.data.DownloadSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The free-space check before a model download: size plus 10%, and the shortfall. */
class DownloadSpaceTest {

    private val mib = 1024L * 1024L

    @Test
    fun requiredIsTheModelSizePlusTenPercent() {
        assertEquals(110L * mib, DownloadSpace.requiredBytes(100))
        assertEquals(75L * mib * 11 / 10, DownloadSpace.requiredBytes(75))
        // Rounded up, never down: 1.1 MiB is 1153433.6 bytes.
        assertEquals(1_153_434L, DownloadSpace.requiredBytes(1))
        assertEquals(0L, DownloadSpace.requiredBytes(0))
        assertEquals(0L, DownloadSpace.requiredBytes(-5))
    }

    @Test
    fun largeModelsDoNotOverflow() {
        // Qwen 3.5 9B, the largest model in the catalogue.
        assertEquals(5680L * mib * 11 / 10, DownloadSpace.requiredBytes(5680))
        assertTrue(DownloadSpace.requiredBytes(5680) > 6_000_000_000L)
    }

    @Test
    fun queuedDownloadsAreCheckedTogether() {
        // The first-run "Download both": Small Q plus Qwen 2.5 1.5B.
        assertEquals(
            DownloadSpace.requiredBytes(181) + DownloadSpace.requiredBytes(1120),
            DownloadSpace.requiredBytes(listOf(181, 1120))
        )
        assertEquals(0L, DownloadSpace.requiredBytes(emptyList()))
    }

    @Test
    fun shortfallIsWhatIsStillMissing() {
        assertEquals(600L, DownloadSpace.shortfallBytes(1_000L, 400L))
        assertEquals(0L, DownloadSpace.shortfallBytes(1_000L, 1_000L))
        assertEquals(0L, DownloadSpace.shortfallBytes(1_000L, 5_000L))
        // A nonsense negative reading counts as no room at all.
        assertEquals(1_000L, DownloadSpace.shortfallBytes(1_000L, -1L))
    }

    @Test
    fun cachesAreClearedOnlyWhenThatIsWhatMakesTheRoom() {
        // Free space alone is short, allocatable space covers it: clear.
        assertTrue(DownloadSpace.shouldClearCaches(1_000L, 500L, 1_500L))
        // Already enough free space: nothing to clear.
        assertFalse(DownloadSpace.shouldClearCaches(1_000L, 1_500L, 2_000L))
        // Not enough even then: the user is asked instead.
        assertFalse(DownloadSpace.shouldClearCaches(1_000L, 500L, 900L))
    }
}
