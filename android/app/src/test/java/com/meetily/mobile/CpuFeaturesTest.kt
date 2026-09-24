package com.meetily.mobile

import com.meetily.mobile.data.CpuFeatures
import com.meetily.mobile.data.HeavyWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuFeaturesTest {

    private val v82 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp " +
        "cpuid asimdrdm lrcpc dcpop asimddp"
    private val v80 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"

    private fun cpuinfo(vararg features: String) = features.mapIndexed { i, f ->
        "processor\t: $i\nBogoMIPS\t: 38.40\nFeatures\t: $f\nCPU implementer\t: 0x41\n"
    }.joinToString("\n")

    @Test
    fun dotprodPhoneUsesTheFastBuild() {
        assertTrue(CpuFeatures.hasDotprodFp16(cpuinfo(v82, v82, v82, v82)))
    }

    @Test
    fun cortexA53PhoneStaysOnBaseline() {
        // Running the armv8.2 build here is SIGILL on the first matmul.
        assertFalse(CpuFeatures.hasDotprodFp16(cpuinfo(v80, v80)))
    }

    @Test
    fun oneCoreWithoutTheFeaturesDisqualifiesThePhone() {
        // A thread can be scheduled on any core.
        assertFalse(CpuFeatures.hasDotprodFp16(cpuinfo(v82, v82, v80)))
    }

    @Test
    fun unreadableOrEmptyCpuinfoIsNotAYes() {
        assertFalse(CpuFeatures.hasDotprodFp16(""))
        assertFalse(CpuFeatures.hasDotprodFp16("processor\t: 0\nHardware\t: Qualcomm\n"))
    }

    @Test
    fun performanceCoresAreThoseNearTheTopClock() {
        // Pixel 9 style 1+3+4: X4 3.1 GHz, A720 2.6 GHz, A520 1.95 GHz.
        val freqs = listOf(1_950_000L, 1_950_000L, 1_950_000L, 1_950_000L,
            2_600_000L, 2_600_000L, 2_600_000L, 3_100_000L)
        assertEquals(4, HeavyWork.performanceCores(freqs))
        // Symmetric SoC: every core counts.
        assertEquals(8, HeavyWork.performanceCores(List(8) { 2_000_000L }))
        // Nothing readable: no cap.
        assertEquals(0, HeavyWork.performanceCores(listOf(null, null)))
        // One core unreadable (hotplugged off): unknown, not a smaller count.
        assertEquals(0, HeavyWork.performanceCores(listOf(3_000_000L, null, 1_800_000L)))
        assertEquals(0, HeavyWork.performanceCores(emptyList()))
    }

    @Test
    fun batchThreadsAreCappedAtThePerformanceCores() {
        assertEquals(4, HeavyWork.batchThreads(8, recordingActive = false, batchJobs = 1, perfCores = 4))
        // The floor still holds on a phone with a single big core.
        assertEquals(2, HeavyWork.batchThreads(8, recordingActive = false, batchJobs = 1, perfCores = 1))
        // Unknown topology (0) leaves the old budget alone.
        assertEquals(6, HeavyWork.batchThreads(8, recordingActive = false, batchJobs = 1, perfCores = 0))
        // Never raises a budget that recording already cut.
        assertEquals(3, HeavyWork.batchThreads(8, recordingActive = true, batchJobs = 1, perfCores = 5))
    }
}
