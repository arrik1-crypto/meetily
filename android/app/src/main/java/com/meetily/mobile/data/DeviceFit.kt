package com.meetily.mobile.data

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Which on-device models suit this phone: a memory fit for every model in
 * the pickers, and the transcription and summary models to suggest on first
 * run.
 *
 * Every figure here is an ESTIMATE. What a phone really lets one app hold
 * depends on the manufacturer's memory killer, on what else is running and
 * on the length of the meeting, so the verdicts drive warnings and
 * suggestions only. Nothing is ever refused because of them.
 *
 * Pure (no Android), so it is unit-tested; DeviceProbe gathers the inputs.
 */
object DeviceFit {

    enum class Level { FITS, TIGHT, TOO_BIG }

    /** Which build of the ggml engines this phone loads; see CpuFeatures. */
    enum class CpuTier {
        /**
         * Plain armv8-a, or x86_64: every quantised dot product in
         * whisper.cpp and llama.cpp is emulated.
         */
        BASELINE,

        /** Every core has dot-product and fp16 SIMD: the fast build loads. */
        DOTPROD;

        companion object {
            fun of(dotprodFp16: Boolean): CpuTier = if (dotprodFp16) DOTPROD else BASELINE
        }
    }

    enum class Kind { TRANSCRIPTION, SPEAKER_ID, LLM }

    /** One model's estimated needs. Build it with [transcriptionNeed] or [llmNeed]. */
    data class Need(
        val key: String,
        val kind: Kind,
        val sizeMb: Int,
        /** Estimated memory the model holds while it runs, MB. */
        val workingSetMb: Int,
        /** Smallest phone (GB of RAM, as sold) it is expected to run on at all. */
        val minRamGb: Int,
        /** Smallest phone it is expected to run on comfortably. */
        val comfortableRamGb: Int,
        val englishOnly: Boolean,
        /** Slow on a [CpuTier.BASELINE] phone; never suggested there. */
        val slowOnBaseline: Boolean
    )

    /** What is known about the phone. */
    data class Device(
        /** ActivityManager.MemoryInfo.totalMem: what the kernel can use. */
        val totalMemBytes: Long,
        /**
         * MemoryInfo.advertisedMem (API 34+), or null. Only ever shown to the
         * user: the verdicts all come from [totalMemBytes], so they mean the
         * same thing on every Android version.
         */
        val advertisedMemBytes: Long?,
        val tier: CpuTier,
        /**
         * Room the app can allocate for downloads, or null when unknown.
         * Only the first-run suggestion reads it; see [recommend].
         */
        val allocatableBytes: Long?,
        /** Locale language code, such as "en" or "de". */
        val language: String
    ) {
        /** The RAM size this phone was most likely sold as; see [ramClassGb]. */
        val ramGb: Int get() = ramClassGb(totalMemBytes)

        /** The RAM to tell the user about: advertised when known. */
        val displayRamGb: Int
            get() = advertisedMemBytes?.let { advertisedGb(it) }?.takeIf { it > 0 } ?: ramGb

        val englishLocale: Boolean get() = language.equals("en", ignoreCase = true)
    }

    /** [need] measured against a phone with [deviceRamGb] of RAM. */
    data class Verdict(
        val level: Level,
        val need: Need,
        val deviceRamGb: Int,
        /** The model is slow on this phone's processor. */
        val slowHere: Boolean
    )

    /** Why a suggestion is not the first choice on the list. */
    enum class Limit {
        /** It is: nothing held it back. */
        NONE,

        /** A better model on the list does not fit in this phone's memory. */
        MEMORY,

        /** A better model would fit, but is slow on this phone's processor. */
        PROCESSOR
    }

    data class Pick(val verdict: Verdict, val limit: Limit) {
        val need: Need get() = verdict.need
    }

    data class Recommendation(
        val transcription: Pick?,
        val llm: Pick?,
        /**
         * A summary model suits the phone's memory but was left out because
         * the measured free space ([Device.allocatableBytes]) cannot hold it
         * as well as the transcription model.
         */
        val llmLeftOutForSpace: Boolean = false
    )

    // --- RAM classes ---------------------------------------------------------

