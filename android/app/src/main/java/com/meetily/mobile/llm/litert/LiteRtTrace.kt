package com.meetily.mobile.llm.litert

import android.content.Context
import android.util.Log
import java.io.File

/**
 * A breadcrumb trail across the process boundary.
 *
 * v3.12.1 fixed two real startup bugs and the sandbox still did not come up,
 * with a signature that rules a lot out: bindService returned true, and then
 * neither onServiceConnected nor onBindingDied nor onNullBinding fired for
 * thirty seconds. The bind was accepted and the system went quiet.
 *
 * Nothing observable from the app process can tell those apart — a sandbox
 * that never started, one that started and stalled, and one that died before
 * binding all look identical from the client's side of a latch. Guessing
 * again would cost another build per guess.
 *
 * So the sandbox writes where it got to, and the app reads it back. Plain
 * file append, because it has to survive the writer's process dying: a
 * binder call cannot report the failure that killed the process making it,
 * and SharedPreferences is explicitly not multi-process safe. It also lands
 * in logcat, so `adb logcat -s LiteRtTrace` shows the same trail live.
 *
 * Cheap to leave in. Failure here must never break anything, so every
 * operation swallows its own errors — a diagnostic that can throw is a
 * second bug wearing the first one's clothes.
 */
object LiteRtTrace {

    private const val TAG = "LiteRtTrace"
    private const val FILE = "litert-boot.log"

    /** Keeps the file from growing without bound across many attempts. */
    private const val MAX_BYTES = 16 * 1024

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Called by the app before binding, so each attempt starts clean. */
    fun begin(context: Context) {
        runCatching { file(context).writeText("") }
        mark(context, "app: binding")
    }

    fun mark(context: Context, stage: String) {
        Log.i(TAG, stage)
        runCatching {
            val f = file(context)
            if (f.length() > MAX_BYTES) f.writeText("")
            f.appendText("${android.os.SystemClock.elapsedRealtime()} $stage\n")
        }
    }

    fun fail(context: Context, stage: String, t: Throwable) {
        Log.e(TAG, stage, t)
        // Type and message only. This is read back into a user-facing
        // string, and a full stack trace there is noise, not information.
        mark(context, "$stage FAILED: ${t.javaClass.simpleName}: ${t.message}")
    }

    /** Everything the sandbox managed to record, oldest first. */
    fun read(context: Context): List<String> =
        runCatching {
            file(context).readLines()
                .mapNotNull { it.substringAfter(' ', "").takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())

    /**
     * One sentence naming how far the sandbox got, for the error the user
     * actually sees.
     */
    fun summarise(context: Context): String {
        val stages = read(context)
        val failure = stages.lastOrNull { it.contains("FAILED") }
        if (failure != null) return failure

        return when {
            stages.none { it.startsWith("sandbox:") } ->
                "the engine process never started — nothing ran in it at all"
            stages.none { it.contains("service created") } ->
                "the engine process started but its service was never created"
            stages.none { it.contains("bound") } ->
                "the engine service was created but never finished binding"
            else ->
                "the engine bound but the app was never told (last step: " +
                    "${stages.last()})"
        }
    }
}
