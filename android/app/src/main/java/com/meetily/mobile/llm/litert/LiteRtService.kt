package com.meetily.mobile.llm.litert

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.meetily.mobile.llm.PromptShaping

/**
 * Hosts LiteRT-LM in its own process (`:litert`, declared in the manifest).
 *
 * Nothing else in the app runs here. That is the whole point: this process
 * loads a 21 MB native library carrying its own statically-linked tensor
 * runtime, and the main process runs whisper.cpp and llama.cpp over a
 * deliberately SHARED ggml. Keeping them in separate address spaces is what
 * stops a symbol clash from surfacing as a SIGSEGV inside transcription.
 *
 * It is a plain bound Service, not a foreground one. The foreground service
 * with its notification, wake lock and Android 15 six-hour budget stays in
 * the main process where SummaryService already manages it; adding a second
 * one here would spend the same per-app budget twice for one summary.
 */
class LiteRtService : Service() {

    /**
     * Created on first use, not as a field.
     *
     * As a field initializer this ran during Service construction, and
     * constructing it resolves the LiteRT classes. If that resolution fails
     * — a missing library, an ABI with no `.so`, a
     * `NoClassDefFoundError` — the Service never finishes being created, so
     * onBind is never reached and the client sees no callback at all. The
     * user gets a bind timeout and no reason, which is the least useful
     * failure this could possibly have.
     *
     * Deferring it puts the same failure inside a method that can throw
     * across the binder, where it arrives as a sentence the user can read.
     */
    private val lock = Any()
    private var bindingOrNull: LiteRtBinding? = null

    private fun binding(): LiteRtBinding {
        bindingOrNull?.let { return it }
        return try {
            LiteRtBinding().also { bindingOrNull = it }
        } catch (t: Throwable) {
            // Throwable: absent native code raises Error, not Exception.
            Log.e(TAG, "LiteRT runtime failed to load", t)
            throw IllegalStateException(
                "The LiteRT runtime could not load on this device " +
                    "(${t.javaClass.simpleName}). Its models will not run here.",
                t
            )
        }
    }

    private val impl = object : ILiteRtEngine.Stub() {

        override fun probe(
            modelPath: String?,
            requestedBackend: String?,
            threadBudget: Int
        ): String = synchronized(lock) {
            binding().load(
                modelPath.orEmpty(),
                requestedBackend.orEmpty(),
                threadBudget.coerceAtLeast(1)
            )
        }

        override fun generate(
            roles: Array<out String>?,
            contents: Array<out String>?,
            maxReplyTokens: Int,
            threadBudget: Int,
            allowMapReduce: Boolean,
            callback: ILiteRtCallback?
        ): String? = synchronized(lock) {
            val engine = binding()
            val messages = zip(roles, contents)
            if (messages.isEmpty()) return@synchronized null

            val charBudget = PromptShaping.charBudget(engine.contextTokens)
            var working = messages

            // Same map-reduce the llama.cpp path uses, from the same
            // implementation — a second copy would drift, and the drift shows
            // up as one engine quietly losing the middle of a long meeting.
            val longest = working.indices.maxByOrNull { working[it].second.length }
            if (allowMapReduce && longest != null &&
                working[longest].second.length > PromptShaping.mapReduceThreshold(
                    engine.contextTokens
                )
            ) {
                val condensed = PromptShaping.condense(
                    content = working[longest].second,
                    charBudget = charBudget,
                    generate = { msgs, tokens -> engine.generate(msgs, tokens) },
                    onSection = { index, total -> report(callback, index, total) }
                )
                if (condensed != null) {
                    working = working.toMutableList()
                        .also { it[longest] = it[longest].first to condensed }
                    report(callback, 0, 0)
                }
            }

            val budgeted = PromptShaping.budgetMessages(working, charBudget)
            PromptShaping.stripThinking(engine.generate(budgeted, maxReplyTokens))
                .ifBlank { null }
        }

        // Nothing to free if it never loaded; do not construct it in order
        // to tear it down.
        override fun release() = synchronized(lock) {
            bindingOrNull?.release()
            Unit
        }
    }

    /**
     * A dead app process must not take generation down with it, and a slow
     * callback must not stall the run. Progress is advisory; the summary is
     * not.
     */
    private fun report(callback: ILiteRtCallback?, index: Int, total: Int) {
        try {
            callback?.onSection(index, total)
        } catch (t: Throwable) {
            Log.w(TAG, "progress callback failed", t)
        }
    }

    private fun zip(
        roles: Array<out String>?,
        contents: Array<out String>?
    ): List<Pair<String, String>> {
        if (roles == null || contents == null) return emptyList()
        val n = minOf(roles.size, contents.size)
        return (0 until n)
            .map { (roles[it].ifBlank { "user" }) to contents[it] }
            .filter { it.second.isNotBlank() }
    }

    override fun onBind(intent: Intent?): IBinder = impl

    override fun onDestroy() {
        runCatching { synchronized(lock) { bindingOrNull?.release() } }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiteRtService"
    }
}
