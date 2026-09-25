package com.meetily.mobile.data

/** Pure sizing math for photo decodes, kept free of Android types for unit tests. */
object PhotoSampling {

    /**
     * The largest power-of-two sample size that still leaves both sides of a
     * [width] x [height] image at least [reqWidth] x [reqHeight] — so a
     * centre-cropped or fitted view never upscales. 1 for bad input.
     */
    fun inSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        return sample
    }

    /**
     * The largest power-of-two sample size that still covers the image when
     * it is scaled to fit inside [reqWidth] x [reqHeight] (a full-screen
     * viewer): only the tighter side has to stay at least the requested size,
     * so a panorama is not kept at full width. 1 for bad input.
     */
    fun inSampleSizeToFit(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= reqWidth || height / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        return sample
    }

    /** Thumbnail cache budget: 1/16 of the heap, in KiB, never below 1 MiB. */
    fun cacheSizeKb(maxMemoryBytes: Long): Int =
        (maxMemoryBytes / 1024L / 16L).coerceIn(1024L, Int.MAX_VALUE.toLong()).toInt()
}
