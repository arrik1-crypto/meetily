package com.meetily.mobile.data

import java.io.File

/**
 * Picks which build of the whisper/llama JNI libraries this phone can run.
 *
 * ggml's matrix kernels are chosen at COMPILE time: built for plain
 * armv8-a, every quantized dot product falls back to emulated SDOT and every
 * F16 op converts through f32, on phones whose cores have had the real
 * instructions since 2018. Built for armv8.2-a+dotprod+fp16 instead, the
 * same code dies with SIGILL on the Cortex-A53/A73 phones minSdk 26 still
 * admits.
 *
 * So both are shipped (the ":native-v82" module builds the second pair,
 * arm64 only) and this chooses one before the first System.loadLibrary.
 * Only one of a pair is ever loaded: they export the same JNI symbols.
 */
object CpuFeatures {

    /** Suffix of the dotprod+fp16 build; see native-v82/build.gradle.kts. */
    private const val V82_SUFFIX = "_v82"

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

    /**
     * Loads [baseName], or its dotprod+fp16 build when this CPU can run it.
     * Falls back to the baseline if the fast build is missing (x86_64, or a
     * build without the module) or fails to load. Throws what the baseline
     * load throws, exactly as a plain System.loadLibrary would.
     */
    fun loadEngineLibrary(baseName: String) {
        if (dotprodFp16) {
            try {
                System.loadLibrary(baseName + V82_SUFFIX)
                return
            } catch (_: UnsatisfiedLinkError) {
                // Not packaged for this ABI; the baseline is.
            }
        }
        System.loadLibrary(baseName)
    }
}
