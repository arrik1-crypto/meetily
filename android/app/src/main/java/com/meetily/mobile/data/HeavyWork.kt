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
     */
    fun batchThreads(cores: Int, recordingActive: Boolean, batchJobs: Int = 1): Int {
        val reserved = (if (recordingActive) recordingThreads(cores) else 0) + 1
        val left = cores - reserved
        val jobs = batchJobs.coerceAtLeast(1)
        return (left / jobs).coerceIn(MIN_THREADS, MAX_THREADS)
    }

    private fun cores(): Int = Runtime.getRuntime().availableProcessors()

    fun recordingThreads(): Int = recordingThreads(cores())

    fun batchThreads(recordingActive: Boolean): Int =
        batchThreads(cores(), recordingActive, 1)
}
