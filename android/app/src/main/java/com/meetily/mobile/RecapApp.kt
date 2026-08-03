package com.meetily.mobile

import android.app.Application
import android.os.Build
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.security.AppLock
import java.io.File

class RecapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, before anything that could crash: local-only crash capture
        // (writes a file, chains to the system handler — no telemetry). This
        // runs in EVERY process, deliberately, so a crash in the LiteRT
        // sandbox is captured the same way as one in the app.
        com.meetily.mobile.diag.CrashLog.install(this)

        // Everything below is app-process work.
        //
        // Application.onCreate runs once per PROCESS, not once per app, so
        // the :litert sandbox was re-running all of it: re-applying night
        // mode, installing the app lock, and — worst — re-arming every
        // reminder alarm from a second process. None of that belongs in a
        // process whose only job is to hold one inference engine, and any of
        // it throwing there takes the sandbox down before it can be bound,
        // which surfaces to the user as nothing but a bind timeout.
        if (!isMainProcess()) return

        // Night-mode preference is process-wide state; reapply on every start.
        ThemeManager.applyNightMode(AppSettings(this).themeMode)
        // App lock + FLAG_SECURE are enforced from lifecycle callbacks so
        // every activity is covered, including quick-tile entry points.
        AppLock.install(this)
        // Re-arm reminder/nudge alarms from persisted state (alarms are lost
        // on process death and app updates).
        Thread { com.meetily.mobile.reminders.Reminders.rescheduleAll(this) }.start()
        com.meetily.mobile.llm.LocalLlm.init(this)
        com.meetily.mobile.llm.LiteRtLlm.init(this)
    }

    /**
     * True in the app's own process, false in a `:suffix` one.
     *
     * getProcessName() is API 28+ and this app ships to API 26, so the
     * fallback reads /proc/self/cmdline. Failing safe means answering TRUE:
     * an unrecognised process gets the full, working initialisation rather
     * than a silently half-started app.
     */
    private fun isMainProcess(): Boolean {
        val name = currentProcessName() ?: return true
        return !name.contains(':')
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching { getProcessName() }.getOrNull()
        }
        return runCatching {
            // cmdline is NUL-separated and the process name is the first
            // entry. Written as an escape, not the byte: a literal NUL in a
            // source file makes it binary to git, grep and every diff.
            File("/proc/self/cmdline").readText()
                .substringBefore('\u0000')
                .trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // A loaded model is the biggest thing we hold; let it go first. Both
        // runtimes, because only one is selected but either may still be
        // holding weights from before the user switched.
        //
        // The LiteRT release also unbinds the sandbox, which is the only way
        // its memory actually comes back: a mmap'd GGUF's pages are
        // reclaimable by the kernel under pressure, but accelerator
        // allocations are not — the process has to go.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            com.meetily.mobile.llm.LocalLlm.release()
            com.meetily.mobile.llm.LiteRtLlm.release()
        }
    }
}
