package com.meetily.mobile

import com.meetily.mobile.data.NativeEngines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class NativeEnginesTest {

    /** A System.loadLibrary stand-in that records each name it is asked for. */
    private class FakeLoader(private val fails: Map<String, Throwable> = emptyMap()) {
        val asked = ArrayList<String>()
        fun load(name: String) {
            asked.add(name)
            fails[name]?.let { throw it }
        }
    }

    @Test
    fun dotprodPhoneTriesTheFastBuildFirst() {
        assertEquals(
            listOf(NativeEngines.DOTPROD, NativeEngines.BASELINE),
            NativeEngines.candidates(dotprodFp16 = true)
        )
    }

    @Test
    fun otherPhonesNeverTryTheFastBuild() {
        // Loading it on a Cortex-A53/A73 phone would be SIGILL at the first
        // matmul, which no fallback can catch.
        assertEquals(listOf(NativeEngines.BASELINE), NativeEngines.candidates(dotprodFp16 = false))
    }

    @Test
    fun theFastBuildLoadsAndNothingElseIsTried() {
        val loader = FakeLoader()
        val name = NativeEngines.loadFirst(NativeEngines.candidates(true), loader::load)
        assertEquals(NativeEngines.DOTPROD, name)
        assertEquals(listOf(NativeEngines.DOTPROD), loader.asked)
    }

    @Test
    fun aMissingFastBuildFallsBackToTheBaseline() {
        // x86_64, or an arm64 build without :native-v82.
        val missing = UnsatisfiedLinkError("dlopen failed: library \"libmeetily_native_v82.so\" not found")
        val loader = FakeLoader(mapOf(NativeEngines.DOTPROD to missing))
        val skipped = ArrayList<Pair<String, Throwable>>()
        val name = NativeEngines.loadFirst(NativeEngines.candidates(true), loader::load) { n, e ->
            skipped.add(n to e)
        }
        assertEquals(NativeEngines.BASELINE, name)
        assertEquals(listOf(NativeEngines.DOTPROD, NativeEngines.BASELINE), loader.asked)
        assertEquals(listOf<Pair<String, Throwable>>(NativeEngines.DOTPROD to missing), skipped)
    }

    @Test
    fun theBaselinesFailureIsThrownAsIs() {
        val broken = UnsatisfiedLinkError("dlopen failed: cannot locate symbol")
        val loader = FakeLoader(
            mapOf(
                NativeEngines.DOTPROD to UnsatisfiedLinkError("v82"),
                NativeEngines.BASELINE to broken
            )
        )
        try {
            NativeEngines.loadFirst(NativeEngines.candidates(true), loader::load)
            fail("expected the baseline's error")
        } catch (e: UnsatisfiedLinkError) {
            assertSame(broken, e)
        }
        assertEquals(listOf(NativeEngines.DOTPROD, NativeEngines.BASELINE), loader.asked)
    }

    @Test
    fun onlyALinkErrorFallsThrough() {
        // Anything else is not "this build is missing": it propagates, as a
        // plain System.loadLibrary call's would, and the baseline is not tried.
        val denied = SecurityException("denied")
        val loader = FakeLoader(mapOf(NativeEngines.DOTPROD to denied))
        try {
            NativeEngines.loadFirst(NativeEngines.candidates(true), loader::load)
            fail("expected the SecurityException")
        } catch (e: SecurityException) {
            assertSame(denied, e)
        }
        assertEquals(listOf(NativeEngines.DOTPROD), loader.asked)
    }

    @Test
    fun theBaselineAloneFailsWithoutAFallback() {
        val broken = UnsatisfiedLinkError("not found")
        val loader = FakeLoader(mapOf(NativeEngines.BASELINE to broken))
        try {
            NativeEngines.loadFirst(NativeEngines.candidates(false), loader::load)
            fail("expected the baseline's error")
        } catch (e: UnsatisfiedLinkError) {
            assertSame(broken, e)
        }
        assertEquals(listOf(NativeEngines.BASELINE), loader.asked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyListIsRefused() {
        NativeEngines.loadFirst(emptyList(), FakeLoader()::load)
    }

    @Test
    fun theTwoBuildsDifferOnlyByTheV82Suffix() {
        assertEquals(NativeEngines.BASELINE + "_v82", NativeEngines.DOTPROD)
    }
}
