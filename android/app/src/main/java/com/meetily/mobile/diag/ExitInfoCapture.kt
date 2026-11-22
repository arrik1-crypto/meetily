package com.meetily.mobile.diag

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.io.InputStream

/**
 * Local capture of the deaths CrashLog's handler cannot see: native crashes
 * in whisper.cpp, llama.cpp and sherpa-onnx, ANRs, and kills for memory or
 * resource use. Android 11+ keeps a record of each process exit; on every
 * start this reads the ones it has not seen (ActivityManager
 * getHistoricalProcessExitReasons), and turns those worth reporting into a
 * crash report in CrashLog's folder, offered through the same share prompt.
 * Nothing is sent anywhere: the report waits on the phone for the user.
 *
 * Which exits count, and how a report reads, is decided in ExitInfoLogic.
 */
object ExitInfoCapture {

    private const val PREFS = "exitinfo"

    /** Timestamp of the newest exit already looked at. */
    private const val PREF_NEWEST = "newest_exit_ms"

    /** Android keeps a short ring of records per package; this covers it. */
    private const val READ_MAX = 16

    /** Longest the launch prompts wait for a scan still running. */
    private const val PROMPT_WAIT_MS = 2_000L

    private val lock = Any()
    private var scanned = false
    private val waiters = mutableListOf<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** From RecapApp.onCreate: one scan per process, off the main thread. */
    fun start(app: Application) {
        Thread({
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) scan(app)
            } catch (_: Throwable) {
                // Diagnostics must never take the app down with them.
            } finally {
                synchronized(lock) { scanned = true }
                main.post { drain() }
            }
        }, "exit-info").start()
    }

    /**
     * Runs [callback] on the main thread once this process's scan has
     * written any report it found, so the launch prompt can offer it now
     * rather than next time. Immediately when the scan is done; otherwise
     * when it finishes, or after [PROMPT_WAIT_MS] at the latest.
     *
     * Callbacks run in the order given, which MainActivity relies on to put
     * the crash offer before the resume prompt: one given while earlier ones
     * still wait for the drain the scan posted goes behind them, even when
     * the scan has just finished.
     */
    fun whenScanned(callback: () -> Unit) {
        var done = false
        val now = synchronized(lock) {
            done = scanned
            val runNow = scanned && waiters.isEmpty()
            if (!runNow) waiters += callback
            runNow
        }
        when {
            now -> callback()
            done -> main.post { drain() }
            else -> main.postDelayed({ drain() }, PROMPT_WAIT_MS)
        }
    }

    private fun drain() {
        val ready = synchronized(lock) { waiters.toList().also { waiters.clear() } }
        ready.forEach { it() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun scan(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val floor = ExitInfoLogic.consumedFloor(
            prefs.getLong(PREF_NEWEST, ExitInfoLogic.NO_MARKER),
            System.currentTimeMillis()
        )
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, READ_MAX)
        val exits = infos.mapIndexed { index, info ->
            ExitInfoLogic.Exit(
                id = index,
                timestampMs = info.timestamp,
                reason = info.reason,
                status = info.status,
                importance = info.importance,
                pssKb = info.pss,
                rssKb = info.rss,
                description = info.description,
                stateSummary = info.processStateSummary
            )
        }
        val chosen = ExitInfoLogic.select(exits, floor)
        if (chosen.isNotEmpty()) writeReport(context, chosen, infos)
        // After the report, so a scan cut short looks at the same exits again.
        prefs.edit().putLong(PREF_NEWEST, ExitInfoLogic.newestTimestamp(exits, floor)).commit()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun writeReport(
        context: Context,
        chosen: List<ExitInfoLogic.Exit>,
        infos: List<ApplicationExitInfo>
    ) {
        val base = CrashLog.newExitReportBase()
        val updatedAt = lastUpdateTime(context)
        val attachments = LinkedHashMap<String, ByteArray>()
        val sections = chosen.mapIndexed { n, exit ->
            val info = infos[exit.id]
            val trace = when (exit.reason) {
                ExitInfoLogic.REASON_ANR -> anrTrace(info)
                ExitInfoLogic.REASON_CRASH_NATIVE ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        tombstone(info, "$base.tombstone${n + 1}.pb", attachments)
                    } else {
                        null
                    }
                else -> null
            }
            val section = ExitInfoLogic.renderExit(exit, updatedAt > exit.timestampMs, trace)
            if (chosen.size > 1) "--- Exit ${n + 1} of ${chosen.size} ---\n$section" else section
        }
        CrashLog.saveExitReport(
            context,
            base,
            ExitInfoLogic.headline(chosen),
            sections.joinToString("\n"),
            attachments
        )
    }

    /**
     * The ANR trace Android took before the kill, if it kept one: its opening
     * and the main thread's stack (ExitInfoLogic.anrFocus), capped.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun anrTrace(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use { stream ->
            val head = ExitInfoLogic.traceHead(
                ExitInfoLogic.anrFocus(stream.bufferedReader(Charsets.UTF_8).lineSequence())
            )
            "--- ANR trace (opening and main thread, " +
                "${ExitInfoLogic.ANR_TRACE_LINES} lines at most) ---\n$head"
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Saves the raw tombstone beside the report as [name] (capped), and
     * returns its decoded summary for the report text.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun tombstone(
        info: ApplicationExitInfo,
        name: String,
        attachments: MutableMap<String, ByteArray>
    ): String? = try {
        info.traceInputStream?.use { stream ->
            val (bytes, cut) = readCapped(stream, ExitInfoLogic.TOMBSTONE_CAP_BYTES)
            if (bytes.isEmpty()) return@use null
            attachments[name] = bytes
            buildString {
                append(
                    TombstoneText.summarise(bytes)
                        ?: "Tombstone could not be decoded.\n"
                )
                append("Full tombstone: ").append(name).append(" (")
                append(bytes.size / 1024).append(" KB")
                if (cut) append(", cut at ").append(ExitInfoLogic.TOMBSTONE_CAP_BYTES / 1024).append(" KB")
                append("), kept on this phone and not part of this text.\n")
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** Up to [cap] bytes of [stream], and whether there was more. */
    private fun readCapped(stream: InputStream, cap: Int): Pair<ByteArray, Boolean> {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (buffer.size() < cap) {
            val n = stream.read(chunk, 0, minOf(chunk.size, cap - buffer.size()))
            if (n < 0) return buffer.toByteArray() to false
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray() to (stream.read() >= 0)
    }

    @Suppress("DEPRECATION")
    private fun lastUpdateTime(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (_: Exception) {
        0L
    }
}
