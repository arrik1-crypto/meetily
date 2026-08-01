package com.recap.spike

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.io.File

/**
 * The measurement itself.
 *
 * Deliberately NOT a tokens-per-second benchmark. The question this spike
 * exists to answer is whether moving the summariser off llama.cpp would help
 * a phone that lost 25% of its battery in a 38-minute meeting — so the
 * headline number is microamp-hours consumed per summary, taken from the
 * battery fuel gauge, with tokens/sec as supporting detail.
 *
 * Speed alone would be misleading: an accelerator that is twice as fast at
 * three times the power is a regression for this product, and a
 * tokens-per-second chart would call it a win.
 */
object Harness {

    /**
     * A realistic unit of work: roughly the length of one map-reduce section
     * that LocalLlm feeds the model today, asking for the same shape of
     * output. Benchmarking a two-line prompt would measure startup, not
     * summarisation.
     */
    private val SECTION = buildString {
        append("Transcript section from a 40-minute operations meeting.\n\n")
        val turns = listOf(
            "Priya: The equipment tracking rollout is behind because the asset tags " +
                "we ordered came in the wrong format and have to be reprinted.",
            "Dan: How far behind are we talking? The compliance review is the 14th.",
            "Priya: Two weeks if the reprint runs clean. I can compress it to one if " +
                "we skip the pilot floor and go straight to the main building.",
            "Marcus: I would not skip the pilot. Last time we did that we found three " +
                "reader dead zones only after the whole floor was tagged.",
            "Dan: Agreed, keep the pilot. Priya, can you get the reprint order in today " +
                "so the clock starts?",
            "Priya: Yes. I will also ask them to ship partial so we can start the pilot " +
                "before the full run lands.",
            "Marcus: On the reader dead zones, I want someone monitoring the three " +
                "seventeen access point specifically, that was the worst one.",
            "Dan: Put that on the main team. Anything else on equipment?",
            "Priya: Only that we still have no owner for the training request system. " +
                "It keeps coming up and nobody has picked it up.",
            "Dan: Leave it unowned for now and flag it at the next staff meeting."
        )
        // Repeated to reach a section-sized prompt without inventing filler
        // that would not tokenise like speech.
        repeat(6) { round ->
            appendLine("--- segment ${round + 1} ---")
            turns.forEach { appendLine(it) }
            appendLine()
        }
        append(
            "\nWrite a summary with these sections: Summary, Key Decisions, " +
                "Action Items, Open Questions. Under 300 words."
        )
    }

    val promptChars: Int get() = SECTION.length

    class Run(
        val backend: String,
        val loadMs: Long,
        val runs: List<Long>,
        val replyChars: List<Int>,
        val microAmpHours: Long,
        val thermalBefore: Int,
        val thermalAfter: Int,
        val notes: List<String>,
        val sampleReply: String
    ) {
        private val medianMs: Long get() = runs.sorted()[runs.size / 2]

        /**
         * Approximate: the runtime is not asked for a token count, so this is
         * derived from reply length. Fine for comparison against llama.cpp
         * measured the same way, useless as an absolute.
         */
        private val approxTokensPerSec: Double
            get() {
                val chars = replyChars.sorted()[replyChars.size / 2]
                return if (medianMs > 0) (chars / 3.6) / (medianMs / 1000.0) else 0.0
            }

        fun report(): String = buildString {
            appendLine("LiteRT-LM spike — result")
            appendLine("========================")
            appendLine("device      : ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("backend     : $backend")
            appendLine("prompt      : $promptChars chars")
            appendLine()
            appendLine("model load  : ${loadMs} ms")
            appendLine("generations : ${runs.joinToString(", ") { "${it} ms" }}")
            appendLine("median      : ${medianMs} ms")
            appendLine("reply chars : ${replyChars.joinToString(", ")}")
            appendLine("approx tok/s: ${"%.1f".format(approxTokensPerSec)}  (from reply length)")
            appendLine()
            appendLine("BATTERY     : ${microAmpHours} µAh for ${runs.size} generations")
            if (runs.isNotEmpty() && microAmpHours > 0) {
                appendLine("            : ~${microAmpHours / runs.size} µAh per summary")
            } else {
                appendLine("            : gauge reported nothing usable — see note below")
            }
            appendLine("thermal     : $thermalBefore -> $thermalAfter (0 = none, 6 = shutdown)")
            appendLine()
            appendLine("binding notes:")
            notes.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("first 400 chars of reply:")
            appendLine(sampleReply.take(400))
        }
    }

    /**
     * Fuel-gauge reading. CHARGE_COUNTER is cumulative µAh and is the only
     * property here with the resolution to see a single generation; the
     * percentage-based ones move too coarsely to measure anything under a
     * few minutes.
     */
    private fun chargeCounter(context: Context): Long {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 0
        return try {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        } catch (_: Throwable) {
            0
        }
    }

    private fun thermal(context: Context): Int {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return -1
        return try {
            pm.currentThermalStatus
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Loads the model, discards a warmup generation, then times [rounds] of
     * them. The warmup matters: the first call pays lazy initialisation and
     * page-in costs that no later summary will.
     */
    fun measure(
        context: Context,
        model: File,
        backend: String,
        rounds: Int = 3,
        onProgress: (String) -> Unit
    ): Result<Run> {
        onProgress("binding runtime…")
        val loadStart = SystemClock.elapsedRealtime()
        val binding = LlmRuntime.bind(context, model.absolutePath, backend)
            .getOrElse { return Result.failure(it) }
        val loadMs = SystemClock.elapsedRealtime() - loadStart

        onProgress("warmup…")
        val warm = try {
            binding.generate.invoke(binding.instance, SECTION) as? String
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        if (warm.isNullOrBlank()) {
            return Result.failure(IllegalStateException("warmup returned nothing"))
        }

        val thermalBefore = thermal(context)
        // Read the gauge AFTER warmup so model load and page-in are excluded
        // from the per-summary energy figure.
        val chargeBefore = chargeCounter(context)

        val times = mutableListOf<Long>()
        val chars = mutableListOf<Int>()
        var last = warm
        for (i in 1..rounds) {
            onProgress("run $i of $rounds…")
            val t0 = SystemClock.elapsedRealtime()
            val reply = try {
                binding.generate.invoke(binding.instance, SECTION) as? String
            } catch (t: Throwable) {
                return Result.failure(t)
            }
            times += SystemClock.elapsedRealtime() - t0
            chars += reply?.length ?: 0
            if (!reply.isNullOrBlank()) last = reply
        }

        val chargeAfter = chargeCounter(context)
        val thermalAfter = thermal(context)
        // The counter decreases while discharging; charging inverts it, which
        // is why the UI tells the user to unplug.
        val used = (chargeBefore - chargeAfter).coerceAtLeast(0L)

        return Result.success(
            Run(
                backend = backend,
                loadMs = loadMs,
                runs = times,
                replyChars = chars,
                microAmpHours = used,
                thermalBefore = thermalBefore,
                thermalAfter = thermalAfter,
                notes = binding.notes,
                sampleReply = last
            )
        )
    }
}
