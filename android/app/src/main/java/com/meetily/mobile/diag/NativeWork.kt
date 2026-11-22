package com.meetily.mobile.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Tells Android what heavy native work is in progress, so the record it
 * keeps of this process's death says so (ActivityManager
 * setProcessStateSummary, API 30+). ExitInfoCapture prints it in the report
 * of a native crash, ANR or memory kill on the next start.
 *
 * Every model load calls [begin] just before it starts and [end] once the
 * model is freed, from wherever that happens; the summary always lists the
 * jobs still running. It holds the job kind, the catalogue model key and
 * the phone's RAM in GB, nothing else: Android keeps it with the exit
 * record and the platform asks for no sensitive data in it.
 *
 * The system call runs on a single worker in order, so callers on the main
 * thread (a teardown, a memory-pressure release) never wait on the binder.
 * [begin] off the main thread waits briefly for its update to land, because
 * the load that follows can take the process down within milliseconds.
 */
object NativeWork {

    private const val BEGIN_WAIT_MS = 250L

    private val lock = Any()
    private val active = ActiveNativeJobs()
    private var ramGb = 0
    private var published: ByteArray? = null

    @Volatile
    private var activityManager: ActivityManager? = null

    private val publisher: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "process-state").apply { isDaemon = true }
        }
    }

    /** From RecapApp.onCreate. Below API 30 nothing is ever tracked. */
    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        activityManager = context.applicationContext
            .getSystemService(ActivityManager::class.java)
    }

    /**
     * Marks [job] on [modelKey] as running. Returns the token for [end]; 0
     * when nothing is tracked, which [end] ignores.
     */
    fun begin(job: NativeJob, modelKey: String): Int {
        val am = activityManager ?: return 0
        val (token, update) = synchronized(lock) {
            if (ramGb == 0) ramGb = totalRamGb(am)
            val started = active.begin(job, modelKey)
            started to publishLocked(am)
        }
        if (update != null && Looper.myLooper() != Looper.getMainLooper()) {
            try {
                update.get(BEGIN_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // Late is still better than not at all; the load goes ahead.
            }
        }
        return token
    }

    /** Ends what [begin] started. Idempotent, and never blocks. */
    fun end(token: Int) {
        if (token == 0) return
        val am = activityManager ?: return
        synchronized(lock) {
            if (active.end(token)) publishLocked(am)
        }
    }

    /** Caller holds [lock], which keeps the queued updates in state order. */
    private fun publishLocked(am: ActivityManager): Future<*>? {
        val bytes = active.summary(ramGb)
        if (bytes contentEquals published) return null
        published = bytes
        return try {
            publisher.submit {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        am.setProcessStateSummary(bytes)
                    }
                } catch (_: Throwable) {
                    // The system may throttle this call with a
                    // RuntimeException; a missed update only costs a line
                    // in a future report.
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun totalRamGb(am: ActivityManager): Int = try {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        ExitInfoLogic.ramGb(info.totalMem)
    } catch (_: Throwable) {
        0
    }
}
