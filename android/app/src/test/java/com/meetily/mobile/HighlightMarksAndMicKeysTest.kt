package com.meetily.mobile

import com.meetily.mobile.data.HighlightMarks
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.whisper.MicDeviceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightMarksAndMicKeysTest {

    private fun seg(audioMs: Long?, highlighted: Boolean = false) =
        TranscriptSegment(
            timestampMs = 0L, text = "x", highlighted = highlighted, audioMs = audioMs
        )

    @Test
    fun markStarsTheLineBeingSpoken() {
        val segments = mutableListOf(seg(0L), seg(5_000L), seg(12_000L))
        HighlightMarks.apply(segments, listOf(7_000L))
        assertFalse(segments[0].highlighted)
        assertTrue(segments[1].highlighted)
        assertFalse(segments[2].highlighted)
    }

    @Test
    fun markBeforeFirstLineStarsTheFirstAndAfterLastStarsTheLast() {
        val segments = mutableListOf(seg(2_000L), seg(5_000L))
        HighlightMarks.apply(segments, listOf(500L, 60_000L))
        assertTrue(segments[0].highlighted)
        assertTrue(segments[1].highlighted)
    }

    @Test
    fun untimedLinesAreSkippedAndApplyIsIdempotent() {
        val segments = mutableListOf(seg(null), seg(1_000L), seg(null))
        HighlightMarks.apply(segments, listOf(3_000L))
        HighlightMarks.apply(segments, listOf(3_000L))
        assertFalse(segments[0].highlighted)
        assertTrue(segments[1].highlighted)
        assertFalse(segments[2].highlighted)
    }

    @Test
    fun noMarksOrNoTimedLinesChangeNothing() {
        val segments = mutableListOf(seg(null), seg(null))
        HighlightMarks.apply(segments, listOf(1_000L))
        HighlightMarks.apply(segments, emptyList())
        assertFalse(segments.any { it.highlighted })
    }

    @Test
    fun builtInMicsWithDifferentAddressesGetDifferentKeys() {
        val bottom = MicDeviceKeys.key(15, "Pixel", "bottom")
        val back = MicDeviceKeys.key(15, "Pixel", "back")
        assertNotEquals(bottom, back)
        assertEquals(1, MicDeviceKeys.indexOf(listOf(bottom, back), back))
        assertEquals("15|Pixel", MicDeviceKeys.key(15, "Pixel", " "))
    }

    @Test
    fun legacyAndMovedKeysFallBackToTypeAndName() {
        val keys = listOf(
            "auto", MicDeviceKeys.key(15, "Pixel", "bottom"), "11|USB Mic|card=2"
        )
        assertEquals(1, MicDeviceKeys.indexOf(keys, "15|Pixel"))
        assertEquals(2, MicDeviceKeys.indexOf(keys, "11|USB Mic|card=1"))
        assertEquals(-1, MicDeviceKeys.indexOf(keys, "22|Headset"))
    }

    @Test
    fun nameOfTakesOnlyTheProductName() {
        assertEquals("Pixel", MicDeviceKeys.nameOf("15|Pixel|back"))
        assertEquals("Pixel", MicDeviceKeys.nameOf("15|Pixel"))
        assertEquals("odd", MicDeviceKeys.nameOf("odd"))
    }
}
