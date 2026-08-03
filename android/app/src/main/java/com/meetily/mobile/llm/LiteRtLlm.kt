package com.meetily.mobile.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.llm.litert.ILiteRtCallback
import com.meetily.mobile.llm.litert.ILiteRtEngine
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App-side face of the LiteRT-LM runtime. Mirrors [LocalLlm]'s contract so
 * LlmClient can route to either without knowing which is loaded.
 *
 * All the real work happens in [com.meetily.mobile.llm.litert.LiteRtService],
 * in its own process. What lives here is the binding, the deferred-release
 * protocol, and the translation of a dead sandbox into an ordinary
 * exception the existing catch sites already handle.
 */
object LiteRtLlm {

    /** Long enough for a cold model load on a slow accelerator path. */
    private const val BIND_TIMEOUT_SECONDS = 30L

    private const val MAX_REPLY_TOKENS = 700

    @Volatile
    var stageListener: ((Int, Int) -> Unit)? = null

    private var appContext: Context? = null

    @Volatile
    private var engine: ILiteRtEngine? = null

    @Volatile
    private var bound = false

    /**
     * Serializes generations, and — as in [LocalLlm] — lets [release] decline
     * to wait. Release is called from the main thread on memory pressure and
     * from Settings; generation holds this for minutes.
     */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    @Volatile
    private var releasePending = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engine = ILiteRtEngine.Stub.asInterface(service)
            bindFailure = null
            connectLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The sandbox died — an OOM kill, or a native crash we
            // deliberately kept out of this process. Drop the handle so the
            // next call rebinds rather than throwing DeadObjectException
            // forever.
            engine = null
            connectLatch?.countDown()
        }

        /**
         * The sandbox process died before it could hand back a binder, or
         * could not be created at all.
         *
         * Without these two, every such failure was indistinguishable from a
         * slow start: the client simply waited out its timeout and reported
         * "did not start in time", which says nothing about why and invites
         * the user to blame the model they just spent twenty minutes
         * downloading. They stop the wait AND name the cause.
         */
        override fun onBindingDied(name: ComponentName?) {
            engine = null
            bound = false
            bindFailure = "the engine process stopped as it was starting"
            connectLatch?.countDown()
        }

        override fun onNullBinding(name: ComponentName?) {
            bindFailure = "the engine process started but refused the connection"
            connectLatch?.countDown()
        }
    }

    @Volatile
    private var connectLatch: CountDownLatch? = null

    /** Why the last bind attempt failed, when the system told us. */
    @Volatile
    private var bindFailure: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** True when the on-device engine is selected AND set to this runtime. */
    fun isSelected(): Boolean {
        val context = appContext ?: return false
        val settings = AppSettings(context)
        return EngineRouting.resolve(settings.llmEngine, settings.localLlmRuntime) ==
            EngineRouting.Target.LITERT
    }

    /** True when a model has been imported and the runtime can be attempted. */
    fun isReady(context: Context): Boolean = LiteRtModels.isImported(context)

    /**
     * Loads the model and returns the backend actually selected ("cpu",
     * "gpu", "tensor"). Blocking; call from a worker thread. Used by Settings
     * so the user finds out here rather than minutes into a summary.
     */
    fun probe(context: Context): String {
        val model = LiteRtModels.current(context)
            ?: throw IllegalStateException("No LiteRT model imported yet")
        val settings = AppSettings(context)
        lock.lock()
        try {
            val remote = connect(context)
            val selected = remote.probe(
                model.absolutePath,
                settings.litertBackend,
                threadBudget()
            ).orEmpty().ifBlank { AppSettings.BACKEND_CPU }
            settings.litertLastBackend = selected
            return selected
        } catch (e: DeadObjectException) {
            throw sandboxDied(e)
        } finally {
            if (releasePending) freeLocked()
            lock.unlock()
        }
    }

    /**
     * Runs one chat completion. [messages] is the same OpenAI-shaped array
     * LlmClient builds for every other engine.
     */
    fun chat(messages: JSONArray, allowMapReduce: Boolean = true): String {
        val context = appContext
            ?: throw IllegalStateException("On-device AI is not initialized")
        val model = LiteRtModels.current(context)
            ?: throw IllegalStateException(
                "No LiteRT model imported — add one in Settings"
            )
        val settings = AppSettings(context)
        val pairs = toPairs(messages)
        if (pairs.isEmpty()) throw IllegalStateException("Nothing to summarise")

        lock.lock()
        try {
            releasePending = false
            val remote = connect(context)

            val selected = remote.probe(
                model.absolutePath,
                settings.litertBackend,
                threadBudget()
            ).orEmpty().ifBlank { AppSettings.BACKEND_CPU }
            settings.litertLastBackend = selected

            val callback = object : ILiteRtCallback.Stub() {
                override fun onSection(index: Int, total: Int) {
                    stageListener?.invoke(index, total)
                }
            }

            val reply = remote.generate(
                pairs.map { it.first }.toTypedArray(),
                pairs.map { it.second }.toTypedArray(),
                MAX_REPLY_TOKENS,
                threadBudget(),
                // Map-reduce runs in the sandbox, next to the engine doing
                // the section passes; this only says whether it may run.
                // LlmClient turns it off for short-answer calls.
                allowMapReduce,
                callback
            )
            if (reply.isNullOrBlank()) {
                throw IllegalStateException("LiteRT returned an empty response")
            }
            return reply
        } catch (e: DeadObjectException) {
            throw sandboxDied(e)
        } finally {
            if (releasePending) freeLocked()
            lock.unlock()
        }
    }

    /**
     * The sandbox process died mid-run.
     *
     * Rewritten into IllegalStateException on purpose: every catch site in
     * the app catches Exception, and DeadObjectException is one, so the
     * summary falls back to the extractive path exactly as it would for any
     * other engine failure. Containing the crash was the reason for the
     * separate process; this is where that containment is cashed in.
     */
    private fun sandboxDied(cause: Throwable): IllegalStateException {
        engine = null
        return IllegalStateException(
            "The LiteRT engine stopped unexpectedly — it may have run out of memory " +
                "for this model.",
            cause
        )
    }

    /** Caller must hold [lock]. Binds on first use and after a sandbox death. */
    private fun connect(context: Context): ILiteRtEngine {
        engine?.let { return it }

        val latch = CountDownLatch(1)
        connectLatch = latch
        bindFailure = null
        if (!bound) {
            val intent = Intent(context, com.meetily.mobile.llm.litert.LiteRtService::class.java)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                throw IllegalStateException("Could not start the LiteRT engine")
            }
        }
        val signalled = latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        engine?.let { return it }

        // Everything below is a failure, and the whole point is to say which
        // one. Prefer what the system told us over the fact that we waited.
        bindFailure?.let { reason ->
            bound = false
            throw IllegalStateException(
                "The LiteRT engine could not start — $reason. This usually means the " +
                    "runtime is not supported on this device; the standard engine still " +
                    "works."
            )
        }
        throw IllegalStateException(
            if (signalled) {
                "The LiteRT engine started but did not connect."
            } else {
                "The LiteRT engine did not start within ${BIND_TIMEOUT_SECONDS}s."
            }
        )
    }

    /**
     * Frees the engine. NEVER BLOCKS — same contract as [LocalLlm.release],
     * and for the same reason: callers include the main thread via
     * onTrimMemory and Settings, while a generation holds the lock for
     * minutes.
     */
    fun release() {
        if (!lock.tryLock()) {
            releasePending = true
            return
        }
        try {
            freeLocked()
        } finally {
            lock.unlock()
        }
    }

    /** Caller must hold [lock]. */
    private fun freeLocked() {
        val context = appContext
        runCatching { engine?.release() }
        if (bound && context != null) {
            runCatching { context.unbindService(connection) }
        }
        // Unbinding the last client lets Android reclaim the whole sandbox
        // process, which is the only way accelerator memory actually comes
        // back — unlike a mmap'd GGUF, it is not reclaimable under pressure.
        bound = false
        engine = null
        releasePending = false
    }

    private fun threadBudget(): Int =
        com.meetily.mobile.data.HeavyWork.batchThreads(
            com.meetily.mobile.RecordingService.isRunning
        )

    private fun toPairs(messages: JSONArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until messages.length()) {
            val obj = messages.optJSONObject(i) ?: continue
            val role = obj.optString("role", "user").ifBlank { "user" }
            val content = obj.optString("content", "")
            if (content.isNotBlank()) out.add(role to content)
        }
        return out
    }
}
