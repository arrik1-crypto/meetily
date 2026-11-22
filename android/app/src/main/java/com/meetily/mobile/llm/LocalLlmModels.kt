package com.meetily.mobile.llm

import android.content.Context
import com.meetily.mobile.data.DeviceFit
import com.meetily.mobile.data.ResumableDownload
import com.meetily.mobile.security.ModelIntegrity
import java.io.File

data class LocalLlmModel(
    val key: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeMb: Int,
    /**
     * Pinned SHA-256 of the file, when known. Null skips only the hash
     * comparison; the length check in [ModelIntegrity.verify] still runs.
     */
    val sha256: String? = null,
    /**
     * Pinned Hugging Face commit, when known: [downloadUrl] then fetches
     * `/resolve/<revision>/` instead of the mutable `/resolve/main/`.
     * Null keeps following main. See android/scripts/model_hashes.py.
     */
    val revision: String? = null,
    /**
     * The RAM its display name states ("12 GB+ RAM"), when it states one. It
     * can only raise [minRamGb] above what the formula estimates, so the
     * label and the picker's fit badge never disagree.
     */
    val documentedRamGb: Int? = null,
    /**
     * The most context, in tokens, this model is run with: 8192 for a small
     * model (a file up to DeviceFit.SMALL_LLM_MAX_MB, about 2B parameters),
     * 4096 for 3B and larger. A phone gets the larger window only where it
     * holds the model comfortably with the bigger KV cache
     * ([DeviceFit.llmPlan]); llama_jni.c then clamps it to the window the
     * model was trained on.
     */
    val contextTokens: Int = DeviceFit.defaultLlmContextTokens(sizeMb)
) {
    /** [url] with [revision] pinned in, when set. */
    val downloadUrl: String
        get() = ModelIntegrity.pinRevision(url, revision)

    /**
     * Estimated memory needs; see DeviceFit.llmWorkingSetMb for the formula.
     * Every model a gigabyte or larger counts as slow on a phone without
     * dot-product instructions, where every quantised matmul is emulated
     * (CpuFeatures): the two the catalogue calls fast are the ones below it.
     */
    val fitNeed: DeviceFit.Need
        get() = DeviceFit.llmNeed(
            key, sizeMb, documentedRamGb, slowOnBaseline = sizeMb >= 1000,
            contextTokens = contextTokens
        )

    /** Smallest phone, in GB of RAM, this is expected to run on (an estimate). */
    val minRamGb: Int get() = fitNeed.minRamGb
}

/** GGUF chat models for the embedded llama.cpp engine (filesDir/llm-models). */
object LocalLlmModels {

