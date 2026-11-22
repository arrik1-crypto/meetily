package com.meetily.mobile.data

import java.io.File

/**
 * Says which build of the native engine library this phone can run.
 *
 * ggml's matrix kernels are chosen at COMPILE time: built for plain
 * armv8-a, every quantized dot product falls back to emulated SDOT and every
 * F16 op converts through f32, on phones whose cores have had the real
 * instructions since 2018. Built for armv8.2-a+dotprod+fp16 instead, the
 * same code dies with SIGILL on the Cortex-A53/A73 phones minSdk 26 still
 * admits.
 *
 * So both are shipped (the ":native-v82" module builds the second one,
 * arm64 only), and [NativeEngines] loads the one this answer picks.
 */
object CpuFeatures {

    /**
     * True when EVERY core listed in [cpuinfo] reports dot-product and
     * half-precision SIMD (asimddp, asimdhp, fphp).
     *
     * Every core, because the scheduler may run an engine thread on any of
     * them. No "Features" line at all means we cannot tell, which is no.
     */
    fun hasDotprodFp16(cpuinfo: String): Boolean {
        var seen = 0
        for (line in cpuinfo.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            if (line.substring(0, colon).trim() != "Features") continue
            val flags = line.substring(colon + 1).trim().split(Regex("\\s+")).toSet()
            if ("asimddp" !in flags || "asimdhp" !in flags || "fphp" !in flags) return false
            seen++
        }
        return seen > 0
    }

    val dotprodFp16: Boolean by lazy {
        try {
            hasDotprodFp16(File("/proc/cpuinfo").readText())
        } catch (_: Throwable) {
            false
        }
    }
}
