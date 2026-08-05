package com.meetily.mobile

import com.meetily.mobile.llm.EngineRouting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a chat call stays on the phone.
 *
 * This had no coverage before it was extracted: the choice lived inside a
 * SharedPreferences-backed `isSelected()` that a JVM test cannot reach, so an
 * engine that quietly captured the endpoint path would have left every other
 * test file green.
 */
class EngineRoutingTest {

    @Test
    fun theOnDeviceEngineStaysOnTheDevice() {
        assertTrue(EngineRouting.staysOnDevice("local"))
    }

    @Test
    fun theEndpointEngineDoesNot() {
        assertFalse(EngineRouting.staysOnDevice("endpoint"))
    }

    @Test
    fun anUnknownEngineNeverSilentlyBecomesOnDevice() {
        // Six places in the app ask this to mean "nothing leaves the phone" —
        // the privacy indicator, llmConfigured, the unattended-summary gate,
        // the endpoint-consent prompt. A key stored by a newer build, or a
        // corrupted one, must not claim on-device privacy for a configuration
        // that has not been established as such. That is the one direction of
        // this answer that cannot be walked back.
        //
        // "litert" is in the list on purpose: it was never a value of this
        // setting, but it is the string most likely to be tried by someone
        // reviving the withdrawn runtime, and it must not shortcut the check.
        for (engine in listOf("", "  ", "Local", "LOCAL", "something-new", "litert")) {
            assertFalse(
                "engine '$engine' claimed on-device",
                EngineRouting.staysOnDevice(engine)
            )
        }
    }
}
