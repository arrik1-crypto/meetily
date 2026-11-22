package com.meetily.mobile.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Kinds of heavy native work named in the process state summary. */
enum class NativeJob(val label: String) {
    TRANSCRIBE("transcribe"),
    SUMMARISE("summarise"),
    DIARISE("diarise"),
    OCR("ocr")
}

/**
 * The decisions behind ExitInfoCapture and NativeWork, kept free of Android
 * types so they are unit-tested: which process exits Android recorded are
 * worth a report, which of them are new, how a report section reads, and
 * how the process state summary is packed into its 128 bytes.
 */
object ExitInfoLogic {

    // Mirrors of ApplicationExitInfo.REASON_*. ExitInfoLogicTest pins the
    // named ones to the platform constants.
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_OTHER = 13

    /** ApplicationExitInfo.REASON_FREEZER, API 33. */
    const val REASON_FREEZER = 14

    /**
     * REASON_MEMORY_LIMITER: killed by the RAM-based app memory limiter that
     * Android 17 brings to every app. The constant arrived with the 37.2
     * SDK, so it does not exist at compileSdk 36; the value is the one the
     * platform documents, and referencing a constant that is not there
     * would not compile.
     */
    const val REASON_MEMORY_LIMITER = 17

    /**
     * REASON_ANOMALY: broke a system resource anomaly rule. Same release as
     * [REASON_MEMORY_LIMITER] and absent at compileSdk 36 for the same
     * reason; recorded because it is the newer sibling of
     * [REASON_EXCESSIVE_RESOURCE_USAGE].
     */
    const val REASON_ANOMALY = 18

    /**
     * Where Android 17 puts its memory-limiter kills while the dedicated
     * reason is not yet reported: REASON_OTHER with a description holding
     * "MemoryLimiter:AnonSwap". Matched on the stable prefix only, because
     * the platform makes no promise about the rest of the description.
     */
    const val MEMORY_LIMITER_MARK = "MemoryLimiter"

    // ActivityManager.RunningAppProcessInfo.IMPORTANCE_*, also pinned by the
    // test.
    const val IMPORTANCE_FOREGROUND = 100
    const val IMPORTANCE_FOREGROUND_SERVICE = 125
    const val IMPORTANCE_VISIBLE = 200
    const val IMPORTANCE_PERCEPTIBLE = 230
    const val IMPORTANCE_SERVICE = 300
    const val IMPORTANCE_TOP_SLEEPING = 325
    const val IMPORTANCE_CANT_SAVE_STATE = 350
    const val IMPORTANCE_CACHED = 400
    const val IMPORTANCE_GONE = 1000

    /** ActivityManager.setProcessStateSummary refuses anything longer. */
    const val SUMMARY_MAX_BYTES = 128

    /** Longest model key the summary carries; catalogue keys are far shorter. */
    const val SUMMARY_KEY_MAX = 40

    /**
     * Exits from before the first scan that still count as new. A stored
     * marker does not exist yet on the first launch of a build with this
     * capture, and without a window that launch would report every kill
     * still in Android's history, some of them weeks old, as "last time".
     * A day keeps the crash that came just before an update.
     */
    const val FIRST_RUN_WINDOW_MS = 24L * 60 * 60 * 1000

    /** Stored-marker value meaning "never scanned". */
    const val NO_MARKER = -1L

    /** Exits written into one report; matches the crash files kept. */
    const val MAX_PER_REPORT = 3

    const val ANR_TRACE_LINES = 200
    const val ANR_TRACE_CHARS = 24_000

    /** Raw tombstone bytes kept beside a report. */
    const val TOMBSTONE_CAP_BYTES = 512 * 1024

    private const val DESCRIPTION_CAP = 300
    private const val TRACE_LINE_CAP = 1_000

    /** One record from getHistoricalProcessExitReasons, Android types removed. */
    class Exit(
        /** Position in the list it came from, so the caller can find the record again. */
        val id: Int,
        val timestampMs: Long,
        val reason: Int,
        val status: Int,
        val importance: Int,
        val pssKb: Long,
        val rssKb: Long,
        val description: String?,
        val stateSummary: ByteArray?
    )

