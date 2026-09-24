package com.meetily.mobile.data

/**
 * One place that decides how much CPU the heavy engines may take.
 *
 * Whisper, the NeMo transducer and llama.cpp each used to size their own
 * pool from `availableProcessors().coerceIn(2, 6)`, with no knowledge of
 * each other. Running an import, a summary and a live recording at once
 * therefore asked for ~18 native compute threads on an 8-core phone, all at
 * normal priority, and the UI thread lost — repeated ANRs while recording.
 *
 * Java thread priorities do not propagate into threads spawned inside
 * whisper.cpp/llama.cpp, so the only lever that actually works is the thread
 * COUNT. That is what this object hands out.
 */
object HeavyWork {

    const val MIN_THREADS = 2
    const val MAX_THREADS = 6

    /**
     * Live capture has to keep up with the microphone in real time and its
     * input is gone forever if it falls behind, so it is sized first and
     * never yields to batch work.
     */
    fun recordingThreads(cores: Int): Int =
        (cores / 2).coerceIn(MIN_THREADS, MAX_THREADS)

    /**
     * Threads for one batch job (import or summary). [batchJobs] is how many
     * are meant to run at once — admission control keeps that at 1, but the
     * arithmetic holds either way.
     *
     * One core is always held back for the UI thread; that reservation is
     * the difference between "slow" and "the system kills us".
     *
     * Also capped at [perfCores] (0 = unknown, no cap). ggml splits each op
     * evenly and waits at a barrier after every graph node, so a thread on a
     * LITTLE core holds up the big ones at every step while burning power
     * spinning: on a 4-big + 4-LITTLE phone, 6 threads are no faster than 4.
     */
    fun batchThreads(
        cores: Int,
        recordingActive: Boolean,
        batchJobs: Int = 1,
        perfCores: Int = 0
    ): Int {
        val reserved = (if (recordingActive) recordingThreads(cores) else 0) + 1
        val left = cores - reserved
        val jobs = batchJobs.coerceAtLeast(1)
        val budget = (left / jobs).coerceIn(MIN_THREADS, MAX_THREADS)
        if (perfCores <= 0) return budget
        return minOf(budget, perfCores.coerceAtLeast(MIN_THREADS))
    }

    /**
     * How many cores run at close to the fastest core's top clock: the big
     * and prime cores, not the efficiency cluster. [maxFreqs] holds each
     * core's cpuinfo_max_freq, null where unreadable. 0 unless every core
     * could be read, which [batchThreads] treats as "no cap": a core that
     * is hotplugged off can hide its cpufreq node, and counting only the
     * visible ones could cap the pool below the big cores that exist.
     */
    fun performanceCores(maxFreqs: List<Long?>): Int {
        if (maxFreqs.any { it == null || it <= 0 }) return 0
        val known = maxFreqs.filterNotNull()
        val top = known.maxOrNull() ?: return 0
        return known.count { it >= top * PERF_CORE_RATIO }
    }

    private const val PERF_CORE_RATIO = 0.8

    private fun cores(): Int = Runtime.getRuntime().availableProcessors()

    /** Read once: the topology does not change while the app runs. */
    private val perfCores: Int by lazy {
        try {
            performanceCores((0 until cores()).map { cpu ->
                try {
                    java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                        .readText().trim().toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            })
        } catch (_: Throwable) {
            0
        }
    }

    fun recordingThreads(): Int = recordingThreads(cores())

    fun batchThreads(recordingActive: Boolean): Int =
        batchThreads(cores(), recordingActive, 1, perfCores)
}
