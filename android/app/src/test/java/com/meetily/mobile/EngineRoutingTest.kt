package com.meetily.mobile

import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.llm.EngineRouting
import com.meetily.mobile.llm.EngineRouting.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who answers a chat call.
 *
 * This existed nowhere before a second on-device runtime arrived: the choice
 * lived inside two SharedPreferences-backed `isSelected()` calls that a JVM
 * test cannot reach. A runtime wired in but never selected, or one that
 * captured the endpoint path, would have left all 36 other test files green.
 */
class EngineRoutingTest {

    @Test
    fun defaultsRouteToLlamaCppWhenOnDevice() {
        // localLlmRuntime defaults to "llama", so every existing install
        // keeps the engine it already had after an update.
        assertEquals(
            Target.LLAMA,
            EngineRouting.resolve("local", AppSettings.RUNTIME_LLAMA)
        )
    }

    @Test
    fun liteRtOnlyClaimsTheCallWhenBothSettingsAgree() {
        assertEquals(
            Target.LITERT,
            EngineRouting.resolve("local", AppSettings.RUNTIME_LITERT)
        )
    }

    @Test
    fun exactlyOneEngineEverClaimsACall() {
        // The two isSelected() methods are independent booleans in
        // production. If both could answer true, LlmClient's ordering would
        // silently decide; if neither could, local chat would fall through
        // to the endpoint path and open a socket.
        val engines = listOf("local", "endpoint", "", "something-new")
        val runtimes = listOf(
            AppSettings.RUNTIME_LLAMA,
            AppSettings.RUNTIME_LITERT,
            "",
            "future-runtime"
        )
        for (engine in engines) {
            for (runtime in runtimes) {
                val target = EngineRouting.resolve(engine, runtime)
                val claims = listOf(
                    target == Target.LLAMA,
                    target == Target.LITERT,
                    target == Target.ENDPOINT
                ).count { it }
                assertEquals("$engine/$runtime resolved ambiguously", 1, claims)
            }
        }
    }

    @Test
    fun choosingLiteRtDoesNotSendAnythingOffDevice() {
        // Six places in the app ask "is the engine local?" to mean "nothing
        // leaves the phone" — the privacy indicator, llmConfigured, the
        // unattended-summary gate, the endpoint-consent prompt. Both runtimes
        // must keep answering yes, which is why the runtime is a separate
        // setting rather than a third value of llmEngine.
        assertTrue(EngineRouting.staysOnDevice("local"))
        assertFalse(EngineRouting.staysOnDevice("endpoint"))
        for (runtime in listOf(AppSettings.RUNTIME_LLAMA, AppSettings.RUNTIME_LITERT)) {
            assertTrue(
                "runtime $runtime must not change the privacy answer",
                EngineRouting.staysOnDevice("local")
            )
            assertTrue(EngineRouting.resolve("local", runtime) != Target.ENDPOINT)
        }
    }

    @Test
    fun anUnknownRuntimeFallsBackRatherThanStranding() {
        // A key written by a newer build must not leave an install with no
        // working engine — the rule TranscriptionModels and NemoModel.legacy
        // already follow.
        assertEquals(Target.LLAMA, EngineRouting.resolve("local", "runtime-from-the-future"))
        assertEquals(Target.LLAMA, EngineRouting.resolve("local", ""))
    }

    @Test
    fun anUnknownEngineNeverSilentlyBecomesOnDevice() {
        // Erring the other way would be worse: it would claim on-device
        // privacy for a configuration that has not been established as such.
        assertEquals(Target.ENDPOINT, EngineRouting.resolve("", AppSettings.RUNTIME_LLAMA))
        assertFalse(EngineRouting.staysOnDevice(""))
    }
}
