package com.meetily.mobile.diag

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Privacy-preserving crash capture: when the app dies, the stack trace is
 * written to a LOCAL file and nothing else happens — no network, no SDK,
 * no telemetry. On the next launch the user is offered the report to share
 * through their own share sheet (or ignore). Play's Android Vitals still
 * receives the OS-level crash signal because the previous (system) handler
 * is always chained.
 */
object CrashLog {

    private const val DIR = "crashlogs"
    private const val KEEP = 3
    private const val PREFS = "crashlog"
    private const val PREF_CONSUMED = "consumed"

    /** Max characters offered into the share sheet. */
    private const val SHARE_CAP = 100_000

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(app, thread, throwable)
            } catch (_: Throwable) {
                // Never let logging break crash handling itself.
            }
            // Chain to the system handler: crash dialog + Android Vitals.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, e: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // Keep the newest few; this write becomes the newest.
        dir.listFiles()
            ?.sortedByDescending { it.name }
            ?.drop(KEEP - 1)
            ?.forEach { it.delete() }
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
            render(
                deviceInfo(context),
                "Thread: ${thread.name}",
                trace.toString()
            )
        )
    }

    /** Pure formatting; unit-tested. */
    fun render(deviceInfo: String, threadLine: String, trace: String): String =
        buildString {
            append("Recap crash report\n")
            append("==================\n")
            append(deviceInfo)
            append('\n')
            append(threadLine)
            append("\n\n")
            append(trace.trim())
            append('\n')
        }

    /** Version + device block, also used by manual bug reports. */
    fun deviceInfo(context: Context): String {
        val (versionName, versionCode) = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            (info.versionName ?: "?") to info.longVersionCode
        } catch (_: Exception) {
            "?" to -1L
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        return buildString {
            append("App: Recap $versionName ($versionCode)\n")
            append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}\n")
            append("Time: $stamp")
        }
    }

    /** The newest crash the user hasn't been asked about yet. */
    fun pendingCrash(context: Context): File? {
        val newest = File(context.filesDir, DIR)
            .listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") }
            ?.maxByOrNull { it.name }
            ?: return null
        val consumed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_CONSUMED, "")
        return if (newest.name == consumed) null else newest
    }

    /** Ask once per crash, whether the user shared or dismissed. */
    fun markConsumed(context: Context, file: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_CONSUMED, file.name).apply()
    }

    /** Crash file content bounded for the share sheet. */
    fun shareText(file: File): String = try {
        file.readText().take(SHARE_CAP)
    } catch (_: Exception) {
        ""
    }

    /** Body for a manual "report a bug" share: env info + latest trace. */
    fun bugReportText(context: Context): String = buildString {
        append("Describe the problem here:\n\n\n")
        append("--- Environment ---\n")
        append(deviceInfo(context))
        append('\n')
        val latest = File(context.filesDir, DIR)
            .listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") }
            ?.maxByOrNull { it.name }
        if (latest != null) {
            append("\n--- Most recent crash (${latest.name}) ---\n")
            append(shareText(latest))
        }
    }.take(SHARE_CAP)
}
