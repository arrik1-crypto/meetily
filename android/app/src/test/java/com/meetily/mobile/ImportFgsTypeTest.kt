package com.meetily.mobile

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which foreground-service type ImportService starts with: mediaProcessing,
 * with its own six-hour allowance, where Android has it; dataSync below.
 */
class ImportFgsTypeTest {

    @Test
    fun android15AndLaterTranscribeAsMediaProcessing() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, ImportFgsType.forSdk(35)
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, ImportFgsType.forSdk(36)
        )
    }

    @Test
    fun belowAndroid15ItStaysDataSync() {
        // The time limits came with Android 15, so below it there is no
        // allowance to gain, and mediaProcessing is a type 15 introduced.
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, ImportFgsType.forSdk(34))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, ImportFgsType.forSdk(29))
    }

    @Test
    fun beforeAndroid10NoTypeIsPassed() {
        assertEquals(0, ImportFgsType.forSdk(28))
        assertEquals(0, ImportFgsType.forSdk(26))
    }

    @Test
    fun exactlyOneTypeSoARunSpendsOneKnownAllowance() {
        // Both bits together would be timed against only one of the two
        // allowances, and not necessarily the new one.
        for (sdk in 29..40) {
            assertEquals("SDK $sdk", 1, Integer.bitCount(ImportFgsType.forSdk(sdk)))
        }
    }

    @Test
    fun theCutoverIsAndroid15() {
        assertEquals(35, ImportFgsType.MEDIA_PROCESSING_FROM_SDK)
        assertEquals(29, ImportFgsType.TYPED_START_FROM_SDK)
    }
}
