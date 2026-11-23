package com.meetily.mobile.data

import android.util.Log

/**
 * Loads Recap's native library, which holds both ggml engines: whisper.cpp
 * (with the speech gate's Silero VAD) and llama.cpp, over one ggml.
 *
 * It is built once per CPU level (app/src/main/cpp/CMakeLists.txt):
 * [BASELINE] for plain armv8-a and x86_64, and [DOTPROD] from the
 * ":native-v82" module, compiled for armv8.2-a+dotprod+fp16, arm64 only.
 * The second is SIGILL on the Cortex-A53/A73 phones minSdk 26 still admits,
 * so it is only tried when [CpuFeatures] says every core can run it.
 *
 * The level is chosen once and the library loaded once per process, whoever
 * asks first: WhisperBridge, LlamaBridge and SileroVad (through
 * WhisperBridge) all come through [load]. Only one of the two builds may
 * ever be loaded, since they export the same JNI symbols.
 */
object NativeEngines {

    /** The baseline build, for any arm64 or x86_64 CPU. */
    const val BASELINE = "meetily_native"

    /** The dotprod+fp16 build; see native-v82/build.gradle.kts. */
    const val DOTPROD = "meetily_native_v82"

    private const val TAG = "meetily_native"

    /**
     * The library names to try, in order: the fast build first when
     * [dotprodFp16] (every core has the instructions), then the baseline.
     */
    fun candidates(dotprodFp16: Boolean): List<String> =
        if (dotprodFp16) listOf(DOTPROD, BASELINE) else listOf(BASELINE)

    /**
     * Loads the first of [names] that [loadLibrary] accepts and returns its
     * name. A name that fails with UnsatisfiedLinkError (not packaged for
     * this ABI, or refused by the dynamic linker) is reported to [skipped]
     * and the next one is tried. Anything else, and the last name's failure,
     * is thrown as is, exactly as a plain System.loadLibrary would throw it.
     */
    fun loadFirst(
        names: List<String>,
        loadLibrary: (String) -> Unit,
        skipped: (String, UnsatisfiedLinkError) -> Unit = { _, _ -> }
    ): String {
        require(names.isNotEmpty()) { "no library to load" }
        for (name in names.dropLast(1)) {
            try {
                loadLibrary(name)
                return name
            } catch (e: UnsatisfiedLinkError) {
                skipped(name, e)
            }
        }
        val last = names.last()
        loadLibrary(last)
        return last
    }

    @Volatile private var loadedName: String? = null

    /**
     * Set by the first load that fails outright. Callers ask again after a
     * failure, so the failure, and a skipped fast build before it, are logged
     * on that first attempt only.
     */
    @Volatile private var failureLogged = false

    /** The build this process loaded ([DOTPROD] or [BASELINE]), or null. */
    val loaded: String? get() = loadedName

    /**
     * Loads the library for this CPU unless it already is; true when it is
     * loaded. A failure is not remembered: the next call tries again, as each
     * bridge did when the engines were separate libraries. Callers turn
     * false into their own "runtime unavailable" message.
     */
    fun load(): Boolean {
        if (loadedName != null) return true
        return synchronized(this) {
            if (loadedName != null) return@synchronized true
            try {
                val name = loadFirst(
                    candidates(CpuFeatures.dotprodFp16),
                    { System.loadLibrary(it) },
                    { skippedName, e ->
                        if (!failureLogged) {
                            Log.w(TAG, "lib$skippedName.so did not load: ${e.message}")
                        }
                    }
                )
                loadedName = name
                Log.i(TAG, "loaded lib$name.so")
                true
            } catch (t: Throwable) {
                if (!failureLogged) {
                    failureLogged = true
                    Log.w(TAG, "native engines unavailable: $t")
                }
                false
            }
        }
    }

    /**
     * Starts [load] on a background thread unless the library is already in.
     * The first load maps both engines and runs llama.cpp's static
     * constructors, so a screen that is about to check the runtime on the
     * main thread calls this first. [load] is synchronized: a check that
     * arrives while this runs waits for it and does not load a second time.
     */
    fun warmUp() {
        if (loadedName != null) return
        Thread({ load() }, "native-warmup").apply { isDaemon = true }.start()
    }
}
