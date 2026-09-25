package com.meetily.mobile

import com.meetily.mobile.data.PhotoSampling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSamplingTest {

    @Test
    fun cropSampleKeepsBothSidesAtLeastRequested() {
        // 4000x3000 into a 252 px square: 4000/8=500, 3000/8=375, /16 -> 187 < 252.
        assertEquals(8, PhotoSampling.inSampleSize(4000, 3000, 252, 252))
        val s = PhotoSampling.inSampleSize(4000, 3000, 252, 252)
        assertTrue(4000 / s >= 252 && 3000 / s >= 252)
    }

    @Test
    fun smallImageIsNotSampled() {
        assertEquals(1, PhotoSampling.inSampleSize(200, 150, 252, 252))
        assertEquals(1, PhotoSampling.inSampleSizeToFit(800, 600, 1080, 2400))
    }

    @Test
    fun badInputFallsBackToOne() {
        assertEquals(1, PhotoSampling.inSampleSize(0, 3000, 252, 252))
        assertEquals(1, PhotoSampling.inSampleSize(4000, 3000, 0, 252))
        assertEquals(1, PhotoSampling.inSampleSizeToFit(-1, 3000, 1080, 2400))
    }

    @Test
    fun fitSampleOnlyNeedsTheTighterSide() {
        // 4000x3000 fitted into 1080x2400: the width limits (scale 0.27), so
        // 4000/2=2000 >= 1080 but 4000/4=1000 < 1080 and 3000/4=750 < 2400.
        assertEquals(2, PhotoSampling.inSampleSizeToFit(4000, 3000, 1080, 2400))
        // A panorama is sampled down by its long side, where crop sizing
        // would keep it at full width because its short side is small.
        assertEquals(1, PhotoSampling.inSampleSize(12000, 1500, 1080, 2400))
        assertEquals(8, PhotoSampling.inSampleSizeToFit(12000, 1500, 1080, 2400))
    }

    @Test
    fun cacheIsASixteenthOfTheHeapWithAFloor() {
        assertEquals(16 * 1024, PhotoSampling.cacheSizeKb(256L * 1024 * 1024))
        assertEquals(1024, PhotoSampling.cacheSizeKb(1024L * 1024))
    }
}