    val ALL: List<LocalLlmModel> = listOf(
        LocalLlmModel(
            "qwen2.5-1.5b",
            "Qwen 2.5 1.5B (recommended)",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1120
        ),
        LocalLlmModel(
            "qwen2.5-0.5b",
            "Qwen 2.5 0.5B (fastest, lighter answers)",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491
        ),
        LocalLlmModel(
            "llama3.2-3b",
            "Llama 3.2 3B (best quality, slower)",
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/" +
                "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            2020
        ),
        LocalLlmModel(
            "gemma3-1b",
            "Gemma 3 1B (fast, light)",
            "google_gemma-3-1b-it-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/" +
                "google_gemma-3-1b-it-Q4_K_M.gguf",
            800
        ),
        LocalLlmModel(
            "gemma3-4b",
            "Gemma 3 4B (great quality · 6 GB+ RAM)",
            "google_gemma-3-4b-it-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/" +
                "google_gemma-3-4b-it-Q4_K_M.gguf",
            2490,
            documentedRamGb = 6
        ),
        LocalLlmModel(
            "gemma4-e2b",
            "Gemma 4 E2B (newest Gemma · 8 GB+ RAM)",
            "gemma-4-E2B-it-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/" +
                "gemma-4-E2B-it-Q4_K_M.gguf",
            3110,
            documentedRamGb = 8
        ),
        // Google's own quantisation-aware-trained Q4_0 builds, opt-in next to
        // the unsloth files (which stay as they are). Text model only: the
        // repo's mmproj file is the vision tower, which nothing here loads.
        // Like the unsloth files, the GGUF asks for BOS (add_bos_token) and
        // its Gemma 4 template emits bos_token, so the prompt takes the same
        // path and ends up with one BOS: llama_jni.c adds it at tokenisation
        // only when the rendered prompt does not already start with it. Its
        // EOS is <eos> where unsloth's is <turn|>; llama.cpp ends generation
        // on both for Gemma 4. Unmeasured on phones against the unsloth
        // files, so neither is suggested over the other. Like them, they are
        // above DeviceFit.SMALL_LLM_MAX_MB and so set up for the 4K window
        // (contextTokens' default), far inside the window they were trained
        // on (131,072 tokens for E2B). On arm64 phones with dotprod their
        // Q4_0 bulk is the one catalogue bulk KleidiAI's dotprod/i8mm
        // kernels take (see CMakeLists.txt).
        LocalLlmModel(
            "gemma4-e2b-qat",
            "Gemma 4 E2B QAT (Google's Q4_0 · 8 GB+ RAM)",
            "gemma-4-E2B_q4_0-it.gguf",
            "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/" +
                "gemma-4-E2B_q4_0-it.gguf",
            3350,
            documentedRamGb = 8
        ),
        LocalLlmModel(
            "gemma4-e4b",
            "Gemma 4 E4B (excellent · 12 GB+ RAM)",
            "gemma-4-E4B-it-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/" +
                "gemma-4-E4B-it-Q4_K_M.gguf",
            4980,
            documentedRamGb = 12
        ),
        LocalLlmModel(
            "gemma4-e4b-qat",
            "Gemma 4 E4B QAT (Google's Q4_0 · 12 GB+ RAM)",
            "gemma-4-E4B_q4_0-it.gguf",
            "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/main/" +
                "gemma-4-E4B_q4_0-it.gguf",
            5155,
            documentedRamGb = 12
        ),
        LocalLlmModel(
            "mistral-7b",
            "Mistral 7B v0.3 (flagship phones · 12 GB+ RAM)",
            "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/" +
                "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            4370,
            documentedRamGb = 12
        ),
        LocalLlmModel(
            "llama3.1-8b",
            "Llama 3.1 8B (flagship phones · 12 GB+ RAM)",
            "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/" +
                "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            4920,
            documentedRamGb = 12
        ),
        // The smaller Qwen 3.5 sizes, opt-in next to the 9B and from the same
        // quantiser. Same architecture (qwen35) and template family: the
        // template's thinking switch is turned off at render time
        // (chat_render.cpp), and any think block that still appears is
        // stripped (PromptShaping). Text GGUFs only; the repos' mmproj files
        // are the vision tower. Not yet checked for summary quality or speed
        // on a phone, so the suggested default stays Qwen 2.5 1.5B. The 2B
        // file is under DeviceFit.SMALL_LLM_MAX_MB, so like the other small
        // models it is set up for the 8K window (contextTokens' default);
        // the 4B keeps 4K. Both are trained on 262,144 tokens, so
        // llama_jni.c's clamp to the trained window never applies.
        LocalLlmModel(
            "qwen35-2b",
            "Qwen 3.5 2B (newer, small · 4 GB+ RAM)",
            "Qwen_Qwen3.5-2B-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/" +
                "Qwen_Qwen3.5-2B-Q4_K_M.gguf",
            1396,
            documentedRamGb = 4
        ),
        LocalLlmModel(
            "qwen35-4b",
            "Qwen 3.5 4B (newer, mid-size · 8 GB+ RAM)",
            "Qwen_Qwen3.5-4B-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF/resolve/main/" +
                "Qwen_Qwen3.5-4B-Q4_K_M.gguf",
            3013,
            documentedRamGb = 8
        ),
        LocalLlmModel(
            "qwen3.5-9b",
            "Qwen 3.5 9B (flagship phones · 16 GB RAM)",
            "Qwen_Qwen3.5-9B-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Qwen_Qwen3.5-9B-GGUF/resolve/main/" +
                "Qwen_Qwen3.5-9B-Q4_K_M.gguf",
            5680,
            documentedRamGb = 16
        ),
        // Domain specialist — same engine, domain-tuned weights (a Gemma 3 4B
        // base, so its chat template is already proven here). Summaries
        // remain drafts, not clinical documents.
        //
        // SaulLM 7B (legal) was withdrawn: its Q4_K_M file was deleted from
        // the quantisation repo, so the download could only ever 404.
        // RecapApp moves anyone who had it selected onto a model that exists.
        LocalLlmModel(
            "medgemma-4b",
            "MedGemma 4B (medical · 6 GB+ RAM)",
            "medgemma-4b-it-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/medgemma-4b-it-GGUF/resolve/main/" +
                "medgemma-4b-it-Q4_K_M.gguf",
            2490,
            documentedRamGb = 6
        )
    )

    fun byKey(key: String): LocalLlmModel =
        ALL.firstOrNull { it.key == key } ?: ALL.first()

    /** [LocalLlmModel.fitNeed] by key; null for a key the catalogue lacks. */
    fun fitNeedOrNull(key: String): DeviceFit.Need? = ALL.firstOrNull { it.key == key }?.fitNeed

    fun dir(context: Context): File =
        File(context.filesDir, "llm-models").apply { mkdirs() }

    fun fileFor(context: Context, model: LocalLlmModel): File =
        File(dir(context), model.fileName)

    fun isDownloaded(context: Context, model: LocalLlmModel): Boolean {
        val file = fileFor(context, model)
        return file.exists() && file.length() > model.sizeMb * 1024L * 1024L * 9 / 10
    }

    fun delete(context: Context, model: LocalLlmModel) {
        val file = fileFor(context, model)
        file.delete()
        ResumableDownload.discardPartial(file)
    }

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Removes legacy `.part-*` leftovers and resumable `.part` files that
     * have sat untouched for a week; a recent `.part` is kept so the next
     * download resumes it (see [ResumableDownload]).
     */
    fun cleanPartials(context: Context) {
        ResumableDownload.cleanPartials(dir(context))
    }

    fun isRuntimeAvailable(): Boolean = LlamaBridge.load()

    /**
     * Blocking download with 0..100 progress; call from a worker thread.
     * Resumes an interrupted `.part` when the server supports it.
     */
    fun download(
        context: Context,
        model: LocalLlmModel,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false }
    ) {
        ResumableDownload.download(
            url = model.downloadUrl,
            target = fileFor(context, model),
            label = model.fileName,
            expectedSha256 = model.sha256,
            cancelled = cancelled,
            onBytes = ResumableDownload.percentReporter(onProgress)
        )
    }
}