    /**
     * RAM sizes phones are sold with, in GB. totalMem is always below the
     * size on the box (the kernel, the modem and the GPU keep some back), so
     * rounding UP to the next of these recovers it: 5.5 GiB is a 6 GB phone,
     * 11.2 GiB a 12 GB one. 10 is listed so that a 12 GB phone with an
     * unusually large carve-out reads as 10, never as 16: when this is wrong
     * it should under-promise.
     */
    private val RAM_CLASSES_GB = intArrayOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32)

    private const val GIB = 1024.0 * 1024.0 * 1024.0

    /** The RAM size, in GB, of a phone reporting [totalMemBytes]; 0 when unknown. */
    fun ramClassGb(totalMemBytes: Long): Int {
        if (totalMemBytes <= 0L) return 0
        val gib = totalMemBytes / GIB
        for (size in RAM_CLASSES_GB) if (gib <= size) return size
        return ceil(gib).toInt()
    }

    /**
     * advertisedMem as whole GB. Manufacturers report it either in binary
     * units (8 GiB) or in decimal ones (8,000,000,000 bytes); the binary
     * reading is taken when it lands on a whole number, the decimal one
     * otherwise.
     */
    internal fun advertisedGb(bytes: Long): Int {
        if (bytes <= 0L) return 0
        val binary = bytes / GIB
        val nearest = Math.round(binary)
        if (nearest > 0 && abs(binary - nearest) < 0.05) return nearest.toInt()
        return Math.round(bytes / 1e9).toInt()
    }

    // --- Memory needs (estimates) --------------------------------------------

    /**
     * Engine overhead of an on-device LLM on top of its weights, MB: the KV
     * cache at LocalLlm.N_CTX (4096 tokens, q8_0 keys and values with flash
     * attention in llama_jni.c, f16 for both if that is rejected), roughly
     * 60 MB for Qwen 2.5 1.5B and 290 MB for the 7-8B models (115 MB and
     * 540 MB at f16), plus llama.cpp's compute buffers for a 512-token batch
     * and the engine itself. An estimate for the q8_0 cache the engine
     * normally gets.
     */
    const val LLM_RUNTIME_MB = 500

    /**
     * Engine overhead of a transcription model, MB: whisper.cpp's encoder
     * and decoder state, or onnxruntime's arena for sherpa-onnx, and the
     * recording pipeline around it.
     */
    const val ASR_RUNTIME_MB = 300

    /**
     * Share of the phone's RAM one model can take and still run at all.
     * Android, the launcher and whatever the user had open hold the rest, and
     * the memory killer closes background apps first and the foreground one
     * last. Checked against the RAM labels the model catalogue already
     * carried: with this share and [llmWorkingSetMb] it reproduces "6 GB+"
     * for Gemma 3 4B, "8 GB+" for Gemma 4 E2B and "12 GB+" for Gemma 4 E4B
     * and Llama 3.1 8B. Mistral 7B and Qwen 3.5 9B come out one size lower
     * than their labels, which then win (see [minRamGb]).
     */
    private const val MIN_SHARE = 0.55

    /** Share of the phone's RAM a model can take without crowding it. */
    private const val COMFORT_SHARE = 0.40

    /**
     * Estimated working set of a GGUF chat model, MB: its whole file, since
     * every weight is read for every token and so ends up resident even
     * though it is memory-mapped; 10% on top for dequantisation scratch and
     * allocator slack; and [LLM_RUNTIME_MB].
     *
     * On phones with dotprod, the arm64 engine's KleidiAI kernels also
     * requantise a Q4_K_M file's Q6_K matmul weights to int8 at load, held
     * in anonymous memory at 1.22x their size: up to about 9% of the file
     * for the catalogue's models (+270 MB for Llama 3.1 8B), out of the same
     * 10%. Loading also takes a temporary int8 copy of each of those weights
     * in turn, rows x cols bytes, the largest being the output head: 501 MiB
     * for Llama 3.1 8B, 640 MiB for Gemma 3 4B. That comes before the KV
     * cache and compute buffers exist, so it competes with [LLM_RUNTIME_MB]
     * and the 10% for room instead of adding to them: well inside them for
     * Llama 3.1 8B, about their size for Gemma 3 4B. None of this has been
     * measured; see ACCELERATION_FINDINGS.md.
     *
     * Not covered: KleidiAI's SME/SME2 kernels. On an SME phone they hold a
     * second packed copy of every Q6_K and Q8_0 weight (about 2.44x their
     * Q6_K size, +1.8 GB for Llama 3.1 8B) and move the Q4_K weights into
     * anonymous memory too. The engine keeps them off (MEETILY_KLEIDIAI_SME
     * in CMakeLists.txt); they need a bigger allowance here before they are
     * turned on.
     */
    fun llmWorkingSetMb(sizeMb: Int): Int = sizeMb + sizeMb / 10 + LLM_RUNTIME_MB

    /**
     * Estimated working set of a transcription model, MB: the weights, about
     * as much again in activations (whisper.cpp's encoder buffers are sized
     * by the architecture rather than the quantised file, and onnxruntime's
     * arena grows to the longest chunk), and [ASR_RUNTIME_MB].
     */
    fun transcriptionWorkingSetMb(sizeMb: Int): Int = sizeMb * 2 + ASR_RUNTIME_MB

    /**
     * The smallest phone, in GB of RAM, a model with [workingSetMb] is
     * expected to run on: the first RAM size where it is no more than
     * [MIN_SHARE] of the total. [documentedGb] is a requirement the catalogue
     * states (a "12 GB+ RAM" label); it can only raise the figure.
     */
    fun minRamGb(workingSetMb: Int, documentedGb: Int? = null): Int =
        maxOf(smallestSizeFor(workingSetMb, MIN_SHARE), documentedGb ?: 0)

    /** The smallest phone it runs on comfortably: [COMFORT_SHARE] of the total. */
    fun comfortableRamGb(workingSetMb: Int, minRamGb: Int): Int =
        maxOf(smallestSizeFor(workingSetMb, COMFORT_SHARE), minRamGb)

    private fun smallestSizeFor(workingSetMb: Int, share: Double): Int {
        for (size in RAM_CLASSES_GB) if (workingSetMb <= size * 1024 * share) return size
        return ceil(workingSetMb / (1024 * share)).toInt()
    }

    fun transcriptionNeed(
        key: String,
        sizeMb: Int,
        englishOnly: Boolean,
        slowOnBaseline: Boolean
    ): Need {
        val workingSet = transcriptionWorkingSetMb(sizeMb)
        val min = minRamGb(workingSet)
        return Need(
            key, Kind.TRANSCRIPTION, sizeMb, workingSet, min,
            comfortableRamGb(workingSet, min), englishOnly, slowOnBaseline
        )
    }

    /**
     * A speaker-ID model: the transcription formula, since it runs on the
     * same onnxruntime. These are small enough to fit almost anything; they
     * carry a badge so every picker reads the same way.
     */
    fun speakerIdNeed(key: String, sizeMb: Int): Need {
        val workingSet = transcriptionWorkingSetMb(sizeMb)
        val min = minRamGb(workingSet)
        return Need(
            key, Kind.SPEAKER_ID, sizeMb, workingSet, min,
            comfortableRamGb(workingSet, min), englishOnly = false, slowOnBaseline = false
        )
    }

    fun llmNeed(key: String, sizeMb: Int, documentedRamGb: Int?, slowOnBaseline: Boolean): Need {
        val workingSet = llmWorkingSetMb(sizeMb)
        val min = minRamGb(workingSet, documentedRamGb)
        return Need(
            key, Kind.LLM, sizeMb, workingSet, min,
            comfortableRamGb(workingSet, min), englishOnly = false, slowOnBaseline = slowOnBaseline
        )
    }

    // --- Verdicts ------------------------------------------------------------

    /** How [need] fits [device]; null when the phone's RAM is unknown. */
    fun classify(need: Need, device: Device): Verdict? {
        val ram = device.ramGb
        if (ram <= 0) return null
        val level = when {
            ram < need.minRamGb -> Level.TOO_BIG
            ram < need.comfortableRamGb -> Level.TIGHT
            else -> Level.FITS
        }
        return Verdict(level, need, ram, need.slowOnBaseline && device.tier == CpuTier.BASELINE)
    }

    /**
     * True when choosing a model in a picker should first warn about its
     * fit: it is tight or too big, and it is not already the working model
     * (re-picking that changes nothing). Only ever a warning: the user can
     * always go ahead.
     */
    fun warnsOnChoice(verdict: Verdict?, alreadyActive: Boolean): Boolean =
        verdict != null && verdict.level != Level.FITS && !alreadyActive

    // --- First-run suggestions -------------------------------------------------

    /**
     * Transcription models a first run may suggest, best first. Small Q is
     * what WhisperModels calls the practical sweet spot on phones; base and
     * tiny step down for memory or processor. English-only models are
     * suggested only for an English locale, where they are the more accurate
     * of a same-sized pair, and a multilingual one everywhere else. NeMo
     * models, Parakeet Ultra included, are chosen by hand only.
     */
    private val TRANSCRIPTION_ENGLISH = listOf("small.en-q5_1", "base.en", "tiny.en")
    private val TRANSCRIPTION_MULTILINGUAL = listOf("small-q5_1", "base", "tiny")

    /**
     * Summary models a first run may suggest, best first: the one the
     * catalogue labels "recommended", then the two it calls fast. The opt-in
     * entries (Qwen 3.5 2B and 4B, Gemma 4 QAT) stay off this list until
     * they have been checked for summary quality and speed on a phone.
     */
    private val LLM_ORDER = listOf("qwen2.5-1.5b", "gemma3-1b", "qwen2.5-0.5b")

    /** Keys a first run may suggest for [language]'s locale, best first. */
    fun transcriptionCandidates(language: String): List<String> =
        if (language.equals("en", ignoreCase = true)) TRANSCRIPTION_ENGLISH
        else TRANSCRIPTION_MULTILINGUAL

    fun llmCandidates(): List<String> = LLM_ORDER

    /**
     * The suggestions for [device]. [needs] resolves a key to its [Need]
     * (null for a key no catalogue has).
     *
     * Transcription: the first candidate that fits comfortably, else the
     * first that is tight, since without one a meeting is audio only. The
     * LLM is optional (the built-in summariser works without one), so only a
     * comfortable fit is suggested. Neither ever suggests a model that is
     * too big, nor one that is slow on this phone's processor.
     *
     * Storage, when known: the LLM is left out unless the free space holds
     * both downloads (DownloadSpace's size plus 10% each, full sizes, since
     * on a first run neither is on disk yet). The transcription model is
     * suggested whatever the space; the free-space check before the
     * download then says how much is missing.
     */
    fun recommend(device: Device, needs: (String) -> Need?): Recommendation {
        val transcription = choose(
            transcriptionCandidates(device.language), device, needs, allowTight = true
        )
        val llm = choose(llmCandidates(), device, needs, allowTight = false)
        val room = device.allocatableBytes
        if (llm != null && room != null) {
            val both = DownloadSpace.requiredBytes(
                listOfNotNull(transcription?.need?.sizeMb, llm.need.sizeMb)
            )
            if (both > room) return Recommendation(transcription, null, llmLeftOutForSpace = true)
        }
        return Recommendation(transcription, llm)
    }

    private fun choose(
        keys: List<String>,
        device: Device,
        needs: (String) -> Need?,
        allowTight: Boolean
    ): Pick? {
        val verdicts = keys.mapNotNull { key -> needs(key)?.let { classify(it, device) } }
        if (verdicts.isEmpty()) return null
        val chosen = bestOf(verdicts.filter { !it.slowHere }, allowTight) ?: return null
        val limit = when {
            chosen.need.key == verdicts.first().need.key -> Limit.NONE
            // What memory alone would have allowed, had the processor not ruled
            // anything out: if that is better than the pick, the processor is
            // what held it back.
            bestOf(verdicts, allowTight)?.need?.key != chosen.need.key -> Limit.PROCESSOR
            else -> Limit.MEMORY
        }
        return Pick(chosen, limit)
    }

    private fun bestOf(verdicts: List<Verdict>, allowTight: Boolean): Verdict? =
        verdicts.firstOrNull { it.level == Level.FITS }
            ?: if (allowTight) verdicts.firstOrNull { it.level == Level.TIGHT } else null
}
