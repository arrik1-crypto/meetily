package com.meetily.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SummaryService.progressPercent maps map-reduce sections onto the 0-85%
 * band of the summary progress bar (a section reports when it STARTS, so
 * section 1 of N is 0%). -1 means "indeterminate".
 */
class SummaryProgressTest {

    @Test
    fun nonPositiveTotalIsIndeterminate() {
        assertEquals(-1, SummaryService.progressPercent(1, 0))
        assertEquals(-1, SummaryService.progressPercent(3, -2))
    }

    @Test
    fun firstSectionStartsAtZero() {
        assertEquals(0, SummaryService.progressPercent(1, 1))
        assertEquals(0, SummaryService.progressPercent(1, 12))
    }

    @Test
    fun lastSectionStaysUnderWritingStage() {
        // The "writing the summary" stage sits at 88%, so even the last
        // section of the largest allowed split must report below it.
        for (total in 1..12) {
            val last = SummaryService.progressPercent(total, total)
            assertTrue("total=$total last=$last", last in 0..85)
            assertTrue("total=$total last=$last", last < 88)
        }
    }

    @Test
    fun percentIsMonotonicAcrossSections() {
        val total = 7
        var previous = -1
        for (section in 1..total) {
            val now = SummaryService.progressPercent(section, total)
            assertTrue("section=$section", now >= previous)
            assertTrue("section=$section", now in 0..85)
            previous = now
        }
    }

    @Test
    fun outOfRangeSectionsAreClamped() {
        assertEquals(0, SummaryService.progressPercent(0, 4))
        assertEquals(0, SummaryService.progressPercent(-3, 4))
        assertEquals(85, SummaryService.progressPercent(400, 4))
    }
}