    fun reasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        REASON_LOW_MEMORY -> "LOW_MEMORY"
        4 -> "CRASH"
        REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        REASON_ANR -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        REASON_OTHER -> "OTHER"
        REASON_FREEZER -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        REASON_MEMORY_LIMITER -> "MEMORY_LIMITER"
        REASON_ANOMALY -> "ANOMALY"
        else -> "REASON_$reason"
    }

    /** What happened, in a phrase, for the report. */
    fun reasonPhrase(reason: Int, description: String?): String = when {
        reason == REASON_CRASH_NATIVE -> "Native crash"
        reason == REASON_ANR -> "Not responding (ANR)"
        reason == REASON_LOW_MEMORY -> "Killed by Android for low memory"
        reason == REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed by Android for excessive resource use"
        reason == REASON_FREEZER -> "Killed by Android's app freezer"
        reason == REASON_MEMORY_LIMITER || isMemoryLimiterOther(reason, description) ->
            "Killed by Android's app memory limit"
        reason == REASON_ANOMALY -> "Killed by Android for a resource anomaly"
        else -> "Process exit"
    }

    fun importanceName(importance: Int): String = when (importance) {
        IMPORTANCE_FOREGROUND -> "foreground"
        IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
        IMPORTANCE_VISIBLE -> "visible"
        IMPORTANCE_PERCEPTIBLE -> "perceptible"
        IMPORTANCE_SERVICE -> "background service"
        IMPORTANCE_TOP_SLEEPING -> "top, screen off"
        IMPORTANCE_CANT_SAVE_STATE -> "can't save state"
        IMPORTANCE_CACHED -> "cached"
        IMPORTANCE_GONE -> "gone"
        else -> "importance"
    }

    private fun isMemoryLimiterOther(reason: Int, description: String?): Boolean =
        reason == REASON_OTHER && description?.contains(MEMORY_LIMITER_MARK) == true

    /**
     * Whether an exit becomes a report.
     *
     * Java crashes are not here: CrashLog's handler already wrote those, and
     * a second report of the same death would only repeat it. A low-memory
     * kill of a process that was only cached is how Android normally frees
     * memory, not a fault, so it counts only when the process was at least
     * perceptible, or when it was doing heavy native work, which the state
     * summary says.
     */
    fun isRecorded(
        reason: Int,
        importance: Int,
        description: String?,
        hadStateSummary: Boolean
    ): Boolean = when (reason) {
        REASON_CRASH_NATIVE,
        REASON_ANR,
        REASON_EXCESSIVE_RESOURCE_USAGE,
        REASON_FREEZER,
        REASON_MEMORY_LIMITER,
        REASON_ANOMALY -> true
        REASON_OTHER -> isMemoryLimiterOther(reason, description)
        REASON_LOW_MEMORY -> importance <= IMPORTANCE_PERCEPTIBLE || hadStateSummary
        else -> false
    }

    /** The floor below which an exit has already been looked at. */
    fun consumedFloor(stored: Long, nowMs: Long): Long =
        if (stored == NO_MARKER) nowMs - FIRST_RUN_WINDOW_MS else stored

    /**
     * New exits worth a report: later than [consumedTs], recorded by
     * [isRecorded], newest first, at most [max].
     */
    fun select(exits: List<Exit>, consumedTs: Long, max: Int = MAX_PER_REPORT): List<Exit> =
        exits.asSequence()
            .filter { it.timestampMs > consumedTs }
            .filter {
                isRecorded(
                    it.reason, it.importance, it.description,
                    it.stateSummary?.isNotEmpty() == true
                )
            }
            .sortedByDescending { it.timestampMs }
            .take(max)
            .toList()

    /**
     * The marker to store after a scan: the newest exit Android returned,
     * recorded or not, so each one is looked at once. Never moves back.
     */
    fun newestTimestamp(exits: List<Exit>, consumedTs: Long): Long =
        maxOf(consumedTs, exits.maxOfOrNull { it.timestampMs } ?: consumedTs)

    /** Total RAM rounded up to whole GiB; phones report a little under the label. */
    fun ramGb(totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        val gib = 1L shl 30
        return ((totalBytes + gib - 1) / gib).toInt().coerceAtMost(9_999)
    }

    /** Model key reduced to characters that cannot carry anything but a name. */
    fun sanitiseKey(key: String): String {
        val cleaned = buildString {
            for (c in key) {
                append(
                    if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                        c == '.' || c == '-' || c == '_'
                    ) c else '_'
                )
                if (length >= SUMMARY_KEY_MAX) break
            }
        }
        return cleaned.ifEmpty { "?" }
    }

    /**
     * Packs the active jobs into at most [SUMMARY_MAX_BYTES] of ASCII:
     * `ram=8;transcribe:parakeet-tdt-v3;diarise:eres2net`. Entries that do
     * not fit are dropped from the end and counted as `;+N`. Only the job
     * kind, the catalogue model key and the RAM size ever go in: no titles,
     * text or paths.
     */
    fun encodeSummary(jobs: List<Pair<NativeJob, String>>, ramGb: Int): ByteArray {
        val head = "ram=${ramGb.coerceIn(0, 9_999)}"
        val entries = jobs.map { (job, key) -> ";${job.label}:${sanitiseKey(key)}" }.distinct()
        val kept = mutableListOf<String>()
        var length = head.length
        for (entry in entries) {
            if (length + entry.length > SUMMARY_MAX_BYTES) break
            kept += entry
            length += entry.length
        }
        var dropped = entries.size - kept.size
        if (dropped > 0) {
            while (kept.isNotEmpty() && length + ";+$dropped".length > SUMMARY_MAX_BYTES) {
                length -= kept.removeAt(kept.size - 1).length
                dropped++
            }
        }
        val text = buildString {
            append(head)
            kept.forEach { append(it) }
            if (dropped > 0) append(";+").append(dropped)
        }
        return text.toByteArray(Charsets.US_ASCII)
    }

    /** A stored summary as report text: printable ASCII only, or null when empty. */
    fun decodeSummary(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return buildString {
            for (b in bytes.take(SUMMARY_MAX_BYTES)) {
                val c = b.toInt() and 0xff
                append(if (c in 0x20..0x7e) c.toChar() else '?')
            }
        }
    }

    /** The first lines of a text trace, capped by lines and characters. */
    fun traceHead(
        lines: Sequence<String>,
        maxLines: Int = ANR_TRACE_LINES,
        maxChars: Int = ANR_TRACE_CHARS
    ): String {
        val out = StringBuilder()
        var count = 0
        var cut = false
        for (raw in lines) {
            // A single runaway line (a dumped buffer) must not hide the rest.
            val line = if (raw.length > TRACE_LINE_CAP) raw.take(TRACE_LINE_CAP) + "..." else raw
            if (count >= maxLines || out.length + line.length + 1 > maxChars) {
                cut = true
                break
            }
            out.append(line).append('\n')
            count++
        }
        if (cut) out.append("[trace cut at ").append(count).append(" lines]\n")
        return out.toString()
    }

    /** The line CrashLog.render puts where a Java crash names its thread. */
    fun headline(exits: List<Exit>): String =
        "Exit: " + exits.joinToString(", ") { reasonName(it.reason) } +
            " (recorded by Android after Recap closed)"

    /**
     * One exit as a report section.
     *
     * [updatedSince]: the app was updated after this exit, so the version in
     * the report header is not the one that died. [trace] is the ANR trace
     * head or the decoded tombstone, already capped.
     */
    fun renderExit(exit: Exit, updatedSince: Boolean, trace: String?): String = buildString {
        append("Reason: ").append(reasonPhrase(exit.reason, exit.description))
        append(" (").append(reasonName(exit.reason)).append(", ").append(exit.reason).append(")\n")
        append("When: ").append(utc(exit.timestampMs)).append('\n')
        if (updatedSince) {
            append("Note: Recap was updated after this; it happened on an earlier version.\n")
        }
        append("Status: ").append(exit.status).append('\n')
        append("Importance: ").append(importanceName(exit.importance))
        append(" (").append(exit.importance).append(")\n")
        append("Memory at last sample: PSS ").append(kbToMb(exit.pssKb))
        append(" MB, RSS ").append(kbToMb(exit.rssKb)).append(" MB\n")
        exit.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("Description: ").append(it.take(DESCRIPTION_CAP)).append('\n')
        }
        append("Native work: ").append(decodeSummary(exit.stateSummary) ?: "none recorded")
        append('\n')
        if (!trace.isNullOrBlank()) {
            append('\n').append(trace.trimEnd()).append('\n')
        }
    }

    private fun kbToMb(kb: Long): Long = if (kb <= 0L) 0L else (kb + 512) / 1024

    fun utc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))
}

/**
 * The heavy native work in progress, by token, in the order it began. Pure
 * bookkeeping behind NativeWork, which adds the lock and the system call.
 */
class ActiveNativeJobs {
    private val active = LinkedHashMap<Int, Pair<NativeJob, String>>()
    private var next = 1

    /** Starts tracking one job; the token ends it. Never returns 0. */
    fun begin(job: NativeJob, modelKey: String): Int {
        val token = next
        next = if (next == Int.MAX_VALUE) 1 else next + 1
        active[token] = job to modelKey
        return token
    }

    /** True when [token] was active; ending one twice is harmless. */
    fun end(token: Int): Boolean = active.remove(token) != null

    val isEmpty: Boolean get() = active.isEmpty()

    /** The bytes for setProcessStateSummary, or null to clear it. */
    fun summary(ramGb: Int): ByteArray? =
        if (active.isEmpty()) null
        else ExitInfoLogic.encodeSummary(active.values.toList(), ramGb)
}
