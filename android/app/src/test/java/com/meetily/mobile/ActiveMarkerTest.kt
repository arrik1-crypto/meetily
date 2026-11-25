package com.meetily.mobile

import com.meetily.mobile.data.MeetingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The crash-recovery marker (MeetingStore.markActive) holds the id of the
 * meeting being recorded, and is read back by its first line.
 */
class ActiveMarkerTest {

    @Test
    fun aMarkerNamesItsMeetingByItsFirstLine() {
        assertEquals("abc", MeetingStore.markerId("abc"))
        // A marker a pre-release build left with a second line.
        assertEquals("abc", MeetingStore.markerId("abc\nlive-short"))
        assertEquals("abc", MeetingStore.markerId(" abc \n"))
        assertNull(MeetingStore.markerId(""))
        assertNull(MeetingStore.markerId("  \nlive-short"))
    }
}
