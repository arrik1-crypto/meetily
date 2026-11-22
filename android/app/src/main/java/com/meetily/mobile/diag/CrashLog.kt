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
 *
 * Deaths this handler never sees (native crashes, ANRs, memory kills) reach
 * the same folder and the same prompt through ExitInfoCapture, which reads
 * Android's own record of them on the next start.
 */
object CrashLog {

    private const val DIR = "crashlogs"
    private const val KEEP = 3
    private const val PREFS = "crashlog"
    private const val PREF_CONSUMED = "consumed"

    /** Max characters offered into the share sheet. */
    private const val SHARE_CAP = 100_000

    /**
     * Ends the name of a report built from Android's exit records (see
     * ExitInfoCapture) rather than from this process's own handler.
     */
    private const val EXIT_SUFFIX = "-exit"
    private const val REPORT_EXT = ".txt"

    /**
     * Serialises writes and pruning: the exit-record scan writes from a
     * worker thread while the crash handler can fire on any other.
     */
    private val lock = Any()

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
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
        synchronized(lock) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            prune(dir)
            File(dir, "crash-${System.currentTimeMillis()}$REPORT_EXT").writeText(
                render(
                    deviceInfo(context),
                    "Thread: ${thread.name}",
                    trace.toString()
                )
            )
        }
    }

    /** Name (without extension) for a new exit-record report. */
    fun newExitReportBase(): String = "crash-${System.currentTimeMillis()}$EXIT_SUFFIX"

    /**
     * Writes a report built from Android's exit records, plus files it
     * refers to by name (a raw tombstone). Named by the time it is written,
     * not the time of the exit, so it sorts after every report already
     * shown: [pendingCrash] offers the newest one.
     */
    fun saveExitReport(
        context: Context,
        base: String,
        headline: String,
        body: String,
        attachments: Map<String, ByteArray>
    ) {
        synchronized(lock) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            prune(dir)
            for ((name, bytes) in attachments) {
                File(dir, name).writeBytes(bytes)
            }
            File(dir, base + REPORT_EXT).writeText(render(deviceInfo(context), headline, body))
        }
    }

    /** True for a report made from Android's exit records. */
    fun isExitReport(file: File): Boolean = file.name.endsWith(EXIT_SUFFIX + REPORT_EXT)

    /** Report files only, oldest first (names sort by time). Internal for tests. */
    internal fun reports(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(REPORT_EXT) }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Keeps the newest few reports, and the files beside them, so the write
     * that follows makes it [KEEP]. A file belongs to the report whose name
     * it starts with up to the first dot (`crash-1-exit.tombstone1.pb` to
     * `crash-1-exit.txt`); anything else in the folder goes. Internal for
     * tests.
     */
    internal fun prune(dir: File) {
        val kept = reports(dir).takeLast(KEEP - 1)
            .map { it.name.substringBefore('.') }
            .toSet()
        dir.listFiles()?.forEach { file ->
            if (file.name.substringBefore('.') !in kept) file.delete()
        }
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
            // getLongVersionCode is API 28; minSdk is 26. Calling it on 8.x
            // throws NoSuchMethodError, which the catch below must also see.
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            (info.versionName ?: "?") to code
        } catch (_: Throwable) {
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
        val newest = reports(File(context.filesDir, DIR)).lastOrNull() ?: return null
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
        val latest = reports(File(context.filesDir, DIR)).lastOrNull()
        if (latest != null) {
            append("\n--- Most recent crash (${latest.name}) ---\n")
            append(shareText(latest))
        }
    }.take(SHARE_CAP)
}
