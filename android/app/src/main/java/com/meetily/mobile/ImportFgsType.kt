package com.meetily.mobile

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo

/**
 * Which foreground-service type ImportService runs under.
 *
 * ImportService does every transcription of a saved recording: files the
 * user imports, the transcript of a meeting captured as audio only (the
 * default, queued by RecordingService when it finishes), and accuracy
 * checks. Android 15 allows dataSync foreground services six hours in any
 * 24, and that allowance is shared by all of an app's dataSync services,
 * which here included model downloads and summaries too. Two long CPU
 * transcriptions and a download in one day ended in onTimeout with a partial
 * transcript.
 *
 * mediaProcessing, new in Android 15, has a six-hour allowance of its own:
 * "the six-hour time limit is tracked separately for dataSync and for
 * mediaProcessing services" (developer.android.com, Foreground service
 * timeouts). Decoding a recorded audio file and transcribing it is the
 * processing of a media asset that the type describes.
 *
 * Below Android 15 the service stays dataSync, as it always was: the time
 * limits came with 15, so there is no allowance to gain, and mediaProcessing
 * is a type 15 introduced. Below Android 10 startForeground takes no type.
 */
object ImportFgsType {

    /** First API level with the mediaProcessing type and its own allowance. */
    const val MEDIA_PROCESSING_FROM_SDK = 35

    /** First API level whose startForeground takes a type. */
    const val TYPED_START_FROM_SDK = 29

    /**
     * The type to pass to startForeground on [sdkInt]: one type only, so a
     * run is always charged to one known allowance. Android times a service
     * started with both against just one of them (ActiveServices,
     * getTimeLimitedFgsType). 0 below [TYPED_START_FROM_SDK], where the
     * untyped startForeground is called instead and this is never passed.
     */
    @SuppressLint("InlinedApi") // only ever passed on the API level it names
    fun forSdk(sdkInt: Int): Int = when {
        sdkInt >= MEDIA_PROCESSING_FROM_SDK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        sdkInt >= TYPED_START_FROM_SDK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }
}
